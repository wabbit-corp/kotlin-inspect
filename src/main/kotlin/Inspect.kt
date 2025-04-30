package one.wabbit.inspect

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.Textifier
import org.objectweb.asm.util.TraceClassVisitor
import java.io.*
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.reflect.*
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.jvm.*

object Inspect {
    /////////////////////////////////////////////////////////////////////////////////////////////////
    // Caller information
    /////////////////////////////////////////////////////////////////////////////////////////////////

    //    val stackTrace = Thread.currentThread().stackTrace
    //    val caller = stackTrace[2]
    //    return caller.methodName
    private val stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

    /**
     * Returns the immediate caller's [Class].
     */
    fun callerClass(): KClass<*> {
        return stackWalker.walk { stream ->
            stream.skip(1) // Skip walk, skip this function (callerClass)
                .findFirst()
                .map { frame -> frame.declaringClass.kotlin }
                .orElseThrow { IllegalStateException("Could not determine caller class") }
        }
    }

    /**
     * Retrieves the source location of the immediate caller of this method.
     *
     * @return A pair containing the file name as a String and line number as an Int of the caller's source location.
     * @throws IllegalStateException if the caller's class information could not be determined.
     */
    fun callerSourceLocation(): Pair<String, Int> {
        return stackWalker.walk { stream ->
            stream.skip(1) // Skip walk, skip this function (callerClass)
                .findFirst()
                .map { frame -> frame.fileName to frame.lineNumber }
                .orElseThrow { IllegalStateException("Could not determine caller class") }
        }
    }

    /**
     * Returns the immediate caller's [Class.simpleName].
     */
    fun callerClassName(): String {
        return stackWalker.walk { stream ->
            stream.skip(1) // Skip walk, skip this function (callerClass)
                .findFirst()
                .map { frame -> frame.declaringClass.simpleName }
                .orElseThrow { IllegalStateException("Could not determine caller class") }
        }
    }

    /**
     * Returns the immediate caller's method name (if any).
     */
    fun callerMethodName(): String {
        return stackWalker.walk { stream ->
            stream.skip(1) // Skip walk, skip this function (callerClass)
                .findFirst()
                .map { frame -> frame.methodName }
                .orElseThrow { IllegalStateException("Could not determine caller method name") }
        }
    }

