plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kern.rust.engine)
}

android {
    namespace = "io.github.jpsaud501.kern.engine"
    compileSdk = libs.versions.android.target.sdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

rustEngine {
    crate = "kern-ffi"
    libraryName = "kern_ffi"
    apiLevel = libs.versions.android.min.sdk.get().toInt()
}

dependencies {
    // The generated bindings call the engine through JNA and use androidx annotations.
    api("${libs.jna.get()}@aar")
    implementation(libs.androidx.annotation)
}
