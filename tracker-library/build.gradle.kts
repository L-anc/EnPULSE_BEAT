plugins {
    id("dev.iclab.android.basic.library")
    /* Parceler runtime (required by the Samsung Health Data SDK aar) */
    id("kotlin-parcelize")
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "kaist.iclab.tracker"
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)

    /* Local config/state storage */
    implementation(libs.gson)
    implementation(libs.couchbase)

    /* Entity serialization */
    implementation(libs.kotlinx.serialization.json)

    // Samsung Dependencies
    api(project(":samsung-health-data-api"))
    api(project(":samsung-health-sensor-api"))

    // Testing
    testImplementation(libs.junit)
}
