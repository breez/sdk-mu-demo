plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    application
}

group = "technology.breez.demo"
version = "0.1.0"

repositories {
    // mavenLocal first so `make setup LOCAL_SDK=1` (which publishes the
    // KMP bindings to ~/.m2) wins over the remote artifact.
    mavenLocal()
    // `libs/m2` is a per-host staged mirror of the KMP-jvm artifact. The
    // published 0.1.0 isn't (yet) on `mvn.breez.technology`, so this is
    // the path Docker / clean clones resolve through. See README "Deploy".
    maven { url = uri("libs/m2") }
    mavenCentral()
    maven { url = uri("https://mvn.breez.technology/releases") }
}

dependencies {
    // SDK — version must match libraryVersion in
    // spark-sdk/crates/breez-sdk/bindings/langs/kotlin-multiplatform/gradle.properties.
    implementation("technology.breez.spark:breez-sdk-spark-kmp-jvm:0.1.0")
    implementation("com.ionspin.kotlin:bignum:0.3.10")
    implementation("net.java.dev.jna:jna:5.18.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.ktor:ktor-server-core-jvm:2.3.13")
    implementation("io.ktor:ktor-server-netty-jvm:2.3.13")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:2.3.13")
    implementation("io.ktor:ktor-server-status-pages-jvm:2.3.13")
    implementation("io.ktor:ktor-server-cors-jvm:2.3.13")
    implementation("io.ktor:ktor-server-rate-limit-jvm:2.3.13")
    implementation("io.ktor:ktor-server-call-logging-jvm:2.3.13")

    implementation("ch.qos.logback:logback-classic:1.5.6")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // DB: JDBC pool + migrations. The SDK manages its own MySQL schema
    // via the shared context; Flyway/HikariCP here are app-only.
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:9.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-mysql:10.20.1")
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(17)
}

// LOCAL_SDK=1 builds the Rust dylib at <spark-sdk-root>/target/release/
// (libbreez_sdk_spark_bindings.{dylib,so}). The published KMP JAR doesn't
// bundle the lib for arbitrary host arches — JNA loads it from
// jna.library.path at runtime. SDK_PATH defaults to ../spark-sdk.
val sdkPathProp: String = (findProperty("sdkPath") as? String)
    ?: System.getenv("SDK_PATH")
    ?: "../spark-sdk"
val nativeLibPath: String = file("$sdkPathProp/target/release").absoluteFile.canonicalPath

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    systemProperty("jna.library.path", nativeLibPath)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
