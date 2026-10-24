package io.github.charlietap.dexter

import io.github.charlietap.dexter.internal.ClassTable
import io.github.charlietap.dexter.internal.Config
import io.github.charlietap.dexter.internal.Pools
import io.github.charlietap.dexter.internal.code.ArrayDataTable
import io.github.charlietap.dexter.internal.code.BranchRelaxer
import io.github.charlietap.dexter.internal.code.CatchRanges
import io.github.charlietap.dexter.internal.code.CodeTables
import io.github.charlietap.dexter.internal.code.MethodLabels
import io.github.charlietap.dexter.internal.code.SwitchTable
import io.github.charlietap.dexter.internal.write.DexWriter

@DslMarker
public annotation class DexterDsl

@DexterDsl
public class DexterOptions internal constructor() {

    /** The most code units a method may take, payloads included. */
    public var maxMethodCodeUnits: Int = Int.MAX_VALUE

    internal var shortBranchReach = 32767
    internal var maxTypeIds = 65535
    internal var maxProtoIds = 65535
    internal var maxFieldIds = 65536
    internal var maxMethodIds = 65536

    // Only enforced once a file uses constString, whose 16-bit string index must fit.
    internal var maxStringIds = 65536
}

/** Writes DEX 035 files one at a time, reusing its buffers. Use one per thread. */
public class Dexter(
    configure: DexterOptions.() -> Unit,
) {

    public constructor() : this({})

    internal val config: Config
    internal val pools: Pools
    internal val classes: ClassTable = ClassTable()
    internal val code: InstanceCodeEmitter
    private val writer = DexWriter()

    private var generation = 0
    private var fileOpen = false

    init {
        val options = DexterOptions().apply(configure)
        require(options.maxMethodCodeUnits > 0) { "maxMethodCodeUnits must be positive" }
        require(options.shortBranchReach in 1..32767) { "shortBranchReach must be 1..32767" }
        config = Config(
            maxMethodCodeUnits = options.maxMethodCodeUnits,
            shortBranchReach = options.shortBranchReach,
            maxTypeIds = options.maxTypeIds,
            maxProtoIds = options.maxProtoIds,
            maxFieldIds = options.maxFieldIds,
            maxMethodIds = options.maxMethodIds,
            maxStringIds = options.maxStringIds,
        )
        pools = Pools(config)
        code = InstanceCodeEmitter(
            pools = pools,
            classes = classes,
            config = config,
            tables = CodeTables(),
            labels = MethodLabels(),
            branches = BranchRelaxer(config.shortBranchReach),
            switches = SwitchTable(),
            arrayData = ArrayDataTable(),
            catches = CatchRanges(),
        )
    }

    public fun beginFile(): DexFile {
        check(!fileOpen) { "A file is already open" }
        pools.clear()
        classes.clear()
        code.clear()
        fileOpen = true
        generation++
        return DexFile(this, generation)
    }

    internal fun checkFile(
        generation: Int,
    ) {
        check(fileOpen && generation == this.generation) { "The file has been finished or aborted" }
    }

    internal fun finishFile(
        generation: Int,
    ): ByteArray {
        checkFile(generation)
        check(!code.isOpen) { "A method is still open" }
        val bytes = writer.write(pools, classes, code.arena, code.tables)
        fileOpen = false
        this.generation++
        return bytes
    }

    internal fun abortFile(
        generation: Int,
    ) {
        if (!fileOpen || generation != this.generation) return
        code.abort()
        fileOpen = false
        this.generation++
    }
}

/** The failed definition has been rolled back and no method is left open. */
public sealed class DexterException(
    message: String,
) : RuntimeException(message)

public class DexterFileFullException internal constructor(
    message: String,
) : DexterException(message)

public class DexterMethodTooLargeException internal constructor(
    message: String,
) : DexterException(message)
