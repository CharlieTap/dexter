package io.github.charlietap.dexter

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val VAR_HANDLE = ClassType("Ljava/lang/invoke/VarHandle;")
private val GET = DexMethod(VAR_HANDLE, "get", DexType.Object, DexType.Object.array())
private val INT_AT = DexMethodType(DexType.Int, DexType.Byte.array(), DexType.Int)

class PolymorphicTest {

    @Test
    fun `packed calls carry the method and the call site type`() {
        val emitted = emit {
            invokePolymorphic(GET, INT_AT, v(0), v(1), v(2))
            moveResult(v(3))
            returnVoid()
        }

        val method = emitted.method(GET)
        val proto = emitted.proto(INT_AT)
        assertEquals(hex(units(0x30FA, method, 0x0210, proto, 0x030A, 0x000E)), hex(emitted.insns))
    }

    @Test
    fun `range calls carry the method and the call site type`() {
        val emitted = emit {
            invokePolymorphicRange(GET, INT_AT, v(20))
            returnVoid()
        }

        assertEquals(hex(units(0x03FB, emitted.method(GET), 20, emitted.proto(INT_AT), 0x000E)), hex(emitted.insns))
    }

    @Test
    fun `consecutive high registers use the range form`() {
        val emitted = emit {
            invokePolymorphic(GET, INT_AT, v(20), v(21), v(22))
            returnVoid()
        }

        assertEquals(hex(units(0x03FB, emitted.method(GET), 20, emitted.proto(INT_AT), 0x000E)), hex(emitted.insns))
    }

    @Test
    fun `wide arguments take two words`() {
        val set = DexMethod(VAR_HANDLE, "set", DexType.Void, DexType.Object.array())
        val type = DexMethodType(DexType.Void, DexType.Long)
        val emitted = emit {
            invokePolymorphic(set, type, v(0), v(1))
            returnVoid()
        }

        assertEquals(hex(units(0x30FA, emitted.method(set), 0x0210, emitted.proto(type), 0x000E)), hex(emitted.insns))
    }

    @Test
    fun `move result follows the call site return type`() {
        val emitted = emit {
            invokePolymorphic(GET, DexMethodType(DexType.Long, DexType.Byte.array(), DexType.Int), v(0), v(1), v(2))
            moveResult(v(4))
            returnVoid()
        }

        assertEquals(0x040B, emitted.insns[4])
    }

    @Test
    fun `the arguments must match the call site type`() {
        assertFailsWith<IllegalArgumentException> { emit { invokePolymorphic(GET, INT_AT, v(0), v(1)) } }
    }

    @Test
    fun `files using polymorphic calls are dex 038`() {
        val bytes = Dexter().assemble {
            addClass(T, DexType.Object).staticMethod(DexMethod(T, "m", DexType.Void), MemberAccess.Public, 3) {
                invokePolymorphic(GET, INT_AT, v(0), v(1), v(2))
                returnVoid()
            }
        }

        assertContentEquals("dex\n038\u0000".encodeToByteArray(), bytes.copyOfRange(0, 8))
    }

    @Test
    fun `an aborted polymorphic call leaves the file at dex 035`() {
        val bytes = Dexter().assemble {
            val builder = addClass(T, DexType.Object)
            val code = builder.beginStaticMethod(DexMethod(T, "m", DexType.Void), 3)
            code.invokePolymorphic(GET, INT_AT, v(0), v(1), v(2))
            code.abort()
        }

        assertContentEquals("dex\n035\u0000".encodeToByteArray(), bytes.copyOfRange(0, 8))
    }
}
