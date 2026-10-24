package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Pools
import io.github.charlietap.dexter.internal.putU16
import io.github.charlietap.dexter.internal.putU32

internal class IdSectionWriter {

    fun write(
        bytes: ByteArray,
        layout: IdLayout,
        pools: Pools,
        classes: ClassTable,
        order: PoolOrder,
        classOrder: IntArray,
        data: DataOffsets,
    ) {
        for (id in 0 until layout.stringCount) putU32(bytes, layout.stringIds + 4 * id, data.strings[id])
        for (id in 0 until layout.typeCount) {
            putU32(bytes, layout.typeIds + 4 * id, order.stringMap[pools.typeDescriptor[order.typeOrder[id]]])
        }
        for (id in 0 until layout.protoCount) {
            val proto = order.protoOrder[id]
            val at = layout.protoIds + 12 * id
            putU32(bytes, at, order.stringMap[pools.protoShorty[proto]])
            putU32(bytes, at + 4, order.typeMap[pools.protoReturn[proto]])
            putU32(bytes, at + 8, data.typeLists.parameters[id])
        }
        for (id in 0 until layout.fieldCount) {
            val field = order.fieldOrder[id]
            val at = layout.fieldIds + 8 * id
            putU16(bytes, at, order.typeMap[pools.fieldClass[field]])
            putU16(bytes, at + 2, order.typeMap[pools.fieldType[field]])
            putU32(bytes, at + 4, order.stringMap[pools.fieldName[field]])
        }
        for (id in 0 until layout.methodCount) {
            val method = order.methodOrder[id]
            val at = layout.methodIds + 8 * id
            putU16(bytes, at, order.typeMap[pools.methodClass[method]])
            putU16(bytes, at + 2, order.protoMap[pools.methodProto[method]])
            putU32(bytes, at + 4, order.stringMap[pools.methodName[method]])
        }
        for (id in 0 until layout.classCount) {
            val definition = classOrder[id]
            val at = layout.classDefs + 32 * id
            putU32(bytes, at, order.typeMap[classes.type[definition]])
            putU32(bytes, at + 4, classes.access[definition])
            putU32(bytes, at + 8, order.typeMap[classes.superclass[definition]])
            putU32(bytes, at + 12, data.typeLists.interfaces[definition])
            val source = classes.sourceFile[definition]
            putU32(bytes, at + 16, if (source == ClassTable.NO_SOURCE) DexFormat.NO_INDEX else order.stringMap[source])
            putU32(bytes, at + 20, 0)
            putU32(bytes, at + 24, data.classData[definition])
            putU32(bytes, at + 28, 0)
        }
        writeMethodHandles(bytes, layout, pools, order)
    }

    private fun writeMethodHandles(
        bytes: ByteArray,
        layout: IdLayout,
        pools: Pools,
        order: PoolOrder,
    ) {
        for (handle in 0 until layout.methodHandleCount) {
            val at = layout.methodHandles + 8 * handle
            val kind = pools.handleKind[handle]
            val member = pools.handleMember[handle]
            putU16(bytes, at, kind)
            putU16(bytes, at + 2, 0)
            putU16(bytes, at + 4, if (kind <= LAST_FIELD_KIND) order.fieldMap[member] else order.methodMap[member])
            putU16(bytes, at + 6, 0)
        }
    }

    private companion object {
        const val LAST_FIELD_KIND = 3
    }

    class DataOffsets(
        val strings: IntArray,
        val typeLists: TypeListWriter.Offsets,
        val classData: IntArray,
    )
}
