package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Burst
class ReferenceTest {

    enum class StaticField(
        val type: ValueType,
        val get: Int,
    ) {
        INT(DexType.Int, 0x60),
        FLOAT(DexType.Float, 0x60),
        LONG(DexType.Long, 0x61),
        DOUBLE(DexType.Double, 0x61),
        OBJECT(DexType.Object, 0x62),
        ARRAY(DexType.Int.array(), 0x62),
        BOOLEAN(DexType.Boolean, 0x63),
        BYTE(DexType.Byte, 0x64),
        CHAR(DexType.Char, 0x65),
        SHORT(DexType.Short, 0x66),
    }

    @Test
    fun `static field access follows the field type`(
        case: StaticField,
    ) {
        val field = DexField(OTHER, "f", case.type)
        val emitted = emit {
            getStatic(v(2), field)
            putStatic(v(4), field)
        }

        val index = emitted.field(field)
        assertEquals(hex(units(0x0200 or case.get, index, 0x0400 or (case.get + 7), index)), hex(emitted.insns))
    }

    enum class InstanceField(
        val type: ValueType,
        val get: Int,
    ) {
        INT(DexType.Int, 0x52),
        FLOAT(DexType.Float, 0x52),
        LONG(DexType.Long, 0x53),
        DOUBLE(DexType.Double, 0x53),
        OBJECT(DexType.Object, 0x54),
        ARRAY(DexType.Int.array(), 0x54),
        BOOLEAN(DexType.Boolean, 0x55),
        BYTE(DexType.Byte, 0x56),
        CHAR(DexType.Char, 0x57),
        SHORT(DexType.Short, 0x58),
    }

    @Test
    fun `instance field access follows the field type`(
        case: InstanceField,
    ) {
        val field = DexField(OTHER, "f", case.type)
        val emitted = emit {
            getField(v(2), v(9), field)
            putField(v(4), v(9), field)
        }

        val index = emitted.field(field)
        assertEquals(hex(units(0x9200 or case.get, index, 0x9400 or (case.get + 7), index)), hex(emitted.insns))
    }

    @Test
    fun `array length packs both registers`() {
        val emitted = emit { arrayLength(v(3), v(12)) }

        assertEquals(hex(units(0xC321)), hex(emitted.insns))
    }

    @Test
    fun `monitors hold an 8-bit register`() {
        val emitted = emit {
            monitorEnter(v(200))
            monitorExit(v(200))
        }

        assertEquals(hex(units(0xC81D, 0xC81E)), hex(emitted.insns))
    }

    @Test
    fun `instance of packs both registers and references its type`() {
        val emitted = emit { instanceOf(v(3), v(12), OTHER) }

        assertEquals(hex(units(0xC320, emitted.type(OTHER))), hex(emitted.insns))
    }

    @Test
    fun `const class references its type`() {
        val emitted = emit { constClass(v(200), DexType.Int.array()) }

        assertEquals(hex(units(0xC81C, emitted.type("[I"))), hex(emitted.insns))
    }

    enum class StringValue(
        val value: String,
    ) {
        ASCII("hello"),
        EMPTY(""),
        NUL("a\u0000b"),
        TWO_BYTE("\u00e9t\u00e9"),
        SUPPLEMENTARY("\uD83D\uDE00"),
        UNPAIRED_SURROGATE("\uD800"),
    }

    @Test
    fun `const string references its string`(
        case: StringValue,
    ) {
        val emitted = emit { constString(v(200), case.value) }

        assertEquals(hex(units(0xC81A, emitted.string(case.value))), hex(emitted.insns))
    }

    @Test
    fun `const string shares one entry per value`() {
        val emitted = emit {
            constString(v(0), "x")
            constString(v(1), "x")
        }

        val index = emitted.string("x")
        assertEquals(hex(units(0x001A, index, 0x011A, index)), hex(emitted.insns))
    }

    @Test
    fun `new instance references its class`() {
        val emitted = emit { newInstance(v(200), OTHER) }

        assertEquals(hex(units(0xC822, emitted.type(OTHER))), hex(emitted.insns))
    }

    @Test
    fun `check cast references its type`() {
        val emitted = emit { checkCast(v(200), OTHER) }

        assertEquals(hex(units(0xC81F, emitted.type(OTHER))), hex(emitted.insns))
    }

    @Test
    fun `check cast accepts array types`() {
        val emitted = emit { checkCast(v(1), DexType.Long.array()) }

        assertEquals(hex(units(0x011F, emitted.type("[J"))), hex(emitted.insns))
    }

    @Test
    fun `new array references its type`() {
        val emitted = emit { newArray(v(1), v(15), DexType.Object.array()) }

        assertEquals(hex(units(0xF123, emitted.type("[Ljava/lang/Object;"))), hex(emitted.insns))
    }

    enum class Rejection(
        val body: CodeEmitter.() -> Unit,
    ) {
        STATIC_FIELD_REGISTER_ABOVE_255({ getStatic(v(256), DexField(OTHER, "f", DexType.Int)) }),
        STATIC_FIELD_WIDE_PAIR_OUTSIDE_METHOD({ getStatic(v(299), DexField(OTHER, "f", DexType.Long)) }),
        CHECK_CAST_REGISTER_ABOVE_255({ checkCast(v(256), OTHER) }),
        INSTANCE_FIELD_VALUE_REGISTER_ABOVE_15({ getField(v(16), v(0), DexField(OTHER, "f", DexType.Int)) }),
        INSTANCE_FIELD_OBJECT_REGISTER_ABOVE_15({ putField(v(0), v(16), DexField(OTHER, "f", DexType.Int)) }),
        INSTANCE_FIELD_WIDE_PAIR_OUTSIDE_METHOD({ getField(v(299), v(0), DexField(OTHER, "f", DexType.Long)) }),
        NEW_ARRAY_REGISTER_ABOVE_15({ newArray(v(0), v(16), DexType.Int.array()) }),
        ARRAY_LENGTH_REGISTER_ABOVE_15({ arrayLength(v(0), v(16)) }),
        INSTANCE_OF_DESTINATION_ABOVE_15({ instanceOf(v(16), v(0), OTHER) }),
        INSTANCE_OF_REFERENCE_ABOVE_15({ instanceOf(v(0), v(16), OTHER) }),
        CONST_CLASS_REGISTER_ABOVE_255({ constClass(v(256), OTHER) }),
        CONST_STRING_REGISTER_ABOVE_255({ constString(v(256), "x") }),
        NEW_INSTANCE_REGISTER_ABOVE_255({ newInstance(v(256), OTHER) }),
        MONITOR_REGISTER_ABOVE_255({ monitorEnter(v(256)) }),
    }

    @Test
    fun `rejects registers no encoding holds`(
        case: Rejection,
    ) {
        assertFailsWith<IllegalArgumentException> { emit(body = case.body) }
    }
}
