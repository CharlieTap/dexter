package io.github.charlietap.dexter

import kotlin.jvm.JvmInline

// Bits 0-7 hold the opcode, bit 8 a wide first operand, bit 9 a wide second operand.

internal const val WIDE_A = 1 shl 8
internal const val WIDE_B = 1 shl 9

internal fun widthA(
    bits: Int,
): Int = 1 + ((bits ushr 8) and 1)

internal fun widthB(
    bits: Int,
): Int = 1 + ((bits ushr 9) and 1)

@JvmInline
public value class BinaryOp internal constructor(
    internal val bits: Int,
) {

    public companion object {
        private const val LONG = WIDE_A or WIDE_B

        public val AddInt: BinaryOp = BinaryOp(0x90)
        public val SubInt: BinaryOp = BinaryOp(0x91)
        public val MulInt: BinaryOp = BinaryOp(0x92)
        public val DivInt: BinaryOp = BinaryOp(0x93)
        public val RemInt: BinaryOp = BinaryOp(0x94)
        public val AndInt: BinaryOp = BinaryOp(0x95)
        public val OrInt: BinaryOp = BinaryOp(0x96)
        public val XorInt: BinaryOp = BinaryOp(0x97)
        public val ShlInt: BinaryOp = BinaryOp(0x98)
        public val ShrInt: BinaryOp = BinaryOp(0x99)
        public val UshrInt: BinaryOp = BinaryOp(0x9A)

        public val AddLong: BinaryOp = BinaryOp(0x9B or LONG)
        public val SubLong: BinaryOp = BinaryOp(0x9C or LONG)
        public val MulLong: BinaryOp = BinaryOp(0x9D or LONG)
        public val DivLong: BinaryOp = BinaryOp(0x9E or LONG)
        public val RemLong: BinaryOp = BinaryOp(0x9F or LONG)
        public val AndLong: BinaryOp = BinaryOp(0xA0 or LONG)
        public val OrLong: BinaryOp = BinaryOp(0xA1 or LONG)
        public val XorLong: BinaryOp = BinaryOp(0xA2 or LONG)

        /** The shift distance is an `Int` register. */
        public val ShlLong: BinaryOp = BinaryOp(0xA3 or WIDE_A)
        public val ShrLong: BinaryOp = BinaryOp(0xA4 or WIDE_A)
        public val UshrLong: BinaryOp = BinaryOp(0xA5 or WIDE_A)

        public val AddFloat: BinaryOp = BinaryOp(0xA6)
        public val SubFloat: BinaryOp = BinaryOp(0xA7)
        public val MulFloat: BinaryOp = BinaryOp(0xA8)
        public val DivFloat: BinaryOp = BinaryOp(0xA9)
        public val RemFloat: BinaryOp = BinaryOp(0xAA)

        public val AddDouble: BinaryOp = BinaryOp(0xAB or LONG)
        public val SubDouble: BinaryOp = BinaryOp(0xAC or LONG)
        public val MulDouble: BinaryOp = BinaryOp(0xAD or LONG)
        public val DivDouble: BinaryOp = BinaryOp(0xAE or LONG)
        public val RemDouble: BinaryOp = BinaryOp(0xAF or LONG)
    }
}

@JvmInline
public value class LiteralOp internal constructor(
    internal val bits: Int,
) {

    internal val lit16Opcode: Int get() = bits and 0xFF

    internal val lit8Opcode: Int get() = (bits ushr 8) and 0xFF

    internal val negatesLiteral: Boolean get() = bits and NEGATE != 0

    public companion object {
        private const val NEGATE = 1 shl 16

        public val AddInt: LiteralOp = LiteralOp(0xD0 or (0xD8 shl 8))
        public val SubInt: LiteralOp = LiteralOp(0xD0 or (0xD8 shl 8) or NEGATE)
        public val MulInt: LiteralOp = LiteralOp(0xD2 or (0xDA shl 8))
        public val DivInt: LiteralOp = LiteralOp(0xD3 or (0xDB shl 8))
        public val RemInt: LiteralOp = LiteralOp(0xD4 or (0xDC shl 8))
        public val AndInt: LiteralOp = LiteralOp(0xD5 or (0xDD shl 8))
        public val OrInt: LiteralOp = LiteralOp(0xD6 or (0xDE shl 8))
        public val XorInt: LiteralOp = LiteralOp(0xD7 or (0xDF shl 8))
        public val ShlInt: LiteralOp = LiteralOp(0xE0 shl 8)
        public val ShrInt: LiteralOp = LiteralOp(0xE1 shl 8)
        public val UshrInt: LiteralOp = LiteralOp(0xE2 shl 8)
    }
}

