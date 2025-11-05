package one.wabbit.inspect

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodNode
import java.lang.invoke.SerializedLambda
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.kotlinFunction

/**
 * Thread-safe helper that computes a *stable* textual identifier
 * for functions/constructors **and** classes.
 *
 * For *named* callables we compose
 *
 *     <owner-FQN>#<name><JVM-descriptor>
 *
 * For constructors we use "<init>" as the name part.
 *
 * For **lambdas / local / anonymous functions** we fall back to
 *
 *     <sourceFile>:<firstLine>-<lastLine>@<enclosingMethod>[<ordinal>]
 *
 * which survives any clean rebuild as long as you don’t change the
 * relative position of the lambda inside its enclosing method.
 *
 * Results are cached in-memory for speed.
 */
object StableId {

    /*───────────────────────────  public API  ───────────────────────────*/

    /** Stable id of a function or constructor. */
    fun of(funOrCtor: KFunction<*>): String =
        fnCache.computeIfAbsent(funOrCtor) { computeForFunction(it) }

    /** Stable id of a class (KClass overload). */
    fun of(kcls: KClass<*>): String =
        clsCache.computeIfAbsent(kcls.java) { computeForClass(it) }

    /** Stable id of a class (java.lang.Class overload). */
    fun of(jcls: Class<*>): String =
        clsCache.computeIfAbsent(jcls) { computeForClass(it) }

    /** Stable id of a lambda instance (`Function<*>`). */
    fun of(lambda: Function<*>): String =
        lambdaCache.computeIfAbsent(lambda.javaClass) { computeForLambda(lambda) }

    /*─────────────────────────  internal state  ─────────────────────────*/

    private val fnCache = ConcurrentHashMap<KFunction<*>, String>()
    private val clsCache = ConcurrentHashMap<Class<*>, String>()
    private val lambdaCache = ConcurrentHashMap<Class<*>,       String>()
    private val classNodeCache = ConcurrentHashMap<Class<*>, ClassNode>()

    /*─────────────────────────  implementation  ─────────────────────────*/

    /* ----------  functions / constructors  ---------- */

    private fun computeForLambda(lambda: Function<*>): String {
        throw UnsupportedOperationException(
            "StableId.of(lambda) is unsupported."
        )

//        // 1. Cheap path – works when lambda is serializable
//        runCatching {
//            val sl = lambda.javaClass.getDeclaredMethod("writeReplace")
//                .apply { isAccessible = true }
//                .invoke(lambda) as java.lang.invoke.SerializedLambda
//            return buildIdFromSerializedLambda(sl)
//        }
//
//        // 2. Robust owner resolution
//        val lambdaCls = lambda.javaClass
//        val owner: Class<*>? =
//            lambdaCls.enclosingClass
//                ?: lambdaCls.enclosingMethod?.declaringClass
//                ?: lambdaCls.enclosingConstructor?.declaringClass
//                ?: runCatching {                       // ← new: name-parsing fallback
//                    val ownerName = lambdaCls.name.substringBefore("\$\$Lambda")
//                    Class.forName(ownerName, false, lambdaCls.classLoader)
//                }.getOrNull()
//
//        if (owner == null) throw IllegalStateException(
//            "StableId: cannot resolve owner of lambda ${lambdaCls.name} " +
//            "(enclosingClass, enclosingMethod, enclosingConstructor are all null)"
//        )
//
//        println("λ-debug  owner = ${owner.name}")
//
//        // 3. Build the *erased* SAM descriptor from the interface "FunctionN"
//
//        println("λ-debug  SAM interfaces = ${lambdaCls.interfaces.toList()}")
//        println("λ-debug  lambda methods = " +
//                lambdaCls.declaredMethods.joinToString { "${it.name}${Type.getMethodDescriptor(it)}" })
//        val samInterface = lambdaCls.interfaces.first()        //  kotlin.jvm.functions.FunctionN
//
//        val lambdaInvoke = lambdaCls.declaredMethods
//            .firstOrNull { it.name == "invoke" && !it.isBridge }
//        val samInvoke = samInterface.methods
//            .first { it.name == "invoke" && !it.isBridge }
//
//        println("λ-debug  lambda invoke method = " +
//                "${lambdaInvoke?.name}${Type.getMethodDescriptor(lambdaInvoke)}")
//        println("λ-debug  SAM invoke method    = " +
//                "${samInvoke.name}${Type.getMethodDescriptor(samInvoke)}")
//
//        val samDesc = lambdaInvoke?.let { Type.getMethodDescriptor(it) }
//            ?: Type.getMethodDescriptor(samInvoke)
//        println("λ-debug  SAM descriptor = $samDesc")
//
//        // 4. Parse owner’s byte-code (cached) and locate the invokedynamic.
//        val cn = classNodeCache.computeIfAbsent(owner, ::loadAsm)
//
//        println("λ-debug  desired SAM desc = $samDesc")
//
//        owner.declaredMethods.forEach { m ->
//            println("λ-debug  owner method  -> ${m.name} :: ${Type.getMethodDescriptor(m)}")
//        }
//
//        cn.methods.forEach { mn ->
//            val match = indyOrdinalAndLines(mn, samDesc)
//            if (match != null) {
//                val (ord, range) = match
//                return "${cn.sourceFile}:${range.first}-${range.last}@${mn.name}[$ord]"
//            }
//        }
//
//        /* 4) Fallback – shouldn’t happen in regular Kotlin sources */
//        return lambdaCls.name
    }

