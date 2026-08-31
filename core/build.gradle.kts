plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    // The Android AAR supplies ai.onnxruntime at runtime; the JVM jar only here for compile and tests.
    compileOnly(libs.onnxruntime.jvm)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.onnxruntime.jvm)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:${kotlin.coreLibrariesVersion}")
}

tasks.test {
    useJUnitPlatform()
}
