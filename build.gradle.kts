import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "dev.myproxy"
version = "0.1.0"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
    implementation("com.google.zxing:core:3.5.4")

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
        mainClass = "dev.myproxy.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MyProxy"
            packageVersion = "1.0.0"
            description = "Material You proxy client powered by Xray"
            modules("java.net.http", "jdk.crypto.ec", "java.naming")
            windows {
                menuGroup = "MyProxy"
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
    mainClass = "dev.myproxy.Screenshots"
    listOf("myproxy.home", "shots.out", "shots.dark", "shots.seed").forEach { k -> System.getProperty(k)?.let { systemProperty(k, it) } }
}
