plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.ksp); alias(libs.plugins.hilt) }
android { namespace = "tv.airloom.data"; compileSdk = 35; buildToolsVersion = "35.0.0"; defaultConfig { minSdk = 26; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }; compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget = "17" }; sourceSets.getByName("androidTest").assets.srcDir("schemas") }
dependencies {
    implementation(project(":core")); implementation(libs.core.ktx); implementation(libs.coroutines.android)
    implementation(libs.room.runtime); implementation(libs.room.ktx); ksp(libs.room.compiler)
    implementation(libs.datastore); implementation(libs.work); implementation(libs.okhttp)
    implementation(libs.hilt.android); ksp(libs.hilt.compiler)
    testImplementation(libs.junit); testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.test.runner); androidTestImplementation(libs.test.junit)
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
