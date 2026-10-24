# LensLate — Архитектура приложения

## Концепция

Офлайн-переводчик изображений с техническим уклоном. Пайплайн полностью на устройстве:
OCR → определение языка → перевод → восстановление фона → рендеринг перевода поверх оригинала.

---

## Стек

| Слой | Технология | Назначение |
|---|---|---|
| OCR | ML Kit Text Recognition v2 | Распознавание EN / RU / JA, bounding boxes |
| Перевод | OPUS-MT (Helsinki-NLP) via ONNX Runtime Android | Нейросетевой перевод на устройстве |
| Inpainting | OpenCV 4 (TELEA алгоритм) | Восстановление фона под текстом |
| Рендеринг | Android Canvas + Typeface | Наложение перевода |
| UI | Jetpack Compose | Современный декларативный UI |
| Async | Kotlin Coroutines + Flow | Пайплайн обработки |
| DI | Hilt | Dependency injection |

---

## Языковые модели

6 направлений → 6 OPUS-MT моделей. Каждая загружается пользователем отдельно (~76–82 МБ).

| Модель | Размер | Направление |
|---|---|---|
| opus-mt-en-ru | 76 МБ | English → Russian |
| opus-mt-ru-en | 74 МБ | Russian → English |
| opus-mt-en-jap | 82 МБ | English → Japanese |
| opus-mt-jap-en | 79 МБ | Japanese → English |
| opus-mt-ru-jap | 81 МБ | Russian → Japanese |
| opus-mt-jap-ru | 80 МБ | Japanese → Russian |

Хранение: `filesDir/models/<model_name>/model.onnx` + `vocab.json` + `sentencepiece.model`

---

## Пайплайн обработки

```
Bitmap (input)
    │
    ▼
OcrProcessor
  - ML Kit TextRecognizer (LATIN + DEVANAGARI + JAPANESE)
  - Возвращает: List<TextBlock> { text, boundingBox, angle }
    │
    ▼
LanguageDetector
  - Анализ Unicode диапазонов символов
  - Фолбэк на ML Kit Language ID
  - Возвращает: LanguageCode (EN / RU / JA)
    │
    ▼
TranslationEngine
  - SentencePiece токенизатор
  - ONNX Runtime InferenceSession
  - Beam search decoder (beam=4)
  - Возвращает: List<TranslatedBlock> { original, translated, bbox }
    │
    ▼
StyleAnalyzer
  - Цвет текста: медиана пикселей в bbox
  - Размер: высота bbox / строки
  - Угол: из TextBlock.boundingBox
  - Возвращает: TextStyle { color, sizePx, angle, isBold }
    │
    ▼
BackgroundRestorer
  - Маска = bbox + 3px отступ
  - OpenCV Imgproc.inpaint(INPAINT_TELEA, radius=3)
  - Возвращает: Bitmap с чистым фоном
    │
    ▼
TextRenderer
  - Canvas.drawBitmap (восстановленный фон)
  - Подбор шрифта: Typeface.DEFAULT / MONOSPACE / sans-serif
  - StaticLayout для auto-wrap
  - Масштабирование: уменьшение если текст не влазит
  - Возвращает: Bitmap (результат)
```

---

## Структура модулей

