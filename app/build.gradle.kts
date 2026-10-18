plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mcpintelligence.fr3k.hud"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.mcpintelligence.fr3k.hud"
        minSdk = 31
        targetSdk = 35
        versionCode = 422
        versionName = "0.4.22"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The pinned source-built engine already carries Gson and the ListenableFuture API.
// Avoid packaging a second copy from the existing AndroidX/Shizuku graph.
configurations.configureEach {
    exclude(group = "com.google.code.gson", module = "gson")
    exclude(group = "com.google.guava", module = "listenablefuture")
    // dexlib2 pulls a full Guava graph. The pinned lspatch engine already bundles a newer,
    // proven-in-production copy (guava 32.0.1-jre + jsr305 + errorprone + j2objc + failureaccess +
    // checker-qual). Exclude every resolved copy so the bundled one stays the single provider.
    exclude(group = "com.google.guava", module = "guava")
    exclude(group = "com.google.guava", module = "failureaccess")
    exclude(group = "com.google.code.findbugs", module = "jsr305")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
    exclude(group = "org.checkerframework", module = "checker-qual")
}

// Use one current Bouncy Castle implementation for both the patch engine and ADB pairing.
// Keep the pinned source jar intact; strip only its bundled duplicate crypto classes at build time.
val patchEngineWithoutBundledCrypto by tasks.registering(Jar::class) {
    from(zipTree("libs/lspatch-fixture-qualified.jar"))
    exclude("org/bouncycastle/**")
    archiveFileName.set("lspatch-engine.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/patch-engine"))
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation(project(":capability"))
    implementation(project(":core"))
    implementation(project(":protocol"))
    implementation(project(":transport"))
    implementation(project(":ui"))
    // Private lab engine, source-built and pinned; limited to the owned fixture.
    implementation(files(patchEngineWithoutBundledCrypto))

    // App-modification analysis: pinned pure-JVM parsers. ARSCLib = binary manifest/resource
    // inspection (Apache-2.0), smali-dexlib2 = DEX indexing/disassembly (BSD-3-Clause).
    implementation("io.github.reandroid:ARSCLib:1.4.0")
    implementation("com.android.tools.smali:smali-dexlib2:3.0.10")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // Shizuku AAR — gives us the real `Shizuku.requestPermission()` and
    // `ShizukuService` classes. The reflection-only path silently fails
    // because the AAR isn't on the classpath, so SUI never sees us in
    // its "apps that can use this" list. With the AAR, requestPermission
    // pops the SUI grant dialog and our package gets registered.
    implementation("dev.rikka.shizuku:api:13.1.5")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // JVM unit tests — file-parsing and pure-Kotlin contract tests.
    // Instrumented tests are NOT wired here; physical-device assertions
    // live in scripts/device/.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Reviewed fixed payloads for data-only helper-call patches. No compiler ships to the phone.
val appModClasses = tasks.register<JavaCompile>("compileAppModTemplates") {
    source(rootProject.file("qualification/appmod-templates"))
    classpath = files(android.bootClasspath)
    options.release.set(8)
    destinationDirectory.set(layout.buildDirectory.dir("generated/appmod/classes"))
}
val appModJar = tasks.register<Jar>("jarAppModTemplates") {
    dependsOn(appModClasses)
    from(appModClasses.flatMap { it.destinationDirectory })
    archiveFileName.set("helpers.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/appmod"))
}
val appModAssets = layout.buildDirectory.dir("generated/appmod/assets")
val appModDex = tasks.register<Exec>("dexAppModTemplates") {
    dependsOn(appModJar)
    inputs.file(appModJar.flatMap { it.archiveFile })
    outputs.file(appModAssets.map { it.file("appmod/classes.dex") })
    doFirst {
        val destination = appModAssets.get().dir("appmod").asFile.apply { mkdirs() }
        commandLine(System.getProperty("java.home") + "/bin/java", "-cp",
            File(android.sdkDirectory, "build-tools/35.0.1/lib/d8.jar"), "com.android.tools.r8.D8", "--min-api", "31",
            "--lib", File(android.sdkDirectory, "platforms/android-35/android.jar"), "--output", destination,
            appModJar.get().archiveFile.get().asFile)
    }
}
android.sourceSets.getByName("main").assets.srcDir(appModAssets)
tasks.named("preBuild") { dependsOn(appModDex) }
