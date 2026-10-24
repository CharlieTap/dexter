package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Burst
class BranchTest {

    enum class Branch(
        val expected: IntArray,
        val body: CodeEmitter.() -> Unit,
        val reach: Int = 32767,
    ) {
        FORWARD_GOTO(units(0x0029, 0x0003, 0x1012, 0x000E), {
            val end = label()
            goto(end)
            const(v(0), 1)
            bind(end)
            returnVoid()
        }),
        FORWARD_IF(units(0x2134, 0x0003, 0x000E, 0x000E), {
            val end = label()
            branch(Cond.Lt, v(1), v(2), end)
            returnVoid()
            bind(end)
            returnVoid()
        }),
        FORWARD_IF_ZERO(units(0xFF3D, 0x0003, 0x000E, 0x000E), {
            val end = label()
            branchZero(Cond.Le, v(255), end)
            returnVoid()
            bind(end)
            returnVoid()
        }),
        BACKWARD_GOTO_8(units(0x0012, 0xFF28), {
            val top = label()
            bind(top)
            const(v(0), 0)
            goto(top)
        }),
        BACKWARD_GOTO_8_LIMIT(units(*IntArray(128) { 0x0012 }, 0x8028), {
            val top = label()
            bind(top)
            repeat(128) { const(v(0), 0) }
            goto(top)
        }),
        BACKWARD_GOTO_16(units(*IntArray(129) { 0x0012 }, 0x0029, 0xFF7F), {
            val top = label()
            bind(top)
            repeat(129) { const(v(0), 0) }
            goto(top)
        }),
        BACKWARD_GOTO_32(units(*IntArray(32769) { 0x0012 }, 0x002A, 0x7FFF, 0xFFFF), {
            val top = label()
            bind(top)
            repeat(32769) { const(v(0), 0) }
            goto(top)
        }),
        SELF_GOTO(units(0x002A, 0x0000, 0x0000), {
            val self = label()
            bind(self)
            goto(self)
        }),
        SELF_IF(units(0x0039, 0x0005, 0x002A, 0xFFFE, 0xFFFF, 0x000E), {
            val self = label()
            bind(self)
            branchZero(Cond.Eq, v(0), self)
            returnVoid()
        }),
        SHARED_ADDRESS(units(0x0029, 0x0004, 0x0029, 0x0002, 0x000E), {
            val first = label()
            val second = label()
            goto(first)
            goto(second)
            bind(first)
            bind(second)
            returnVoid()
        }),
        INVERTED_OVER_GOTO_32(
            units(0x0039, 0x0005, 0x002A, 0x0008, 0x0000, 0x0012, 0x0012, 0x0012, 0x0012, 0x0012, 0x000E),
            {
                val end = label()
                branchZero(Cond.Eq, v(0), end)
                repeat(5) { const(v(0), 0) }
                bind(end)
                returnVoid()
            },
            reach = 4,
        ),
        GOTO_AT_REACH(units(0x0029, 0x0007, 0x0012, 0x0012, 0x0012, 0x0012, 0x0012, 0x000E), {
            val end = label()
            goto(end)
            repeat(5) { const(v(0), 0) }
            bind(end)
            returnVoid()
        }, reach = 7),
        GOTO_PAST_REACH(units(0x002A, 0x0008, 0x0000, 0x0012, 0x0012, 0x0012, 0x0012, 0x0012, 0x000E), {
            val end = label()
            goto(end)
            repeat(5) { const(v(0), 0) }
            bind(end)
            returnVoid()
        }, reach = 6),

        // The conditional widens by 3 units, pushing the backward goto from -3 to -6, past a reach of 4.
        CASCADE(
            units(
                0x0012,
                0x0039,
                0x0005,
                0x002A,
                0x000A,
                0x0000,
                0x002A,
                0xFFFA,
                0xFFFF,
                0x0012,
                0x0012,
                0x0012,
                0x0012,
                0x000E,
            ),
            {
                val top = label()
                val end = label()
                bind(top)
                const(v(0), 0)
                branchZero(Cond.Eq, v(0), end)
                goto(top)
                repeat(4) { const(v(0), 0) }
                bind(end)
                returnVoid()
            },
            reach = 4,
        ),
        SWITCH_PAYLOAD(
            units(
                0x022B,
                0x0006,
                0x0000,
                0x000E,
                0x000E,
                0x000E,
                0x0100,
                0x0003,
                0xFFFF,
                0xFFFF,
                0x0004,
                0x0000,
                0x0005,
                0x0000,
                0x0004,
                0x0000,
            ),
            {
                val cases = labels(2)
                val table = labelTable(-1, 3, cases[0])
                table[1] = cases[1]
                packedSwitch(v(2), table)
                returnVoid()
                bind(cases[0])
                returnVoid()
                bind(cases[1])
                returnVoid()
            },
        ),
        SWITCH_PAYLOAD_PADDED(
            units(0x0012, 0x002B, 0x0005, 0x0000, 0x000E, 0x0000, 0x0100, 0x0001, 0xFFFF, 0x7FFF, 0x0003, 0x0000),
            {
                val target = label()
                const(v(0), 0)
                packedSwitch(v(0), labelTable(Int.MAX_VALUE, 1, target))
                bind(target)
                returnVoid()
            },
        ),

        // const; inverted if over goto/32; 4 consts; switch at 10; goto/32 at 13; handler at 16; pad; payload at 18.
        RELAXED_SWITCH_AND_TRY(
            units(
                0x0012,
                0x0039,
                0x0005,
                0x002A,
                0x000D,
                0x0000,
                0x0012,
                0x0012,
                0x0012,
                0x0012,
                0x002B,
                0x0008,
                0x0000,
                0x002A,
                0xFFF3,
                0xFFFF,
                0x000E,
                0x0000,
                0x0100,
                0x0001,
                0x0000,
                0x0000,
                0xFFF6,
                0xFFFF,
            ),
            RELAXED_BODY,
            reach = 3,
        ),
    }

