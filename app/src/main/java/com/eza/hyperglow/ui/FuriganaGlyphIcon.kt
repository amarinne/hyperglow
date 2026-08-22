package com.eza.hyperglow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/**
 * A single centered text glyph standing in for an icon slot, mirroring the
 * sibling app's GlyphIconDrawable chips: the glyph names the script concept
 * (あ for furigana) where no Lucide glyph exists. Like the Lucide vectors, the
 * text follows the local content color.
 */
@Composable
internal fun FuriganaGlyphIcon(glyph: String) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
