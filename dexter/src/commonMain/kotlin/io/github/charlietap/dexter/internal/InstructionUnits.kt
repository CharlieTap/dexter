package io.github.charlietap.dexter.internal

// Code units per opcode, 16 opcodes per row; 0 marks an unused opcode.
private const val UNITS =
    "1123123123111111" + // 0x00
        "1112322352232112" + // 0x10
        "2122333112333222" + // 0x20
        "2222222222222200" + // 0x30
        "0000222222222222" + // 0x40
        "2222222222222222" + // 0x50
        "2222222222222233" + // 0x60
        "3330333330011111" + // 0x70
        "1111111111111111" + // 0x80
        "2222222222222222" + // 0x90
        "2222222222222222" + // 0xA0
        "1111111111111111" + // 0xB0
        "1111111111111111" + // 0xC0
        "2222222222222222" + // 0xD0
        "2220000000000000" + // 0xE0
        "0000000000443322" // 0xF0

internal fun instructionUnits(
    opcode: Int,
): Int = UNITS[opcode] - '0'
