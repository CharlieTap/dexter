# Limits and versions

## Method size

Large methods are costly for ART to verify and compile, so Dexter can cap their size.
`maxMethodCodeUnits` in the `Dexter` options sets a cap for every method:

```kotlin
val dexter = Dexter { maxMethodCodeUnits = 10_000 }
```

`maxCodeUnits` on a `begin` call sets a lower cap for a single method. Both count switch and
array payloads and branches widened to their long form. A method over its cap is rolled back
and `DexterMethodTooLargeException` is thrown as soon as Dexter knows it cannot fit: by the
instruction that takes it past the cap, or by `finish` once payloads and widened branches are
counted. Code that may run long can give up early instead of emitting the rest of the method.
A method that would overflow the file's code first fails with `DexterFileFullException` instead
(see below):

```kotlin
val code = classBuilder.beginStaticMethod(
    DexMethod(owner, "big", DexType.Void),
    registerCount = 1,
    maxCodeUnits = 2_000,
)

try {
    repeat(1_000) { code.const(Reg(0), 100_000) }
    code.returnVoid()
    code.finish()
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
