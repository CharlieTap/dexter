package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val NONE = DexMethod(OTHER, "none", DexType.Void)
private val ONE = DexMethod(OTHER, "one", DexType.Void, DexType.Int)
private val TWO = DexMethod(OTHER, "two", DexType.Void, DexType.Int, DexType.Int)
private val THREE = DexMethod(OTHER, "three", DexType.Void, DexType.Int, DexType.Int, DexType.Int)
private val FOUR = DexMethod(OTHER, "four", DexType.Void, DexType.Int, DexType.Int, DexType.Int, DexType.Int)
private val FIVE = DexMethod(OTHER, "five", DexType.Void, DexType.Int, DexType.Int, DexType.Int, DexType.Int, DexType.Int)
private val MIXED = DexMethod(OTHER, "mixed", DexType.Void, DexType.Int, DexType.Long, DexType.Int)
private val TWO_LONGS = DexMethod(OTHER, "twoLongs", DexType.Void, DexType.Long, DexType.Long)
private val THREE_LONGS = DexMethod(OTHER, "threeLongs", DexType.Void, DexType.Long, DexType.Long, DexType.Long)
private val INT_LONG = DexMethod(OTHER, "intLong", DexType.Void, DexType.Int, DexType.Long)
private val TEN_LONGS = DexMethod(OTHER, "tenLongs", DexType.Void, List(10) { DexType.Long })
private val CTOR = DexConstructor(OTHER)
private val CTOR_INT = DexConstructor(OTHER, DexType.Int)

@Burst
class InvokeTest {

    enum class Invoke(
        val callee: Any,
        val expected: (Int) -> IntArray,
        val outs: Int,
        val body: CodeEmitter.() -> Unit,
    ) {
        STATIC_0(NONE, { units(0x0071, it, 0x0000) }, 0, { invokeStatic(NONE) }),
        STATIC_1(ONE, { units(0x1071, it, 0x000F) }, 1, { invokeStatic(ONE, v(15)) }),
        STATIC_2(TWO, { units(0x2071, it, 0x0021) }, 2, { invokeStatic(TWO, v(1), v(2)) }),
        STATIC_3(THREE, { units(0x3071, it, 0x0321) }, 3, { invokeStatic(THREE, v(1), v(2), v(3)) }),
        STATIC_4(FOUR, { units(0x4071, it, 0x4321) }, 4, { invokeStatic(FOUR, v(1), v(2), v(3), v(4)) }),
        STATIC_5(FIVE, { units(0x5971, it, 0x1357) }, 5, { invokeStatic(FIVE, v(7), v(5), v(3), v(1), v(9)) }),
        STATIC_WIDE_ARGUMENT(MIXED, { units(0x4071, it, 0x4321) }, 4, { invokeStatic(MIXED, v(1), v(2), v(4)) }),
        VIRTUAL_0(NONE, { units(0x106E, it, 0x0003) }, 1, { invokeVirtual(NONE, v(3)) }),
        VIRTUAL_1(ONE, { units(0x206E, it, 0x0010) }, 2, { invokeVirtual(ONE, v(0), v(1)) }),
        VIRTUAL_2(TWO, { units(0x306E, it, 0x0210) }, 3, { invokeVirtual(TWO, v(0), v(1), v(2)) }),
        VIRTUAL_3(THREE, { units(0x406E, it, 0x3210) }, 4, { invokeVirtual(THREE, v(0), v(1), v(2), v(3)) }),
        VIRTUAL_4(FOUR, { units(0x546E, it, 0x3210) }, 5, { invokeVirtual(FOUR, v(0), v(1), v(2), v(3), v(4)) }),
        VIRTUAL_WIDE_ARGUMENTS(TWO_LONGS, { units(0x546E, it, 0x3210) }, 5, { invokeVirtual(TWO_LONGS, v(0), v(1), v(3)) }),
        DIRECT(NONE, { units(0x1070, it, 0x0003) }, 1, { invokeDirect(NONE, v(3)) }),
        INTERFACE(NONE, { units(0x1072, it, 0x0003) }, 1, { invokeInterface(NONE, v(3)) }),
        VIRTUAL_SIX_WORDS(FIVE, { units(0x0674, it, 0x0000) }, 6, { invokeVirtual(FIVE, v(0), v(1), v(2), v(3), v(4), v(5)) }),
        DIRECT_SIX_WORDS(FIVE, { units(0x0676, it, 0x0000) }, 6, { invokeDirect(FIVE, v(0), v(1), v(2), v(3), v(4), v(5)) }),
        INTERFACE_SIX_WORDS(FIVE, { units(0x0678, it, 0x0000) }, 6, { invokeInterface(FIVE, v(0), v(1), v(2), v(3), v(4), v(5)) }),
        STATIC_HIGH_CONSECUTIVE(INT_LONG, { units(0x0377, it, 0x0010) }, 3, { invokeStatic(INT_LONG, v(16), v(17)) }),
        VIRTUAL_HIGH_CONSECUTIVE(
            THREE_LONGS,
            { units(0x0774, it, 0x0100) },
            7,
            { invokeVirtual(THREE_LONGS, v(256), v(257), v(259), v(261)) },
        ),
        STATIC_RANGE_EMPTY(NONE, { units(0x0077, it, 0x012B) }, 0, { invokeStaticRange(NONE, v(299)) }),
        VIRTUAL_RANGE(NONE, { units(0x0174, it, 0x0014) }, 1, { invokeVirtualRange(NONE, v(20)) }),
        DIRECT_RANGE(NONE, { units(0x0176, it, 0x0014) }, 1, { invokeDirectRange(NONE, v(20)) }),
        INTERFACE_RANGE(NONE, { units(0x0178, it, 0x0014) }, 1, { invokeInterfaceRange(NONE, v(20)) }),
        CONSTRUCTOR(CTOR, { units(0x1070, it, 0x0003) }, 1, { invokeConstructor(CTOR, v(3)) }),
        CONSTRUCTOR_WITH_ARGUMENT(CTOR_INT, { units(0x2070, it, 0x0043) }, 2, { invokeConstructor(CTOR_INT, v(3), v(4)) }),
        CONSTRUCTOR_RANGE(CTOR_INT, { units(0x0276, it, 0x0014) }, 2, { invokeConstructorRange(CTOR_INT, v(20)) }),
        STATIC_RANGE_EMPTY_AT_LAST_REGISTER(NONE, { units(0x0077, it, 0x012C) }, 0, { invokeStaticRange(NONE, v(300)) }),
        RANGE_OF_WIDE_ARGUMENTS(TEN_LONGS, { units(0x1574, it, 0x0004) }, 21, { invokeVirtualRange(TEN_LONGS, v(4)) }),
    }

