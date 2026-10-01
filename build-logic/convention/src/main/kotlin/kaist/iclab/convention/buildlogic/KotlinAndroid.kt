package kaist.iclab.convention.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal fun Project.configureKotlinAndroid(
    commonExtension: CommonExtension,
) {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

    val compileSdkVersion = libs.findVersion("compileSdk").get().requiredVersion.toInt()
    val minSdkVersion = libs.findVersion("minSdk").get().requiredVersion.toInt()

    when (commonExtension) {
        is ApplicationExtension -> commonExtension.apply {
            compileSdk = compileSdkVersion
            defaultConfig {
                minSdk = minSdkVersion
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables.useSupportLibrary = true
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        is LibraryExtension -> commonExtension.apply {
            compileSdk = compileSdkVersion
            defaultConfig {
                minSdk = minSdkVersion
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
    }
}

internal fun Project.configureKotlinCompilerOption(
    extension: KotlinAndroidProjectExtension
) {
    extension.apply {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}