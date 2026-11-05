package com.hukua.fundradar.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Two stroke-only icons for the bottom bar. Hand written so the app does not
 * have to pull in material-icons-extended (3 MB for a handful of glyphs).
 */
private fun tabIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block
        )
    }.build()

/** A card: the fund list. */
val FundTabIcon: ImageVector = tabIcon("FundTab") {
    moveTo(3.5f, 6.5f)
    lineTo(20.5f, 6.5f)
    lineTo(20.5f, 17.5f)
    lineTo(3.5f, 17.5f)
    close()
    moveTo(3.5f, 10.5f)
    lineTo(20.5f, 10.5f)
}

/** A trend line: the index tab. */
val IndexTabIcon: ImageVector = tabIcon("IndexTab") {
    moveTo(4f, 17.5f)
    lineTo(9f, 11.5f)
    lineTo(13f, 14.5f)
    lineTo(20f, 6.5f)
}
