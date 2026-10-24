package io.github.charlietap.dexter

import app.cash.burst.Burst
import io.github.charlietap.dexter.internal.adler32
import io.github.charlietap.dexter.internal.compareUtf16
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@Burst
class FileTest {

    @Test
    fun `adapter matches the reviewed file byte for byte`() {
        val bytes = Samples.adapter()

        assertEquals(ADAPTER_HEX, bytes.toHex())
    }

    enum class Header(
        val expected: (ByteArray, DexReader) -> Int,
        val read: (DexReader) -> Int,
    ) {
        FILE_SIZE({ bytes, _ -> bytes.size }, { it.fileSize }),
        HEADER_SIZE({ _, _ -> 0x70 }, { it.u32(36) }),
        ENDIAN_TAG({ _, _ -> 0x12345678 }, { it.u32(40) }),
        LINK_SIZE({ _, _ -> 0 }, { it.u32(44) }),
        LINK_OFFSET({ _, _ -> 0 }, { it.u32(48) }),
        DATA_OFFSET({ _, reader -> reader.classDefsOff + 32 * reader.classCount }, { it.u32(108) }),
        DATA_SIZE({ bytes, reader -> bytes.size - reader.u32(108) }, { it.u32(104) }),
        MAP_AT_END({ bytes, reader -> bytes.size - 4 - 12 * reader.u32(reader.mapOff) }, { it.mapOff }),
        CHECKSUM({ bytes, _ -> adler32(bytes, 12, bytes.size) }, { it.u32(8) }),
    }

    @Test
    fun `header fields are consistent`(
        case: Header,
    ) {
        val bytes = Samples.hierarchy()
        val reader = DexReader(bytes)

        assertEquals(case.expected(bytes, reader), case.read(reader))
    }

    @Test
    fun `the signature is left zeroed`() {
        val bytes = Samples.hierarchy()

        assertContentEquals(ByteArray(20), bytes.copyOfRange(12, 32))
    }

    @Test
    fun `magic is dex 035`() {
        val bytes = Samples.hierarchy()

        assertContentEquals("dex\n035\u0000".encodeToByteArray(), bytes.copyOfRange(0, 8))
    }

    @Test
    fun `a reused workspace writes the same bytes`() {
        val dexter = Dexter()
        val first = Samples.hierarchy(dexter)
        Samples.big(dexter)
        Samples.adapter(dexter)

        assertContentEquals(first, Samples.hierarchy(dexter))
    }

    @Test
    fun `separate workspaces write the same bytes`() {
        val bytes = Samples.hierarchy(Dexter())

        assertContentEquals(bytes, Samples.hierarchy(Dexter()))
    }

    enum class SortedSection(
        val keys: (DexReader) -> List<Comparable<*>>,
    ) {
        STRINGS({ reader -> reader.strings.map(::Utf16) }),
        TYPES({ reader -> reader.types.map { reader.strings.indexOf(it) } }),
        FIELDS({ reader -> List(reader.fieldCount) { ids(reader, reader.fieldIdsOff + 8 * it) } }),
        METHODS({ reader -> List(reader.methodCount) { ids(reader, reader.methodIdsOff + 8 * it) } }),
    }

    @Test
    fun `id sections are sorted and distinct`(
        case: SortedSection,
    ) {
        val keys = case.keys(DexReader(Samples.hierarchy()))

        assertEquals(keys.sortedWith(compareBy { it }).distinct(), keys)
    }

    @Test
    fun `supertypes come before the classes that extend them`() {
        val reader = DexReader(Samples.hierarchy())

        assertEquals(listOf("Lchasm/Base;", "Lchasm/Holder;"), reader.classes.map { it.type })
    }

    @Test
    fun `static fields are sorted by index`() {
        val reader = DexReader(Samples.hierarchy())

        val fields = reader.classes[1].staticFields.map { reader.fields[it.index] }
        assertEquals(listOf("Lchasm/Holder;->K:[Ljava/lang/Object;", "Lchasm/Holder;->count:J"), fields)
    }

    @Test
    fun `direct methods are static private or constructors sorted by index`() {
        val reader = DexReader(Samples.hierarchy())

        val methods = reader.classes[1].directMethods.map { reader.methods[it.index] }
        val expected = listOf(
            "Lchasm/Holder;-><clinit>()V",
            "Lchasm/Holder;-><init>()V",
            "Lchasm/Holder;->mix(IJD)I",
            "Lchasm/Holder;->\u00e9t\u00e9\uD83D\uDE00()V",
        )
        assertEquals(expected, methods)
    }