    /* Helper – returns Pair(ordinal, line-range) or null */
    private fun indyOrdinalAndLines(
        mn: org.objectweb.asm.tree.MethodNode,
        samDesc: String
    ): Pair<Int, IntRange>? {
        var ordinal = 0
        val lines = mutableListOf<Int>()

        mn.instructions?.forEach { insn ->
            when (insn) {
                is org.objectweb.asm.tree.LineNumberNode ->
                    lines += insn.line

                is org.objectweb.asm.tree.InvokeDynamicInsnNode -> {
                    println("λ-debug  InvokeDynamicInsnNode: " +
                            "${insn.name} ${insn.desc} ${insn.bsmArgs?.toList() ?: "[]"} " +
                            "ordinal=$ordinal")
                    println("λ-debug  bsm = ${insn.bsm} ")
                    println("λ-debug  bsmArgs[0].type = ${insn.bsmArgs[0].javaClass}")
                    /*  Bootstrap argument #1 is the SAM Type  */
                    val indySam = (insn.bsmArgs[0] as? org.objectweb.asm.Type)?.descriptor
                    if (indySam == samDesc) {
                        val range = (lines.minOrNull() ?: 0)..(lines.maxOrNull() ?: 0)
                        return ordinal to range
                    }
                    ordinal++
                }
            }
        }
        return null
    }

    /* ---------- helpers ------------------------------------------------ */

    private fun buildIdFromSerializedLambda(sl: SerializedLambda): String {
        val ownerFqn  = sl.implClass.replace('/', '.')
        val implName  = sl.implMethodName
        val implDesc  = sl.implMethodSignature

        val ownerCls  = Class.forName(ownerFqn, false, StableId::class.java.classLoader)
        val cn        = classNodeCache.computeIfAbsent(ownerCls, ::loadAsm)
        val mn        = cn.methods.firstOrNull { it.name == implName && it.desc == implDesc }
            ?: return "$ownerFqn#$implName$implDesc"

        val lines = mn.instructions.filterIsInstance<LineNumberNode>().map { it.line }
        val minL  = lines.minOrNull() ?: 0
        val maxL  = lines.maxOrNull() ?: minL
        return "${cn.sourceFile}:$minL-$maxL@$implName"
    }

    private fun computeForFunction(kf: KFunction<*>): String {
        // Fast path: ordinary method
        runCatching { kf.javaMethod }.getOrNull()
            ?.let { return ordinaryMethodId(it) }

        // Fast path: constructor
        runCatching { kf.javaConstructor }.getOrNull()
            ?.let { return ctorId(it) }

        // Fast path 1 – ordinary method (may throw on FunctionN.invoke)
        (kf.safeJavaMethod() ?: kf.safeJavaConstructor())?.let {
            return if (it is Method) ordinaryMethodId(it) else ctorId(it as Constructor<*>)
        }

        // Lambda / local / anonymous
        return lambdaId(kf)
    }

    /** Same as `KFunction::javaMethod` but swallows KotlinReflectionInternalError. */
    private fun KFunction<*>.safeJavaMethod(): Method? =
        runCatching { javaMethod }.getOrNull()

    /** Same as `KFunction::javaConstructor` but swallows KotlinReflectionInternalError. */
    private fun KFunction<*>.safeJavaConstructor(): Constructor<*>? =
        runCatching { javaConstructor }.getOrNull()

    private fun ordinaryMethodId(m: Method): String =
        "${m.declaringClass.name}#" +
                "${m.name}${Type.getMethodDescriptor(m)}"

