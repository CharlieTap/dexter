package io.github.charlietap.dexter.internal

import io.github.charlietap.dexter.DexField
import io.github.charlietap.dexter.DexMethod
import io.github.charlietap.dexter.DexMethodHandle
import io.github.charlietap.dexter.DexType
import io.github.charlietap.dexter.DexterFileFullException
import io.github.charlietap.dexter.Proto

internal class Pools(
    private val config: Config,
) {

    val strings = Interner<String>(256)

    val types = Interner<DexType>(128)
    var typeDescriptor = IntArray(128)
        private set

    val protos = Interner<Proto>(64)
    var protoShorty = IntArray(64)
        private set
    var protoReturn = IntArray(64)
        private set
    var protoParamStart = IntArray(64)
        private set
    var protoParamCount = IntArray(64)
        private set
    var protoParams = IntArray(256)
        private set
    var protoParamsSize = 0
        private set

    val fields = Interner<DexField>(64)
    var fieldClass = IntArray(64)
        private set
    var fieldType = IntArray(64)
        private set
    var fieldName = IntArray(64)
        private set

    val methods = Interner<DexMethod>(128)
    var methodClass = IntArray(128)
        private set
    var methodProto = IntArray(128)
        private set
    var methodName = IntArray(128)
        private set

    val methodHandles = Interner<DexMethodHandle>(16)

    // DexFormat handle kinds, and the field or method each refers to.
    var handleKind = IntArray(16)
        private set
    var handleMember = IntArray(16)
        private set

    // How many const-string instructions the file holds; while any do, every string index must fit 16 bits.
    private var constStrings = 0

    private val scratch = IntArray(MARK_SIZE)

    fun clear() {
        strings.clear()
        types.clear()
        protos.clear()
        protoParamsSize = 0
        fields.clear()
        methods.clear()
        methodHandles.clear()
        constStrings = 0
    }

    fun save(
        mark: IntArray,
    ) {
        mark[0] = strings.size
        mark[1] = types.size
        mark[2] = protos.size
        mark[3] = protoParamsSize
        mark[4] = fields.size
        mark[5] = methods.size
        mark[6] = methodHandles.size
        mark[7] = constStrings
    }

    fun restore(
        mark: IntArray,
    ) {
        constStrings = mark[7]
        methodHandles.truncate(mark[6])
        methods.truncate(mark[5])
        fields.truncate(mark[4])
        protoParamsSize = mark[3]
        protos.truncate(mark[2])
        types.truncate(mark[1])
        strings.truncate(mark[0])
    }

    fun string(
        value: String,
    ): Int {
        val hash = value.hashCode()
        val found = strings.find(value, hash) { stored, wanted -> stored == wanted }
        if (found >= 0) return found
        if (constStrings > 0 && strings.size >= config.maxStringIds) throw stringsFull()
        return strings.add(value, hash, found)
    }

    /** Interns [value] for a const-string instruction, whose operand holds a 16-bit string index. */
    fun constString(
        value: String,
    ): Int {
        val index = string(value)
        if (strings.size > config.maxStringIds) throw stringsFull()
        constStrings++
        return index
    }

    private fun stringsFull(): DexterFileFullException = DexterFileFullException("More than ${config.maxStringIds} strings in a file that uses constString")

    fun type(
        type: DexType,
    ): Int {
        val found = types.find(type, type.hash) { stored, wanted -> stored.descriptor == wanted.descriptor }
        if (found >= 0) return found
        if (types.size >= config.maxTypeIds) throw DexterFileFullException("More than ${config.maxTypeIds} types")
        val descriptor = string(type.descriptor)
        val index = types.add(type, type.hash, found)
        if (index == typeDescriptor.size) typeDescriptor = typeDescriptor.grown(index + 1)
        typeDescriptor[index] = descriptor
        return index
    }

    private inline fun atomically(
        add: () -> Int,
    ): Int {
        save(scratch)
        try {
            return add()
        } catch (t: Throwable) {
            restore(scratch)
            throw t
        }
    }

    fun proto(
        proto: Proto,
    ): Int {
        val found = protos.find(proto, proto.hash) { stored, wanted -> stored.matches(wanted) }
        if (found >= 0) return found
        if (protos.size >= config.maxProtoIds) throw DexterFileFullException("More than ${config.maxProtoIds} prototypes")
        val shorty = string(proto.shorty())
        val returns = type(proto.returns)
        val parameters = proto.parameters
        val start = protoParamsSize
        if (start + parameters.size > protoParams.size) {
            protoParams = protoParams.grown(start + parameters.size)
        }
        for (index in parameters.indices) protoParams[start + index] = type(parameters[index])
        protoParamsSize = start + parameters.size
        val index = protos.add(proto, proto.hash, found)
        if (index == protoShorty.size) {
            protoShorty = protoShorty.grown(index + 1)
            protoReturn = protoReturn.grown(index + 1)
            protoParamStart = protoParamStart.grown(index + 1)
            protoParamCount = protoParamCount.grown(index + 1)
        }
        protoShorty[index] = shorty
        protoReturn[index] = returns
        protoParamStart[index] = start
        protoParamCount[index] = parameters.size
        return index
    }

    fun field(
        field: DexField,
    ): Int {
        val found = fields.find(field, field.hash) { stored, wanted -> stored.matches(wanted) }
        if (found >= 0) return found
        return atomically { addField(field, found) }
    }

    fun methodHandle(
        handle: DexMethodHandle,
    ): Int {
        val found = methodHandles.find(handle, handle.hash) { stored, wanted -> stored.matches(wanted) }
        if (found >= 0) return found
        return atomically { addMethodHandle(handle, found) }
    }

    private fun addMethodHandle(
        handle: DexMethodHandle,
        miss: Int,
    ): Int {
        if (methodHandles.size >= MAX_METHOD_HANDLES) throw DexterFileFullException("More than $MAX_METHOD_HANDLES method handles")
        val field = handle.field
        val member = if (field != null) {
            val found = fields.find(field, field.hash) { stored, wanted -> stored.matches(wanted) }
            if (found >= 0) found else addField(field, found)
        } else {
            val method = checkNotNull(handle.method)
            val found = methods.find(method, method.hash) { stored, wanted -> stored.matches(wanted) }
            if (found >= 0) found else addMethod(method, found)
        }
        val index = methodHandles.add(handle, handle.hash, miss)
        if (index == handleKind.size) {
            handleKind = handleKind.grown(index + 1)
            handleMember = handleMember.grown(index + 1)
        }
        handleKind[index] = handle.kind
        handleMember[index] = member
        return index
    }

    private fun addField(
        field: DexField,
        miss: Int,
    ): Int {
        if (fields.size >= config.maxFieldIds) throw DexterFileFullException("More than ${config.maxFieldIds} fields")
        val owner = type(field.owner)
        val type = type(field.type)
        val name = string(field.name)
        val index = fields.add(field, field.hash, miss)
        if (index == fieldClass.size) {
            fieldClass = fieldClass.grown(index + 1)
            fieldType = fieldType.grown(index + 1)
            fieldName = fieldName.grown(index + 1)
        }
        fieldClass[index] = owner
        fieldType[index] = type
        fieldName[index] = name
        return index
    }

    fun method(
        method: DexMethod,
    ): Int {
        val found = methods.find(method, method.hash) { stored, wanted -> stored.matches(wanted) }
        if (found >= 0) return found
        return atomically { addMethod(method, found) }
    }

    private fun addMethod(
        method: DexMethod,
        miss: Int,
    ): Int {
        if (methods.size >= config.maxMethodIds) throw DexterFileFullException("More than ${config.maxMethodIds} methods")
        val owner = type(method.owner)
        val proto = proto(method.proto)
        val name = string(method.name)
        val index = methods.add(method, method.hash, miss)
        if (index == methodClass.size) {
            methodClass = methodClass.grown(index + 1)
            methodProto = methodProto.grown(index + 1)
            methodName = methodName.grown(index + 1)
        }
        methodClass[index] = owner
        methodProto[index] = proto
        methodName[index] = name
        return index
    }

    companion object {
        const val MARK_SIZE = 8
        const val MAX_METHOD_HANDLES = 65536
        const val KIND_TYPE = 0
        const val KIND_FIELD = 1
        const val KIND_METHOD = 2
        const val KIND_PROTO = 3
        const val KIND_STRING = 4
    }
}
