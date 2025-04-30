package one.wabbit.inspect

import one.wabbit.inspect.Inspect.bytecodeOf
import kotlin.reflect.full.functions
import kotlin.test.*

class BytecodeTests {
    class SimpleClass {
        fun simpleMethod() {}
        fun methodWithParams(x: Int, y: String): Boolean = true
        constructor(i: Int)
        constructor() : this(0) // Secondary constructor

        companion object {
            @JvmStatic fun staticMethod() {}
        }
    }

    @Test
    fun `bytecodeOf(KClass) should return non-empty string`() {
        val bytecode = bytecodeOf<SimpleClass>()
        assertTrue(bytecode.isNotBlank())
        assertTrue(bytecode.contains("SimpleClass"))
        assertTrue(bytecode.contains("simpleMethod"))
        assertTrue(bytecode.contains("methodWithParams"))
        assertTrue(bytecode.contains("<init>")) // Constructor
    }

    @Test
    fun `bytecodeOf(KFunction) for simple method`() {
        val kfun = SimpleClass::simpleMethod
        val bytecode = bytecodeOf(kfun)
        assertTrue(bytecode.isNotBlank())
        // Check for common bytecode instructions expected in a simple method
        assertTrue(!bytecode.contains("<init>")) // Should not contain constructor code
        assertContains(bytecode, "RETURN") // Simple void method returns
        assertTrue(!bytecode.contains("methodWithParams")) // Should only contain simpleMethod code
    }

    @Test
    fun `bytecodeOf(KFunction) for static method`() {
        val kfun = SimpleClass.Companion::staticMethod
        val bytecode = bytecodeOf(kfun)
        assertTrue(bytecode.isNotBlank())
        // Check for common bytecode instructions expected in a simple method
        assertTrue(!bytecode.contains("<init>")) // Should not contain constructor code
        assertContains(bytecode, "RETURN") // Simple void method returns
        assertTrue(!bytecode.contains("methodWithParams")) // Should only contain simpleMethod code
    }

    @Test
    fun `bytecodeOf(KFunction) for global method`() {
        val kfun = ::readln
        val bytecode = bytecodeOf(kfun)
        assertTrue(bytecode.isNotBlank())
    }

    @Test
    fun `FAILED bytecodeOf(KFunction) for local method`() {
        assertFailsWith<UnsupportedOperationException> {
            fun localMethod() {}
            val bytecode = bytecodeOf(::localMethod)
            assertTrue(bytecode.isNotBlank())
        }
    }

    @Test
    fun `FAILED bytecodeOf(KFunction) for invoke on another function`() {
        assertFailsWith<UnsupportedOperationException> {
            val bytecode = bytecodeOf(::readln::invoke)
            assertTrue(bytecode.isNotBlank())
        }
    }

    @Test
    fun `FAILED bytecodeOf(KFunction) for lambda`() {
        val kfun = { println("Hello, world!") }
        assertFailsWith<UnsupportedOperationException> {
            val bytecode = bytecodeOf(kfun::invoke)
            assertTrue(bytecode.isNotBlank())
        }
    }

    @Test
    fun `bytecodeOf(KFunction) for method with parameters`() {
        val kfun = SimpleClass::methodWithParams
        val bytecode = bytecodeOf(kfun)
        assertTrue(bytecode.isNotBlank())
        assertContains(bytecode, "ICONST_1") // Loading 'true'
        assertContains(bytecode, "IRETURN") // Return boolean (integer 1)
        assertTrue(!bytecode.contains("simpleMethod"))
    }

    @Test
    fun `bytecodeOf(KFunction) for primary constructor`() {
        // Find the primary constructor (takes Int)
        val kfun = SimpleClass::class.constructors.first { it.parameters.size == 1 && it.parameters[0].type.classifier == Int::class }
        val bytecode = bytecodeOf(kfun)
        assertTrue(bytecode.isNotBlank())
        assertContains(bytecode, "INVOKESPECIAL java/lang/Object.<init>") // Calls super constructor
        assertContains(bytecode, "RETURN")
    }

    @Test
    fun `bytecodeOf(KFunction) for secondary constructor`() {
        // Find the secondary constructor (takes no args)
        val kfun = SimpleClass::class.constructors.first { it.parameters.isEmpty() }
        val bytecode = bytecodeOf(kfun)
        assertTrue(bytecode.isNotBlank())
        // It should call the primary constructor (this(0))
        assertContains(bytecode, "ALOAD 0") // Load this
        assertContains(bytecode, "ICONST_0") // Load constant 0
        assertContains(bytecode, "INVOKESPECIAL one/wabbit/inspect/BytecodeTests\$SimpleClass.<init> (I)V") // Call primary constructor
        assertContains(bytecode, "RETURN")
    }

    // Test overload resolution
    class OverloadClass {
        fun process(i: Int) {}
        fun process(s: String) {}
    }

    @Test
    fun `bytecodeOf(KFunction) should handle overloaded methods correctly`() {
        val intFun = OverloadClass::class.functions.first { it.name == "process" && it.parameters.last().type.classifier == Int::class }
        val strFun = OverloadClass::class.functions.first { it.name == "process" && it.parameters.last().type.classifier == String::class }

        val intBytecode = bytecodeOf(intFun)
        val strBytecode = bytecodeOf(strFun)

        assertTrue(intBytecode.isNotBlank())
        assertTrue(strBytecode.isNotBlank())
        assertNotEquals(intBytecode, strBytecode)
    }
}