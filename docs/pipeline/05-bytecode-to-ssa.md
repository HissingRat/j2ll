# 05 Bytecode To SSA

本阶段把 stack-based JVM bytecode lower 成三地址 SSA IR。它消费 CFG、frame facts、hierarchy 和 runtime analysis facts。

## 输入

- `ParsedMethod`
- `BytecodeCfg`
- frame facts
- `ClassHierarchy`
- `RuntimeAnalysisResult`
- `DevirtualizationPlan`

## 输出

- `IrMethod`
- `IrClass`
- `IrProgram`
- lowering diagnostics
- unsupported feature diagnostics

## 推荐包

```text
xyz.melodysky.ir.ssa
```

推荐类型：

- `BytecodeToSsaLowerer`
- `SsaConstructionContext`
- `StackState`
- `LocalState`
- `ValueFactory`
- `FrameFacts`
- `FrameFactsBuilder`
- `InstructionLowerer`
- `OpcodeLoweringRegistry`

## 拆分方向

以旧 `MethodIrBuilder` 的行为为 reference，在新 source tree 中重建这些职责：

- CFG 相关：已属于 `frontend.cfg`。
- ASM frame 分析：迁到 `FrameFactsBuilder`。
- operand stack 操作：迁到 `StackState`。
- locals 类型与 slot：迁到 `LocalState`。
- opcode lowering：按领域拆到独立 lowerer。
- IR validation：保留在 `ir.validate`，在 stage 结束后运行。

可拆分 lowerer：

- locals
- constants
- arithmetic
- field
- invoke
- invokedynamic
- array
- type
- switch
- exception
- monitor

## SSA 模型

推荐直接设计 block parameter 或 phi 指令表达 stack/local merge，不再把 stack merge 编码成 synthetic local。这样后续 devirtualization、escape analysis 和 scalar replacement 会更自然。

当前 clean-room 主线使用 block parameters 表达 stack/local merge，branch/goto/switch terminator 携带 target arguments。已覆盖 diamond branch、local/stack merge、simple loop counter 和 switch merge；stack height、type 或 local slot category 不一致时必须显式 `frontendSkipped`，不能猜测 frame state。

Exception/JMM/class-init 语义当前为保守 helper/fence-backed base：typed catch handler block 带 exception object 参数，显式 `athrow` lower 为 `THROW` terminator 并已接入 env-backed JNI `Throw` bridge，null/array/cast/div-zero implicit exception site 记录在 instruction metadata；`idiv` / `irem` / `ldiv` / `lrem` 在 LLVM path 必须保留为 div/rem exception helper call，采用 JNI pending exception + placeholder return convention；`arraylength`、primitive array load/store、`aaload` / `aastore` 的当前 subset 由 JNI array helper 拥有 null/bounds/wrong-type exception 语义；field helper 拥有 null receiver exception。`monitorenter` / `monitorexit`、`ACC_SYNCHRONIZED` method、识别出的 synchronized exceptional unlock handler、volatile read/write、final field publication、monitor happens-before、Thread.start/join happens-before 都有明确 IR marker；当前 LLVM path 已对 volatile read/write field helper E2E 保留 acquire/release fence，并对 synchronized block/method 通过 JNI `MonitorEnter` / `MonitorExit` helper path 覆盖正常/异常释放 smoke。复杂 catch-all/finally 仍用明确 reason code 保守跳过。

Class initialization active use 已有 skeleton：跨 owner `getstatic` / `putstatic` / `invokestatic` 和 `new` 插入 class object + `CLASS_INIT_GUARD`，guard 后记录 class-init happens-before marker；同 owner static field / static invoke 不递归 guard，因为当前 owner 已处于初始化语义内。`<clinit>` body 插入 begin/end/failed helper，同 owner self access 不递归 guard。Packaging 当前已有 generic straight-line/simple-branch `<clinit>` body helper first layer，可把选中的简单 static int/long/String writes、简单 arithmetic 和无 block-arg 的 branch/goto 重写成 Java stub + same-owner private static native helper；完整 classloader 并发、失败状态复用、recursive init runtime 行为和跨 owner static reads 仍由 runtime/toolchain 后续接实。

