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

当前 clean-room 主线使用 block parameters 表达 stack/local merge，branch/goto/switch terminator 携带 target arguments。已覆盖 diamond branch、local/stack merge、simple loop counter、switch merge，并通过 child JVM differential 覆盖 `tableswitch` / `lookupswitch` 进入 `LLVM_NATIVE_PATH` 的窄路径；stack height、type 或 local slot category 不一致时，完整 method 必须显式记录为 `skipped`，不能猜测 frame state。

Exception/JMM/class-init 语义当前为显式 IR + helper/fence-backed base。对位于user try region且可能留下JNI pending exception的instruction，SSA为每个site记录独立exception value、按classfile exception-table顺序排列的typed/catch-all handlers，以及throw-site live locals；每条exception edge的arguments固定以throwable开头并继续携带handler需要的locals，handler block parameters与其严格对齐。显式 `athrow` lower 为 `THROW` terminator，并携带同样有序的exception edges。null/array/cast/div-zero implicit exception site、field/null receiver、call/allocation/class-init等helper site都通过统一exception-semantics分类器决定是否需要该metadata，不能由backend临时猜测。

`idiv` / `irem` / `ldiv` / `lrem` 在 LLVM path 必须保留为 div/rem exception helper call；`arraylength`、primitive array load/store、`aaload` / `aastore` 的当前 subset 由 JNI array helper 拥有 null/bounds/wrong-type exception 语义。LLVM在受保护site后立即把pending exception物化成exception CFG，因此这些helper不再仅限“返回JVM caller处理”：匹配的typed/catch-all handler可以在同一native method内继续执行，全部typed handler不匹配时再rethrow。`monitorenter` / `monitorexit`、`ACC_SYNCHRONIZED` method、识别出的 synchronized exceptional unlock handler、volatile read/write、final field publication、monitor happens-before、Thread.start/join happens-before 都有明确 IR marker；当前 LLVM path 已对 volatile read/write field helper E2E 保留 acquire/release fence，并对 synchronized block/method 通过 JNI `MonitorEnter` / `MonitorExit` helper path 覆盖正常/异常释放 smoke。只要这些 JVM/JNI helper 已真正接入最终 native body，method 仍是 `nativeLowered`。无法合并throw-site local frame、不可约exception-state、复杂monitor/finally interaction，以及 `Thread.<init>(Runnable)` / `start` / `join`、`Object.wait/notify` 等未实现shape仍用精确reason将完整method标记为`skipped`。

Class initialization active use 已有 skeleton：跨 owner `getstatic` / `putstatic` / `invokestatic` 和 `new` 插入 class object + `CLASS_INIT_GUARD`，guard 后记录 class-init happens-before marker；同 owner static field / static invoke 不递归 guard，因为当前 owner 已处于初始化语义内。`<clinit>` body 插入 begin/end/failed helper，同 owner self access 不递归 guard。当前initializer planner让合法 `<clinit>` 保留loader/bootstrap Java stub，并把完整、经最终LLVM planner验证的initializer IR注册为same-owner private static native helper；不再依赖只覆盖固定opcode清单的generic template body。

Constructor planner只接受可证明的verifier-safe split：Java端保留从入口到真正初始化`uninitializedThis`的唯一、线性 `this(...)` / `super(...)` prefix，包括原descriptor和计算实参所需bytecode；post-init IR才进入native body。pre-init有branch、整个constructor含任意exception table、或存在多个/无法唯一识别的initializing invocation时fail closed。Constructor post-init或class initializer的剩余IR若超出最终LLVM/helper支持矩阵，完整initializer仍为`skipped`。

