package kern.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Runs `cargo ndk` to build a crate's `cdylib` for each ABI, laid out as `<abi>/lib<name>.so`. */
@CacheableTask
abstract class CargoNdkBuild @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val cargo: Property<String>

    /** The Cargo workspace root. Tracked through [sources], not as a path. */
    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Tracked through [ndkVersion], so the build cache does not depend on where the SDK lives. */
    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:Input
    val ndkVersion: Provider<String> = ndkDirectory.map { it.asFile.name }

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val apiLevel: Property<Int>

    @get:Input
    abstract val crate: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        exec.exec {
            workingDir(workspaceDirectory.get().asFile)
            executable(cargo.get())
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
            args(
                buildList {
                    add("ndk")
                    abis.get().forEach { abi -> addAll(listOf("--target", abi)) }
                    addAll(listOf("--platform", apiLevel.get().toString()))
                    addAll(listOf("--output-dir", output.absolutePath))
                    addAll(listOf("build", "--release", "--locked", "--package", crate.get()))
                },
            )
        }
    }
}
