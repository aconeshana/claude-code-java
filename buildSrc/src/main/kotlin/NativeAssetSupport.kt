import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

internal val supportedCcConnectTargets = listOf(
    "darwin-amd64",
    "darwin-arm64",
    "linux-amd64",
    "linux-arm64",
)
internal val supportedRipgrepTargets = listOf(
    "x64-linux",
    "arm64-linux",
    "x64-darwin",
    "arm64-darwin",
    "x64-win32",
    "arm64-win32",
)
internal val supportedDistributionTargets = listOf(
    "darwin-amd64",
    "darwin-arm64",
    "linux-amd64",
    "linux-arm64",
    "windows-amd64",
    "windows-arm64",
)
private const val maxCcConnectArchiveBytes = 128L * 1024 * 1024
private const val maxRipgrepArchiveBytes = 64L * 1024 * 1024

internal data class CcConnectTarget(
    val platform: String,
    val asset: String,
    val sha256: String,
) {
    fun url(spec: CcConnectReleaseSpec): String =
        "https://github.com/${spec.repository}/releases/download/${spec.version}/$asset"
}

data class NativeDistributionTarget(
    val name: String,
    val ccConnectTarget: String?,
    val ripgrepTarget: String,
)

fun nativeDistributionTarget(name: String): NativeDistributionTarget = when (name) {
    "darwin-amd64" -> NativeDistributionTarget(name, name, "x64-darwin")
    "darwin-arm64" -> NativeDistributionTarget(name, name, "arm64-darwin")
    "linux-amd64" -> NativeDistributionTarget(name, name, "x64-linux")
    "linux-arm64" -> NativeDistributionTarget(name, name, "arm64-linux")
    "windows-amd64" -> NativeDistributionTarget(name, null, "x64-win32")
    "windows-arm64" -> NativeDistributionTarget(name, null, "arm64-win32")
    else -> error(
        "unsupported distribution target '$name'; expected one of " +
            supportedDistributionTargets.joinToString(),
    )
}

internal data class CcConnectReleaseSpec(
    val repository: String,
    val version: String,
    val commit: String,
    val targets: Map<String, CcConnectTarget>,
) {
    companion object {
        fun from(properties: Properties): CcConnectReleaseSpec {
            val repository = properties.required("cc-connect.repository")
            check(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
                "invalid cc-connect.repository: $repository"
            }
            val version = properties.required("cc-connect.version")
            check(version.matches(Regex("[A-Za-z0-9._-]+"))) {
                "invalid cc-connect.version: $version"
            }
            val commit = properties.required("cc-connect.commit")
            check(commit.matches(Regex("[0-9a-fA-F]{40}"))) {
                "cc-connect.commit must be a full 40-character Git SHA"
            }
            val targets = supportedCcConnectTargets.associateWith { platform ->
                val asset = properties.required("cc-connect.$platform.asset")
                check(Path.of(asset).fileName.toString() == asset &&
                    !asset.contains('/') && !asset.contains('\\')) {
                    "cc-connect asset must be a plain file name: $asset"
                }
                val hash = properties.required("cc-connect.$platform.sha256").lowercase()
                check(hash.matches(Regex("[0-9a-f]{64}"))) {
                    "cc-connect.$platform.sha256 must contain 64 hexadecimal characters"
                }
                CcConnectTarget(platform, asset, hash)
            }
            return CcConnectReleaseSpec(repository, version, commit, targets)
        }
    }
}

