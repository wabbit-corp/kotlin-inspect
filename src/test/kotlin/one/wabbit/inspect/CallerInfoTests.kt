package one.wabbit.inspect

import one.wabbit.inspect.Inspect.callerClass
import one.wabbit.inspect.Inspect.callerClassName
import one.wabbit.inspect.Inspect.callerFunction
import one.wabbit.inspect.Inspect.callerMethodName
import one.wabbit.inspect.Inspect.callerSourceLocation
import kotlin.reflect.KFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallerInfoTests {
    @Test
    fun `callerSourceLocation returns something plausible`() {
        val (fileName, lineNumber) = callerSourceLocation()
        assertEquals("CallerInfoTests.kt", fileName)
        assertTrue(lineNumber > 0)
    }

    @Test
    fun `callerClass should return the test class`() {
        assertEquals(this::class, callerClass())
        assertEquals(this::class.simpleName, callerClassName())
    }

    @Test
    fun `callerMethodName should return the test method name`() {
        val callerMethod = callerMethodName()
        assertEquals("callerMethodName should return the test method name", callerMethod)
    }

    @Test
    fun `callerFunction should return KFunction of the test method`() {
        val kFun = callerFunction()
        assertEquals("callerFunction should return KFunction of the test method", kFun.name)
        assertEquals(1, kFun.parameters.size) // this
        assertEquals(Unit::class, kFun.returnType.classifier)
    }

    // Helper for overload tests
    fun overloadedMethod(s: String): KFunction<*> = callerFunction()
    fun overloadedMethod(i: Int): KFunction<*> = callerFunction()

    @Test
    fun `callerFunction should distinguish overloaded methods`() {
        val callerFromStr: KFunction<*> = overloadedMethod("test")
        val callerFromInt: KFunction<*> = overloadedMethod(123)

        assertEquals("overloadedMethod", callerFromStr.name)
        assertEquals(String::class, callerFromStr.parameters.last().type.classifier)

        assertEquals("overloadedMethod", callerFromInt.name)
        assertEquals(Int::class, callerFromInt.parameters.last().type.classifier)
    }
}