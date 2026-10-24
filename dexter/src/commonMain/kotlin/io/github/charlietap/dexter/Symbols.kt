package io.github.charlietap.dexter

public sealed class DexType(
    public val descriptor: String,
    internal val kind: Int,
    internal val staticGetOpcode: Int,
    internal val shortyChar: Char,
) {

    internal val hash: Int = descriptor.hashCode()

    internal val width: Int = when (kind) {
        KIND_VOID -> 0
        KIND_WIDE -> 2
        else -> 1
    }

    final override fun equals(
        other: Any?,
    ): Boolean = this === other || (other is DexType && other.descriptor == descriptor)

    final override fun hashCode(): Int = hash

    final override fun toString(): String = descriptor

    public companion object {
        public val Void: VoidType = VoidType()
        public val Boolean: PrimitiveType = PrimitiveType('Z', KIND_SINGLE, 0x63)
        public val Byte: PrimitiveType = PrimitiveType('B', KIND_SINGLE, 0x64)
        public val Short: PrimitiveType = PrimitiveType('S', KIND_SINGLE, 0x66)
        public val Char: PrimitiveType = PrimitiveType('C', KIND_SINGLE, 0x65)
        public val Int: PrimitiveType = PrimitiveType('I', KIND_SINGLE, 0x60)
        public val Long: PrimitiveType = PrimitiveType('J', KIND_WIDE, 0x61)
        public val Float: PrimitiveType = PrimitiveType('F', KIND_SINGLE, 0x60)
        public val Double: PrimitiveType = PrimitiveType('D', KIND_WIDE, 0x61)
        public val Object: ClassType = ClassType("Ljava/lang/Object;")
        public val String: ClassType = ClassType("Ljava/lang/String;")

        internal const val KIND_VOID = 0
        internal const val KIND_SINGLE = 1
        internal const val KIND_WIDE = 2
        internal const val KIND_REFERENCE = 3
    }
}

public class VoidType internal constructor() : DexType("V", KIND_VOID, 0, 'V')

public sealed class ValueType(
    descriptor: String,
    kind: Int,
    staticGetOpcode: Int,
    shortyChar: Char,
) : DexType(descriptor, kind, staticGetOpcode, shortyChar) {

    public fun array(): ArrayType = ArrayType(this)
}

public class PrimitiveType internal constructor(
    descriptor: Char,
    kind: Int,
    staticGetOpcode: Int,
) : ValueType(descriptor.toString(), kind, staticGetOpcode, descriptor)

public sealed class ReferenceType(
    descriptor: String,
) : ValueType(descriptor, KIND_REFERENCE, 0x62, 'L')

public class ClassType(
    descriptor: String,
) : ReferenceType(descriptor) {

    init {
        require(isValidClassDescriptor(descriptor)) { "Invalid class descriptor: $descriptor" }
    }

    public companion object {
        public fun fromName(
            binaryName: String,
        ): ClassType = ClassType("L${binaryName.replace('.', '/')};")
    }
}

public class ArrayType internal constructor(
    public val element: ValueType,
) : ReferenceType("[" + element.descriptor) {

    internal val dimensions: Int = if (element is ArrayType) element.dimensions + 1 else 1

    init {
        require(dimensions <= 255) { "Arrays have at most 255 dimensions" }
    }
}

public class DexField(
    public val owner: ClassType,
    public val name: String,
    public val type: ValueType,
) {
    internal val hash: Int = (owner.hash * 31 + name.hashCode()) * 31 + type.hash

    init {
        require(isValidSimpleName(name)) { "Invalid field name: $name" }
    }

    override fun equals(
        other: Any?,
    ): Boolean =
        this === other ||
            (other is DexField && matches(other))

    internal fun matches(
        other: DexField,
    ): Boolean = other.hash == hash && other.owner == owner && other.name == name && other.type == type

    override fun hashCode(): Int = hash

    override fun toString(): String = "$owner->$name:$type"
}

