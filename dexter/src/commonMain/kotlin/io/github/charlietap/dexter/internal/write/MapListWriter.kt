package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink
import io.github.charlietap.dexter.internal.grown

internal class MapListWriter(
    private val out: ByteSink,
) {

    private var entries = IntArray(3 * 12)
    private var count = 0

    fun reset(
        layout: IdLayout,
    ) {
        count = 0
        add(DexFormat.TYPE_HEADER, 1, 0)
        add(DexFormat.TYPE_STRING_ID, layout.stringCount, layout.stringIds)
        add(DexFormat.TYPE_TYPE_ID, layout.typeCount, layout.typeIds)
        add(DexFormat.TYPE_PROTO_ID, layout.protoCount, layout.protoIds)
        add(DexFormat.TYPE_FIELD_ID, layout.fieldCount, layout.fieldIds)
        add(DexFormat.TYPE_METHOD_ID, layout.methodCount, layout.methodIds)
        add(DexFormat.TYPE_CLASS_DEF, layout.classCount, layout.classDefs)
        add(DexFormat.TYPE_METHOD_HANDLE_ITEM, layout.methodHandleCount, layout.methodHandles)
    }

    fun add(
        type: Int,
        size: Int,
        offset: Int,
    ) {
        if (size == 0) return
        if (count * 3 == entries.size) entries = entries.grown(count * 3 + 3)
        entries[count * 3] = type
        entries[count * 3 + 1] = size
        entries[count * 3 + 2] = offset
        count++
    }

    fun write(): Int {
        out.align(4)
        val offset = out.size
        add(DexFormat.TYPE_MAP_LIST, 1, offset)
        out.u32(count)
        for (entry in 0 until count) {
            out.u16(entries[entry * 3])
            out.u16(0)
            out.u32(entries[entry * 3 + 1])
            out.u32(entries[entry * 3 + 2])
        }
        return offset
    }
}
