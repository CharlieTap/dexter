package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Pools

internal class IdLayout(
    pools: Pools,
    classes: ClassTable,
) {
    val stringCount: Int = pools.strings.size
    val typeCount: Int = pools.types.size
    val protoCount: Int = pools.protos.size
    val fieldCount: Int = pools.fields.size
    val methodCount: Int = pools.methods.size
    val classCount: Int = classes.count
    val methodHandleCount: Int = pools.methodHandles.size

    val stringIds: Int = DexFormat.HEADER_SIZE
    val typeIds: Int = stringIds + 4 * stringCount
    val protoIds: Int = typeIds + 4 * typeCount
    val fieldIds: Int = protoIds + 12 * protoCount
    val methodIds: Int = fieldIds + 8 * fieldCount
    val classDefs: Int = methodIds + 8 * methodCount

    val methodHandles: Int = classDefs + 32 * classCount

    val data: Int = methodHandles + 8 * methodHandleCount
}

internal object DexFormat {
    const val HEADER_SIZE = 0x70
    const val ENDIAN_CONSTANT = 0x12345678
    const val NO_INDEX = -1

    fun magic(
        version: Int,
    ): ByteArray = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, (0x30 + version / 10).toByte(), (0x30 + version % 10).toByte(), 0x00)

    const val TYPE_HEADER = 0x0000
    const val TYPE_STRING_ID = 0x0001
    const val TYPE_TYPE_ID = 0x0002
    const val TYPE_PROTO_ID = 0x0003
    const val TYPE_FIELD_ID = 0x0004
    const val TYPE_METHOD_ID = 0x0005
    const val TYPE_CLASS_DEF = 0x0006
    const val TYPE_METHOD_HANDLE_ITEM = 0x0008
    const val TYPE_MAP_LIST = 0x1000
    const val TYPE_TYPE_LIST = 0x1001
    const val TYPE_CLASS_DATA = 0x2000
    const val TYPE_CODE_ITEM = 0x2001
    const val TYPE_STRING_DATA = 0x2002
    const val TYPE_DEBUG_INFO_ITEM = 0x2003
}
