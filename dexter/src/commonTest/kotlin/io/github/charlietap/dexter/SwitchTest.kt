package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Burst
class SwitchTest {

    /** A switch at unit 0, a fall-through return at 3, then `first` at 4 and `second` at 5. */
    enum class Choice(
        val cases: (first: Label, second: Label) -> SwitchCases,
        val expected: IntArray,
    ) {
        PACKED_WITH_A_GAP(
            { first, second -> cases(1 to first, 3 to second) },
            units(
                0x002B,
                0x0006,
                0x0000,
                0x000E,
                0x000E,
                0x000E,
                0x0100,
                0x0003,
                0x0001,
                0x0000,
                0x0004,
                0x0000,
                0x0003,
                0x0000,
                0x0005,
                0x0000,
            ),
        ),
        SPARSE(
            { first, second -> cases(0 to first, 1000 to second) },
            units(
                0x002C,
                0x0006,
                0x0000,
                0x000E,
                0x000E,
                0x000E,
                0x0200,
                0x0002,
                0x0000,
                0x0000,
                0x03E8,
                0x0000,
                0x0004,
                0x0000,
                0x0005,
                0x0000,
            ),
        ),
        SPARSE_KEYS_SORTED(
            { first, second -> cases(1000 to second, -5 to first) },
            units(
                0x002C,
                0x0006,
                0x0000,
                0x000E,
                0x000E,
                0x000E,
                0x0200,
                0x0002,
                0xFFFB,
                0xFFFF,
                0x03E8,
                0x0000,
                0x0004,
                0x0000,
                0x0005,
                0x0000,
            ),
        ),
        SPANNING_INT_RANGE(
            { first, second -> cases(Int.MIN_VALUE to first, Int.MAX_VALUE to second) },
            units(
                0x002C,
                0x0006,
                0x0000,
                0x000E,
                0x000E,
                0x000E,
                0x0200,
                0x0002,
                0x0000,
                0x8000,
                0xFFFF,
                0x7FFF,
                0x0004,
                0x0000,
                0x0005,
                0x0000,
            ),
        ),
    }

    @Test
    fun `switch picks the smaller payload`(
        case: Choice,
    ) {
        val insns = emit {
            val first = label()
            val second = label()
            switch(v(0), case.cases(first, second))
            returnVoid()
            bind(first)
            returnVoid()
            bind(second)
            returnVoid()
        }.insns

        assertEquals(hex(case.expected), hex(insns))
    }

    @Test
    fun `an empty switch writes nothing`() {
        val insns = emit {
            switch(v(0), SwitchCases())
            returnVoid()
        }.insns

        assertEquals(hex(units(0x000E)), hex(insns))
    }

    @Test
    fun `cases can be reused after clear`() {
        val reused = SwitchCases()
        val insns = emit {
            val target = boundLabel()
            reused.case(7, target)
            reused.clear()
            reused.case(9, target)
            switch(v(0), reused)
        }.insns

        assertEquals(hex(units(0x002B, 0x0004, 0x0000, 0x0000, 0x0100, 0x0001, 0x0009, 0x0000, 0x0000, 0x0000)), hex(insns))
    }

    enum class Rejection(
        val body: CodeEmitter.() -> Unit,
    ) {
        DUPLICATE_KEY({
            val target = label()
            switch(v(0), cases(1 to target, 1 to target))
        }),
        REGISTER_ABOVE_255({ switch(v(256), cases(1 to label())) }),
        LABEL_FROM_ANOTHER_METHOD({ switch(v(0), cases(1 to Label(0L))) }),
        MORE_THAN_65535_CASES({
            val target = label()
            switch(v(0), SwitchCases().apply { for (key in 0..65535) case(key * 2, target) })
        }),
    }

    @Test
    fun `invalid switches are rejected`(
        case: Rejection,
    ) {
        assertFailsWith<IllegalArgumentException> { emit(body = case.body) }
    }
}

private fun cases(
    vararg entries: Pair<Int, Label>,
): SwitchCases = SwitchCases().apply { for ((key, label) in entries) case(key, label) }
