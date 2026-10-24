package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ByteSink
import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.atLeast

internal class ClassDataWriter(
    private val out: ByteSink,
) {

    private val listSizes = IntArray(3)
    private var finalIndex = IntArray(64)
    private var members = IntArray(64)
    private var position = IntArray(16)
    private var starts = IntArray(64)
    private var fieldMember = IntArray(64)
    private var methodMember = IntArray(64)

    fun write(
        classes: ClassTable,
        classOrder: IntArray,
        order: PoolOrder,
        layout: IdLayout,
        codeOffsets: IntArray,
        map: MapListWriter,
    ): IntArray {
        sortMembers(classes, classOrder, order, layout)
        val count = classes.memberCount
        val offsets = IntArray(classes.count)
        var first = 0
        var written = 0
        var at = 0
        while (at < count) {
            val owner = classes.memberClass[members[at]]
            var end = at
            while (end < count && classes.memberClass[members[end]] == owner) end++
            if (written++ == 0) first = out.size
            offsets[owner] = out.size
            writeClass(classes, at, end, codeOffsets)
            at = end
        }
        map.add(DexFormat.TYPE_CLASS_DATA, written, first)
        return offsets
    }

    private fun sortMembers(
        classes: ClassTable,
        classOrder: IntArray,
        order: PoolOrder,
        layout: IdLayout,
    ) {
        val count = classes.memberCount
        members = members.atLeast(count)
        finalIndex = finalIndex.atLeast(count)
        position = position.atLeast(classes.count)
        for (rank in 0 until classes.count) position[classOrder[rank]] = rank
        val buckets = classes.count * 3
        starts = starts.atLeast(buckets + 1)
        starts.fill(0, 0, buckets + 1)
        fieldMember = fieldMember.atLeast(layout.fieldCount)
        fieldMember.fill(NOT_DEFINED, 0, layout.fieldCount)
        methodMember = methodMember.atLeast(layout.methodCount)
        methodMember.fill(NOT_DEFINED, 0, layout.methodCount)
        val memberList = classes.memberList
        val memberIndex = classes.memberIndex
        for (member in 0 until count) {
            starts[bucket(classes, member) + 1]++
            if (memberList[member] == ClassTable.LIST_STATIC_FIELDS) {
                fieldMember[memberIndex[member]] = member
            } else {
                methodMember[memberIndex[member]] = member
            }
        }
        for (bucket in 1..buckets) starts[bucket] += starts[bucket - 1]
        scatter(classes, order.fieldOrder, fieldMember, layout.fieldCount)
        scatter(classes, order.methodOrder, methodMember, layout.methodCount)
    }

    private fun scatter(
        classes: ClassTable,
        sorted: IntArray,
        memberOf: IntArray,
        count: Int,
    ) {
        for (rank in 0 until count) {
            val member = memberOf[sorted[rank]]
            if (member == NOT_DEFINED) continue
            finalIndex[member] = rank
            members[starts[bucket(classes, member)]++] = member
        }
    }

    private fun bucket(
        classes: ClassTable,
        member: Int,
    ): Int = position[classes.memberClass[member]] * 3 + classes.memberList[member]

    private fun writeClass(
        classes: ClassTable,
        start: Int,
        end: Int,
        codeOffsets: IntArray,
    ) {
        listSizes.fill(0)
        for (at in start until end) listSizes[classes.memberList[members[at]]]++
        out.uleb(listSizes[ClassTable.LIST_STATIC_FIELDS])
        out.uleb(0)
        out.uleb(listSizes[ClassTable.LIST_DIRECT_METHODS])
        out.uleb(listSizes[ClassTable.LIST_VIRTUAL_METHODS])
        var list = -1
        var previous = 0
        for (at in start until end) {
            val member = members[at]
            if (classes.memberList[member] != list) {
                list = classes.memberList[member]
                previous = 0
            }
            val index = finalIndex[member]
            out.uleb(index - previous)
            previous = index
            out.uleb(classes.memberAccess[member])
            if (list != ClassTable.LIST_STATIC_FIELDS) {
                val code = classes.memberCode[member]
                out.uleb(if (code == ClassTable.NO_CODE) 0 else codeOffsets[code])
            }
        }
    }

    private companion object {
        const val NOT_DEFINED = -1
    }
}
