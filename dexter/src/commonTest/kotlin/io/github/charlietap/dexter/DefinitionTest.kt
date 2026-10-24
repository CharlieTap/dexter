package io.github.charlietap.dexter

import app.cash.burst.Burst
import app.cash.burst.burstValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

private val M = DexMethod(T, "m", DexType.Void)

@Burst
class DefinitionTest {

    @Test
    fun `invalid class descriptors are rejected`(
        descriptor: String = burstValues("", "I", "I;", "Lfoo", "L;", "[I", "La b;", "La//b;", "Q"),
    ) {
        assertFailsWith<IllegalArgumentException> { ClassType(descriptor) }
    }

    @Test
    fun `invalid member names are rejected`(
        name: String = burstValues("", "a b", "<init>", "<clinit>", "a;b", "\uD83D"),
    ) {
        assertFailsWith<IllegalArgumentException> { DexMethod(T, name, DexType.Void) }
    }

    @Test
    fun `invalid field names are rejected`(
        name: String = burstValues("", "a b", "<init>", "a/b"),
    ) {
        assertFailsWith<IllegalArgumentException> { DexField(T, name, DexType.Int) }
    }

    @Test
    fun `names may use the SimpleName unicode ranges`() {
        val method = DexMethod(T, "é😀", DexType.Void)

        assertEquals("é😀", method.name)
    }

    @Test
    fun `arrays may have 255 dimensions`() {
        val type = (0 until 255).fold<Int, ValueType>(DexType.Int) { element, _ -> element.array() }

        assertEquals("[".repeat(255) + "I", type.descriptor)
    }

    @Test
    fun `arrays may not have 256 dimensions`() {
        val type = (0 until 255).fold<Int, ValueType>(DexType.Int) { element, _ -> element.array() }

        assertFailsWith<IllegalArgumentException> { type.array() }
    }

    @Test
    fun `class types can be made from binary names`() {
        val type = ClassType.fromName("java.lang.String")

        assertEquals(DexType.String, type)
    }

    @Test
    fun `methods compare structurally`() {
        val method = DexMethod(T, "a", DexType.Int, DexType.Long)

        assertEquals(DexMethod(T, "a", DexType.Int, listOf(DexType.Long)), method)
    }

    @Test
    fun `methods with different returns differ`() {
        val method = DexMethod(T, "a", DexType.Int)

        assertNotEquals(DexMethod(T, "a", DexType.Long), method)
    }

    @Test
    fun `constructors are void methods named init`() {
        val constructor = DexConstructor(T, DexType.Int)

        assertEquals("LT;-><init>(I)V", constructor.toString())
    }

    @Test
    fun `parameters are copied`() {
        val list = mutableListOf<ValueType>(DexType.Int)
        val method = DexMethod(T, "m", DexType.Void, list)
        list.add(DexType.Long)

        assertEquals(listOf(DexType.Int), method.parameters)
    }

    @Test
    fun `parameters may take at most 255 words`() {
        assertFailsWith<IllegalArgumentException> { DexMethod(T, "m", DexType.Void, List(128) { DexType.Long }) }
    }