    @Test
    fun `branches take their exact or relaxed form`(
        case: Branch,
    ) {
        val insns = emit(dexter = reaching(case.reach), body = case.body).insns

        assertEquals(hex(case.expected), hex(insns))
    }

    enum class Condition(
        val cond: Cond,
        val offset: Int,
    ) {
        EQ(Cond.Eq, 0),
        NE(Cond.Ne, 1),
        LT(Cond.Lt, 2),
        GE(Cond.Ge, 3),
        GT(Cond.Gt, 4),
        LE(Cond.Le, 5),
    }

    @Test
    fun `branch uses its condition's if-test opcode`(
        case: Condition,
    ) {
        val insns = emit {
            val end = label()
            branch(case.cond, v(1), v(2), end)
            bind(end)
            returnVoid()
        }.insns

        assertEquals(hex(units(0x2132 + case.offset, 0x0002, 0x000E)), hex(insns))
    }

    @Test
    fun `branch zero uses its condition's if-testz opcode`(
        case: Condition,
    ) {
        val insns = emit {
            val end = label()
            branchZero(case.cond, v(3), end)
            bind(end)
            returnVoid()
        }.insns

        assertEquals(hex(units(0x0338 + case.offset, 0x0002, 0x000E)), hex(insns))
    }

    @Test
    fun `relaxation moves try ranges and handlers`() {
        val tries = emit(dexter = reaching(3), body = RELAXED_BODY).code.tries

        assertEquals(listOf(Triple(6, 7, listOf("Ljava/lang/Throwable;" to 16))), tries.map { Triple(it.start, it.count, it.handlers) })
    }

    @Test
    fun `long try ranges split and share one handler`() {
        val code = DexReader(Samples.big()).method(Samples.LOOP.toString())

        val handler = listOf("Ljava/lang/StackOverflowError;" to 0x1117F)
        assertEquals(listOf(Triple(6, 0xFFFF, handler), Triple(0x10005, 0x1171, handler)), code.tries.map { Triple(it.start, it.count, it.handlers) })
    }

