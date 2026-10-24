package io.github.charlietap.dexter.internal

internal class Bits {
    private var words = LongArray(4)

    private var used = 0

    operator fun get(
        index: Int,
    ): Boolean {
        val word = index ushr 6
        return word < words.size && (words[word] ushr (index and 63)) and 1L != 0L
    }

    fun set(
        index: Int,
    ) {
        val word = index ushr 6
        if (word >= words.size) words = words.grown(word + 1)
        words[word] = words[word] or (1L shl (index and 63))
        if (word >= used) used = word + 1
    }

    fun clear() {
        words.fill(0L, 0, used)
        used = 0
    }
}
