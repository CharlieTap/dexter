package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink
import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Pools
import io.github.charlietap.dexter.internal.grown

internal class TypeListWriter(
    private val out: ByteSink,
) {

    private var contents = IntArray(64)
    private var contentSize = 0
    private var starts = IntArray(16)
    private var sizes = IntArray(16)
    private var offsets = IntArray(16)
    private var count = 0
    private var first = 0

    private var slots = IntArray(32)

    fun write(
        pools: Pools,
        classes: ClassTable,
        classOrder: IntArray,
        order: PoolOrder,
        map: MapListWriter,
    ): Offsets {
        slots.fill(0)
        contentSize = 0
        count = 0
        val parameters = IntArray(pools.protos.size)
        for (newIndex in parameters.indices) {
            val proto = order.protoOrder[newIndex]
            val size = pools.protoParamCount[proto]
            if (size > 0) parameters[newIndex] = list(pools.protoParams, pools.protoParamStart[proto], size, order.typeMap)
        }
        val interfaces = IntArray(classes.count)
        for (definition in classOrder) {
            val size = classes.interfaceCount[definition]
            if (size > 0) interfaces[definition] = list(classes.interfaces, classes.interfaceStart[definition], size, order.typeMap)
        }
        map.add(DexFormat.TYPE_TYPE_LIST, count, first)
        return Offsets(parameters, interfaces)
    }

    private fun list(
        source: IntArray,
        start: Int,
        size: Int,
        typeMap: IntArray,
    ): Int {
        var hash = 1
        for (index in start until start + size) hash = hash * 31 + typeMap[source[index]]
        val mask = slots.size - 1
        var slot = mix(hash) and mask
        while (slots[slot] != 0) {
            val list = slots[slot] - 1
            if (sizes[list] == size && same(list, source, start, typeMap)) return offsets[list]
            slot = (slot + 1) and mask
        }
        out.align(4)
        val offset = out.size
        if (count == 0) first = offset
        out.u32(size)
        if (contentSize + size > contents.size) contents = contents.grown(contentSize + size)
        for (index in 0 until size) {
            val type = typeMap[source[start + index]]
            contents[contentSize + index] = type
            out.u16(type)
        }
        if (count == starts.size) {
            starts = starts.grown(count + 1)
            sizes = sizes.grown(count + 1)
            offsets = offsets.grown(count + 1)
        }
        starts[count] = contentSize
        sizes[count] = size
        offsets[count] = offset
        contentSize += size
        slots[slot] = ++count
        if (count * 2 > slots.size) rehash()
        return offset
    }

    private fun same(
        list: Int,
        source: IntArray,
        start: Int,
        typeMap: IntArray,
    ): Boolean {
        val at = starts[list]
        for (index in 0 until sizes[list]) if (contents[at + index] != typeMap[source[start + index]]) return false
        return true
    }

    private fun rehash() {
        slots = IntArray(slots.size * 2)
        val mask = slots.size - 1
        for (list in 0 until count) {
            var hash = 1
            for (index in starts[list] until starts[list] + sizes[list]) hash = hash * 31 + contents[index]
            var slot = mix(hash) and mask
            while (slots[slot] != 0) slot = (slot + 1) and mask
            slots[slot] = list + 1
        }
    }

    class Offsets(
        val parameters: IntArray,
        val interfaces: IntArray,
    )
}

private fun mix(
    hash: Int,
): Int = (hash * -0x61c88647) xor (hash ushr 16)
