package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Burst
class EncodingTest {

    enum class Encoding(
        val expected: IntArray,
        val body: CodeEmitter.() -> Unit,
        val registers: Int = 300,
        val returns: DexType = DexType.Void,
    ) {
        CONST_4_MAX(units(0x7012), { const(v(0), 7) }),
        CONST_4_MIN(units(0x8F12), { const(v(15), -8) }),
        CONST_16_ABOVE_NIBBLE(units(0x0013, 0x0008), { const(v(0), 8) }),
        CONST_16_BELOW_NIBBLE(units(0x0013, 0xFFF7), { const(v(0), -9) }),
        CONST_16_HIGH_REGISTER(units(0x1013, 0x0001), { const(v(16), 1) }),
        CONST_16_MIN(units(0xFF13, 0x8000), { const(v(255), -32768) }),
        CONST_16_MAX(units(0x0013, 0x7FFF), { const(v(0), 32767) }),
        CONST_HIGH16(units(0x0015, 0x0001), { const(v(0), 0x10000) }),
        CONST_HIGH16_MIN(units(0x0015, 0x8000), { const(v(0), Int.MIN_VALUE) }),
        CONST_HIGH16_NEGATIVE(units(0x0015, 0xFFFF), { const(v(0), -65536) }),
        CONST_32_ABOVE_SHORT(units(0x0014, 0x8000, 0x0000), { const(v(0), 32768) }),
        CONST_32(units(0x0014, 0xCD15, 0x075B), { const(v(0), 123456789) }),
        CONST_32_MAX(units(0x0014, 0xFFFF, 0x7FFF), { const(v(0), Int.MAX_VALUE) }),

        CONST_WIDE_16_ZERO(units(0x0016, 0x0000), { constWide(v(0), 0) }),
        CONST_WIDE_16_MIN(units(0x0016, 0x8000), { constWide(v(0), -32768) }),
        CONST_WIDE_HIGH16(units(0x0019, 0x0001), { constWide(v(0), 1L shl 48) }),
        CONST_WIDE_HIGH16_MIN(units(0x0019, 0x8000), { constWide(v(0), Long.MIN_VALUE) }),
        CONST_WIDE_32_ABOVE_SHORT(units(0x0017, 0x8000, 0x0000), { constWide(v(0), 32768) }),
        CONST_WIDE_32_MIN(units(0x0017, 0x0000, 0x8000), { constWide(v(0), Int.MIN_VALUE.toLong()) }),
        CONST_WIDE_64(units(0x0018, 0x6789, 0x2345, 0x0001, 0x0000), { constWide(v(0), 0x123456789L) }),
        CONST_WIDE_64_MAX(units(0xFE18, 0xFFFF, 0xFFFF, 0xFFFF, 0x7FFF), { constWide(v(254), Long.MAX_VALUE) }),

        MOVE(units(0x2101), { move(v(1), v(2)) }),
        MOVE_NIBBLE_MAX(units(0xFF01), { move(v(15), v(15)) }),
        MOVE_FROM16(units(0x1002, 0x0001), { move(v(16), v(1)) }),
        MOVE_FROM16_HIGH_SOURCE(units(0x0102, 0x0010), { move(v(1), v(16)) }),
        MOVE_FROM16_MAX(units(0xFF02, 0x012B), { move(v(255), v(299)) }),
        MOVE_16(units(0x0003, 0x0100, 0x0001), { move(v(256), v(1)) }),
        MOVE_16_MAX(units(0x0003, 0xFFFE, 0xFFFD), { move(v(65534), v(65533)) }, registers = 65535),
        MOVE_WIDE(units(0x2004), { moveWide(v(0), v(2)) }),
        MOVE_WIDE_FROM16(units(0x1005, 0x0002), { moveWide(v(16), v(2)) }),
        MOVE_WIDE_16(units(0x0006, 0x0100, 0x0002), { moveWide(v(256), v(2)) }),
        MOVE_OBJECT(units(0x2107), { moveObject(v(1), v(2)) }),
        MOVE_OBJECT_FROM16(units(0x1008, 0x0002), { moveObject(v(16), v(2)) }),
        MOVE_OBJECT_16(units(0x0009, 0x0100, 0x0002), { moveObject(v(256), v(2)) }),

        BINARY_2ADDR(units(0x21B0), { binary(BinaryOp.AddInt, v(1), v(1), v(2)) }),
        BINARY(units(0x0190, 0x0302), { binary(BinaryOp.AddInt, v(1), v(2), v(3)) }),
        BINARY_HIGH_DESTINATION(units(0x1090, 0x0110), { binary(BinaryOp.AddInt, v(16), v(16), v(1)) }),
        BINARY_HIGH_SOURCE(units(0x0190, 0x1001), { binary(BinaryOp.AddInt, v(1), v(1), v(16)) }),
        BINARY_MAX(units(0xFF9A, 0xFFFF), { binary(BinaryOp.UshrInt, v(255), v(255), v(255)) }),
        BINARY_LONG_2ADDR(units(0x20BB), { binary(BinaryOp.AddLong, v(0), v(0), v(2)) }),
        SHIFT_LONG_2ADDR(units(0x20C3), { binary(BinaryOp.ShlLong, v(0), v(0), v(2)) }),
        SHIFT_LONG(units(0x00A3, 0x0402), { binary(BinaryOp.ShlLong, v(0), v(2), v(4)) }),
        SHIFT_LONG_SINGLE_DISTANCE(units(0x30C3), { binary(BinaryOp.ShlLong, v(0), v(0), v(3)) }, registers = 4),
        BINARY_FLOAT_2ADDR(units(0x21C6), { binary(BinaryOp.AddFloat, v(1), v(1), v(2)) }),
        BINARY_DOUBLE(units(0x00AF, 0x0402), { binary(BinaryOp.RemDouble, v(0), v(2), v(4)) }),

        LITERAL_16(units(0x21D0, 0x0005), { binary(LiteralOp.AddInt, v(1), v(2), 5) }),
        LITERAL_16_MAX(units(0x21D0, 0x7FFF), { binary(LiteralOp.AddInt, v(1), v(2), 32767) }),
        LITERAL_16_MIN(units(0x21D0, 0x8000), { binary(LiteralOp.AddInt, v(1), v(2), -32768) }),
        LITERAL_8(units(0x10D8, 0x0502), { binary(LiteralOp.AddInt, v(16), v(2), 5) }),
        LITERAL_8_MAX(units(0xFFD8, 0x7FFF), { binary(LiteralOp.AddInt, v(255), v(255), 127) }),
        LITERAL_8_MIN(units(0x10D8, 0x8002), { binary(LiteralOp.AddInt, v(16), v(2), -128) }),
        SUB_LITERAL_16(units(0x21D0, 0xFFFB), { binary(LiteralOp.SubInt, v(1), v(2), 5) }),
        SUB_LITERAL_16_NEGATES_TO_MIN(units(0x21D0, 0x8000), { binary(LiteralOp.SubInt, v(1), v(2), 32768) }),
        SUB_LITERAL_8(units(0x10D8, 0x8002), { binary(LiteralOp.SubInt, v(16), v(2), 128) }),
        SHL_LITERAL(units(0x01E0, 0x0302), { binary(LiteralOp.ShlInt, v(1), v(2), 3) }),
        SHR_LITERAL(units(0x01E1, 0x1F02), { binary(LiteralOp.ShrInt, v(1), v(2), 31) }),
        USHR_LITERAL(units(0x01E2, 0xFF02), { binary(LiteralOp.UshrInt, v(1), v(2), -1) }),

        COMPARE_MAX(units(0xFF31, 0xFDFB), { compare(CompareOp.CmpLong, v(255), v(251), v(253)) }),
        ARRAY_GET_WIDE_MAX(units(0xFE45, 0xFDFC), { arrayGet(ArrayKind.Wide, v(254), v(252), v(253)) }),

        RETURN_VOID(units(0x000E), { returnVoid() }),
        RETURN_INT(units(0xFF0F), { returnValue(v(255)) }, returns = DexType.Int),
        RETURN_WIDE(units(0x0010), { returnValue(v(0)) }, returns = DexType.Double),
        RETURN_OBJECT(units(0x0311), { returnValue(v(3)) }, returns = DexType.String),
        THROW(units(0x0527), { throwValue(v(5)) }),
    }

