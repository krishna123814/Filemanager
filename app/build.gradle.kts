plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.krishna.myfiles"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.krishna.myfiles"
        minSdk = 24
        targetSdk = 34

        val vcProp = (project.findProperty("vc") as String?)?.toIntOrNull()
        versionCode = vcProp ?: (System.currentTimeMillis() / 60000L).toInt()
        versionName = "1.0"

        // GitHub Actions -PgithubRepo=<owner/repo> deta hai (auto-update isi repo ke Releases se hota hai)
        val repoProp = (project.findProperty("githubRepo") as String?)
            ?.takeIf { it.isNotBlank() } ?: ""
        buildConfigField("String", "UPDATE_REPO", "\"$repoProp\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Keystore GitHub Secrets se build ke waqt app/myfiles-release.jks me banti hai (repo me nahi).
    val ks = file("myfiles-release.jks")
    val ksPass = System.getenv("LION_STORE_PASSWORD")
    val ksAlias = System.getenv("LION_KEY_ALIAS") ?: "lion"
    val ksKeyPass = System.getenv("LION_KEY_PASSWORD") ?: ksPass
    if (ks.exists() && !ksPass.isNullOrEmpty()) {
        signingConfigs {
            create("mine") {
                storeFile = ks
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("mine")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("mine")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:33.2.1-android")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
}
