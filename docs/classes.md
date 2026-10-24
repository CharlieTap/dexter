# Classes and members

`addClass(type, superclass, vararg interfaces, access, sourceFile)` adds a class straight away
and returns a `ClassBuilder`. Classes are public and final unless you pass another
`ClassAccess`. Dexter orders classes so supertypes come first, whatever order you add them in.

A `ClassBuilder` opens method bodies with:

- `beginStaticMethod(method, registerCount)`
- `beginInstanceMethod(method, registerCount)`, whose emitter also has `self`
- `beginConstructor(constructor, registerCount)`
- `beginStaticInitializer(registerCount)`

Methods are public unless you pass a `MemberAccess`, and private instance methods and
constructors are written as direct methods automatically.

## Interfaces

`addInterface` adds a public interface, and `addAbstractMethod` declares its methods. A class
implements it by listing it after the superclass:

```kotlin
val shape = ClassType("Lcom/example/Shape;")
val area = DexMethod(shape, "area", DexType.Int)
file.addInterface(shape).addAbstractMethod(area)

val square = ClassType("Lcom/example/Square;")
val squareClass = file.addClass(square, DexType.Object, shape)

val constructor = squareClass.beginConstructor(DexConstructor(square), registerCount = 1)
constructor.invokeConstructor(DexConstructor(DexType.Object), constructor.self)
constructor.returnVoid()
constructor.finish()

val squareArea = squareClass.beginInstanceMethod(DexMethod(square, "area", DexType.Int), registerCount = 2)
squareArea.const(Reg(0), 16)
squareArea.returnValue(Reg(0))
squareArea.finish()
```

A constructor must call a superclass constructor on `self` before returning, as the Java
verifier expects.

## Fields and static initializers

`addStaticField` adds a static field. Its value starts as zero or null; set it in the static
initializer:

```kotlin
val greeting = DexField(owner, "GREETING", DexType.String)
classBuilder.addStaticField(greeting, MemberAccess.Public.final())

val init = classBuilder.beginStaticInitializer(registerCount = 1)
init.constString(Reg(0), "Hello")
init.putStatic(Reg(0), greeting)
init.returnVoid()
init.finish()
```

Dexter can read and write instance fields of existing classes with `getField` and `putField`,
but doesn't define instance fields itself.
