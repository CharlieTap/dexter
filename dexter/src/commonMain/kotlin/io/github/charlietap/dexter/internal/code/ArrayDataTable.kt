package io.github.charlietap.dexter.internal.code

import io.github.charlietap.dexter.internal.grown
import io.github.charlietap.dexter.internal.putU16
import io.github.charlietap.dexter.internal.putU32

internal class ArrayDataTable {

    // Four ints each: site unit, element width, element count, start in data.
    private var fills = IntArray(16)
    private var count = 0

    var data = ByteArray(256)
        private set
    private var dataSize = 0

    fun reset() {
        count = 0
        dataSize = 0
    }

    fun add(
        site: Int,
        width: Int,
        elements: Int,
    ): Int {
        val record = count * 4
        if (record + 4 > fills.size) fills = fills.grown(record + 4)
        val bytes = width * elements
        if (dataSize + bytes > data.size) data = data.grown(dataSize + bytes)
        val start = dataSize
        fills[record] = site
        fills[record + 1] = width
        fills[record + 2] = elements
        fills[record + 3] = start
        dataSize += bytes
        count++
        return start
    }

    fun remap(
        map: AddressMap,
    ) {
        for (fill in 0 until count) fills[fill * 4] = map.map(fills[fill * 4])
    }

    fun payloadUnits(
        start: Int,
    ): Int {
        if (count == 0) return 0
        var units = start and 1
        for (fill in 0 until count) units += units(fill)
        return units
    }

    fun writePayloads(
        arena: ByteArray,
        methodStart: Int,
        start: Int,
    ) {
        if (count == 0) return
        var unit = start
        if (unit and 1 != 0) putU16(arena, methodStart + unit++ * 2, 0x0000)
        for (fill in 0 until count) {
            val record = fill * 4
            val site = fills[record]
            val width = fills[record + 1]
            val elements = fills[record + 2]
            val at = methodStart + unit * 2
            val payloadUnits = units(fill)
            putU16(arena, at, 0x0300)
            putU16(arena, at + 2, width)
            putU32(arena, at + 4, elements)
            data.copyInto(arena, at + 8, fills[record + 3], fills[record + 3] + width * elements)
            arena.fill(0, at + 8 + width * elements, at + payloadUnits * 2)
            putU32(arena, methodStart + site * 2 + 2, unit - site)
            unit += payloadUnits
        }
    }

    private fun units(
        fill: Int,
    ): Int {
        val dataUnits = (fills[fill * 4 + 1] * fills[fill * 4 + 2] + 1) / 2
        return (4 + dataUnits + 1) and 1.inv()
    }
}
