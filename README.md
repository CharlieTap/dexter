<br>

<img src="dexter.webp" width="240" alt="dexter logo"/>

---

Dexter is a small Kotlin Multiplatform library for writing [Android DEX](https://source.android.com/docs/core/runtime/dex-format) files with Kotlin.

# Setup

```kotlin
dependencies {
    implementation("io.github.charlietap.dexter:dexter:0.1.0")
}
```

# Usage

Build a class, write its bytecode, and get a DEX file back:

```kotlin
val greeter = ClassType("Lcom/example/Greeter;")
val greet = DexMethod(greeter, "greet", DexType.String, DexType.String)
val concat = DexMethod(DexType.String, "concat", DexType.String, DexType.String)

val file = Dexter().beginFile()
val code = file.addClass(greeter, DexType.Object).beginStaticMethod(greet, registerCount = 2)

// String greet(String name) { return "Hello, ".concat(name); }
val greeting = Reg(0)
code.constString(greeting, "Hello, ")
code.invokeVirtual(concat, greeting, code.parameter(0))
code.moveResult(greeting)
code.returnValue(greeting)
code.finish()

val dex: ByteArray = file.finish()
```

On Android, load it and call it like any other class:

```kotlin
val loader = InMemoryDexClassLoader(ByteBuffer.wrap(dex), context.classLoader)
val greet = loader.loadClass("com.example.Greeter").getMethod("greet", String::class.java)

greet.invoke(null, "Dexter") // "Hello, Dexter"
```

# Documentation

- [Getting started](docs/getting-started.md): files, classes and methods, from `Dexter` to bytes
- [Classes and members](docs/classes.md): interfaces, constructors, fields and static initializers
- [Registers](docs/registers.md): where parameters live, wide values and register limits
- [Instructions](docs/instructions.md): arithmetic, arrays, objects, fields, calls and method handles
- [Control flow](docs/control-flow.md): labels, branches, switches and exception handlers
- [Limits and versions](docs/limits.md): method size caps, full files and DEX versions

## License

This project is dual-licensed under both the MIT and Apache 2.0 licenses. You can choose which one you want to use the software under.

- For details on the MIT license, please see the [LICENSE-MIT](LICENSE-MIT) file.
- For details on the Apache 2.0 license, please see the [LICENSE-APACHE](LICENSE-APACHE) file.
