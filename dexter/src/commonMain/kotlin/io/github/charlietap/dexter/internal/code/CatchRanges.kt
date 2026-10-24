package io.github.charlietap.dexter.internal.code

import io.github.charlietap.dexter.internal.Bits
import io.github.charlietap.dexter.internal.atLeast
import io.github.charlietap.dexter.internal.grown
import io.github.charlietap.dexter.internal.instructionUnits
import io.github.charlietap.dexter.internal.sortIndices
import io.github.charlietap.dexter.internal.ulebSize

internal class CatchRanges {

    private var ranges = IntArray(16)
    private var count = 0

    private var moveExceptions = IntArray(8)
    private var moveExceptionCount = 0

    private var list = IntArray(16)

    private var sorted = IntArray(16)
    private var sortedCount = 0
    private var scratch = IntArray(16)
    private var stack = IntArray(16)
    private var bounds = IntArray(32)
    private var boundCount = 0

    private var typeStamps = IntArray(64)
    private var stamp = 0

    private val handlerAt = Bits()
    private val jumpTarget = Bits()

    fun reset() {
        count = 0
        moveExceptionCount = 0
    }

    fun add(
        start: Int,
        end: Int,
        type: Int,
        handler: Int,
    ) {
        val record = count * 4
        if (record + 4 > ranges.size) ranges = ranges.grown(record + 4)
        ranges[record] = start
        ranges[record + 1] = end
        ranges[record + 2] = type
        ranges[record + 3] = handler
        count++
    }

    fun addMoveException(
        site: Int,
        fallsInto: Boolean,
    ) {
        if (moveExceptionCount == moveExceptions.size) moveExceptions = moveExceptions.grown(moveExceptionCount + 1)
        moveExceptions[moveExceptionCount++] = (site shl 1) or if (fallsInto) 1 else 0
    }

    fun validate(
        labels: MethodLabels,
        units: Int,
        method: Any?,
        branches: BranchRelaxer,
        switches: SwitchTable,
    ) {
        for (range in 0 until count) {
            val record = range * 4
            val start = bound(labels, ranges[record], method)
            val end = bound(labels, ranges[record + 1], method)
            check(start <= end) { "A catch range in $method ends before it starts" }
            checkTarget(labels, ranges[record + 3], units, method)
        }
        checkNesting(labels, method)
        if (moveExceptionCount > 0) checkMoveExceptions(labels, method, branches, switches)
    }

    private fun checkMoveExceptions(
        labels: MethodLabels,
        method: Any?,
        branches: BranchRelaxer,
        switches: SwitchTable,
    ) {
        handlerAt.clear()
        jumpTarget.clear()
        for (range in 0 until count) handlerAt.set(labels.address(ranges[range * 4 + 3]))
        branches.markTargets(labels, jumpTarget)
        switches.markTargets(labels, jumpTarget)
        for (index in 0 until moveExceptionCount) {
            val packed = moveExceptions[index]
            val site = packed ushr 1
            check(handlerAt[site]) { "A handler begun in $method is used by no catch range" }
            check(site != 0 && packed and 1 == 0 && !jumpTarget[site]) {
                "A handler in $method that reads its exception can be reached other than by an exception"
            }
        }
    }

    private fun bound(
        labels: MethodLabels,
        label: Int,
        method: Any?,
    ): Int {
        val address = labels.address(label)
        check(address != MethodLabels.UNBOUND) { "A label used by a catch range in $method is never bound" }
        return address
    }

    private fun checkNesting(
        labels: MethodLabels,
        method: Any?,
    ) {
        if (count < 2) return
        order(labels)
        var top = 0
        for (position in 0 until sortedCount) {
            val range = sorted[position]
            val start = start(labels, range)
            while (top > 0 && end(labels, stack[top - 1]) <= start) top--
            check(top == 0 || end(labels, range) <= end(labels, stack[top - 1])) { "Catch ranges in $method overlap without nesting" }
            stack[top++] = range
        }
    }

    fun remap(
        map: AddressMap,
    ) {
        for (index in 0 until moveExceptionCount) {
            val packed = moveExceptions[index]
            moveExceptions[index] = (map.map(packed ushr 1) shl 1) or (packed and 1)
        }
    }

