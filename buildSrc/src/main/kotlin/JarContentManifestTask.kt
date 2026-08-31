import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.zip.ZipFile

@CacheableTask
abstract class JarContentManifestTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jarFile: RegularFileProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val entries = readJar(jarFile.get().asFile)
        val output = manifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(entries.values.joinToString(separator = "\n", postfix = "\n") { entry ->
            "${entry.sha256} ${entry.size} ${entry.path}"
        })
    }
}

private data class JarEntryDigest(
    val path: String,
    val size: Long,
    val sha256: String,
)

private fun readJar(file: java.io.File): Map<String, JarEntryDigest> = ZipFile(file).use { zip ->
    buildMap {
        zip.entries().asSequence()
            .filterNot { it.isDirectory }
            .sortedBy { it.name }
            .forEach { entry ->
                check(entry.name !in this) { "duplicate JAR entry ${entry.name} in $file" }
                val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                put(entry.name, JarEntryDigest(entry.name, bytes.size.toLong(), sha256))
            }
    }
}
