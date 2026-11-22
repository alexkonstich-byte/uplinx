import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "dev.uplinx"
version = (findProperty("appVersion") as String?) ?: "1.6.3"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")
    implementation("org.yaml:snakeyaml:2.6")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.uplinx.MainKt"
        jvmArgs += listOf(
            "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Duplinx.version=${project.version}",
            // a small heap that is handed back to Windows when idle, instead of the JVM default (1/4 of RAM)
            "-Xms48m", "-Xmx320m", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=15000",
            "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=25", "-XX:+UseStringDeduplication",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Uplinx"
            packageVersion = project.version.toString()
            // shown by Windows as the process name in Task Manager
            description = "Uplinx Client"
            vendor = "Uplinx"
            copyright = "© 2026 Uplinx"
            modules("java.instrument", "java.net.http", "jdk.unsupported", "jdk.crypto.ec", "java.naming", "jdk.charsets", "jdk.localedata")
            windows {
                menuGroup = "Uplinx"
                iconFile.set(project.file("packaging/uplinx.ico"))
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b1e1a3c-5d0f-4a8e-9a43-2a6f4d9c7e11"
            }
        }
    }
}

// Offscreen UI screenshots with demo data (no window, no core, no system changes).
tasks.register<JavaExec>("screenshots") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.uplinx.Screenshots"
    listOf("uplinx.home", "shots.out", "shots.dark", "shots.seed", "shots.lang").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
}

// Regenerates packaging/uplinx.ico, packaging/uplinx-512.png and the in-app icon from AppIcon.kt.
tasks.register<JavaExec>("icon") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.uplinx.IconGen"
    workingDir = projectDir
    listOf("icon.preview", "icon.seed").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
}

tasks.register<JavaExec>("pingCheck") {
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "dev.uplinx.PingCheck"
    listOf("uplinx.home", "ping.count").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
}
