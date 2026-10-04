// Plain JVM module: the sources compile against android.jar only (every
// hidden API is reached reflectively at runtime), then d8 converts the
// jar to a dex jar that `app_process` can load as a shell-uid service.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val androidHome: String = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: error("ANDROID_HOME/ANDROID_SDK_ROOT not set")
val androidJar = "$androidHome/platforms/android-35/android.jar"
val d8 = "$androidHome/build-tools/35.0.0/d8"

dependencies {
    compileOnly(files(androidJar))
}

val dexJar by tasks.registering(Exec::class) {
    group = "build"
    description = "d8 the host jar into app/src/main/assets-compatible dex"
    val jarFile = layout.buildDirectory.file("libs/vscreen.jar")
    val outFile = layout.buildDirectory.file("assets/vscreen.jar")
    inputs.files(tasks.named("jar"))
    inputs.file(androidJar)
    outputs.file(outFile)
    doFirst { outFile.get().asFile.parentFile.mkdirs() }
    // d8 shells out to `java` — give it the toolchain JDK when PATH lacks it.
    environment("PATH", "${System.getenv("JAVA_HOME") ?: ""}/bin:${System.getenv("PATH")}")
    commandLine(
        d8, "--release", "--min-api", "30",
        "--lib", androidJar,
        "--output", outFile.get().asFile.absolutePath,
        jarFile.get().asFile.absolutePath,
    )
}
