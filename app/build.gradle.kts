plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android); alias(libs.plugins.kotlin.compose); alias(libs.plugins.ksp); alias(libs.plugins.hilt) }
android {
    namespace = "tv.airloom.app"; compileSdk = 35; buildToolsVersion = "35.0.0"
    defaultConfig { applicationId = "tv.airloom.app"; minSdk = 26; targetSdk = 35; versionCode = 24; versionName = "0.4.11"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
composeCompiler { stabilityConfigurationFile = rootProject.layout.projectDirectory.file("compose-stability.conf") }
dependencies {
    implementation(project(":core")); implementation(project(":data")); implementation(project(":playback"))
    implementation(libs.core.ktx); implementation(libs.activity.compose); implementation(libs.lifecycle.runtime); implementation(libs.lifecycle.viewmodel)
    implementation(platform(libs.compose.bom)); implementation(libs.compose.ui); implementation(libs.compose.foundation); implementation(libs.compose.icons); implementation(libs.tv.material)
    implementation(libs.coroutines.android); implementation(libs.coil); implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    debugImplementation(libs.compose.tooling); debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom)); androidTestImplementation(libs.compose.test); androidTestImplementation(libs.test.runner); androidTestImplementation(libs.test.junit); androidTestImplementation(libs.work)
}
