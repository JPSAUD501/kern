plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
}

gradlePlugin {
    plugins {
        register("rustEngine") {
            id = "kern.rust-engine"
            implementationClass = "kern.build.RustEnginePlugin"
        }
    }
}
