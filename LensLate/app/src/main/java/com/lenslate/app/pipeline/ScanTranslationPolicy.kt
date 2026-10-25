package com.lenslate.app.pipeline

import com.lenslate.app.model.TextRegionRecord

internal object ScanTranslationPolicy {

    fun selectableIndices(regions: List<TextRegionRecord>): List<Int> =
        regions.indices.filter { index ->
            val region = regions[index]
            region.text.isNotBlank() && !region.isFormula && !region.isProtected
        }
}