@JvmInline
public value class UnaryOp internal constructor(
    internal val bits: Int,
) {

    public companion object {
        public val NegInt: UnaryOp = UnaryOp(0x7B)
        public val NotInt: UnaryOp = UnaryOp(0x7C)
        public val NegLong: UnaryOp = UnaryOp(0x7D or WIDE_A or WIDE_B)
        public val NotLong: UnaryOp = UnaryOp(0x7E or WIDE_A or WIDE_B)
        public val NegFloat: UnaryOp = UnaryOp(0x7F)
        public val NegDouble: UnaryOp = UnaryOp(0x80 or WIDE_A or WIDE_B)
    }
}

@JvmInline
public value class Conversion internal constructor(
    internal val bits: Int,
) {

    public companion object {
        public val IntToLong: Conversion = Conversion(0x81 or WIDE_A)
        public val IntToFloat: Conversion = Conversion(0x82)
        public val IntToDouble: Conversion = Conversion(0x83 or WIDE_A)
        public val LongToInt: Conversion = Conversion(0x84 or WIDE_B)
        public val LongToFloat: Conversion = Conversion(0x85 or WIDE_B)
        public val LongToDouble: Conversion = Conversion(0x86 or WIDE_A or WIDE_B)
        public val FloatToInt: Conversion = Conversion(0x87)
        public val FloatToLong: Conversion = Conversion(0x88 or WIDE_A)
        public val FloatToDouble: Conversion = Conversion(0x89 or WIDE_A)
        public val DoubleToInt: Conversion = Conversion(0x8A or WIDE_B)
        public val DoubleToLong: Conversion = Conversion(0x8B or WIDE_A or WIDE_B)
        public val DoubleToFloat: Conversion = Conversion(0x8C or WIDE_B)
        public val IntToByte: Conversion = Conversion(0x8D)
        public val IntToChar: Conversion = Conversion(0x8E)
        public val IntToShort: Conversion = Conversion(0x8F)
    }
}

@JvmInline
public value class CompareOp internal constructor(
    internal val bits: Int,
) {

    public companion object {
        public val CmplFloat: CompareOp = CompareOp(0x2D)

        public val CmpgFloat: CompareOp = CompareOp(0x2E)
        public val CmplDouble: CompareOp = CompareOp(0x2F or WIDE_B)
        public val CmpgDouble: CompareOp = CompareOp(0x30 or WIDE_B)
        public val CmpLong: CompareOp = CompareOp(0x31 or WIDE_B)
    }
}

@JvmInline
public value class ArrayKind internal constructor(
    internal val bits: Int,
) {

    public companion object {
        /** `int[]` or `float[]`. */
        public val Int: ArrayKind = ArrayKind(0x44)

        /** `long[]` or `double[]`. */
        public val Wide: ArrayKind = ArrayKind(0x45 or WIDE_A)
        public val Object: ArrayKind = ArrayKind(0x46)
        public val Boolean: ArrayKind = ArrayKind(0x47)
        public val Byte: ArrayKind = ArrayKind(0x48)
        public val Char: ArrayKind = ArrayKind(0x49)
        public val Short: ArrayKind = ArrayKind(0x4A)
    }
}

/** Signed comparison of `Int` values; [Eq] and [Ne] also compare references. */
@JvmInline
public value class Cond internal constructor(
    internal val bits: Int,
) {

    public companion object {
        public val Eq: Cond = Cond(0)
        public val Ne: Cond = Cond(1)
        public val Lt: Cond = Cond(2)
        public val Ge: Cond = Cond(3)
        public val Gt: Cond = Cond(4)
        public val Le: Cond = Cond(5)
    }
}
