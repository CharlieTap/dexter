# Control flow

## Labels and branches

`label()` creates a jump target and `bind(label)` places it at the next instruction; `boundLabel()`
does both. Labels can be used before they're bound, and Dexter picks the short or long form of
each branch when the method finishes. `labels(n)` makes several at once.

`goto` always jumps, `branch` compares two registers, and `branchZero` compares one with zero.
[Getting started](getting-started.md) has a `branch` example.

## Switches

`switch` takes any set of keys and writes whichever DEX switch is smaller:

```kotlin
// int weight(int status) { switch (status) { case 200: return 1; case 404: return 2; default: return 0; } }
val weight = DexMethod(owner, "weight", DexType.Int, DexType.Int)
val code = classBuilder.beginStaticMethod(weight, registerCount = 2)
val ok = code.label()
val missing = code.label()
val cases = SwitchCases()
cases.case(200, ok)
cases.case(404, missing)

code.switch(code.parameter(0), cases)
code.const(Reg(0), 0)
code.returnValue(Reg(0))
code.bind(ok)
code.const(Reg(0), 1)
code.returnValue(Reg(0))
code.bind(missing)
code.const(Reg(0), 2)
code.returnValue(Reg(0))
code.finish()
```

For a run of consecutive keys, `labelTable` builds the jump table directly, with every entry
starting at a default target:

```kotlin
// int score(int grade) for grades 0, 1 and 2, falling through to -1 otherwise.
val score = DexMethod(owner, "score", DexType.Int, DexType.Int)
val code = classBuilder.beginStaticMethod(score, registerCount = 2)
val otherwise = code.label()
val grades = code.labels(3)
val table = code.labelTable(firstKey = 0, size = 3, initial = otherwise)
for (grade in 0 until 3) table[grade] = grades[grade]

code.packedSwitch(code.parameter(0), table)
code.bind(otherwise)
code.const(Reg(0), -1)
code.returnValue(Reg(0))
for (grade in 0 until 3) {
    code.bind(grades[grade])
    code.const(Reg(0), grade * 10)
    code.returnValue(Reg(0))
}
code.finish()
```

A value outside the keys continues at the instruction after the switch.

## Exceptions

A catch range covers the instructions between two labels. Its handler is a `HandlerLabel`, bound
with `bindHandler`:

```kotlin
// int parse(String s): Integer.parseInt(s), or -1 if s is not a number.
val parseInt = DexMethod(ClassType("Ljava/lang/Integer;"), "parseInt", DexType.Int, DexType.String)
val parse = DexMethod(owner, "parse", DexType.Int, DexType.String)
val code = classBuilder.beginStaticMethod(parse, registerCount = 2)
val start = code.label()
val end = code.label()
val notANumber = code.handlerLabel()

code.bind(start)
code.invokeStatic(parseInt, code.parameter(0))
code.moveResult(Reg(0))
code.bind(end)
code.returnValue(Reg(0))

code.bindHandler(notANumber)
code.const(Reg(0), -1)
code.returnValue(Reg(0))

code.catchRange(start, end, ClassType("Ljava/lang/NumberFormatException;"), notANumber)
code.finish()
```

`bindHandler(handler, exception)` also moves the caught exception into a register. A handler
that reads its exception must only be reachable through the exception, not by falling into it or
jumping to it:

```kotlin
// void close(Closeable c): c.close(), wrapping any IOException in an IllegalStateException.
val closeable = ClassType("Ljava/io/Closeable;")
val close = DexMethod(closeable, "close", DexType.Void)
val illegalState = ClassType("Ljava/lang/IllegalStateException;")
val wrap = DexConstructor(illegalState, ClassType("Ljava/lang/Throwable;"))

val code = classBuilder.beginStaticMethod(DexMethod(owner, "close", DexType.Void, closeable), registerCount = 3)
val start = code.boundLabel()
code.invokeInterface(close, code.parameter(0))
val end = code.boundLabel()
code.returnVoid()

val failed = code.handlerLabel()
code.bindHandler(failed, exception = Reg(0))
code.newInstance(Reg(1), illegalState)
code.invokeConstructor(wrap, Reg(1), Reg(0))
code.throwValue(Reg(1))

code.catchRange(start, end, ClassType("Ljava/io/IOException;"), failed)
code.finish()
```

Ranges can be declared any time before `finish()` and must be disjoint or nested; inner ranges
are tried first. `catchAllRange` catches every exception, and `throwValue` throws one.