    @Test
    fun `picks the smallest encoding`(
        case: Encoding,
    ) {
        val insns = emit(registers = case.registers, returns = case.returns, body = case.body).insns

        assertEquals(hex(case.expected), hex(insns))
    }

    enum class Rejection(
        val body: CodeEmitter.() -> Unit,
        val registers: Int = 300,
        val returns: DexType = DexType.Void,
    ) {
        CONST_REGISTER_ABOVE_255({ const(v(256), 0) }),
        CONST_REGISTER_OUTSIDE_METHOD({ const(v(5), 0) }, registers = 5),
        CONST_NEGATIVE_REGISTER({ const(v(-1), 0) }),
        CONST_WIDE_NEGATIVE_REGISTER({ constWide(v(-1), 0) }),
        CONST_WIDE_REGISTER_ABOVE_255({ constWide(v(256), 0) }),
        CONST_WIDE_PAIR_OUTSIDE_METHOD({ constWide(v(4), 0) }, registers = 5),
        MOVE_WIDE_PAIR_OUTSIDE_METHOD({ moveWide(v(0), v(3)) }, registers = 4),
        MOVE_OUTSIDE_METHOD({ move(v(4), v(0)) }, registers = 4),
        BINARY_REGISTER_ABOVE_255({ binary(BinaryOp.AddInt, v(1), v(2), v(256)) }),
        BINARY_WIDE_PAIR_OUTSIDE_METHOD({ binary(BinaryOp.AddLong, v(0), v(0), v(3)) }, registers = 4),
        LITERAL_ABOVE_LIT16({ binary(LiteralOp.AddInt, v(1), v(2), 32768) }),
        LITERAL_ABOVE_LIT8({ binary(LiteralOp.AddInt, v(16), v(2), 128) }),
        LITERAL_BELOW_LIT8({ binary(LiteralOp.AddInt, v(16), v(2), -129) }),
        LITERAL_REGISTER_ABOVE_255({ binary(LiteralOp.AddInt, v(256), v(2), 1) }),
        SHIFT_LITERAL_ABOVE_LIT8({ binary(LiteralOp.ShlInt, v(1), v(2), 128) }),
        SUB_LITERAL_NEGATION_ABOVE_LIT16({ binary(LiteralOp.SubInt, v(1), v(2), -32768) }),
        SUB_LITERAL_MIN({ binary(LiteralOp.SubInt, v(1), v(2), Int.MIN_VALUE) }),
        UNARY_REGISTER_ABOVE_15({ unary(UnaryOp.NegInt, v(16), v(1)) }),
        CONVERT_REGISTER_ABOVE_15({ convert(Conversion.IntToLong, v(1), v(16)) }),
        CONVERT_WIDE_DESTINATION_OUTSIDE_METHOD({ convert(Conversion.IntToLong, v(3), v(0)) }, registers = 4),
        CONVERT_WIDE_SOURCE_OUTSIDE_METHOD({ convert(Conversion.LongToInt, v(0), v(3)) }, registers = 4),
        COMPARE_REGISTER_ABOVE_255({ compare(CompareOp.CmpLong, v(0), v(2), v(256)) }),
        COMPARE_WIDE_PAIR_OUTSIDE_METHOD({ compare(CompareOp.CmpLong, v(0), v(2), v(4)) }, registers = 5),
        ARRAY_REGISTER_ABOVE_255({ arrayGet(ArrayKind.Int, v(256), v(2), v(3)) }),
        ARRAY_WIDE_PAIR_OUTSIDE_METHOD({ arrayGet(ArrayKind.Wide, v(2), v(0), v(1)) }, registers = 3),
        THROW_REGISTER_ABOVE_255({ throwValue(v(256)) }),
        RETURN_REGISTER_ABOVE_255({ returnValue(v(256)) }, returns = DexType.Int),
        RETURN_WIDE_PAIR_OUTSIDE_METHOD({ returnValue(v(1)) }, registers = 2, returns = DexType.Long),
    }