JDK 和 invokedynamic 当前通过 policy/handler 显式分流：`JdkIntrinsicRegistry` 覆盖 Object/String/StringBuilder/System.arraycopy/Math/boxing/Objects 第一批 direct/runtime helper；未知 JDK call 产生 `JVM_HELPER_FALLBACK` warning 并把 method 标记为 `halfLowered`。StringConcatFactory `makeConcat` 已能 lower 到真实 StringBuilder helper E2E；`makeConcatWithConstants` 常见 recipe 通过 tokenized `j2ll_rt_string_constant` carrier + StringBuilder helper E2E。LambdaMetafactory `metafactory` 常见非捕获、单引用捕获、static method reference、JDK public instance method reference 和 constructor reference 通过 `j2ll_rt_lambda_new` helper E2E；unsupported recipe、`altMetafactory` runtime class semantics 或复杂 capture 走 `halfLowered` fallback。

Allocation/String/type/dispatch helper-backed lowering 当前有第一批可运行 JVM-hosted 路径：selected primitive `newarray` 和 selected `anewarray` lower 到 tokenized JNI allocation helpers，ordinary-method `new T(int,int)` 可走 object allocation + constructor call helper，`checkcast` / `instanceof` lower 到 JNI `IsInstanceOf` backed helpers，String `length` / `equals` lower 到 env-backed String helpers，same-class selected static/private-special call 可 lower 成 direct LLVM internal call，no-arg `int` virtual/interface call site 可以 lower 到 tokenized JNI dispatch helper。primitive/reference arrays 作为 opaque JNI handles 通过 `New<Type>Array` / `Get<Type>ArrayRegion` / `Set<Type>ArrayRegion` / `NewObjectArray` / `GetObjectArrayElement` / `SetObjectArrayElement` 操作，`aastore` wrong-type 由 JVM/JNI 抛 `ArrayStoreException`。`multianewarray`、更广 constructor shapes 和复杂 dispatch shape 必须给出明确 helper/fallback reason，不能静默 skip。

validator 至少要检查：

- use-before-def
- dominance
- value type
- terminator
- phi/block parameter arity
- exceptional edge input

## Invoke lowering

invoke lowering 应消费 runtime analysis facts：

- `STATIC` / `SPECIAL`：可直接 lower。
- `VIRTUAL` / `INTERFACE` 单目标：按 `DevirtualizationPlan` lower 成 direct call 或 direct helper。
- 多目标但目标集合完整：保留 runtime dispatch helper 或 method table helper。
- unknown、external 或 skipped target：生成 JVM helper fallback plan；包含该 call site 的 method 记录为 `halfLowered`，并由 packaging 按 `fallbackMode` 存储 fallback bytecode target。

class initialization、null check、access check、exception behavior 必须在 lowering 或 runtime helper 中有明确归属。不能因为 devirtualized 就丢失 JVM 可见语义。

lowering 阶段只描述 fallback 需求，不决定 fallback bytecode 放在 JAR 还是 native artifact。schema version 1 的实际存储策略是 `nativeEmbeddedClassBlob`，由 packaging 阶段执行。

## 新增 lowering 的推荐路径

1. 在 guide 或 `AGENTS.md` 中确认 opcode 属于哪个 lowerer。
2. 新增最小 bytecode test，先复现 unsupported 行为或目标行为。
3. 实现 lowerer。
4. 增加 IR validator 覆盖，如果引入新 IR shape。
5. 增加 LLVM backend test，如果 IR 需要新的 backend emission。
6. 增加 pipeline/e2e test，如果功能跨过 frontend、runtime helper 或 packaging。

新增 Java 语义支持时还要回答：

- 是否影响 verifier/frame facts？
- 是否影响 exceptional control flow？
- 是否需要 class hierarchy 或 call graph facts？
- 是否需要 runtime helper？
- 是否改变 frontend skip/fallback 行为？

## 测试

- opcode 和 method-level lowering。
- branch join。
- loop。
- exception handler。
- stack merge。
- local merge。
- invoke/devirtualization。
- unsupported feature diagnostic。