internal class NativeArchiveCache(
    private val root: Path,
    private val maxArchiveBytes: Long,
) {
    fun resolve(
        asset: String,
        expectedSha256: String,
        localAsset: Path? = null,
        download: (Path) -> Unit,
    ): Path {
        val targetDirectory = root.resolve(expectedSha256.lowercase())
        Files.createDirectories(targetDirectory)
        val cached = targetDirectory.resolve(asset)
        val lock = targetDirectory.resolve("$asset.lock")
        FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (Files.isRegularFile(cached) &&
                    Files.size(cached) <= maxArchiveBytes &&
                    sha256(cached).equals(expectedSha256, ignoreCase = true)) return cached
                Files.deleteIfExists(cached)
                val temporary = Files.createTempFile(targetDirectory, "$asset.", ".part")
                try {
                    if (localAsset != null) {
                        check(Files.isRegularFile(localAsset)) {
                            "local native asset is missing: $localAsset"
                        }
                        check(Files.size(localAsset) <= maxArchiveBytes) {
                            "local native asset is too large: $localAsset"
                        }
                        Files.copy(localAsset, temporary, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        download(temporary)
                    }
                    check(Files.size(temporary) <= maxArchiveBytes) {
                        "native asset is too large: $asset"
                    }
                    val actual = sha256(temporary)
                    check(actual.equals(expectedSha256, ignoreCase = true)) {
                        "native asset checksum mismatch for $asset: " +
                            "expected $expectedSha256, got $actual"
                    }
                    try {
                        Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    Files.deleteIfExists(temporary)
                }
                return cached
            }
        }
    }
}

internal class CcConnectArchiveCache(root: Path) {
    private val delegate = NativeArchiveCache(root, maxCcConnectArchiveBytes)

    fun resolve(
        target: CcConnectTarget,
        localAssetDirectory: Path?,
        download: (Path) -> Unit,
    ): Path = delegate.resolve(
        target.asset,
        target.sha256,
        localAssetDirectory?.resolve(target.asset),
        download,
    )
}

@CacheableTask
abstract class VendorRipgrepTask @Inject constructor(
    private val files: FileSystemOperations,
) : DefaultTask() {

    @get:Input
    abstract val targets: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val downloadCacheDirectory: DirectoryProperty

    init {
        targets.convention(supportedRipgrepTargets)
    }

    @TaskAction
    fun vendor() {
        val output = outputDirectory.get().asFile
        files.delete { delete(output) }
        output.mkdirs()

        val manifest = loadProperties(manifestFile.get().asFile.toPath())
        val version = manifest.required("ripgrep.version")
        val selectedTargets = targets.get()
        check(selectedTargets.isNotEmpty()) { "at least one ripgrep target is required" }
        check(selectedTargets.toSet().size == selectedTargets.size) {
            "ripgrep targets must not contain duplicates"
        }
        check(supportedRipgrepTargets.containsAll(selectedTargets)) {
            "unsupported ripgrep target in $selectedTargets"
        }
        val cache = NativeArchiveCache(
            downloadCacheDirectory.get().asFile.toPath().resolve(version),
            maxRipgrepArchiveBytes,
        )

        selectedTargets.forEach { target ->
            val prefix = "ripgrep.$target"
            val triple = manifest.required("$prefix.triple")
            val extension = manifest.required("$prefix.extension")
            val expectedHash = manifest.required("$prefix.sha256")
            val binaryName = if (target.endsWith("win32")) "rg.exe" else "rg"
            val archiveName = "ripgrep-$version-$triple.$extension"
            val url = "https://github.com/BurntSushi/ripgrep/releases/download/$version/" +
                archiveName
            val archive = cache.resolve(archiveName, expectedHash) { destination ->
                download(URI(url), destination)
            }

            val targetDirectory = output.resolve("vendor/ripgrep/$target")
            targetDirectory.mkdirs()
            val binary = targetDirectory.resolve(binaryName).toPath()
            if (extension == "zip") {
                extractZipBinary(archive, binaryName, binary)
            } else {
                extractTarGzipBinary(archive, binaryName, binary)
            }
            check(targetDirectory.resolve(binaryName).isFile) {
                "ripgrep archive for $triple did not contain $binaryName"
            }
        }
    }

    private fun download(uri: URI, destination: Path) {
        check(uri.scheme == "https" && uri.host == "github.com") {
            "native assets may only be downloaded from https://github.com"
        }
        Files.createDirectories(destination.parent)
        val connection = uri.toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 120_000
        }
        connection.getInputStream().use { input ->
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun extractZipBinary(
        archive: java.nio.file.Path,
        binaryName: String,
        destination: java.nio.file.Path,
    ) {
        ZipFile(archive.toFile()).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == binaryName }
                ?: error("archive $archive did not contain $binaryName")
            zip.getInputStream(entry).use { input ->
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun extractTarGzipBinary(
        archive: java.nio.file.Path,
        binaryName: String,
        destination: java.nio.file.Path,
    ) {
        Files.newInputStream(archive).use { fileInput ->
            GZIPInputStream(fileInput).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.substringAfterLast('/') == binaryName) {
                            Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
                            return
                        }
                    }
                }
            }
        }
        error("archive $archive did not contain $binaryName")
    }
}

