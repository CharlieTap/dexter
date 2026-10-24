package io.github.charlietap.dexter.internal

import app.cash.burst.Burst
import app.cash.burst.burstValues
import io.github.charlietap.dexter.Opcodes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

private fun hex(
    bytes: ByteArray,
    size: Int = bytes.size,
): String = (0 until size).joinToString("") { (bytes[it].toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun sink(
    block: ByteSink.() -> Unit,
): String {
    val sink = ByteSink(1)
    sink.block()
    return hex(sink.bytes, sink.size)
}

@Burst
class PrimitivesTest {

    enum class Adler(
        val input: () -> ByteArray,
        val expected: Int,
    ) {
        EMPTY({ ByteArray(0) }, 1),
        WIKIPEDIA({ "Wikipedia".encodeToByteArray() }, 0x11E60398),

        MILLION_FF({ ByteArray(1_000_000) { -1 } }, 0x3843E1BE),
    }

    @Test
    fun `adler32 matches known values`(
        case: Adler,
    ) {
        val input = case.input()

        assertEquals(case.expected, adler32(input, 0, input.size))
    }

    @Test
    fun `adler32 covers only the given range`() {
        val padded = "xxWikipediayy".encodeToByteArray()

        assertEquals(0x11E60398, adler32(padded, 2, 11))
    }

    enum class Uleb(
        val value: Int,
        val expected: String,
    ) {
        ZERO(0, "00"),
        ONE(1, "01"),
        MAX_1_BYTE(127, "7f"),
        MIN_2_BYTES(128, "8001"),
        MAX_2_BYTES(16383, "ff7f"),
        MIN_3_BYTES(16384, "808001"),
        MAX_3_BYTES(0x1FFFFF, "ffff7f"),
        MIN_4_BYTES(0x200000, "80808001"),
        MAX_4_BYTES(0x0FFFFFFF, "ffffff7f"),
        MIN_5_BYTES(0x10000000, "8080808001"),
        INT_MAX(Int.MAX_VALUE, "ffffffff07"),
        UNSIGNED_MAX(-1, "ffffffff0f"),
    }

    @Test
    fun `uleb128 encodes boundaries`(
        case: Uleb,
    ) {
        val bytes = sink { uleb(case.value) }

        assertEquals(case.expected, bytes)
    }

    @Test
    fun `uleb128 size matches its encoding`(
        case: Uleb,
    ) {
        val size = ulebSize(case.value)

        assertEquals(case.expected.length / 2, size)
    }

    enum class Sleb(
        val value: Int,
        val expected: String,
    ) {
        ZERO(0, "00"),
        ONE(1, "01"),
        MINUS_ONE(-1, "7f"),
        MAX_1_BYTE(63, "3f"),
        MIN_2_BYTES(64, "c000"),
        MIN_1_BYTE(-64, "40"),
        MAX_NEGATIVE_2_BYTES(-65, "bf7f"),
        MAX_2_BYTES(8191, "ff3f"),
        MIN_3_BYTES(8192, "80c000"),
        INT_MIN(Int.MIN_VALUE, "8080808078"),
        INT_MAX(Int.MAX_VALUE, "ffffffff07"),
    }

    @Test
    fun `sleb128 encodes boundaries`(
        case: Sleb,
    ) {
        val bytes = sink { sleb(case.value) }

        assertEquals(case.expected, bytes)
    }

    enum class Mutf8(
        val value: String,
        val expected: String,
    ) {
        NUL("\u0000", "c080"),
        ASCII("A\u0001\u007F", "41017f"),
        TWO_BYTES("\u0080é߿", "c280c3a9dfbf"),
        THREE_BYTES("ࠀ￿", "e0a080efbfbf"),

        SUPPLEMENTARY("😀", "eda0bdedb880"),
        UNPAIRED_HIGH_SURROGATE("\uD800", "eda080"),
        UNPAIRED_LOW_SURROGATE("\uDC00", "edb080"),
    }

    @Test
    fun `modified utf-8 encodes each utf-16 unit`(
        case: Mutf8,
    ) {
        val bytes = sink { mutf8(case.value) }

        assertEquals(case.expected, bytes)
    }

    @Test
    fun `alignment pads with zeros`() {
        val bytes = sink {
            u8(0xFF)
            align(4)
            u32(0x01020304)
            u16(0xBEEF)
            align(4)
        }

        assertEquals("ff00000004030201efbe0000", bytes)
    }

    private val keys = List(1000) { "key$it" }

    private fun Interner<String>.lookup(
        key: String,
        hash: Int = key.hashCode(),
    ): Int = maxOf(find(key, hash) { stored, probe -> stored == probe }, -1)

    private fun Interner<String>.insert(
        key: String,
        hash: Int = key.hashCode(),
    ): Int = add(key, hash, find(key, hash) { stored, probe -> stored == probe })

    private fun filled(): Interner<String> {
        val interner = Interner<String>(2)
        for (key in keys) interner.insert(key)
        return interner
    }

    @Test
    fun `interner finds equal keys after growing`() {
        val interner = filled()

        val found = keys.map { interner.lookup(it.toCharArray().concatToString()) }
        assertEquals(keys.indices.toList(), found)
    }

    @Test
    fun `interner forgets truncated keys`() {
        val interner = filled()
        interner.truncate(400)

        val found = keys.map { interner.lookup(it) }
        assertEquals(keys.indices.map { if (it < 400) it else -1 }, found)
    }

    @Test
    fun `interner reuses truncated indices`() {
        val interner = filled()
        interner.truncate(400)
        for (index in 400 until 1000) interner.insert(keys[index])

        val found = keys.map { interner.lookup(it) }
        assertEquals(keys.indices.toList(), found)
    }

    @Test
    fun `interner separates colliding hashes by equality`() {
        val interner = Interner<String>()
        interner.insert("a", 7)
        interner.insert("b", 7)
        interner.truncate(1)

        assertEquals(listOf(0, -1), listOf(interner.lookup("a", 7), interner.lookup("b", 7)))
    }

    @Test
    fun `sorting is stable`(
        size: Int = burstValues(0, 1, 2, 15, 16, 17, 100, 1000),
    ) {
        val random = Random(size)
        val values = IntArray(size) { random.nextInt(20) }
        val order = IntArray(size + 3)

        sortIndices(order, size, IntArray(size + 3)) { left, right -> values[left] - values[right] }
        assertEquals((0 until size).sortedBy { values[it] }, order.copyOf(size).toList())
    }

    @Test
    fun `strings sort by utf-16 code units`(
        size: Int = burstValues(0, 1, 2, 12, 13, 100, 2000),
    ) {
        val random = Random(size)
        val units = listOf("a", "b", "/", "Entry", "\uFFFF", "\uD83D\uDE00", "\u0000")
        val strings = List(size) { index -> "Lchasm/jit/" + List(random.nextInt(4)) { units.random(random) }.joinToString("") + index }
        val order = IntArray(size)

        sortStrings(order, size, strings.toTypedArray<String?>(), LongArray(size))
        assertEquals(strings.sortedWith(::compareUtf16), order.map { strings[it] })
    }

    @Test
    fun `instruction sizes match the opcode formats`() {
        val expected = (0 until 256).map { opcode -> Opcodes.formats[opcode]?.let(Opcodes::units) ?: 0 }

        assertEquals(expected, (0 until 256).map(::instructionUnits))
    }

    @Test
    fun `strings that prefix each other sort shortest first`() {
        val strings = listOf("abcd", "", "abc", "a\u0000", "ab", "abcde", "a", "\u0000", "abcdefg", "abd", "b")
        val order = IntArray(strings.size)

        sortStrings(order, strings.size, strings.toTypedArray<String?>(), LongArray(strings.size))
        assertEquals(strings.sortedWith(::compareUtf16), order.map { strings[it] })
    }

    @Test
    fun `counting sort is stable`() {
        val values = intArrayOf(2, 0, 2, 1, 0)
        val sorted = IntArray(5)

        countingSort(intArrayOf(0, 1, 2, 3, 4), sorted, 5, IntArray(4), 3) { values[it] }
        assertEquals(listOf(1, 4, 3, 0, 2), sorted.toList())
    }

    enum class Utf16Order(
        val left: String,
        val right: String,
        val sign: Int,
    ) {
        BY_CODE_UNIT_NOT_CODE_POINT("￿", "😀", 1),
        PREFIX_FIRST("ab", "abc", -1),
        EQUAL("abc", "abc", 0),
        UPPER_BEFORE_LOWER("B", "a", -1),
    }

    @Test
    fun `strings compare by utf-16 code units`(
        case: Utf16Order,
    ) {
        val sign = compareUtf16(case.left, case.right).coerceIn(-1, 1)

        assertEquals(case.sign, sign)
    }
}