JDK 和 invokedynamic 当前通过 policy/handler 显式分流：`JdkIntrinsicRegistry` 覆盖 Object/String/StringBuilder/System.arraycopy/Math/boxing/Objects 第一批 direct/runtime helper，其中 `Object.getClass()` 与 `Class.getClassLoader()` 使用env-backed JNI helper；`Thread.sleep(J)V` 调回 JVM static method并让 `InterruptedException` 留给统一pending-exception flow处理。另有一组逐descriptor审核的tokenized JVM bridge，当前包括class-relative resource lookup、`InputStream.close/readAllBytes`、`Throwable.addSuppressed`、`ByteBuffer`的`wrap/get/remaining`、`Arrays.fill(byte[], byte)`，以及`privateLookupIn`、`Lookup.defineHiddenClass/lookupClass`；这些调用仍由JVM拥有对象、资源、隐藏类和异常语义，native侧不解释其内部布局。System.arraycopy 当前 E2E 覆盖 byte/int/long/double/object/overlap/null/oob/ArrayStoreException。已实现的 JVM/JNI helper 是 native body 的组成部分，对应 method 记录为 `nativeLowered`，不得因为调用 JVM API 就降级状态。ArrayList/HashMap/Arrays/Collections/Optional/String.format、Throwable、除`sleep(J)V`外的Thread scheduler操作、raw/off-heap Unsafe、MethodHandle adapter 和复杂 `altMetafactory` 等超出已审核descriptor的shape如果尚无完整native implementation，则包含该shape的完整method记录为`skipped`，原Code原位保留。StringConcatFactory `makeConcat`、常见 `makeConcatWithConstants` 和 LambdaMetafactory `metafactory` 的已接实 helper path 继续进入 `nativeLowered`。reason code 必须描述具体缺口；reason 名称不能暗示存在任何隐藏字节码执行路径。

Allocation/String/type/dispatch helper-backed lowering 当前有第一批可运行 JVM-hosted 路径：selected primitive `newarray` 和 selected `anewarray` lower 到 tokenized JNI allocation helpers，ordinary-method `new T(int,int)` 可走 object allocation + constructor call helper，`checkcast` / `instanceof` lower 到 JNI `IsInstanceOf` backed helpers，String `length` / `equals` lower 到 env-backed String helpers，same-class selected static/private-special call 可 lower 成 direct LLVM internal call。virtual/interface call site 的当前 helper subset 覆盖 no-arg int、int-arg int、reference return 和 single-reference-argument/reference-return；helper 通过 JNI `GetObjectClass` / `GetMethodID` / `Call<Type>Method` 保留 JVM override/interface/default-interface dispatch，不使用 native vtable/object layout。default-interface inherited/override 真实 E2E 通过。conflict/diamond boundary、`I.super.m()` default-interface super、`multianewarray`、更广 constructor shapes 和更复杂 dispatch shape 在当前实现不能完整保持语义时，完整 method 记录为 `skipped`，原 Code 保留且不注册 native。primitive/reference arrays 作为 opaque JNI handles 通过 `New<Type>Array` / `Get<Type>ArrayRegion` / `Set<Type>ArrayRegion` / `NewObjectArray` / `GetObjectArrayElement` / `SetObjectArrayElement` 操作，`aastore` wrong-type 由 JVM/JNI 抛 `ArrayStoreException`。

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
- unknown、external 或 skipped target：只有在已实现 JNI dispatch ABI 能完整表达其语义时才允许继续；否则包含该 call site 的完整 method 记录为 `skipped`。

class initialization、null check、access check、exception behavior 必须在 lowering 或 runtime helper 中有明确归属。不能因为 devirtualized 就丢失 JVM 可见语义。

selected 且有 Code 的 method 在 final implementation plan 中只有两种结果：

- `nativeLowered`：所有语义都由最终 LLVM/C native body 与已接实的 JVM/JNI helper 实现。
- `skipped`：任何 unsupported diagnostic 都使整 method 退出 native pipeline；丢弃其 partial IR implementation，保留输入 class 中原 Code，不生成 native stub/helper/registration。

不生成、编码、携带或运行原 class/method 字节码副本。lowering 与 packaging 之间不存在 bytecode compatibility storage plan。

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
- 是否改变 `nativeLowered` / `skipped` 决策或 skipped reason？

## 测试

- opcode 和 method-level lowering。
- branch join。
- loop。
- exception handler。
- protected helper pending-exception、ordered typed/catch-all dispatch和throw-site local/block-argument parity。
- stack merge。
- local merge。
- invoke/devirtualization。
- unsupported feature diagnostic。
- weird-bytecode seed corpus：stack permutation、wide local/iinc、table/lookup switch、catch-all rethrow、multi-exit/nested/monitor finally boundary。
- `reports/opcode-support-matrix.json` 必须随着 opcode support 与 method-level `skipped` 边界更新，并由 golden/focused tests 覆盖。

本轮protected exception与initializer新增路径的真实运行证据当前是Windows real-Zig host child-JVM differential；六目标交叉构建只证明结构性artifact closure，不能替代non-host runtime E2E。
