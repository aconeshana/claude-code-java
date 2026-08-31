import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.gradle.api.tasks.Internal
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class CcConnectAssetSupportTest {

    @Test
    fun `distribution target selects matching native assets`() {
        assertEquals(
            NativeDistributionTarget("darwin-arm64", "darwin-arm64", "arm64-darwin"),
            nativeDistributionTarget("darwin-arm64"),
        )
        assertEquals(
            NativeDistributionTarget("linux-amd64", "linux-amd64", "x64-linux"),
            nativeDistributionTarget("linux-amd64"),
        )
        assertEquals(
            NativeDistributionTarget("windows-amd64", null, "x64-win32"),
            nativeDistributionTarget("windows-amd64"),
        )
        assertEquals(
            NativeDistributionTarget("windows-arm64", null, "arm64-win32"),
            nativeDistributionTarget("windows-arm64"),
        )
        assertFailsWith<IllegalStateException> { nativeDistributionTarget("freebsd-amd64") }
    }

    @Test
    fun `persistent download cache is not Gradle local state`() {
        val getters = listOf(
            VendorCcConnectTask::class.java.getMethod("getDownloadCacheDirectory"),
            VendorRipgrepTask::class.java.getMethod("getDownloadCacheDirectory"),
        )

        getters.forEach { getter ->
            check(getter.isAnnotationPresent(Internal::class.java)) {
                "Gradle deletes LocalState when restoring task outputs from Build Cache"
            }
        }
    }

    @Test
    fun `release spec pins repository version commit asset and archive hash`() {
        val properties = releaseProperties("a".repeat(64))

        val spec = CcConnectReleaseSpec.from(properties)

        assertEquals("aconeshana/cc-connect", spec.repository)
        assertEquals("v1.5.0-sessionhost.1", spec.version)
        assertEquals("0123456789abcdef0123456789abcdef01234567", spec.commit)
        assertEquals(
            "https://github.com/aconeshana/cc-connect/releases/download/" +
                "v1.5.0-sessionhost.1/cc-connect-v1.5.0-sessionhost.1-darwin-arm64.tar.gz",
            spec.targets.getValue("darwin-arm64").url(spec),
        )
    }

    @Test
    fun `release spec rejects asset path traversal`() {
        val properties = releaseProperties("a".repeat(64)).apply {
            setProperty("cc-connect.darwin-arm64.asset", "../sidecar.tar.gz")
        }

        assertFailsWith<IllegalStateException> { CcConnectReleaseSpec.from(properties) }
    }

    @Test
    fun `archive cache survives clean output without downloading again`() {
        val root = Files.createTempDirectory("cc-connect-cache-test")
        val localAssets = root.resolve("assets").createDirectories()
        val cache = root.resolve("cache")
        val archive = localAssets.resolve("sidecar.tar.gz")
        createArchive(archive, "cc-connect", "sidecar-v1".toByteArray())
        val expected = sha256(archive)
        val target = CcConnectTarget("darwin-arm64", archive.fileName.toString(), expected)
        val store = CcConnectArchiveCache(cache)

        val first = store.resolve(target, localAssets) { error("network must not be used") }
        archive.deleteExisting()
        val second = store.resolve(target, null) { error("cached archive should be reused") }

        assertEquals(first, second)
        assertContentEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun `archive cache rejects a mismatched checksum`() {
        val root = Files.createTempDirectory("cc-connect-hash-test")
        val localAssets = root.resolve("assets").createDirectories()
        val archive = localAssets.resolve("sidecar.tar.gz")
        createArchive(archive, "cc-connect", "tampered".toByteArray())
        val target = CcConnectTarget("darwin-arm64", archive.fileName.toString(), "0".repeat(64))

        assertFailsWith<IllegalStateException> {
            CcConnectArchiveCache(root.resolve("cache"))
                .resolve(target, localAssets) { error("network must not be used") }
        }
    }

    @Test
    fun `configured local asset directory never falls back to the network`() {
        val root = Files.createTempDirectory("cc-connect-local-only-test")
        val source = root.resolve("source.tar.gz")
        createArchive(source, "cc-connect", "sidecar".toByteArray())
        val target = CcConnectTarget("darwin-arm64", "missing.tar.gz", sha256(source))
        var downloaded = false

        assertFailsWith<IllegalStateException> {
            CcConnectArchiveCache(root.resolve("cache"))
                .resolve(target, root.resolve("empty").createDirectories()) { destination ->
                    downloaded = true
                    Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
        }
        assertFalse(downloaded)
    }

    @Test
    fun `extractor accepts only the root cc-connect regular file`() {
        val root = Files.createTempDirectory("cc-connect-extract-test")
        val valid = root.resolve("valid.tar.gz")
        createArchive(valid, "cc-connect", "valid".toByteArray())
        val output = root.resolve("out/cc-connect")

        extractCcConnectArchive(valid, output)

        assertContentEquals("valid".toByteArray(), output.readBytes())

        val traversal = root.resolve("traversal.tar.gz")
        createArchive(traversal, "../cc-connect", "invalid".toByteArray())
        assertFailsWith<IllegalStateException> {
            extractCcConnectArchive(traversal, root.resolve("bad/cc-connect"))
        }
    }

    private fun releaseProperties(hash: String) = Properties().apply {
        setProperty("cc-connect.repository", "aconeshana/cc-connect")
        setProperty("cc-connect.version", "v1.5.0-sessionhost.1")
        setProperty("cc-connect.commit", "0123456789abcdef0123456789abcdef01234567")
        for (target in listOf("darwin-amd64", "darwin-arm64", "linux-amd64", "linux-arm64")) {
            setProperty(
                "cc-connect.$target.asset",
                "cc-connect-v1.5.0-sessionhost.1-$target.tar.gz",
            )
            setProperty("cc-connect.$target.sha256", hash)
        }
    }

    private fun createArchive(path: Path, name: String, bytes: ByteArray) {
        path.parent.createDirectories()
        FileOutputStream(path.toFile()).use { file ->
            java.util.zip.GZIPOutputStream(file).use { gzip ->
                TarArchiveOutputStream(gzip).use { tar ->
                    val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                    tar.finish()
                }
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(path.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
