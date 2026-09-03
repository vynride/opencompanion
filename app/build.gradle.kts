plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// Keyword models are CC BY-NC-SA and must never ship; package the models
// directory through a filtered copy instead of wiring it in directly.
val modelAssetsDir: java.io.File =
    layout.buildDirectory
        .dir("generated/modelAssets")
        .get()
        .asFile
val copyModelAssets =
    tasks.register<Sync>("copyModelAssets") {
        from(rootProject.file("models"))
        exclude("wakeword/*.onnx")
        into(modelAssetsDir)
    }

android {
    namespace = "io.github.vynride.opencompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vynride.opencompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets.getByName("main") {
        assets.srcDir(modelAssetsDir)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.webkit)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:${kotlin.coreLibrariesVersion}")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Every variant task hangs off preBuild, so the filtered assets exist before merging.
tasks.named("preBuild") {
    dependsOn(copyModelAssets)
}