    @Test
    fun `invokes pick 35c or 3rc`(
        case: Invoke,
    ) {
        val emitted = emit(body = case.body)

        assertEquals(hex(case.expected(emitted.method(case.callee))), hex(emitted.insns))
    }

    @Test
    fun `outs counts the largest call`(
        case: Invoke,
    ) {
        val outs = emit(body = case.body).code.outs

        assertEquals(case.outs, outs)
    }

    enum class Rejection(
        val body: CodeEmitter.() -> Unit,
        val registers: Int = 300,
    ) {
        TOO_FEW_ARGUMENTS({ invokeStatic(TWO, v(1)) }),
        TOO_MANY_ARGUMENTS({ invokeStatic(TWO, v(1), v(2), v(3)) }),
        RECEIVER_COUNTED_AS_ARGUMENT({ invokeVirtual(TWO, v(0), v(1)) }),
        HIGH_AND_SCATTERED({ invokeStatic(TWO, v(16), v(18)) }),
        WIDE_PAIR_OUTSIDE_METHOD({ invokeStatic(INT_LONG, v(0), v(3)) }, registers = 4),
        STATIC_RANGE_OUTSIDE_METHOD({ invokeStaticRange(INT_LONG, v(2)) }, registers = 4),
        VIRTUAL_RANGE_OUTSIDE_METHOD({ invokeVirtualRange(INT_LONG, v(1)) }, registers = 4),
        RANGE_PAST_LAST_REGISTER({ invokeStaticRange(FIVE, v(296)) }),
        EMPTY_RANGE_PAST_LAST_REGISTER({ invokeStaticRange(NONE, v(301)) }),
        EMPTY_RANGE_NEGATIVE_REGISTER({ invokeStaticRange(NONE, v(-1)) }),
        CONSTRUCTOR_WITHOUT_ITS_ARGUMENT({ invokeConstructor(CTOR_INT, v(3)) }),
    }

    @Test
    fun `rejects arguments no encoding holds`(
        case: Rejection,
    ) {
        assertFailsWith<IllegalArgumentException> { emit(registers = case.registers, body = case.body) }
    }

    enum class Result(
        val returns: DexType,
        val opcode: Int,
    ) {
        INT(DexType.Int, 0x0A),
        FLOAT(DexType.Float, 0x0A),
        LONG(DexType.Long, 0x0B),
        DOUBLE(DexType.Double, 0x0B),
        OBJECT(DexType.Object, 0x0C),
        ARRAY(DexType.Int.array(), 0x0C),
    }

    @Test
    fun `move result follows the callee's return type`(
        case: Result,
    ) {
        val callee = DexMethod(OTHER, "call", case.returns)
        val emitted = emit {
            invokeStatic(callee)
            moveResult(v(254))
        }

        assertEquals(hex(units(0x0071, emitted.method(callee), 0x0000, 0xFE00 or case.opcode)), hex(emitted.insns))
    }

    @Test
    fun `move result follows a range invoke`() {
        val callee = DexMethod(OTHER, "call", DexType.Object, DexType.Int)
        val emitted = emit {
            invokeStaticRange(callee, v(32))
            moveResult(v(3))
        }

        assertEquals(hex(units(0x0177, emitted.method(callee), 0x0020, 0x030C)), hex(emitted.insns))
    }

    enum class MisplacedResult(
        val body: CodeEmitter.() -> Unit,
    ) {
        WITHOUT_INVOKE({ moveResult(v(0)) }),
        AFTER_VOID_INVOKE({
            invokeStatic(NONE)
            moveResult(v(0))
        }),
        AFTER_ANOTHER_INSTRUCTION({
            invokeStatic(RETURNS_INT)
            const(v(0), 1)
            moveResult(v(0))
        }),
        AFTER_A_LABEL({
            invokeStatic(RETURNS_INT)
            bind(label())
            moveResult(v(0))
        }),
        TWICE({
            invokeStatic(RETURNS_INT)
            moveResult(v(0))
            moveResult(v(1))
        }),
        ACROSS_A_TRY_BOUNDARY({
            val handler = handlerLabel()
            tryCatch(THROWABLE, handler) { invokeStatic(RETURNS_INT) }
            moveResult(v(0))
            bindHandler(handler)
            returnVoid()
        }),
    }

    @Test
    fun `move result must directly follow an invoke`(
        case: MisplacedResult,
    ) {
        assertFailsWith<IllegalStateException> { emit(body = case.body) }
    }

    @Test
    fun `move result rejects a register above 255`() {
        assertFailsWith<IllegalArgumentException> {
            emit {
                invokeStatic(RETURNS_INT)
                moveResult(v(256))
            }
        }
    }
}

private val RETURNS_INT = DexMethod(OTHER, "returnsInt", DexType.Int)
