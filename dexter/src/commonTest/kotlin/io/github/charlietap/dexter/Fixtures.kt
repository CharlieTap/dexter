package io.github.charlietap.dexter

internal val T = ClassType("LT;")
internal val OTHER = ClassType("LOther;")
internal val THROWABLE = ClassType("Ljava/lang/Throwable;")

internal class Emitted(
    val reader: DexReader,
    val code: DexReader.CodeItem,
) {
    val insns: IntArray get() = code.insns

    fun method(
        signature: Any,
    ): Int = reader.methods.indexOf(signature.toString()).also { check(it >= 0) { "No method $signature" } }

    fun field(
        signature: Any,
    ): Int = reader.fields.indexOf(signature.toString()).also { check(it >= 0) { "No field $signature" } }

    fun type(
        descriptor: Any,
    ): Int = reader.types.indexOf(descriptor.toString()).also { check(it >= 0) { "No type $descriptor" } }

    fun proto(
        signature: Any,
    ): Int = reader.protos.indexOf(signature.toString()).also { check(it >= 0) { "No proto $signature" } }

    fun string(
        value: String,
    ): Int = reader.strings.indexOf(value).also { check(it >= 0) { "No string $value" } }
}

internal fun emit(
    registers: Int = 300,
    returns: DexType = DexType.Void,
    parameters: List<ValueType> = emptyList(),
    isStatic: Boolean = true,
    dexter: Dexter = Dexter(),
    body: CodeEmitter.() -> Unit,
): Emitted {
    val method = DexMethod(T, "m", returns, parameters)
    val bytes = dexter.assemble {
        val builder = addClass(T, DexType.Object)
        if (isStatic) {
            builder.staticMethod(method, MemberAccess.Public, registers, body)
        } else {
            builder.instanceMethod(method, MemberAccess.Public, registers) { body() }
        }
    }
    val reader = DexReader(bytes)
    return Emitted(reader, reader.method(method.toString()))
}

internal fun reaching(
    reach: Int,
): Dexter = Dexter { shortBranchReach = reach }

internal fun v(
    index: Int,
): Reg = Reg(index)

internal inline fun ignoringFailure(
    block: () -> Unit,
) {
    try {
        block()
    } catch (_: Exception) {
    }
}

// Stand-ins for a later DSL; a body that throws aborts its method.

internal fun Dexter.assemble(
    block: DexFile.() -> Unit,
): ByteArray {
    val file = beginFile()
    try {
        file.block()
        return file.finish()
    } catch (failure: Throwable) {
        file.abort()
        throw failure
    }
}

internal fun ClassBuilder.staticMethod(
    method: DexMethod,
    access: MemberAccess,
    registerCount: Int,
    body: CodeEmitter.() -> Unit,
) {
    beginStaticMethod(method, registerCount, access).finishWith(body)
}

internal fun ClassBuilder.instanceMethod(
    method: DexMethod,
    access: MemberAccess,
    registerCount: Int,
    body: InstanceCodeEmitter.() -> Unit,
) {
    beginInstanceMethod(method, registerCount, access).finishWith(body)
}

internal fun ClassBuilder.constructor(
    constructor: DexConstructor,
    access: ConstructorAccess,
    registerCount: Int,
    body: InstanceCodeEmitter.() -> Unit,
) {
    beginConstructor(constructor, registerCount, access).finishWith(body)
}

internal fun ClassBuilder.staticInitializer(
    registerCount: Int,
    body: CodeEmitter.() -> Unit,
) {
    beginStaticInitializer(registerCount).finishWith(body)
}

private fun <C : CodeEmitter> C.finishWith(
    body: C.() -> Unit,
) {
    try {
        body()
        finish()
    } catch (failure: Throwable) {
        abort()
        throw failure
    }
}

internal fun CodeEmitter.tryCatch(
    exception: ClassType,
    handler: HandlerLabel,
    body: CodeEmitter.() -> Unit,
) {
    val start = boundLabel()
    try {
        body()
    } finally {
        catchRange(start, boundLabel(), exception, handler)
    }
}
