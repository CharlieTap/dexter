package io.github.charlietap.dexter.internal

internal inline fun sortIndices(
    order: IntArray,
    count: Int,
    scratch: IntArray,
    crossinline compare: (Int, Int) -> Int,
) {
    for (index in 0 until count) order[index] = index
    val run = 16
    var start = 0
    while (start < count) {
        val end = minOf(start + run, count)
        for (next in start + 1 until end) {
            val value = order[next]
            var at = next - 1
            while (at >= start && compare(order[at], value) > 0) {
                order[at + 1] = order[at]
                at--
            }
            order[at + 1] = value
        }
        start = end
    }
    var from = order
    var to = scratch
    var width = run
    while (width < count) {
        var left = 0
        while (left < count) {
            val mid = minOf(left + width, count)
            val right = minOf(left + 2 * width, count)
            var leftAt = left
            var rightAt = mid
            var outAt = left
            while (leftAt < mid && rightAt < right) {
                to[outAt++] = if (compare(from[rightAt], from[leftAt]) < 0) from[rightAt++] else from[leftAt++]
            }
            while (leftAt < mid) to[outAt++] = from[leftAt++]
            while (rightAt < right) to[outAt++] = from[rightAt++]
            left = right
        }
        val swap = from
        from = to
        to = swap
        width *= 2
    }
    if (from !== order) from.copyInto(order, 0, 0, count)
}

internal inline fun countingSort(
    from: IntArray,
    to: IntArray,
    count: Int,
    counts: IntArray,
    range: Int,
    key: (Int) -> Int,
) {
    counts.fill(0, 0, range + 1)
    for (index in 0 until count) counts[key(from[index]) + 1]++
    for (bucket in 1..range) counts[bucket] += counts[bucket - 1]
    for (index in 0 until count) {
        val value = from[index]
        to[counts[key(value)]++] = value
    }
}

internal fun sortStrings(
    order: IntArray,
    count: Int,
    strings: Array<String?>,
    keys: LongArray,
) {
    for (index in 0 until count) {
        order[index] = index
        keys[index] = key(strings[index]!!, 0)
    }
    sortStrings(order, keys, 0, count, 0, strings)
}

private fun sortStrings(
    order: IntArray,
    keys: LongArray,
    from: Int,
    to: Int,
    depth: Int,
    strings: Array<String?>,
) {
    var low = from
    var high = to
    var unit = depth
    while (high - low > 1) {
        if (high - low <= SMALL_PARTITION) {
            insertionSort(order, keys, low, high, unit, strings)
            return
        }
        val pivot = keys[(low + high) ushr 1]
        var less = low
        var greater = high - 1
        var at = low
        while (at <= greater) {
            val current = keys[at]
            when {
                current < pivot -> swap(order, keys, less++, at++)
                current > pivot -> swap(order, keys, at, greater--)
                else -> at++
            }
        }
        sortStrings(order, keys, low, less, unit, strings)
        sortStrings(order, keys, greater + 1, high, unit, strings)
        if (ended(pivot)) return
        low = less
        high = greater + 1
        unit += KEY_UNITS
        for (index in low until high) keys[index] = key(strings[order[index]]!!, unit)
    }
}

private fun insertionSort(
    order: IntArray,
    keys: LongArray,
    from: Int,
    to: Int,
    depth: Int,
    strings: Array<String?>,
) {
    for (next in from + 1 until to) {
        val value = order[next]
        val key = keys[next]
        val string = strings[value]!!
        var at = next - 1
        while (at >= from && after(keys[at], key, strings[order[at]]!!, string, depth)) {
            order[at + 1] = order[at]
            keys[at + 1] = keys[at]
            at--
        }
        order[at + 1] = value
        keys[at + 1] = key
    }
}

private fun after(
    leftKey: Long,
    rightKey: Long,
    left: String,
    right: String,
    depth: Int,
): Boolean =
    when {
        leftKey != rightKey -> leftKey > rightKey
        ended(leftKey) -> false
        else -> compareUtf16(left, right, depth + KEY_UNITS) > 0
    }

private fun key(
    string: String,
    depth: Int,
): Long {
    val length = string.length
    val first = if (depth < length) string[depth].code + 1 else 0
    val second = if (depth + 1 < length) string[depth + 1].code + 1 else 0
    val third = if (depth + 2 < length) string[depth + 2].code + 1 else 0
    return (first.toLong() shl 34) or (second.toLong() shl 17) or third.toLong()
}

private fun ended(
    key: Long,
): Boolean = key and UNIT_MASK == 0L

private fun swap(
    order: IntArray,
    keys: LongArray,
    first: Int,
    second: Int,
) {
    val value = order[first]
    order[first] = order[second]
    order[second] = value
    val key = keys[first]
    keys[first] = keys[second]
    keys[second] = key
}

private const val SMALL_PARTITION = 12
private const val KEY_UNITS = 3
private const val UNIT_MASK = (1L shl 17) - 1

internal fun compareUtf16(
    left: String,
    right: String,
    from: Int = 0,
): Int {
    val length = minOf(left.length, right.length)
    for (index in from until length) {
        val diff = left[index].code - right[index].code
        if (diff != 0) return diff
    }
    return left.length - right.length
}
