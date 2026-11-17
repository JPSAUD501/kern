plugins {
    alias(libs.plugins.android.application)
}

// A test app of text fields that behave like the apps Kern must work with. Its instrumented tests
// drive the installed Kern keyboard through UI Automator (D16).
android {
    namespace = "io.github.jpsaud501.kern.bench"
    compileSdk = libs.versions.android.target.sdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.jpsaud501.kern.bench"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = libs.versions.android.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}
