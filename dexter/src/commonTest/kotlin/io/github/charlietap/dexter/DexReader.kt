package io.github.charlietap.dexter

class DexReader(
    val bytes: ByteArray,
) {

    fun u8(
        at: Int,
    ): Int = bytes[at].toInt() and 0xFF

    fun u16(
        at: Int,
    ): Int = u8(at) or (u8(at + 1) shl 8)

    fun u32(
        at: Int,
    ): Int = u16(at) or (u16(at + 2) shl 16)

    val fileSize = u32(32)
    val mapOff = u32(52)
    val stringCount = u32(56)
    val stringIdsOff = u32(60)
    val typeCount = u32(64)
    val typeIdsOff = u32(68)
    val protoCount = u32(72)
    val protoIdsOff = u32(76)
    val fieldCount = u32(80)
    val fieldIdsOff = u32(84)
    val methodCount = u32(88)
    val methodIdsOff = u32(92)
    val classCount = u32(96)
    val classDefsOff = u32(100)

    val strings: List<String> = List(stringCount) { readString(u32(stringIdsOff + 4 * it)) }
    val types: List<String> = List(typeCount) { strings[u32(typeIdsOff + 4 * it)] }
    val protos: List<String> = List(protoCount) { index ->
        val at = protoIdsOff + 12 * index
        val params = u32(at + 8)
        val list = if (params == 0) emptyList() else List(u32(params)) { types[u16(params + 4 + 2 * it)] }
        "(${list.joinToString("")})${types[u32(at + 4)]}"
    }
    val fields: List<String> = List(fieldCount) { index ->
        val at = fieldIdsOff + 8 * index
        "${types[u16(at)]}->${strings[u32(at + 4)]}:${types[u16(at + 2)]}"
    }
    val methods: List<String> = List(methodCount) { index ->
        val at = methodIdsOff + 8 * index
        "${types[u16(at)]}->${strings[u32(at + 4)]}${protos[u16(at + 2)]}"
    }

    class Member(
        val index: Int,
        val access: Int,
        val codeOff: Int,
    )

    // Each map entry's (type, size, offset).
    val mapEntries: List<Triple<Int, Int, Int>> = List(u32(mapOff)) { index ->
        val at = mapOff + 4 + 12 * index
        Triple(u16(at), u32(at + 4), u32(at + 8))
    }

    // Each method handle as "kind member", with the kind as its DEX number.
    val methodHandles: List<String> = mapEntries.firstOrNull { it.first == 0x0008 }?.let { (_, size, offset) ->
        List(size) { index ->
            val at = offset + 8 * index
            val kind = u16(at)
            val member = u16(at + 4)
            "$kind ${if (kind <= 3) fields[member] else methods[member]}"
        }
    } ?: emptyList()

    class ClassDef(
        val type: String,
        val access: Int,
        val superclass: String,
        val interfaces: List<String>,
        val staticFields: List<Member>,
        val directMethods: List<Member>,
        val virtualMethods: List<Member>,
        val sourceFile: String?,
    )

    val classes: List<ClassDef> = List(classCount) { index ->
        val at = classDefsOff + 32 * index
        val interfacesOff = u32(at + 12)
        val interfaces = if (interfacesOff == 0) emptyList() else List(u32(interfacesOff)) { types[u16(interfacesOff + 4 + 2 * it)] }
        val dataOff = u32(at + 24)
        val lists = Array(4) { mutableListOf<Member>() }
        if (dataOff != 0) {
            var cursor = dataOff

            fun uleb(): Int {
                var result = 0
                var shift = 0
                while (true) {
                    val byte = u8(cursor++)
                    result = result or ((byte and 0x7F) shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                }
            }
            val sizes = IntArray(4) { uleb() }
            for (list in 0 until 4) {
                var index = 0
                repeat(sizes[list]) {
                    index += uleb()
                    val access = uleb()
                    val code = if (list >= 2) uleb() else 0
                    lists[list].add(Member(index, access, code))
                }
            }
        }
        val source = u32(at + 16)
        val sourceFile = if (source == -1) null else strings[source]
        ClassDef(types[u32(at)], u32(at + 4), types[u32(at + 8)], interfaces, lists[0], lists[2], lists[3], sourceFile)
    }

    class CodeItem(
        val registers: Int,
        val ins: Int,
        val outs: Int,
        val insns: IntArray,
        val tries: List<Try>,
        val debugInfo: DebugInfo?,
    )

    class DebugInfo(
        val parameters: Int,
        val lines: List<Pair<Int, Int>>,
    )

    class Try(
        val start: Int,
        val count: Int,
        val handlers: List<Pair<String, Int>>,
    )

    fun method(
        signature: String,
    ): CodeItem {
        val index = methods.indexOf(signature)
        require(index >= 0) { "No method $signature in ${methods.joinToString()}" }
        val member = classes.flatMap { it.directMethods + it.virtualMethods }.single { it.index == index }
        return code(member.codeOff)
    }

    fun code(
        offset: Int,
    ): CodeItem {
        val units = u32(offset + 12)
        val insns = IntArray(units) { u16(offset + 16 + 2 * it) }
        val triesSize = u16(offset + 6)
        var triesAt = offset + 16 + 2 * units
        if (triesSize > 0 && units % 2 == 1) triesAt += 2
        val handlersOff = triesAt + 8 * triesSize
        val tries = List(triesSize) { index ->
            val at = triesAt + 8 * index
            var cursor = handlersOff + u16(at + 6)

            fun uleb(): Int {
                var result = 0
                var shift = 0
                while (true) {
                    val byte = u8(cursor++)
                    result = result or ((byte and 0x7F) shl shift)
                    if (byte and 0x80 == 0) return result
                    shift += 7
                }
            }
            var size = 0
            var shift = 0
            var byte: Int
            do {
                byte = u8(cursor++)
                size = size or ((byte and 0x7F) shl shift)
                shift += 7
            } while (byte and 0x80 != 0)
            if (shift < 32 && byte and 0x40 != 0) size = size or (-1 shl shift)
            val handlers = List(if (size < 0) -size else size) { types[uleb()] to uleb() }
            // A catch-all handler is listed last with the type "*".
            Try(u32(at), u16(at + 4), if (size <= 0) handlers + ("*" to uleb()) else handlers)
        }
        val debugInfoOff = u32(offset + 8)
        val debugInfo = if (debugInfoOff == 0) null else debugInfo(debugInfoOff)
        return CodeItem(u16(offset), u16(offset + 2), u16(offset + 4), insns, tries, debugInfo)
    }

    // Runs the debug_info_item state machine, collecting (address, line) for each position.
    private fun debugInfo(
        offset: Int,
    ): DebugInfo {
        var cursor = offset

        fun uleb(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = u8(cursor++)
                result = result or ((byte and 0x7F) shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
        }

        fun sleb(): Int {
            var result = 0
            var shift = 0
            var byte: Int
            do {
                byte = u8(cursor++)
                result = result or ((byte and 0x7F) shl shift)
                shift += 7
            } while (byte and 0x80 != 0)
            return if (shift < 32 && byte and 0x40 != 0) result or (-1 shl shift) else result
        }
        var line = uleb()
        val parameters = uleb()
        repeat(parameters) { uleb() }
        var address = 0
        val lines = mutableListOf<Pair<Int, Int>>()
        while (true) {
            when (val opcode = u8(cursor++)) {
                0x00 -> return DebugInfo(parameters, lines)
                0x01 -> address += uleb()
                0x02 -> line += sleb()
                in 0x03..0x09 -> error("Unexpected debug opcode $opcode")
                else -> {
                    val adjusted = opcode - 0x0A
                    line += -4 + adjusted % 15
                    address += adjusted / 15
                    lines += address to line
                }
            }
        }
    }

    private fun readString(
        offset: Int,
    ): String {
        var cursor = offset
        var length = 0
        var shift = 0
        while (true) {
            val byte = u8(cursor++)
            length = length or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        val chars = CharArray(length)
        for (index in 0 until length) {
            val first = u8(cursor++)
            chars[index] = when {
                first < 0x80 -> first.toChar()
                first < 0xE0 -> (((first and 0x1F) shl 6) or (u8(cursor++) and 0x3F)).toChar()
                else -> {
                    val second = u8(cursor++)
                    val third = u8(cursor++)
                    (((first and 0x0F) shl 12) or ((second and 0x3F) shl 6) or (third and 0x3F)).toChar()
                }
            }
        }
        return chars.concatToString()
    }
}

internal fun instructionStarts(
    insns: IntArray,
): List<Int> {
    val starts = mutableListOf<Int>()
    var address = 0
    while (address < insns.size) {
        starts.add(address)
        val unit = insns[address]
        address += when (unit) {
            0x0100 -> 4 + insns[address + 1] * 2
            0x0200 -> 2 + insns[address + 1] * 4
            0x0300 -> 4 + (insns[address + 1] * (insns[address + 2] or (insns[address + 3] shl 16)) + 1) / 2
            else -> Opcodes.units(checkNotNull(Opcodes.formats[unit and 0xFF]) { "Unknown opcode ${unit and 0xFF}" })
        }
    }
    return starts
}

internal fun hex(
    units: IntArray,
): String = units.joinToString(" ") { it.toString(16).padStart(4, '0') }

internal fun units(
    vararg values: Int,
): IntArray = values
