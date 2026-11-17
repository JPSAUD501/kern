package kern.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Generates Kotlin bindings from a built library with UniFFI's library mode. The generator is the
 * workspace's `uniffi-bindgen` binary, so it always matches the UniFFI version the engine uses.
 */
@CacheableTask
abstract class UniffiBindgen @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val cargo: Property<String>

    @get:Internal
    abstract val workspaceDirectory: DirectoryProperty

    /** Pins the generator's version. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockFile: RegularFileProperty

    /** The crate's `uniffi.toml`. UniFFI finds it itself; it is declared so edits rerun the task. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val config: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        exec.exec {
            workingDir(workspaceDirectory.get().asFile)
            executable(cargo.get())
            args(
                "run", "--quiet", "--locked", "--package", "uniffi-bindgen", "--",
                "generate",
                "--library", library.get().asFile.absolutePath,
                "--language", "kotlin",
                "--no-format",
                "--out-dir", output.absolutePath,
            )
        }
    }
}
