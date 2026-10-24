package io.github.charlietap.dexter

import io.github.charlietap.dexter.internal.grown
import kotlin.jvm.JvmInline

@JvmInline
public value class Reg(
    public val index: Int,
) {
    override fun toString(): String = "v$index"
}

@JvmInline
public value class Label internal constructor(
    internal val id: Long,
)

/** A catch handler's entry, which only [CodeEmitter.bindHandler] binds. */
@JvmInline
public value class HandlerLabel internal constructor(
    internal val id: Long,
)

public class Labels internal constructor(
    private val baseId: Long,
    public val size: Int,
) {

    public operator fun get(
        index: Int,
    ): Label {
        if (index !in 0 until size) throw IndexOutOfBoundsException("Label index $index out of range 0 until $size")
        return Label(baseId + index)
    }
}

public class LabelTable internal constructor(
    public val firstKey: Int,
    size: Int,
    initial: Label,
) {

    internal val ids = LongArray(size) { initial.id }

    public val size: Int get() = ids.size

    public operator fun get(
        index: Int,
    ): Label = Label(ids[index])

    public operator fun set(
        index: Int,
        label: Label,
    ) {
        ids[index] = label.id
    }
}

public class SwitchCases {

    internal var keys = IntArray(16)
        private set
    internal var ids = LongArray(16)
        private set

    public var size: Int = 0
        private set

    public fun case(
        key: Int,
        target: Label,
    ) {
        if (size == keys.size) {
            keys = keys.grown(size + 1)
            ids = ids.grown(size + 1)
        }
        keys[size] = key
        ids[size] = target.id
        size++
    }

    public fun clear() {
        size = 0
    }
}

@JvmInline
public value class ClassAccess internal constructor(
    internal val flags: Int,
) {

    public fun synthetic(): ClassAccess = ClassAccess(flags or AccessFlags.SYNTHETIC)

    override fun toString(): String = "ClassAccess(0x${flags.toString(16)})"

    public companion object {
        public val Public: ClassAccess = ClassAccess(AccessFlags.PUBLIC)
        public val PublicFinal: ClassAccess = ClassAccess(AccessFlags.PUBLIC or AccessFlags.FINAL)
        public val PublicAbstract: ClassAccess = ClassAccess(AccessFlags.PUBLIC or AccessFlags.ABSTRACT)
        public val Package: ClassAccess = ClassAccess(0)
        public val PackageFinal: ClassAccess = ClassAccess(AccessFlags.FINAL)
        public val PackageAbstract: ClassAccess = ClassAccess(AccessFlags.ABSTRACT)
    }
}

@JvmInline
public value class MemberAccess internal constructor(
    internal val flags: Int,
) {

    public fun final(): MemberAccess = MemberAccess(flags or AccessFlags.FINAL)

    public fun synthetic(): MemberAccess = MemberAccess(flags or AccessFlags.SYNTHETIC)

    override fun toString(): String = "MemberAccess(0x${flags.toString(16)})"

    public companion object {
        public val Public: MemberAccess = MemberAccess(AccessFlags.PUBLIC)
        public val Private: MemberAccess = MemberAccess(AccessFlags.PRIVATE)
        public val Protected: MemberAccess = MemberAccess(AccessFlags.PROTECTED)
        public val Package: MemberAccess = MemberAccess(0)
    }
}

@JvmInline
public value class ConstructorAccess internal constructor(
    internal val flags: Int,
) {

    public fun synthetic(): ConstructorAccess = ConstructorAccess(flags or AccessFlags.SYNTHETIC)

    override fun toString(): String = "ConstructorAccess(0x${flags.toString(16)})"

    public companion object {
        public val Public: ConstructorAccess = ConstructorAccess(AccessFlags.PUBLIC)
        public val Private: ConstructorAccess = ConstructorAccess(AccessFlags.PRIVATE)
        public val Protected: ConstructorAccess = ConstructorAccess(AccessFlags.PROTECTED)
        public val Package: ConstructorAccess = ConstructorAccess(0)
    }
}

internal object AccessFlags {
    const val PUBLIC = 0x1
    const val PRIVATE = 0x2
    const val PROTECTED = 0x4
    const val STATIC = 0x8
    const val FINAL = 0x10
    const val INTERFACE = 0x200
    const val ABSTRACT = 0x400
    const val SYNTHETIC = 0x1000
    const val CONSTRUCTOR = 0x10000
}
