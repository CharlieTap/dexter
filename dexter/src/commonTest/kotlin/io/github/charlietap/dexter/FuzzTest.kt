package io.github.charlietap.dexter

import app.cash.burst.Burst
import app.cash.burst.burstValues
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Burst
class FuzzTest {

    private val other = OTHER
    private val fields = listOf(
        DexField(other, "i", DexType.Int),
        DexField(other, "j", DexType.Long),
        DexField(other, "o", DexType.Object),
        DexField(other, "b", DexType.Byte),
    )
    private val callees = listOf(
        DexMethod(other, "v", DexType.Void),
        DexMethod(other, "i", DexType.Int, DexType.Int),
        DexMethod(other, "j", DexType.Long, DexType.Long, DexType.Int),
        DexMethod(other, "o", DexType.Object, DexType.Object, DexType.Long, DexType.Long),
        DexMethod(other, "five", DexType.Void, DexType.Int, DexType.Int, DexType.Int, DexType.Int, DexType.Int),
    )
    private val binaryOps = listOf(
        BinaryOp.AddInt to "add-int",
        BinaryOp.SubInt to "sub-int",
        BinaryOp.MulInt to "mul-int",
        BinaryOp.DivInt to "div-int",
        BinaryOp.RemInt to "rem-int",
        BinaryOp.AndInt to "and-int",
        BinaryOp.OrInt to "or-int",
        BinaryOp.XorInt to "xor-int",
        BinaryOp.ShlInt to "shl-int",
        BinaryOp.ShrInt to "shr-int",
        BinaryOp.UshrInt to "ushr-int",
        BinaryOp.AddLong to "add-long",
        BinaryOp.ShlLong to "shl-long",
        BinaryOp.XorLong to "xor-long",
        BinaryOp.MulFloat to "mul-float",
        BinaryOp.DivDouble to "div-double",
    )
    private val literalOps = listOf(
        LiteralOp.AddInt to "add-int",
        LiteralOp.SubInt to "sub-int",
        LiteralOp.MulInt to "mul-int",
        LiteralOp.DivInt to "div-int",
        LiteralOp.RemInt to "rem-int",
        LiteralOp.AndInt to "and-int",
        LiteralOp.OrInt to "or-int",
        LiteralOp.XorInt to "xor-int",
        LiteralOp.ShlInt to "shl-int",
        LiteralOp.ShrInt to "shr-int",
        LiteralOp.UshrInt to "ushr-int",
    )
    private val unaryOps = listOf(UnaryOp.NegInt to "neg-int", UnaryOp.NotLong to "not-long", UnaryOp.NegDouble to "neg-double")
    private val conversions = listOf(
        Conversion.IntToLong to "int-to-long",
        Conversion.LongToInt to "long-to-int",
        Conversion.DoubleToLong to "double-to-long",
        Conversion.IntToChar to "int-to-char",
    )
    private val compares = listOf(CompareOp.CmplFloat to "cmpl-float", CompareOp.CmpgDouble to "cmpg-double", CompareOp.CmpLong to "cmp-long")
    private val arrays = listOf(ArrayKind.Int to "", ArrayKind.Wide to "-wide", ArrayKind.Object to "-object", ArrayKind.Short to "-short")
    private val conds = listOf(Cond.Eq to "eq", Cond.Ne to "ne", Cond.Lt to "lt", Cond.Ge to "ge", Cond.Gt to "gt", Cond.Le to "le")
    private val literals = listOf(
        0,
        1,
        -1,
        7,
        8,
        -8,
        -9,
        127,
        128,
        -128,
        -129,
        32767,
        32768,
        -32768,
        -32769,
        0x10000,
        -0x10000,
        Int.MIN_VALUE,
        Int.MAX_VALUE,
    )

    private class Expected(
        val text: String,
        val labels: List<Int> = emptyList(),
    )

    @Test
    fun `random bodies decode to what was asked`(
        reach: Int = burstValues(2, 5, 40, 300, 32767),
    ) {
        val failures = (0 until 80).mapNotNull { seed -> check(seed, reach) }

        assertEquals(emptyList(), failures)
    }

