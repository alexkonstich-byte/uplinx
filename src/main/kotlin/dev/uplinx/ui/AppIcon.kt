package dev.uplinx.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter

/** Brand colours: "signal going up" — electric indigo through azure and teal to neon lime. */
object Brand {
    val Indigo = Color(0xFF4B2EFF)
    val Azure = Color(0xFF1F8BFF)
    val Teal = Color(0xFF00BFB3)
    val Mint = Color(0xFF1ED99A)
    val Lime = Color(0xFFC8F53A)
}

/**
 * The Uplinx mark: a geometric "U" made of three flat gradient pieces —
 * left stem (indigo → azure), bowl (azure → teal → mint) and a right stem cut on a
 * diagonal that brightens upward (mint → lime), like a signal going up. Colours meet exactly at the joints, so the letter reads as one stroke.
 * Drawn on a 256×256 grid and scaled.
 */
fun DrawScope.drawUplinxMark() {
    val k = size.minDimension / 256f
    scale(k, k, pivot = Offset.Zero) {
        val top = 22f; val joint = 160f
        val l0 = 34f; val l1 = 98f      // left stem x range
        val r0 = 158f; val r1 = 222f    // right stem x range
        val cx = 128f
        val outerR = cx - l0            // 94
        val innerR = cx - l1            // 30

        // bowl: half annulus below the joint line
        val bowl = Path().apply {
            moveTo(l0, joint)
            arcTo(Rect(cx - outerR, joint - outerR, cx + outerR, joint + outerR), 180f, -180f, false)
            lineTo(r0, joint)
            arcTo(Rect(cx - innerR, joint - innerR, cx + innerR, joint + innerR), 0f, 180f, false)
            close()
        }
        // flat colour under each stem, the blend only in the curve, so the joints are seamless
        val a = (l1 - l0) / (r1 - l0); val b = (r0 - l0) / (r1 - l0)
        drawPath(bowl, Brush.linearGradient(
            0f to Brand.Azure, a to Brand.Azure, 0.5f to Brand.Teal, b to Brand.Mint, 1f to Brand.Mint,
            start = Offset(l0, 0f), end = Offset(r1, 0f)))

        // left stem
        val left = Path().apply { moveTo(l0, top); lineTo(l1, top); lineTo(l1, joint); lineTo(l0, joint); close() }
        drawPath(left, Brush.verticalGradient(listOf(Brand.Indigo, Brand.Azure), startY = top, endY = joint))

        // right stem with the signature diagonal top
        val right = Path().apply { moveTo(r0, top + 64f); lineTo(r1, top); lineTo(r1, joint); lineTo(r0, joint); close() }
        drawPath(right, Brush.verticalGradient(listOf(Brand.Lime, Brand.Mint), startY = top, endY = joint))
    }
}

/** App/tray icon; [badge] adds a status dot (connected / error) in the corner. */
class UplinxIcon(private val badge: Color? = null, private val badgeRing: Color = Color.White) : Painter() {
    override val intrinsicSize = Size(256f, 256f)
    override fun DrawScope.onDraw() {
        drawUplinxMark()
        badge?.let {
            val r = size.minDimension * 0.17f
            val c = Offset(size.width - r * 1.05f, size.height - r * 1.05f)
            drawCircle(badgeRing, r * 1.25f, c)
            drawCircle(it, r, c)
        }
    }
}
