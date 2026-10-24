# Limits and versions

## Method size

Large methods are costly for ART to verify and compile, so Dexter can cap their size.
`maxMethodCodeUnits` in the `Dexter` options sets a cap for every method:

```kotlin
val dexter = Dexter { maxMethodCodeUnits = 10_000 }
```

`finish(maxCodeUnits)` sets a lower cap for a single method. Both count switch and array
payloads and branches widened to their long form. A method over its cap is rolled back and
`finish` throws `DexterMethodTooLargeException`:

```kotlin
val code = classBuilder.beginStaticMethod(DexMethod(owner, "big", DexType.Void), registerCount = 1)
repeat(1_000) { code.const(Reg(0), 100_000) }
code.returnVoid()

try {
    code.finish(maxCodeUnits = 2_000)
} catch (tooLarge: DexterMethodTooLargeException) {
    // The method has been rolled back; emit a smaller version or split it up.
}
```

## Full files

A DEX file holds at most 65,536 method references, 65,536 field references and 65,535 types
and prototypes, and Dexter caps a file's code at 512 MiB. A definition that would go past one of
these throws `DexterFileFullException`. Like every `DexterException`, it rolls back the failed
definition and leaves no method open, so the file still holds everything defined before it:
`finish()` it and continue in a new file.

## DEX versions

Dexter writes the oldest DEX version the file's instructions allow, so most files are DEX 035
and load on every Android release:

| Instructions used | DEX version | Minimum Android |
|---|---|---|
| Everything else | 035 | Any |
| `invokePolymorphic` | 038 | 8.0 (API 26) |
| `constMethodHandle`, `constMethodType` | 039 | 9 (API 28) |
