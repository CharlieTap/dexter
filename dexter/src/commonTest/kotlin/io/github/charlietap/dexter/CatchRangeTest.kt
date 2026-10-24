package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val ERROR = ClassType("Ljava/lang/Error;")
private const val T1 = "Ljava/lang/Throwable;"
private const val T2 = "Ljava/lang/Error;"
private const val ALL = "*"

private fun flattened(
    declare: CodeEmitter.(at: Labels, pads: List<HandlerLabel>) -> Unit,
): List<Triple<Int, Int, List<Pair<String, Int>>>> {
    val code = emit {
        val at = labels(11)
        val pads = List(2) { handlerLabel() }
        for (index in 0 until 10) {
            bind(at[index])
            const(v(0), 0)
        }
        bind(at[10])
        bindHandler(pads[0])
        returnVoid()
        bindHandler(pads[1])
        returnVoid()
        declare(at, pads)
    }.code
    return code.tries.map { Triple(it.start, it.count, it.handlers) }
}

@Burst
class CatchRangeTest {

    enum class Flattening(
        val declare: CodeEmitter.(Labels, List<HandlerLabel>) -> Unit,
        val expected: List<Triple<Int, Int, List<Pair<String, Int>>>>,
    ) {
        SINGLE(
            { at, pads -> catchRange(at[2], at[5], THROWABLE, pads[0]) },
            listOf(Triple(2, 3, listOf(T1 to 10))),
        ),
        NESTED(
            { at, pads ->
                catchRange(at[0], at[10], THROWABLE, pads[0])
                catchRange(at[3], at[6], ERROR, pads[1])
            },
            listOf(Triple(0, 3, listOf(T1 to 10)), Triple(3, 3, listOf(T2 to 11, T1 to 10)), Triple(6, 4, listOf(T1 to 10))),
        ),
        INNER_DECLARED_FIRST(
            { at, pads ->
                catchRange(at[3], at[6], ERROR, pads[1])
                catchRange(at[0], at[10], THROWABLE, pads[0])
            },
            listOf(Triple(0, 3, listOf(T1 to 10)), Triple(3, 3, listOf(T2 to 11, T1 to 10)), Triple(6, 4, listOf(T1 to 10))),
        ),
        INNER_CATCHES_THE_SAME_TYPE(
            { at, pads ->
                catchRange(at[0], at[10], THROWABLE, pads[0])
                catchRange(at[3], at[6], THROWABLE, pads[1])
            },
            listOf(Triple(0, 3, listOf(T1 to 10)), Triple(3, 3, listOf(T1 to 11)), Triple(6, 4, listOf(T1 to 10))),
        ),
        INNER_CATCH_ALL(
            { at, pads ->
                catchRange(at[0], at[10], THROWABLE, pads[0])
                catchAllRange(at[3], at[6], pads[1])
            },
            listOf(Triple(0, 3, listOf(T1 to 10)), Triple(3, 3, listOf(ALL to 11)), Triple(6, 4, listOf(T1 to 10))),
        ),
        OUTER_CATCH_ALL(
            { at, pads ->
                catchAllRange(at[0], at[10], pads[0])
                catchRange(at[3], at[6], ERROR, pads[1])
            },
            listOf(Triple(0, 3, listOf(ALL to 10)), Triple(3, 3, listOf(T2 to 11, ALL to 10)), Triple(6, 4, listOf(ALL to 10))),
        ),
        EQUAL_RANGES_IN_DECLARATION_ORDER(
            { at, pads ->
                catchRange(at[0], at[10], ERROR, pads[1])
                catchRange(at[0], at[10], THROWABLE, pads[0])
            },
            listOf(Triple(0, 10, listOf(T2 to 11, T1 to 10))),
        ),
        ADJACENT_RANGES_MERGE(
            { at, pads ->
                catchRange(at[0], at[3], THROWABLE, pads[0])
                catchRange(at[3], at[6], THROWABLE, pads[0])
            },
            listOf(Triple(0, 6, listOf(T1 to 10))),
        ),
        DISJOINT_RANGES(
            { at, pads ->
                catchRange(at[0], at[3], THROWABLE, pads[0])
                catchRange(at[5], at[8], THROWABLE, pads[0])
            },
            listOf(Triple(0, 3, listOf(T1 to 10)), Triple(5, 3, listOf(T1 to 10))),
        ),
        EMPTY_RANGE_SKIPPED(
            { at, pads -> catchRange(at[4], at[4], THROWABLE, pads[0]) },
            emptyList(),
        ),
        END_AT_CODE_END(
            { at, pads -> catchRange(at[0], boundLabel(), THROWABLE, pads[0]) },
            listOf(Triple(0, 12, listOf(T1 to 10))),
        ),
    }