    enum class Rejected(
        val block: DexFile.() -> Unit,
    ) {
        EXTENDS_ITSELF({ addClass(T, T) }),
        DUPLICATE_INTERFACE({ addClass(T, DexType.Object, DexType.String, DexType.String) }),
        DUPLICATE_CLASS({
            addClass(T, DexType.Object)
            addClass(T, DexType.Object)
        }),
        FIELD_OF_ANOTHER_CLASS({ addClass(T, DexType.Object).addStaticField(DexField(OTHER, "f", DexType.Int)) }),
        DUPLICATE_FIELD({
            val builder = addClass(T, DexType.Object)
            builder.addStaticField(DexField(T, "f", DexType.Int))
            builder.addStaticField(DexField(T, "f", DexType.Int), MemberAccess.Private)
        }),
        METHOD_OF_ANOTHER_CLASS({
            addClass(T, DexType.Object).staticMethod(DexMethod(OTHER, "m", DexType.Void), MemberAccess.Public, 0) { returnVoid() }
        }),
        TOO_FEW_REGISTERS({ addClass(T, DexType.Object).instanceMethod(M, MemberAccess.Public, 0) { returnVoid() } }),
        TOO_MANY_REGISTERS({ addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 65536) { returnVoid() } }),
        RECEIVER_PAST_255_WORDS({
            val method = DexMethod(T, "m", DexType.Void, List(127) { DexType.Long } + DexType.Int)
            addClass(T, DexType.Object).instanceMethod(method, MemberAccess.Public, 300) { returnVoid() }
        }),
        DUPLICATE_METHOD({
            val builder = addClass(T, DexType.Object)
            builder.staticMethod(M, MemberAccess.Public, 0) { returnVoid() }
            builder.instanceMethod(M, MemberAccess.Public, 1) { returnVoid() }
        }),
        DUPLICATE_CONSTRUCTOR({
            val builder = addClass(T, DexType.Object)
            builder.constructor(DexConstructor(T), ConstructorAccess.Public, 1) { returnVoid() }
            builder.constructor(DexConstructor(T), ConstructorAccess.Private, 1) { returnVoid() }
        }),
    }

    @Test
    fun `invalid definitions are rejected`(
        case: Rejected,
    ) {
        assertFailsWith<IllegalArgumentException> { Dexter().assemble(case.block) }
    }

    enum class IllegalState(
        val block: DexFile.() -> Unit,
    ) {
        CLASS_CYCLE({
            addClass(T, OTHER)
            addClass(OTHER, T)
        }),
        EMPTY_BODY({ addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) { } }),
        METHOD_INSIDE_A_BODY({
            val builder = addClass(T, DexType.Object)
            builder.staticMethod(M, MemberAccess.Public, 0) { builder.staticInitializer(0) { returnVoid() } }
        }),
        FIELD_INSIDE_A_BODY({
            val builder = addClass(T, DexType.Object)
            builder.staticMethod(M, MemberAccess.Public, 0) { builder.addStaticField(DexField(T, "f", DexType.Int)) }
        }),
        CLASS_INSIDE_A_BODY({
            val assembly = this
            addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) { assembly.addClass(OTHER, DexType.Object) }
        }),
        CODE_AFTER_ITS_METHOD({
            var code: CodeEmitter? = null
            addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) {
                code = this
                returnVoid()
            }
            code!!.returnVoid()
        }),
        LABEL_AFTER_ITS_METHOD({
            var code: CodeEmitter? = null
            addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) {
                code = this
                returnVoid()
            }
            code!!.label()
        }),
        REGISTER_AFTER_ITS_METHOD({
            var code: CodeEmitter? = null
            addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 1) {
                code = this
                returnVoid()
            }
            code!!.const(v(0), 0)
        }),
        RETURN_VOID_FROM_VALUE_METHOD({
            addClass(T, DexType.Object).staticMethod(DexMethod(T, "i", DexType.Int), MemberAccess.Public, 0) { returnVoid() }
        }),
        RETURN_VALUE_FROM_VOID_METHOD({
            addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 1) { returnValue(v(0)) }
        }),
    }

    @Test
    fun `out of order use is rejected`(
        case: IllegalState,
    ) {
        assertFailsWith<IllegalStateException> { Dexter().assemble(case.block) }
    }

    @Test
    fun `only one file is open at a time`() {
        val dexter = Dexter()
        dexter.beginFile()

        assertFailsWith<IllegalStateException> { dexter.beginFile() }
    }

    @Test
    fun `a class builder kept from an earlier file is rejected`() {
        var builder: ClassBuilder? = null
        Dexter().assemble { builder = addClass(T, DexType.Object) }

        assertFailsWith<IllegalStateException> { builder!!.addStaticField(DexField(T, "f", DexType.Int)) }
    }

    @Test
    fun `a file kept after it is finished is rejected`() {
        var assembly: DexFile? = null
        Dexter().assemble { assembly = this }

        assertFailsWith<IllegalStateException> { assembly!!.addClass(T, DexType.Object) }
    }

    @Test
    fun `parameters sit in the last registers`(
        isStatic: Boolean,
    ) {
        var registers = emptyList<Int>()
        emit(registers = 10, parameters = listOf(DexType.Int, DexType.Long, DexType.Object), isStatic = isStatic) {
            registers = listOf(parameter(0).index, parameter(1).index, parameter(2).index)
            returnVoid()
        }

        assertEquals(listOf(6, 7, 9), registers)
    }

    @Test
    fun `instance methods receive the receiver below the parameters`() {
        var receiver = -1
        Dexter().assemble {
            addClass(T, DexType.Object).instanceMethod(DexMethod(T, "m", DexType.Void, DexType.Long), MemberAccess.Public, 10) {
                receiver = self.index
                returnVoid()
            }
        }

        assertEquals(7, receiver)
    }

    enum class Flags(
        val define: ClassBuilder.() -> Unit,
        val expected: Int,
    ) {
        STATIC_FIELD({ addStaticField(DexField(T, "f", DexType.Int)) }, 0x0009),
        FINAL_STATIC_FIELD({ addStaticField(DexField(T, "f", DexType.Int), MemberAccess.Private.final()) }, 0x001A),
        SYNTHETIC_STATIC_FIELD({ addStaticField(DexField(T, "f", DexType.Int), MemberAccess.Package.synthetic()) }, 0x1008),
        STATIC_METHOD({ staticMethod(M, MemberAccess.Public, 0) { returnVoid() } }, 0x0009),
        INSTANCE_METHOD({ instanceMethod(M, MemberAccess.Protected.final(), 1) { returnVoid() } }, 0x0014),
        CONSTRUCTOR({ constructor(DexConstructor(T), ConstructorAccess.Public.synthetic(), 1) { returnVoid() } }, 0x11001),
        STATIC_INITIALIZER({ staticInitializer(0) { returnVoid() } }, 0x10008),
        DEFAULT_STATIC_METHOD({ beginStaticMethod(M, 0).apply { returnVoid() }.finish() }, 0x0009),
        DEFAULT_INSTANCE_METHOD({ beginInstanceMethod(M, 1).apply { returnVoid() }.finish() }, 0x0001),
        DEFAULT_CONSTRUCTOR({ beginConstructor(DexConstructor(T), 1).apply { returnVoid() }.finish() }, 0x10001),
    }

    @Test
    fun `members are written with their access flags`(
        case: Flags,
    ) {
        val reader = DexReader(Dexter().assemble { addClass(T, DexType.Object).apply(case.define) })

        val members = reader.classes.single().let { it.staticFields + it.directMethods + it.virtualMethods }
        assertEquals(listOf(case.expected), members.map { it.access })
    }

    enum class ClassFlags(
        val access: ClassAccess,
        val expected: Int,
    ) {
        PUBLIC(ClassAccess.Public, 0x0001),
        PUBLIC_FINAL(ClassAccess.PublicFinal, 0x0011),
        PUBLIC_ABSTRACT(ClassAccess.PublicAbstract, 0x0401),
        PACKAGE(ClassAccess.Package, 0x0000),
        PACKAGE_FINAL(ClassAccess.PackageFinal, 0x0010),
        PACKAGE_ABSTRACT(ClassAccess.PackageAbstract, 0x0400),
        SYNTHETIC(ClassAccess.PublicFinal.synthetic(), 0x1011),
    }

    @Test
    fun `classes are written with their access flags`(
        case: ClassFlags,
    ) {
        val reader = DexReader(Dexter().assemble { addClass(T, DexType.Object, access = case.access) })

        assertEquals(case.expected, reader.classes.single().access)
    }

    @Test
    fun `classes are public and final by default`() {
        val reader = DexReader(Dexter().assemble { addClass(T, DexType.Object) })

        assertEquals(0x0011, reader.classes.single().access)
    }

    @Test
    fun `try blocks skip empty ranges`() {
        val tries = emit {
            val handler = handlerLabel()
            tryCatch(THROWABLE, handler) { }
            bindHandler(handler)
            returnVoid()
        }.code.tries

        assertEquals(0, tries.size)
    }

    @Test
    fun `options must allow at least one code unit`() {
        assertFailsWith<IllegalArgumentException> { Dexter { maxMethodCodeUnits = 0 } }
    }

    @Test
    fun `options are read once`() {
        var options: DexterOptions? = null
        val dexter = Dexter {
            options = this
            maxMethodCodeUnits = 1
        }
        options!!.maxMethodCodeUnits = 100

        assertFailsWith<DexterMethodTooLargeException> {
            dexter.assemble { addClass(T, DexType.Object).staticMethod(M, MemberAccess.Public, 0) { repeat(2) { returnVoid() } } }
        }
    }
}
