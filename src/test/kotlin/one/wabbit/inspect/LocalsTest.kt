package one.wabbit.inspect

import one.wabbit.inspect.Inspect.locals
import kotlin.test.Ignore
import kotlin.test.Test

class LocalsTest {
    @Ignore @Test fun `locals() should return empty list`() {
        // -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:0
        val locals = locals()
        assert(locals.isEmpty()) { "Expected empty list, but got: $locals" }
    }
}