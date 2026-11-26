import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.net.URLClassLoader
import java.util.TreeSet
import java.util.zip.ZipFile

/**
 * Generates GraalVM native-image reachability metadata for all Jackson-bound
 * {@code com.claudecode.*} types directly from the compiled module classpath
 * and production serialization/deserialization call sites.
 *
 * <p>Emits {@code META-INF/native-image/com.claudecode/claude-code-app-owned/reachability-metadata.json},
 * which native-image merges automatically with the hand-maintained third-party/JDK
 * metadata file. Because every owned DTO, enum, sealed hierarchy, and collection
 * element array class is derived at build time, new features cannot drift out of
 * sync with native reflection registration.
 */
@CacheableTask
abstract class GenerateOwnedNativeMetadataTask : DefaultTask() {

    @get:Classpath
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionSources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val urls = runtimeClasspath.files
            .filter { it.exists() }
            .map { it.toURI().toURL() }
            .toTypedArray()

        val ownedTypes = TreeSet<String>()
        val arrayElementTypes = TreeSet<String>()

        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val seeds = linkedSetOf<Class<*>>()
            collectAnnotatedOwnedSeeds(loader, seeds)
            collectCallSiteSeeds(loader, seeds)

            val visited = linkedSetOf<Class<*>>()
            for (seed in seeds) {
                visitClass(seed, visited, arrayElementTypes)
            }
            for (type in visited) {
                if (type.name.startsWith("com.claudecode.") && !type.isArray) {
                    ownedTypes.add(type.name)
                }
            }
        }

        val targetFile = outputDirectory.file(
            "META-INF/native-image/com.claudecode/claude-code-app-owned/reachability-metadata.json"
        ).get().asFile
        targetFile.parentFile.mkdirs()
        targetFile.writeText(renderMetadataJson(ownedTypes, arrayElementTypes), Charsets.UTF_8)
    }

    private fun collectAnnotatedOwnedSeeds(loader: ClassLoader, seeds: MutableSet<Class<*>>) {
        for (entry in runtimeClasspath.files) {
            if (!entry.exists()) continue
            val classNames = if (entry.isFile && entry.name.endsWith(".jar")
                && entry.name.startsWith("claude-code-")) {
                readOwnedClassNamesFromJar(entry)
            } else if (entry.isDirectory) {
                readOwnedClassNamesFromDir(entry)
            } else {
                emptyList()
            }
            for (className in classNames) {
                val clazz = loadSilently(loader, className) ?: continue
                if (hasJacksonAnnotation(clazz)) {
                    seeds.add(clazz)
                }
            }
        }
    }

    private fun collectCallSiteSeeds(loader: ClassLoader, seeds: MutableSet<Class<*>>) {
        val callPattern = Regex(
            """\b(?:readJson|fromJson|readJsonList|treeToValue|readValue|convertValue)\s*\([^;]*?,\s*([A-Za-z_$][\w.$]*)\.class\s*\)"""
        )
        val pkgPattern = Regex("""(?m)^package\s+([\w.]+);""")

        for (file in productionSources.files) {
            if (!file.isFile || !file.name.endsWith(".java")) continue
            val body = file.readText(Charsets.UTF_8)
            val pkg = pkgPattern.find(body)?.groupValues?.get(1)
            val fileClass = file.name.removeSuffix(".java")
            for (match in callPattern.findAll(body)) {
                val raw = match.groupValues[1]
                val resolved = resolveType(loader, body, pkg, fileClass, raw) ?: continue
                if (resolved.name.startsWith("com.claudecode.")) {
                    seeds.add(resolved)
                }
            }
        }
    }

    private fun resolveType(
        loader: ClassLoader,
        body: String,
        pkg: String?,
        fileClass: String,
        raw: String,
    ): Class<*>? {
        if (raw.contains('.')) {
            loadSilently(loader, raw)?.let { return it }
        }
        if (pkg != null) {
            loadSilently(loader, "$pkg.$fileClass$${raw.replace('.', '$')}")?.let { return it }
            loadSilently(loader, "$pkg.${raw.replace('.', '$')}")?.let { return it }
        }
        val head = if (raw.contains('.')) raw.substringBefore('.') else raw
        val tail = if (raw.contains('.')) "$" + raw.substringAfter('.').replace('.', '$') else ""
        val importPattern = Regex("""(?m)^import\s+(?:static\s+)?([\w.]*\b${Regex.escape(head)});""")
        val fqn = importPattern.find(body)?.groupValues?.get(1) ?: return null
        return loadSilently(loader, fqn + tail)
    }

    private fun hasJacksonAnnotation(clazz: Class<*>): Boolean {
        if (clazz.annotations.any { isJacksonAnnotation(it) }) return true
        if (clazz.isRecord) {
            val components = runCatching { clazz.recordComponents }.getOrNull() ?: emptyArray()
            if (components.any { c -> c.annotations.any { isJacksonAnnotation(it) } }) return true
        }
        val fields = runCatching { clazz.declaredFields }.getOrNull() ?: emptyArray()
        if (fields.any { f -> f.annotations.any { isJacksonAnnotation(it) } }) return true
        val methods = runCatching { clazz.declaredMethods }.getOrNull() ?: emptyArray()
        if (methods.any { m -> m.annotations.any { isJacksonAnnotation(it) } }) return true
        val ctors = runCatching { clazz.declaredConstructors }.getOrNull() ?: emptyArray()
        return ctors.any { c -> c.annotations.any { isJacksonAnnotation(it) } }
    }

    private fun isJacksonAnnotation(annotation: Annotation): Boolean =
        annotation.annotationClass.java.name.startsWith("com.fasterxml.jackson.")

    private fun visitClass(
        type: Class<*>,
        visited: MutableSet<Class<*>>,
        arrayElements: MutableSet<String>,
    ) {
        if (type.isPrimitive) return
        if (type.isArray) {
            val component = type.componentType
            if (!component.isPrimitive && component.name.startsWith("com.claudecode.")) {
                arrayElements.add(component.name)
            }
            visitClass(component, visited, arrayElements)
            return
        }
        if (!visited.add(type)) return
        val name = type.name
        if (!name.startsWith("com.claudecode.") && !name.startsWith("java.util.")) return

        // Follow @JsonSubTypes, @JsonSerialize(using=...), @JsonDeserialize(using=...) reflectively
        // so buildSrc does not need a direct compile dependency on jackson-databind.
        for (annotation in runCatching { type.annotations }.getOrDefault(emptyArray())) {
            val annName = annotation.annotationClass.java.name
            when (annName) {
                "com.fasterxml.jackson.annotation.JsonSubTypes" -> {
                    val entries = invokeNoArg(annotation, "value") as? Array<*> ?: continue
                    for (sub in entries) {
                        val subType = invokeNoArg(sub ?: continue, "value") as? Class<*> ?: continue
                        visitClass(subType, visited, arrayElements)
                    }
                }
                "com.fasterxml.jackson.databind.annotation.JsonSerialize",
                "com.fasterxml.jackson.databind.annotation.JsonDeserialize" -> {
                    val using = invokeNoArg(annotation, "using") as? Class<*> ?: continue
                    if (using.name.startsWith("com.claudecode.")) {
                        visitClass(using, visited, arrayElements)
                    }
                }
            }
        }

        val permitted = runCatching { type.permittedSubclasses }.getOrNull()
        if (permitted != null) {
            for (sub in permitted) {
                visitClass(sub, visited, arrayElements)
            }
        }

        val superClass = type.superclass
        if (superClass != null && superClass.name.startsWith("com.claudecode.")) {
            visitClass(superClass, visited, arrayElements)
        }

        if (type.isRecord) {
            for (component in runCatching { type.recordComponents }.getOrDefault(emptyArray())) {
                if (!hasJsonIgnore(component.annotations)
                    && !hasJsonIgnore(runCatching { component.accessor.annotations }.getOrDefault(emptyArray()))
                    && !hasJsonIgnore(runCatching { type.getDeclaredField(component.name).annotations }.getOrDefault(emptyArray()))
                ) {
                    visitGenericType(component.genericType, visited, arrayElements)
                }
            }
        } else {
            for (field in runCatching { type.declaredFields }.getOrDefault(emptyArray())) {
                if (!Modifier.isStatic(field.modifiers) && !hasJsonIgnore(field.annotations)) {
                    visitGenericType(field.genericType, visited, arrayElements)
                }
            }
        }
    }

    private fun visitGenericType(
        type: Type,
        visited: MutableSet<Class<*>>,
        arrayElements: MutableSet<String>,
    ) {
        when (type) {
            is Class<*> -> visitClass(type, visited, arrayElements)
            is ParameterizedType -> {
                val raw = type.rawType
                visitGenericType(raw, visited, arrayElements)
                val isContainer = raw is Class<*>
                    && (Collection::class.java.isAssignableFrom(raw) || Map::class.java.isAssignableFrom(raw))
                for (arg in type.actualTypeArguments) {
                    if (isContainer) {
                        recordArrayElement(arg, arrayElements)
                    }
                    visitGenericType(arg, visited, arrayElements)
                }
            }
            is WildcardType -> {
                for (bound in type.upperBounds) {
                    visitGenericType(bound, visited, arrayElements)
                }
            }
            is GenericArrayType -> {
                recordArrayElement(type.genericComponentType, arrayElements)
                visitGenericType(type.genericComponentType, visited, arrayElements)
            }
        }
    }

    private fun recordArrayElement(type: Type, arrayElements: MutableSet<String>) {
        when (type) {
            is Class<*> -> if (!type.isPrimitive && type.name.startsWith("com.claudecode.")) {
                arrayElements.add(type.name)
            }
            is ParameterizedType -> recordArrayElement(type.rawType, arrayElements)
            is WildcardType -> type.upperBounds.forEach { recordArrayElement(it, arrayElements) }
        }
    }

    private fun hasJsonIgnore(annotations: Array<Annotation>): Boolean =
        annotations.any { it.annotationClass.java.name == "com.fasterxml.jackson.annotation.JsonIgnore" }

    private fun invokeNoArg(target: Any, methodName: String): Any? =
        runCatching { target.javaClass.getMethod(methodName).invoke(target) }.getOrNull()

    private fun loadSilently(loader: ClassLoader, className: String): Class<*>? =
        runCatching { Class.forName(className, false, loader) }.getOrNull()

    private fun readOwnedClassNamesFromJar(jar: File): List<String> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith("com/claudecode/") && it.name.endsWith(".class") }
            .filter { !it.name.endsWith("package-info.class") && !it.name.endsWith("module-info.class") }
            .map { it.name.removeSuffix(".class").replace('/', '.') }
            .toList()
    }

    private fun readOwnedClassNamesFromDir(dir: File): List<String> = dir.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".class") }
        .map { it.relativeTo(dir).path.replace(File.separatorChar, '/') }
        .filter { it.startsWith("com/claudecode/") && !it.endsWith("package-info.class") }
        .map { it.removeSuffix(".class").replace('/', '.') }
        .toList()

    private fun renderMetadataJson(
        ownedTypes: Set<String>,
        arrayElementTypes: Set<String>,
    ): String {
        val entries = mutableListOf<String>()
        for (type in ownedTypes) {
            entries.add(
                """    {
      "type": "$type",
      "allDeclaredFields": true,
      "allPublicMethods": true,
      "allDeclaredConstructors": true
    }"""
            )
        }
        for (element in arrayElementTypes) {
            entries.add(
                """    {
      "type": "$element[]"
    }"""
            )
        }
        return buildString {
            appendLine("{")
            appendLine("  \"reflection\": [")
            appendLine(entries.joinToString(",\n"))
            appendLine("  ]")
            appendLine("}")
        }
    }
}
