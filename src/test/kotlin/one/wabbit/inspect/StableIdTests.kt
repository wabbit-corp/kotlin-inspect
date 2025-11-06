package one.wabbit.inspect

import kotlin.reflect.full.functions
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/* ---------------------------------------------------------------------------
 *  Auxiliary declarations used only by the tests
 * ------------------------------------------------------------------------- */

/** Top-level function (no receiver) to verify FQN handling. */
internal fun sampleTopLevel(a: Int, b: String): String = "$a$b"

/** Class with several members / ctors used in multiple tests. */
class SampleClass {
    fun intFun(i: Int) = i + 1

    fun strFun(s: String) = s.reversed()

    constructor() // secondary

    constructor(flag: Boolean) // primary

    companion object {
        @JvmStatic fun staticFun() {}
    }
}

/** Class with *overloaded* methods to ensure descriptor disambiguation. */
class OverloadClass {
    fun process(i: Int) = i

    fun process(s: String) = s.length
}

/* ---------------------------------------------------------------------------
 *  Test-suite
 * ------------------------------------------------------------------------- */
class StableIdTests {
    // ----------  named / top-level / member / static  ----------

    @Test
    fun `top-level function id contains name`() {
        val id = StableId.of(::sampleTopLevel)
        assertTrue(id.contains("#sampleTopLevel("))
    }

    @Test
    fun `member method id contains class and name`() {
        val id = StableId.of(SampleClass::intFun)
        assertTrue(id.contains("SampleClass#intFun("))
    }

    @Test
    fun `static companion method id contains class and name`() {
        val id = StableId.of(SampleClass.Companion::staticFun)
        assertTrue(id.contains("SampleClass\$Companion#staticFun("))
    }

    // ----------  constructors  ----------

    @Test
    fun `different constructors yield different ids`() {
        val primary = SampleClass::class.constructors.first { it.parameters.size == 1 }
        val secondary = SampleClass::class.constructors.first { it.parameters.isEmpty() }

        val idPrimary = StableId.of(primary)
        val idSecondary = StableId.of(secondary)

        assertNotEquals(idPrimary, idSecondary)
        assertTrue(idPrimary.contains("#<init>("))
        assertTrue(idSecondary.contains("#<init>("))
    }

    // ----------  overload resolution  ----------

    @Test
    fun `overloaded methods have distinct ids`() {
        val intFun =
            OverloadClass::class.functions.single {
                it.name == "process" && it.parameters.last().type.classifier == Int::class
            }
        val strFun =
            OverloadClass::class.functions.single {
                it.name == "process" && it.parameters.last().type.classifier == String::class
            }

        val idInt = StableId.of(intFun)
        val idStr = StableId.of(strFun)

        assertNotEquals(idInt, idStr)
        assertTrue(idInt.contains("(I)"))
        assertTrue(idStr.contains("(Ljava/lang/String;)"))
    }

    // ----------  lambdas / local / anonymous  ----------

    @Ignore
    @Test
    fun `FAILING different lambdas on different lines differ`() {
        val lamA = { x: Int -> x + 1 } // line N
        val lamB = { x: Int -> x + 2 } // line N+1

        val idA = StableId.of(lamA)
        val idB = StableId.of(lamB)

        assertNotEquals(idA, idB)
    }

    @Ignore
    @Test
    fun `FAILING lambda id repeats identically for same instance`() {
        val lam = { s: String -> s.uppercase() }
        val kfun = lam::invoke // bound reference carries instance

        val id1 = StableId.of(kfun)
        val id2 = StableId.of(kfun) // same instance, second call

        assertEquals(id1, id2)
    }

    // ----------  class identifiers  ----------

    @Test
    fun `class id equals canonical name for ordinary class`() {
        val id = StableId.of(SampleClass::class)
        assertEquals("one.wabbit.inspect.SampleClass", id)
    }

    @Test
    fun `anonymous class id tagged with anon`() {
        val anonObj =
            object : Runnable {
                override fun run() {}
            }
        val id = StableId.of(anonObj::class)
        assertTrue(id.contains("\$anon"))
    }

    // ----------  caching behaviour  ----------

    @Test
    fun `StableId reuses cache on subsequent calls`() {
        val funRef = ::sampleTopLevel
        val first = StableId.of(funRef)
        val second = StableId.of(funRef) // served from in-memory cache

        assertSame(first, second)
    }
}
