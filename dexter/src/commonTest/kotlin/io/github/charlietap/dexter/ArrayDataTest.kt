package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Burst
class ArrayDataTest {

    enum class Fill(
        val body: CodeEmitter.() -> Unit,
        val payload: IntArray,
    ) {
        BYTES({ fillArrayData(v(1), byteArrayOf(1, 2, 3)) }, units(0x0300, 1, 3, 0, 0x0201, 0x0003)),
        BOOLEANS({ fillArrayData(v(1), booleanArrayOf(true, false)) }, units(0x0300, 1, 2, 0, 0x0001, 0x0000)),
        SHORTS({ fillArrayData(v(1), shortArrayOf(-2)) }, units(0x0300, 2, 1, 0, 0xFFFE, 0x0000)),
        CHARS({ fillArrayData(v(1), charArrayOf('A', 'B')) }, units(0x0300, 2, 2, 0, 0x0041, 0x0042)),
        INTS({ fillArrayData(v(1), intArrayOf(1, -1)) }, units(0x0300, 4, 2, 0, 0x0001, 0x0000, 0xFFFF, 0xFFFF)),
        FLOATS({ fillArrayData(v(1), floatArrayOf(1f)) }, units(0x0300, 4, 1, 0, 0x0000, 0x3F80)),
        LONGS({ fillArrayData(v(1), longArrayOf(0x1122334455667788)) }, units(0x0300, 8, 1, 0, 0x7788, 0x5566, 0x3344, 0x1122)),
        DOUBLES({ fillArrayData(v(1), doubleArrayOf(1.0)) }, units(0x0300, 8, 1, 0, 0x0000, 0x0000, 0x0000, 0x3FF0)),
        EMPTY({ fillArrayData(v(1), IntArray(0)) }, units(0x0300, 4, 0, 0)),
    }

    @Test
    fun `fill array data points at its payload`(
        case: Fill,
    ) {
        val emitted = emit {
            case.body(this)
            returnVoid()
        }

        assertEquals(hex(units(0x0126, 0x0004, 0x0000, 0x000E) + case.payload), hex(emitted.insns))
    }

    @Test
    fun `a payload after an odd code end is aligned`() {
        val emitted = emit {
            fillArrayData(v(1), intArrayOf(7))
            returnVoid()
            returnVoid()
        }

        assertEquals(hex(units(0x0126, 0x0006, 0x0000, 0x000E, 0x000E, 0x0000, 0x0300, 4, 1, 0, 7, 0)), hex(emitted.insns))
    }

    @Test
    fun `array data follows switch payloads`() {
        val emitted = emit {
            val target = label()
            packedSwitch(v(0), labelTable(0, 1, target))
            fillArrayData(v(1), intArrayOf(7))
            bind(target)
            returnVoid()
        }

        val payloads = units(0x0100, 1, 0, 0, 0x0006, 0x0000, 0x0300, 4, 1, 0, 7, 0)
        assertEquals(hex(units(0x002B, 0x0008, 0x0000, 0x0126, 0x000B, 0x0000, 0x000E, 0x0000) + payloads), hex(emitted.insns))
    }

    @Test
    fun `relaxed branches keep the payload offset`() {
        val insns = emit(dexter = reaching(4)) {
            val target = label()
            goto(target)
            fillArrayData(v(1), intArrayOf(7))
            repeat(5) { const(v(0), 0) }
            bind(target)
            returnVoid()
        }.insns

        val site = insns.indexOf(0x0126)
        assertEquals(0x0300, insns[site + (insns[site + 1] or (insns[site + 2] shl 16))])
    }

    @Test
    fun `fill array data needs a register below 256`() {
        assertFailsWith<IllegalArgumentException> { emit { fillArrayData(v(256), intArrayOf(1)) } }
    }
}
