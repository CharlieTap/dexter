# Registers

A method declares how many registers it uses when it begins, and its inputs take the highest of
them: the receiver first for an instance method, then the parameters in order. `parameter(index)`
and, for instance methods, `self` return those registers. The rest are yours to use as locals.

A `long` or `double` takes two adjacent registers and is referred to by the first.

```kotlin
// long sum(long a, int b) on an instance: five registers, of which the last four are inputs.
val sum = DexMethod(owner, "sum", DexType.Long, DexType.Long, DexType.Int)
val code = classBuilder.beginInstanceMethod(sum, registerCount = 5)

check(code.self == Reg(1))
check(code.parameter(0) == Reg(2)) // a takes v2 and v3
check(code.parameter(1) == Reg(4))

code.returnValue(code.parameter(0))
code.finish()
```

## Register limits

DEX encodes registers in 4, 8 or 16 bits depending on the instruction. Most instructions accept
any register below 256, and Dexter picks the shortest encoding for the registers you pass. A
few only reach low registers:

| Registers below 16 | Registers below 256 |
|---|---|
| `branch`, `unary`, `convert`, `newArray`, `arrayLength`, `getField`, `putField`, `instanceOf` | Everything else, including `const`, `binary`, `branchZero`, `returnValue` and the switches |

Moves accept any register. Calls take up to five argument words in registers below 16, or any
number of consecutive registers; Dexter chooses between the two forms. The `Range` variants
take the first of the consecutive registers directly.

When no encoding fits, the instruction throws `IllegalArgumentException`.
