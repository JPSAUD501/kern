package kern.build

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File

/**
 * Builds the Rust engine for Android and generates its Kotlin bindings.
 *
 * Applied to the Android library that wraps the engine. The native libraries and the generated
 * Kotlin sources are wired into every variant as generated sources, so AGP packages and compiles
 * them like any other source.
 */
class RustEnginePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("rustEngine", RustEngineExtension::class.java)
        extension.abis.convention(listOf("arm64-v8a", "x86_64"))

        project.pluginManager.withPlugin("com.android.library") {
            val androidComponents =
                project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            val workspace = project.layout.settingsDirectory
            val cargoExecutable = findCargo(project)

            val cargoBuild = project.tasks.register("cargoNdkBuild", CargoNdkBuild::class.java) {
                description = "Builds the Rust engine for the Android ABIs."
                cargo.set(cargoExecutable)
                workspaceDirectory.set(workspace)
                sources.from(
                    workspace.files("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/config.toml"),
                    workspace.dir("engine").asFileTree.matching {
                        include("**/*.rs", "**/*.toml", "**/*.json")
                    },
                )
                ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
                abis.set(extension.abis)
                apiLevel.set(extension.apiLevel)
                crate.set(extension.crate)
                outputDirectory.set(project.layout.buildDirectory.dir("rust/jniLibs"))
            }

            val bindgen = project.tasks.register("uniffiBindgen", UniffiBindgen::class.java) {
                description = "Generates the Kotlin bindings for the Rust engine."
                cargo.set(cargoExecutable)
                workspaceDirectory.set(workspace)
                lockFile.set(workspace.file("Cargo.lock"))
                config.set(extension.crate.map { crate -> workspace.file("engine/$crate/uniffi.toml") })
                library.set(
                    cargoBuild.flatMap { build ->
                        build.outputDirectory.zip(extension.libraryName) { directory, name ->
                            directory.file("${extension.abis.get().first()}/lib$name.so")
                        }
                    },
                )
                outputDirectory.set(project.layout.buildDirectory.dir("generated/uniffi/kotlin"))
            }

            androidComponents.onVariants { variant ->
                variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuild::outputDirectory)
                variant.sources.kotlin?.addGeneratedSourceDirectory(bindgen, UniffiBindgen::outputDirectory)
            }
        }
    }

    /**
     * The rustup `cargo` proxy, which honors `rust-toolchain.toml`. Gradle daemons often start
     * without the user's shell PATH, so the usual install location is checked first.
     */
    private fun findCargo(project: Project): String {
        val cargoHome = project.providers.environmentVariable("CARGO_HOME").orNull
            ?: File(System.getProperty("user.home"), ".cargo").path
        val installed = File(cargoHome, "bin/cargo")
        return if (installed.canExecute()) installed.path else "cargo"
    }
}

abstract class RustEngineExtension {
    /** The Cargo package that builds the `cdylib`. */
    abstract val crate: Property<String>

    /** The library name without `lib` and `.so`, as set in the crate's `[lib]` section. */
    abstract val libraryName: Property<String>

    /** Android ABIs to build. The first one is also used to generate the bindings. */
    abstract val abis: ListProperty<String>

    /** The Android API level the libraries link against: the app's `minSdk`. */
    abstract val apiLevel: Property<Int>
}