    /**
     * Attempts to reflectively retrieve the [KFunction] that called this function.
     * This might fail if the method is overloaded or if you lack debug info.
     */
    fun callerFunction(): KFunction<*> {
        val frame = stackWalker.walk { stream ->
            stream.skip(1) // Skip walk, skip this function (callerFunction)
                .findFirst()
                .orElseThrow { IllegalStateException("Could not determine caller stack frame") }
        }

        val declaringClass = frame.declaringClass
        val methodName = frame.methodName
        val methodType = frame.methodType // Provides parameter types

        try {
            // Find the specific method using parameter types for overload resolution
            val method = declaringClass.getDeclaredMethod(methodName, *methodType.parameterArray())
            method.isAccessible = true // Ensure we can access it
            return method.kotlinFunction
                ?: throw IllegalStateException("Could not get KFunction for method $methodName in $declaringClass")
        } catch (e: NoSuchMethodException) {
            // Could happen for constructors or synthetic methods? Fallback or rethrow.
            // Let's try finding constructors if the method name indicates one
            if (methodName == "<init>") {
                try {
                    val constructor = declaringClass.getDeclaredConstructor(*methodType.parameterArray())
                    constructor.isAccessible = true
                    return constructor.kotlinFunction
                        ?: throw IllegalStateException("Could not get KFunction for constructor in $declaringClass")
                } catch (e2: NoSuchMethodException) {
                    throw NoSuchMethodException("Could not find constructor matching $methodType in $declaringClass (rethrown)")
                }
            }
            throw NoSuchMethodException("Could not find method $methodName matching $methodType in $declaringClass (rethrown)")
        } catch (e: Exception) {
            throw IllegalStateException("Failed to reflect on caller function $methodName in $declaringClass", e)
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Bytecode inspection
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Loads full ClassNode (ASM) for the given Class.
     *
     * @param cls The Java class to load bytecode for.
     * @return The ASM [ClassNode].
     * @throws IllegalStateException if the class resource cannot be found or read.
     */
    @Throws(IllegalStateException::class)
    internal fun loadClassBytecode(cls: Class<*>): ClassNode {
        val className = cls.name.replace('.', '/')
        val resourceName = "$className.class"

        val stream = cls.classLoader?.getResourceAsStream(resourceName)
            ?: cls.getResourceAsStream("/$resourceName") // Fallback for system classes?
            ?: throw IllegalStateException("No class file resource for $className (resource=$resourceName, classloader=${cls.classLoader})")

        return stream.use {
            val cr = ClassReader(it)
            val cn = ClassNode() // FIXME: Opcodes.ASM9? Specify ASM API version
            // EXPAND_FRAMES => more expensive but more complete stack map frames
            cr.accept(cn, ClassReader.EXPAND_FRAMES)
            cn
        }
    }

    /**
     * Returns a human-readable textual disassembly of the entire class bytecode.
     *
     * @param cls The Kotlin class ([KClass]) to disassemble.
     * @return A string containing the disassembled bytecode.
     */
    fun bytecodeOf(cls: KClass<*>): String {
        val cn = loadClassBytecode(cls.java)
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            val tcv = TraceClassVisitor(null, Textifier(), pw)
            cn.accept(tcv)
        }
        return sw.toString()
    }

    /**
     * Reified convenience overload: get the bytecode for a known reified type [T].
     */
    inline fun <reified T> bytecodeOf() = bytecodeOf(T::class)

    class LambdasNotSupportedException(message: String, cause: Throwable? = null) : UnsupportedOperationException(message, cause)

    /**
     * Returns a textual disassembly of **only** the given [KFunction]'s bytecode.
     * It accurately locates the corresponding method in the bytecode using the method descriptor,
     * handling overloaded methods correctly.
     *
     * @param kfun The Kotlin function ([KFunction]) to disassemble.
     * @return A string containing the disassembled bytecode of the specific function.
     * @throws IllegalStateException if the underlying Java method/constructor cannot be located.
     * @throws NoSuchMethodError if the corresponding method node cannot be found in the class bytecode.
     */
    fun bytecodeOf(kfun: KFunction<*>): String {
        try {
            kfun.javaMethod
        } catch (e: kotlin.reflect.jvm.internal.KotlinReflectionInternalError) {
            if (e.message?.startsWith("Unknown origin of") == true) {
                throw LambdasNotSupportedException("Cannot disassemble a lambda function", e)
            }

            throw UnsupportedOperationException("Cannot disassemble a function via reflection", e)
        }

        val javaMember = kfun.javaMethod ?: kfun.javaConstructor
        ?: throw IllegalStateException("Cannot locate a JVM method or constructor for $kfun")

        val containingClass = javaMember.declaringClass
        val classNode = loadClassBytecode(containingClass)

        // Get the JVM method descriptor to accurately find the method, handling overloads
        val methodDescriptor = when (javaMember) {
            is java.lang.reflect.Method -> org.objectweb.asm.Type.getMethodDescriptor(javaMember)
            is java.lang.reflect.Constructor<*> -> org.objectweb.asm.Type.getConstructorDescriptor(javaMember)
            else -> throw IllegalStateException("Unsupported java member type: ${javaMember::class}")
        }
        val methodName = if (javaMember is java.lang.reflect.Constructor<*>) {
            "<init>"
        } else {
            javaMember.name
        }

        // Find matching MethodNode in ASM
        val mn = classNode.methods.firstOrNull { m ->
            m.name == methodName && m.desc == methodDescriptor
        } ?: throw NoSuchMethodError("Cannot find ASM MethodNode for $methodName::$methodDescriptor in ${containingClass.name}")

        // Now disassemble just that method node
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        val textifier = Textifier()
        val tcv = TraceClassVisitor(null, textifier, pw)

        val tempClassNode = ClassNode()
        tempClassNode.version = classNode.version
        tempClassNode.access = classNode.access
        tempClassNode.name = classNode.name
        tempClassNode.superName = classNode.superName
        tempClassNode.methods.add(mn)

        tempClassNode.accept(tcv)
        var result = sw.toString()
        result = result.substringAfter("{").substringBeforeLast("}")
        // Remove empty lines from the beginning
        result = result.trimIndent()
        while (result.startsWith("\n")) {
            result = result.substring(1)
        }
        return result
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////
    // Closure variables
    /////////////////////////////////////////////////////////////////////////////////////////////////

    @OptIn(ExperimentalReflectionOnLambdas::class)
    fun <R> closureVarsOf(f: () -> R): Map<String, Any?> {
        // This relies on the lambda instance itself for reflection.
        val ownerClass = f::class.java
        println("Inspecting closure fields for lambda instance of: ${ownerClass.name}")
        val result = mutableMapOf<String, Any?>()
        ownerClass.declaredFields.forEach { field ->
            try {
                if (Modifier.isStatic(field.modifiers)) return@forEach
                if (field.isSynthetic || field.name.contains("\$")) {
                    field.isAccessible = true
                    val value = field.get(f)
                    println("  Found potential closure field: ${field.name} = $value")
                    result[field.name] = value
                }
            } catch (e: Exception) {
                println("  Could not access or read field: ${field.name}")
            }
        }
        if (result.isEmpty()) {
            println("No closure fields found or inspection failed for: ${ownerClass.name}")
        }
        return result

//        val kfun: KFunction<*> = f.reflect()!!
//        val owner = kfun.instanceParameter ?: return
//        val fields = owner.javaClass.declaredFields
//        for (field in fields) {
//            if (Modifier.isStatic(field.modifiers)) continue
//            if (field.name.contains("\$") && !field.name.startsWith("param")) {
//                field.isAccessible = true
//                val value = field.get(owner)
//                println("${field.name} = $value")
//            }
//        }
    }

    /**
     * Attempt to find closure variables captured by [f].
     *
     * - If [f] is a lambda, Kotlin typically compiles it to a synthetic class with fields
     *   for each captured variable. We can reflect on those fields.
     * - If [f] is a normal function, it might not have "closure variables" in the same sense.
     *
     * This is extremely brittle, depends on how the Kotlin compiler names synthetic classes
     * and fields. Use at your own risk.
     */
    internal fun closureVarsOf(f: KFunction<*>): Map<String, Any?> {
//        // Lambdas/function references are often compiled into synthetic classes.
//        // The 'instanceParameter' of the KFunction might point to an instance of this class.
//        val functionOwnerInstance = try {
//            // Accessing the instance parameter might require reflection capabilities.
//            // This part can be complex depending on how the KFunction was obtained.
//            // A common way a lambda KFunction holds its receiver is via MethodHandles.lookup()
//            // or similar mechanisms, but direct access isn't straightforward via KFunction alone.
//            // A simpler proxy is needed if 'f' is directly a lambda instance:
//            if (f is Function<*>) {
//                // Try to get the receiver if 'f' itself is the lambda object.
//                // This is still heuristic.
//                val receiverField = f::class.java.declaredFields.find { it.name == "receiver" } // Common pattern
//                receiverField?.isAccessible = true
//                receiverField?.get(f)
//            } else {
//                // If f was obtained via reflection on a class, instanceParameter might work
//                f.instanceParameter?.let { param ->
//                    // How to get the *actual instance* associated with this KFunction?
//                    // This is the tricky part. KFunction is metadata.
//                    // We might need the object the function was *called on*.
//                    // If f represents e.g. obj::method, we need 'obj'.
//                    // If f is a lambda reference passed around, we need the lambda instance.
//                    // This function likely needs the *lambda instance itself* passed in, not just KFunction.
//                    // Let's adjust the function signature later if this proves unusable.
//                    // For now, assume f *might* be the lambda object itself, cast carefully.
//                    if (f::class.java.name.contains("\$Lambda$") || f::class.java.interfaces.any { it.isAnnotationPresent(FunctionalInterface::class.java) }) {
//                        // It *might* be the lambda object. Let's try getting fields from it.
//                        f // Assuming f *is* the instance for now. Highly speculative.
//                    } else {
//                        null
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            // Ignore errors trying to get the owner, likely not inspectable this way.
//            null
//        } ?: return emptyMap() // Cannot determine the object holding the captured state
//
//        val ownerClass = functionOwnerInstance::class.java
//        println("Inspecting closure fields for instance of: ${ownerClass.name}")
//
//        // Heuristic: Captured fields often contain '$' but aren't static or synthetic accessors.
//        // Common names: $capturedLocal, this$0 (outer class instance)
//        val result = mutableMapOf<String, Any?>()
//        ownerClass.declaredFields.forEach { field ->
//            try {
//                if (Modifier.isStatic(field.modifiers)) return@forEach // Skip static fields
//                // Refined heuristic: check for '$' and potentially exclude known synthetic fields
//                // This is still very guess-based.
//                if (field.isSynthetic || field.name.contains("\$")) {
//                    // Examples: this$0, $capturedVar, $receiver (for extension lambdas)
//                    // We might capture too much or too little.
//                    field.isAccessible = true
//                    val value = field.get(functionOwnerInstance)
//                    println("  Found potential closure field: ${field.name} = $value")
//                    result[field.name] = value
//                }
//            } catch (e: Exception) {
//                // Ignore fields we cannot access or read
//                println("  Could not access or read field: ${field.name}")
//            }
//        }
//
//        if (result.isEmpty()) {
//            println("No closure fields found or inspection failed for: ${ownerClass.name}")
//        }
//        return result

        val javaMethod = f.javaMethod
        if (javaMethod != null) {
            // Typically a top-level or member function, no real "closure"
            return emptyMap()
        }

        // If it's a constructor, no closure
        val javaCtor = f.javaConstructor
        if (javaCtor != null) {
            return emptyMap()
        }

        // Possibly it's a lambda or local function reference.
        // Let's see if it's a class: e.g. "SomeFile$someFun$1"
        val owner = f.instanceParameter ?: return emptyMap()

        // We'll reflect over all fields. Some might be $capture$0, $this, etc.
        val result = mutableMapOf<String, Any?>()
        val fields = owner.javaClass.declaredFields
        for (field in fields) {
            // We skip certain synthetic fields
            if (Modifier.isStatic(field.modifiers)) continue
            if (field.name.contains("\$") && !field.name.startsWith("param")) {
                // It's probably a captured variable
            }
            field.isAccessible = true
            val value = field.get(owner)
            result[field.name] = value
        }
        return result
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////
    // Source location
    /////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Attempts to locate the jar file path or directory from which [cls] was loaded.
     *
     * @param cls The Kotlin class ([KClass]).
     * @return The URL path as a string (e.g., "file:/path/to/my.jar", "jrt:/java.base"),
     * or a descriptive string like "<Unknown code source>" or "<Unknown location>" if not found.
     */
    fun sourceLocationOf(cls: KClass<*>): String {
        return try {
            val codeSource = cls.java.protectionDomain?.codeSource
            if (codeSource == null) {
                // we can use cls.java.module.name to get the module name
                if (cls.java.module != null) {
                    return "jrt:/${cls.java.module.name}"
                }
                return "<Unknown code source>"
            }
            val location = codeSource.location ?: return "<Unknown location>"
            location.toString()
        } catch (e: SecurityException) {
            "<Security manager prevents access>"
        } catch (e: Exception) {
            "<Error retrieving location: ${e.message}>"
        }
    }

//    /**
//     * Attempt to locate a possible "-sources.jar" next to the JAR of [cls].
//     * If found, returns the local filesystem path to it. If not found, returns null.
//     */
//    internal fun findLocalSourcesJar(cls: KClass<*>): File? {
//        val loc = cls.java.protectionDomain.codeSource.location
//            ?: return null
//        println("Location: $loc")
//        val path = loc.toURI().path
//        if (path.endsWith(".jar")) {
//            if (path.contains("/.m2/repository/")) {
//                // e.g. /home/myuser/.m2/repository/foo/bar/1.0/bar-1.0.jar
//                val base = path.removeSuffix(".jar")
//                val candidate = File("$base-sources.jar")
//                if (candidate.exists()) {
//                    return candidate
//                }
//            } else if (path.contains(".gradle/caches/modules-")) {
//                // e.g. /home/myuser/.gradle/caches/modules-2/files-2.1/foo/bar/1.0/21ac91884e0b9462b9106d/bar-1.0.jar
//                val parent = Paths.get(path).parent
//                // hash, e.g. 21ac91884e0b9462b9106d1a83e5e0d200170c65
//                if (parent.name.matches(Regex("[0-9a-f]{40}"))) {
//                    val grandparent = parent.parent
//                    val searchName = Paths.get(path).fileName.toString().removeSuffix(".jar") + "-sources.jar"
//                    for (subdir in grandparent.toFile().listFiles() ?: emptyArray()) {
//                        val candidate = subdir.resolve(searchName)
//                        if (candidate.exists()) {
//                            return candidate
//                        }
//                    }
//                }
//            }
//        }
//        return null
//    }

    /**
     * Attempts to locate a corresponding "-sources.jar" next to the JAR of [cls]
     * in common local cache locations (Maven `.m2`, Gradle `caches`).
     *
     * This is heuristic and may not work for all build tools or cache configurations.
     *
     * @param cls The Kotlin class ([KClass]).
     * @return The local filesystem [Path] to the sources JAR if found, or null otherwise.
     */
    internal fun findLocalSourcesJar(cls: KClass<*>): Path? {
        val locationUrl = cls.java.protectionDomain?.codeSource?.location ?: return null
        val locationUri: URI = try {
            locationUrl.toURI()
        } catch (e: Exception) {
            return null // Cannot convert URL to URI (e.g., malformed)
        }

        // Only proceed if it's a file URI and points to a JAR
        if (locationUri.scheme != "file") return null
        val jarPath = try {
            Paths.get(locationUri)
        } catch (e: Exception) {
            return null // Invalid path
        }
        if (!jarPath.name.endsWith(".jar") || !Files.isRegularFile(jarPath)) return null

        val jarFileName = jarPath.fileName.toString()
        val baseName = jarFileName.removeSuffix(".jar")
        val sourcesJarName = "$baseName-sources.jar"
        val sourcesJarPath = jarPath.resolveSibling(sourcesJarName)

        // 1. Check direct sibling
        if (sourcesJarPath.exists() && !Files.isDirectory(sourcesJarPath)) {
            // println("Found sources JAR as direct sibling: $sourcesJarPath")
            return sourcesJarPath
        }

        // 2. Check Maven ~/.m2 structure: .../groupId/artifactId/version/artifactId-version.jar
        //    Sources expected: .../groupId/artifactId/version/artifactId-version-sources.jar
        val pathString = jarPath.toString()
        if (pathString.contains(File.separator + ".m2" + File.separator + "repository" + File.separator)) {
            // The direct sibling check above already covers the standard Maven layout.
            // println("Checked Maven structure via sibling check for: $jarPath")
        }

        // 3. Check Gradle ~/.gradle/caches/modules-2/files-2.1/groupId/artifactId/version/HASH/artifactId-version.jar
        //    Sources expected: .../groupId/artifactId/version/SOURCES_HASH/artifactId-version-sources.jar
        if (pathString.contains(File.separator + ".gradle" + File.separator + "caches" + File.separator + "modules-")) {
            val parent = jarPath.parent // HASH directory
            val versionDir = parent?.parent // version directory
            val artifactDir = versionDir?.parent // artifactId directory
            val groupDir = artifactDir?.parent // groupId directory

            if (parent != null && versionDir != null && parent.name.matches(Regex("[0-9a-f]{30,}"))) { // Check if parent looks like a hash
                // println("Checking Gradle cache structure for: $jarPath")
                // Look for a sibling directory under 'versionDir' containing the sources JAR
                try {
                    var result: Path? = null
                    Files.list(versionDir).use { stream ->
                        stream.filter { Files.isDirectory(it) && it != parent } // Look in other hash dirs
                            .map { potentialSourcesHashDir -> potentialSourcesHashDir.resolve(sourcesJarName) }
                            .filter { Files.isRegularFile(it) }
                            .findFirst()
                            .ifPresent { result = it } // Found it!
                    }
                    if (result != null) {
                        // println("Found sources JAR in Gradle cache: $result")
                        return result
                    } else {
                        // println("No sources JAR found in Gradle cache for: $jarPath")
                    }
                } catch (e: IOException) {
                    System.err.println("Warning: Failed to scan Gradle cache directories under $versionDir: ${e.message}")
                }
            }
        }

        // println("Sources JAR not found in common local cache locations for: $jarPath")
        return null
    }

//    /**
//     * Attempt a naive approach to see if there's a corresponding -sources.jar
//     * on Maven Central for the jar that loaded [cls].
//     *
//     * - We parse the jar filename to guess (groupId, artifactId, version).
//     * - Then we do a HEAD or GET request for the -sources.jar on central.
//     * - If found, we download it to a local temp or M2 cache location.
//     * - Return the path if successful, or null if not found.
//     *
//     * This is a purely illustrative approach and not fully robust.
//     */
//    internal fun findSourcesJarOnMavenCentral(cls: KClass<*>): File? {
//        val loc = cls.java.protectionDomain.codeSource.location ?: return null
//        val path = loc.toString()
//        val fileName = File(path).name
//        // Example: "my-artifact-1.2.3.jar"
//        // We'll do a naive parse
//        val regex = Regex("(.+)-(\\d+.*)\\.jar$")
//        val match = regex.matchEntire(fileName) ?: return null
//        val artifactId = match.groupValues[1]
//        val version = match.groupValues[2]
//
//        // We have no groupId here.
//        // In real code, we might parse from the path or have a known group.
//        // Let's guess "com.example" for illustration:
//        val groupId = "com/example"
//
//        // Construct a naive maven central URL:
//        // e.g. https://repo1.maven.org/maven2/com/example/my-artifact/1.2.3/my-artifact-1.2.3-sources.jar
//        val centralUrl = "https://repo1.maven.org/maven2/$groupId/$artifactId/$version/" +
//                "$artifactId-$version-sources.jar"
//
//        return runBlocking {
//            val tmpFile = Files.createTempFile("$artifactId-$version-sources", ".jar").toFile()
//            if (httpDownload(centralUrl, tmpFile.toPath())) {
//                tmpFile
//            } else {
//                null
//            }
//        }
//    }

    /**
     * Attempts to download a corresponding "-sources.jar" from Maven Central.
     * Requires groupId, artifactId, and version, which it tries to parse heuristically
     * from the JAR file path if it follows a Maven-like structure.
     *
     * **Warning:** Parsing groupId from the path is fragile. Providing it explicitly is safer.
     * This function performs **blocking network I/O**.
     *
     * @param cls The Kotlin class ([KClass]) whose sources are needed.
     * @param groupId Optional explicit groupId. If null, attempts to parse from the JAR path (less reliable).
     * @param downloadDir Directory to download the sources JAR to. Defaults to system temp directory.
     * @return The local filesystem [Path] to the downloaded sources JAR if successful, or null otherwise.
     */
    internal fun findAndDownloadSourcesJarFromMavenCentral(
        cls: KClass<*>,
        groupId: String? = null,
        downloadDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))
    ): Path? {
        val locationUrl = cls.java.protectionDomain?.codeSource?.location ?: return null
        val locationUri: URI = try { locationUrl.toURI() } catch (e: Exception) { return null }
        if (locationUri.scheme != "file") return null
        val jarPath = try { Paths.get(locationUri) } catch (e: Exception) { return null }
        if (!jarPath.name.endsWith(".jar") || !Files.isRegularFile(jarPath)) return null

        val jarFileName = jarPath.fileName.toString()

        // Heuristic parsing of artifactId and version from filename like "my-artifact-1.2.3.jar"
        val nameVersionRegex = Regex("(.+)-([0-9][^-]+(?:-[^-]+)*)\\.jar$")
        val match = nameVersionRegex.matchEntire(jarFileName)
        val artifactId = match?.groupValues?.getOrNull(1) ?: return null // Failed parse
        val version = match?.groupValues?.getOrNull(2) ?: return null // Failed parse

        // Attempt to parse groupId from path if not provided (e.g., .../.m2/repository/com/example/...)
        val effectiveGroupId = groupId ?: run {
            val pathString = jarPath.toString()
            val repoMarker = File.separator + "repository" + File.separator
            val repoIndex = pathString.indexOf(repoMarker)
            if (repoIndex != -1) {
                val groupArtifactVersionPath = pathString.substring(repoIndex + repoMarker.length)
                val parts = groupArtifactVersionPath.split(File.separatorChar)
                // Expecting: group/parts/artifactId/version/artifactId-version.jar
                if (parts.size >= 4 && parts[parts.size - 2] == version && parts[parts.size - 3] == artifactId) {
                    parts.dropLast(3).joinToString(".") // Reconstruct group id
                } else {
                    null // Path structure doesn't match expected Maven layout
                }
            } else {
                null // Not in a recognizable repository path
            }
        }

        if (effectiveGroupId == null) {
            System.err.println("Warning: Could not determine groupId for $jarFileName. Cannot query Maven Central.")
            return null
        }

        val groupIdPath = effectiveGroupId.replace('.', '/')
        val sourcesJarName = "$artifactId-$version-sources.jar"
        val centralUrl = "https://repo1.maven.org/maven2/$groupIdPath/$artifactId/$version/$sourcesJarName"

        println("Attempting to download sources from: $centralUrl")

        return try {
            val destPath = downloadDir.resolve(sourcesJarName)
            Files.createDirectories(downloadDir) // Ensure download directory exists
            if (httpDownload(centralUrl, destPath)) {
                println("Successfully downloaded sources to: $destPath")
                destPath
            } else {
                println("Failed to download sources from Maven Central (URL: $centralUrl)")
                Files.deleteIfExists(destPath) // Clean up failed download attempt
                null
            }
        } catch (e: Exception) {
            System.err.println("Error during sources download/saving: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Simple blocking HTTP GET downloader.
     *
     * @param urlStr The URL to download from.
     * @param destPath The destination file Path.
     * @param connectTimeout Connection timeout duration.
     * @param readTimeout Read timeout duration.
     * @return True if download was successful (HTTP 2xx), false otherwise.
     */
    internal fun httpDownload(
        urlStr: String,
        destPath: Path,
        connectTimeout: Duration = Duration.ofSeconds(5),
        readTimeout: Duration = Duration.ofSeconds(30)
    ): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            var success = false
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = connectTimeout.toMillis().toInt()
                conn.readTimeout = readTimeout.toMillis().toInt()
                conn.instanceFollowRedirects = true // Follow redirects
                conn.connect()

                val responseCode = conn.responseCode
                // println("HTTP Response Code for $urlStr: $responseCode") // Verbose

                if (responseCode in 200..299) {
                    conn.inputStream.use { input ->
                        destPath.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    success = true
                } else {
                    // Log error stream if available
                    conn.errorStream?.use { err ->
                        val errorText = err.bufferedReader().readText()
                        System.err.println("HTTP Error ($responseCode) for $urlStr: $errorText")
                    }
                }
            } finally {
                conn.disconnect()
            }
            success
        } catch (e: IOException) {
            System.err.println("IOException during HTTP download from $urlStr to $destPath: ${e.message}")
            false
        } catch (e: Exception) {
            System.err.println("Unexpected exception during HTTP download from $urlStr: ${e.message}")
            e.printStackTrace() // Log unexpected errors
            false
        }
    }

    /**
     * Represents a fragment of source code with location information.
     *
     * @property sourceFile Path to the source file.
     * @property startLine Starting line number (1-based).
     * @property endLine Ending line number (1-based). Can be same as startLine.
     * @property code The actual source code lines as a string.
     */
    data class SourceCodeFragment(
        val sourceFile: Path,
        val startLine: Int,
        val endLine: Int,
        val code: String
    )

    /**
     * **EXPERIMENTAL and FRAGILE:** Attempts to retrieve the source code fragment for a given
     * function or constructor.
     *
     * **Requires:**
     * 1.  The class file must contain LineNumberTable debug information.
     * 2.  A corresponding source file (`.java` or `.kt`) must be locatable, either locally
     * (via `findLocalSourcesJar`) or potentially downloaded (requires manual download step).
     * 3.  Source file encoding is assumed to be UTF-8.
     * 4.  Parsing is basic line-based extraction, might be inaccurate with complex formatting or annotations.
     *
     * @param kfun The [KFunction] to retrieve source code for.
     * @param searchMavenCentral If true, attempts to download sources from Maven Central if not found locally (requires network).
     * @param groupId Explicit groupId for Maven Central lookup (recommended if `searchMavenCentral` is true).
     * @return A [SourceCodeFragment] if successful, or null if sources cannot be found,
     * line number information is missing, or an error occurs.
     */
    fun getSourceCodeFragment(
        kfun: KFunction<*>,
        searchMavenCentral: Boolean = false,
        groupId: String? = null
    ): SourceCodeFragment? {
        val javaMember = kfun.javaMethod ?: kfun.javaConstructor
        ?: return null // Cannot proceed without Java member

        val containingClass = javaMember.declaringClass
        val classNode: ClassNode = try {
            loadClassBytecode(containingClass)
        } catch (e: Exception) {
            System.err.println("Failed to load bytecode for ${containingClass.name}: ${e.message}")
            return null
        }

        // Find the corresponding MethodNode
        val methodDescriptor = when (javaMember) {
            is Method -> org.objectweb.asm.Type.getMethodDescriptor(javaMember)
            is java.lang.reflect.Constructor<*> -> org.objectweb.asm.Type.getConstructorDescriptor(javaMember)
            else -> return null
        }
        val methodName = javaMember.name
        val methodNode = classNode.methods.firstOrNull { m ->
            m.name == methodName && m.desc == methodDescriptor
        } ?: return null // Method not found in bytecode

        // Find the min and max line numbers from the LineNumberTable attribute
        val lineNumbers = methodNode.instructions?.asSequence()
            ?.filterIsInstance<LineNumberNode>()
            ?.map { it.line }
            ?.toList()

        if (lineNumbers.isNullOrEmpty()) {
            println("Warning: No line number information found in bytecode for $methodName in ${containingClass.name}")
            return null
        }
        val startLine = lineNumbers.minOrNull() ?: return null
        val endLine = lineNumbers.maxOrNull() ?: startLine // Use startLine if only one entry

        // Try to find the source file path
        val sourceFileName = classNode.sourceFile ?: run {
            // Guess source file name from class name (e.g., MyClass.kt or MyClass.java)
            // This is a heuristic!
            val simpleName = containingClass.simpleName.substringBefore('$') // Handle inner/anonymous classes
            listOf("$simpleName.kt", "$simpleName.java").firstOrNull() // Prefer .kt
        } ?: return null // Cannot determine source file name

        var sourcesJarPath: Path? = findLocalSourcesJar(containingClass.kotlin)

        if (sourcesJarPath == null && searchMavenCentral) {
            println("Local sources not found, attempting download from Maven Central...")
            sourcesJarPath = findAndDownloadSourcesJarFromMavenCentral(containingClass.kotlin, groupId)
        }

        if (sourcesJarPath == null) {
            println("Could not locate sources JAR for ${containingClass.name}")
            return null
        }

        // Construct the expected path within the JAR
        val packagePath = containingClass.packageName.replace('.', '/')
        val sourceEntryPath = if (packagePath.isEmpty()) sourceFileName else "$packagePath/$sourceFileName"

        // Read the source file content from the JAR
        try {
            JarFile(sourcesJarPath.toFile()).use { jar ->
                val entry: JarEntry? = jar.getJarEntry(sourceEntryPath)
                if (entry == null) {
                    println("Source file entry '$sourceEntryPath' not found in JAR '$sourcesJarPath'")
                    // Try common variations like src/main/java or src/main/kotlin prefixes
                    val commonPrefixes = listOf("src/main/kotlin/", "src/main/java/", "src/commonMain/kotlin/", "")
                    val foundEntry = commonPrefixes.firstNotNullOfOrNull { prefix ->
                        jar.getJarEntry("$prefix$sourceEntryPath")
                    } ?: return null // Still not found
                    println("Found source entry under alternative path: ${foundEntry.name}")
                    return readSourceFragmentFromJarEntry(jar, foundEntry, sourcesJarPath, startLine, endLine)

                } else {
                    return readSourceFragmentFromJarEntry(jar, entry, sourcesJarPath, startLine, endLine)
                }
            }
        } catch (e: Exception) {
            System.err.println("Error reading source fragment from $sourcesJarPath ($sourceEntryPath): ${e.message}")
            return null
        }
    }

    // Helper to read lines from a JAR entry
    private fun readSourceFragmentFromJarEntry(
        jar: JarFile,
        entry: JarEntry,
        sourceFilePath: Path, // Path to the JAR itself for the result
        startLine: Int,
        endLine: Int
    ): SourceCodeFragment? {
        try {
            jar.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val lines = reader.readLines()
                if (startLine > lines.size || startLine <= 0 || endLine < startLine) {
                    println("Warning: Line numbers ($startLine-$endLine) out of bounds for source file ${entry.name} (total lines: ${lines.size})")
                    return null
                }
                // Extract lines (adjusting for 0-based index)
                val code = lines.subList(startLine - 1, minOf(endLine, lines.size)).joinToString("\n")
                return SourceCodeFragment(
                    sourceFile = sourceFilePath.resolve("!/${entry.name}"), // Represent path inside JAR
                    startLine = startLine,
                    endLine = minOf(endLine, lines.size), // Adjust end line if it exceeded actual lines
                    code = code
                )
            }
        } catch (e: Exception) {
            System.err.println("Error reading JarEntry ${entry.name}: ${e.message}")
            return null
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////
    // Running code in a separate JVM
    /////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Creates a jar containing the child class from our resources (in base64).
     */
    internal fun createRunnerJar(): File {
        // 1) create temp dir
        val tmpDir = Files.createTempDirectory("child").toFile()
        tmpDir.deleteOnExit()

        // 2) The child class content from resource
        val childClassBytes = Runner::class.java.getResourceAsStream("/one/wabbit/inspect/Runner.class")!!.readBytes()

        // 4) jar it up
        val jarFile = File.createTempFile("gadget-", ".jar")
        jarFile.deleteOnExit()
        // Use ZipOutputStream to create a jar file
        jarFile.outputStream().use { jarOut ->
            val jar = java.util.jar.JarOutputStream(jarOut)

            jar.putNextEntry(java.util.jar.JarEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.write("Main-Class: one.wabbit.inspect.Runner\n".toByteArray())
            jar.closeEntry()

            jar.putNextEntry(java.util.jar.JarEntry("one/wabbit/inspect/Runner.class"))
            jar.write(childClassBytes)
            jar.close()
        }
        return jarFile
    }

    internal inline fun <reified R> start(noinline f: () -> R) {
        // We need to make sure that we have enough information to run the function
        // Step 1. Get the function name and class name
        if (f::class.java.name.contains("$\$Lambda")) {
            throw IllegalArgumentException("Cannot run lambda functions")
        }

        // Convert the function pointer to a KFunction
        val kfun: KFunction<*>? = f.reflect()
        requireNotNull(kfun) { "Unable to reflect on the provided function." }

        // Grab the underlying Java method
        val jMethod = kfun.javaMethod
        requireNotNull(jMethod) { "The function is not a Java static method." }

        val className = jMethod.declaringClass.name
        val methodName = jMethod.name

        val runnerJar = createRunnerJar()
        // Start a NEW JVM that runs the given function
        val javaHome = System.getProperty("java.home")
        val javaBin = javaHome + File.separator + "bin" + File.separator + "java"
        val classpath = System.getProperty("java.class.path")

        val processBuilder = ProcessBuilder(
            javaBin, "-cp", classpath + File.pathSeparator + runnerJar.absolutePath,
            "one.wabbit.inspect.Runner",
            className, methodName).inheritIO()
        val process = processBuilder.start()
        process.waitFor()
    }

    /////////////////////////////////////////////////////////////////////////////////////////////////
    // Inspection of locals
    /////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Exception thrown when the `locals()` inspection fails.
     *
     * @param message The error message.
     * @param cause Optional underlying cause.
     * @param errorOutput Output from the helper process's error stream, if any.
     */
    class InspectionException(
        message: String,
        cause: Throwable? = null,
        val errorOutput: String? = null
    ) : RuntimeException(message, cause)

    /**
     * **HIGHLY EXPERIMENTAL and SLOW:** Attempts to retrieve a map of local variable names
     * to their runtime string representations from the **caller's** stack frame.
     *
     * Launches `InspectorGadget` helper process. It first checks if the current JVM
     * has a JDWP agent already listening on a specific port.
     * - If yes: Calls `InspectorGadget --connect <host>:<port> <threadName>`.
     * - If no (or random port): Calls `InspectorGadget get-locals <pid> <threadName> [port]`,
     * which attempts dynamic JDWP enablement.
     *
     * **WARNING:** See warnings for the previous `locals()` version. Dynamic attach mode
     * remains extremely fragile. Direct connect mode requires specific JVM startup flags.
     *
     * @return A map of local variable names to their string representations.
     * @throws InspectionException if any part of the process fails.
     * @throws IOException if the helper JAR cannot be created.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    fun locals(): Map<String, String?> {
        val pid = getCurrentPid()
        val threadName = Thread.currentThread().name
        val helperJar: File

        println("locals(): Current PID=$pid, ThreadName=$threadName")

        // 1. Check for existing JDWP agent and prepare command args
        val existingJdwpConfig = findExistingJdwpConfig()
        val commandArgs = mutableListOf<String>()

        if (existingJdwpConfig != null && existingJdwpConfig.port != "0") {
            println("locals(): Found existing JDWP agent on specific port ${existingJdwpConfig.port}. Using --connect mode.")
            commandArgs.add("--connect")
            commandArgs.add("${existingJdwpConfig.host}:${existingJdwpConfig.port}")
            commandArgs.add(threadName)
        } else {
            if (existingJdwpConfig != null) { // Port was 0
                println("locals(): Existing JDWP agent uses random port. Attempting dynamic attach (might fail)...")
            } else {
                println("locals(): No existing JDWP agent detected. Using dynamic attach mode.")
            }
            // Use dynamic attach mode
            commandArgs.add("get-locals")
            commandArgs.add(pid)
            commandArgs.add(threadName)
            // We don't pass a port here, letting InspectorGadget choose one if needed for dynamic load
        }

        // 2. Create Helper JAR
        try {
            helperJar = createInspectorGadgetJar()
            // println("locals(): Created helper JAR: ${helperJar.absolutePath}") // Less verbose
        } catch (e: Exception) {
            throw IOException("Failed to create InspectorGadget helper JAR. Ensure InspectorGadget.class is in resources.", e)
        }

        // 3. Prepare and Launch Process
        val javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val command = mutableListOf(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            // Required for InspectorGadget to access internal attach APIs on Java 9+
            "--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED",
            "-jar",
            helperJar.absolutePath
        )
        command.addAll(commandArgs) // Add mode-specific arguments

        val processBuilder = ProcessBuilder(command)

        println("locals(): Launching InspectorGadget: ${command.joinToString(" ")}")
        val process: Process
        try {
            process = processBuilder.start()
        } catch (e: IOException) {
            throw InspectionException("Failed to launch InspectorGadget process", e)
        }

        // 4. Process Output and Wait
        val outputMap = mutableMapOf<String, String?>()
        val errorOutput = StringBuilder()
        var exitCode: Int? = null
        val stdoutLogs = mutableListOf<String>() // Capture stdout for debugging

        try {
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))

            var line: String?
            var readingLocals = false

            // Read stdout
            while (stdoutReader.readLine().also { line = it } != null) {
                stdoutLogs.add("Helper stdout: $line") // Capture all stdout
                if (line == "--- LOCALS START ---") readingLocals = true
                else if (line == "--- LOCALS END ---") readingLocals = false
                else if (readingLocals) {
                    val parts = line!!.split(" = ", limit = 2)
                    if (parts.size == 2) {
                        outputMap[parts[0].trim()] = if (parts[1] == "null") null else parts[1]
                    } else {
                        System.err.println("locals(): Warning - Could not parse local line: $line")
                    }
                }
            }

            // Read stderr
            while (stderrReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            // Wait for process completion
            if (!process.waitFor(30, TimeUnit.SECONDS)) { // Timeout
                process.destroyForcibly()
                throw InspectionException(
                    "InspectorGadget process timed out.",
                    errorOutput = errorOutput.toString().trim().ifEmpty { null } + "\n--- Stdout Log ---\n" + stdoutLogs.joinToString("\n")
                )
            }

            exitCode = process.exitValue()
            println("locals(): InspectorGadget process finished with exit code: $exitCode")
            stdoutLogs.forEach(::println) // Print captured stdout
            if (errorOutput.isNotEmpty()) {
                System.err.println("--- Helper Error Output ---")
                System.err.print(errorOutput)
                System.err.println("-------------------------")
            }


            if (exitCode != 0) {
                throw InspectionException(
                    "InspectorGadget process failed with exit code $exitCode.",
                    errorOutput = errorOutput.toString().trim().ifEmpty { null }
                )
            }

            if (outputMap.isEmpty() && !errorOutput.toString().contains("No visible local variables") && !errorOutput.toString().contains("Debug information not available")) {
                System.err.println("locals(): Warning - Helper exited successfully but no locals were parsed. Check helper logs and ensure target code has debug info.")
            }

        } catch (e: IOException) {
            throw InspectionException("I/O error communicating with InspectorGadget process", e, errorOutput.toString().trim().ifEmpty { null })
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Interrupted while waiting for InspectorGadget process.")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            // Files.deleteIfExists(helperJar.toPath()) // deleteOnExit should handle
        }

        return outputMap
    }

    /** Data class to hold parsed JDWP agent config */
    internal data class JdwpConfig(val host: String, val port: String, val isServer: Boolean)

    /**
     * Checks JVM input arguments for a pre-configured JDWP agent listening on a specific port.
     * Returns null if not found, not in server mode, or uses a random port (port 0).
     */
    internal fun findExistingJdwpConfig(): JdwpConfig? {
        val runtimeMxBean = ManagementFactory.getRuntimeMXBean()
        val inputArguments = runtimeMxBean.inputArguments ?: return null

        val jdwpArgPrefix = "-agentlib:jdwp="
        val runjdwpArgPrefix = "-Xrunjdwp:" // Older style

        for (arg in inputArguments) {
            val optionsString = when {
                arg.startsWith(jdwpArgPrefix) -> arg.substring(jdwpArgPrefix.length)
                arg.startsWith(runjdwpArgPrefix) -> arg.substring(runjdwpArgPrefix.length)
                else -> continue
            }

            val options = optionsString.split(',')
                .mapNotNull { opt -> val parts = opt.split('=', limit = 2); if (parts.size == 2) parts[0].trim() to parts[1].trim() else null }
                .toMap()

            val transport = options["transport"]
            val server = options["server"]
            val address = options["address"]

            if (transport == "dt_socket" && server == "y" && address != null) {
                val addressPattern = Pattern.compile("^(?:([^*:]+):)?(\\d+)$")
                val matcher = addressPattern.matcher(address)
                if (matcher.matches()) {
                    val host = matcher.group(1) ?: "*"
                    val port = matcher.group(2)
                    if (port != "0") {
                        val connectHost = if (host == "*") "localhost" else host
                        return JdwpConfig(connectHost, port, true)
                    } else {
                        return null // Random port
                    }
                }
            }
        }
        return null // No suitable agent found
    }

    /**
     * **EXPERIMENTAL:** Dynamically loads a native agent library into the current JVM process.
     *
     * This works by launching the `InspectorGadget` helper process, which uses the
     * Attach API to connect back to this JVM and load the specified agent library.
     *
     * **WARNING:**
     * - Requires a full JDK.
     * - Requires OS permissions for process attachment.
     * - Requires `one.wabbit.inspect.InspectorGadget.class` in resources.
     * - Relies on internal JVM APIs and is fragile across versions/vendors.
     * - The agent library must be compatible with the current JVM architecture and OS.
     * - The agent library must support dynamic loading (e.g., implement `Agent_OnAttach` if it's a JVMTI agent).
     * - Slow due to process launch.
     *
     * @param agentPath The absolute path to the native agent library (.so, .dll, .dylib).
     * @param options Optional string arguments to pass to the agent's initialization function (e.g., `Agent_OnAttach` or `Agent_OnLoad`).
     * @throws InspectionException if the agent loading fails (check `errorOutput` for details from the helper).
     * @throws IOException if the helper JAR cannot be created or the process launch fails.
     * @throws InterruptedException if the calling thread is interrupted.
     */
    fun loadAgent(agentPath: String, options: String? = null) {
        val pid = getCurrentPid()
        val helperJar: File

        println("loadAgent(): Loading agent '$agentPath' into current PID $pid with options: $options")

        // 1. Validate agent path
        val agentFile = File(agentPath)
        if (!agentFile.exists() || !agentFile.isFile) {
            throw IllegalArgumentException("Agent path does not exist or is not a file: $agentPath")
        }
        val absoluteAgentPath = agentFile.absolutePath // Ensure absolute path

        // 2. Create Helper JAR
        try {
            helperJar = createInspectorGadgetJar()
        } catch (e: Exception) {
            throw IOException("Failed to create InspectorGadget helper JAR.", e)
        }

        // 3. Prepare and Launch Process
        val javaExecutable = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val command = mutableListOf(
            javaExecutable,
            "-Dfile.encoding=UTF-8",
            "--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED",
            "-jar",
            helperJar.absolutePath,
            "load-agent", // Command for InspectorGadget
            pid,
            absoluteAgentPath // Agent path argument
        )
        // Add options if provided
        options?.let { command.add(it) }

        val processBuilder = ProcessBuilder(command)
        println("loadAgent(): Launching InspectorGadget: ${command.joinToString(" ")}")
        val process: Process
        try {
            process = processBuilder.start()
        } catch (e: IOException) {
            throw InspectionException("Failed to launch InspectorGadget process for agent loading", e)
        }

        // 4. Process Output and Wait
        val errorOutput = StringBuilder()
        val stdoutLogs = mutableListOf<String>() // Capture stdout for debugging
        var exitCode: Int? = null

        try {
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8))

            var line: String?
            // Read stdout
            while (stdoutReader.readLine().also { line = it } != null) {
                stdoutLogs.add("Helper stdout: $line")
            }
            // Read stderr
            while (stderrReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            // Wait for process completion
            if (!process.waitFor(20, TimeUnit.SECONDS)) { // Shorter timeout for agent load?
                process.destroyForcibly()
                throw InspectionException(
                    "InspectorGadget process timed out during agent load.",
                    errorOutput = errorOutput.toString().trim().ifEmpty { null } + "\n--- Stdout Log ---\n" + stdoutLogs.joinToString("\n")
                )
            }

            exitCode = process.exitValue()
            println("loadAgent(): InspectorGadget process finished with exit code: $exitCode")
            stdoutLogs.forEach(::println) // Print captured stdout
            if (errorOutput.isNotEmpty()) {
                System.err.println("--- Helper Error Output ---")
                System.err.print(errorOutput)
                System.err.println("-------------------------")
            }

            if (exitCode != 0) {
                throw InspectionException(
                    "InspectorGadget failed to load agent '$agentPath' (exit code $exitCode).",
                    errorOutput = errorOutput.toString().trim().ifEmpty { null }
                )
            }

            println("loadAgent(): Agent '$agentPath' likely loaded successfully.")

        } catch (e: IOException) {
            throw InspectionException("I/O error communicating with InspectorGadget process during agent load", e, errorOutput.toString().trim().ifEmpty { null })
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Interrupted while waiting for InspectorGadget process during agent load.")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            // Files.deleteIfExists(helperJar.toPath())
        }
    }

    /**
     * Gets the current process ID.
     */
    internal fun getCurrentPid(): String {
        // ProcessHandle is Java 9+
        return try {
            ProcessHandle.current().pid().toString()
        } catch (t: Throwable) {
            // Fallback for older Java versions or if ProcessHandle fails
            val name = ManagementFactory.getRuntimeMXBean().name
            name.substringBefore('@')
        }
    }

    /**
     * Creates the temporary JAR file containing the InspectorGadget class.
     */
    @Throws(IOException::class, IllegalStateException::class)
    internal fun createInspectorGadgetJar(): File {
        val resourcePath = "/one/wabbit/inspect/InspectorGadget.class" // Relative to resources root
        val classBytes = object {}.javaClass.getResourceAsStream(resourcePath)?.readBytes()
            ?: throw IllegalStateException("Resource not found: $resourcePath. Ensure InspectorGadget.class is compiled and in the correct resources path.")

        val jarFile = Files.createTempFile("inspector-gadget-", ".jar").toFile()
        jarFile.deleteOnExit() // Clean up later

        JarOutputStream(Files.newOutputStream(jarFile.toPath())).use { jos ->
            // Manifest
            jos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            jos.write("Manifest-Version: 1.0\n".toByteArray())
            jos.write("Main-Class: one.wabbit.inspect.InspectorGadget\n".toByteArray()) // Must match the class FQN
            jos.closeEntry()

            // Class file
            // Path inside JAR must match package structure
            jos.putNextEntry(ZipEntry("one/wabbit/inspect/InspectorGadget.class"))
            jos.write(classBytes)
            jos.closeEntry()
        }
        return jarFile
    }
}