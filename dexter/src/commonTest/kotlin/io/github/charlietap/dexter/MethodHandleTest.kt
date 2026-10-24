package io.github.charlietap.dexter

import app.cash.burst.Burst
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private val FIELD = DexField(OTHER, "f", DexType.Long)
private val STATIC = DexMethod(OTHER, "s", DexType.Int, DexType.Int)
private val VIRTUAL = DexMethod(OTHER, "v", DexType.Void)

@Burst
class MethodHandleTest {

    enum class Handle(
        val handle: DexMethodHandle,
        val expected: String,
    ) {
        STATIC_PUT(DexMethodHandle.staticPut(FIELD), "0 LOther;->f:J"),
        STATIC_GET(DexMethodHandle.staticGet(FIELD), "1 LOther;->f:J"),
        INSTANCE_PUT(DexMethodHandle.instancePut(FIELD), "2 LOther;->f:J"),
        INSTANCE_GET(DexMethodHandle.instanceGet(FIELD), "3 LOther;->f:J"),
        INVOKE_STATIC(DexMethodHandle.invokeStatic(STATIC), "4 LOther;->s(I)I"),
        INVOKE_INSTANCE(DexMethodHandle.invokeInstance(VIRTUAL), "5 LOther;->v()V"),
        INVOKE_CONSTRUCTOR(DexMethodHandle.invokeConstructor(DexConstructor(OTHER, DexType.Int)), "6 LOther;-><init>(I)V"),
        INVOKE_DIRECT(DexMethodHandle.invokeDirect(VIRTUAL), "7 LOther;->v()V"),
        INVOKE_INTERFACE(DexMethodHandle.invokeInterface(VIRTUAL), "8 LOther;->v()V"),
    }

    @Test
    fun `method handles are written with their kind and member`(
        case: Handle,
    ) {
        val emitted = emit {
            constMethodHandle(v(1), case.handle)
            returnVoid()
        }

        assertEquals(listOf(case.expected), emitted.reader.methodHandles)
    }

    @Test
    fun `const method handle loads the handle's index`() {
        val emitted = emit {
            constMethodHandle(v(1), DexMethodHandle.invokeStatic(STATIC))
            constMethodHandle(v(2), DexMethodHandle.staticGet(FIELD))
            returnVoid()
        }

        assertEquals(hex(units(0x01FE, 0, 0x02FE, 1, 0x000E)), hex(emitted.insns))
    }

    @Test
    fun `equal handles are written once`() {
        val emitted = emit {
            constMethodHandle(v(1), DexMethodHandle.invokeStatic(STATIC))
            constMethodHandle(v(2), DexMethodHandle.invokeStatic(STATIC))
            returnVoid()
        }

        assertEquals(1, emitted.reader.methodHandles.size)
    }

    @Test
    fun `const method type loads the proto`() {
        val type = DexMethodType(DexType.Int, DexType.Long)
        val emitted = emit {
            constMethodType(v(1), type)
            returnVoid()
        }

        assertEquals(hex(units(0x01FF, emitted.proto(type), 0x000E)), hex(emitted.insns))
    }

    @Test
    fun `data starts after the method handles`() {
        val reader = emit {
            constMethodHandle(v(1), DexMethodHandle.invokeStatic(STATIC))
            returnVoid()
        }.reader

        assertEquals(reader.classDefsOff + 32 * reader.classCount + 8, reader.u32(108))
    }

    @Test
    fun `an aborted method leaves no method handles`() {
        val bytes = Dexter().assemble {
            val builder = addClass(T, DexType.Object)
            val code = builder.beginStaticMethod(DexMethod(T, "m", DexType.Void), 2)
            code.constMethodHandle(v(1), DexMethodHandle.invokeStatic(STATIC))
            code.abort()
        }

        assertEquals(emptyList(), DexReader(bytes).methodHandles)
    }

    @Test
    fun `files using method handle constants are dex 039`() {
        val bytes = Dexter().assemble {
            addClass(T, DexType.Object).staticMethod(DexMethod(T, "m", DexType.Void), MemberAccess.Public, 2) {
                constMethodType(v(1), DexMethodType(DexType.Void))
                returnVoid()
            }
        }

        assertContentEquals("dex\n039\u0000".encodeToByteArray(), bytes.copyOfRange(0, 8))
    }
}
