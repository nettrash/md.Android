import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

plugins {
    alias(libs.plugins.android.application)
    // AGP 9's built-in Kotlin auto-applies the Kotlin Android plugin.
    // Adding it explicitly here would throw "Cannot add extension with
    // name 'kotlin' …" — see android.builtInKotlin in gradle.properties.
    alias(libs.plugins.kotlin.compose)
}

// versionCode strategy: read from `version.properties` at the repo root
// and auto-incremented after every successful assemble*/bundle* by the
// `bumpVersionCode` finalizer below. Mirrors the iOS app's `agvtool
// bump` post-build action — every Build button press bumps the number.
// versionName is human (semver); the release workflow can override it
// with `-PversionName=1.2.3` derived from the git tag.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use(::load)
    }
}
val storedVersionCode: Int = run {
    val raw = versionProps.getProperty("versionCode")
        ?: throw GradleException(
            "version.properties is missing or has no `versionCode` entry. " +
                "Commit it to the repo, or pass `-PversionCode=N` (strictly greater " +
                "than every versionCode previously uploaded to Play)."
        )
    val override = (project.findProperty("versionCode") as String?)?.toIntOrNull()
    override ?: raw.toInt()
}

val resolvedVersionName: String =
    (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.5"

val skipVersionBump: Boolean = project.hasProperty("noBump")

// Resolve release signing from `keystore.properties` (developer
// machines) or MD_KEYSTORE_* env vars (CI). Returns null when nothing is
// configured — the release build then falls back to the debug signing
// config so `assembleRelease` still works locally (just not uploadable).
val releaseSigning: Map<String, String>? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        val p = Properties().apply { propsFile.inputStream().use(::load) }
        mapOf(
            "storeFile" to (p.getProperty("storeFile") ?: return@run null),
            "storePassword" to (p.getProperty("storePassword") ?: return@run null),
            "keyAlias" to (p.getProperty("keyAlias") ?: return@run null),
            "keyPassword" to (p.getProperty("keyPassword") ?: return@run null),
        )
    } else {
        val path = System.getenv("MD_KEYSTORE_PATH")
        val storePassword = System.getenv("MD_KEYSTORE_PASSWORD")
        val keyAlias = System.getenv("MD_KEY_ALIAS")
        val keyPassword = System.getenv("MD_KEY_PASSWORD")
        if (path != null && storePassword != null && keyAlias != null && keyPassword != null) {
            mapOf(
                "storeFile" to path,
                "storePassword" to storePassword,
                "keyAlias" to keyAlias,
                "keyPassword" to keyPassword,
            )
        } else null
    }
}

android {
    namespace = "me.nettrash.md"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.nettrash.md"
        // Android 12 (API 31) and later, from 1.5 — md began at API 36 only.
        // 31 is the floor the file associations set: the manifest's
        // `android:pathSuffix` filters (what makes a `.mdown` or `.puml`
        // open in md at all) exist from API 31. Nothing else in md needs
        // more: lint at 31 reports no newer API, there is no SDK_INT branch,
        // every picker is a SAF contract, Back is AndroidX's BackHandler
        // (classic Back below 33, predictive from 33), `enableEdgeToEdge()`
        // covers 31–34, and a themed icon's monochrome layer is simply unused
        // before Android 13.
        minSdk = 31
        targetSdk = 36
        versionCode = storedVersionCode
        versionName = resolvedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        releaseSigning?.let { sig ->
            create("release") {
                storeFile = rootProject.file(sig.getValue("storeFile"))
                storePassword = sig.getValue("storePassword")
                keyAlias = sig.getValue("keyAlias")
                keyPassword = sig.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        // A debug build installs BESIDE the Play copy instead of over it: its
        // own application id (me.nettrash.md.debug) and its own launcher name,
        // "md debug" (src/debug/res/values/strings.xml). Without this the two
        // share an id but not a signing key, so Android refuses to install a
        // test build on a phone that has md from Google Play — the only way in
        // was to uninstall the real app and lose its preferences. Release
        // builds are untouched. Nothing in the code hard-codes the package:
        // the FileProvider authority is ${applicationId} in the manifest and
        // context.packageName in Exporter.kt.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Only the parent for the XML splash window theme
    // (`Theme.Material3.*`). Compose draws everything at runtime.
    implementation(libs.material)

    // SAF folder-tree walking for the book navigator (`book/Book.kt`).
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---- IDE compatibility: legacy aggregate test-class tasks --------------
// AGP 9 stopped creating the legacy aggregate `unitTestClasses` /
// `androidTestClasses` tasks; Android Studio's Gradle sync still invokes
// those names on some code paths. Register thin aliases so the IDE is
// happy — pure aggregators that dependsOn the per-variant compile tasks.
afterEvaluate {
    if (tasks.findByName("unitTestClasses") == null) {
        tasks.register("unitTestClasses") {
            group = "verification"
            dependsOn(tasks.matching {
                val n = it.name
                n.startsWith("compile") &&
                    (n.endsWith("UnitTestKotlin") || n.endsWith("UnitTestJavaWithJavac"))
            })
        }
    }
    if (tasks.findByName("androidTestClasses") == null) {
        tasks.register("androidTestClasses") {
            group = "verification"
            dependsOn(tasks.matching {
                val n = it.name
                n.startsWith("compile") &&
                    (n.endsWith("AndroidTestKotlin") || n.endsWith("AndroidTestJavaWithJavac"))
            })
        }
    }
}

// ---- versionCode auto-bump ----------------------------------------------
// Mirrors the iOS app's `agvtool bump`: every successful assemble/bundle
// rewrites `version.properties` with `versionCode + 1`, effective on the
// next build. `doLast` only fires on success, so failed builds don't bump.
// Opt out per-build with `-PnoBump`.
val bumpedInThisInvocation = AtomicBoolean(false)
afterEvaluate {
    if (skipVersionBump) return@afterEvaluate
    listOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease").forEach { taskName ->
        tasks.findByName(taskName)?.doLast {
            if (!bumpedInThisInvocation.compareAndSet(false, true)) return@doLast
            val newValue = storedVersionCode + 1
            versionProps.setProperty("versionCode", newValue.toString())
            versionPropsFile.outputStream().use {
                versionProps.store(it, "Auto-incremented after build. Edit only if you know what you're doing.")
            }
            logger.lifecycle(":md: bumped versionCode $storedVersionCode -> $newValue (effective next build)")
        }
    }
}