```
app/
├── src/main/java/com/lenslate/
│   │
│   ├── di/                          # Hilt modules
│   │   ├── OcrModule.kt
│   │   ├── TranslationModule.kt
│   │   └── ProcessingModule.kt
│   │
│   ├── ui/
│   │   ├── MainActivity.kt
│   │   ├── screen/
│   │   │   ├── HomeScreen.kt        # Загрузка изображения, выбор языков
│   │   │   ├── ProcessingScreen.kt  # Прогресс пайплайна
│   │   │   ├── ResultScreen.kt      # Просмотр результата, зум
│   │   │   └── ModelsScreen.kt      # Менеджер моделей
│   │   ├── component/
│   │   │   ├── LanguageSelector.kt
│   │   │   ├── ProcessingStep.kt
│   │   │   ├── TranslationOverlay.kt
│   │   │   └── ModelRow.kt
│   │   └── theme/
│   │       ├── Theme.kt
│   │       ├── Color.kt
│   │       └── Type.kt
│   │
│   ├── pipeline/
│   │   ├── ImageTranslationPipeline.kt   # Оркестратор
│   │   ├── OcrProcessor.kt               # ML Kit OCR
│   │   ├── LanguageDetector.kt           # Определение языка
│   │   ├── TranslationEngine.kt          # ONNX OPUS-MT
│   │   ├── StyleAnalyzer.kt              # Цвет/размер текста
│   │   ├── BackgroundRestorer.kt         # OpenCV inpainting
│   │   └── TextRenderer.kt              # Canvas рендеринг
│   │
│   ├── model/
│   │   ├── TextBlock.kt                  # OCR блок + bbox
│   │   ├── TranslatedBlock.kt            # Переведённый блок + стиль
│   │   ├── TextStyle.kt                  # Цвет, размер, угол
│   │   ├── TranslationPair.kt            # Языковая пара
│   │   └── PipelineResult.kt             # Итог обработки
│   │
│   ├── data/
│   │   ├── ModelRepository.kt            # Скачивание и хранение моделей
│   │   ├── ModelDownloader.kt            # HTTP download + прогресс
│   │   └── ModelStorage.kt              # filesDir управление
│   │
│   └── util/
│       ├── BitmapUtils.kt
│       ├── ColorUtils.kt
│       └── LanguageUtils.kt
│
├── src/main/assets/
│   └── tokenizers/                       # SentencePiece .model (маленькие, бандлятся)
│
└── src/main/res/
    └── (стандартные ресурсы Compose)
```

---

## Ключевые технические детали

### OCR — ML Kit Text Recognition v2

```kotlin
// OcrProcessor.kt
val recognizer = TextRecognition.getClient(
    TextRecognizerOptions.Builder()
        .addLanguageModule(JapaneseTextRecognizerOptions.Builder().build())
        // Latin + Cyrillic поддерживаются по умолчанию
        .build()
)

suspend fun process(bitmap: Bitmap): List<TextBlock> {
    val image = InputImage.fromBitmap(bitmap, 0)
    return recognizer.process(image).await()
        .textBlocks
        .map { block ->
            TextBlock(
                text       = block.text,
                boundingBox = block.boundingBox!!,
                cornerPoints = block.cornerPoints,
                lines      = block.lines.map { it.text }
            )
        }
}
```

### Перевод — ONNX Runtime

```kotlin
// TranslationEngine.kt
class TranslationEngine(
    private val modelPath: String,   // путь к .onnx файлу
    private val vocabPath: String,   // vocab.json
    private val spmPath: String,     // sentencepiece.model
) {
    private val session: InferenceSession by lazy {
        val opts = SessionOptions().apply {
            setIntraOpNumThreads(4)
            setInterOpNumThreads(2)
            addConfigEntry("session.load_model_format", "ORT")
        }
        InferenceSession(modelPath, opts)
    }

    private val tokenizer: SentencePieceProcessor by lazy {
        SentencePieceProcessor().apply { load(spmPath) }
    }

    suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        val tokens = tokenizer.encodeAsIds(text)
        val inputIds = OnnxTensor.createTensor(env, longArrayOf(*tokens.map { it.toLong() }.toLongArray()).reshape(1, tokens.size))
        val output = session.run(mapOf("input_ids" to inputIds))
        val outputIds = (output[0].value as Array<LongArray>)[0]
        tokenizer.decodeIds(outputIds.map { it.toInt() })
    }
}
```

### Inpainting — OpenCV