    private fun ctorId(c: Constructor<*>): String =
        "${c.declaringClass.name}#<init>${Type.getConstructorDescriptor(c)}"

    private val kFunctionImplClass = Class.forName("kotlin.reflect.jvm.internal.KFunctionImpl")
    private val boundReceiverValue = kFunctionImplClass.getDeclaredField("rawBoundReceiver").apply {
        isAccessible = true // Allow access to the private field
    }

    /**
     * Builds a stable id for a lambda / local / anonymous function.
     *
     * 1.  We take the lambda **instance** (kf is backed by that object),
     *     grab its synthetic class.
     * 2.  We locate the *enclosing* method by using `enclosingMethod()`
     *     metadata; if absent we inspect every method for the
     *     matching `invokedynamic`.
     * 3.  Inside the enclosing method we iterate over `invokeDynamic`
     *     instructions, counting an *ordinal* until we find the one
     *     whose descriptor matches the lambda’s invoked type.
     * 4.  We gather **line numbers** of that method to obtain a range.
     * 5.  Combine to:  `<sourceFile>:<min>-<max>@<method>[<ordinal>]`
     */
    private fun lambdaId(kf: KFunction<*>): String {
        // Best effort to get the *instance* that owns the synthetic lambda class
        val lambdaObj =
            // Case 1 – user already passed the instance
            (kf as? Function<*>) ?:
            // Case 2 – a bound reference (KFunctionImpl) -> try its boundReceiver
            boundReceiverValue.get(kf) as? Function<*> ?:
            error(
                "StableId: please pass the lambda instance itself " +
                " (StableId.of(myLambda)) instead of myLambda::invoke"
            )

        return lambdaCache.computeIfAbsent(lambdaObj.javaClass) { computeForLambda(lambdaObj) }
    }

    /**
     * Walks the instructions of [mn]; returns
     * *ordinal* of the first `invokedynamic` matching [targetDesc] and
     * all line numbers seen in that method.
     *
     * @return Pair(ordinal, IntRange of lines) ; ordinal == -1 if not found
     */
    private fun locateInvokeDynamic(
        mn: MethodNode,
        samDesc: String,                       // "(Ljava/lang/String;)Ljava/lang/String;" etc.
        stopOnFirst: Boolean = true
    ): Pair<Int, IntRange> {

        var ordinal  = 0
        val lineNums = mutableListOf<Int>()

        mn.instructions?.forEach { insn ->
            when (insn) {
                is LineNumberNode        -> lineNums += insn.line

                is InvokeDynamicInsnNode -> {
                    val args = insn.bsmArgs
                    if (args != null && args.size >= 2 && args[1] is org.objectweb.asm.Type) {
                        val argDesc = (args[1] as org.objectweb.asm.Type).descriptor
                        if (argDesc == samDesc) {
                            val range = lineNums.minOrNull()!!..lineNums.maxOrNull()!!
                            return ordinal to range
                        }
                    }
                    ordinal++
                }
            }
        }
        return -1 to IntRange.EMPTY
    }

    private fun buildLambdaSignature(
        cn: ClassNode,
        enclosingMethodName: String,
        lineRange: IntRange,
        ordinal: Int
    ) = "${cn.sourceFile}:${lineRange.first}-${lineRange.last}@" +
            "$enclosingMethodName[$ordinal]"

    /* ----------  classes  ---------- */

    private fun computeForClass(cls: Class<*>): String {
        // Canonical name is stable for normal and inner classes.
        cls.canonicalName?.let { return it }

        // Anonymous class → tag with its enclosing class & first line
        val encCls = cls.enclosingClass
        val encMethod = cls.enclosingMethod
        val encCtor = cls.enclosingConstructor

        return when {
            encMethod != null -> "${encCls.name}.${encMethod.name}\$anon"
            encCtor != null   -> "${encCls.name}.<init>\$anon"
            else              -> cls.name                 // fallback
        }
    }

    /* ----------  ASM helpers  ---------- */

    private fun loadAsm(jcls: Class<*>): ClassNode =
        jcls.getResourceAsStream("/${jcls.name.replace('.', '/')}.class")
            ?.use { stream ->
                val cr = ClassReader(stream)
                val cn = ClassNode()
                cr.accept(cn, ClassReader.SKIP_DEBUG)
                cn
            }
            ?: throw IllegalStateException("Byte-code not found for ${jcls.name}")
}
