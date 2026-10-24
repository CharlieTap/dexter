package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink

internal class StringDataWriter(
    private val out: ByteSink,
) {

    fun write(
        order: PoolOrder,
        map: MapListWriter,
    ): IntArray {
        val strings = order.stringOrder
        val count = order.stringCount
        val offsets = IntArray(count)
        val start = out.size
        for (id in 0 until count) {
            val value = order.string(strings[id])
            offsets[id] = out.size
            out.uleb(value.length)
            out.mutf8(value)
            out.u8(0)
        }
        map.add(DexFormat.TYPE_STRING_DATA, count, start)
        return offsets
    }
}
