package io.github.charlietap.dexter

import app.cash.burst.Burst
import app.cash.burst.burstValues
import io.github.charlietap.dexter.internal.adler32
import java.util.zip.Adler32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Burst
class JvmCrossCheckTest {

    @Test
    fun `header checksum matches the jdk`(
        sample: Sample,
    ) {
        val bytes = sample.bytes()

        val adler = Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt()
        assertEquals(adler, DexReader(bytes).u32(8))
    }

    @Test
    fun `adler32 matches the jdk on random input`(
        length: Int = burstValues(0, 1, 7, 8, 9, 3799, 3800, 3801, 100_000),
    ) {
        val bytes = Random(length).nextBytes(length)

        assertEquals(Adler32().apply { update(bytes) }.value.toInt(), adler32(bytes, 0, length))
    }

    @Test
    fun `java callers cannot change a method's parameters`() {
        val method = DexMethod(T, "m", DexType.Void, DexType.Int, DexType.Int)

        @Suppress("UNCHECKED_CAST", "PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val javaList = method.parameters as java.util.List<ValueType>
        assertFailsWith<UnsupportedOperationException> { javaList.set(0, DexType.Long) }
    }
}