    @Test
    fun `rejects operands no encoding holds`(
        case: Rejection,
    ) {
        assertFailsWith<IllegalArgumentException> {
            emit(registers = case.registers, returns = case.returns, body = case.body)
        }
    }

    enum class BinaryOpcode(
        val op: BinaryOp,
        val opcode: Int,
    ) {
        ADD_INT(BinaryOp.AddInt, 0x90),
        SUB_INT(BinaryOp.SubInt, 0x91),
        MUL_INT(BinaryOp.MulInt, 0x92),
        DIV_INT(BinaryOp.DivInt, 0x93),
        REM_INT(BinaryOp.RemInt, 0x94),
        AND_INT(BinaryOp.AndInt, 0x95),
        OR_INT(BinaryOp.OrInt, 0x96),
        XOR_INT(BinaryOp.XorInt, 0x97),
        SHL_INT(BinaryOp.ShlInt, 0x98),
        SHR_INT(BinaryOp.ShrInt, 0x99),
        USHR_INT(BinaryOp.UshrInt, 0x9A),
        ADD_LONG(BinaryOp.AddLong, 0x9B),
        SUB_LONG(BinaryOp.SubLong, 0x9C),
        MUL_LONG(BinaryOp.MulLong, 0x9D),
        DIV_LONG(BinaryOp.DivLong, 0x9E),
        REM_LONG(BinaryOp.RemLong, 0x9F),
        AND_LONG(BinaryOp.AndLong, 0xA0),
        OR_LONG(BinaryOp.OrLong, 0xA1),
        XOR_LONG(BinaryOp.XorLong, 0xA2),
        SHL_LONG(BinaryOp.ShlLong, 0xA3),
        SHR_LONG(BinaryOp.ShrLong, 0xA4),
        USHR_LONG(BinaryOp.UshrLong, 0xA5),
        ADD_FLOAT(BinaryOp.AddFloat, 0xA6),
        SUB_FLOAT(BinaryOp.SubFloat, 0xA7),
        MUL_FLOAT(BinaryOp.MulFloat, 0xA8),
        DIV_FLOAT(BinaryOp.DivFloat, 0xA9),
        REM_FLOAT(BinaryOp.RemFloat, 0xAA),
        ADD_DOUBLE(BinaryOp.AddDouble, 0xAB),
        SUB_DOUBLE(BinaryOp.SubDouble, 0xAC),
        MUL_DOUBLE(BinaryOp.MulDouble, 0xAD),
        DIV_DOUBLE(BinaryOp.DivDouble, 0xAE),
        REM_DOUBLE(BinaryOp.RemDouble, 0xAF),
    }

