package io.github.charlietap.dexter.internal

internal class Interner<T : Any>(
    initialCapacity: Int = 64,
) {

    private var table = IntArray(tableSizeFor(initialCapacity)) { EMPTY }
    private var mask = table.size - 1

    private var entries: Array<Any?> = arrayOfNulls(initialCapacity)
    private var hashes = IntArray(initialCapacity)
    private var slots = IntArray(initialCapacity)

    var size = 0
        private set

    inline fun find(
        key: T,
        hash: Int,
        same: (T, T) -> Boolean,
    ): Int {
        var slot = mix(hash) and mask
        while (true) {
            val index = table[slot]
            if (index == EMPTY) return -(slot + 1)
            val entry = get(index)
            if (entry === key || (hashes[index] == hash && same(entry, key))) return index
            slot = (slot + 1) and mask
        }
    }

    fun add(
        key: T,
        hash: Int,
        miss: Int,
    ): Int {
        val index = size
        if (index == entries.size) growEntries()
        entries[index] = key
        hashes[index] = hash
        if ((index + 1) * 2 > table.size) {
            rehash(table.size * 2)
            place(index, hash)
        } else {
            val slot = -(miss + 1)
            table[slot] = index
            slots[index] = slot
        }
        size = index + 1
        return index
    }

    @Suppress("UNCHECKED_CAST")
    operator fun get(
        index: Int,
    ): T = entries[index] as T

    fun truncate(
        newSize: Int,
    ) {
        var index = size - 1
        while (index >= newSize) {
            table[slots[index]] = EMPTY
            entries[index] = null
            index--
        }
        if (newSize < size) size = newSize
    }

    fun clear() {
        if (size == 0) return
        table.fill(EMPTY)
        entries.fill(null, 0, size)
        size = 0
    }

    private fun place(
        index: Int,
        hash: Int,
    ) {
        var slot = mix(hash) and mask
        while (table[slot] != EMPTY) slot = (slot + 1) and mask
        table[slot] = index
        slots[index] = slot
    }

    private fun growEntries() {
        val capacity = entries.size * 2
        entries = entries.copyOf(capacity)
        hashes = hashes.copyOf(capacity)
        slots = slots.copyOf(capacity)
    }

    private fun rehash(
        capacity: Int,
    ) {
        table = IntArray(capacity) { EMPTY }
        mask = capacity - 1
        for (index in 0 until size) place(index, hashes[index])
    }

    companion object {
        const val EMPTY = -1

        fun tableSizeFor(
            capacity: Int,
        ): Int {
            var size = 16
            while (size < capacity * 2) size *= 2
            return size
        }

        fun mix(
            hash: Int,
        ): Int {
            val spread = hash * -0x61c88647
            return spread xor (spread ushr 16)
        }
    }
}
