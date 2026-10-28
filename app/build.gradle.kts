plugins {
    id("com.android.application"); id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose"); id("com.google.devtools.ksp")
}
android {
    namespace = "app.dim"; compileSdk = 36
    defaultConfig { applicationId = "app.dim"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Compose foundation/ui only: no Material artifacts.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui"); implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.7.1"); implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    // Health Connect (read-only sleep, steps, heart rate). Needs minSdk 26+.
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Unit tests (src/test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