    @Test
    fun `binary operations use their opcode`(
        case: BinaryOpcode,
    ) {
        val insns = emit { binary(case.op, v(0), v(2), v(4)) }.insns

        assertEquals(hex(units(case.opcode, 0x0402)), hex(insns))
    }

    @Test
    fun `binary operations use their 2addr opcode`(
        case: BinaryOpcode,
    ) {
        val insns = emit { binary(case.op, v(0), v(0), v(4)) }.insns

        assertEquals(hex(units(0x4000 or (case.opcode + 0x20))), hex(insns))
    }

    enum class LiteralOpcode(
        val op: LiteralOp,
        val lit16: Int?,
        val lit8: Int,
    ) {
        ADD_INT(LiteralOp.AddInt, 0xD0, 0xD8),
        MUL_INT(LiteralOp.MulInt, 0xD2, 0xDA),
        DIV_INT(LiteralOp.DivInt, 0xD3, 0xDB),
        REM_INT(LiteralOp.RemInt, 0xD4, 0xDC),
        AND_INT(LiteralOp.AndInt, 0xD5, 0xDD),
        OR_INT(LiteralOp.OrInt, 0xD6, 0xDE),
        XOR_INT(LiteralOp.XorInt, 0xD7, 0xDF),
        SHL_INT(LiteralOp.ShlInt, null, 0xE0),
        SHR_INT(LiteralOp.ShrInt, null, 0xE1),
        USHR_INT(LiteralOp.UshrInt, null, 0xE2),
    }

    @Test
    fun `literal operations prefer lit16 for low registers`(
        case: LiteralOpcode,
    ) {
        val insns = emit { binary(case.op, v(1), v(2), 9) }.insns

        val expected = if (case.lit16 == null) units(0x0100 or case.lit8, 0x0902) else units(0x2100 or case.lit16, 0x0009)
        assertEquals(hex(expected), hex(insns))
    }

    @Test
    fun `literal operations use lit8 for high registers`(
        case: LiteralOpcode,
    ) {
        val insns = emit { binary(case.op, v(16), v(2), 9) }.insns

        assertEquals(hex(units(0x1000 or case.lit8, 0x0902)), hex(insns))
    }

