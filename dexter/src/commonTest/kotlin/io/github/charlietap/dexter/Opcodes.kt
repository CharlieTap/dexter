package io.github.charlietap.dexter

internal object Opcodes {

    val names: Array<String?> = arrayOfNulls(256)
    val formats: Array<String?> = arrayOfNulls(256)

    fun units(
        format: String,
    ): Int = format[0] - '0'

    private fun op(
        code: Int,
        name: String,
        format: String,
    ) {
        names[code] = name
        formats[code] = format
    }

    private fun series(
        first: Int,
        format: String,
        vararg names: String,
    ) {
        for (offset in names.indices) op(first + offset, names[offset], format)
    }

    init {
        op(0x00, "nop", "10x")
        series(0x01, "12x", "move")
        op(0x02, "move/from16", "22x")
        op(0x03, "move/16", "32x")
        op(0x04, "move-wide", "12x")
        op(0x05, "move-wide/from16", "22x")
        op(0x06, "move-wide/16", "32x")
        op(0x07, "move-object", "12x")
        op(0x08, "move-object/from16", "22x")
        op(0x09, "move-object/16", "32x")
        series(0x0A, "11x", "move-result", "move-result-wide", "move-result-object", "move-exception")
        op(0x0E, "return-void", "10x")
        series(0x0F, "11x", "return", "return-wide", "return-object")
        op(0x12, "const/4", "11n")
        op(0x13, "const/16", "21s")
        op(0x14, "const", "31i")
        op(0x15, "const/high16", "21h")
        op(0x16, "const-wide/16", "21s")
        op(0x17, "const-wide/32", "31i")
        op(0x18, "const-wide", "51l")
        op(0x19, "const-wide/high16", "21h")
        op(0x1A, "const-string", "21c")
        op(0x1B, "const-string/jumbo", "31c")
        op(0x1C, "const-class", "21c")
        series(0x1D, "11x", "monitor-enter", "monitor-exit")
        op(0x1F, "check-cast", "21c")
        op(0x20, "instance-of", "22c")
        op(0x21, "array-length", "12x")
        op(0x22, "new-instance", "21c")
        op(0x23, "new-array", "22c")
        op(0x24, "filled-new-array", "35c")
        op(0x25, "filled-new-array/range", "3rc")
        op(0x26, "fill-array-data", "31t")
        op(0x27, "throw", "11x")
        op(0x28, "goto", "10t")
        op(0x29, "goto/16", "20t")
        op(0x2A, "goto/32", "30t")
        op(0x2B, "packed-switch", "31t")
        op(0x2C, "sparse-switch", "31t")
        series(0x2D, "23x", "cmpl-float", "cmpg-float", "cmpl-double", "cmpg-double", "cmp-long")
        series(0x32, "22t", "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le")
        series(0x38, "21t", "if-eqz", "if-nez", "if-ltz", "if-gez", "if-gtz", "if-lez")
        val kinds = arrayOf("", "-wide", "-object", "-boolean", "-byte", "-char", "-short")
        for (offset in kinds.indices) {
            op(0x44 + offset, "aget${kinds[offset]}", "23x")
            op(0x4B + offset, "aput${kinds[offset]}", "23x")
            op(0x52 + offset, "iget${kinds[offset]}", "22c")
            op(0x59 + offset, "iput${kinds[offset]}", "22c")
            op(0x60 + offset, "sget${kinds[offset]}", "21c")
            op(0x67 + offset, "sput${kinds[offset]}", "21c")
        }
        val invokes = arrayOf("virtual", "super", "direct", "static", "interface")
        for (offset in invokes.indices) {
            op(0x6E + offset, "invoke-${invokes[offset]}", "35c")
            op(0x74 + offset, "invoke-${invokes[offset]}/range", "3rc")
        }
        series(0x7B, "12x", "neg-int", "not-int", "neg-long", "not-long", "neg-float", "neg-double")
        series(
            0x81,
            "12x",
            "int-to-long",
            "int-to-float",
            "int-to-double",
            "long-to-int",
            "long-to-float",
            "long-to-double",
            "float-to-int",
            "float-to-long",
            "float-to-double",
            "double-to-int",
            "double-to-long",
            "double-to-float",
            "int-to-byte",
            "int-to-char",
            "int-to-short",
        )
        val binary = arrayOf(
            "add-int",
            "sub-int",
            "mul-int",
            "div-int",
            "rem-int",
            "and-int",
            "or-int",
            "xor-int",
            "shl-int",
            "shr-int",
            "ushr-int",
            "add-long",
            "sub-long",
            "mul-long",
            "div-long",
            "rem-long",
            "and-long",
            "or-long",
            "xor-long",
            "shl-long",
            "shr-long",
            "ushr-long",
            "add-float",
            "sub-float",
            "mul-float",
            "div-float",
            "rem-float",
            "add-double",
            "sub-double",
            "mul-double",
            "div-double",
            "rem-double",
        )
        for (offset in binary.indices) {
            op(0x90 + offset, binary[offset], "23x")
            op(0xB0 + offset, "${binary[offset]}/2addr", "12x")
        }
        series(
            0xD0,
            "22s",
            "add-int/lit16",
            "rsub-int",
            "mul-int/lit16",
            "div-int/lit16",
            "rem-int/lit16",
            "and-int/lit16",
            "or-int/lit16",
            "xor-int/lit16",
        )
        series(
            0xD8,
            "22b",
            "add-int/lit8",
            "rsub-int/lit8",
            "mul-int/lit8",
            "div-int/lit8",
            "rem-int/lit8",
            "and-int/lit8",
            "or-int/lit8",
            "xor-int/lit8",
            "shl-int/lit8",
            "shr-int/lit8",
            "ushr-int/lit8",
        )
        op(0xFA, "invoke-polymorphic", "45cc")
        op(0xFB, "invoke-polymorphic/range", "4rcc")
        op(0xFC, "invoke-custom", "35c")
        op(0xFD, "invoke-custom/range", "3rc")
        op(0xFE, "const-method-handle", "21c")
        op(0xFF, "const-method-type", "21c")
    }
}
