plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android { namespace = "tv.airloom.playback"; compileSdk = 35; buildToolsVersion = "35.0.0"; defaultConfig { minSdk = 26 }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" } }
dependencies {
    implementation(project(":core")); implementation(libs.coroutines.android); implementation(libs.core.ktx)
    api(libs.media3.exoplayer); implementation(libs.media3.hls); implementation(libs.media3.dash)
    api(libs.media3.ui); implementation(libs.media3.session); implementation(libs.media3.okhttp)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
}
