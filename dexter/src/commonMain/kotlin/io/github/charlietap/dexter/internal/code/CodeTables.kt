package io.github.charlietap.dexter.internal.code

import io.github.charlietap.dexter.internal.grown

internal class CodeTables {

    var refs = IntArray(256)
        private set
    var refCount = 0
        private set

    var tries = IntArray(48)
        private set
    var tryCount = 0
        private set

    var handlers = IntArray(64)
        private set
    var handlerSize = 0
        private set

    var positions = IntArray(64)
        private set
    var positionCount = 0
        private set

    var records = IntArray(16 * RECORD_SIZE)
        private set
    var recordCount = 0
        private set

    private var listSlots = IntArray(64)
    private var listStamps = IntArray(64)
    private var listStamp = 0
    private var listCount = 0

    fun clear() {
        refCount = 0
        tryCount = 0
        handlerSize = 0
        positionCount = 0
        recordCount = 0
    }

    fun save(
        mark: IntArray,
    ) {
        mark[0] = refCount
        mark[1] = tryCount
        mark[2] = handlerSize
        mark[3] = recordCount
        mark[4] = positionCount
    }

    fun restore(
        mark: IntArray,
    ) {
        refCount = mark[0]
        tryCount = mark[1]
        handlerSize = mark[2]
        recordCount = mark[3]
        positionCount = mark[4]
    }

    // A later line at the same address replaces the earlier one.
    fun addPosition(
        from: Int,
        address: Int,
        line: Int,
    ) {
        if (positionCount > from && positions[positionCount * 2 - 2] == address) {
            positions[positionCount * 2 - 1] = line
            return
        }
        val at = positionCount * 2
        if (at + 2 > positions.size) positions = positions.grown(at + 2)
        positions[at] = address
        positions[at + 1] = line
        positionCount++
    }

    fun remapPositions(
        from: Int,
        map: AddressMap,
    ) {
        for (position in from until positionCount) positions[position * 2] = map.map(positions[position * 2])
    }

    fun addRef(
        bytePos: Int,
        kind: Int,
    ) {
        if (refCount == refs.size) refs = refs.grown(refCount + 1)
        refs[refCount++] = (bytePos shl 3) or kind
    }

    fun remapRefs(
        from: Int,
        methodStart: Int,
        map: AddressMap,
    ) {
        for (index in from until refCount) {
            val ref = refs[index]
            val unit = ((ref ushr 3) - methodStart) / 2
            refs[index] = ((methodStart + map.map(unit) * 2) shl 3) or (ref and 7)
        }
    }

    fun addTry(
        start: Int,
        count: Int,
        list: Int,
    ) {
        val record = tryCount * 3
        if (record + 3 > tries.size) tries = tries.grown(record + 3)
        tries[record] = start
        tries[record + 1] = count
        tries[record + 2] = list
        tryCount++
    }

    fun beginLists() {
        listStamp++
        listCount = 0
        if (listStamp == 0) {
            listStamps.fill(0)
            listStamp = 1
        }
    }

    fun handlerList(
        list: IntArray,
        length: Int,
    ): Int {
        val mask = listSlots.size - 1
        var slot = list.contentHash(0, length) and mask
        while (listStamps[slot] == listStamp) {
            val at = listSlots[slot]
            if (2 + handlers[at] * 2 == length && sameList(at, list, length)) return at
            slot = (slot + 1) and mask
        }
        if (handlerSize + length > handlers.size) handlers = handlers.grown(handlerSize + length)
        list.copyInto(handlers, handlerSize, 0, length)
        val index = handlerSize
        handlerSize += length
        listSlots[slot] = index
        listStamps[slot] = listStamp
        if (++listCount * 2 > listSlots.size) rehashLists()
        return index
    }

    private fun rehashLists() {
        val old = listSlots
        val oldStamps = listStamps
        listSlots = IntArray(old.size * 2)
        listStamps = IntArray(old.size * 2)
        val mask = listSlots.size - 1
        for (oldSlot in old.indices) {
            if (oldStamps[oldSlot] != listStamp) continue
            val at = old[oldSlot]
            var slot = handlers.contentHash(at, 2 + handlers[at] * 2) and mask
            while (listStamps[slot] == listStamp) slot = (slot + 1) and mask
            listSlots[slot] = at
            listStamps[slot] = listStamp
        }
    }

    private fun sameList(
        at: Int,
        list: IntArray,
        length: Int,
    ): Boolean {
        for (offset in 0 until length) if (handlers[at + offset] != list[offset]) return false
        return true
    }

    fun addRecord(
        start: Int,
        units: Int,
        registers: Int,
        ins: Int,
        outs: Int,
        parameters: Int,
        version: Int,
        mark: IntArray,
    ): Int {
        val record = recordCount * RECORD_SIZE
        if (record + RECORD_SIZE > records.size) records = records.grown(record + RECORD_SIZE)
        records[record + R_START] = start
        records[record + R_UNITS] = units
        records[record + R_REGISTERS] = registers
        records[record + R_INS] = ins
        records[record + R_OUTS] = outs
        records[record + R_REF_START] = mark[0]
        records[record + R_REF_END] = refCount
        records[record + R_TRY_START] = mark[1]
        records[record + R_TRY_END] = tryCount
        records[record + R_HANDLER_START] = mark[2]
        records[record + R_HANDLER_END] = handlerSize
        records[record + R_POSITION_START] = mark[4]
        records[record + R_POSITION_END] = positionCount
        records[record + R_PARAMETERS] = parameters
        records[record + R_VERSION] = version
        return recordCount++
    }

    companion object {
        const val MARK_SIZE = 5
        const val RECORD_SIZE = 15
        const val R_START = 0
        const val R_UNITS = 1
        const val R_REGISTERS = 2
        const val R_INS = 3
        const val R_OUTS = 4
        const val R_REF_START = 5
        const val R_REF_END = 6
        const val R_TRY_START = 7
        const val R_TRY_END = 8
        const val R_HANDLER_START = 9
        const val R_HANDLER_END = 10
        const val R_POSITION_START = 11
        const val R_POSITION_END = 12
        const val R_PARAMETERS = 13
        const val R_VERSION = 14
    }
}

private fun IntArray.contentHash(
    from: Int,
    length: Int,
): Int {
    var hash = 1
    for (index in from until from + length) hash = hash * 31 + this[index]
    return (hash * -0x61c88647) xor (hash ushr 16)
}
