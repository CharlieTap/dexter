package io.github.charlietap.dexter

import org.junit.Assume.assumeTrue
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test

/** Runs only with `DEXTER_BENCH` set; `DEXTER_BENCH_RUNS` overrides the number of timed runs. */
class GenerationBenchmark {

    private val other = ClassType("Lbench/Other;")
    private val owner = ClassType("Lbench/Code;")
    private val field = DexField(other, "k", DexType.Long)
    private val callee = DexMethod(other, "call", DexType.Long, DexType.Int, DexType.Long)
    private val methods = List(METHODS) { DexMethod(owner, "f$it", DexType.Long, DexType.Long, DexType.Long) }
    private val throwable = ClassType("Ljava/lang/Throwable;")
    private val compiledFunction = ClassType("Lbench/CompiledFunction;")
    private val objectConstructor = DexConstructor(DexType.Object)
    private val smallFunctions = List(FUNCTIONS) { DexMethod(owner, "f$it", DexType.Long, DexType.Long, DexType.Long) }
    private val deepFunctions = List(FUNCTIONS) { DexMethod(owner, "deep$it", DexType.Long, DexType.Long, DexType.Long) }
    private val entryTypes = List(FUNCTIONS) { ClassType("Lchasm/jit/Entry$it;") }

    @Test
    fun `write a large file`() {
        measure("large", METHODS * INSTRUCTIONS) { dexter -> emit(dexter) }
    }

    @Test
    fun `write a file of many small functions`() {
        measure("functions", FUNCTIONS * SMALL_INSTRUCTIONS) { dexter -> emitSmallFunctions(dexter, FUNCTIONS) }
    }

    // A small module: per-file costs dominate.
    @Test
    fun `write a tiny file`() {
        measure("tiny", TINY_FUNCTIONS * SMALL_INSTRUCTIONS) { dexter -> emitSmallFunctions(dexter, TINY_FUNCTIONS) }
    }

    // DEXTER_BENCH_ONLY picks one workload by name.
    private fun measure(
        workload: String,
        instructions: Int,
        emit: (Dexter) -> DexFile,
    ) {
        assumeTrue("set DEXTER_BENCH to run", System.getenv("DEXTER_BENCH") != null)
        assumeTrue("not selected", System.getenv("DEXTER_BENCH_ONLY")?.let { it == workload } ?: true)
        val dexter = Dexter()
        // Small workloads repeat until a second has passed, so the JIT has compiled them.
        val warmupStart = System.nanoTime()
        var warmups = 0
        while (warmups < WARMUP || System.nanoTime() - warmupStart < MIN_NANOS) {
            emit(dexter).finish()
            warmups++
        }
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val threadId = Thread.currentThread().id
        val runs = System.getenv("DEXTER_BENCH_RUNS")?.toInt() ?: maxOf(RUNS, warmups)
        val times = LongArray(runs)
        val finishTimes = LongArray(runs)
        var bytes = 0
        val allocatedBefore = threads.getThreadAllocatedBytes(threadId)
        for (run in 0 until runs) {
            val start = System.nanoTime()
            val file = emit(dexter)
            val finishing = System.nanoTime()
            bytes = file.finish().size
            val end = System.nanoTime()
            times[run] = end - start
            finishTimes[run] = end - finishing
        }
        val allocated = (threads.getThreadAllocatedBytes(threadId) - allocatedBefore) / runs
        times.sort()
        finishTimes.sort()
        val median = times[runs / 2]
        val line = "median %.1f us/file (finish %.1f us), %.1f ns/instruction, %d KB allocated/file, %d bytes, label=%s".format(
            median / 1e3,
            finishTimes[runs / 2] / 1e3,
            median.toDouble() / instructions,
            allocated / 1024,
            bytes,
            "${System.getenv("DEXTER_BENCH")}/$workload",
        )
        println(line)
        File("build/benchmark.txt").appendText(line + "\n")
    }