```kotlin
// BackgroundRestorer.kt
fun restore(bitmap: Bitmap, blocks: List<TextBlock>): Bitmap {
    val src = Mat()
    Utils.bitmapToMat(bitmap, src)
    
    val mask = Mat.zeros(src.size(), CvType.CV_8U)
    
    blocks.forEach { block ->
        val rect = block.boundingBox
        // Расширяем маску на 3px для чистого края
        val expanded = Rect(
            maxOf(0, rect.left   - 3),
            maxOf(0, rect.top    - 3),
            minOf(src.width()  - rect.left + 3, rect.width()  + 6),
            minOf(src.height() - rect.top  + 3, rect.height() + 6)
        )
        mask.submat(
            expanded.y, expanded.y + expanded.height,
            expanded.x, expanded.x + expanded.width
        ).setTo(Scalar(255.0))
    }
    
    val dst = Mat()
    Photo.inpaint(src, mask, dst, 3.0, Photo.INPAINT_TELEA)
    
    val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(dst, result)
    return result
}
```

### Рендеринг — Canvas

```kotlin
// TextRenderer.kt
fun render(bitmap: Bitmap, blocks: List<TranslatedBlock>): Bitmap {
    val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    
    blocks.forEach { block ->
        val rect = block.boundingBox
        val style = block.style
        
        val paint = TextPaint().apply {
            color = style.color
            isAntiAlias = true
            typeface = when {
                style.isMono  -> Typeface.MONOSPACE
                style.isBold  -> Typeface.DEFAULT_BOLD
                else          -> Typeface.DEFAULT
            }
        }
        
        // Подбираем размер: начинаем с оригинального, уменьшаем если не влезает
        var textSize = style.sizePx
        var layout: StaticLayout
        do {
            paint.textSize = textSize
            layout = StaticLayout.Builder
                .obtain(block.translated, 0, block.translated.length, paint, rect.width())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.1f)
                .setIncludePad(false)
                .build()
            textSize -= 1f
        } while (layout.height > rect.height() && textSize > 6f)
        
        canvas.save()
        if (style.angle != 0f) {
            canvas.rotate(style.angle, rect.exactCenterX(), rect.exactCenterY())
        }
        canvas.translate(rect.left.toFloat(), rect.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
    
    return result
}
```

---

## Gradle зависимости

```kotlin
// build.gradle.kts (app)
dependencies {
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")

    // ML Kit OCR
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")

    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    // OpenCV
    implementation("com.quickbirdstudios:opencv:4.5.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")

    // Coil (отображение изображений в Compose)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // WorkManager (фоновая загрузка моделей)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
}
```

---

## Порядок разработки

### Фаза 1 — Ядро (2–3 недели)
1. Настройка проекта: Compose, Hilt, ONNX, OpenCV
2. `OcrProcessor` — ML Kit, тест на реальных чертежах
3. `LanguageDetector` — Unicode + ML Kit Language ID
4. `TranslationEngine` — ONNX Runtime + SentencePiece, тест EN→RU
5. Базовый UI: загрузка фото → показ результата

### Фаза 2 — Качество наложения (1–2 недели)
6. `StyleAnalyzer` — цвет текста, размер, угол
7. `BackgroundRestorer` — OpenCV inpainting
8. `TextRenderer` — масштабирование, перенос строк, угол
9. Сравнение оригинал/перевод (side-by-side, overlay toggle)

### Фаза 3 — Все языки + UX (1–2 недели)
10. Добавить все 6 языковых пар
11. `ModelRepository` — скачивание, хранение, удаление
12. `ModelsScreen` — менеджер моделей с прогрессом
13. Полировка UI, жесты зума, сохранение результата

### Фаза 4 — Рукописный текст (позже)
14. Замена/дополнение ML Kit на Tesseract с рукописными весами
15. Или интеграция Google Vision API (если офлайн-ограничение снимается)

---

## Риски и решения

| Риск | Решение |
|---|---|
| Медленный ONNX на старых устройствах | NNAPI делегат для GPU-ускорения: `addNnapiDelegate()` |
| Плохой inpainting на сложном фоне | Детектировать тип фона; для градиентов — средний цвет bbox |
| Японский OCR требует загрузки модели | Предложить скачать при первом выборе JA |
| Текст не вписывается после перевода | Минимальный размер шрифта 6sp + уведомление пользователю |
| RU→JA/JA→RU низкое качество | Цепочка через EN как промежуточный язык (RU→EN→JA) |
