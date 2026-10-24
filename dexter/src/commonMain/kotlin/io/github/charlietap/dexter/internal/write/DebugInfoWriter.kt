package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink
import io.github.charlietap.dexter.internal.atLeast
import io.github.charlietap.dexter.internal.code.CodeTables

internal class DebugInfoWriter(
    private val out: ByteSink,
) {

    private var offsets = IntArray(64)

    fun write(
        tables: CodeTables,
        map: MapListWriter,
    ): IntArray {
        val records = tables.records
        offsets = offsets.atLeast(tables.recordCount)
        val offsets = offsets
        offsets.fill(0, 0, tables.recordCount)
        var first = 0
        var written = 0
        for (record in 0 until tables.recordCount) {
            val recordAt = record * CodeTables.RECORD_SIZE
            val from = records[recordAt + CodeTables.R_POSITION_START]
            val to = records[recordAt + CodeTables.R_POSITION_END]
            if (from == to) continue
            if (written++ == 0) first = out.size
            offsets[record] = out.size
            writeItem(tables.positions, from, to, records[recordAt + CodeTables.R_PARAMETERS])
        }
        map.add(DexFormat.TYPE_DEBUG_INFO_ITEM, written, first)
        return offsets
    }

    private fun writeItem(
        positions: IntArray,
        from: Int,
        to: Int,
        parameters: Int,
    ) {
        var line = positions[from * 2 + 1]
        out.uleb(line)
        out.uleb(parameters)
        repeat(parameters) { out.uleb(0) }
        var address = 0
        for (position in from until to) {
            val addressDelta = positions[position * 2] - address
            var lineDelta = positions[position * 2 + 1] - line
            if (lineDelta !in LINE_BASE until LINE_BASE + LINE_RANGE) {
                out.u8(DBG_ADVANCE_LINE)
                out.sleb(lineDelta)
                lineDelta = 0
            }
            val special = FIRST_SPECIAL + (lineDelta - LINE_BASE)
            if (special + LINE_RANGE.toLong() * addressDelta > 0xFF) {
                out.u8(DBG_ADVANCE_PC)
                out.uleb(addressDelta)
                out.u8(special)
            } else {
                out.u8(special + LINE_RANGE * addressDelta)
            }
            address = positions[position * 2]
            line = positions[position * 2 + 1]
        }
        out.u8(DBG_END_SEQUENCE)
    }

    private companion object {
        const val DBG_END_SEQUENCE = 0x00
        const val DBG_ADVANCE_PC = 0x01
        const val DBG_ADVANCE_LINE = 0x02
        const val FIRST_SPECIAL = 0x0A
        const val LINE_BASE = -4
        const val LINE_RANGE = 15
    }
}
