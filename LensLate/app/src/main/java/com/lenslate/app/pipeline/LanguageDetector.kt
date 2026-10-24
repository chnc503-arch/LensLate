package com.lenslate.app.pipeline

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.lenslate.app.model.Language
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Определяет язык по доминирующей письменности, а не по вероятностной догадке:
 * кана/хань → японский, кириллица → русский, латиница → английский.
 * ML Kit используется только как резерв для письменностей, которых нет
 * среди поддерживаемых языков приложения.
 */
@Singleton
class LanguageDetector @Inject constructor() {

    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder()
            .setConfidenceThreshold(MIN_IDENTIFICATION_CONFIDENCE)
            .build(),
    )

    suspend fun detect(text: String): Language? {
        if (text.isBlank()) return null
        detectScript(text)?.let { return it }
        return try {
            when (identifier.identifyLanguage(text.take(MAX_IDENTIFICATION_CHARS)).await()) {
                "en" -> Language.ENGLISH
                "ru" -> Language.RUSSIAN
                "ja" -> Language.JAPANESE
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        identifier.close()
    }

    companion object {
        /**
         * Кириллица в технической документации соседствует с формулами, артикулами
         * и латинскими терминами, поэтому она выигрывает уже при заметной доле
         * букв (6%), а не только при абсолютном большинстве.
         */
        fun detectScript(text: String): Language? {
            if (text.isBlank()) return null
            var kana = 0
            var han = 0
            var cyrillic = 0
            var latin = 0
            text.codePoints().forEach { codePoint ->
                when {
                    codePoint in 0x3040..0x30FF || codePoint in 0x31F0..0x31FF -> kana++
                    codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF -> han++
                    codePoint in 0x0400..0x052F -> cyrillic++
                    codePoint in 0x0041..0x005A ||
                        codePoint in 0x0061..0x007A ||
                        codePoint in 0x00C0..0x024F -> latin++
                }
            }
            val letters = kana + han + cyrillic + latin
            if (letters == 0) return null
            val cyrillicShare = cyrillic.toFloat() / letters
            return when {
                kana > 0 -> Language.JAPANESE
                han > 0 && han >= latin && han >= cyrillic -> Language.JAPANESE
                cyrillic >= MIN_CYRILLIC_LETTERS && cyrillicShare >= MIN_CYRILLIC_SHARE ->
                    Language.RUSSIAN
                latin >= MIN_LATIN_LETTERS && cyrillicShare < MIN_CYRILLIC_SHARE ->
                    Language.ENGLISH
                cyrillic > 0 -> Language.RUSSIAN
                else -> null
            }
        }

        private const val MAX_IDENTIFICATION_CHARS = 400
        private const val MIN_IDENTIFICATION_CONFIDENCE = 0.35f
        private const val MIN_CYRILLIC_LETTERS = 3
        private const val MIN_CYRILLIC_SHARE = 0.06f
        private const val MIN_LATIN_LETTERS = 2
    }
}
