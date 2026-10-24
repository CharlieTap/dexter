package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@Burst
class DebugInfoTest {

    enum class Lines(
        val body: CodeEmitter.() -> Unit,
        val expected: List<Pair<Int, Int>>,
    ) {
        ONE_LINE_PER_INSTRUCTION(
            {
                line(10)
                const(v(0), 0)
                line(11)
                const(v(0), 0)
                line(9)
                returnVoid()
            },
            listOf(0 to 10, 1 to 11, 2 to 9),
        ),
        LATER_LINE_AT_THE_SAME_ADDRESS_WINS(
            {
                line(1)
                line(2)
                returnVoid()
            },
            listOf(0 to 2),
        ),
        LARGE_LINE_JUMPS(
            {
                line(5)
                const(v(0), 0)
                line(100_000)
                const(v(0), 0)
                line(3)
                returnVoid()
            },
            listOf(0 to 5, 1 to 100_000, 2 to 3),
        ),
        LARGE_ADDRESS_GAPS(
            {
                line(1)
                repeat(40) { const(v(0), 0) }
                line(2)
                returnVoid()
            },
            listOf(0 to 1, 40 to 2),
        ),
        FIRST_LINE_AFTER_THE_ENTRY(
            {
                const(v(0), 0)
                line(7)
                returnVoid()
            },
            listOf(1 to 7),
        ),
    }

    @Test
    fun `lines decode to the instructions they mark`(
        case: Lines,
    ) {
        val lines = emit(body = case.body).code.debugInfo?.lines

        assertEquals(case.expected, lines)
    }

    @Test
    fun `lines follow relaxed branches`() {
        val lines = emit(dexter = reaching(4)) {
            val target = label()
            line(1)
            goto(target)
            repeat(6) { const(v(0), 0) }
            line(2)
            bind(target)
            returnVoid()
        }.code.debugInfo?.lines

        assertEquals(listOf(0 to 1, 9 to 2), lines)
    }

    @Test
    fun `debug info counts the method's parameters`() {
        val parameters = emit(parameters = listOf(DexType.Int, DexType.Long)) {
            line(1)
            returnVoid()
        }.code.debugInfo?.parameters

        assertEquals(2, parameters)
    }

    @Test
    fun `a method without lines has no debug info`() {
        assertNull(emit { returnVoid() }.code.debugInfo)
    }

    @Test
    fun `lines start at 1`() {
        assertFailsWith<IllegalArgumentException> { emit { line(0) } }
    }

    @Test
    fun `classes name their source file`() {
        val bytes = Dexter().assemble { addClass(T, DexType.Object, sourceFile = "module.wasm") }

        assertEquals("module.wasm", DexReader(bytes).classes.single().sourceFile)
    }
}