    private fun check(
        seed: Int,
        reach: Int,
    ): String? {
        val random = Random(seed)
        val registers = listOf(16, 40, 300, 65535).random(random)
        val labelCount = random.nextInt(1, 10)
        val length = random.nextInt(1, if (random.nextInt(10) == 0) 3000 else 120)
        val expected = mutableListOf<Expected>()
        val bindAt = IntArray(labelCount) { -1 }
        val method = DexMethod(T, "m", DexType.Void)
        val bytes = Dexter { shortBranchReach = reach }.assemble {
            addClass(T, DexType.Object).staticMethod(method, MemberAccess.Public, registers) {
                val labels = labels(labelCount)
                var lastReturn: DexType? = null

                fun reg(): Int =
                    when (random.nextInt(10)) {
                        in 0..5 -> random.nextInt(16)
                        in 6..7 -> random.nextInt(256)
                        8 -> random.nextInt(registers)
                        else -> random.nextInt(registers - 3, registers + 1)
                    }

                fun lit(): Int = if (random.nextBoolean()) literals.random(random) else random.nextInt(-300, 300)
                repeat(length) {
                    var produced: DexType? = null
                    var touched = true
                    try {
                        when (random.nextInt(17)) {
                            0 -> {
                                val register = reg()
                                val value = lit()
                                const(Reg(register), value)
                                expected += Expected("const v$register $value")
                            }
                            1 -> {
                                val register = reg()
                                val value = when (random.nextInt(3)) {
                                    0 -> lit().toLong()
                                    1 -> lit().toLong() shl 48
                                    else -> random.nextLong()
                                }
                                constWide(Reg(register), value)
                                expected += Expected("const-wide v$register $value")
                            }
                            2 -> {
                                val destination = reg()
                                val source = reg()
                                when (random.nextInt(3)) {
                                    0 -> {
                                        move(Reg(destination), Reg(source))
                                        expected += Expected("move v$destination v$source")
                                    }
                                    1 -> {
                                        moveWide(Reg(destination), Reg(source))
                                        expected += Expected("move-wide v$destination v$source")
                                    }
                                    else -> {
                                        moveObject(Reg(destination), Reg(source))
                                        expected += Expected("move-object v$destination v$source")
                                    }
                                }
                            }
                            3 -> {
                                val (op, name) = binaryOps.random(random)
                                val destination = reg()
                                val left = if (random.nextBoolean()) destination else reg()
                                val right = reg()
                                binary(op, Reg(destination), Reg(left), Reg(right))
                                expected += Expected("$name v$destination v$left v$right")
                            }
                            4 -> {
                                val (op, name) = literalOps.random(random)
                                val destination = reg()
                                val operand = reg()
                                val value = lit()
                                binary(op, Reg(destination), Reg(operand), value)
                                if (name == "sub-int") {
                                    expected += Expected("add-int-lit v$destination v$operand ${-value}")
                                } else {
                                    expected += Expected("$name-lit v$destination v$operand $value")
                                }
                            }
                            5 -> {
                                val destination = reg()
                                val source = reg()
                                if (random.nextBoolean()) {
                                    val (op, name) = unaryOps.random(random)
                                    unary(op, Reg(destination), Reg(source))
                                    expected += Expected("$name v$destination v$source")
                                } else {
                                    val (op, name) = conversions.random(random)
                                    convert(op, Reg(destination), Reg(source))
                                    expected += Expected("$name v$destination v$source")
                                }
                            }
                            6 -> {
                                val (op, name) = compares.random(random)
                                val destination = reg()
                                val left = reg()
                                val right = reg()
                                compare(op, Reg(destination), Reg(left), Reg(right))
                                expected += Expected("$name v$destination v$left v$right")
                            }
                            7 -> {
                                val (kind, suffix) = arrays.random(random)
                                val value = reg()
                                val array = reg()
                                val index = reg()
                                if (random.nextBoolean()) {
                                    arrayGet(kind, Reg(value), Reg(array), Reg(index))
                                    expected += Expected("aget$suffix v$value v$array v$index")
                                } else {
                                    arrayPut(kind, Reg(value), Reg(array), Reg(index))
                                    expected += Expected("aput$suffix v$value v$array v$index")
                                }
                            }
                            8 -> {
                                val field = fields.random(random)
                                val register = reg()
                                val suffix = when (field.type) {
                                    DexType.Long -> "-wide"
                                    DexType.Object -> "-object"
                                    DexType.Byte -> "-byte"
                                    else -> ""
                                }
                                if (random.nextBoolean()) {
                                    getStatic(Reg(register), field)
                                    expected += Expected("sget$suffix v$register $field")
                                } else {
                                    putStatic(Reg(register), field)
                                    expected += Expected("sput$suffix v$register $field")
                                }
                            }
                            9 -> {
                                val callee = callees.random(random)
                                val kind = listOf("static", "virtual", "direct", "interface").random(random)
                                val receiver = kind != "static"
                                val args = List(callee.parameters.size + if (receiver) 1 else 0) { reg() }
                                val words = mutableListOf<Int>()
                                args.forEachIndexed { index, register ->
                                    words += register
                                    val parameter = if (receiver) index - 1 else index
                                    if (parameter >= 0 && callee.parameters[parameter].width == 2) words += register + 1
                                }
                                invoke(kind, callee, args.map(::Reg))
                                expected += Expected("invoke-$kind {${words.joinToString { "v$it" }}} $callee")
                                produced = callee.returnType
                            }
                            10 -> {
                                val callee = callees.random(random)
                                val kind = listOf("static", "virtual", "direct", "interface").random(random)
                                val first = reg()
                                val count = callee.words + if (kind == "static") 0 else 1
                                when (kind) {
                                    "static" -> invokeStaticRange(callee, Reg(first))
                                    "virtual" -> invokeVirtualRange(callee, Reg(first))
                                    "direct" -> invokeDirectRange(callee, Reg(first))
                                    else -> invokeInterfaceRange(callee, Reg(first))
                                }
                                expected += Expected("invoke-$kind {${(first until first + count).joinToString { "v$it" }}} $callee")
                                produced = callee.returnType
                            }
                            11 -> {
                                val register = reg()
                                moveResult(Reg(register))
                                val suffix = when (lastReturn) {
                                    DexType.Long -> "-wide"
                                    DexType.Object -> "-object"
                                    else -> ""
                                }
                                expected += Expected("move-result$suffix v$register")
                            }
                            12 -> {
                                val label = random.nextInt(labelCount)
                                goto(labels[label])
                                expected += Expected("goto", listOf(label))
                            }
                            13 -> {
                                val label = random.nextInt(labelCount)
                                val (cond, name) = conds.random(random)
                                if (random.nextBoolean()) {
                                    val left = reg()
                                    val right = reg()
                                    branch(cond, Reg(left), Reg(right), labels[label])
                                    expected += Expected("if-$name v$left v$right", listOf(label))
                                } else {
                                    val operand = reg()
                                    branchZero(cond, Reg(operand), labels[label])
                                    expected += Expected("if-${name}z v$operand", listOf(label))
                                }
                            }
                            14 -> {
                                val size = random.nextInt(1, 6)
                                val targets = List(size) { random.nextInt(labelCount) }
                                val register = reg()
                                val key = lit()
                                val table = labelTable(key, size, labels[targets[0]])
                                targets.forEachIndexed { slot, label -> table[slot] = labels[label] }
                                packedSwitch(Reg(register), table)
                                expected += Expected("packed-switch v$register $key", targets)
                            }
                            15 -> {
                                val label = random.nextInt(labelCount)
                                if (bindAt[label] < 0) {
                                    bind(labels[label])
                                    bindAt[label] = expected.size
                                } else {
                                    touched = false
                                }
                            }
                            else -> {
                                if (random.nextBoolean()) {
                                    returnVoid()
                                    expected += Expected("return-void")
                                } else {
                                    val register = reg()
                                    throwValue(Reg(register))
                                    expected += Expected("throw v$register")
                                }
                            }
                        }
                        if (touched) lastReturn = produced
                    } catch (_: IllegalArgumentException) {
                    } catch (failure: IllegalStateException) {
                        assertTrue(failure.message!!.startsWith("moveResult") || failure.message!!.startsWith("The preceding"), failure.message)
                    }
                }
                for (label in 0 until labelCount) {
                    if (bindAt[label] < 0) {
                        bind(labels[label])
                        bindAt[label] = expected.size
                    }
                }
                returnVoid()
                expected += Expected("return-void")
            }
        }

        val reader = DexReader(bytes)
        val code = reader.method(method.toString())
        val resolved = expected.map { entry ->
            if (entry.labels.isEmpty()) entry.text else "${entry.text} -> ${entry.labels.map { bindAt[it] }}"
        }
        val decoded = decode(code.insns, reader, expected)
        if (resolved == decoded) return null
        val at = resolved.indices.firstOrNull { it >= decoded.size || resolved[it] != decoded[it] } ?: resolved.size
        return "seed $seed: at $at expected ${resolved.getOrNull(at)}, got ${decoded.getOrNull(at)}"
    }

