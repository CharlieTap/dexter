package io.github.charlietap.dexter

import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Config
import io.github.charlietap.dexter.internal.Pools
import io.github.charlietap.dexter.internal.code.ArrayDataTable
import io.github.charlietap.dexter.internal.code.BranchRelaxer
import io.github.charlietap.dexter.internal.code.CatchRanges
import io.github.charlietap.dexter.internal.code.CodeTables
import io.github.charlietap.dexter.internal.code.MethodLabels
import io.github.charlietap.dexter.internal.code.SwitchTable
import io.github.charlietap.dexter.internal.code.putLongConditional
import io.github.charlietap.dexter.internal.putU16
import io.github.charlietap.dexter.internal.putU32
import io.github.charlietap.dexter.internal.putU64

/**
 * Writes one method body, from a `begin` call on a [ClassBuilder] until [finish] or [abort]. Every
 * `begin` call returns this same object, so a reference kept from an earlier method writes into
 * whichever method is open now. After a [DexterException] the method has been aborted; any other
 * exception leaves it unchanged.
 */
@DexterDsl
public abstract class CodeEmitter internal constructor(
    private val pools: Pools,
    private val classes: ClassTable,
    private val config: Config,
    internal val tables: CodeTables,
    private val labels: MethodLabels,
    private val branches: BranchRelaxer,
    private val switches: SwitchTable,
    private val arrayData: ArrayDataTable,
    private val catches: CatchRanges,
) {

    internal var arena = ByteArray(1 shl 16)
        private set
    private var pos = 0

    // Zero while no method is open, so the capacity check also rejects use outside a body.
    private var limit = 0

    internal var isOpen = false
        private set
    private var methodStart = 0
    private val poolMark = IntArray(Pools.MARK_SIZE)
    private val tableMark = IntArray(CodeTables.MARK_SIZE)
    private var registerCount = 0
    private var ins = 0
    private var isStatic = false
    private var returnKind = 0
    private var outs = 0
    private var method: DexMethod? = null
    private var methodIndex = 0
    private var parameterCount = 0

    // The DEX version this method's instructions need.
    private var version = BASE_VERSION
    private var memberClass = 0
    private var memberList = 0
    private var memberFlags = 0

    private var lastInvokeEnd = -1
    private var lastInvokeReturn = 0

    private var terminatorEnd = -1

    private val args = IntArray(6)
    private val words = IntArray(11)

    internal val selfIndex: Int get() = registerCount - ins

    internal fun clear() {
        pos = 0
        limit = 0
        tables.clear()
        isOpen = false
    }

    internal fun begin(
        method: DexMethod,
        registerCount: Int,
        ins: Int,
        isStatic: Boolean,
        memberClass: Int,
        memberList: Int,
        memberFlags: Int,
    ) {
        pools.save(poolMark)
        val index = try {
            pools.method(method)
        } catch (failure: Throwable) {
            pools.restore(poolMark)
            throw failure
        }
        if (classes.methodDefined[index]) {
            pools.restore(poolMark)
            throw IllegalArgumentException("Method $method is already defined")
        }
        this.method = method
        this.registerCount = registerCount
        this.ins = ins
        this.isStatic = isStatic
        this.methodIndex = index
        parameterCount = method.parameterCount
        version = BASE_VERSION
        this.memberClass = memberClass
        this.memberList = memberList
        this.memberFlags = memberFlags
        returnKind = method.returnType.kind
        outs = 0
        methodStart = pos
        tables.save(tableMark)
        labels.reset()
        branches.reset()
        switches.reset()
        arrayData.reset()
        catches.reset()
        lastInvokeEnd = -1
        terminatorEnd = -1
        limit = arena.size
        isOpen = true
    }

    /**
     * Throws [IllegalStateException], leaving the method open, if a label, catch range or
     * handler is invalid, or [DexterMethodTooLargeException], aborting it, if it is too large.
     */
    public fun finish(): Unit = finish(config.maxMethodCodeUnits)

    /**
     * As [finish], but also aborts with [DexterMethodTooLargeException] if the method needs more than [maxCodeUnits]
     * code units, counting payloads and relaxed branches. It cannot raise the limit set in [DexterOptions].
     */
    public fun finish(
        maxCodeUnits: Int,
    ) {
        checkOpen()
        require(maxCodeUnits > 0) { "maxCodeUnits must be positive: $maxCodeUnits" }
        validate()
        val record = try {
            commit(minOf(maxCodeUnits, config.maxMethodCodeUnits))
        } catch (failure: DexterException) {
            abort()
            throw failure
        }
        classes.addMember(memberClass, memberList, methodIndex, memberFlags, record)
        classes.methodDefined.set(methodIndex)
        close()
    }

    /** Discards the open method and its symbols. Does nothing when none is open. */
    public fun abort() {
        if (!isOpen) return
        pos = methodStart
        tables.restore(tableMark)
        pools.restore(poolMark)
        close()
    }

    private fun close() {
        isOpen = false
        limit = 0
        registerCount = 0
        method = null
    }

    /** Attributes the next instructions to source [line], from 1, for debuggers, profilers and stack traces. */
    public fun line(
        line: Int,
    ) {
        checkOpen()
        require(line >= 1) { "Line numbers start at 1: $line" }
        tables.addPosition(tableMark[4], unit(), line)
    }

    public fun label(): Label {
        checkOpen()
        return labels.create()
    }

    public fun labels(
        count: Int,
    ): Labels {
        checkOpen()
        return labels.block(count)
    }

    /** Targets for keys from [firstKey] to `firstKey + size - 1`, each starting at [initial]. */
    public fun labelTable(
        firstKey: Int,
        size: Int,
        initial: Label,
    ): LabelTable {
        checkOpen()
        require(size in 1..SwitchTable.MAX_SIZE) { "Switch table size must be 1..${SwitchTable.MAX_SIZE}: $size" }
        require(firstKey.toLong() + size - 1 <= Int.MAX_VALUE) { "Switch keys overflow: $firstKey + $size" }
        return LabelTable(firstKey, size, initial)
    }

    public fun handlerLabel(): HandlerLabel {
        checkOpen()
        return HandlerLabel(labels.create().id)
    }

    public fun boundLabel(): Label {
        val label = label()
        bind(label)
        return label
    }

    public fun isBound(
        label: Label,
    ): Boolean {
        checkOpen()
        return labels.isBound(labels.indexOf(label))
    }

    public fun bind(
        label: Label,
    ) {
        checkOpen()
        labels.bind(labels.indexOf(label), unit())
        lastInvokeEnd = -1
    }

    /** Excludes any receiver; a wide parameter starts at the returned register. */
    public fun parameter(
        index: Int,
    ): Reg {
        val method = checkNotNull(method) { "Code used outside a method body" }
        if (index < 0 || index >= method.parameterCount) throw IndexOutOfBoundsException("Parameter index out of range: $index")
        return Reg(registerCount - ins + (if (isStatic) 0 else 1) + method.proto.offset(index))
    }

    public fun const(
        dst: Reg,
        value: Int,
    ) {
        val dstIndex = dst.index
        checkReg(dstIndex, 1)
        when {
            dstIndex < 16 && value in -8..7 -> write(0x12 or (dstIndex shl 8) or ((value and 0xF) shl 12))
            dstIndex >= 256 -> reject("const needs a register below 256: $dst")
            value.toShort().toInt() == value -> write(0x13 or (dstIndex shl 8), value)
            value and 0xFFFF == 0 -> write(0x15 or (dstIndex shl 8), value ushr 16)
            else -> write(0x14 or (dstIndex shl 8), value, value ushr 16)
        }
    }

    public fun constWide(
        dst: Reg,
        value: Long,
    ) {
        val dstIndex = reg8(dst, 2, "constWide")
        val low = value.toInt()
        val high = (value ushr 32).toInt()
        when {
            value.toShort().toLong() == value -> write(0x16 or (dstIndex shl 8), low)
            value and 0xFFFF_FFFF_FFFFL == 0L -> write(0x19 or (dstIndex shl 8), high ushr 16)
            value.toInt().toLong() == value -> write(0x17 or (dstIndex shl 8), low, low ushr 16)
            else -> {
                val at = reserve(5)
                putU16(arena, at, 0x18 or (dstIndex shl 8))
                putU32(arena, at + 2, low)
                putU32(arena, at + 6, high)
            }
        }
    }

    public fun move(
        dst: Reg,
        src: Reg,
    ): Unit = move(0x01, dst, src, 1)

    public fun moveWide(
        dst: Reg,
        src: Reg,
    ): Unit = move(0x04, dst, src, 2)

    public fun moveObject(
        dst: Reg,
        src: Reg,
    ): Unit = move(0x07, dst, src, 1)

    private fun move(
        opcode: Int,
        dst: Reg,
        src: Reg,
        width: Int,
    ) {
        val dstIndex = dst.index
        val srcIndex = src.index
        checkReg(dstIndex, width)
        checkReg(srcIndex, width)
        when {
            (dstIndex or srcIndex) < 16 -> write(opcode or (dstIndex shl 8) or (srcIndex shl 12))
            dstIndex < 256 -> write(opcode + 1 or (dstIndex shl 8), srcIndex)
            else -> write(opcode + 2, dstIndex, srcIndex)
        }
    }

    /** Must directly follow an invoke. */
    public fun moveResult(
        dst: Reg,
    ) {
        checkOpen()
        check(lastInvokeEnd == pos) { "moveResult must directly follow an invoke" }
        val opcode = when (lastInvokeReturn) {
            DexType.KIND_SINGLE -> 0x0A
            DexType.KIND_WIDE -> 0x0B
            DexType.KIND_REFERENCE -> 0x0C
            else -> throw IllegalStateException("The preceding invoke returns void")
        }
        val dstIndex = reg8(dst, if (opcode == 0x0B) 2 else 1, "moveResult")
        write(opcode or (dstIndex shl 8))
    }

    public fun binary(
        op: BinaryOp,
        dst: Reg,
        a: Reg,
        b: Reg,
    ) {
        val bits = op.bits
        val dstIndex = dst.index
        val aIndex = a.index
        val bIndex = b.index
        val wideA = widthA(bits)
        checkReg(dstIndex, wideA)
        checkReg(aIndex, wideA)
        checkReg(bIndex, widthB(bits))
        val opcode = bits and 0xFF
        when {
            dstIndex == aIndex && (dstIndex or bIndex) < 16 -> write(opcode + 0x20 or (dstIndex shl 8) or (bIndex shl 12))
            (dstIndex or aIndex or bIndex) < 256 -> write(opcode or (dstIndex shl 8), aIndex or (bIndex shl 8))
            else -> reject("binary needs registers below 256: $dst, $a, $b")
        }
    }

    /** A literal no encoding holds is rejected; load it with [const] instead. */
    public fun binary(
        op: LiteralOp,
        dst: Reg,
        a: Reg,
        literal: Int,
    ) {
        val lit8Opcode = op.lit8Opcode
        val dstIndex = dst.index
        val aIndex = a.index
        checkReg(dstIndex, 1)
        checkReg(aIndex, 1)
        val value = if (op.negatesLiteral) -literal else literal
        if (op.negatesLiteral && literal == Int.MIN_VALUE) reject("Literal out of range: $literal")
        val lit16Opcode = op.lit16Opcode
        when {
            lit16Opcode != 0 && (dstIndex or aIndex) < 16 && value.toShort().toInt() == value ->
                write(lit16Opcode or (dstIndex shl 8) or (aIndex shl 12), value)
            (dstIndex or aIndex) < 256 && value.toByte().toInt() == value ->
                write(lit8Opcode or (dstIndex shl 8), aIndex or ((value and 0xFF) shl 8))
            else -> reject("No literal encoding for $literal with registers $dst, $a")
        }
    }

    public fun unary(
        op: UnaryOp,
        dst: Reg,
        src: Reg,
    ): Unit = twelve(op.bits, dst, src, "unary")

    public fun convert(
        op: Conversion,
        dst: Reg,
        src: Reg,
    ): Unit = twelve(op.bits, dst, src, "convert")

    private fun twelve(
        bits: Int,
        dst: Reg,
        src: Reg,
        instruction: String,
    ) {
        val dstIndex = reg4(dst, widthA(bits), instruction)
        val srcIndex = reg4(src, widthB(bits), instruction)
        write((bits and 0xFF) or (dstIndex shl 8) or (srcIndex shl 12))
    }

    public fun compare(
        op: CompareOp,
        dst: Reg,
        a: Reg,
        b: Reg,
    ) {
        val bits = op.bits
        val width = widthB(bits)
        threeByte(bits and 0xFF, dst, 1, a, width, b, width)
    }

    public fun arrayGet(
        kind: ArrayKind,
        dst: Reg,
        array: Reg,
        index: Reg,
    ) {
        val bits = kind.bits
        threeByte(bits and 0xFF, dst, widthA(bits), array, 1, index, 1)
    }

    public fun arrayPut(
        kind: ArrayKind,
        src: Reg,
        array: Reg,
        index: Reg,
    ) {
        val bits = kind.bits
        threeByte((bits and 0xFF) + 7, src, widthA(bits), array, 1, index, 1)
    }

    private fun threeByte(
        opcode: Int,
        registerA: Reg,
        widthA: Int,
        registerB: Reg,
        widthB: Int,
        registerC: Reg,
        widthC: Int,
    ) {
        val aIndex = registerA.index
        val bIndex = registerB.index
        val cIndex = registerC.index
        checkReg(aIndex, widthA)
        checkReg(bIndex, widthB)
        checkReg(cIndex, widthC)
        if ((aIndex or bIndex or cIndex) >= 256) reject("Needs registers below 256: $registerA, $registerB, $registerC")
        write(opcode or (aIndex shl 8), bIndex or (cIndex shl 8))
    }

    public fun newArray(
        dst: Reg,
        length: Reg,
        type: ArrayType,
    ) {
        val dstIndex = reg4(dst, 1, "newArray")
        val lengthIndex = reg4(length, 1, "newArray")
        reference(0x23 or (dstIndex shl 8) or (lengthIndex shl 12), intern { pools.type(type) }, Pools.KIND_TYPE)
    }

    public fun getStatic(
        dst: Reg,
        field: DexField,
    ): Unit = static(field.type.staticGetOpcode, dst, field)

    public fun putStatic(
        src: Reg,
        field: DexField,
    ): Unit = static(field.type.staticGetOpcode + 7, src, field)

    private fun static(
        opcode: Int,
        reg: Reg,
        field: DexField,
    ) {
        val regIndex = reg8(reg, field.type.width, "Static field access")
        reference(opcode or (regIndex shl 8), intern { pools.field(field) }, Pools.KIND_FIELD)
    }

    /** Reads [field] of the object in [instance]; both registers must be below 16. */
    public fun getField(
        dst: Reg,
        instance: Reg,
        field: DexField,
    ): Unit = instanceField(OP_IGET, dst, instance, field)

    /** Writes [field] of the object in [instance]; both registers must be below 16. */
    public fun putField(
        src: Reg,
        instance: Reg,
        field: DexField,
    ): Unit = instanceField(OP_IPUT, src, instance, field)

    // The iget and iput families order their kinds as sget does.
    private fun instanceField(
        family: Int,
        reg: Reg,
        instance: Reg,
        field: DexField,
    ) {
        val regIndex = reg4(reg, field.type.width, "Instance field access")
        val instanceIndex = reg4(instance, 1, "Instance field access")
        val opcode = family + field.type.staticGetOpcode - OP_SGET
        reference(opcode or (regIndex shl 8) or (instanceIndex shl 12), intern { pools.field(field) }, Pools.KIND_FIELD)
    }

    /** Loads [handle] as a `java.lang.invoke.MethodHandle`; needs DEX 039. */
    public fun constMethodHandle(
        dst: Reg,
        handle: DexMethodHandle,
    ) {
        val dstIndex = reg8(dst, 1, "constMethodHandle")
        val index = intern { pools.methodHandle(handle) }
        write(0xFE or (dstIndex shl 8), index)
        if (version < CONSTANT_HANDLE_VERSION) version = CONSTANT_HANDLE_VERSION
    }

    /** Loads [type] as a `java.lang.invoke.MethodType`; needs DEX 039. */
    public fun constMethodType(
        dst: Reg,
        type: DexMethodType,
    ) {
        val dstIndex = reg8(dst, 1, "constMethodType")
        reference(0xFF or (dstIndex shl 8), intern { pools.proto(type.proto) }, Pools.KIND_PROTO)
        if (version < CONSTANT_HANDLE_VERSION) version = CONSTANT_HANDLE_VERSION
    }

    /** Both registers must be below 16. */
    public fun arrayLength(
        dst: Reg,
        array: Reg,
    ) {
        val dstIndex = reg4(dst, 1, "arrayLength")
        val arrayIndex = reg4(array, 1, "arrayLength")
        write(0x21 or (dstIndex shl 8) or (arrayIndex shl 12))
    }

    public fun monitorEnter(
        reg: Reg,
    ) {
        val regIndex = reg8(reg, 1, "monitorEnter")
        write(0x1D or (regIndex shl 8))
    }

    public fun monitorExit(
        reg: Reg,
    ) {
        val regIndex = reg8(reg, 1, "monitorExit")
        write(0x1E or (regIndex shl 8))
    }

    /** Copies [values] into the array in [array], which must have room for them. */
    public fun fillArrayData(
        array: Reg,
        values: ByteArray,
    ) {
        val at = fill(array, 1, values.size)
        values.copyInto(arrayData.data, at)
    }

    public fun fillArrayData(
        array: Reg,
        values: BooleanArray,
    ) {
        val at = fill(array, 1, values.size)
        val data = arrayData.data
        for (index in values.indices) data[at + index] = if (values[index]) 1 else 0
    }

    public fun fillArrayData(
        array: Reg,
        values: ShortArray,
    ) {
        val at = fill(array, 2, values.size)
        val data = arrayData.data
        for (index in values.indices) putU16(data, at + index * 2, values[index].toInt())
    }

    public fun fillArrayData(
        array: Reg,
        values: CharArray,
    ) {
        val at = fill(array, 2, values.size)
        val data = arrayData.data
        for (index in values.indices) putU16(data, at + index * 2, values[index].code)
    }

    public fun fillArrayData(
        array: Reg,
        values: IntArray,
    ) {
        val at = fill(array, 4, values.size)
        val data = arrayData.data
        for (index in values.indices) putU32(data, at + index * 4, values[index])
    }

    public fun fillArrayData(
        array: Reg,
        values: FloatArray,
    ) {
        val at = fill(array, 4, values.size)
        val data = arrayData.data
        for (index in values.indices) putU32(data, at + index * 4, values[index].toRawBits())
    }

    public fun fillArrayData(
        array: Reg,
        values: LongArray,
    ) {
        val at = fill(array, 8, values.size)
        val data = arrayData.data
        for (index in values.indices) putU64(data, at + index * 8, values[index])
    }

    public fun fillArrayData(
        array: Reg,
        values: DoubleArray,
    ) {
        val at = fill(array, 8, values.size)
        val data = arrayData.data
        for (index in values.indices) putU64(data, at + index * 8, values[index].toRawBits())
    }

    // Writes fill-array-data and returns where its elements go in the table's data.
    private fun fill(
        array: Reg,
        width: Int,
        elements: Int,
    ): Int {
        val arrayIndex = reg8(array, 1, "fillArrayData")
        val site = unit()
        write(0x26 or (arrayIndex shl 8), 0, 0)
        return arrayData.add(site, width, elements)
    }

    /** Writes 1 to [dst] if [reg] holds an instance of [type], else 0; both registers must be below 16. */
    public fun instanceOf(
        dst: Reg,
        reg: Reg,
        type: ReferenceType,
    ) {
        val dstIndex = reg4(dst, 1, "instanceOf")
        val regIndex = reg4(reg, 1, "instanceOf")
        reference(0x20 or (dstIndex shl 8) or (regIndex shl 12), intern { pools.type(type) }, Pools.KIND_TYPE)
    }

    /**
     * Loads [value] as a `java.lang.String`. A file that uses this holds at most 65,536 strings in all;
     * a string past that throws [DexterFileFullException].
     */
    public fun constString(
        dst: Reg,
        value: String,
    ) {
        val dstIndex = reg8(dst, 1, "constString")
        reference(0x1A or (dstIndex shl 8), intern { pools.constString(value) }, Pools.KIND_STRING)
    }

    /**
     * Allocates an instance of [type] without running a constructor. Call one with [invokeConstructor] on
     * [dst] before using the object.
     */
    public fun newInstance(
        dst: Reg,
        type: ClassType,
    ) {
        val dstIndex = reg8(dst, 1, "newInstance")
        reference(0x22 or (dstIndex shl 8), intern { pools.type(type) }, Pools.KIND_TYPE)
    }

    /** Loads the `java.lang.Class` for [type]. */
    public fun constClass(
        dst: Reg,
        type: ReferenceType,
    ) {
        val dstIndex = reg8(dst, 1, "constClass")
        reference(0x1C or (dstIndex shl 8), intern { pools.type(type) }, Pools.KIND_TYPE)
    }

    public fun checkCast(
        reg: Reg,
        type: ReferenceType,
    ) {
        val regIndex = reg8(reg, 1, "checkCast")
        reference(0x1F or (regIndex shl 8), intern { pools.type(type) }, Pools.KIND_TYPE)
    }

    private fun reference(
        unit0: Int,
        index: Int,
        kind: Int,
    ) {
        val at = reserve(2)
        putU16(arena, at, unit0)
        putU16(arena, at + 2, index)
        tables.addRef(at + 2, kind)
    }

    public fun invokeStatic(
        method: DexMethod,
    ): Unit = invoke(OP_STATIC, method, false)

    public fun invokeStatic(
        method: DexMethod,
        a: Reg,
    ): Unit = invoke(OP_STATIC, method, false, a)

    public fun invokeStatic(
        method: DexMethod,
        a: Reg,
        b: Reg,
    ): Unit = invoke(OP_STATIC, method, false, a, b)

    public fun invokeStatic(
        method: DexMethod,
        a: Reg,
        b: Reg,
        c: Reg,
    ): Unit = invoke(OP_STATIC, method, false, a, b, c)

    public fun invokeStatic(
        method: DexMethod,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
    ): Unit = invoke(OP_STATIC, method, false, a, b, c, d)

    public fun invokeStatic(
        method: DexMethod,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
        e: Reg,
    ): Unit = invoke(OP_STATIC, method, false, a, b, c, d, e)

    public fun invokeVirtual(
        method: DexMethod,
        receiver: Reg,
    ): Unit = invoke(OP_VIRTUAL, method, true, receiver)

    public fun invokeVirtual(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
    ): Unit = invoke(OP_VIRTUAL, method, true, receiver, a)

    public fun invokeVirtual(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
    ): Unit = invoke(OP_VIRTUAL, method, true, receiver, a, b)

    public fun invokeVirtual(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
    ): Unit = invoke(OP_VIRTUAL, method, true, receiver, a, b, c)

    public fun invokeVirtual(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
    ): Unit = invoke(OP_VIRTUAL, method, true, receiver, a, b, c, d)

    public fun invokeVirtual(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
        e: Reg,
    ): Unit = invoke(OP_VIRTUAL, method, true, receiver, a, b, c, d, e)

    public fun invokeDirect(
        method: DexMethod,
        receiver: Reg,
    ): Unit = invoke(OP_DIRECT, method, true, receiver)

    public fun invokeDirect(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
    ): Unit = invoke(OP_DIRECT, method, true, receiver, a)

    public fun invokeDirect(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
    ): Unit = invoke(OP_DIRECT, method, true, receiver, a, b)

    public fun invokeDirect(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
    ): Unit = invoke(OP_DIRECT, method, true, receiver, a, b, c)

    public fun invokeDirect(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
    ): Unit = invoke(OP_DIRECT, method, true, receiver, a, b, c, d)

    public fun invokeDirect(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
        e: Reg,
    ): Unit = invoke(OP_DIRECT, method, true, receiver, a, b, c, d, e)

    public fun invokeInterface(
        method: DexMethod,
        receiver: Reg,
    ): Unit = invoke(OP_INTERFACE, method, true, receiver)

    public fun invokeInterface(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
    ): Unit = invoke(OP_INTERFACE, method, true, receiver, a)

    public fun invokeInterface(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
    ): Unit = invoke(OP_INTERFACE, method, true, receiver, a, b)

    public fun invokeInterface(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
    ): Unit = invoke(OP_INTERFACE, method, true, receiver, a, b, c)

    public fun invokeInterface(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
    ): Unit = invoke(OP_INTERFACE, method, true, receiver, a, b, c, d)

    public fun invokeInterface(
        method: DexMethod,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
        e: Reg,
    ): Unit = invoke(OP_INTERFACE, method, true, receiver, a, b, c, d, e)

    public fun invokeConstructor(
        constructor: DexConstructor,
        receiver: Reg,
    ): Unit = invoke(OP_DIRECT, constructor.method, true, receiver)

    public fun invokeConstructor(
        constructor: DexConstructor,
        receiver: Reg,
        a: Reg,
    ): Unit = invoke(OP_DIRECT, constructor.method, true, receiver, a)

    public fun invokeConstructor(
        constructor: DexConstructor,
        receiver: Reg,
        a: Reg,
        b: Reg,
    ): Unit = invoke(OP_DIRECT, constructor.method, true, receiver, a, b)

    public fun invokeConstructor(
        constructor: DexConstructor,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
    ): Unit = invoke(OP_DIRECT, constructor.method, true, receiver, a, b, c)

    public fun invokeConstructor(
        constructor: DexConstructor,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
    ): Unit = invoke(OP_DIRECT, constructor.method, true, receiver, a, b, c, d)

    public fun invokeConstructor(
        constructor: DexConstructor,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
        e: Reg,
    ): Unit = invoke(OP_DIRECT, constructor.method, true, receiver, a, b, c, d, e)

    public fun invokeConstructorRange(
        constructor: DexConstructor,
        first: Reg,
    ): Unit = invokeRange(OP_DIRECT, constructor.method, first, true)

    public fun invokeStaticRange(
        method: DexMethod,
        first: Reg,
    ): Unit = invokeRange(OP_STATIC, method, first, false)

    public fun invokeVirtualRange(
        method: DexMethod,
        first: Reg,
    ): Unit = invokeRange(OP_VIRTUAL, method, first, true)

    public fun invokeDirectRange(
        method: DexMethod,
        first: Reg,
    ): Unit = invokeRange(OP_DIRECT, method, first, true)

    public fun invokeInterfaceRange(
        method: DexMethod,
        first: Reg,
    ): Unit = invokeRange(OP_INTERFACE, method, first, true)

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
    ) {
        invoke(opcode, method, 0, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
        argument0: Reg,
    ) {
        args[0] = argument0.index
        invoke(opcode, method, 1, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
        argument0: Reg,
        argument1: Reg,
    ) {
        args[0] = argument0.index
        args[1] = argument1.index
        invoke(opcode, method, 2, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
        argument0: Reg,
        argument1: Reg,
        argument2: Reg,
    ) {
        args[0] = argument0.index
        args[1] = argument1.index
        args[2] = argument2.index
        invoke(opcode, method, 3, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
        argument0: Reg,
        argument1: Reg,
        argument2: Reg,
        argument3: Reg,
    ) {
        args[0] = argument0.index
        args[1] = argument1.index
        args[2] = argument2.index
        args[3] = argument3.index
        invoke(opcode, method, 4, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
        argument0: Reg,
        argument1: Reg,
        argument2: Reg,
        argument3: Reg,
        argument4: Reg,
    ) {
        args[0] = argument0.index
        args[1] = argument1.index
        args[2] = argument2.index
        args[3] = argument3.index
        args[4] = argument4.index
        invoke(opcode, method, 5, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        hasReceiver: Boolean,
        argument0: Reg,
        argument1: Reg,
        argument2: Reg,
        argument3: Reg,
        argument4: Reg,
        argument5: Reg,
    ) {
        args[0] = argument0.index
        args[1] = argument1.index
        args[2] = argument2.index
        args[3] = argument3.index
        args[4] = argument4.index
        args[5] = argument5.index
        invoke(opcode, method, 6, hasReceiver)
    }

    private fun invoke(
        opcode: Int,
        method: DexMethod,
        argc: Int,
        hasReceiver: Boolean,
    ) {
        checkOpen()
        val expected = method.parameterCount + if (hasReceiver) 1 else 0
        require(argc == expected) { "$method takes $expected argument registers, got $argc" }
        val count = collectWords(argc, if (hasReceiver) method.wideMask shl 1 else method.wideMask)
        val index = intern { pools.method(method) }
        if (wordsPacked) {
            val at = reserve(3)
            writePacked(at, opcode, index, count)
        } else {
            emitRange(opcode, index, words[0], count)
        }
        finishInvoke(method.returnType.kind, count)
    }

    private var wordsPacked = false

    // Expands args into register words, and whether they fit the 4-bit form, else rejects them unless consecutive.
    private fun collectWords(
        argc: Int,
        wideMask: Int,
    ): Int {
        val words = words
        var count = 0
        var registerBits = 0
        var contiguous = true
        for (argument in 0 until argc) {
            val register = args[argument]
            val wide = (wideMask ushr argument) and 1 != 0
            checkReg(register, if (wide) 2 else 1)
            if (count > 0 && register != words[count - 1] + 1) contiguous = false
            words[count++] = register
            registerBits = registerBits or register
            if (wide) {
                words[count++] = register + 1
                registerBits = registerBits or (register + 1)
            }
        }
        wordsPacked = count <= 5 && registerBits < 16
        if (!wordsPacked && !contiguous) {
            reject("Invoke arguments need registers below 16 (at most 5 words) or consecutive registers")
        }
        return count
    }

    private fun writePacked(
        at: Int,
        opcode: Int,
        index: Int,
        count: Int,
    ) {
        val fifth = if (count == 5) words[4] else 0
        putU16(arena, at, opcode or (fifth shl 8) or (count shl 12))
        putU16(arena, at + 2, index)
        var nibbles = 0
        for (word in 0 until minOf(count, 4)) nibbles = nibbles or (words[word] shl (4 * word))
        putU16(arena, at + 4, nibbles)
        tables.addRef(at + 2, Pools.KIND_METHOD)
    }

    /**
     * Calls a signature-polymorphic [method], such as `MethodHandle.invokeExact` or `VarHandle.get`, on [receiver]
     * with arguments of the call site's [type]; [moveResult] reads a result of [type]'s return type.
     */
    public fun invokePolymorphic(
        method: DexMethod,
        type: DexMethodType,
        receiver: Reg,
    ) {
        args[0] = receiver.index
        polymorphic(method, type, 1)
    }

    public fun invokePolymorphic(
        method: DexMethod,
        type: DexMethodType,
        receiver: Reg,
        a: Reg,
    ) {
        args[0] = receiver.index
        args[1] = a.index
        polymorphic(method, type, 2)
    }

    public fun invokePolymorphic(
        method: DexMethod,
        type: DexMethodType,
        receiver: Reg,
        a: Reg,
        b: Reg,
    ) {
        args[0] = receiver.index
        args[1] = a.index
        args[2] = b.index
        polymorphic(method, type, 3)
    }

    public fun invokePolymorphic(
        method: DexMethod,
        type: DexMethodType,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
    ) {
        args[0] = receiver.index
        args[1] = a.index
        args[2] = b.index
        args[3] = c.index
        polymorphic(method, type, 4)
    }

    public fun invokePolymorphic(
        method: DexMethod,
        type: DexMethodType,
        receiver: Reg,
        a: Reg,
        b: Reg,
        c: Reg,
        d: Reg,
    ) {
        args[0] = receiver.index
        args[1] = a.index
        args[2] = b.index
        args[3] = c.index
        args[4] = d.index
        polymorphic(method, type, 5)
    }

    /** As [invokePolymorphic], with the receiver and arguments in consecutive registers from [first]. */
    public fun invokePolymorphicRange(
        method: DexMethod,
        type: DexMethodType,
        first: Reg,
    ) {
        val count = type.words + 1
        if (count > 255) reject("$type takes more than 254 argument words")
        checkOpen()
        val firstIndex = first.index
        checkReg(firstIndex, count)
        val methodIndex = intern { pools.method(method) }
        val protoIndex = intern { pools.proto(type.proto) }
        val at = reserve(4)
        putU16(arena, at, OP_POLYMORPHIC_RANGE or (count shl 8))
        putU16(arena, at + 2, methodIndex)
        putU16(arena, at + 4, firstIndex)
        tables.addRef(at + 2, Pools.KIND_METHOD)
        polymorphicProto(at, protoIndex, type, count)
    }

    private fun polymorphic(
        method: DexMethod,
        type: DexMethodType,
        argc: Int,
    ) {
        checkOpen()
        val expected = type.parameterCount + 1
        require(argc == expected) { "A call of type $type takes $expected argument registers, got $argc" }
        val count = collectWords(argc, type.wideMask shl 1)
        val methodIndex = intern { pools.method(method) }
        val protoIndex = intern { pools.proto(type.proto) }
        val at = reserve(4)
        if (wordsPacked) {
            writePacked(at, OP_POLYMORPHIC, methodIndex, count)
        } else {
            putU16(arena, at, OP_POLYMORPHIC_RANGE or (count shl 8))
            putU16(arena, at + 2, methodIndex)
            putU16(arena, at + 4, words[0])
            tables.addRef(at + 2, Pools.KIND_METHOD)
        }
        polymorphicProto(at, protoIndex, type, count)
    }

    private fun polymorphicProto(
        at: Int,
        protoIndex: Int,
        type: DexMethodType,
        count: Int,
    ) {
        putU16(arena, at + 6, protoIndex)
        tables.addRef(at + 6, Pools.KIND_PROTO)
        finishInvoke(type.returnType.kind, count)
        if (version < POLYMORPHIC_VERSION) version = POLYMORPHIC_VERSION
    }

    private fun invokeRange(
        opcode: Int,
        method: DexMethod,
        first: Reg,
        hasReceiver: Boolean,
    ) {
        val count = method.words + if (hasReceiver) 1 else 0
        if (count > 255) reject("$method takes more than 255 argument words")
        checkOpen()
        val firstIndex = first.index
        checkReg(firstIndex, count)
        val index = intern { pools.method(method) }
        emitRange(opcode, index, firstIndex, count)
        finishInvoke(method.returnType.kind, count)
    }

    private fun emitRange(
        opcode: Int,
        index: Int,
        first: Int,
        count: Int,
    ) {
        val at = reserve(3)
        putU16(arena, at, (opcode + 6) or (count shl 8))
        putU16(arena, at + 2, index)
        putU16(arena, at + 4, first)
        tables.addRef(at + 2, Pools.KIND_METHOD)
    }

    private fun finishInvoke(
        returnKind: Int,
        count: Int,
    ) {
        if (count > outs) outs = count
        lastInvokeEnd = pos
        lastInvokeReturn = returnKind
    }

    public fun goto(
        target: Label,
    ) {
        checkOpen()
        val label = labels.indexOf(target)
        val site = unit()
        val form = branches.gotoForm(site, labels.address(label))
        when (form) {
            BranchRelaxer.GOTO8 -> write(0x28)
            BranchRelaxer.GOTO16 -> write(0x29, 0)
            else -> write(0x2A, 0, 0)
        }
        branches.add(site, label, form)
        terminatorEnd = unit()
    }

    /** Both registers must be below 16. */
    public fun branch(
        cond: Cond,
        a: Reg,
        b: Reg,
        target: Label,
    ) {
        checkOpen()
        val aIndex = reg4(a, 1, "branch")
        val bIndex = reg4(b, 1, "branch")
        conditional((0x32 + cond.bits) or (aIndex shl 8) or (bIndex shl 12), labels.indexOf(target))
    }

    public fun branchZero(
        cond: Cond,
        a: Reg,
        target: Label,
    ) {
        checkOpen()
        val aIndex = reg8(a, 1, "branchZero")
        conditional((0x38 + cond.bits) or (aIndex shl 8), labels.indexOf(target))
    }

    private fun conditional(
        unit0: Int,
        label: Int,
    ) {
        val site = unit()
        val form = branches.conditionalForm(site, labels.address(label))
        if (form == BranchRelaxer.IF16) {
            write(unit0, 0)
        } else {
            putLongConditional(arena, reserve(5), unit0)
        }
        branches.add(site, label, form)
    }

    public fun packedSwitch(
        value: Reg,
        targets: LabelTable,
    ) {
        val valueIndex = reg8(value, 1, "A switch")
        write(switches.addPacked(unit(), targets, labels) or (valueIndex shl 8), 0, 0)
    }

    /** Writes a packed or sparse switch, whichever is smaller, or nothing when [cases] is empty. */
    public fun switch(
        value: Reg,
        cases: SwitchCases,
    ) {
        val valueIndex = reg8(value, 1, "A switch")
        val opcode = switches.addCases(unit(), cases, labels)
        if (opcode >= 0) write(opcode or (valueIndex shl 8), 0, 0)
    }

    public fun returnVoid() {
        check(!isOpen || returnKind == DexType.KIND_VOID) { "returnVoid in a method that returns a value" }
        write(0x0E)
        terminatorEnd = unit()
    }

    public fun returnValue(
        reg: Reg,
    ) {
        checkOpen()
        val opcode = when (returnKind) {
            DexType.KIND_SINGLE -> 0x0F
            DexType.KIND_WIDE -> 0x10
            DexType.KIND_REFERENCE -> 0x11
            else -> throw IllegalStateException("returnValue in a void method")
        }
        val regIndex = reg8(reg, if (opcode == 0x10) 2 else 1, "returnValue")
        write(opcode or (regIndex shl 8))
        terminatorEnd = unit()
    }

    public fun throwValue(
        reg: Reg,
    ) {
        val regIndex = reg8(reg, 1, "throwValue")
        write(0x27 or (regIndex shl 8))
        terminatorEnd = unit()
    }

    /**
     * Ranges may be declared any time before [finish] and must be disjoint or nested. Inner handlers
     * are tried first, and ranges with equal bounds in declaration order.
     */
    public fun catchRange(
        start: Label,
        end: Label,
        exception: ClassType,
        handler: HandlerLabel,
    ) {
        checkOpen()
        val startLabel = labels.indexOf(start)
        val endLabel = labels.indexOf(end)
        val handlerLabel = labels.indexOf(Label(handler.id))
        catches.add(startLabel, endLabel, intern { pools.type(exception) }, handlerLabel)
    }

    public fun catchAllRange(
        start: Label,
        end: Label,
        handler: HandlerLabel,
    ) {
        checkOpen()
        catches.add(labels.indexOf(start), labels.indexOf(end), CatchRanges.CATCH_ALL, labels.indexOf(Label(handler.id)))
    }

    /** Binds [handler] to the next instruction, leaving the caught exception unread. */
    public fun bindHandler(
        handler: HandlerLabel,
    ) {
        bind(Label(handler.id))
    }

    /**
     * Binds [handler] to the next instruction, which moves the caught exception into [exception]. The handler
     * must then not be reachable other than by an exception: not by falling through, and not by a jump to a
     * label bound at the same place.
     */
    public fun bindHandler(
        handler: HandlerLabel,
        exception: Reg,
    ) {
        val exceptionIndex = reg8(exception, 1, "bindHandler")
        bindHandler(handler)
        val site = unit()
        write(0x0D or (exceptionIndex shl 8))
        catches.addMoveException(site, fallsInto = site != terminatorEnd)
    }

    private fun validate() {
        val units = unit()
        check(units > 0) { "Method body is empty" }
        branches.checkTargets(labels, units, method)
        switches.checkTargets(labels, units, method)
        catches.validate(labels, units, method, branches, switches)
    }

    private fun commit(
        maxCodeUnits: Int,
    ): Int {
        if (!branches.allFit(labels)) relax()
        branches.patch(arena, methodStart, labels)
        val codeEnd = unit()
        val switchUnits = switches.payloadUnits(codeEnd)
        val payloadEnd = pos + (switchUnits + arrayData.payloadUnits(codeEnd + switchUnits)) * 2
        if (payloadEnd > limit) growArena(payloadEnd)
        pos = payloadEnd
        switches.writePayloads(arena, methodStart, codeEnd, labels)
        arrayData.writePayloads(arena, methodStart, codeEnd + switchUnits)

        val total = unit()
        if (total > maxCodeUnits) {
            throw DexterMethodTooLargeException("$method needs $total code units, more than $maxCodeUnits")
        }
        catches.flatten(labels, tables, arena, methodStart)
        if (tables.tryCount - tableMark[1] > 0xFFFF || !CatchRanges.handlersFit(tables, tableMark[2])) {
            throw DexterMethodTooLargeException("$method needs more try items or handlers than a code item holds")
        }
        return tables.addRecord(methodStart, total, registerCount, ins, outs, parameterCount, version, tableMark)
    }

    private fun relax() {
        val end = pos
        val length = end - methodStart + branches.widen(labels) * 2
        val body = branches.rebuild(arena, methodStart, end, length)
        if (methodStart + length > arena.size) growArena(methodStart + length)
        body.copyInto(arena, methodStart, 0, length)
        pos = methodStart + length
        limit = arena.size
        labels.remap(branches)
        tables.remapRefs(tableMark[0], methodStart, branches)
        tables.remapPositions(tableMark[4], branches)
        switches.remap(branches)
        arrayData.remap(branches)
        catches.remap(branches)
        branches.finish()
    }

    private fun unit(): Int = (pos - methodStart) ushr 1

    private fun reserve(
        units: Int,
    ): Int {
        val at = pos
        val end = at + units * 2
        if (end > limit) {
            checkOpen()
            growArena(end)
        }
        pos = end
        return at
    }

    private fun growArena(
        end: Int,
    ) {
        if (end > MAX_ARENA) {
            abort()
            throw DexterFileFullException("Code exceeds ${MAX_ARENA ushr 20} MiB")
        }
        arena = arena.copyOf(minOf(maxOf(arena.size * 2, end), MAX_ARENA))
        limit = arena.size
    }

    private fun write(
        unit0: Int,
    ) {
        val at = reserve(1)
        putU16(arena, at, unit0)
    }

    private fun write(
        unit0: Int,
        unit1: Int,
    ) {
        val at = reserve(2)
        putU16(arena, at, unit0)
        putU16(arena, at + 2, unit1)
    }

    private fun write(
        unit0: Int,
        unit1: Int,
        unit2: Int,
    ) {
        val at = reserve(3)
        putU16(arena, at, unit0)
        putU16(arena, at + 2, unit1)
        putU16(arena, at + 4, unit2)
    }

    private inline fun intern(
        lookup: () -> Int,
    ): Int =
        try {
            lookup()
        } catch (failure: DexterFileFullException) {
            abort()
            throw failure
        }

    private fun checkOpen() {
        check(isOpen) { "Code used outside a method body" }
    }

    private fun reg8(
        reg: Reg,
        width: Int,
        instruction: String,
    ): Int {
        val index = reg.index
        checkReg(index, width)
        if (index >= 256) reject("$instruction needs a register below 256: $reg")
        return index
    }

    private fun reg4(
        reg: Reg,
        width: Int,
        instruction: String,
    ): Int {
        val index = reg.index
        checkReg(index, width)
        if (index >= 16) reject("$instruction needs a register below 16: $reg")
        return index
    }

    private fun checkReg(
        index: Int,
        width: Int,
    ) {
        // One compare: as an unsigned value widened to Long, a negative index is huge and cannot wrap.
        if (index.toUInt().toLong() + width > registerCount) {
            checkOpen()
            reject(
                if (width == 2) {
                    "Wide register pair v$index, v${index + 1} is outside the method's $registerCount registers"
                } else {
                    "Register v$index is outside the method's $registerCount registers"
                },
            )
        }
    }

    private fun reject(
        message: String,
    ): Nothing = throw IllegalArgumentException(message)

    private companion object {
        const val OP_IGET = 0x52
        const val OP_IPUT = 0x59
        const val OP_SGET = 0x60
        const val BASE_VERSION = 35
        const val OP_VIRTUAL = 0x6E
        const val OP_DIRECT = 0x70
        const val OP_STATIC = 0x71
        const val OP_INTERFACE = 0x72
        const val OP_POLYMORPHIC = 0xFA
        const val OP_POLYMORPHIC_RANGE = 0xFB
        const val POLYMORPHIC_VERSION = 38
        const val CONSTANT_HANDLE_VERSION = 39
        const val MAX_ARENA = 1 shl 29
    }
}

public class InstanceCodeEmitter internal constructor(
    pools: Pools,
    classes: ClassTable,
    config: Config,
    tables: CodeTables,
    labels: MethodLabels,
    branches: BranchRelaxer,
    switches: SwitchTable,
    arrayData: ArrayDataTable,
    catches: CatchRanges,
) : CodeEmitter(pools, classes, config, tables, labels, branches, switches, arrayData, catches) {

    public val self: Reg get() = Reg(selfIndex)
}