    fun flatten(
        labels: MethodLabels,
        tables: CodeTables,
        arena: ByteArray,
        methodStart: Int,
    ) {
        if (count == 0) return
        order(labels)
        if (sortedCount == 0) return
        tables.beginLists()
        bounds(labels)

        var top = 0
        var next = 0
        var openStart = 0
        var openEnd = 0
        var openList = -1
        for (bound in 0 until boundCount - 1) {
            val at = bounds[bound]
            while (top > 0 && end(labels, stack[top - 1]) <= at) top--
            while (next < sortedCount && start(labels, sorted[next]) == at) stack[top++] = sorted[next++]
            val listIndex = if (top == 0) -1 else handlerList(labels, tables, top)
            if (listIndex == openList && listIndex >= 0) {
                openEnd = bounds[bound + 1]
                continue
            }
            if (openList >= 0) addTries(tables, arena, methodStart, openStart, openEnd, openList)
            openStart = at
            openEnd = bounds[bound + 1]
            openList = listIndex
        }
        if (openList >= 0) addTries(tables, arena, methodStart, openStart, openEnd, openList)
    }

    // The distinct range starts and ends, ascending.
    private fun bounds(
        labels: MethodLabels,
    ) {
        val size = sortedCount * 2
        bounds = bounds.atLeast(size)
        val all = bounds
        for (position in 0 until sortedCount) {
            all[position * 2] = start(labels, sorted[position])
            all[position * 2 + 1] = end(labels, sorted[position])
        }
        all.sort(0, size)
        var distinct = 0
        for (index in 0 until size) if (index == 0 || all[index] != all[index - 1]) all[distinct++] = all[index]
        boundCount = distinct
    }

    private fun handlerList(
        labels: MethodLabels,
        tables: CodeTables,
        top: Int,
    ): Int {
        if (list.size < 2 + top * 2) list = IntArray(2 + top * 4)
        if (++stamp == 0) {
            typeStamps.fill(0)
            stamp = 1
        }
        var types = 0
        var catchAll = -1
        var depth = top - 1
        while (depth >= 0) {
            val record = stack[depth] * 4
            val type = ranges[record + 2]
            val address = labels.address(ranges[record + 3])
            if (type == CATCH_ALL) {
                catchAll = address
                break
            }
            if (type >= typeStamps.size) typeStamps = typeStamps.grown(type + 1)
            if (typeStamps[type] != stamp) {
                typeStamps[type] = stamp
                list[2 + types * 2] = type
                list[3 + types * 2] = address
                types++
            }
            depth--
        }
        list[0] = types
        list[1] = catchAll
        return tables.handlerList(list, 2 + types * 2)
    }

    private fun addTries(
        tables: CodeTables,
        arena: ByteArray,
        methodStart: Int,
        start: Int,
        end: Int,
        listIndex: Int,
    ) {
        var from = start
        while (end - from > 0xFFFF) {
            var at = from
            while (true) {
                val next = at + instructionUnits(arena[methodStart + at * 2].toInt() and 0xFF)
                if (next - from > 0xFFFF) break
                at = next
            }
            tables.addTry(from, at - from, listIndex)
            from = at
        }
        tables.addTry(from, end - from, listIndex)
    }

    // Equal ranges latest-declared first, so the earliest declared ends up innermost on the stack.
    private fun order(
        labels: MethodLabels,
    ) {
        sorted = sorted.atLeast(count)
        scratch = scratch.atLeast(count)
        stack = stack.atLeast(count)
        val all = sorted
        sortIndices(all, count, scratch) { left, right ->
            var diff = start(labels, left) - start(labels, right)
            if (diff == 0) diff = end(labels, right) - end(labels, left)
            if (diff == 0) diff = right - left
            diff
        }
        var nonEmpty = 0
        for (position in 0 until count) {
            val range = all[position]
            if (start(labels, range) < end(labels, range)) all[nonEmpty++] = range
        }
        sortedCount = nonEmpty
    }

    private fun start(
        labels: MethodLabels,
        range: Int,
    ): Int = labels.address(ranges[range * 4])

    private fun end(
        labels: MethodLabels,
        range: Int,
    ): Int = labels.address(ranges[range * 4 + 1])

    companion object {
        const val CATCH_ALL = -1

        fun handlersFit(
            tables: CodeTables,
            handlerStart: Int,
        ): Boolean {
            val handlers = tables.handlers
            var offset = 5
            var at = handlerStart
            while (at < tables.handlerSize) {
                if (offset > 0xFFFF) return false
                val types = handlers[at]
                offset += 3
                for (type in 0 until types) offset += 3 + ulebSize(handlers[at + 3 + type * 2])
                if (handlers[at + 1] >= 0) offset += ulebSize(handlers[at + 1])
                at += 2 + types * 2
            }
            return true
        }
    }
}
