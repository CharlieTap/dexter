# Getting started

Everything in Dexter follows one shape:

1. A `Dexter` writes one file at a time. `beginFile()` starts one.
2. `addClass` and `addInterface` put classes into the file and return builders for their members.
3. A `begin` call on a class builder opens one method body as a `CodeEmitter`. You write its
   instructions, then `finish()` it, and only then does the method join the class.
4. `finish()` on the file returns the complete DEX file as a `ByteArray`.

```kotlin
val owner = ClassType("Lcom/example/MathOps;")
val clamp = DexMethod(owner, "clamp", DexType.Int, DexType.Int, DexType.Int, DexType.Int)

val dexter = Dexter()
val file = dexter.beginFile()
val mathOps = file.addClass(owner, DexType.Object, sourceFile = "MathOps.kt")

// The three parameters fill all three registers.
val code = mathOps.beginStaticMethod(clamp, registerCount = 3)
val value = code.parameter(0)
val min = code.parameter(1)
val max = code.parameter(2)
val atLeastMin = code.label()
val inRange = code.label()

code.line(1)
code.branch(Cond.Ge, value, min, atLeastMin)
code.returnValue(min)
code.bind(atLeastMin)
code.line(2)
code.branch(Cond.Le, value, max, inRange)
code.returnValue(max)
code.bind(inRange)
code.line(3)
code.returnValue(value)
code.finish()

val bytes: ByteArray = file.finish()
```

`DexMethod`, `DexField` and `ClassType` describe what a file refers to, whether it defines them
or calls into them. Types use DEX descriptors: `Lcom/example/MathOps;` for a class, `I` for an
`int`, `[J` for a `long[]`, with the common ones on `DexType`. `ClassType.fromName("com.example.MathOps")`
converts a Java name.

`line(n)` maps the instructions after it to source line `n`, and the class's `sourceFile` names
the source. Together they put file names and line numbers into stack traces and profilers.

## Loading the file

On Android, `InMemoryDexClassLoader` loads the bytes directly. Elsewhere, write them to a
`.dex` file for tools such as `dexdump` or `d8`.

## Abandoning work

`abort()` on a method drops it without touching anything already in the file. `abort()` on the
file drops the whole file:

```kotlin
val dexter = Dexter()
val file = dexter.beginFile()
val classBuilder = file.addClass(ClassType("Lcom/example/Draft;"), DexType.Object)
val code = classBuilder.beginStaticMethod(DexMethod(ClassType("Lcom/example/Draft;"), "unused", DexType.Void), registerCount = 0)
code.abort() // drops this method; the class stays

file.abort() // drops the whole file; the Dexter is ready for beginFile again
```

Mistakes in a method, such as a register that doesn't fit an instruction, throw
`IllegalArgumentException` from the instruction itself and leave the method open, so you can
abort it or carry on.

## Reusing a Dexter

A `Dexter` keeps its buffers and symbol tables between files, so writing many files with one
instance is faster than creating a new one each time. The same input always produces the same
bytes, whether written by a fresh instance or a reused one. A `Dexter` isn't thread-safe; use
one per thread.