public class DexMethod private constructor(
    public val owner: ReferenceType,
    public val name: String,
    internal val proto: Proto,
) {

    public constructor(
        owner: ReferenceType,
        name: String,
        returnType: DexType,
        parameters: List<ValueType>,
    ) : this(owner, checkedName(name), Proto(returnType, parameters.toTypedArray()))

    public constructor(
        owner: ReferenceType,
        name: String,
        returnType: DexType,
        vararg parameters: ValueType,
    ) : this(owner, checkedName(name), Proto(returnType, arrayOf(*parameters)))

    public val returnType: DexType get() = proto.returns

    public val parameters: List<ValueType> get() = ParameterList(proto.parameters)

    internal val hash: Int = (owner.hash * 31 + name.hashCode()) * 31 + proto.hash

    internal val words: Int get() = proto.words

    internal val wideMask: Int get() = proto.wideMask

    init {
        require(proto.words <= 255) { "Parameters take ${proto.words} register words, more than 255" }
    }

    internal val parameterCount: Int get() = proto.parameters.size

    override fun equals(
        other: Any?,
    ): Boolean =
        this === other ||
            (other is DexMethod && matches(other))

    internal fun matches(
        other: DexMethod,
    ): Boolean = other.hash == hash && other.owner == owner && other.name == name && other.proto.matches(proto)

    override fun hashCode(): Int = hash

    override fun toString(): String = "$owner->$name(${parameters.joinToString("")})$returnType"

    internal companion object {

        fun constructor(
            owner: ClassType,
            parameters: Array<ValueType>,
        ): DexMethod = DexMethod(owner, "<init>", Proto(DexType.Void, parameters))

        fun staticInitializer(
            owner: ClassType,
        ): DexMethod = DexMethod(owner, "<clinit>", Proto(DexType.Void, emptyArray()))

        private fun checkedName(
            name: String,
        ): String {
            require(isValidSimpleName(name)) { "Invalid method name: $name" }
            return name
        }
    }
}

public class DexConstructor(
    public val owner: ClassType,
    parameters: List<ValueType>,
) {

    public constructor(
        owner: ClassType,
        vararg parameters: ValueType,
    ) : this(owner, parameters.asList())

    internal val method: DexMethod = DexMethod.constructor(owner, parameters.toTypedArray())

    public val parameters: List<ValueType> get() = method.parameters

    override fun equals(
        other: Any?,
    ): Boolean = this === other || (other is DexConstructor && other.method == method)

    override fun hashCode(): Int = method.hashCode()

    override fun toString(): String = method.toString()
}

/** A field access or method call that [CodeEmitter.constMethodHandle] loads as a `java.lang.invoke.MethodHandle`. */
public class DexMethodHandle private constructor(
    internal val kind: Int,
    internal val field: DexField?,
    internal val method: DexMethod?,
) {

    internal val hash: Int = kind * 31 + (field?.hash ?: checkNotNull(method).hash)

    internal fun matches(
        other: DexMethodHandle,
    ): Boolean = other.kind == kind && other.field == field && other.method == method

    override fun equals(
        other: Any?,
    ): Boolean = this === other || (other is DexMethodHandle && matches(other))

    override fun hashCode(): Int = hash

    override fun toString(): String = "${KINDS[kind]} ${field ?: method}"

    public companion object {
        public fun staticPut(
            field: DexField,
        ): DexMethodHandle = DexMethodHandle(0, field, null)

        public fun staticGet(
            field: DexField,
        ): DexMethodHandle = DexMethodHandle(1, field, null)

        public fun instancePut(
            field: DexField,
        ): DexMethodHandle = DexMethodHandle(2, field, null)

        public fun instanceGet(
            field: DexField,
        ): DexMethodHandle = DexMethodHandle(3, field, null)

        public fun invokeStatic(
            method: DexMethod,
        ): DexMethodHandle = DexMethodHandle(4, null, method)

        public fun invokeInstance(
            method: DexMethod,
        ): DexMethodHandle = DexMethodHandle(5, null, method)

        public fun invokeConstructor(
            constructor: DexConstructor,
        ): DexMethodHandle = DexMethodHandle(6, null, constructor.method)

        public fun invokeDirect(
            method: DexMethod,
        ): DexMethodHandle = DexMethodHandle(7, null, method)

        public fun invokeInterface(
            method: DexMethod,
        ): DexMethodHandle = DexMethodHandle(8, null, method)

        private val KINDS = arrayOf(
            "static-put",
            "static-get",
            "instance-put",
            "instance-get",
            "invoke-static",
            "invoke-instance",
            "invoke-constructor",
            "invoke-direct",
            "invoke-interface",
        )
    }
}