    private fun CodeEmitter.invoke(
        kind: String,
        callee: DexMethod,
        registers: List<Reg>,
    ) {
        when (kind) {
            "static" -> when (registers.size) {
                0 -> invokeStatic(callee)
                1 -> invokeStatic(callee, registers[0])
                2 -> invokeStatic(callee, registers[0], registers[1])
                3 -> invokeStatic(callee, registers[0], registers[1], registers[2])
                4 -> invokeStatic(callee, registers[0], registers[1], registers[2], registers[3])
                else -> invokeStatic(callee, registers[0], registers[1], registers[2], registers[3], registers[4])
            }
            else -> {
                instance(kind, callee, registers)
            }
        }
    }

    private fun CodeEmitter.instance(
        kind: String,
        callee: DexMethod,
        registers: List<Reg>,
    ) {
        when (kind) {
            "virtual" -> when (registers.size) {
                1 -> invokeVirtual(callee, registers[0])
                2 -> invokeVirtual(callee, registers[0], registers[1])
                3 -> invokeVirtual(callee, registers[0], registers[1], registers[2])
                4 -> invokeVirtual(callee, registers[0], registers[1], registers[2], registers[3])
                else -> invokeVirtual(callee, registers[0], registers[1], registers[2], registers[3], registers[4], registers[5])
            }
            "direct" -> when (registers.size) {
                1 -> invokeDirect(callee, registers[0])
                2 -> invokeDirect(callee, registers[0], registers[1])
                3 -> invokeDirect(callee, registers[0], registers[1], registers[2])
                4 -> invokeDirect(callee, registers[0], registers[1], registers[2], registers[3])
                else -> invokeDirect(callee, registers[0], registers[1], registers[2], registers[3], registers[4], registers[5])
            }
            else -> when (registers.size) {
                1 -> invokeInterface(callee, registers[0])
                2 -> invokeInterface(callee, registers[0], registers[1])
                3 -> invokeInterface(callee, registers[0], registers[1], registers[2])
                4 -> invokeInterface(callee, registers[0], registers[1], registers[2], registers[3])
                else -> invokeInterface(callee, registers[0], registers[1], registers[2], registers[3], registers[4], registers[5])
            }
        }
    }

