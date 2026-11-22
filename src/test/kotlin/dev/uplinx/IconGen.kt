package dev.uplinx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.uplinx.ui.drawUplinxMark
import dev.uplinx.ui.markColors
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the Uplinx mark with a Material You scheme built from [SEED] into PNGs and a multi-size .ico.
 * `-Dicon.preview=1` writes a sheet with several seeds, light and dark, to compare palettes.
 */
object IconGen {
    val SEED: Long = (System.getProperty("icon.seed") ?: "FF8D4E2A").toLong(16)

    private fun scheme(seed: Long, dark: Boolean) =
        dynamicColorScheme(seedColor = Color(seed), isDark = dark, isAmoled = false, style = PaletteStyle.TonalSpot)

    private fun png(px: Int, seed: Long = SEED, dark: Boolean = System.getProperty("icon.light") == null): ByteArray = ImageComposeScene(px, px, Density(1f)) {
        val c = scheme(seed, dark).markColors()
        Canvas(Modifier.fillMaxSize()) { drawUplinxMark(c) }
    }.use { it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes }

    private fun preview(out: File) {
        val seeds = listOf(0xFF8D4E2A to "Amber-brown", 0xFF6750A4 to "M3 baseline", 0xFF1F8BFF to "Azure", 0xFF006A6A to "Teal", 0xFFB3261E to "Red", 0xFF386A20 to "Green")
        ImageComposeScene(1320, 520, Density(1f)) {
            Column(Modifier.fillMaxSize().background(Color(0xFFF2F2F2)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (dark in listOf(false, true)) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    seeds.forEach { (seed, name) ->
                        val cs = scheme(seed, dark)
                        Column(Modifier.background(cs.surface).padding(12.dp)) {
                            Canvas(Modifier.size(170.dp)) { drawUplinxMark(cs.markColors()) }
                            Text(name + if (dark) " · dark" else "", color = cs.onSurface, fontSize = 13.sp)
                        }
                    }
                }
            }
        }.use { out.writeBytes(it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes) }
    }

    @JvmStatic fun main(args: Array<String>) {
        System.getProperty("icon.preview")?.let { preview(File(it)); println("preview written"); System.exit(0) }
        val sizes = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)
        val images = sizes.map { it to png(it) }
        val header = ByteBuffer.allocate(6 + 16 * images.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(0).putShort(1).putShort(images.size.toShort())
        var offset = 6 + 16 * images.size
        images.forEach { (s, data) ->
            header.put((if (s >= 256) 0 else s).toByte()).put((if (s >= 256) 0 else s).toByte())
            header.put(0).put(0).putShort(1).putShort(32).putInt(data.size).putInt(offset)
            offset += data.size
        }
        val ico = ByteArrayOutputStream().apply { write(header.array()); images.forEach { write(it.second) } }.toByteArray()
        File("packaging").mkdirs()
        File("packaging/uplinx.ico").writeBytes(ico)
        File("packaging/uplinx-512.png").writeBytes(png(512))
        File("src/main/resources/icon.png").writeBytes(png(256))
        println("icon written: ${ico.size} bytes, ${sizes.size} sizes")
        System.exit(0)
    }
}