    // Shaped like a JIT batch: a static method pair per function plus an adapter class with three methods.
    private fun emitSmallFunctions(
        dexter: Dexter,
        functions: Int,
    ): DexFile {
        val file = dexter.beginFile()
        val code = file.addClass(owner, DexType.Object)
        for (function in 0 until functions) {
            val compiled = smallFunctions[function]
            for (method in arrayOf(compiled, deepFunctions[function])) {
                val body = code.beginStaticMethod(method, 8)
                body.binary(BinaryOp.AddLong, Reg(0), Reg(4), Reg(6))
                body.binary(LiteralOp.AddInt, Reg(2), Reg(2), function and 0x7F)
                body.invokeStatic(callee, Reg(2), Reg(0))
                body.moveResult(Reg(0))
                body.returnValue(Reg(0))
                body.finish()
            }
            val type = entryTypes[function]
            val adapter = file.addClass(type, DexType.Object, compiledFunction)
            val constructor = adapter.beginConstructor(DexConstructor(type), 1)
            constructor.invokeConstructor(objectConstructor, constructor.self)
            constructor.returnVoid()
            constructor.finish()
            for (name in arrayOf("invoke", "call")) {
                val signature = DexMethod(type, name, DexType.Void, other, DexType.Long.array(), DexType.Int)
                val method = adapter.beginInstanceMethod(signature, 9)
                method.arrayGet(ArrayKind.Wide, Reg(0), Reg(7), Reg(8))
                method.invokeStaticRange(compiled, Reg(0))
                method.moveResult(Reg(0))
                method.arrayPut(ArrayKind.Wide, Reg(0), Reg(7), Reg(8))
                method.returnVoid()
                method.finish()
            }
        }
        return file
    }

    private fun emit(
        dexter: Dexter,
    ): DexFile {
        val file = dexter.beginFile()
        val builder = file.addClass(owner, DexType.Object)
        for (method in methods) {
            val code = builder.beginStaticMethod(method, 40)
            body(code)
            code.finish()
        }
        return file
    }

    private fun body(
        code: CodeEmitter,
    ) {
        val first = code.parameter(0)
        val second = code.parameter(1)
        val segments = code.labels(SEGMENTS + 1)
        val handler = code.handlerLabel()
        val cases = SwitchCases()
        var tryStart = code.boundLabel()
        for (segment in 0 until SEGMENTS) {
            code.bind(segments[segment])
            code.const(Reg(0), segment)
            code.binary(BinaryOp.AddInt, Reg(1), Reg(1), Reg(0))
            code.binary(BinaryOp.AddLong, Reg(2), Reg(2), first)
            code.binary(LiteralOp.AndInt, Reg(3), Reg(1), 0x7F)
            code.arrayGet(ArrayKind.Wide, Reg(4), Reg(20), Reg(3))
            code.getStatic(Reg(6), field)
            code.invokeStatic(callee, Reg(3), Reg(4))
            code.moveResult(Reg(4))
            code.branchZero(Cond.Eq, Reg(3), segments[segment + 1])
            if (segment % 50 == 49) {
                cases.clear()
                cases.case(0, segments[segment - 10])
                cases.case(7, segments[segment + 1])
                cases.case(1000, segments[segment - 3])
                code.switch(Reg(3), cases)
            } else if (segment % 10 == 9) {
                code.goto(segments[segment - 5])
            }
            if (segment % 100 == 99) {
                val end = code.boundLabel()
                code.catchRange(tryStart, end, throwable, handler)
                tryStart = end
            }
        }
        code.bind(segments[SEGMENTS])
        code.returnValue(second)
        code.bindHandler(handler)
        code.returnValue(first)
    }

    private companion object {
        const val METHODS = 100
        const val SEGMENTS = 400
        const val INSTRUCTIONS = SEGMENTS * 10
        const val WARMUP = 200
        const val RUNS = 200
        const val MIN_NANOS = 1_000_000_000L
        const val FUNCTIONS = 2000
        const val TINY_FUNCTIONS = 10
        const val SMALL_INSTRUCTIONS = 22
    }
}
