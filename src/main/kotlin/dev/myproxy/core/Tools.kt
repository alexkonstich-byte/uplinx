package dev.myproxy.core

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import dev.myproxy.data.Paths
import kotlinx.serialization.json.*
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

object BuildInfo {
    val version: String = System.getProperty("myproxy.version") ?: "dev"
    const val REPO = "alexkonstich-byte/myproxy"
}

/** QR codes from the screen (all monitors), a clipboard image or an image file — the desktop "camera". */
object QrScanner {
    private fun decode(img: BufferedImage): List<String> = runCatching {
        val bmp = BinaryBitmap(HybridBinarizer(BufferedImageLuminanceSource(img)))
        QRCodeMultiReader().decodeMultiple(bmp, mapOf(DecodeHintType.TRY_HARDER to true)).map { it.text }
    }.getOrDefault(emptyList())

    fun fromScreen(): List<String> {
        val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
        return ge.screenDevices.flatMap { dev ->
            val b: Rectangle = dev.defaultConfiguration.bounds
            runCatching { decode(Robot(dev).createScreenCapture(b)) }.getOrDefault(emptyList())
        }.distinct()
    }

    fun fromClipboard(): List<String> = runCatching {
        val img = Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.imageFlavor) as java.awt.Image
        val bi = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
        bi.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        decode(bi)
    }.getOrDefault(emptyList())

    fun fromFile(f: File): List<String> = runCatching { ImageIO.read(f)?.let(::decode).orEmpty() }.getOrDefault(emptyList())

    fun isImage(f: File) = f.extension.lowercase() in setOf("png", "jpg", "jpeg", "bmp", "gif", "webp")
}

/** Executables currently running — for "per-app" routing rules (Windows' answer to Android per-app VPN). */
object Processes {
    fun running(): List<String> = ProcessHandle.allProcesses().toList()
        .mapNotNull { it.info().command().orElse(null)?.let { c -> File(c).name } }
        .filter { it.endsWith(".exe", ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
}

data class UpdateInfo(val version: String, val notes: String, val url: String, val assetUrl: String?)

/** Checks this project's GitHub releases; downloads and starts the installer on request. */
object Updater {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

    private fun parts(v: String) = v.trimStart('v').split('.', '-').mapNotNull { it.toIntOrNull() }

    fun newer(a: String, b: String): Boolean {
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    fun check(): UpdateInfo? {
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/${BuildInfo.REPO}/releases/latest"))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "MyProxy-updater").timeout(Duration.ofSeconds(20)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()}")
        val o = Json.parseToJsonElement(resp.body()).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content ?: return null
        if (BuildInfo.version != "dev" && !newer(tag, BuildInfo.version)) return null
        val exe = (o["assets"] as? JsonArray)?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".exe") == true }
        return UpdateInfo(tag.trimStart('v'), o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["html_url"]!!.jsonPrimitive.content,
            exe?.get("browser_download_url")?.jsonPrimitive?.content)
    }

    /** Downloads the installer and starts it; the caller exits so files can be replaced. */
    fun downloadAndRun(info: UpdateInfo, onProgress: (Float) -> Unit) {
        val url = info.assetUrl ?: error("В релизе нет установщика")
        val target = File(Paths.home, "MyProxy-${info.version}.exe")
        val resp = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "MyProxy-updater").build(), HttpResponse.BodyHandlers.ofInputStream())
        val total = resp.headers().firstValueAsLong("content-length").orElse(-1)
        resp.body().use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (total > 0) onProgress(done.toFloat() / total) }
            }
        }
        ProcessBuilder(target.path).start()
    }
}
