package io.github.charlietap.dexter.internal

internal class ClassTable {

    var count = 0
        private set
    var type = IntArray(16)
        private set
    var access = IntArray(16)
        private set
    var superclass = IntArray(16)
        private set
    var interfaceStart = IntArray(16)
        private set
    var interfaceCount = IntArray(16)
        private set
    var interfaces = IntArray(16)
        private set

    var sourceFile = IntArray(16)
        private set
    private var interfacesSize = 0

    var memberCount = 0
        private set
    var memberClass = IntArray(64)
        private set

    var memberList = IntArray(64)
        private set
    var memberIndex = IntArray(64)
        private set
    var memberAccess = IntArray(64)
        private set

    var memberCode = IntArray(64)
        private set

    val classDefined = Bits()
    val fieldDefined = Bits()
    val methodDefined = Bits()

    fun clear() {
        count = 0
        interfacesSize = 0
        memberCount = 0
        classDefined.clear()
        fieldDefined.clear()
        methodDefined.clear()
    }

    fun addClass(
        typeIndex: Int,
        flags: Int,
        superIndex: Int,
        interfaceIndices: IntArray,
        interfaceSize: Int,
        source: Int,
    ): Int {
        val index = count
        if (index == type.size) {
            type = type.grown(index + 1)
            access = access.grown(index + 1)
            superclass = superclass.grown(index + 1)
            interfaceStart = interfaceStart.grown(index + 1)
            interfaceCount = interfaceCount.grown(index + 1)
            sourceFile = sourceFile.grown(index + 1)
        }
        if (interfacesSize + interfaceSize > interfaces.size) {
            interfaces = interfaces.grown(interfacesSize + interfaceSize)
        }
        interfaceIndices.copyInto(interfaces, interfacesSize, 0, interfaceSize)
        type[index] = typeIndex
        access[index] = flags
        superclass[index] = superIndex
        interfaceStart[index] = interfacesSize
        interfaceCount[index] = interfaceSize
        sourceFile[index] = source
        interfacesSize += interfaceSize
        count = index + 1
        return index
    }

    fun addMember(
        classIndex: Int,
        list: Int,
        index: Int,
        flags: Int,
        code: Int,
    ) {
        val member = memberCount
        if (member == memberClass.size) {
            memberClass = memberClass.grown(member + 1)
            memberList = memberList.grown(member + 1)
            memberIndex = memberIndex.grown(member + 1)
            memberAccess = memberAccess.grown(member + 1)
            memberCode = memberCode.grown(member + 1)
        }
        memberClass[member] = classIndex
        memberList[member] = list
        memberIndex[member] = index
        memberAccess[member] = flags
        memberCode[member] = code
        memberCount = member + 1
    }

    companion object {
        const val NO_CODE = -1
        const val NO_SOURCE = -1
        const val LIST_STATIC_FIELDS = 0
        const val LIST_DIRECT_METHODS = 1
        const val LIST_VIRTUAL_METHODS = 2
    }
}
