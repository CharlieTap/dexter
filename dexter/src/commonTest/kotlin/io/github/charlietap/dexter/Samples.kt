package io.github.charlietap.dexter

internal object Samples {

    val ENTRY = ClassType("Lchasm/Entry;")
    val COMPILED_FUNCTION = ClassType("Lchasm/CompiledFunction;")
    val CONTEXT = ClassType("Lchasm/Context;")
    val STACK_OVERFLOW = ClassType("Ljava/lang/StackOverflowError;")
    val BRIDGE = ClassType("Lchasm/Bridge;")

    val OBJECT_INIT = DexConstructor(DexType.Object)
    val INVOKE = DexMethod(ENTRY, "invoke", DexType.Void, CONTEXT, DexType.Long.array(), DexType.Int)
    val BRIDGE_DEPTH = DexMethod(BRIDGE, "depth", DexType.Int, CONTEXT)
    val TARGET = DexMethod(BRIDGE, "f0", DexType.Long, CONTEXT, DexType.Int, DexType.Long, DexType.Long)
    val STACK_EXHAUSTED = DexMethod(BRIDGE, "callStackExhausted", DexType.Void)

    fun adapter(
        dexter: Dexter = Dexter(),
    ): ByteArray =
        dexter.assemble {
            val entry = addClass(ENTRY, DexType.Object, COMPILED_FUNCTION)
            entry.constructor(DexConstructor(ENTRY), ConstructorAccess.Public, registerCount = 1) {
                invokeConstructor(OBJECT_INIT, self)
                returnVoid()
            }
            entry.instanceMethod(INVOKE, MemberAccess.Public, registerCount = 4 + 7) {
                val context = parameter(0)
                val frame = parameter(1)
                val framePointer = parameter(2)
                val index = Reg(6)
                val overflow = handlerLabel()
                tryCatch(STACK_OVERFLOW, overflow) {
                    moveObject(Reg(0), context)
                    invokeStatic(BRIDGE_DEPTH, Reg(0))
                    moveResult(Reg(1))
                    for (slot in 0 until 2) {
                        binary(LiteralOp.AddInt, index, framePointer, literal = slot)
                        arrayGet(ArrayKind.Wide, Reg(2 + 2 * slot), frame, index)
                    }
                    invokeStaticRange(TARGET, Reg(0))
                    moveResult(Reg(2))
                    arrayPut(ArrayKind.Wide, Reg(2), frame, framePointer)
                }
                returnVoid()
                bindHandler(overflow)
                invokeStatic(STACK_EXHAUSTED)
                returnVoid()
            }
        }

    val HOLDER = ClassType("Lchasm/Holder;")
    val BASE = ClassType("Lchasm/Base;")
    val K = DexField(HOLDER, "K", DexType.Object.array())
    val COUNT = DexField(HOLDER, "count", DexType.Long)
    val CONSTANTS_K = DexField(ClassType("Lchasm/Constants;"), "K", DexType.Object.array())
    val BASE_INIT = DexConstructor(BASE)
    val MIX = DexMethod(HOLDER, "mix", DexType.Int, DexType.Int, DexType.Long, DexType.Double)
    val SWITCHY = DexMethod(HOLDER, "switchy", DexType.Int, DexType.Int)
    val UNICODE = DexMethod(HOLDER, "été😀", DexType.Void)

    fun hierarchy(
        dexter: Dexter = Dexter(),
    ): ByteArray =
        dexter.assemble {
            val holder = addClass(HOLDER, BASE, access = ClassAccess.Public)
            val base = addClass(BASE, DexType.Object, access = ClassAccess.PublicAbstract)
            base.constructor(BASE_INIT, ConstructorAccess.Protected, registerCount = 1) {
                invokeConstructor(OBJECT_INIT, self)
                returnVoid()
            }
            holder.addStaticField(K, MemberAccess.Public.final())
            holder.addStaticField(COUNT, MemberAccess.Private)
            holder.staticInitializer(registerCount = 1) {
                getStatic(Reg(0), CONSTANTS_K)
                putStatic(Reg(0), K)
                returnVoid()
            }
            holder.constructor(DexConstructor(HOLDER), ConstructorAccess.Public, registerCount = 1) {
                invokeConstructor(BASE_INIT, self)
                returnVoid()
            }
            holder.staticMethod(MIX, MemberAccess.Public, registerCount = 20) {
                val intValue = parameter(0)
                val longValue = parameter(1)
                val doubleValue = parameter(2)
                const(Reg(0), 7)
                const(Reg(1), 1000)
                const(Reg(2), 0x10000)
                const(Reg(3), 123456789)
                constWide(Reg(4), 1L shl 50)
                binary(BinaryOp.AddInt, Reg(0), Reg(0), intValue)
                binary(BinaryOp.MulInt, Reg(1), Reg(0), Reg(3))
                binary(LiteralOp.SubInt, Reg(1), Reg(1), literal = 300)
                binary(LiteralOp.ShlInt, Reg(1), Reg(1), literal = 3)
                binary(BinaryOp.AddLong, Reg(4), Reg(4), longValue)
                convert(Conversion.LongToInt, Reg(2), Reg(4))
                moveWide(Reg(12), doubleValue)
                convert(Conversion.DoubleToLong, Reg(6), Reg(12))
                compare(CompareOp.CmpLong, Reg(3), Reg(6), Reg(4))
                unary(UnaryOp.NegInt, Reg(3), Reg(3))
                val done = label()
                branchZero(Cond.Lt, Reg(3), done)
                branch(Cond.Ge, Reg(0), Reg(1), done)
                const(Reg(8), 4)
                newArray(Reg(9), Reg(8), DexType.Int.array())
                arrayPut(ArrayKind.Int, Reg(0), Reg(9), Reg(2))
                arrayGet(ArrayKind.Int, Reg(0), Reg(9), Reg(2))
                move(Reg(17), Reg(0))
                moveWide(Reg(10), Reg(4))
                bind(done)
                returnValue(Reg(0))
            }
            holder.instanceMethod(SWITCHY, MemberAccess.Public, registerCount = 3) {
                val value = parameter(0)
                val cases = labels(3)
                val table = labelTable(-1, 4, cases[0])
                table[1] = cases[1]
                table[3] = cases[2]
                packedSwitch(value, table)
                const(Reg(0), -1)
                returnValue(Reg(0))
                bind(cases[0])
                const(Reg(0), 10)
                returnValue(Reg(0))
                bind(cases[1])
                const(Reg(0), 20)
                returnValue(Reg(0))
                bind(cases[2])
                const(Reg(0), 30)
                returnValue(Reg(0))
            }
            holder.instanceMethod(UNICODE, MemberAccess.Private, registerCount = 1) {
                returnVoid()
            }
        }

