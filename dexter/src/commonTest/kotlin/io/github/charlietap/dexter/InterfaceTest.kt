package io.github.charlietap.dexter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private val SHAPE = ClassType("LShape;")
private val NAMED = ClassType("LNamed;")
private val AREA = DexMethod(SHAPE, "area", DexType.Int)
private val SQUARE = ClassType("LSquare;")

class InterfaceTest {

    private fun shape(): DexReader =
        DexReader(
            Dexter().assemble {
                addInterface(SHAPE, NAMED).addAbstractMethod(AREA)
            },
        )

    @Test
    fun `interfaces are public abstract and extend object`() {
        val shape = shape().classes.single()

        assertEquals(listOf(0x0601, "Ljava/lang/Object;", listOf("LNamed;")), listOf(shape.access, shape.superclass, shape.interfaces))
    }

    @Test
    fun `abstract methods are public abstract virtual methods without code`() {
        val method = shape().classes.single().virtualMethods.single()

        assertEquals(listOf(0x0401, 0), listOf(method.access, method.codeOff))
    }

    @Test
    fun `classes implement generated interfaces and call through them`() {
        val bytes = Dexter().assemble {
            addInterface(SHAPE).addAbstractMethod(AREA)
            val square = addClass(SQUARE, DexType.Object, SHAPE)
            square.instanceMethod(DexMethod(SQUARE, "area", DexType.Int), MemberAccess.Public, registerCount = 1) {
                const(v(0), 4)
                returnValue(v(0))
            }
            square.staticMethod(DexMethod(SQUARE, "measure", DexType.Int, SHAPE), MemberAccess.Public, registerCount = 2) {
                invokeInterface(AREA, parameter(0))
                moveResult(v(0))
                returnValue(v(0))
            }
        }

        assertEquals(listOf("LShape;"), DexReader(bytes).classes.single { it.type == "LSquare;" }.interfaces)
    }

    @Test
    fun `abstract methods belong to their interface`() {
        assertFailsWith<IllegalArgumentException> {
            Dexter().assemble { addInterface(SHAPE).addAbstractMethod(DexMethod(SQUARE, "area", DexType.Int)) }
        }
    }

    @Test
    fun `an abstract method is declared once`() {
        assertFailsWith<IllegalArgumentException> {
            Dexter().assemble {
                val shape = addInterface(SHAPE)
                shape.addAbstractMethod(AREA)
                shape.addAbstractMethod(AREA)
            }
        }
    }
}
