plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load signing properties if the file exists (local, never committed)
fun loadSigningProps(): Map<String, String> {
    val f = rootProject.file("../android/signing/signing.properties")
    if (!f.exists()) return emptyMap()
    return f.readLines()
        .filter { it.contains("=") && !it.startsWith("#") }
        .associate { line ->
            val idx = line.indexOf('=')
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
}
val signingProps = loadSigningProps()

android {
    namespace = "io.raventag.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.raventag.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "IPFS_GATEWAY", "\"https://ipfs.io/ipfs/\"")
        buildConfigField(
            "String",
            "IPFS_GATEWAYS",
            "\"https://ipfs.io/ipfs/,https://cloudflare-ipfs.com/ipfs/,https://gateway.pinata.cloud/ipfs/\""
        )
        buildConfigField("String", "API_BASE_URL", "\"https://api.raventag.com\"")
        buildConfigField("String", "ADMIN_KEY", "\"\"")
    }

    flavorDimensions += "variant"
    productFlavors {
        create("brand") {
            dimension = "variant"
            applicationId = "io.raventag.app.brand"
            versionNameSuffix = "-brand"
            resValue("string", "app_name", "RavenTag Brand")
            buildConfigField("Boolean", "IS_BRAND", "true")
        }
        create("consumer") {
            dimension = "variant"
            applicationId = "io.raventag.app"
            versionNameSuffix = "-consumer"
            // app_name overridden in consumer/res/values/strings.xml
            buildConfigField("Boolean", "IS_BRAND", "false")
        }
    }

    if (signingProps.isNotEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file("../${signingProps["KEYSTORE_FILE"]}")
                storePassword = signingProps["KEYSTORE_PASSWORD"]
                keyAlias = signingProps["KEY_ALIAS"]
                keyPassword = signingProps["KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/BCKEY.DSA"
            excludes += "META-INF/BCKEY.SF"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.bouncy.castle)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.security.crypto)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.splashscreen)
    implementation(libs.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
