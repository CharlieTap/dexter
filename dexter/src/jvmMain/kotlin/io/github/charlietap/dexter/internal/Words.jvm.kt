package io.github.charlietap.dexter.internal

import java.lang.invoke.MethodHandles
import java.nio.ByteOrder

private val longs = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

internal fun ByteArray.longAt(
    index: Int,
): Long = longs.get(this, index) as Long
