package io.github.charlietap.dexter.internal.code

import io.github.charlietap.dexter.Label
import io.github.charlietap.dexter.Labels
import io.github.charlietap.dexter.internal.grown
import kotlin.random.Random

internal class MethodLabels {

    private var token = Random.nextLong() and TOKEN_MASK
    private var base = 0L
    private var addresses = IntArray(64)

    var count = 0
        private set

    fun reset() {
        token = (token + 1) and TOKEN_MASK
        base = token shl 24
        count = 0
    }

    fun create(): Label {
        val index = count
        check(index < MAX_LABELS) { "Too many labels in one method" }
        if (index == addresses.size) addresses = addresses.grown(index + 1)
        addresses[index] = UNBOUND
        count = index + 1
        return Label(base or index.toLong())
    }

    fun block(
        size: Int,
    ): Labels {
        require(size >= 0) { "Negative label count: $size" }
        val start = count
        check(size <= MAX_LABELS - start) { "Too many labels in one method" }
        val end = start + size
        if (end > addresses.size) addresses = addresses.grown(end)
        addresses.fill(UNBOUND, start, end)
        count = end
        return Labels(base or start.toLong(), size)
    }

    fun indexOf(
        label: Label,
    ): Int {
        val id = label.id
        val index = (id and 0xFFFFFF).toInt()
        require(id ushr 24 == token && index < count) { "Label belongs to another method" }
        return index
    }

    fun bind(
        index: Int,
        address: Int,
    ) {
        check(addresses[index] == UNBOUND) { "Label is already bound" }
        addresses[index] = address
    }

    fun address(
        index: Int,
    ): Int = addresses[index]

    fun isBound(
        index: Int,
    ): Boolean = addresses[index] != UNBOUND

    fun remap(
        map: AddressMap,
    ) {
        for (label in 0 until count) {
            val address = addresses[label]
            if (address != UNBOUND) addresses[label] = map.map(address)
        }
    }

    companion object {
        const val UNBOUND = -1
        private const val MAX_LABELS = 1 shl 24
        private const val TOKEN_MASK = (1L shl 40) - 1
    }
}

internal fun interface AddressMap {
    fun map(
        address: Int,
    ): Int
}
