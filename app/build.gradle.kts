import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is read from a gitignored keystore.properties at the repo root
// (storeFile/storePassword/keyAlias/keyPassword). When it's absent — a fresh clone,
// CI without the secret — the release build falls back to debug signing so it still
// produces a sideloadable APK; only a real distribution build needs the keystore.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

/**
 * The GIF search key the app ships with, so that GIFs work on a fresh install with nothing to set
 * up. Read exactly like [reportToken] above — `local.properties` (git-ignored) for a local build,
 * the `KLIPY_KEY` repository secret in CI — and for the same reason: **this repository is public**,
 * and a key committed to it is a key that is scraped within days and rate-limited for every user of
 * the app. An empty string is a working build; GIF search is then off until somebody puts their own
 * key in Settings, which is the same state the app shipped in before this.
 */
val klipyKey: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("klipyKey")
    } else {
        null
    }
    fromFile ?: System.getenv("KLIPY_KEY") ?: ""
}

/**
 * Scrambles [value] so it is not a readable string in the shipped APK.
 *
 * XOR against a fixed pad, then Base64 — undone by `KlipyKey` at runtime. **Be clear about what
 * this buys.** Anything the app can decode, a person holding the APK can decode too: the pad is in
 * the same binary. What it stops is the cheap attack, which is also the only one that happens at
 * this scale — `strings app.apk | grep -i klipy`, or a scraper walking public repositories for
 * things shaped like API keys. Someone willing to open the APK in a decompiler was always going to
 * get it, key or no key, which is why the real protection is that this key can be rotated from the
 * partner panel and the app keeps working from the next build.
 */
fun scramble(value: String): String {
    if (value.isEmpty()) return ""
    val pad = "brightchat".toByteArray(Charsets.UTF_8)
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor pad[i % pad.size].toInt()).toByte() }
    // `java.util.Base64` spelled out would resolve `java` to Gradle's own `java` extension, not
    // the package — hence the import at the top of this file.
    return Base64.getEncoder().encodeToString(out)
}

android {
    namespace = "com.gios.lightchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gios.lightchat"
        minSdk = 34   // Light Phone III runs Android 14 — the only target device.
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 18
        versionName = "2.37.0"

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        // Scrambled, not encrypted — see [scramble]. Decoded by `api/KlipyKey.kt`.
        buildConfigField("String", "KLIPY_KEY", "\"${scramble(klipyKey)}\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Sign debug with the release key too, when it's available, so a local
            // `adb install -r` replaces the installed release instead of failing on a
            // certificate mismatch.
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // A stable release key is not optional. Android identifies an app by
            // (packageName, signing certificate), so a build signed with the runner's
            // throwaway debug keystore — regenerated per job — installs once and then
            // fails every Obtainium update with an opaque "Failure: Invalid". Locally
            // a debug-signed APK is fine and still sideloads; in CI it is a bug, so
            // fail the build rather than publish one.
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                if (System.getenv("CI") != null) {
                    throw GradleException(
                        "keystore.properties is missing: the release keystore secret did " +
                            "not decode. Refusing to publish an APK signed with a " +
                            "throwaway key. See .github/workflows/build.yml.",
                    )
                }
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    // Shake-to-report, the wheel, and the LightSync backup provider. The wheel arrived in
    // the library at 1.2.0; until this version it was a vendored copy under
    // com.gios.lightchat.hw, which is now deleted.
    implementation("com.gios:light-common:1.9.0")
    // What actually applies the baseline profile that ships inside light-common's AAR.
    // Below API 31 nothing on the device reads a profile on its own — the installer only
    // learned to on Android 12 — so without this the profile is inert bytes in the APK and
    // the first cold start after an update is fully interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    // For LocalLifecycleOwner, which the camera controller binds to. The copy in
    // androidx.compose.ui.platform is deprecated in favour of this one.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    // CameraX, for the in-app viewfinder. Same versions as LightTip, which is where
    // the "grab PreviewView.bitmap instead of an ImageCapture round-trip" trick that
    // CameraScreen uses was worked out on this hardware.
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // QR decoding for the agent-config scanner. Pure-Java ZXing core from Maven
    // Central — no Google Play Services, matching the rest of the app.
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // WorkManager, only for DeliveryWorker: a catch-up path that lives in JobScheduler
    // instead of AlarmManager, so it survives the things that break the alarm chain
    // (force-stop, app update with the process dead) and is restored after a reboot with
    // no receiver of ours involved. AndroidX, not Play Services — it has no Google
    // dependency and doesn't drag one in.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // REST is plain HttpURLConnection + org.json (bundled in the platform), like
    // hive/pod. The live event feed is Socket.IO — a plain JVM client (pulls
    // okhttp + engine.io), NOT a Google dependency. org.json is excluded because
    // the platform already provides it (avoids a duplicate-class build error).
    implementation("io.socket:socket.io-client:2.1.0") {
        exclude(group = "org.json", module = "json")
    }
    // No Google Play Services / Firebase anywhere — that's the whole point.

    // The first unit tests in this app, for LoginCodes. It is pure Kotlin with no Android
    // imports precisely so it can be tested on the JVM — the parser decides what gets pinned
    // to the keyboard's suggestion strip, and being wrong there is silent.
    testImplementation("junit:junit:4.13.2")
    // A real org.json on the *unit test* classpath only. The platform provides org.json at
    // runtime, so the app never bundles it (it is even excluded from socket.io above) — but the
    // android.jar unit tests compile against is the stub one, whose every method throws
    // "Stub!". Anything parsing JSON is therefore untestable without this, which is why
    // NewsletterJson had no test until it got one. Not `implementation`: adding it there would
    // put a second copy of these classes in the APK and fail the build on duplicates.
    testImplementation("org.json:json:20240303")
}
