package io.github.charlietap.dexter

import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Pools

@DexterDsl
public class DexFile internal constructor(
    private val dexter: Dexter,
    private val generation: Int,
) {

    private val mark = IntArray(Pools.MARK_SIZE)

    /**
     * Adds a class to the file straight away and returns the builder for its members. The class is
     * written whether or not any members are added.
     */
    public fun addClass(
        type: ClassType,
        superclass: ClassType,
        vararg interfaces: ClassType,
        access: ClassAccess = ClassAccess.PublicFinal,
        sourceFile: String? = null,
    ): ClassBuilder = ClassBuilder(dexter, generation, type, define(type, superclass, interfaces, access.flags, sourceFile))

    /** Adds a public interface, whose methods are all abstract, to the file straight away and returns its builder. */
    public fun addInterface(
        type: ClassType,
        vararg superinterfaces: ClassType,
        sourceFile: String? = null,
    ): InterfaceBuilder {
        val flags = AccessFlags.PUBLIC or AccessFlags.INTERFACE or AccessFlags.ABSTRACT
        return InterfaceBuilder(dexter, generation, type, define(type, DexType.Object, superinterfaces, flags, sourceFile))
    }

    private fun define(
        type: ClassType,
        superclass: ClassType,
        interfaces: Array<out ClassType>,
        flags: Int,
        sourceFile: String?,
    ): Int {
        val dexter = dexter
        dexter.checkFile(generation)
        check(!dexter.code.isOpen) { "Classes cannot be defined while a method is open" }
        require(superclass != type) { "$type cannot extend itself" }

        val pools = dexter.pools
        val classes = dexter.classes
        pools.save(mark)
        try {
            val typeIndex = pools.type(type)
            require(!classes.classDefined[typeIndex]) { "Class $type is already defined" }
            val superIndex = pools.type(superclass)
            val faces = IntArray(interfaces.size)
            for (face in interfaces.indices) faces[face] = pools.type(interfaces[face])
            checkDistinct(faces, interfaces)
            val source = if (sourceFile == null) ClassTable.NO_SOURCE else pools.string(sourceFile)
            val index = classes.addClass(typeIndex, flags, superIndex, faces, faces.size, source)
            classes.classDefined.set(typeIndex)
            return index
        } catch (failure: Throwable) {
            pools.restore(mark)
            throw failure
        }
    }

    private fun checkDistinct(
        faces: IntArray,
        interfaces: Array<out ClassType>,
    ) {
        if (faces.size < 2) return
        val sorted = faces.sortedArray()
        for (face in 1 until sorted.size) {
            require(sorted[face] != sorted[face - 1]) { "Duplicate interface: ${interfaces[faces.indexOf(sorted[face])]}" }
        }
    }

    /** Writes the file and returns its bytes. No method may be open. */
    public fun finish(): ByteArray = dexter.finishFile(generation)

    /** Discards the file. Does nothing if it has already been finished or aborted. */
    public fun abort() {
        dexter.abortFile(generation)
    }
}