@CacheableTask
abstract class VendorCcConnectTask @Inject constructor(
    private val files: FileSystemOperations,
) : DefaultTask() {

    @get:Input
    abstract val targets: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localAssetDirectory: DirectoryProperty

    @get:Internal
    abstract val downloadCacheDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        targets.convention(supportedCcConnectTargets)
    }

    @TaskAction
    fun vendor() {
        val spec = CcConnectReleaseSpec.from(
            loadProperties(manifestFile.get().asFile.toPath()),
        )
        val output = outputDirectory.get().asFile
        files.delete { delete(output) }
        output.mkdirs()
        val localAssets = localAssetDirectory.orNull?.asFile?.toPath()
        val cache = CcConnectArchiveCache(
            downloadCacheDirectory.get().asFile.toPath().resolve(spec.version),
        )
        val selectedTargets = targets.get()
        check(selectedTargets.isNotEmpty()) { "at least one cc-connect target is required" }
        check(selectedTargets.toSet().size == selectedTargets.size) {
            "cc-connect targets must not contain duplicates"
        }
        check(supportedCcConnectTargets.containsAll(selectedTargets)) {
            "unsupported cc-connect target in $selectedTargets"
        }
        val report = StringBuilder()
        selectedTargets.map(spec.targets::getValue).forEach { target ->
            val archive = cache.resolve(target, localAssets) { destination ->
                downloadHttps(URI(target.url(spec)), destination)
            }
            val binary = output.toPath().resolve("native/${target.platform}/cc-connect")
            extractCcConnectArchive(archive, binary)
            report.append(target.platform)
                .append(" archive=").append(target.sha256)
                .append(" binary=").append(sha256(binary)).append('\n')
        }
        val metadata = output.resolve("META-INF/cc-connect.properties")
        metadata.parentFile.mkdirs()
        metadata.writeText(buildString {
            append("repository=").append(spec.repository).append('\n')
            append("version=").append(spec.version).append('\n')
            append("commit=").append(spec.commit).append('\n')
            append(report)
        })
    }
}

internal fun extractCcConnectArchive(archive: Path, destination: Path) {
    Files.createDirectories(destination.parent)
    var extracted = false
    Files.newInputStream(archive).use { fileInput ->
        GZIPInputStream(fileInput).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (entry.name != "cc-connect") continue
                    check(!extracted) { "cc-connect archive contains duplicate executable entries" }
                    check(entry.isFile && !entry.isLink && !entry.isSymbolicLink) {
                        "cc-connect archive executable must be a regular file"
                    }
                    check(entry.size in 1..maxCcConnectArchiveBytes) {
                        "cc-connect archive executable has an invalid size: ${entry.size}"
                    }
                    Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING)
                    extracted = true
                }
            }
        }
    }
    check(extracted) { "cc-connect archive $archive did not contain root entry cc-connect" }
}

private fun downloadHttps(uri: URI, destination: Path) {
    check(uri.scheme == "https" && uri.host == "github.com") {
        "native assets may only be downloaded from https://github.com"
    }
    Files.createDirectories(destination.parent)
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = 20_000
        readTimeout = 120_000
    }
    connection.getInputStream().use { input ->
        Files.newOutputStream(destination, StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                check(total <= maxCcConnectArchiveBytes) {
                    "cc-connect release archive exceeds 128 MiB"
                }
                output.write(buffer, 0, read)
            }
        }
    }
}

private fun loadProperties(path: java.nio.file.Path): Properties = Properties().apply {
    Files.newInputStream(path).use(::load)
}

private fun Properties.required(key: String): String =
    getProperty(key)?.trim()?.takeIf(String::isNotEmpty)
        ?: error("missing native asset property: $key")

private fun sha256(path: java.nio.file.Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
