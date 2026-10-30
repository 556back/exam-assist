plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "cn.examassist.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.examassist.app"; minSdk = 26; targetSdk = 35; versionCode = 22; versionName = "1.0.0"
        testInstrumentationRunner = "cn.examassist.app.AnswerOverlayCheck"
    }
    val signingPath = System.getenv("EXAM_ASSIST_KEYSTORE")
    signingConfigs {
        if (!signingPath.isNullOrBlank()) create("distribution") {
            storeFile = file(signingPath)
            storePassword = requireNotNull(System.getenv("EXAM_ASSIST_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("EXAM_ASSIST_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("EXAM_ASSIST_KEY_PASSWORD"))
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!signingPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