/** A method type, as at a polymorphic call site: what is passed and returned, without an owner or name. */
public class DexMethodType private constructor(
    internal val proto: Proto,
) {

    public constructor(
        returnType: DexType,
        parameters: List<ValueType>,
    ) : this(Proto(returnType, parameters.toTypedArray()))

    public constructor(
        returnType: DexType,
        vararg parameters: ValueType,
    ) : this(Proto(returnType, arrayOf(*parameters)))

    public val returnType: DexType get() = proto.returns

    public val parameters: List<ValueType> get() = ParameterList(proto.parameters)

    internal val parameterCount: Int get() = proto.parameters.size

    internal val words: Int get() = proto.words

    internal val wideMask: Int get() = proto.wideMask

    init {
        require(proto.words <= 254) { "Parameters take ${proto.words} register words, more than 254 beside a receiver" }
    }

    override fun equals(
        other: Any?,
    ): Boolean = this === other || (other is DexMethodType && other.proto.matches(proto))

    override fun hashCode(): Int = proto.hash

    override fun toString(): String = "(${parameters.joinToString("")})$returnType"
}

private class ParameterList(
    private val types: Array<ValueType>,
) : AbstractList<ValueType>() {

    override val size: Int get() = types.size

    override fun get(
        index: Int,
    ): ValueType = types[index]
}

internal class Proto(
    val returns: DexType,
    val parameters: Array<ValueType>,
) {

    // The register words the parameters take, and which of the first 31 are wide.
    val words: Int
    val wideMask: Int

    init {
        var words = 0
        var mask = 0
        for (index in parameters.indices) {
            val width = parameters[index].width
            words += width
            if (width == 2 && index < 31) mask = mask or (1 shl index)
        }
        this.words = words
        this.wideMask = mask
    }

    // Summed on demand, since only CodeEmitter.parameter asks.
    fun offset(
        index: Int,
    ): Int {
        var words = 0
        for (before in 0 until index) words += parameters[before].width
        return words
    }

    // Built on demand: only a proto's first interning in a file needs it.
    fun shorty(): String =
        buildString(parameters.size + 1) {
            append(returns.shortyChar)
            for (type in parameters) append(type.shortyChar)
        }

    val hash: Int = parameters.fold(returns.hash) { acc, type -> acc * 31 + type.hash }

    override fun equals(
        other: Any?,
    ): Boolean =
        this === other ||
            (other is Proto && matches(other))

    fun matches(
        other: Proto,
    ): Boolean = other === this || (other.hash == hash && other.returns == returns && other.parameters.contentEquals(parameters))

    override fun hashCode(): Int = hash
}

internal fun isValidClassDescriptor(
    descriptor: String,
): Boolean {
    val last = descriptor.length - 1
    if (last < 2 || descriptor[0] != 'L' || descriptor[last] != ';') return false
    var start = 1
    while (true) {
        val slash = descriptor.indexOf('/', start)
        val end = if (slash < 0) last else slash
        if (!isValidSimpleName(descriptor, start, end)) return false
        if (end == last) return true
        start = end + 1
    }
}

internal fun isValidSimpleName(
    name: String,
    start: Int = 0,
    end: Int = name.length,
): Boolean {
    if (start == end) return false
    var at = start
    while (at < end) {
        val char = name[at]
        when {
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '$' || char == '-' || char == '_' -> Unit
            char.isHighSurrogate() -> {
                if (at + 1 >= end || !name[at + 1].isLowSurrogate()) return false
                at++
            }
            char in '¡'..'῿' || char in '‐'..'‧' || char in '‰'..'퟿' || char in ''..'￯' -> Unit
            else -> return false
        }
        at++
    }
    return true
}
