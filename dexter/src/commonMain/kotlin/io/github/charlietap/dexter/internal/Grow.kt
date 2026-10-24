@file:Suppress("NOTHING_TO_INLINE")

package io.github.charlietap.dexter.internal

internal inline fun IntArray.grown(
    required: Int,
): IntArray = copyOf(maxOf(size * 2, required))

internal inline fun LongArray.grown(
    required: Int,
): LongArray = copyOf(maxOf(size * 2, required))

internal inline fun ByteArray.grown(
    required: Int,
): ByteArray = copyOf(maxOf(size * 2, required))

internal inline fun IntArray.atLeast(
    required: Int,
): IntArray = if (size >= required) this else IntArray(maxOf(size * 2, required))

internal inline fun LongArray.atLeast(
    required: Int,
): LongArray = if (size >= required) this else LongArray(maxOf(size * 2, required))
