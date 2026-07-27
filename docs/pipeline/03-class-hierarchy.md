# 03 Class Hierarchy

本阶段从 parsed classes 构建 program-level class hierarchy，为 call graph 和 runtime analysis 提供统一事实来源。

## 输入

- `List<ParsedClass>`
- optional classpath resolver
- external type policy

## 输出

- `ClassHierarchy`
- method/field lookup facts
- missing external type diagnostics
- world model metadata

## 推荐包

```text
xyz.melodysky.analysis.hierarchy
```

推荐类型：

- `ClassHierarchyBuilder`
- `ClassHierarchy`
- `HierarchyClass`
- `HierarchyMethod`
- `HierarchyField`
- `MethodSignature`
- `FieldSignature`
- `ExternalTypePolicy`
- `AnalysisWorld`

## 查询能力

第一版至少需要：

- `lookupClass(internalName)`
- `superClassOf(className)`
- `interfacesOf(className)`
- `subtypesOf(className)`
- `implementorsOf(interfaceName)`
- `declaresMethod(className, signature)`
- `resolveVirtualMethod(declaredOwner, signature)`
- `isFinalClass(className)`
- `isFinalMethod(className, signature)`

## World model

为了让后续分析知道自己的精度边界，hierarchy builder 必须明确当前分析世界：

```text
CLOSED_WORLD
PARTIAL_WORLD
JDK_EXTERNAL_WORLD
UNKNOWN_DYNAMIC_WORLD
```

不同 world 会影响 call graph、devirtualization、JVM dispatch/helper 精度和保守 `skipped` 边界。`CLOSED_WORLD` 是历史 wire name，表示完整 JVM classpath 分析假设；`UNKNOWN_DYNAMIC_WORLD` 必须保留 runtime helper。

含义：

- `CLOSED_WORLD`：输入 JAR、resolved `classPath` 和 JDK metadata 覆盖分析需要的 JVM classes。适合更激进的 CHA/RTA、devirtualization、call indirection 和 method table hiding；输出仍是 JVM-hosted JAR。
- `PARTIAL_WORLD`：应用 class 基本可见，但外部依赖可能不完整。对 external type 保守，不能假设没有额外 subtype。
- `JDK_EXTERNAL_WORLD`：应用 class 可分析，JDK class 作为外部 runtime/library 处理。JDK method 多数走 runtime/JVM helper 或专门 intrinsic。
- `UNKNOWN_DYNAMIC_WORLD`：允许 reflection、custom classloader 或 runtime generated class 改变类型世界。只能做保守 call graph 和 guarded/helper-backed lowering；无法证明安全的 selected caller 必须是 `skipped`。

`worldModel` 是 required config field，推荐值为 `PARTIAL_WORLD`。任何需要更强 world model 的 analysis/protection pass 都必须声明 execution requirement。`fieldInternalization` 在非 closed world 的真实 build 中使用统一 Y/N gate：Y 只给该 feature 授权 current-input-JAR-only scope，不改写 hierarchy 的 `worldModel` 且不解析配置 classpath；N/EOF fail closed。validate/dry-run 只报告待确认 warning。未来 whole-program analysis 应复用同一 requirement/policy 模型，而不是各自读取 stdin。

输入、JDK metadata 和 preflight 行为的详细矩阵以 `docs/io-config-output-contract.md` 为准，避免 hierarchy guide 和用户可见 config 契约分叉。

## 外部类型策略

JDK 和依赖库 class 不一定在输入 JAR 中。缺失类型必须保守处理：

- 缺失 super/interface：允许构建，但标注 hierarchy incomplete。
- 缺失方法声明：virtual/interface resolution 回退到 conservative unknown target。
- 缺失 final 信息：不要假设 final。
- 缺失 class init / module metadata：不要假设初始化和访问语义可以被省略。

## 边界

- hierarchy 不做 reachability。
- hierarchy 不记录 allocation site。
- hierarchy 不输出 devirtualization decision。

## 测试

- 单继承链。
- interface extends interface。
- class implements multiple interfaces。
- override lookup。
- abstract method。
- final class / final method。
- missing external parent。
- hierarchy cycle diagnostic。
