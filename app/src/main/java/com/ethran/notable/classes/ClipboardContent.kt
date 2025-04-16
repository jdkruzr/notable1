package com.ethran.notable.classes

import com.ethran.notable.db.Image
import com.ethran.notable.db.Stroke

data class ClipboardContent(
    val strokes: List<Stroke> = emptyList(),
    val images: List<Image> = emptyList(),
)