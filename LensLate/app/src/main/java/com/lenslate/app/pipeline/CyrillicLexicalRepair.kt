package com.lenslate.app.pipeline

/**
 * Repairs invented Cyrillic words in the automated lab's lexical pass.
 *
 * Low-contrast posters make Tesseract invent words that keep a plausible vowel
 * pattern ("ЧЕМНОТЩ", "ОТПИЧЭЮТСЯ", "ДЗРЖИТСЯ"). Structural plausibility accepts
 * them, and the translator then transliterates them into Latin gibberish. The
 * repair only touches words that are absent from the offline lexicon and that
 * the model already suspects, then searches a small edit beam for a known word.
 * Rare but real vocabulary and well-formed invented terms stay untouched.
 *
 * This is deliberately lab-only: the regular scan never rewrites recognized
 * text. The model is evidence for choosing a reading, never a free rewriter.
 */
internal object CyrillicLexicalRepair {

    fun repair(text: String): String {
        if (text.isBlank()) return text
        var budget = MAX_REPAIRS_PER_REGION
        return cleanup(text).lines().joinToString("\n") { line ->
            var previous: String? = null
            CYRILLIC_WORD.replace(line) { match ->
                val token = match.value
                val replacement = if (budget <= 0) null else repairWord(token, previous)
                if (replacement != null) budget--
                previous = token
                replacement ?: token
            }
        }
    }

    /**
     * Removes the debris that a tight crop picks up around a block: symbol runs
     * between glyphs, slashes glued to letters, standalone stray letters ("Ё")
     * and lines that contain no word or number at all.
     */
    private fun cleanup(text: String): String {
        val joined = text
            .replace(NBSP, ' ')
            .replace(SLASH_SINGLE, " ")
            .replace(SLASH_NOISE, " ")
            .replace(SYMBOLS_INSIDE_WORD, "")
            .replace(STRAY_SINGLE_LETTER, " ")
            .replace(SYMBOL_RUN, " ")
        return joined
            .lines()
            .map { line -> line.replace(SPACE_BEFORE_PUNCT, "$1").replace(MULTI_SPACE, " ").trim() }
            .filter { line -> line.isNotEmpty() && isMeaningfulLine(line) }
            .joinToString("\n")
    }

    private fun isMeaningfulLine(line: String): Boolean {
        if (line.any { it == MIDDLE_DOT || it == BULLET }) return true
        val word = WORD_TOKEN.find(line)
        if (word != null) {
            val letters = word.value.count { it.isLetter() }
            if (letters >= MIN_MEANINGFUL_WORD) return true
        }
        return NUMBER_TOKEN.containsMatchIn(line) ||
            LATIN_TOKEN.containsMatchIn(line) ||
            line.any { it == '—' || it == '–' }
    }

    private fun repairWord(token: String, previous: String?): String? {
        if (token.length < MIN_REPAIR_LENGTH) return null
        val normalized = RussianLanguageModel.normalize(token)
        if (RussianLanguageModel.isKnownWord(normalized)) return null
        val cost = RussianLanguageModel.wordCost(normalized)
        val near = RussianLanguageModel.nearCorrections(normalized)
        // "Э" is too rare in Russian to appear in an unknown word by chance;
        // an unknown word that contains it is almost always a misread "Е"/"А".
        val suspiciousLetter = normalized.any { it == 'э' }
        if (cost < SUSPECT_COST && near.isEmpty() && !suspiciousLetter) return null
        val maxEdits = when {
            cost >= HARD_COST && normalized.length >= HARD_WORD_LENGTH -> 3
            cost >= MEDIUM_COST && normalized.length >= MEDIUM_WORD_LENGTH -> 2
            else -> 1
        }
        val found = search(normalized, maxEdits)
        val best = found.minWithOrNull(
            compareBy<Candidate> { it.edits }
                // A cut-off word is usually completed by the lost letter
                // ("ТЕМНОТ" -> "ТЕМНОТА"), so an extension that keeps the whole
                // source wins over dropping its last letter.
                .thenBy { candidate ->
                    if (candidate.word.length > normalized.length &&
                        candidate.word.startsWith(normalized)
                    ) {
                        0
                    } else {
                        1
                    }
                }
                .thenByDescending { it.visual }
                .thenByDescending { agreesWithContext(previous, it.word) }
                .thenBy { it.cost },
        ) ?: return null
        if (best.cost > MAX_CANDIDATE_COST) return null
        val strong = best.word in near
        if (strong) {
            if (best.cost > cost + MAX_COST_REGRESSION) return null
        } else if (best.cost > cost - MIN_COST_GAIN) {
            return null
        }
        return applyCase(token, best.word)
    }

