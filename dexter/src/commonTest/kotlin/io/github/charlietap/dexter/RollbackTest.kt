package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private class Unsupported : RuntimeException()

private val A = DexMethod(T, "a", DexType.Void)
private val B = DexMethod(T, "b", DexType.Int, DexType.Long)
private val C = DexMethod(T, "c", DexType.Void)
private val HELPER = DexMethod(ClassType("LHelper;"), "help", DexType.Long, ClassType("LNever;"))
private val FIELD = DexField(ClassType("LHelper;"), "f", ClassType("LAlsoNever;"))
private val ELSEWHERE = ClassType("LElsewhere;")

private fun DexFile.before(): ClassBuilder {
    val builder = addClass(T, DexType.Object)
    builder.staticMethod(A, MemberAccess.Public, 2) {
        const(v(0), 1)
        returnVoid()
    }
    return builder
}

private fun ClassBuilder.after() {
    staticMethod(C, MemberAccess.Public, 1) {
        val label = label()
        goto(label)
        bind(label)
        returnVoid()
    }
}

private val EXPECTED: ByteArray by lazy { Dexter().assemble { before().after() } }

@Burst
class RollbackTest {

    enum class Failure(
        val exception: KClass<out Throwable>,
        val define: ClassBuilder.() -> Unit,
        val options: DexterOptions.() -> Unit = {},
    ) {
        THROWS_MIDWAY(Unsupported::class, {
            staticMethod(B, MemberAccess.Public, 8) {
                val top = label()
                bind(top)
                invokeStatic(HELPER, v(0))
                moveResult(v(2))
                getStatic(v(1), FIELD)
                packedSwitch(v(0), labelTable(0, 1, top))
                tryCatch(ClassType("LNeverThrown;"), handlerLabel()) {
                    const(v(0), 70000)
                    throw Unsupported()
                }
            }
        }),
        TOO_LARGE(DexterMethodTooLargeException::class, {
            staticMethod(B, MemberAccess.Public, 8) {
                getStatic(v(1), FIELD)
                repeat(3) { const(v(0), 0) }
                returnValue(v(0))
            }
        }, { maxMethodCodeUnits = 5 }),

        TYPES_FULL(DexterFileFullException::class, {
            staticMethod(B, MemberAccess.Public, 8) { returnValue(v(0)) }
        }, { maxTypeIds = 4 }),
        METHODS_FULL(DexterFileFullException::class, {
            staticMethod(B, MemberAccess.Public, 8) {
                invokeStatic(HELPER, v(0))
                returnValue(v(0))
            }
        }, { maxMethodIds = 2 }),
        FIELDS_FULL(DexterFileFullException::class, {
            addStaticField(DexField(T, "x", DexType.Int))
        }, { maxFieldIds = 0 }),
        STRINGS_FULL(DexterFileFullException::class, {
            staticMethod(B, MemberAccess.Public, 8) {
                constString(v(0), "never")
                returnValue(v(0))
            }
        }, { maxStringIds = 4 }),
        DUPLICATE(IllegalArgumentException::class, {
            staticMethod(A, MemberAccess.Public, 1) { returnVoid() }
        }),
    }

    private fun assembleWith(
        failure: Failure,
    ): Pair<Throwable?, ByteArray> {
        var thrown: Throwable? = null
        val bytes = Dexter(failure.options).assemble {
            val builder = before()
            thrown = runCatching { builder.apply(failure.define) }.exceptionOrNull()
            builder.after()
        }
        return thrown to bytes
    }

    @Test
    fun `a failed definition throws`(
        case: Failure,
    ) {
        val (thrown, _) = assembleWith(case)

        assertEquals(case.exception, thrown?.let { it::class })
    }

    @Test
    fun `a failed definition leaves no trace`(
        case: Failure,
    ) {
        val (_, bytes) = assembleWith(case)

        assertContentEquals(EXPECTED, bytes)
    }

