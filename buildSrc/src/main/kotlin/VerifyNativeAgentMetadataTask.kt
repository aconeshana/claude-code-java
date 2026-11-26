import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Compares the reflection accesses recorded at runtime by GraalVM's
 * {@code -agentlib:native-image-agent} against the merged native-image
 * reachability metadata (hand-maintained + build-generated).
 *
 * <p>Fails the build if any {@code com.claudecode.*} type, method, field, or
 * array class observed by the tracing agent is not covered by the metadata.
 * Because the agent observes real runtime reflection (including new reflective
 * calls introduced by third-party dependency upgrades such as Jackson), this
 * catches regressions that static source scans cannot predict.
 */
abstract class VerifyNativeAgentMetadataTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val agentOutputDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataFiles: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val dir = agentOutputDir.get().asFile
        val agentFile = sequenceOf(
            File(dir, "reachability-metadata.json"),
            File(dir, "reflect-config.json"),
        ).firstOrNull { it.isFile }
            ?: throw GradleException(
                "GraalVM tracing agent produced neither reachability-metadata.json nor " +
                    "reflect-config.json in $dir"
            )

        val observed = parseReflectionEntries(agentFile)
            .filter { it.type.startsWith("com.claudecode.") }
            // Ignore test classes themselves and the probe's own bootstrap lookup of JsonUtils.getMapper().
            .filterNot {
                it.type.endsWith("Test")
                    || it.type.contains("Test$")
                    || it.type == "com.claudecode.core.serialization.JsonUtils"
            }

        if (observed.isEmpty()) {
            throw GradleException(
                "Tracing agent recorded zero com.claudecode.* reflection entries in $agentFile; " +
                    "probe suite did not exercise Jackson/reflection as expected."
            )
        }

        val registeredByType = linkedMapOf<String, MutableList<ReflectionEntry>>()
        for (file in metadataFiles.files) {
            if (!file.isFile) continue
            for (entry in parseReflectionEntries(file)) {
                registeredByType.computeIfAbsent(entry.type) { mutableListOf() }.add(entry)
            }
        }

        val gaps = mutableListOf<String>()
        for ((type, _, _, _, methods, fields) in observed) {
            val covered = registeredByType[type].orEmpty()
            if (covered.isEmpty()) {
                gaps.add("MISSING TYPE: $type")
                continue
            }
            if (type.endsWith("[]")) {
                // Array registration only needs the type entry itself.
                continue
            }
            val hasAllMethods = covered.any { it.allPublicMethods }
            val hasAllCtors = covered.any { it.allDeclaredConstructors }
            val hasAllFields = covered.any { it.allDeclaredFields }
            val explicitMethods = covered.flatMap { it.methods }.toSet()
            val explicitFields = covered.flatMap { it.fields }.toSet()

            for (method in methods) {
                val isCtor = method.startsWith("<init>(")
                val ok = if (isCtor) {
                    hasAllCtors || method in explicitMethods
                } else {
                    hasAllMethods || method in explicitMethods
                }
                if (!ok) {
                    gaps.add("MISSING METHOD: $type#$method")
                }
            }
            for (field in fields) {
                if (!hasAllFields && field !in explicitFields) {
                    gaps.add("MISSING FIELD: $type.$field")
                }
            }
        }

        if (gaps.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("GraalVM tracing agent observed reflection accesses not covered by reachability metadata:")
                    gaps.distinct().sorted().forEach { appendLine("  - $it") }
                    appendLine("Observed file: $agentFile")
                    appendLine("Checked metadata files: ${metadataFiles.files.joinToString()}")
                }
            )
        }

        logger.lifecycle(
            "verifyNativeAgentMetadata: verified ${observed.size} runtime com.claudecode.* " +
                "reflection entries against ${metadataFiles.files.size} metadata file(s)."
        )
    }
}

private data class ReflectionEntry(
    val type: String,
    val allDeclaredFields: Boolean,
    val allPublicMethods: Boolean,
    val allDeclaredConstructors: Boolean,
    val methods: Set<String>,
    val fields: Set<String>,
)

