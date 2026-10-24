package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.adler32
import io.github.charlietap.dexter.internal.putU32

internal class HeaderWriter {

    fun write(
        bytes: ByteArray,
        layout: IdLayout,
        fileSize: Int,
        mapOffset: Int,
        version: Int,
    ) {
        DexFormat.magic(version).copyInto(bytes, 0)
        putU32(bytes, 32, fileSize)
        putU32(bytes, 36, DexFormat.HEADER_SIZE)
        putU32(bytes, 40, DexFormat.ENDIAN_CONSTANT)
        putU32(bytes, 52, mapOffset)
        section(bytes, 56, layout.stringCount, layout.stringIds)
        section(bytes, 64, layout.typeCount, layout.typeIds)
        section(bytes, 72, layout.protoCount, layout.protoIds)
        section(bytes, 80, layout.fieldCount, layout.fieldIds)
        section(bytes, 88, layout.methodCount, layout.methodIds)
        section(bytes, 96, layout.classCount, layout.classDefs)
        putU32(bytes, 104, fileSize - layout.data)
        putU32(bytes, 108, layout.data)
        putU32(bytes, 8, adler32(bytes, 12, fileSize))
    }

    private fun section(
        bytes: ByteArray,
        at: Int,
        count: Int,
        offset: Int,
    ) {
        putU32(bytes, at, count)
        putU32(bytes, at + 4, if (count == 0) 0 else offset)
    }
}
