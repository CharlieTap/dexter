package io.github.charlietap.dexter.internal.code

import io.github.charlietap.dexter.internal.Bits
import io.github.charlietap.dexter.internal.getU16
import io.github.charlietap.dexter.internal.grown
import io.github.charlietap.dexter.internal.putU16
import io.github.charlietap.dexter.internal.putU32

internal class BranchRelaxer(
    private val reach: Int,
) : AddressMap {

    private var sites = IntArray(64)
    private var count = 0

    private var original = IntArray(16)
    private var forms = IntArray(16)
    private var targets = IntArray(16)

    private var targetSites = IntArray(16)
    private var shift = IntArray(17)
    private var scratch = ByteArray(0)

    fun reset() {
        count = 0
    }

    fun gotoForm(
        site: Int,
        target: Int,
    ): Int =
        when {
            target == MethodLabels.UNBOUND -> GOTO16
            fits(GOTO8, target - site) -> GOTO8
            fits(GOTO16, target - site) -> GOTO16
            else -> GOTO32
        }

    fun conditionalForm(
        site: Int,
        target: Int,
    ): Int = if (target == MethodLabels.UNBOUND || fits(IF16, target - site)) IF16 else IFLONG

    fun add(
        site: Int,
        label: Int,
        form: Int,
    ) {
        val record = count * 2
        if (record + 2 > sites.size) sites = sites.grown(record + 2)
        sites[record] = site
        sites[record + 1] = (label shl 3) or form
        count++
    }

    fun checkTargets(
        labels: MethodLabels,
        units: Int,
        method: Any?,
    ) {
        for (branch in 0 until count) checkTarget(labels, sites[branch * 2 + 1] ushr 3, units, method)
    }

    fun markTargets(
        labels: MethodLabels,
        targets: Bits,
    ) {
        for (branch in 0 until count) targets.set(labels.address(sites[branch * 2 + 1] ushr 3))
    }

    fun allFit(
        labels: MethodLabels,
    ): Boolean {
        for (branch in 0 until count) {
            val packed = sites[branch * 2 + 1]
            if (!fits(packed and 7, labels.address(packed ushr 3) - sites[branch * 2])) return false
        }
        return true
    }

    fun widen(
        labels: MethodLabels,
    ): Int {
        if (original.size < count) {
            original = IntArray(count)
            forms = IntArray(count)
            targets = IntArray(count)
            targetSites = IntArray(count)
            shift = IntArray(count + 1)
        }
        for (branch in 0 until count) {
            original[branch] = sites[branch * 2 + 1] and 7
            forms[branch] = original[branch]
            targets[branch] = labels.address(sites[branch * 2 + 1] ushr 3)
            targetSites[branch] = sitesBefore(targets[branch])
        }
        while (true) {
            shift[0] = 0
            for (branch in 0 until count) shift[branch + 1] = shift[branch] + FORM_UNITS[forms[branch]] - FORM_UNITS[original[branch]]
            var changed = false
            for (branch in 0 until count) {
                val target = targets[branch] + shift[targetSites[branch]]
                if (!fits(forms[branch], target - (sites[branch * 2] + shift[branch]))) {
                    forms[branch] = when (forms[branch]) {
                        GOTO8 -> GOTO16
                        GOTO16 -> GOTO32
                        else -> IFLONG
                    }
                    changed = true
                }
            }
            if (!changed) return shift[count]
        }
    }

    fun rebuild(
        source: ByteArray,
        start: Int,
        end: Int,
        length: Int,
    ): ByteArray {
        if (scratch.size < length) scratch = ByteArray(length)
        var readAt = start
        var writeAt = 0
        for (branch in 0 until count) {
            val siteAt = start + sites[branch * 2] * 2
            source.copyInto(scratch, writeAt, readAt, siteAt)
            writeAt += siteAt - readAt
            val from = original[branch]
            val to = forms[branch]
            if (from == to) {
                source.copyInto(scratch, writeAt, siteAt, siteAt + FORM_UNITS[from] * 2)
            } else {
                writeWidened(source, siteAt, writeAt, to)
            }
            writeAt += FORM_UNITS[to] * 2
            readAt = siteAt + FORM_UNITS[from] * 2
        }
        source.copyInto(scratch, writeAt, readAt, end)
        return scratch
    }

    private fun writeWidened(
        source: ByteArray,
        siteAt: Int,
        writeAt: Int,
        form: Int,
    ) {
        when (form) {
            GOTO16 -> {
                putU16(scratch, writeAt, 0x29)
                putU16(scratch, writeAt + 2, 0)
            }
            GOTO32 -> {
                putU16(scratch, writeAt, 0x2A)
                putU32(scratch, writeAt + 2, 0)
            }
            else -> putLongConditional(scratch, writeAt, getU16(source, siteAt))
        }
    }

    override fun map(
        address: Int,
    ): Int = address + shift[sitesBefore(address)]

    private fun sitesBefore(
        address: Int,
    ): Int {
        var low = 0
        var high = count
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sites[mid * 2] < address) low = mid + 1 else high = mid
        }
        return low
    }

    fun finish() {
        for (branch in 0 until count) {
            sites[branch * 2] += shift[branch]
            sites[branch * 2 + 1] = (sites[branch * 2 + 1] and 7.inv()) or forms[branch]
        }
    }

    fun patch(
        arena: ByteArray,
        methodStart: Int,
        labels: MethodLabels,
    ) {
        for (branch in 0 until count) {
            val site = sites[branch * 2]
            val packed = sites[branch * 2 + 1]
            val target = labels.address(packed ushr 3)
            val at = methodStart + site * 2
            when (packed and 7) {
                GOTO8 -> arena[at + 1] = (target - site).toByte()
                GOTO16, IF16 -> putU16(arena, at + 2, target - site)
                GOTO32 -> putU32(arena, at + 2, target - site)
                IFLONG -> putU32(arena, at + 6, target - (site + 2))
            }
        }
    }

    private fun fits(
        form: Int,
        offset: Int,
    ): Boolean =
        when (form) {
            GOTO8 -> offset != 0 && offset in -128..127 && offset in -reach - 1..reach
            GOTO16, IF16 -> offset != 0 && offset in -reach - 1..reach
            else -> true
        }

    companion object {
        const val GOTO8 = 0
        const val GOTO16 = 1
        const val GOTO32 = 2
        const val IF16 = 3
        const val IFLONG = 4
        private val FORM_UNITS = intArrayOf(1, 2, 3, 2, 5)
    }
}

internal fun putLongConditional(
    bytes: ByteArray,
    at: Int,
    unit0: Int,
) {
    putU16(bytes, at, unit0 xor 1)
    putU16(bytes, at + 2, 5)
    putU16(bytes, at + 4, 0x2A)
    putU32(bytes, at + 6, 0)
}

internal fun checkTarget(
    labels: MethodLabels,
    index: Int,
    units: Int,
    method: Any?,
) {
    val address = labels.address(index)
    check(address != MethodLabels.UNBOUND) { "A label used in $method is never bound" }
    check(address < units) { "A label used in $method is bound after the last instruction" }
}