    @Test
    fun `other instance methods are virtual`() {
        val reader = DexReader(Samples.hierarchy())

        val methods = reader.classes[1].virtualMethods.map { reader.methods[it.index] }
        assertEquals(listOf("Lchasm/Holder;->switchy(I)I"), methods)
    }

    @Test
    fun `interfaces are listed`() {
        val reader = DexReader(Samples.adapter())

        assertEquals(listOf("Lchasm/CompiledFunction;"), reader.classes.single().interfaces)
    }

    @Test
    fun `an empty file is a header and a two entry map`() {
        val bytes = Dexter().assemble { }

        assertEquals(0x70 + 4 + 2 * 12, bytes.size)
    }

    @Test
    fun `a class without members has no class data`() {
        val reader = DexReader(Dexter().assemble { addClass(T, DexType.Object) })

        assertEquals(0, reader.u32(reader.classDefsOff + 24))
    }

    @Test
    fun `a class has no source file`() {
        val reader = DexReader(Dexter().assemble { addClass(T, DexType.Object) })

        assertEquals(-1, reader.u32(reader.classDefsOff + 16))
    }

    @Test
    fun `equal type lists are written once`() {
        val bytes = Dexter().assemble {
            val builder = addClass(T, DexType.Object, OTHER)
            builder.staticMethod(DexMethod(T, "a", DexType.Void, OTHER), MemberAccess.Public, 1) { returnVoid() }
            builder.staticMethod(DexMethod(T, "b", DexType.Int, OTHER), MemberAccess.Public, 1) {
                const(v(0), 0)
                returnValue(v(0))
            }
        }
        val reader = DexReader(bytes)

        val offsets = List(reader.protoCount) { reader.u32(reader.protoIdsOff + 12 * it + 8) } + reader.u32(reader.classDefsOff + 12)
        assertEquals(1, offsets.distinct().size)
    }
}

private class Utf16(
    val value: String,
) : Comparable<Utf16> {
    override fun compareTo(
        other: Utf16,
    ): Int = compareUtf16(value, other.value)

    override fun equals(
        other: Any?,
    ): Boolean = other is Utf16 && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

private fun ids(
    reader: DexReader,
    at: Int,
): Comparable<*> = Triple(reader.u16(at), reader.u32(at + 4), reader.u16(at + 2)).let { (a, b, c) -> (a.toLong() shl 40) or (b.toLong() shl 16) or c.toLong() }

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** [Samples.adapter], reviewed with dexdump 36.0.0, with the signature then zeroed. */
private const val ADAPTER_HEX =
    "6465780a303335005269d10a0000000000000000000000000000000000000000" +
        "4c03000070000000785634120000000000000000c40200001200000070000000" +
        "0a000000b800000004000000e000000000000000000000000600000010010000" +
        "0100000040010000ec01000060010000ea010000f2010000f5010000f9010000" +
        "fc01000003020000130200002d0200003e0200004d0200006102000081020000" +
        "840200008a0200008e020000a2020000a9020000ad0200000100000003000000" +
        "05000000060000000700000008000000090000000a0000000b0000000d000000" +
        "0200000000000000c40100000400000001000000cc0100000b00000008000000" +
        "000000000c00000008000000d8010000020002000e000000020000000f000000" +
        "0200010010000000050002000000000005000300110000000600020000000000" +
        "050000001100000006000000e4010000ffffffff00000000b502000000000000" +
        "010001000100000000000000040000007010050000000e000b00040006000100" +
        "000000001800000007807110010000000a01d0a6000045020906d0a601004504" +
        "09067706020000000b024c02090a0e007100000000000e000000000013000100" +
        "0101071401000000040000000400000004000000010001000300000004000900" +
        "00000000010000000300063c696e69743e0001490002494c00014a00054a4c49" +
        "4a4a000e4c636861736d2f4272696467653b00184c636861736d2f436f6d7069" +
        "6c656446756e6374696f6e3b000f4c636861736d2f436f6e746578743b000d4c" +
        "636861736d2f456e7472793b00124c6a6176612f6c616e672f4f626a6563743b" +
        "001e4c6a6176612f6c616e672f537461636b4f766572666c6f774572726f723b" +
        "0001560004564c4c4900025b4a001263616c6c537461636b4578686175737465" +
        "6400056465707468000266300006696e766f6b65000000010103818004e00204" +
        "01f802000b000000000000000100000000000000010000001200000070000000" +
        "020000000a000000b80000000300000004000000e00000000500000006000000" +
        "1001000006000000010000004001000001200000020000006001000001100000" +
        "04000000c40100000220000012000000ea0100000020000001000000b5020000" +
        "0010000001000000c4020000"
