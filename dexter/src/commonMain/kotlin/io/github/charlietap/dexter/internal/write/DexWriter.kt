package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink
import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Pools
import io.github.charlietap.dexter.internal.code.CodeTables

internal class DexWriter(
    private val out: ByteSink = ByteSink(1 shl 16),
    private val debugInfo: DebugInfoWriter = DebugInfoWriter(out),
    private val codeItems: CodeItemWriter = CodeItemWriter(out),
    private val typeLists: TypeListWriter = TypeListWriter(out),
    private val stringData: StringDataWriter = StringDataWriter(out),
    private val classData: ClassDataWriter = ClassDataWriter(out),
    private val mapList: MapListWriter = MapListWriter(out),
    private val ids: IdSectionWriter = IdSectionWriter(),
    private val header: HeaderWriter = HeaderWriter(),
    private val order: PoolOrder = PoolOrder(),
) {

    fun write(
        pools: Pools,
        classes: ClassTable,
        arena: ByteArray,
        tables: CodeTables,
    ): ByteArray {
        order.sort(pools)
        val classOrder = classOrder(classes, pools.types.size)
        val layout = IdLayout(pools, classes)

        out.reset()
        out.ensure(layout.data)
        out.bytes.fill(0, 0, DexFormat.HEADER_SIZE)
        out.size = layout.data
        mapList.reset(layout)

        val debugOffsets = debugInfo.write(tables, mapList)
        val codeOffsets = codeItems.write(arena, tables, order, debugOffsets, mapList)
        val typeListOffsets = typeLists.write(pools, classes, classOrder, order, mapList)
        val stringOffsets = stringData.write(order, mapList)
        val classDataOffsets = classData.write(classes, classOrder, order, layout, codeOffsets, mapList)
        val mapOffset = mapList.write()

        val size = out.size
        val bytes = out.bytes
        val data = IdSectionWriter.DataOffsets(stringOffsets, typeListOffsets, classDataOffsets)
        ids.write(bytes, layout, pools, classes, order, classOrder, data)
        header.write(bytes, layout, size, mapOffset, version(tables))
        return bytes.copyOf(size)
    }

    // The newest version any method needs, so files without newer instructions stay 035.
    private fun version(
        tables: CodeTables,
    ): Int {
        var version = BASE_VERSION
        for (record in 0 until tables.recordCount) {
            version = maxOf(version, tables.records[record * CodeTables.RECORD_SIZE + CodeTables.R_VERSION])
        }
        return version
    }

    private companion object {
        const val BASE_VERSION = 35
    }
}
