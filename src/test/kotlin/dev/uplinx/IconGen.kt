package dev.uplinx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import dev.uplinx.ui.drawUplinxMark
import org.jetbrains.skia.EncodedImageFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders the Uplinx mark to PNGs and a multi-size Windows .ico (PNG-compressed entries). */
object IconGen {
    private fun png(px: Int): ByteArray = ImageComposeScene(px, px, Density(1f)) {
        Canvas(Modifier.fillMaxSize()) { drawUplinxMark() }
    }.use { it.render().encodeToData(EncodedImageFormat.PNG)!!.bytes }

    @JvmStatic fun main(args: Array<String>) {
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
