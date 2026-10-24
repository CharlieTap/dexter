package io.github.charlietap.dexter.internal.write

import io.github.charlietap.dexter.internal.ClassTable

internal fun classOrder(
    classes: ClassTable,
    typeCount: Int,
): IntArray {
    val count = classes.count
    val classOfType = IntArray(typeCount) { -1 }
    for (index in 0 until count) classOfType[classes.type[index]] = index
    val state = IntArray(count)
    val order = IntArray(count)
    var size = 0
    val stack = IntArray(count * 2)
    for (root in 0 until count) {
        if (state[root] != UNSEEN) continue
        stack[0] = root
        stack[1] = 0
        state[root] = OPEN
        var top = 1
        while (top > 0) {
            val current = stack[(top - 1) * 2]
            val next = stack[(top - 1) * 2 + 1]
            if (next == 1 + classes.interfaceCount[current]) {
                state[current] = DONE
                order[size++] = current
                top--
                continue
            }
            stack[(top - 1) * 2 + 1] = next + 1
            val typeIndex = if (next == 0) classes.superclass[current] else classes.interfaces[classes.interfaceStart[current] + next - 1]
            val dependency = classOfType[typeIndex]
            if (dependency < 0 || state[dependency] == DONE) continue
            check(state[dependency] == UNSEEN) { "Class hierarchy has a cycle" }
            state[dependency] = OPEN
            stack[top * 2] = dependency
            stack[top * 2 + 1] = 0
            top++
        }
    }
    return order
}

private const val UNSEEN = 0
private const val OPEN = 1
private const val DONE = 2
