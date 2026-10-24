# Instructions

Each instruction is a method on `CodeEmitter`. Operations that come in several forms take a
value naming the form, such as `BinaryOp.AddInt` or `ArrayKind.Wide`, and Dexter picks the
encoding.

## Constants and moves

`const` and `constWide` load numbers, `constString` a string, and `constClass` a class
literal. `move`, `moveWide` and `moveObject` copy registers; `moveResult` takes the result of
the call just before it.

## Arithmetic

`binary` combines two registers, or a register and a literal. `unary` negates or inverts,
`convert` changes type, and `compare` compares longs, floats and doubles:

```kotlin
// int average(int a, int b) { return (a + b) / 2; }
val average = DexMethod(owner, "average", DexType.Int, DexType.Int, DexType.Int)
val code = classBuilder.beginStaticMethod(average, registerCount = 3)
code.binary(BinaryOp.AddInt, Reg(0), code.parameter(0), code.parameter(1))
code.binary(LiteralOp.DivInt, Reg(0), Reg(0), 2)
code.returnValue(Reg(0))
code.finish()
```

## Arrays

`newArray` creates an array, `arrayGet` and `arrayPut` read and write elements, and
`arrayLength` reads its length. `fillArrayData` writes many constant elements at once:

```kotlin
// int[] primes() { return new int[] { 2, 3, 5, 7 }; }
val primes = DexMethod(owner, "primes", DexType.Int.array())
val code = classBuilder.beginStaticMethod(primes, registerCount = 2)
code.const(Reg(0), 4)
code.newArray(Reg(1), Reg(0), DexType.Int.array())
code.fillArrayData(Reg(1), intArrayOf(2, 3, 5, 7))
code.returnValue(Reg(1))
code.finish()
```

## Objects and calls

`newInstance` allocates an object, which a constructor then initializes. `invokeStatic`,
`invokeVirtual`, `invokeDirect`, `invokeInterface` and `invokeConstructor` call methods, with a
`Range` variant for consecutive registers. `checkCast` and `instanceOf` test types:

```kotlin
// String shout(String s) { return new StringBuilder().append(s).append("!").toString(); }
val stringBuilder = ClassType("Ljava/lang/StringBuilder;")
val append = DexMethod(stringBuilder, "append", stringBuilder, DexType.String)
val toString = DexMethod(stringBuilder, "toString", DexType.String)
val shout = DexMethod(owner, "shout", DexType.String, DexType.String)

val code = classBuilder.beginStaticMethod(shout, registerCount = 3)
code.newInstance(Reg(0), stringBuilder)
code.invokeConstructor(DexConstructor(stringBuilder), Reg(0))
code.invokeVirtual(append, Reg(0), code.parameter(0))
code.constString(Reg(1), "!")
code.invokeVirtual(append, Reg(0), Reg(1))
code.invokeVirtual(toString, Reg(0))
code.moveResult(Reg(0))
code.returnValue(Reg(0))
code.finish()
```

## Fields

`getStatic` and `putStatic` read and write static fields, and `getField` and `putField` the
fields of an instance. The field's type picks the instruction:

```kotlin
// static int count; static int next() { return ++count; }
val count = DexField(owner, "count", DexType.Int)
classBuilder.addStaticField(count, MemberAccess.Private)

val next = classBuilder.beginStaticMethod(DexMethod(owner, "next", DexType.Int), registerCount = 1)
next.getStatic(Reg(0), count)
next.binary(LiteralOp.AddInt, Reg(0), Reg(0), 1)
next.putStatic(Reg(0), count)
next.returnValue(Reg(0))
next.finish()
```

## Method handles

`invokePolymorphic` calls a `MethodHandle` or `VarHandle` with the arguments' own types, given
as a `DexMethodType`. `constMethodHandle` and `constMethodType` load handles and types as
constants:

```kotlin
// int readInt(VarHandle view, byte[] bytes, int offset): view.get(bytes, offset), on Android 13 and later.
val varHandle = ClassType("Ljava/lang/invoke/VarHandle;")
val get = DexMethod(varHandle, "get", DexType.Object, DexType.Object.array())
val readInt = DexMethod(owner, "readInt", DexType.Int, varHandle, DexType.Byte.array(), DexType.Int)

val code = classBuilder.beginStaticMethod(readInt, registerCount = 4)
code.invokePolymorphic(get, DexMethodType(DexType.Int, DexType.Byte.array(), DexType.Int), Reg(1), Reg(2), Reg(3))
code.moveResult(Reg(0))
code.returnValue(Reg(0))
code.finish()
```

These need newer DEX versions; see [Limits and versions](limits.md).

## Monitors

`monitorEnter` and `monitorExit` lock and unlock an object, as `synchronized` blocks do.
