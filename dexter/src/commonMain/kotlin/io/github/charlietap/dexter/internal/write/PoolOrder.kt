package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.Interner
import io.github.charlietap.dexter.internal.Pools
import io.github.charlietap.dexter.internal.atLeast
import io.github.charlietap.dexter.internal.countingSort
import io.github.charlietap.dexter.internal.sortIndices
import io.github.charlietap.dexter.internal.sortStrings

internal class PoolOrder {

    private val strings = Ordering()
    private val types = Ordering()
    private val protos = Ordering()
    private val fields = Ordering()
    private val methods = Ordering()
    private var scratch = IntArray(64)
    private var counts = IntArray(64)
    private var stringValues = arrayOfNulls<String>(64)
    private var stringKeys = LongArray(64)
    private lateinit var stringPool: Interner<String>

    val stringOrder: IntArray get() = strings.order
    val stringMap: IntArray get() = strings.map
    val typeOrder: IntArray get() = types.order
    val typeMap: IntArray get() = types.map
    val protoOrder: IntArray get() = protos.order
    val protoMap: IntArray get() = protos.map
    val fieldOrder: IntArray get() = fields.order
    val fieldMap: IntArray get() = fields.map
    val methodOrder: IntArray get() = methods.order
    val methodMap: IntArray get() = methods.map

    var stringCount = 0
        private set

    var referenceMaps: Array<IntArray> = emptyArray()
        private set

    fun sort(
        pools: Pools,
    ) {
        stringPool = pools.strings
        stringCount = pools.strings.size
        val typeCount = pools.types.size
        val protoCount = pools.protos.size
        counts = counts.atLeast(maxOf(stringCount, typeCount, protoCount) + 1)
        scratch = scratch.atLeast(maxOf(stringCount, typeCount, protoCount, pools.fields.size, pools.methods.size))

        if (stringValues.size < stringCount) stringValues = arrayOfNulls(maxOf(stringValues.size * 2, stringCount))
        for (index in 0 until stringCount) stringValues[index] = pools.strings[index]
        stringKeys = stringKeys.atLeast(stringCount)
        val stringMap = order(strings, stringCount) { order -> sortStrings(order, stringCount, stringValues, stringKeys) }
        stringValues.fill(null, 0, stringCount)

        val descriptors = pools.typeDescriptor
        val typeMap = order(types, typeCount) { order ->
            if (prefersComparison(typeCount, stringCount)) {
                sortIndices(order, typeCount, scratch) { left, right -> stringMap[descriptors[left]] - stringMap[descriptors[right]] }
            } else {
                identity(typeCount)
                countingSort(scratch, order, typeCount, counts, stringCount) { stringMap[descriptors[it]] }
            }
        }
        val protoMap = order(protos, protoCount) { order ->
            sortIndices(order, protoCount, scratch) { left, right -> compareProtos(pools, typeMap, left, right) }
        }
        val fieldClass = pools.fieldClass
        val fieldName = pools.fieldName
        val fieldType = pools.fieldType
        val fieldMap = order(fields, pools.fields.size) { order ->
            byKeys(
                order,
                pools.fields.size,
                typeCount,
                { typeMap[fieldClass[it]] },
                stringCount,
                { stringMap[fieldName[it]] },
                typeCount,
                { typeMap[fieldType[it]] },
            )
        }
        val methodClass = pools.methodClass
        val methodName = pools.methodName
        val methodProto = pools.methodProto
        val methodMap = order(methods, pools.methods.size) { order ->
            byKeys(
                order,
                pools.methods.size,
                typeCount,
                { typeMap[methodClass[it]] },
                stringCount,
                { stringMap[methodName[it]] },
                protoCount,
                { protoMap[methodProto[it]] },
            )
        }
        referenceMaps = arrayOf(typeMap, fieldMap, methodMap, protoMap, stringMap)
    }

    fun string(
        index: Int,
    ): String = stringPool[index]

    private inline fun order(
        ordering: Ordering,
        count: Int,
        sort: (IntArray) -> Unit,
    ): IntArray {
        ordering.order = ordering.order.atLeast(count)
        ordering.map = ordering.map.atLeast(count)
        val order = ordering.order
        val map = ordering.map
        sort(order)
        for (position in 0 until count) map[order[position]] = position
        return map
    }

    // Stable passes from the least significant key, so the first key decides and later ones break ties.
    private inline fun byKeys(
        order: IntArray,
        count: Int,
        firstRange: Int,
        crossinline first: (Int) -> Int,
        secondRange: Int,
        crossinline second: (Int) -> Int,
        thirdRange: Int,
        crossinline third: (Int) -> Int,
    ) {
        if (prefersComparison(count, firstRange + secondRange + thirdRange)) {
            sortIndices(order, count, scratch) { left, right ->
                var diff = first(left) - first(right)
                if (diff == 0) diff = second(left) - second(right)
                if (diff == 0) diff = third(left) - third(right)
                diff
            }
            return
        }
        identity(count)
        countingSort(scratch, order, count, counts, thirdRange, third)
        countingSort(order, scratch, count, counts, secondRange, second)
        countingSort(scratch, order, count, counts, firstRange, first)
    }

    // Counting passes cost the key range as well as the count, so a small pool sorts by comparison instead.
    private fun prefersComparison(
        count: Int,
        range: Int,
    ): Boolean = count <= SMALL_POOL || count * 8 < range

    private fun identity(
        count: Int,
    ) {
        for (index in 0 until count) scratch[index] = index
    }

    private fun compareProtos(
        pools: Pools,
        typeMap: IntArray,
        left: Int,
        right: Int,
    ): Int {
        val diff = typeMap[pools.protoReturn[left]] - typeMap[pools.protoReturn[right]]
        if (diff != 0) return diff
        val params = pools.protoParams
        val leftStart = pools.protoParamStart[left]
        val rightStart = pools.protoParamStart[right]
        val leftCount = pools.protoParamCount[left]
        val rightCount = pools.protoParamCount[right]
        for (index in 0 until minOf(leftCount, rightCount)) {
            val param = typeMap[params[leftStart + index]] - typeMap[params[rightStart + index]]
            if (param != 0) return param
        }
        return leftCount - rightCount
    }

    private companion object {
        const val SMALL_POOL = 32
    }

    // A pool's indices in file order, and each index's position in it.
    private class Ordering {
        var order = IntArray(64)
        var map = IntArray(64)
    }
}
