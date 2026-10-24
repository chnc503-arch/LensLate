package com.lenslate.app.pipeline

import com.lenslate.app.model.Language

/**
 * Technical-document post-processing around ML Kit:
 * alphanumeric codes, part numbers and measurements are replaced by opaque tokens
 * before translation, engineering terms on short labels are locked to the preferred
 * target term, and the translated text is cleaned up and unit-localized afterwards.
 */
internal object TechnicalGlossary {

    data class PreparedText(val text: String, val tokens: List<String>)

    private data class Term(
        val source: Language,
        val pattern: String,
        val translations: Map<Language, String>,
    )

    private val TERMS: List<Term> = listOf(
        term(Language.ENGLISH, """torques?""", ru = "крутящий момент", ja = "トルク"),
        term(Language.ENGLISH, """tightening\s+torques?""", ru = "момент затяжки", ja = "締付トルク"),
        term(Language.ENGLISH, """pressures?""", ru = "давление", ja = "圧力"),
        term(Language.ENGLISH, """temperatures?""", ru = "температура", ja = "温度"),
        term(Language.ENGLISH, """flow\s+rates?""", ru = "расход", ja = "流量"),
        term(Language.ENGLISH, """voltages?""", ru = "напряжение", ja = "電圧"),
        term(Language.ENGLISH, """pumps?""", ru = "насос", ja = "ポンプ"),
        term(Language.ENGLISH, """valves?""", ru = "клапан", ja = "バルブ"),
        term(Language.ENGLISH, """pipes?""", ru = "труба", ja = "配管"),
        term(Language.ENGLISH, """tubes?""", ru = "трубка", ja = "チューブ"),
        term(Language.ENGLISH, """flanges?""", ru = "фланец", ja = "フランジ"),
        term(Language.ENGLISH, """gaskets?""", ru = "прокладка", ja = "ガスケット"),
        term(Language.ENGLISH, """seals?""", ru = "уплотнение", ja = "シール"),
        term(Language.ENGLISH, """bearings?""", ru = "подшипник", ja = "ベアリング"),
        term(Language.ENGLISH, """shafts?""", ru = "вал", ja = "シャフト"),
        term(Language.ENGLISH, """bolts?""", ru = "болт", ja = "ボルト"),
        term(Language.ENGLISH, """nuts?""", ru = "гайка", ja = "ナット"),
        term(Language.ENGLISH, """washers?""", ru = "шайба", ja = "ワッシャー"),
        term(Language.ENGLISH, """screws?""", ru = "винт", ja = "ねじ"),
        term(Language.ENGLISH, """couplings?""", ru = "муфта", ja = "カップリング"),
        term(Language.ENGLISH, """gears?""", ru = "шестерня", ja = "ギア"),
        term(Language.ENGLISH, """motors?""", ru = "двигатель", ja = "モーター"),
        term(Language.ENGLISH, """sensors?""", ru = "датчик", ja = "センサー"),
        term(Language.ENGLISH, """switches""", ru = "переключатель", ja = "スイッチ"),
        term(Language.ENGLISH, """wiring""", ru = "электропроводка", ja = "配線"),
        term(Language.ENGLISH, """circuits?""", ru = "цепь", ja = "回路"),
        term(Language.ENGLISH, """maintenance""", ru = "техническое обслуживание", ja = "メンテナンス"),
        term(Language.ENGLISH, """troubleshooting""", ru = "поиск и устранение неисправностей", ja = "トラブルシューティング"),
        term(Language.ENGLISH, """safety""", ru = "безопасность", ja = "安全"),
        term(Language.ENGLISH, """warnings?""", ru = "предупреждение", ja = "警告"),
        term(Language.ENGLISH, """cautions?""", ru = "осторожно", ja = "注意"),
        term(Language.ENGLISH, """specifications?""", ru = "технические характеристики", ja = "仕様"),
        term(Language.ENGLISH, """dimensions""", ru = "размеры", ja = "寸法"),
        term(Language.ENGLISH, """torque\s+wrench(?:es)?""", ru = "динамометрический ключ", ja = "トルクレンチ"),
        term(Language.ENGLISH, """pressure\s+gauges?""", ru = "манометр", ja = "圧力計"),
        term(Language.ENGLISH, """flow\s+meters?""", ru = "расходомер", ja = "流量計"),
        term(Language.ENGLISH, """check\s+valves?""", ru = "обратный клапан", ja = "逆止弁"),
        term(Language.ENGLISH, """ball\s+valves?""", ru = "шаровой кран", ja = "ボールバルブ"),
        term(Language.ENGLISH, """relief\s+valves?""", ru = "предохранительный клапан", ja = "リリーフバルブ"),
        term(Language.ENGLISH, """hydraulic""", ru = "гидравлический", ja = "油圧"),
        term(Language.ENGLISH, """pneumatic""", ru = "пневматический", ja = "空気圧"),
        term(Language.ENGLISH, """hoses?""", ru = "шланг", ja = "ホース"),
        term(Language.ENGLISH, """fittings?""", ru = "фитинг", ja = "継手"),
        term(Language.ENGLISH, """adapters?""", ru = "переходник", ja = "アダプター"),
        term(Language.ENGLISH, """generators?""", ru = "генератор", ja = "発電機"),
        term(Language.ENGLISH, """compressors?""", ru = "компрессор", ja = "コンプレッサー"),
        term(Language.ENGLISH, """fans?""", ru = "вентилятор", ja = "ファン"),
        term(Language.ENGLISH, """filters?""", ru = "фильтр", ja = "フィルター"),
        term(Language.ENGLISH, """coolants?""", ru = "охлаждающая жидкость", ja = "冷却液"),
        term(Language.ENGLISH, """lubricants?""", ru = "смазка", ja = "潤滑剤"),

        term(Language.RUSSIAN, """давлени(?:е|я|ю|ем|и|й|ям|ями|ях)""", en = "pressure", ja = "圧力"),
        term(Language.RUSSIAN, """температур(?:а|ы|у|ой|е|ам|ами|ах)""", en = "temperature", ja = "温度"),
        term(Language.RUSSIAN, """крутящ(?:ий|его|ему|им|ем)\s+момент(?:а|у|ом|е)?""", en = "torque", ja = "トルク"),
        term(Language.RUSSIAN, """момент(?:а|у|ом|е)?\s+затяжк(?:и|у|ой|е)""", en = "tightening torque", ja = "締付トルク"),
        term(Language.RUSSIAN, """момент\s+силы""", en = "torque", ja = "トルク"),
        term(Language.RUSSIAN, """расход""", en = "flow rate", ja = "流量"),
        term(Language.RUSSIAN, """напряжени(?:е|я|ю|ем|и|й)""", en = "voltage", ja = "電圧"),
        term(Language.RUSSIAN, """насос(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "pump", ja = "ポンプ"),
        term(Language.RUSSIAN, """клапан(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "valve", ja = "バルブ"),
        term(Language.RUSSIAN, """труб(?:а|ы|у|ой|е|ам|ами|ах)""", en = "pipe", ja = "配管"),
        term(Language.RUSSIAN, """флан(?:ец|ца|цу|цем|це|цы|цев|цам|цами|цах)""", en = "flange", ja = "フランジ"),
        term(Language.RUSSIAN, """прокладк(?:а|и|у|ой|е|ам|ами|ах)""", en = "gasket", ja = "ガスケット"),
        term(Language.RUSSIAN, """уплотнени(?:е|я|ю|ем|и|й|ям|ями|ях)""", en = "seal", ja = "シール"),
        term(Language.RUSSIAN, """подшипник(?:а|у|ом|е|и|ов|ам|ами|ах)?""", en = "bearing", ja = "ベアリング"),
        term(Language.RUSSIAN, """вал(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "shaft", ja = "シャフト"),
        term(Language.RUSSIAN, """болт(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "bolt", ja = "ボルト"),
        term(Language.RUSSIAN, """гайк(?:а|и|у|ой|е|ам|ами|ах)""", en = "nut", ja = "ナット"),
        term(Language.RUSSIAN, """шайб(?:а|ы|у|ой|е|ам|ами|ах)""", en = "washer", ja = "ワッシャー"),
        term(Language.RUSSIAN, """винт(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "screw", ja = "ねじ"),
        term(Language.RUSSIAN, """муфт(?:а|ы|у|ой|е|ам|ами|ах)""", en = "coupling", ja = "カップリング"),
        term(Language.RUSSIAN, """шестерн(?:я|и|ю|ёй|е|ям|ями|ях)""", en = "gear", ja = "ギア"),
        term(Language.RUSSIAN, """двигател(?:ь|я|ю|ем|е|и|ей|ям|ями|ях)""", en = "motor", ja = "モーター"),
        term(Language.RUSSIAN, """датчик(?:а|у|ом|е|и|ов|ам|ами|ах)?""", en = "sensor", ja = "センサー"),
        term(Language.RUSSIAN, """переключател(?:ь|я|ю|ем|е|и|ей|ям|ями|ях)""", en = "switch", ja = "スイッチ"),
        term(Language.RUSSIAN, """электропроводк(?:а|и|у|ой|е)""", en = "wiring"),
        term(Language.RUSSIAN, """техническ(?:ое|ого|ому|им|ом)\s+обслуживани(?:е|я|ю|ем)""", en = "maintenance", ja = "メンテナンス"),
        term(Language.RUSSIAN, """поиск\s+и\s+устранение\s+неисправностей""", en = "troubleshooting"),
        term(Language.RUSSIAN, """безопасност(?:ь|и|ью|ей)""", en = "safety", ja = "安全"),
        term(Language.RUSSIAN, """предупреждени(?:е|я|ю|ем)""", en = "warning", ja = "警告"),
        term(Language.RUSSIAN, """осторожно""", en = "caution", ja = "注意"),
        term(Language.RUSSIAN, """техническ(?:ие|их|им|ими)\s+характеристик(?:и|ам|ами|ах)""", en = "specifications", ja = "仕様"),
        term(Language.RUSSIAN, """размер(?:ы|ов|ам|ами|ах)""", en = "dimensions", ja = "寸法"),
        term(Language.RUSSIAN, """динамометрическ(?:ий|ого|ому|им|ом)\s+ключ(?:а|у|ом|е)?""", en = "torque wrench", ja = "トルクレンチ"),
        term(Language.RUSSIAN, """манометр(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "pressure gauge", ja = "圧力計"),
        term(Language.RUSSIAN, """расходомер(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "flow meter", ja = "流量計"),
        term(Language.RUSSIAN, """обратн(?:ый|ого|ому|ым|ом)\s+клапан(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "check valve"),
        term(Language.RUSSIAN, """шаров(?:ой|ого|ому|ым|ом)\s+кран(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "ball valve"),
        term(Language.RUSSIAN, """гидравлическ(?:ий|ого|ому|им|ом|ая|ой|ую|ие|их)""", en = "hydraulic", ja = "油圧"),
        term(Language.RUSSIAN, """пневматическ(?:ий|ого|ому|им|ом|ая|ой|ую|ие|их)""", en = "pneumatic", ja = "空気圧"),
        term(Language.RUSSIAN, """шланг(?:а|у|ом|е|и|ов|ам|ами|ах)?""", en = "hose", ja = "ホース"),
        term(Language.RUSSIAN, """фитинг(?:а|у|ом|е|и|ов|ам|ами|ах)?""", en = "fitting", ja = "継手"),
        term(Language.RUSSIAN, """переходник(?:а|у|ом|е|и|ов|ам|ами|ах)?""", en = "adapter", ja = "アダプター"),
        term(Language.RUSSIAN, """генератор(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "generator", ja = "発電機"),
        term(Language.RUSSIAN, """компрессор(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "compressor", ja = "コンプレッサー"),
        term(Language.RUSSIAN, """вентилятор(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "fan", ja = "ファン"),
        term(Language.RUSSIAN, """фильтр(?:а|у|ом|е|ы|ов|ам|ами|ах)?""", en = "filter", ja = "フィルター"),
        term(Language.RUSSIAN, """смазк(?:а|и|у|ой|е)""", en = "lubricant", ja = "潤滑剤"),
        term(Language.RUSSIAN, """охлаждающ(?:ая|ей|ую|ие|их)\s+жидкост(?:ь|и|ью|ей)""", en = "coolant", ja = "冷却液"),

        term(Language.JAPANESE, """トルク""", en = "torque", ru = "крутящий момент"),
        term(Language.JAPANESE, """圧力""", en = "pressure", ru = "давление"),
        term(Language.JAPANESE, """温度""", en = "temperature", ru = "температура"),
        term(Language.JAPANESE, """流量""", en = "flow rate", ru = "расход"),
        term(Language.JAPANESE, """電圧""", en = "voltage", ru = "напряжение"),
        term(Language.JAPANESE, """ポンプ""", en = "pump", ru = "насос"),
        term(Language.JAPANESE, """バルブ""", en = "valve", ru = "клапан"),
        term(Language.JAPANESE, """配管""", en = "piping", ru = "трубопровод"),
        term(Language.JAPANESE, """フランジ""", en = "flange", ru = "фланец"),
        term(Language.JAPANESE, """ガスケット""", en = "gasket", ru = "прокладка"),
        term(Language.JAPANESE, """シール""", en = "seal", ru = "уплотнение"),
        term(Language.JAPANESE, """ベアリング""", en = "bearing", ru = "подшипник"),
        term(Language.JAPANESE, """シャフト""", en = "shaft", ru = "вал"),
        term(Language.JAPANESE, """ボルト""", en = "bolt", ru = "болт"),
        term(Language.JAPANESE, """ナット""", en = "nut", ru = "гайка"),
        term(Language.JAPANESE, """ワッシャー""", en = "washer", ru = "шайба"),
        term(Language.JAPANESE, """ねじ""", en = "screw", ru = "винт"),
        term(Language.JAPANESE, """カップリング""", en = "coupling", ru = "муфта"),
        term(Language.JAPANESE, """ギア""", en = "gear", ru = "шестерня"),
        term(Language.JAPANESE, """モーター""", en = "motor", ru = "двигатель"),
        term(Language.JAPANESE, """センサー""", en = "sensor", ru = "датчик"),
        term(Language.JAPANESE, """スイッチ""", en = "switch", ru = "переключатель"),
        term(Language.JAPANESE, """配線""", en = "wiring", ru = "электропроводка"),
        term(Language.JAPANESE, """回路""", en = "circuit", ru = "цепь"),
        term(Language.JAPANESE, """メンテナンス""", en = "maintenance", ru = "техническое обслуживание"),
        term(Language.JAPANESE, """安全""", en = "safety", ru = "безопасность"),
        term(Language.JAPANESE, """警告""", en = "warning", ru = "предупреждение"),
        term(Language.JAPANESE, """注意""", en = "caution", ru = "осторожно"),
        term(Language.JAPANESE, """仕様""", en = "specifications", ru = "технические характеристики"),
        term(Language.JAPANESE, """寸法""", en = "dimensions", ru = "размеры"),
        term(Language.JAPANESE, """トルクレンチ""", en = "torque wrench", ru = "динамометрический ключ"),
        term(Language.JAPANESE, """圧力計""", en = "pressure gauge", ru = "манометр"),
    )

    private val PROTECTION_PATTERNS = listOf(
        Regex("""(?i)\b(?:https?://|www\.)[^\s]+|\b[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}\b"""),
        Regex("""(?<![\p{L}\p{N}])(?=[A-Z0-9._/+×*\-]*\d)(?=[A-Z0-9._/+×*\-]*[A-Z])[A-Z0-9]+(?:[._/+×*\-][A-Z0-9]+)*(?![\p{L}\p{N}])"""),
        Regex("""(?<![\p{L}\p{N}])\d+(?:[.,]\d+)?[ \u00A0]?(?:мм|см|дм|км|кг|мг|мл|мПа|кПа|МПа|бар|атм|об/мин|л/мин|м³/ч|м3/ч|мм²|мм2|%|°C|°F|Вт|кВт|Гц|кГц|МГц|Н·м|Нм|кН|mm|cm|km|kg|mg|ml|MPa|kPa|bar|psi|rpm|W|kW|Hz|kHz|MHz|N·m|Nm|kN|V|A|in|ft)(?![\p{L}\p{N}])"""),
        Regex("""(?<![\p{L}\p{N}])(?:[Øø]\s?\d+(?:[.,]\d+)?|M\d+(?:[×xX]\d+(?:[.,]\d+)?)?|\d+/\d+"|\d+\s?[×xX]\s?\d+(?:\s?[×xX]\s?\d+)?|\d+\s?:\s?\d+)(?![\p{L}\p{N}])"""),
    )

    private val TOKEN_PATTERN = Regex("""ZQX\s*(\d+)\s*QXZ""", RegexOption.IGNORE_CASE)

    private val RU_PHRASE_FIXES = listOf(
        "момент силы" to "крутящий момент",
        "вращающий момент" to "крутящий момент",
        "момент вращения" to "крутящий момент",
        "скорость потока" to "расход",
        "объёмный поток" to "расход",
        "объемный поток" to "расход",
        "техобслуживание" to "техническое обслуживание",
    )

    private val EN_PHRASE_FIXES = listOf(
        "moment of force" to "torque",
        "rotational moment" to "torque",
        "rotating moment" to "torque",
        "rate of flow" to "flow rate",
    )

    private val EN_UNITS = mapOf(
        "мм" to "mm",
        "см" to "cm",
        "км" to "km",
        "кг" to "kg",
        "мг" to "mg",
        "мл" to "mL",
        "МПа" to "MPa",
        "кПа" to "kPa",
        "Вт" to "W",
        "кВт" to "kW",
        "Гц" to "Hz",
        "кГц" to "kHz",
        "МГц" to "MHz",
        "об/мин" to "rpm",
        "л/мин" to "L/min",
        "м³/ч" to "m³/h",
        "м3/ч" to "m3/h",
        "мм²" to "mm²",
        "мм2" to "mm2",
    )

    private val RU_UNITS = EN_UNITS.entries.associate { (russian, english) -> english to russian }

    fun prepare(text: String, source: Language, target: Language): PreparedText {
        if (text.isBlank()) return PreparedText(text, emptyList())
        val tokens = mutableListOf<String>()
        var prepared = text
        PROTECTION_PATTERNS.forEach { pattern ->
            prepared = pattern.replace(prepared) { match -> token(tokens, match.value) }
        }
        prepared = lockTerms(prepared, source, target, tokens)
        return PreparedText(prepared, tokens)
    }

    fun restore(text: String, tokens: List<String>): String {
        if (tokens.isEmpty()) return text
        return TOKEN_PATTERN.replace(text) { match ->
            val index = match.groupValues[1].toIntOrNull()
            if (index == null) match.value else tokens.getOrElse(index) { match.value }
        }
    }

    fun refine(text: String, target: Language): String {
        if (text.isBlank()) return text
        var result = text
        when (target) {
            Language.RUSSIAN -> RU_PHRASE_FIXES.forEach { (from, to) ->
                result = replacePhrase(result, from, to)
            }
            Language.ENGLISH -> EN_PHRASE_FIXES.forEach { (from, to) ->
                result = replacePhrase(result, from, to)
            }
            Language.JAPANESE -> Unit
        }
        result = localizeUnits(result, target)
        result = normalizeWhitespace(result, target)
        return result
    }

    private fun lockTerms(
        text: String,
        source: Language,
        target: Language,
        tokens: MutableList<String>,
    ): String {
        if (!isLabelLike(text)) return text
        var result = text
        TERMS
            .filter { term -> term.source == source && term.translations.containsKey(target) }
            .sortedByDescending { term -> term.pattern.length }
            .forEach { term ->
                val regex = Regex(
                    """(?<![\p{L}\p{N}])(?:${term.pattern})(?![\p{L}\p{N}])""",
                    RegexOption.IGNORE_CASE,
                )
                val translation = term.translations.getValue(target)
                result = regex.replace(result) { match ->
                    val value = if (target != Language.JAPANESE &&
                        match.value.firstOrNull()?.isUpperCase() == true
                    ) {
                        translation.replaceFirstChar { it.uppercase() }
                    } else {
                        translation
                    }
                    token(tokens, value)
                }
            }
        return result
    }

    private fun isLabelLike(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.endsWith(".")) return false
        val words = trimmed.split(Regex("""\s+""")).count { it.isNotEmpty() }
        return words in 1..8 && trimmed.length <= 96
    }

    private fun token(tokens: MutableList<String>, value: String): String {
        tokens += value
        return "ZQX${tokens.lastIndex}QXZ"
    }

    private fun replacePhrase(text: String, from: String, to: String): String {
        val regex = Regex(
            """(?<![\p{L}\p{N}])${Regex.escape(from)}(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { match ->
            if (match.value.firstOrNull()?.isUpperCase() == true) {
                to.replaceFirstChar { it.uppercase() }
            } else {
                to
            }
        }
    }

    private fun localizeUnits(text: String, target: Language): String {
        val mapping = when (target) {
            Language.ENGLISH -> EN_UNITS
            Language.RUSSIAN -> RU_UNITS
            Language.JAPANESE -> emptyMap()
        }
        if (mapping.isEmpty()) return text
        val units = mapping.keys.sortedByDescending { it.length }
        val regex = Regex(
            """(\d[ \u00A0]?)(?:${units.joinToString("|") { Regex.escape(it) }})(?![\p{L}\p{N}])""",
        )
        return regex.replace(text) { match ->
            val prefix = match.groupValues[1]
            val unit = match.value.substring(prefix.length)
            prefix + (mapping[unit] ?: unit)
        }
    }

    private fun normalizeWhitespace(text: String, target: Language): String {
        var result = text.replace(Regex("""[ \t]+\n"""), "\n")
        result = result.replace(Regex("""\n{3,}"""), "\n\n")
        result = result.replace(Regex("""[ \t]{2,}"""), " ")
        result = result.replace(Regex("""\s+([,.;:!?%)\]}])""")) { it.groupValues[1] }
        result = result.replace(Regex("""([(\[{])\s+""")) { it.groupValues[1] }
        if (target != Language.JAPANESE) {
            result = result.replace(Regex("""([,;:])(?=\S)""")) { it.groupValues[1] + " " }
        }
        return result.trim()
    }

    private fun term(
        source: Language,
        pattern: String,
        en: String? = null,
        ru: String? = null,
        ja: String? = null,
    ): Term {
        val translations = linkedMapOf<Language, String>()
        if (en != null) translations[Language.ENGLISH] = en
        if (ru != null) translations[Language.RUSSIAN] = ru
        if (ja != null) translations[Language.JAPANESE] = ja
        return Term(source, pattern, translations)
    }
}