    enum class TwelveOpcode(
        val body: CodeEmitter.() -> Unit,
        val opcode: Int,
    ) {
        NEG_INT({ unary(UnaryOp.NegInt, v(0), v(2)) }, 0x7B),
        NOT_INT({ unary(UnaryOp.NotInt, v(0), v(2)) }, 0x7C),
        NEG_LONG({ unary(UnaryOp.NegLong, v(0), v(2)) }, 0x7D),
        NOT_LONG({ unary(UnaryOp.NotLong, v(0), v(2)) }, 0x7E),
        NEG_FLOAT({ unary(UnaryOp.NegFloat, v(0), v(2)) }, 0x7F),
        NEG_DOUBLE({ unary(UnaryOp.NegDouble, v(0), v(2)) }, 0x80),
        INT_TO_LONG({ convert(Conversion.IntToLong, v(0), v(2)) }, 0x81),
        INT_TO_FLOAT({ convert(Conversion.IntToFloat, v(0), v(2)) }, 0x82),
        INT_TO_DOUBLE({ convert(Conversion.IntToDouble, v(0), v(2)) }, 0x83),
        LONG_TO_INT({ convert(Conversion.LongToInt, v(0), v(2)) }, 0x84),
        LONG_TO_FLOAT({ convert(Conversion.LongToFloat, v(0), v(2)) }, 0x85),
        LONG_TO_DOUBLE({ convert(Conversion.LongToDouble, v(0), v(2)) }, 0x86),
        FLOAT_TO_INT({ convert(Conversion.FloatToInt, v(0), v(2)) }, 0x87),
        FLOAT_TO_LONG({ convert(Conversion.FloatToLong, v(0), v(2)) }, 0x88),
        FLOAT_TO_DOUBLE({ convert(Conversion.FloatToDouble, v(0), v(2)) }, 0x89),
        DOUBLE_TO_INT({ convert(Conversion.DoubleToInt, v(0), v(2)) }, 0x8A),
        DOUBLE_TO_LONG({ convert(Conversion.DoubleToLong, v(0), v(2)) }, 0x8B),
        DOUBLE_TO_FLOAT({ convert(Conversion.DoubleToFloat, v(0), v(2)) }, 0x8C),
        INT_TO_BYTE({ convert(Conversion.IntToByte, v(0), v(2)) }, 0x8D),
        INT_TO_CHAR({ convert(Conversion.IntToChar, v(0), v(2)) }, 0x8E),
        INT_TO_SHORT({ convert(Conversion.IntToShort, v(0), v(2)) }, 0x8F),
    }

    @Test
    fun `unary operations and conversions use their opcode`(
        case: TwelveOpcode,
    ) {
        val insns = emit(body = case.body).insns

        assertEquals(hex(units(0x2000 or case.opcode)), hex(insns))
    }

    enum class CompareOpcode(
        val op: CompareOp,
        val opcode: Int,
    ) {
        CMPL_FLOAT(CompareOp.CmplFloat, 0x2D),
        CMPG_FLOAT(CompareOp.CmpgFloat, 0x2E),
        CMPL_DOUBLE(CompareOp.CmplDouble, 0x2F),
        CMPG_DOUBLE(CompareOp.CmpgDouble, 0x30),
        CMP_LONG(CompareOp.CmpLong, 0x31),
    }

    @Test
    fun `comparisons use their opcode`(
        case: CompareOpcode,
    ) {
        val insns = emit { compare(case.op, v(0), v(2), v(4)) }.insns

        assertEquals(hex(units(case.opcode, 0x0402)), hex(insns))
    }

    enum class ArrayOpcode(
        val kind: ArrayKind,
        val get: Int,
    ) {
        INT(ArrayKind.Int, 0x44),
        WIDE(ArrayKind.Wide, 0x45),
        OBJECT(ArrayKind.Object, 0x46),
        BOOLEAN(ArrayKind.Boolean, 0x47),
        BYTE(ArrayKind.Byte, 0x48),
        CHAR(ArrayKind.Char, 0x49),
        SHORT(ArrayKind.Short, 0x4A),
    }

    @Test
    fun `array gets use their opcode`(
        case: ArrayOpcode,
    ) {
        val insns = emit { arrayGet(case.kind, v(0), v(2), v(3)) }.insns

        assertEquals(hex(units(case.get, 0x0302)), hex(insns))
    }

    @Test
    fun `array puts use their opcode`(
        case: ArrayOpcode,
    ) {
        val insns = emit { arrayPut(case.kind, v(0), v(2), v(3)) }.insns

        assertEquals(hex(units(case.get + 7, 0x0302)), hex(insns))
    }
}
