package io.github.charlietap.dexter.internal.code

import io.github.charlietap.dexter.Label
import io.github.charlietap.dexter.LabelTable
import io.github.charlietap.dexter.SwitchCases
import io.github.charlietap.dexter.internal.Bits
import io.github.charlietap.dexter.internal.grown
import io.github.charlietap.dexter.internal.putU16
import io.github.charlietap.dexter.internal.putU32

internal class SwitchTable {

    private var switches = IntArray(20)
    private var count = 0

    private var targets = IntArray(64)
    private var keys = IntArray(64)
    private var targetCount = 0

    private var pairs = LongArray(16)

    fun reset() {
        count = 0
        targetCount = 0
    }

    fun addPacked(
        site: Int,
        table: LabelTable,
        labels: MethodLabels,
    ): Int {
        val ids = table.ids
        val size = ids.size
        val start = reserve(size)
        for (entry in 0 until size) targets[start + entry] = labels.indexOf(Label(ids[entry]))
        add(site, PACKED, start, size, table.firstKey)
        return PACKED_OPCODE
    }

    fun addCases(
        site: Int,
        cases: SwitchCases,
        labels: MethodLabels,
    ): Int {
        val caseCount = cases.size
        if (caseCount == 0) return -1
        require(caseCount <= MAX_SIZE) { "A switch holds at most $MAX_SIZE cases: $caseCount" }
        val caseKeys = cases.keys
        var min = caseKeys[0]
        var max = min
        for (case in 1 until caseCount) {
            val key = caseKeys[case]
            if (key < min) min = key
            if (key > max) max = key
        }
        val span = max.toLong() - min + 1
        return if (span <= MAX_SIZE && 4 + 2 * span <= 2 + 4L * caseCount) {
            addDense(site, cases, min, span.toInt(), labels)
        } else {
            addSparse(site, cases, labels)
        }
    }

    private fun addDense(
        site: Int,
        cases: SwitchCases,
        min: Int,
        size: Int,
        labels: MethodLabels,
    ): Int {
        val caseKeys = cases.keys
        val ids = cases.ids
        val start = reserve(size)
        targets.fill(FALLTHROUGH, start, start + size)
        for (case in 0 until cases.size) {
            val slot = start + caseKeys[case] - min
            require(targets[slot] == FALLTHROUGH) { "Duplicate switch key ${caseKeys[case]}" }
            targets[slot] = labels.indexOf(Label(ids[case]))
        }
        add(site, PACKED, start, size, min)
        return PACKED_OPCODE
    }

    private fun addSparse(
        site: Int,
        cases: SwitchCases,
        labels: MethodLabels,
    ): Int {
        val caseCount = cases.size
        val caseKeys = cases.keys
        if (pairs.size < caseCount) pairs = pairs.grown(caseCount)
        for (case in 0 until caseCount) pairs[case] = (caseKeys[case].toLong() shl 32) or case.toLong()
        pairs.sort(0, caseCount)
        val start = reserve(caseCount)
        for (pair in 0 until caseCount) {
            val key = (pairs[pair] shr 32).toInt()
            require(pair == 0 || key != keys[start + pair - 1]) { "Duplicate switch key $key" }
            keys[start + pair] = key
            targets[start + pair] = labels.indexOf(Label(cases.ids[pairs[pair].toInt()]))
        }
        add(site, SPARSE, start, caseCount, 0)
        return SPARSE_OPCODE
    }

    fun checkTargets(
        labels: MethodLabels,
        units: Int,
        method: Any?,
    ) {
        for (entry in 0 until targetCount) {
            val target = targets[entry]
            if (target != FALLTHROUGH) checkTarget(labels, target, units, method)
        }
    }

    fun markTargets(
        labels: MethodLabels,
        marks: Bits,
    ) {
        for (entry in 0 until targetCount) {
            val target = targets[entry]
            if (target != FALLTHROUGH) marks.set(labels.address(target))
        }
    }

    fun remap(
        map: AddressMap,
    ) {
        for (switch in 0 until count) switches[switch * 5] = map.map(switches[switch * 5])
    }

    fun payloadUnits(
        end: Int,
    ): Int {
        if (count == 0) return 0
        var units = end and 1
        for (switch in 0 until count) {
            val size = switches[switch * 5 + 3]
            units += if (switches[switch * 5 + 1] == PACKED) 4 + size * 2 else 2 + size * 4
        }
        return units
    }

    fun writePayloads(
        arena: ByteArray,
        methodStart: Int,
        end: Int,
        labels: MethodLabels,
    ) {
        if (count == 0) return
        var unit = end
        if (unit and 1 != 0) putU16(arena, methodStart + unit++ * 2, 0x0000)
        for (switch in 0 until count) {
            val record = switch * 5
            val site = switches[record]
            val start = switches[record + 2]
            val size = switches[record + 3]
            val at = methodStart + unit * 2
            if (switches[record + 1] == PACKED) {
                putU16(arena, at, 0x0100)
                putU16(arena, at + 2, size)
                putU32(arena, at + 4, switches[record + 4])
                for (entry in 0 until size) putU32(arena, at + 8 + entry * 4, target(targets[start + entry], site, labels) - site)
            } else {
                putU16(arena, at, 0x0200)
                putU16(arena, at + 2, size)
                for (entry in 0 until size) putU32(arena, at + 4 + entry * 4, keys[start + entry])
                val targetsAt = at + 4 + size * 4
                for (entry in 0 until size) putU32(arena, targetsAt + entry * 4, labels.address(targets[start + entry]) - site)
            }
            putU32(arena, methodStart + site * 2 + 2, unit - site)
            unit += if (switches[record + 1] == PACKED) 4 + size * 2 else 2 + size * 4
        }
    }

    // A packed entry with no case falls through to the instruction after the 3-unit switch.
    private fun target(
        target: Int,
        site: Int,
        labels: MethodLabels,
    ): Int = if (target == FALLTHROUGH) site + 3 else labels.address(target)

    private fun reserve(
        size: Int,
    ): Int {
        val start = targetCount
        if (start + size > targets.size) {
            targets = targets.grown(start + size)
            keys = keys.grown(start + size)
        }
        return start
    }

    private fun add(
        site: Int,
        kind: Int,
        start: Int,
        size: Int,
        firstKey: Int,
    ) {
        targetCount = start + size
        val record = count * 5
        if (record + 5 > switches.size) switches = switches.grown(record + 5)
        switches[record] = site
        switches[record + 1] = kind
        switches[record + 2] = start
        switches[record + 3] = size
        switches[record + 4] = firstKey
        count++
    }

    companion object {
        const val PACKED_OPCODE = 0x2B
        const val SPARSE_OPCODE = 0x2C
        const val MAX_SIZE = 0xFFFF
        private const val PACKED = 0
        private const val SPARSE = 1
        private const val FALLTHROUGH = -1
    }
}
