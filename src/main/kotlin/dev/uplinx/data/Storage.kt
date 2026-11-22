package dev.uplinx.data

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object Paths {
    val home: File = (System.getProperty("uplinx.home")?.let(::File) ?: defaultHome()).apply { mkdirs() }

    /** %APPDATA%/Uplinx. Data from the app's former name (MyProxy) is moved over once. */
    private fun defaultHome(): File {
        val base = File(System.getenv("APPDATA") ?: System.getProperty("user.home"))
        val home = File(base, "Uplinx")
        val legacy = File(base, "MyProxy")
        if (!home.exists() && legacy.isDirectory) {
            if (!legacy.renameTo(home)) runCatching { legacy.copyRecursively(home, overwrite = false) }
        }
        return home
    }
    val core: File = File(home, "core").apply { mkdirs() }
    val stateFile = File(home, "state.json")
    val runConfig = File(home, "run-config.json")
    val xrayExe get() = File(core, "xray.exe")
    val wintunDll get() = File(core, "wintun.dll")
}

object Storage {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }

    fun load(): AppState = runCatching {
        if (Paths.stateFile.exists()) json.decodeFromString<AppState>(Paths.stateFile.readText()) else AppState()
    }.getOrElse {
        // keep the broken file for the user instead of silently overwriting it
        Paths.stateFile.copyTo(File(Paths.home, "state.broken.json"), overwrite = true)
        AppState()
    }

    @Synchronized
    fun save(state: AppState) {
        val tmp = File(Paths.home, "state.json.tmp")
        tmp.writeText(json.encodeToString(AppState.serializer(), state))
        Files.move(tmp.toPath(), Paths.stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
