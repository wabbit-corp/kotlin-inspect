package one.wabbit.inspect

import one.wabbit.inspect.Inspect.closureVarsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClosureInspectionTests {

    val instanceVar = "InstanceValue"

    @Test
    fun `closureVarsOf lambda capturing local val`() {
        val localVar = "LocalValue"
        val lambda = { println("Accessing: $localVar") }

        // Use the overload taking the lambda instance directly
        val vars = closureVarsOf(lambda)
        assertTrue(vars.isNotEmpty())

        // Name might be '$localVar' or similar compiler-generated name
        val capturedEntry = vars.entries.first()
        assertEquals("LocalValue", capturedEntry.value)
    }

    @Test
    fun `closureVarsOf lambda capturing local var`() {
        var localVar = 123
        val lambda = { println("Accessing: ${localVar++}") }
        lambda() // Execute once to potentially change value

        val vars = closureVarsOf(lambda)
        assertTrue(vars.isNotEmpty())
        val capturedEntry = vars.entries.first()

        // Mutable vars are often wrapped in Ref objects (e.g., IntRef)
        // Check the type and value inside the Ref object
        assertTrue(capturedEntry.value is kotlin.jvm.internal.Ref.IntRef)
        val refValue = (capturedEntry.value as kotlin.jvm.internal.Ref.IntRef).element
        assertEquals(124, refValue) // Value after increment
    }

    @Test
    fun `closureVarsOf lambda capturing instance var and this`() {
        val lambda = { println("Accessing instance: $instanceVar and this: $this") }

        val vars = closureVarsOf(lambda)
        // Expect 'this$0' (or similar) for outer class instance
        // Expect '$instanceVar' if captured directly, or accessed via 'this$0'
        assertTrue(vars.isNotEmpty()) // Should capture at least 'this$0'
        assertTrue(vars.values.any { it === this }) // Check for outer 'this'
        // Direct capture of instanceVar is less common; usually accessed via 'this$0'
        // So, we might not see a field named '$instanceVar'.
    }

//    @Test
//    fun `closureVarsOf function reference capturing nothing`() {
//        fun topLevelFunc() {}
//        val funcRef = ::topLevelFunc
//
//        // Use the KFunction overload (less reliable for closures)
//        val vars = closureVarsOf(funcRef)
//        println("Vars for top-level func ref: $vars")
//
//        // Top-level function references usually don't capture anything
//        assertTrue(vars.isEmpty())
//    }
//
//    @Test
//    fun `closureVarsOf member function reference capturing this`() {
//        val memberRef = this::instanceVarAccess
//
//        // Use the KFunction overload
//        val vars = closureVarsOf(memberRef)
//        println("Vars for member func ref: $vars")
//
////        // Member references capture the receiver instance ('this')
////        assertThat(vars).hasSize(1) // Should capture receiver
////        val capturedEntry = vars.entries.first()
////        assertThat(capturedEntry.key).isEqualTo("receiver") // Common name for receiver field
////        assertThat(capturedEntry.value).isSameAs(this@ClosureInspectionTests)
//    }

    fun instanceVarAccess() = instanceVar // Helper for member ref test
}