    @Test
    fun `nested ranges flatten into disjoint try items`(
        case: Flattening,
    ) {
        val tries = flattened(case.declare)

        assertEquals(case.expected, tries)
    }

    enum class Invalid(
        val declare: CodeEmitter.(Labels, List<HandlerLabel>) -> Unit,
    ) {
        PARTIAL_OVERLAP({ at, pads ->
            catchRange(at[0], at[5], THROWABLE, pads[0])
            catchRange(at[3], at[8], ERROR, pads[1])
        }),
        ENDS_BEFORE_IT_STARTS({ at, pads -> catchRange(at[5], at[3], THROWABLE, pads[0]) }),
        UNBOUND_END({ at, pads -> catchRange(at[0], label(), THROWABLE, pads[0]) }),
        UNBOUND_HANDLER({ at, _ -> catchRange(at[0], at[3], THROWABLE, handlerLabel()) }),
        HANDLER_AFTER_THE_LAST_INSTRUCTION({ at, _ ->
            val handler = handlerLabel()
            bindHandler(handler)
            catchRange(at[0], at[3], THROWABLE, handler)
        }),
    }

    @Test
    fun `invalid ranges are rejected at end`(
        case: Invalid,
    ) {
        assertFailsWith<IllegalStateException> { flattened(case.declare) }
    }

    @Test
    fun `a handler can move the caught exception into a register`() {
        val insns = emit {
            val start = boundLabel()
            const(v(0), 0)
            val end = boundLabel()
            returnVoid()
            val pad = handlerLabel()
            bindHandler(pad, v(200))
            throwValue(v(200))
            catchRange(start, end, THROWABLE, pad)
        }.insns

        assertEquals(hex(units(0x0012, 0x000E, 0xC80D, 0xC827)), hex(insns))
    }

    enum class UnreachableHandler(
        val body: CodeEmitter.() -> Unit,
    ) {
        USED_BY_NO_RANGE({
            returnVoid()
            bindHandler(handlerLabel(), v(0))
            throwValue(v(0))
        }),
        FALLS_INTO_IT({
            val start = boundLabel()
            const(v(0), 0)
            val end = boundLabel()
            val pad = handlerLabel()
            bindHandler(pad, v(0))
            throwValue(v(0))
            catchAllRange(start, end, pad)
        }),
        AT_METHOD_ENTRY({
            val start = boundLabel()
            val pad = handlerLabel()
            bindHandler(pad, v(0))
            val end = boundLabel()
            throwValue(v(0))
            catchAllRange(start, end, pad)
        }),
        BRANCH_TARGET({
            val start = boundLabel()
            const(v(0), 0)
            val target = label()
            branchZero(Cond.Eq, v(0), target)
            val end = boundLabel()
            returnVoid()
            bind(target)
            val pad = handlerLabel()
            bindHandler(pad, v(0))
            throwValue(v(0))
            catchAllRange(start, end, pad)
        }),
        SWITCH_TARGET({
            val start = boundLabel()
            val target = label()
            switch(v(0), SwitchCases().apply { case(1, target) })
            val end = boundLabel()
            returnVoid()
            bind(target)
            val pad = handlerLabel()
            bindHandler(pad, v(0))
            throwValue(v(0))
            catchAllRange(start, end, pad)
        }),
    }

    @Test
    fun `a handler that reads its exception must be reached only by an exception`(
        case: UnreachableHandler,
    ) {
        assertFailsWith<IllegalStateException> { emit(body = case.body) }
    }

    @Test
    fun `a handler reads its exception into a register below 256`() {
        assertFailsWith<IllegalArgumentException> { emit { bindHandler(handlerLabel(), v(256)) } }
    }

    @Test
    fun `a handler without an exception register emits nothing`() {
        val insns = emit {
            val start = boundLabel()
            const(v(0), 0)
            val end = boundLabel()
            returnVoid()
            val pad = handlerLabel()
            bindHandler(pad)
            returnVoid()
            catchRange(start, end, THROWABLE, pad)
        }.insns

        assertEquals(hex(units(0x0012, 0x000E, 0x000E)), hex(insns))
    }
}
