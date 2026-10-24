package io.github.charlietap.dexter

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val M = DexMethod(T, "m", DexType.Void)
private val N = DexMethod(T, "n", DexType.Void)

class BuilderTest {

    private val empty = Dexter().assemble { addClass(T, DexType.Object) }

    @Test
    fun `a method cannot begin while another is open`() {
        val builder = Dexter().beginFile().addClass(T, DexType.Object)
        builder.beginStaticMethod(M, 0)

        assertFailsWith<IllegalStateException> { builder.beginStaticMethod(N, 0) }
    }

    @Test
    fun `a file cannot finish while a method is open`() {
        val file = Dexter().beginFile()
        file.addClass(T, DexType.Object).beginStaticMethod(M, 0)

        assertFailsWith<IllegalStateException> { file.finish() }
    }

    @Test
    fun `end needs an open method`() {
        val code = Dexter().beginFile().addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.returnVoid()
        code.finish()

        assertFailsWith<IllegalStateException> { code.finish() }
    }

    @Test
    fun `abort discards the method`() {
        val file = Dexter().beginFile()
        val code = file.addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.invokeStatic(DexMethod(OTHER, "x", DexType.Void))
        code.abort()

        assertContentEquals(empty, file.finish())
    }

    @Test
    fun `abort twice is harmless`() {
        val file = Dexter().beginFile()
        val code = file.addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.abort()
        code.abort()

        assertContentEquals(empty, file.finish())
    }

    @Test
    fun `a rejected end leaves the method open`() {
        val code = Dexter().beginFile().addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.goto(code.label())
        ignoringFailure { code.finish() }

        assertTrue(code.isOpen)
    }

    @Test
    fun `a method too large for end is aborted`() {
        val file = Dexter { maxMethodCodeUnits = 1 }.beginFile()
        val code = file.addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.returnVoid()
        code.returnVoid()
        ignoringFailure { code.finish() }

        assertContentEquals(empty, file.finish())
    }

    @Test
    fun `a method over its own limit is aborted`() {
        val file = Dexter().beginFile()
        val code = file.addClass(T, DexType.Object).beginStaticMethod(M, 0)
        repeat(3) { code.returnVoid() }
        ignoringFailure { code.finish(maxCodeUnits = 2) }

        assertContentEquals(empty, file.finish())
    }

    @Test
    fun `a method at its own limit is kept`() {
        val bytes = Dexter().assemble {
            val code = addClass(T, DexType.Object).beginStaticMethod(M, 0)
            repeat(2) { code.returnVoid() }
            code.finish(maxCodeUnits = 2)
        }

        assertEquals(2, DexReader(bytes).method(M.toString()).insns.size)
    }

    @Test
    fun `a method's own limit counts its payloads`() {
        assertFailsWith<DexterMethodTooLargeException> {
            Dexter().assemble {
                val code = addClass(T, DexType.Object).beginStaticMethod(M, 1)
                val target = code.boundLabel()
                code.packedSwitch(v(0), code.labelTable(0, 1, target))
                code.returnVoid()
                code.finish(maxCodeUnits = 9)
            }
        }
    }

    @Test
    fun `a method's own limit cannot raise the global one`() {
        assertFailsWith<DexterMethodTooLargeException> {
            Dexter { maxMethodCodeUnits = 1 }.assemble {
                val code = addClass(T, DexType.Object).beginStaticMethod(M, 0)
                repeat(2) { code.returnVoid() }
                code.finish(maxCodeUnits = 100)
            }
        }
    }

    @Test
    fun `a method's own limit must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            Dexter().assemble {
                val code = addClass(T, DexType.Object).beginStaticMethod(M, 0)
                code.returnVoid()
                code.finish(maxCodeUnits = 0)
            }
        }
    }

    @Test
    fun `an aborted file leaves the workspace usable`() {
        val dexter = Dexter()
        val file = dexter.beginFile()
        file.addClass(OTHER, DexType.Object).beginStaticMethod(DexMethod(OTHER, "x", DexType.Void), 0)
        file.abort()

        assertContentEquals(empty, dexter.assemble { addClass(T, DexType.Object) })
    }

    @Test
    fun `an aborted file rejects its builders`() {
        val file = Dexter().beginFile()
        file.abort()

        assertFailsWith<IllegalStateException> { file.addClass(T, DexType.Object) }
    }

    @Test
    fun `a finished file rejects its class builders`() {
        val file = Dexter().beginFile()
        val builder = file.addClass(T, DexType.Object)
        file.finish()

        assertFailsWith<IllegalStateException> { builder.beginStaticMethod(M, 0) }
    }

    @Test
    fun `an instance method's receiver sits below its parameters`() {
        val builder = Dexter().beginFile().addClass(T, DexType.Object)

        val self = builder.beginInstanceMethod(DexMethod(T, "m", DexType.Void, DexType.Long), 10).self
        assertEquals(7, self.index)
    }

    @Test
    fun `a constructor's receiver sits below its parameters`() {
        val builder = Dexter().beginFile().addClass(T, DexType.Object)

        val self = builder.beginConstructor(DexConstructor(T, DexType.Int), 4).self
        assertEquals(2, self.index)
    }

    @Test
    fun `a bound label marks the next instruction`() {
        val insns = emit {
            val top = boundLabel()
            const(v(0), 0)
            goto(top)
        }.insns

        assertEquals(hex(units(0x0012, 0xFF28)), hex(insns))
    }

    @Test
    fun `a label is bound once bind is called`() {
        val states = mutableListOf<Boolean>()
        emit {
            val label = label()
            states += isBound(label)
            bind(label)
            states += isBound(label)
            returnVoid()
        }

        assertEquals(listOf(false, true), states)
    }

    @Test
    fun `isBound rejects a label from another method`() {
        var escaped: Label? = null
        emit {
            escaped = label()
            returnVoid()
        }

        assertFailsWith<IllegalArgumentException> { emit { isBound(escaped!!) } }
    }

    @Test
    fun `a closed method interns nothing when a call is rejected`() {
        val file = Dexter().beginFile()
        val code = file.addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.returnVoid()
        code.finish()
        ignoringFailure { code.invokeStatic(DexMethod(OTHER, "unseen", DexType.Void)) }
        ignoringFailure { code.invokeStaticRange(DexMethod(OTHER, "unseen", DexType.Void), v(0)) }
        val expected = Dexter().assemble {
            addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) { returnVoid() }
        }

        assertContentEquals(expected, file.finish())
    }

    @Test
    fun `a closed method rejects calls`() {
        val code = Dexter().beginFile().addClass(T, DexType.Object).beginStaticMethod(M, 0)
        code.returnVoid()
        code.finish()

        assertFailsWith<IllegalStateException> { code.invokeStatic(DexMethod(OTHER, "unseen", DexType.Void)) }
    }

    @Test
    fun `no method is open after finishing`() {
        val dexter = Dexter()
        dexter.assemble { addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) { returnVoid() } }

        assertFalse(dexter.code.isOpen)
    }

    @Test
    fun `a method ending exactly at the code buffer's capacity is written`() {
        val bytes = Dexter().assemble {
            val builder = addClass(T, DexType.Object)
            builder.staticMethod(M, MemberAccess.Public, 0) { returnVoid() }
            builder.staticMethod(N, MemberAccess.Public, 1) {
                repeat(32766) { const(v(0), 0) }
                returnVoid()
            }
        }

        assertEquals(32767, DexReader(bytes).method(N.toString()).insns.size)
    }
}
