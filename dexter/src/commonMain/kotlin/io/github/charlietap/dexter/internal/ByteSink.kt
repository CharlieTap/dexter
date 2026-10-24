package io.github.charlietap.dexter.internal

internal class ByteSink(
    initialCapacity: Int = 4096,
) {

    var bytes = ByteArray(initialCapacity)
        private set

    var size = 0

    fun reset() {
        size = 0
    }

    fun ensure(
        extra: Int,
    ) {
        val needed = size + extra
        if (needed > bytes.size) bytes = bytes.grown(needed)
    }

    fun u8(
        value: Int,
    ) {
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun u16(
        value: Int,
    ) {
        ensure(2)
        val buffer = bytes
        val at = size
        buffer[at] = value.toByte()
        buffer[at + 1] = (value ushr 8).toByte()
        size = at + 2
    }

    fun u32(
        value: Int,
    ) {
        ensure(4)
        putU32(bytes, size, value)
        size += 4
    }

    fun align(
        alignment: Int,
    ) {
        val padded = (size + alignment - 1) and (alignment - 1).inv()
        ensure(padded - size)
        bytes.fill(0, size, padded)
        size = padded
    }

    fun uleb(
        value: Int,
    ) {
        ensure(5)
        var rest = value
        while (rest ushr 7 != 0) {
            bytes[size++] = ((rest and 0x7F) or 0x80).toByte()
            rest = rest ushr 7
        }
        bytes[size++] = rest.toByte()
    }

    fun sleb(
        value: Int,
    ) {
        ensure(5)
        var rest = value
        while (true) {
            val byte = rest and 0x7F
            rest = rest shr 7
            val done = (rest == 0 && byte and 0x40 == 0) || (rest == -1 && byte and 0x40 != 0)
            bytes[size++] = (if (done) byte else byte or 0x80).toByte()
            if (done) return
        }
    }

    fun copyFrom(
        source: ByteArray,
        start: Int,
        end: Int,
    ) {
        val length = end - start
        ensure(length)
        source.copyInto(bytes, size, start, end)
        size += length
    }

    fun mutf8(
        value: String,
    ) {
        val length = value.length
        ensure(length * 3)
        val buffer = bytes
        var at = size
        for (index in 0 until length) {
            val unit = value[index].code
            when {
                unit in 0x01..0x7F -> buffer[at++] = unit.toByte()
                unit < 0x800 -> {
                    buffer[at++] = (0xC0 or (unit ushr 6)).toByte()
                    buffer[at++] = (0x80 or (unit and 0x3F)).toByte()
                }
                else -> {
                    buffer[at++] = (0xE0 or (unit ushr 12)).toByte()
                    buffer[at++] = (0x80 or ((unit ushr 6) and 0x3F)).toByte()
                    buffer[at++] = (0x80 or (unit and 0x3F)).toByte()
                }
            }
        }
        size = at
    }
}

internal fun putU16(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
}

internal fun putU32(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

internal fun putU64(
    bytes: ByteArray,
    offset: Int,
    value: Long,
) {
    putU32(bytes, offset, value.toInt())
    putU32(bytes, offset + 4, (value ushr 32).toInt())
}

internal fun getU16(
    bytes: ByteArray,
    offset: Int,
): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

internal fun ulebSize(
    value: Int,
): Int =
    when {
        value ushr 7 == 0 -> 1
        value ushr 14 == 0 -> 2
        value ushr 21 == 0 -> 3
        value ushr 28 == 0 -> 4
        else -> 5
    }
