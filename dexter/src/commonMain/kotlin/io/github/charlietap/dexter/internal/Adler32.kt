package io.github.charlietap.dexter.internal

internal expect fun adler32(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Int