@DexterDsl
public class ClassBuilder internal constructor(
    private val dexter: Dexter,
    private val generation: Int,
    private val type: ClassType,
    private val index: Int,
) {

    private val mark = IntArray(Pools.MARK_SIZE)

    /** Adds [field] to the class straight away. */
    public fun addStaticField(
        field: DexField,
        access: MemberAccess = MemberAccess.Public,
    ) {
        checkUsable()
        require(field.owner == type) { "Field $field does not belong to $type" }

        val pools = dexter.pools
        val classes = dexter.classes
        pools.save(mark)
        try {
            val fieldIndex = pools.field(field)
            require(!classes.fieldDefined[fieldIndex]) { "Field $field is already defined" }
            classes.addMember(index, ClassTable.LIST_STATIC_FIELDS, fieldIndex, access.flags or AccessFlags.STATIC, ClassTable.NO_CODE)
            classes.fieldDefined.set(fieldIndex)
        } catch (failure: Throwable) {
            pools.restore(mark)
            throw failure
        }
    }

    /** Starts the body of static [method]. It joins the class only when [CodeEmitter.finish] succeeds. */
    public fun beginStaticMethod(
        method: DexMethod,
        registerCount: Int,
        access: MemberAccess = MemberAccess.Public,
    ): CodeEmitter {
        checkOwner(method)
        return begin(method, access.flags or AccessFlags.STATIC, registerCount, isStatic = true)
    }

    /** Starts the body of instance [method]. It joins the class only when [CodeEmitter.finish] succeeds. */
    public fun beginInstanceMethod(
        method: DexMethod,
        registerCount: Int,
        access: MemberAccess = MemberAccess.Public,
    ): InstanceCodeEmitter {
        checkOwner(method)
        return begin(method, access.flags, registerCount, isStatic = false)
    }

    /** Starts the body of [constructor]. It joins the class only when [CodeEmitter.finish] succeeds. */
    public fun beginConstructor(
        constructor: DexConstructor,
        registerCount: Int,
        access: ConstructorAccess = ConstructorAccess.Public,
    ): InstanceCodeEmitter {
        checkUsable()
        require(constructor.owner == type) { "Constructor $constructor does not belong to $type" }
        return begin(constructor.method, access.flags or AccessFlags.CONSTRUCTOR, registerCount, isStatic = false)
    }

    /** Starts the class's static initializer. It joins the class only when [CodeEmitter.finish] succeeds. */
    public fun beginStaticInitializer(
        registerCount: Int,
    ): CodeEmitter {
        checkUsable()
        val method = DexMethod.staticInitializer(type)
        return begin(method, AccessFlags.STATIC or AccessFlags.CONSTRUCTOR, registerCount, isStatic = true)
    }

    private fun checkOwner(
        method: DexMethod,
    ) {
        checkUsable()
        require(method.owner == type) { "Method $method does not belong to $type" }
    }

    private fun begin(
        method: DexMethod,
        flags: Int,
        registerCount: Int,
        isStatic: Boolean,
    ): InstanceCodeEmitter {
        val ins = method.words + if (isStatic) 0 else 1
        require(ins <= 255) { "$method takes $ins argument words, more than 255" }
        require(registerCount in ins..65535) { "registerCount must be in $ins..65535: $registerCount" }
        val direct = flags and (AccessFlags.STATIC or AccessFlags.PRIVATE or AccessFlags.CONSTRUCTOR) != 0
        val list = if (direct) ClassTable.LIST_DIRECT_METHODS else ClassTable.LIST_VIRTUAL_METHODS
        val code = dexter.code
        code.begin(method, registerCount, ins, isStatic, index, list, flags)
        return code
    }

    private fun checkUsable() {
        dexter.checkFile(generation)
        check(!dexter.code.isOpen) { "Members cannot be defined while a method is open" }
    }
}

@DexterDsl
public class InterfaceBuilder internal constructor(
    private val dexter: Dexter,
    private val generation: Int,
    private val type: ClassType,
    private val index: Int,
) {

    private val mark = IntArray(Pools.MARK_SIZE)

    /** Adds [method] to the interface straight away, public and abstract, without a body. */
    public fun addAbstractMethod(
        method: DexMethod,
    ) {
        dexter.checkFile(generation)
        check(!dexter.code.isOpen) { "Members cannot be defined while a method is open" }
        require(method.owner == type) { "Method $method does not belong to $type" }

        val pools = dexter.pools
        val classes = dexter.classes
        pools.save(mark)
        try {
            val methodIndex = pools.method(method)
            require(!classes.methodDefined[methodIndex]) { "Method $method is already defined" }
            val flags = AccessFlags.PUBLIC or AccessFlags.ABSTRACT
            classes.addMember(index, ClassTable.LIST_VIRTUAL_METHODS, methodIndex, flags, ClassTable.NO_CODE)
            classes.methodDefined.set(methodIndex)
        } catch (failure: Throwable) {
            pools.restore(mark)
            throw failure
        }
    }
}
