package com.lenslate.app.pipeline

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.lenslate.app.model.Language
import com.lenslate.app.model.TranslationPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class TranslationOutcome(
    val text: String?,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = text != null
}

/**
 * On-device перевод через ML Kit Translation.
 *
 * Модели скачиваются по требованию и после загрузки работают без сети.
 * Пары RU↔JA ML Kit автоматически переводит через английский.
 */
@Singleton
class TranslationEngine @Inject constructor() {

    private val modelManager = RemoteModelManager.getInstance()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val downloadConditions = DownloadConditions.Builder()
        .build()

    suspend fun installedLanguageCodes(): Set<String> = try {
        modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
            .await()
            .mapTo(mutableSetOf()) { it.language }
    } catch (_: Exception) {
        emptySet()
    }

    suspend fun isInstalled(language: Language): Boolean =
        translateLanguage(language) in installedLanguageCodes()

    /** Скачивает недостающие языковые модели пары; [onDownload] вызывается только при реальной загрузке. */
    suspend fun ensureModels(
        pair: TranslationPair,
        onDownload: (() -> Unit)? = null,
    ) {
        if (pair.isIdentity) return
        val installed = installedLanguageCodes()
        val missing = listOf(pair.from, pair.to)
            .distinct()
            .filter { translateLanguage(it) !in installed }
        if (missing.isEmpty()) return
        onDownload?.invoke()
        missing.forEach { download(it) }
    }

    suspend fun download(language: Language) {
        val model = TranslateRemoteModel.Builder(translateLanguage(language)).build()
        modelManager.download(model, downloadConditions).await()
    }

    suspend fun translate(text: String, pair: TranslationPair): String {
        if (text.isBlank() || pair.isIdentity) return text
        val prepared = TechnicalGlossary.prepare(text, pair.from, pair.to)
        val translator = translatorFor(pair)
        translator.downloadModelIfNeeded(downloadConditions).await()
        val translated = translator.translate(prepared.text).await()
        return TechnicalGlossary.refine(
            TechnicalGlossary.restore(translated, prepared.tokens),
            pair.to,
        )
    }

    /** Переводит набор регионов ограниченным параллелизмом; сбой одного региона не рушит остальные. */
    suspend fun translateBatch(
        texts: List<String>,
        pair: TranslationPair,
        onModelDownload: (() -> Unit)? = null,
    ): List<TranslationOutcome> {
        if (texts.isEmpty()) return emptyList()
        if (pair.isIdentity) return texts.map { TranslationOutcome(it) }
        ensureModels(pair, onModelDownload)
        val semaphore = Semaphore(MAX_PARALLEL_TRANSLATIONS)
        return coroutineScope {
            texts.map { text ->
                async(Dispatchers.Default) {
                    semaphore.withPermit {
                        if (text.isBlank()) {
                            TranslationOutcome(text)
                        } else {
                            try {
                                TranslationOutcome(translate(text, pair))
                            } catch (exception: Exception) {
                                TranslationOutcome(
                                    text = null,
                                    error = exception.localizedMessage ?: "Ошибка перевода",
                                )
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun closeAll() {
        translators.values.forEach { translator ->
            runCatching { translator.close() }
        }
        translators.clear()
    }

    private fun translatorFor(pair: TranslationPair): Translator =
        translators.getOrPut(translatorKey(pair)) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(translateLanguage(pair.from))
                    .setTargetLanguage(translateLanguage(pair.to))
                    .build(),
            )
        }

    private fun translatorKey(pair: TranslationPair): String =
        "${translateLanguage(pair.from)}->${translateLanguage(pair.to)}"

    private fun translateLanguage(language: Language): String = when (language) {
        Language.ENGLISH -> TranslateLanguage.ENGLISH
        Language.RUSSIAN -> TranslateLanguage.RUSSIAN
        Language.JAPANESE -> TranslateLanguage.JAPANESE
    }

    private companion object {
        const val MAX_PARALLEL_TRANSLATIONS = 3
    }
}