    val BIG = ClassType("Lchasm/Big;")
    val LOOP = DexMethod(BIG, "loop", DexType.Int, DexType.Int)

    fun big(
        dexter: Dexter = Dexter(),
        filler: Int = 70_000,
    ): ByteArray =
        dexter.assemble {
            val big = addClass(BIG, DexType.Object)
            big.staticMethod(LOOP, MemberAccess.Public, registerCount = 4) {
                val limit = parameter(0)
                val top = label()
                val exit = label()
                val handler = handlerLabel()
                const(Reg(0), 0)
                bind(top)
                branch(Cond.Ge, Reg(0), limit, exit)
                tryCatch(STACK_OVERFLOW, handler) {
                    repeat(filler) { binary(BinaryOp.AddInt, Reg(1), Reg(1), Reg(0)) }
                }
                val table = labelTable(0, 2, top)
                table[1] = exit
                packedSwitch(Reg(1), table)
                binary(LiteralOp.AddInt, Reg(0), Reg(0), literal = 1)
                goto(top)
                bind(exit)
                returnValue(Reg(1))
                bindHandler(handler)
                const(Reg(1), -1)
                returnValue(Reg(1))
            }
        }
}

object ExceptionSamples {
    private val GUARDED = ClassType("Lchasm/Guarded;")
    private val ARITHMETIC = ClassType("Ljava/lang/ArithmeticException;")
    private val RUNTIME = ClassType("Ljava/lang/RuntimeException;")
    private val DIVIDE = DexMethod(GUARDED, "divide", DexType.Int, DexType.Int)
    private val CLASSIFY = DexMethod(GUARDED, "classify", DexType.Int, DexType.Int)

    fun file(
        dexter: Dexter = Dexter(),
    ): ByteArray {
        val file = dexter.beginFile()
        val guarded = file.addClass(GUARDED, DexType.Object)
        val divide = guarded.beginStaticMethod(DIVIDE, registerCount = 4)
        with(divide) {
            val outerStart = boundLabel()
            const(v(0), 10)
            val innerStart = boundLabel()
            binary(BinaryOp.DivInt, v(1), v(0), parameter(0))
            val innerEnd = boundLabel()
            binary(LiteralOp.AddInt, v(1), v(1), 1)
            val outerEnd = boundLabel()
            returnValue(v(1))
            val innerPad = handlerLabel()
            bindHandler(innerPad, v(2))
            const(v(1), -1)
            returnValue(v(1))
            val outerPad = handlerLabel()
            bindHandler(outerPad, v(2))
            const(v(1), -2)
            returnValue(v(1))
            val anyPad = handlerLabel()
            bindHandler(anyPad, v(2))
            throwValue(v(2))
            catchRange(innerStart, innerEnd, ARITHMETIC, innerPad)
            catchRange(outerStart, outerEnd, RUNTIME, outerPad)
            catchAllRange(outerStart, outerEnd, anyPad)
            finish()
        }
        val classify = guarded.beginStaticMethod(CLASSIFY, registerCount = 2)
        with(classify) {
            val small = label()
            val large = label()
            val cases = SwitchCases()
            cases.case(-1000, small)
            cases.case(1_000_000, large)
            switch(parameter(0), cases)
            const(v(0), 0)
            returnValue(v(0))
            bind(small)
            const(v(0), 1)
            returnValue(v(0))
            bind(large)
            const(v(0), 2)
            returnValue(v(0))
            finish()
        }
        return file.finish()
    }
}

enum class Sample(
    val bytes: () -> ByteArray,
) {
    ADAPTER({ Samples.adapter() }),
    HIERARCHY({ Samples.hierarchy() }),
    BIG({ Samples.big() }),
    BIG_SMALL_REACH({ Samples.big(Dexter { shortBranchReach = 3 }, filler = 20) }),
    EMPTY({ Dexter().assemble { } }),
    EXCEPTIONS({ ExceptionSamples.file() }),
}
