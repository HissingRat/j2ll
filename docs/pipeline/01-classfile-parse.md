# 01 ASM Parse

本阶段负责发现输入 `.class`，调用 ASM parse，并输出后续 stage 可共享的 class facts。

## 输入

- `.class` bytes
- JAR entry metadata
- `whiteList` / `blackList` selector config
- optional classpath metadata

## 输出

- `ParsedClass`
- `ParsedMethod`
- parse diagnostics
- source location metadata

## 推荐包

```text
xyz.melodysky.frontend.classfile
```

推荐类型：

- `ClassFileSource`
- `JarClassFileSource`
- `SingleClassFileSource`
- `AsmClassParser`
- `ParsedClass`
- `ParsedField`
- `ParsedMethod`
- `ClassParseDiagnostic`

## Parsed model

`ParsedClass` 至少记录：

- internal name
- access flags
- class version
- super name
- interfaces
- fields
- methods
- source entry/path

`ParsedMethod` 至少记录：

- owner internal name
- name
- descriptor
- access flags
- exceptions
- instructions
- try/catch table
- max locals
- max stack

For abstract, already-native and other no-Code methods, `instructions`, `try/catch table`, `max locals` and `max stack` must be represented explicitly as absent/no-Code facts, not as an empty runnable method body. Selector eligibility later records such methods as `notApplicable` when matched.

ASM tree 可以短期保留在 frontend 边界内，但中后端不要直接依赖 ASM 节点。

## 边界

- 该阶段允许依赖 ASM。
- 该阶段不构建 CFG。
- 该阶段不 lower IR。
- 该阶段不决定 virtual call target。
- 该阶段不因为后续暂不支持某个 opcode 而丢弃 class；unsupported lowering 属于后续 stage。

## 测试

- 最小 class。
- interface。
- annotation。
- record。
- inner/nested class metadata。
- JAR 中非 class entry 过滤。
- parse failure diagnostic。
- deterministic ordering。