    @Test
    fun `long try ranges split on instruction boundaries`() {
        val code = emit {
            val handler = handlerLabel()
            tryCatch(THROWABLE, handler) {
                repeat(32768) { const(v(0), 1000) }
            }
            bindHandler(handler)
            returnVoid()
        }.code

        assertEquals(listOf(0 to 0xFFFE, 0xFFFE to 2), code.tries.map { it.start to it.count })
    }

    @Test
    fun `too many distinct handlers for 16-bit handler offsets are rejected`() {
        assertFailsWith<DexterMethodTooLargeException> {
            emit {
                val handlers = List(20_000) { handlerLabel() }
                for (handler in handlers) tryCatch(THROWABLE, handler) { const(v(0), 0) }
                for (handler in handlers) {
                    bindHandler(handler)
                    returnVoid()
                }
            }
        }
    }

    enum class Rejection(
        val body: CodeEmitter.() -> Unit,
    ) {
        IF_REGISTER_ABOVE_15({ branch(Cond.Eq, v(16), v(1), label()) }),
        IF_ZERO_REGISTER_ABOVE_255({ branchZero(Cond.Eq, v(256), label()) }),
        SWITCH_REGISTER_ABOVE_255({ packedSwitch(v(256), labelTable(0, 1, label())) }),
        SWITCH_KEYS_OVERFLOW({ labelTable(Int.MAX_VALUE, 2, label()) }),
        EMPTY_TABLE({ labelTable(0, 0, label()) }),
        TABLE_TOO_LARGE({ labelTable(0, 65536, label()) }),
        NEGATIVE_LABEL_COUNT({ labels(-1) }),
    }

    @Test
    fun `rejects operands no encoding holds`(
        case: Rejection,
    ) {
        assertFailsWith<IllegalArgumentException> { emit(body = case.body) }
    }

    enum class Misuse(
        val body: CodeEmitter.() -> Unit,
    ) {
        BOUND_TWICE({
            val label = label()
            bind(label)
            bind(label)
        }),
        NEVER_BOUND({ goto(label()) }),
        BOUND_AFTER_LAST_INSTRUCTION({
            val label = label()
            goto(label)
            bind(label)
        }),
        SWITCH_TARGET_NEVER_BOUND({
            packedSwitch(v(0), labelTable(0, 1, label()))
            returnVoid()
        }),
        HANDLER_NEVER_BOUND({ tryCatch(THROWABLE, handlerLabel()) { returnVoid() } }),
    }

    @Test
    fun `rejects labels and tries used out of order`(
        case: Misuse,
    ) {
        assertFailsWith<IllegalStateException> { emit(body = case.body) }
    }

    @Test
    fun `a label index outside its block is rejected`() {
        assertFailsWith<IndexOutOfBoundsException> { emit { labels(2)[2] } }
    }

    @Test
    fun `a label from another method is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Dexter().assemble {
                val builder = addClass(T, DexType.Object)
                var escaped: Label? = null
                builder.staticMethod(DexMethod(T, "a", DexType.Void), MemberAccess.Public, 1) {
                    escaped = label()
                    returnVoid()
                }
                builder.staticMethod(DexMethod(T, "b", DexType.Void), MemberAccess.Public, 1) {
                    goto(escaped!!)
                }
            }
        }
    }

    @Test
    fun `a label from another workspace is rejected`() {
        var escaped: Label? = null
        emit {
            escaped = label()
            returnVoid()
        }

        assertFailsWith<IllegalArgumentException> {
            emit {
                bind(label())
                goto(escaped!!)
            }
        }
    }
}

private val RELAXED_BODY: CodeEmitter.() -> Unit = {
    val top = label()
    val exit = label()
    val handler = handlerLabel()
    val table = labelTable(0, 1, top)
    bind(top)
    const(v(0), 0)
    branchZero(Cond.Eq, v(0), exit)
    tryCatch(THROWABLE, handler) {
        repeat(4) { const(v(0), 0) }
        packedSwitch(v(0), table)
    }
    goto(top)
    bind(exit)
    bindHandler(handler)
    returnVoid()
}
