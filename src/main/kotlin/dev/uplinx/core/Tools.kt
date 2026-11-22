package dev.uplinx.core

import dev.uplinx.L

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import dev.uplinx.data.Paths
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
    val version: String = System.getProperty("uplinx.version") ?: "dev"
    const val REPO = "alexkonstich-byte/uplinx"
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
    /** Running programs: exe name → full path (first seen), for pickers with icons. */
    fun runningWithPaths(): Map<String, String> = ProcessHandle.allProcesses().toList()
        .mapNotNull { it.info().command().orElse(null) }
        .filter { it.endsWith(".exe", ignoreCase = true) && !it.contains("\\Windows\\", ignoreCase = true) }
        .groupBy { File(it).name.lowercase() }
        .mapValues { it.value.first() }
        .toSortedMap()

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, java.awt.image.BufferedImage?>()

    /** The Windows shell icon of an executable (32 px), cached. */
    fun icon(path: String?): java.awt.image.BufferedImage? {
        if (path == null) return null
        return iconCache.getOrPut(path) {
            runCatching {
                val ic = javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(File(path), 32, 32) ?: return@runCatching null
                val img = java.awt.image.BufferedImage(ic.iconWidth, ic.iconHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                val g = img.createGraphics(); ic.paintIcon(null, g, 0, 0); g.dispose(); img
            }.getOrNull()
        }
    }

    fun running(): List<String> = ProcessHandle.allProcesses().toList()
        .mapNotNull { it.info().command().orElse(null)?.let { c -> File(c).name } }
        .filter { it.endsWith(".exe", ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }
}

data class UpdateInfo(val version: String, val notes: String, val url: String, val assetUrl: String?, val zipUrl: String? = null)

/**
 * Updates from this project's GitHub Releases. Installed copies download and run the setup .exe;
 * portable copies (a `portable.txt` next to Uplinx.exe) download the zip and swap files after exit.
 */
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

    /** Folder of the running Uplinx.exe, or null when started from Gradle. */
    val appDir: File? get() = ProcessHandle.current().info().command().orElse(null)?.let(::File)
        ?.takeIf { it.name.equals("Uplinx.exe", ignoreCase = true) || it.name.equals("MyProxy.exe", ignoreCase = true) }?.parentFile
    val isPortable: Boolean get() = appDir?.let { File(it, "portable.txt").exists() } == true

    private fun request(path: String): HttpRequest = HttpRequest.newBuilder(URI("https://api.github.com/repos/${BuildInfo.REPO}/$path"))
        .header("Accept", "application/vnd.github+json").header("User-Agent", "Uplinx-updater").timeout(Duration.ofSeconds(20)).build()

    private fun info(o: kotlinx.serialization.json.JsonObject): UpdateInfo {
        val assets = (o["assets"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        fun asset(pred: (String) -> Boolean) = assets.firstOrNull { pred(it["name"]?.jsonPrimitive?.content.orEmpty()) }
            ?.get("browser_download_url")?.jsonPrimitive?.content
        return UpdateInfo(
            o["tag_name"]!!.jsonPrimitive.content.trimStart('v'), o["body"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["html_url"]!!.jsonPrimitive.content,
            asset { it.endsWith(".exe") }, asset { it.endsWith(".zip") && "portable" in it },
        )
    }

    fun check(): UpdateInfo? {
        val resp = http.send(request("releases/latest"), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() != 200) error("HTTP ${resp.statusCode()}")
        val u = info(Json.parseToJsonElement(resp.body()).jsonObject)
        return u.takeIf { BuildInfo.version == "dev" || newer(it.version, BuildInfo.version) }
    }

    /** Release notes of a given version, for the "What's new" screen after an update. */
    fun notesFor(version: String): UpdateInfo? = runCatching {
        val resp = http.send(request("releases/tags/v$version"), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() == 200) info(Json.parseToJsonElement(resp.body()).jsonObject) else null
    }.getOrNull()

    private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val resp = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "Uplinx-updater").build(), HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() !in 200..299) error("HTTP ${resp.statusCode()}")
        val total = resp.headers().firstValueAsLong("content-length").orElse(-1)
        resp.body().use { input ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (total > 0) onProgress(done.toFloat() / total) }
            }
        }
    }

    /** Downloads the update and starts whatever applies it; the caller then exits. */
    fun downloadAndRun(info: UpdateInfo, onProgress: (Float) -> Unit) {
        val dir = appDir
        if (isPortable && dir != null && info.zipUrl != null) {
            val work = File(Paths.home, "update").apply { deleteRecursively(); mkdirs() }
            val zip = File(work, "update.zip")
            download(info.zipUrl, zip, onProgress)
            java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val f = File(work, "new/" + e.name)
                    if (!f.canonicalPath.startsWith(work.canonicalPath)) continue // zip-slip guard
                    if (e.isDirectory) f.mkdirs() else { f.parentFile.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
            }
            val src = File(work, "new").listFiles()?.singleOrNull { it.isDirectory } ?: File(work, "new")
            // wait for this process to exit, mirror the new files over the app folder, start it again
            val script = File(work, "apply.ps1")
            script.writeText("""
                ${'$'}ErrorActionPreference = 'SilentlyContinue'
                Wait-Process -Id ${ProcessHandle.current().pid()} -Timeout 60
                robocopy "${src.path}" "${dir.path}" /MIR /R:3 /W:1 /NFL /NDL /NJH /NJS | Out-Null
                New-Item -ItemType File -Force "${dir.path}\portable.txt" | Out-Null
                Start-Process "${File(dir, "Uplinx.exe").path}"
            """.trimIndent())
            ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.path).start()
            return
        }
        val url = info.assetUrl ?: error(L("В релизе нет установщика"))
        val target = File(Paths.home, "Uplinx-${info.version}.exe")
        download(url, target, onProgress)
        ProcessBuilder(target.path).start()
    }
}
