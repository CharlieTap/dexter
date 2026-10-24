package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink
import io.github.charlietap.dexter.internal.code.CodeTables
import io.github.charlietap.dexter.internal.getU16
import io.github.charlietap.dexter.internal.grown
import io.github.charlietap.dexter.internal.putU16
import io.github.charlietap.dexter.internal.putU32

internal class CodeItemWriter(
    private val out: ByteSink,
) {

    private var handlerOffsets = IntArray(64)

    fun write(
        arena: ByteArray,
        tables: CodeTables,
        order: PoolOrder,
        debugInfo: IntArray,
        map: MapListWriter,
    ): IntArray {
        val count = tables.recordCount
        val offsets = IntArray(count)
        for (record in 0 until count) {
            out.align(4)
            offsets[record] = out.size
            writeItem(arena, tables, record * CodeTables.RECORD_SIZE, order, debugInfo[record])
        }
        if (count > 0) map.add(DexFormat.TYPE_CODE_ITEM, count, offsets[0])
        return offsets
    }

    private fun writeItem(
        arena: ByteArray,
        tables: CodeTables,
        recordAt: Int,
        order: PoolOrder,
        debugInfo: Int,
    ) {
        val records = tables.records
        val start = records[recordAt + CodeTables.R_START]
        val units = records[recordAt + CodeTables.R_UNITS]
        val tryStart = records[recordAt + CodeTables.R_TRY_START]
        val tryEnd = records[recordAt + CodeTables.R_TRY_END]
        out.u16(records[recordAt + CodeTables.R_REGISTERS])
        out.u16(records[recordAt + CodeTables.R_INS])
        out.u16(records[recordAt + CodeTables.R_OUTS])
        out.u16(tryEnd - tryStart)
        out.u32(debugInfo)
        out.u32(units)
        val refStart = records[recordAt + CodeTables.R_REF_START]
        val refEnd = records[recordAt + CodeTables.R_REF_END]
        writeInstructions(arena, tables, start, units, refStart, refEnd, order)
        if (tryEnd == tryStart) return
        if (units and 1 != 0) out.u16(0)
        val handlerStart = records[recordAt + CodeTables.R_HANDLER_START]
        val handlerEnd = records[recordAt + CodeTables.R_HANDLER_END]
        writeTries(tables, tryStart, tryEnd, handlerStart, handlerEnd, order)
    }

    private fun writeInstructions(
        arena: ByteArray,
        tables: CodeTables,
        start: Int,
        units: Int,
        refStart: Int,
        refEnd: Int,
        order: PoolOrder,
    ) {
        val insns = out.size
        out.copyFrom(arena, start, start + units * 2)
        val bytes = out.bytes
        val refs = tables.refs
        val maps = order.referenceMaps
        for (index in refStart until refEnd) {
            val ref = refs[index]
            val at = insns + (ref ushr 3) - start
            putU16(bytes, at, maps[ref and 7][getU16(bytes, at)])
        }
    }

    private fun writeTries(
        tables: CodeTables,
        tryStart: Int,
        tryEnd: Int,
        handlerStart: Int,
        handlerEnd: Int,
        order: PoolOrder,
    ) {
        val handlers = tables.handlers
        val typeMap = order.typeMap
        if (handlerOffsets.size < handlerEnd - handlerStart) handlerOffsets = handlerOffsets.grown(handlerEnd - handlerStart)
        val triesAt = out.size
        out.ensure((tryEnd - tryStart) * 8)
        out.size += (tryEnd - tryStart) * 8

        val listsAt = out.size
        var lists = 0
        var at = handlerStart
        while (at < handlerEnd) {
            lists++
            at += 2 + handlers[at] * 2
        }
        out.uleb(lists)
        at = handlerStart
        while (at < handlerEnd) {
            handlerOffsets[at - handlerStart] = out.size - listsAt
            val types = handlers[at]
            val catchAll = handlers[at + 1]
            out.sleb(if (catchAll >= 0) -types else types)
            for (handler in 0 until types) {
                out.uleb(typeMap[handlers[at + 2 + handler * 2]])
                out.uleb(handlers[at + 3 + handler * 2])
            }
            if (catchAll >= 0) out.uleb(catchAll)
            at += 2 + types * 2
        }

        val bytes = out.bytes
        val tries = tables.tries
        for (item in tryStart until tryEnd) {
            val itemAt = triesAt + (item - tryStart) * 8
            putU32(bytes, itemAt, tries[item * 3])
            putU16(bytes, itemAt + 4, tries[item * 3 + 1])
            putU16(bytes, itemAt + 6, handlerOffsets[tries[item * 3 + 2] - handlerStart])
        }
    }
}