    enum class CaughtRejection(
        val call: CodeEmitter.() -> Unit,
        val options: DexterOptions.() -> Unit = {},
    ) {
        SCATTERED_INVOKE({ invokeStatic(DexMethod(ELSEWHERE, "f", DexType.Void, DexType.Int, DexType.Int), v(0), v(16)) }),
        RANGE_PAST_LAST_REGISTER({ invokeStaticRange(DexMethod(ELSEWHERE, "g", DexType.Void, DexType.Long), v(19)) }),
        STATIC_FIELD_PAIR_PAST_LAST_REGISTER({ getStatic(v(19), DexField(ELSEWHERE, "x", DexType.Long)) }),
        NEW_ARRAY_HIGH_REGISTER({ newArray(v(0), v(16), ELSEWHERE.array()) }),
        LITERAL_TOO_LARGE({ binary(LiteralOp.AddInt, v(16), v(16), 1000) }),
    }

    @Test
    fun `a caught rejection inside a body leaves no trace`(
        case: CaughtRejection,
    ) {
        val with = Dexter(case.options).assemble {
            addClass(T, DexType.Object).staticMethod(C, MemberAccess.Public, 20) {
                ignoringFailure { case.call(this) }
                returnVoid()
            }
        }
        val without = Dexter().assemble {
            addClass(T, DexType.Object).staticMethod(C, MemberAccess.Public, 20) { returnVoid() }
        }

        assertContentEquals(without, with)
    }

    enum class PoolFull(
        val call: CodeEmitter.() -> Unit,
        val options: DexterOptions.() -> Unit,
    ) {
        METHODS({ invokeStatic(DexMethod(ELSEWHERE, "h", DexType.Void)) }, { maxMethodIds = 1 }),

        // Elsewhere's type fits but J does not, so the type interned part way must be undone.
        TYPES_PART_WAY({ invokeStatic(DexMethod(ELSEWHERE, "h", DexType.Void, DexType.Long), v(0)) }, { maxTypeIds = 4 }),
        FIELDS({ getStatic(v(0), DexField(ELSEWHERE, "x", DexType.Int)) }, { maxFieldIds = 0 }),
    }

    @Test
    fun `a full pool aborts the method`(
        case: PoolFull,
    ) {
        val with = Dexter(case.options).assemble {
            val code = addClass(T, DexType.Object).beginStaticMethod(C, 20)
            ignoringFailure { case.call(code) }
        }
        val without = Dexter().assemble { addClass(T, DexType.Object) }

        assertContentEquals(without, with)
    }

    @Test
    fun `a full pool leaves no method open`(
        case: PoolFull,
    ) {
        val file = Dexter(case.options).beginFile()
        val code = file.addClass(T, DexType.Object).beginStaticMethod(C, 20)
        ignoringFailure { case.call(code) }

        assertFailsWith<IllegalStateException> { code.finish() }
    }

    @Test
    fun `65536 methods fit`() {
        val methods = List(65536) { DexMethod(T, "m$it", DexType.Void) }
        val bytes = Dexter().assemble {
            addClass(T, DexType.Object).staticMethod(methods[0], MemberAccess.Public, 0) {
                for (index in 1 until 65536) invokeStatic(methods[index])
                returnVoid()
            }
        }

        assertEquals(65536, DexReader(bytes).methodCount)
    }

    @Test
    fun `the 65537th method does not fit`() {
        val methods = List(65537) { DexMethod(T, "m$it", DexType.Void) }
        val code = Dexter().beginFile().addClass(T, DexType.Object).beginStaticMethod(methods[0], 0)
        for (index in 1 until 65536) code.invokeStatic(methods[index])

        assertFailsWith<DexterFileFullException> { code.invokeStatic(methods[65536]) }
    }

    @Test
    fun `a failure outside any method leaves the workspace usable`() {
        val dexter = Dexter()
        ignoringFailure {
            dexter.assemble {
                before()
                throw Unsupported()
            }
        }

        assertContentEquals(EXPECTED, dexter.assemble { before().after() })
    }

    @Test
    fun `a file without const string ignores the string limit`() {
        val bytes = Dexter { maxStringIds = 1 }.assemble { before().after() }

        assertContentEquals(EXPECTED, bytes)
    }

    @Test
    fun `strings added after a const string count against the limit`() {
        val dexter = Dexter { maxStringIds = 20 }

        assertFailsWith<DexterFileFullException> {
            emit(dexter = dexter) {
                constString(v(0), "x")
                for (index in 0 until 20) invokeStatic(DexMethod(ClassType("LOwner$index;"), "m", DexType.Void))
            }
        }
    }
}