/**
 * Minimal zero-dependency JSON parser for GraalVM reachability-metadata.json / reflect-config.json
 * reflection arrays. Handles both the GraalVM 25 unified schema ({"reflection": [...]})
 * and legacy reflect-config.json ([...]).
 */
private fun parseReflectionEntries(file: File): List<ReflectionEntry> {
    val text = file.readText(Charsets.UTF_8)
    val array: List<*> = when (val root = MiniJsonParser(text).parseValue()) {
        is Map<*, *> -> (root["reflection"] as? List<*>) ?: emptyList<Any>()
        is List<*> -> root
        else -> emptyList<Any>()
    }
    val result = mutableListOf<ReflectionEntry>()
    for (item in array) {
        val obj = item as? Map<*, *> ?: continue
        val type = when (val rawType = obj["type"] ?: obj["name"]) {
            is String -> rawType
            else -> continue
        }
        val methods = linkedSetOf<String>()
        val rawMethods = obj["methods"] as? List<*> ?: emptyList<Any>()
        for (m in rawMethods) {
            val mObj = m as? Map<*, *> ?: continue
            val mName = mObj["name"] as? String ?: continue
            val params = (mObj["parameterTypes"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.joinToString(",")
                .orEmpty()
            methods.add("$mName($params)")
        }
        val fields = linkedSetOf<String>()
        val rawFields = obj["fields"] as? List<*> ?: emptyList<Any>()
        for (f in rawFields) {
            val fObj = f as? Map<*, *> ?: continue
            val fName = fObj["name"] as? String ?: continue
            fields.add(fName)
        }
        result.add(
            ReflectionEntry(
                type = type,
                allDeclaredFields = obj["allDeclaredFields"] == true,
                allPublicMethods = obj["allPublicMethods"] == true || obj["allDeclaredMethods"] == true,
                allDeclaredConstructors = obj["allDeclaredConstructors"] == true || obj["allPublicConstructors"] == true,
                methods = methods,
                fields = fields,
            )
        )
    }
    return result
}

private class MiniJsonParser(private val src: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWs()
        if (pos >= src.length) return null
        return when (src[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { pos += 4; true }
            'f' -> { pos += 5; false }
            'n' -> { pos += 4; null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        pos++ // '{'
        skipWs()
        if (pos < src.length && src[pos] == '}') { pos++; return map }
        while (pos < src.length) {
            skipWs()
            val key = parseString()
            skipWs()
            if (pos < src.length && src[pos] == ':') pos++
            val value = parseValue()
            map[key] = value
            skipWs()
            if (pos < src.length && src[pos] == ',') { pos++; continue }
            if (pos < src.length && src[pos] == '}') { pos++; break }
            break
        }
        return map
    }

    private fun parseArray(): List<Any?> {
        val list = mutableListOf<Any?>()
        pos++ // '['
        skipWs()
        if (pos < src.length && src[pos] == ']') { pos++; return list }
        while (pos < src.length) {
            list.add(parseValue())
            skipWs()
            if (pos < src.length && src[pos] == ',') { pos++; continue }
            if (pos < src.length && src[pos] == ']') { pos++; break }
            break
        }
        return list
    }

    private fun parseString(): String {
        pos++ // '"'
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos++]
            if (c == '"') break
            if (c == '\\' && pos < src.length) {
                when (val esc = src[pos++]) {
                    '"', '\\', '/' -> sb.append(esc)
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = src.substring(pos, pos + 4)
                        pos += 4
                        sb.append(hex.toInt(16).toChar())
                    }
                    else -> sb.append(esc)
                }
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun parseNumber(): Number {
        val start = pos
        while (pos < src.length && src[pos] in "0123456789-+eE.") pos++
        val token = src.substring(start, pos)
        return token.toLongOrNull() ?: token.toDoubleOrNull() ?: 0
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }
}