    private fun decode(
        insns: IntArray,
        reader: DexReader,
        expected: List<Expected>,
    ): List<String> {
        fun signed16(
            value: Int,
        ) = value.toShort().toInt()

        fun signed32(
            at: Int,
        ) = insns[at] or (insns[at + 1] shl 16)
        val texts = mutableListOf<String>()
        val targets = mutableListOf<List<Int>>()
        val eventAt = HashMap<Int, Int>()
        var address = 0
        while (address < insns.size) {
            val unit = insns[address]
            val opcode = unit and 0xFF
            if (opcode == 0) break
            val name = checkNotNull(Opcodes.names[opcode]) { "Unknown opcode $opcode" }
            val format = Opcodes.formats[opcode]!!
            var size = Opcodes.units(format)
            val nibbleA = (unit shr 8) and 0xF
            val nibbleB = unit shr 12
            val byteAA = unit shr 8
            eventAt[address] = texts.size
            var branchTargets = emptyList<Int>()
            val text = when {
                name.startsWith("const-wide") -> "const-wide v$byteAA " + when (name) {
                    "const-wide/16" -> signed16(insns[address + 1]).toLong()
                    "const-wide/32" -> signed32(address + 1).toLong()
                    "const-wide/high16" -> insns[address + 1].toLong() shl 48
                    else -> (signed32(address + 1).toLong() and 0xFFFF_FFFFL) or (signed32(address + 3).toLong() shl 32)
                }
                name == "const/4" -> "const v$nibbleA ${(unit shl 16) shr 28}"
                name.startsWith("const") -> "const v$byteAA " + when (name) {
                    "const/16" -> signed16(insns[address + 1])
                    "const/high16" -> insns[address + 1] shl 16
                    else -> signed32(address + 1)
                }
                name.startsWith("move-result") -> "$name v$byteAA"
                name.startsWith("move") -> name.substringBefore('/') + when (format) {
                    "12x" -> " v$nibbleA v$nibbleB"
                    "22x" -> " v$byteAA v${insns[address + 1]}"
                    else -> " v${insns[address + 1]} v${insns[address + 2]}"
                }
                name.endsWith("/2addr") -> "${name.removeSuffix("/2addr")} v$nibbleA v$nibbleA v$nibbleB"
                format == "22s" -> "${name.removeSuffix("/lit16")}-lit v$nibbleA v$nibbleB ${signed16(insns[address + 1])}"
                format == "22b" -> "${name.removeSuffix("/lit8")}-lit v$byteAA v${insns[address + 1] and 0xFF} ${signed16(insns[address + 1]) shr 8}"
                format == "12x" -> "$name v$nibbleA v$nibbleB"
                format == "23x" -> "$name v$byteAA v${insns[address + 1] and 0xFF} v${insns[address + 1] shr 8}"
                format == "21c" -> "$name v$byteAA ${reader.fields[insns[address + 1]]}"
                format == "35c" -> {
                    val words = listOf(insns[address + 2] and 0xF, (insns[address + 2] shr 4) and 0xF, (insns[address + 2] shr 8) and 0xF, insns[address + 2] shr 12, nibbleA)
                    "$name {${words.take(nibbleB).joinToString { "v$it" }}} ${reader.methods[insns[address + 1]]}"
                }
                format == "3rc" -> {
                    val first = insns[address + 2]
                    "${name.removeSuffix("/range")} {${(first until first + byteAA).joinToString { "v$it" }}} ${reader.methods[insns[address + 1]]}"
                }
                name.startsWith("goto") -> {
                    val offset = when (name) {
                        "goto" -> (unit shl 16) shr 24
                        "goto/16" -> signed16(insns[address + 1])
                        else -> signed32(address + 1)
                    }
                    branchTargets = listOf(address + offset)
                    "goto"
                }
                name.startsWith("if-") -> {
                    val offset = signed16(insns[address + 1])
                    val operands = if (format == "22t") "v$nibbleA v$nibbleB" else "v$byteAA"
                    val inverted = Opcodes.names[opcode xor 1]!!
                    val wanted = expected.getOrNull(texts.size)?.text
                    if (offset == 5 && insns.getOrNull(address + 2) == 0x2A && wanted == "$inverted $operands") {
                        branchTargets = listOf(address + 2 + signed32(address + 3))
                        size = 5
                        "$inverted $operands"
                    } else {
                        branchTargets = listOf(address + offset)
                        "$name $operands"
                    }
                }
                name == "packed-switch" -> {
                    val payload = address + signed32(address + 1)
                    assertEquals(0x0100, insns[payload])
                    assertEquals(0, payload % 2)
                    val count = insns[payload + 1]
                    branchTargets = List(count) { address + signed32(payload + 4 + 2 * it) }
                    "packed-switch v$byteAA ${signed32(payload + 2)}"
                }
                name == "return-void" -> name
                name == "throw" -> "throw v$byteAA"
                else -> fail("Unexpected $name")
            }
            texts += text
            targets += branchTargets
            address += size
        }
        return texts.indices.map { event ->
            if (targets[event].isEmpty()) {
                texts[event]
            } else {
                "${texts[event]} -> ${targets[event].map { eventAt[it] ?: fail("Branch to $it is not an instruction start") }}"
            }
        }
    }
}