    /**
     * Beam search over visually plausible edits. Arbitrary substitutions are not
     * generated - only print confusions and vowel insertions - because a full
     * alphabet neighbourhood at depth three becomes both slow and inventive.
     */
    private fun search(source: String, maxEdits: Int): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        var frontier = listOf(Candidate(source, 0, 0, RussianLanguageModel.wordCost(source)))
        RussianLanguageModel.nearCorrections(source)
            .filter(RussianLanguageModel::isSolidWord)
            .forEach { word ->
                found[word] = Candidate(
                    word = word,
                    edits = 1,
                    visual = 1,
                    cost = RussianLanguageModel.wordCost(word),
                )
            }
        var depth = 0
        while (depth < maxEdits && frontier.isNotEmpty()) {
            val next = LinkedHashMap<String, Candidate>()
            for (node in frontier) {
                for (edit in edits(node.word)) {
                    val word = edit.word
                    if (word == source) continue
                    if (word.length < MIN_CANDIDATE_LENGTH ||
                        word.length > source.length + maxEdits ||
                        word.length > MAX_CANDIDATE_LENGTH
                    ) {
                        continue
                    }
                    val candidate = Candidate(
                        word = word,
                        edits = node.edits + 1,
                        visual = node.visual + edit.visual,
                        cost = RussianLanguageModel.wordCost(word),
                    )
                    if (RussianLanguageModel.isSolidWord(word)) {
                        val existing = found[word]
                        if (existing == null ||
                            candidate.edits < existing.edits ||
                            (candidate.edits == existing.edits &&
                                candidate.visual > existing.visual)
                        ) {
                            found[word] = candidate
                        }
                    } else if (candidate.cost <= MAX_EXPANSION_COST) {
                        val existing = next[word]
                        if (existing == null ||
                            candidate.visual > existing.visual ||
                            (candidate.visual == existing.visual &&
                                candidate.cost < existing.cost)
                        ) {
                            next[word] = candidate
                        }
                    }
                }
            }
            frontier = next.values
                .sortedWith(compareByDescending<Candidate> { it.visual }.thenBy { it.cost })
                .take(BEAM_WIDTH)
            depth++
        }
        return found.values.toList()
    }

    private fun edits(word: String): List<Edit> {
        val result = ArrayList<Edit>(word.length * 8 + (word.length + 1) * VOWELS.length)
        for (index in word.indices) {
            CONFUSIONS[word[index]]?.forEach { letter ->
                result += Edit(
                    word = word.substring(0, index) + letter + word.substring(index + 1),
                    visual = 1,
                )
            }
        }
        for (index in word.indices) {
            if (word.length > MIN_CANDIDATE_LENGTH) {
                result += Edit(word.removeRange(index, index + 1), 1)
            }
        }
        for (index in 0..word.length) {
            VOWELS.forEach { letter ->
                result += Edit(word.substring(0, index) + letter + word.substring(index), 1)
            }
        }
        for (index in 0 until word.lastIndex) {
            if (word[index] != word[index + 1]) {
                result += Edit(
                    word = word.substring(0, index) + word[index + 1] + word[index] +
                        word.substring(index + 2),
                    visual = 0,
                )
            }
        }
        return result
    }

    /**
     * Neighbouring words disambiguate equally cheap completions: "ПОЛНАЯ
     * ТЕМНОТ" wants "ТЕМНОТА", and "ПОСЛЕ КОНЦЭРТЭ" wants "КОНЦЕРТА".
     * Without this, the trigram model ties and picks an ungrammatical form.
     */
    private fun agreesWithContext(previous: String?, candidate: String): Int {
        val endings = preferredEndings(previous) ?: return 0
        return if (candidate.last() in endings) 1 else 0
    }

    private fun preferredEndings(previous: String?): Set<Char>? {
        if (previous == null) return null
        val word = RussianLanguageModel.normalize(previous)
        if (word.length < 2) return null
        return when {
            word.endsWith("ая") || word.endsWith("яя") -> setOf('а', 'я')
            word.endsWith("ое") || word.endsWith("ее") -> setOf('о', 'е')
            word in GENITIVE_PREPOSITIONS -> setOf('а', 'я')
            else -> null
        }
    }

    private fun applyCase(source: String, candidate: String): String {
        val letters = source.filter(Char::isLetter)
        if (letters.isEmpty()) return candidate
        if (letters.all(Char::isUpperCase)) return candidate.uppercase()
        if (letters.first().isUpperCase() && letters.drop(1).all(Char::isLowerCase)) {
            return candidate.replaceFirstChar { it.uppercaseChar() }
        }
        return candidate
    }

    private data class Candidate(
        val word: String,
        val edits: Int,
        val visual: Int,
        val cost: Float,
    )

    private data class Edit(val word: String, val visual: Int)

    private const val VOWELS = "аеёиоуыэюя"
    private const val MIN_REPAIR_LENGTH = 4
    private const val MIN_CANDIDATE_LENGTH = 3
    private const val MAX_CANDIDATE_LENGTH = 40
    private const val MIN_MEANINGFUL_WORD = 2
    private const val MAX_REPAIRS_PER_REGION = 6
    private const val MEDIUM_WORD_LENGTH = 6
    private const val HARD_WORD_LENGTH = 8
    private const val SUSPECT_COST = 0.30f
    private const val MEDIUM_COST = 0.315f
    private const val HARD_COST = 0.315f
    private const val MAX_CANDIDATE_COST = 0.32f
    private const val MAX_EXPANSION_COST = 0.50f
    private const val MIN_COST_GAIN = 0.02f
    private const val MAX_COST_REGRESSION = 0.02f
    private const val BEAM_WIDTH = 6
    private val GENITIVE_PREPOSITIONS = setOf(
        "без", "для", "до", "из", "кроме", "около", "от", "после", "с", "у",
    )
    private const val MIDDLE_DOT = '\u00B7'
    private const val BULLET = '\u2022'
    private val NBSP = '\u00A0'
    private val CYRILLIC_WORD = Regex("[А-Яа-яЁё]+")
    private val WORD_TOKEN = Regex("[А-Яа-яЁё]{2,}")
    private val NUMBER_TOKEN = Regex("\\d{2,}")
    private val LATIN_TOKEN = Regex("[A-Za-z]{2,}")
    private val SLASH_SINGLE = Regex("/[^\\s/]/")
    private val SLASH_NOISE = Regex("(?<!\\d)[/\\\\]+|[/\\\\]+(?!\\d)")
    private val SYMBOLS_INSIDE_WORD =
        Regex("(?<=[А-Яа-яЁё])[‹›«»„“”|_^~\\\\\\[\\]{}]+(?=[А-Яа-яЁё])")
    private val STRAY_SINGLE_LETTER = Regex("(?<![А-Яа-яЁё])[ЁёІіЇїЄєҐґ](?![А-Яа-яЁё])")
    private val SYMBOL_RUN =
        Regex("(?<![\\p{L}\\p{N}])[/\\\\;.,:!?+_=~`'\"’‘„“”<>|\\[\\]{}()\\-–—]+(?![\\p{L}\\p{N}])")
    private val SPACE_BEFORE_PUNCT = Regex("[ \\t]+([.,!?;:…])")
    private val MULTI_SPACE = Regex("[ \\t]{2,}")
    private val CONFUSIONS: Map<Char, List<Char>> = buildMap {
        fun pair(first: Char, second: Char) {
            this[first] = (this[first] ?: emptyList()) + second
            this[second] = (this[second] ?: emptyList()) + first
        }
        listOf(
            'а' to 'о', 'а' to 'е', 'а' to 'и', 'а' to 'я', 'а' to 'э',
            'о' to 'е', 'о' to 'у', 'е' to 'и', 'е' to 'э', 'е' to 'я',
            'и' to 'й', 'и' to 'ы', 'и' to 'я', 'у' to 'ю', 'ы' to 'ь',
            'п' to 'л', 'п' to 'н', 'л' to 'м', 'л' to 'и', 'н' to 'и',
            'н' to 'м', 'с' to 'е', 'с' to 'о', 'з' to 'в', 'з' to 'е',
            'в' to 'б', 'б' to 'ь', 'ч' to 'т', 'ч' to 'ц', 'ч' to 'щ',
            'ш' to 'щ', 'ш' to 'ж', 'ж' to 'х', 'х' to 'к', 'к' to 'н',
            'т' to 'г', 'т' to 'п', 'г' to 'р', 'р' to 'ф', 'ф' to 'в',
            'ц' to 'щ', 'й' to 'н', 'ъ' to 'ь', 'щ' to 'а', 'ю' to 'о',
        ).forEach { (first, second) -> pair(first, second) }
    }
}
