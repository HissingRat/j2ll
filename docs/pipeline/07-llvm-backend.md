# 07 LLVM Backend

本阶段只消费验证后的 optimized IR 和 runtime/helper metadata，输出 LLVM IR 和 native build 所需 artifact。LLVM IR 混淆必须基于 LLVM module model，不做 `.ll` 文本 regex 后处理。保护/混淆的完整设计见 [`../protection-obfuscation.md`](../protection-obfuscation.md)。

## 输入

- validated optimized `IrProgram`
- runtime helper metadata
- direct call/devirtualization metadata
- per-class output layout metadata

## 输出

- per-class LLVM module model
- per-class LLVM text
- Zig toolchain LLVM input manifest
- runtime stub C sources
- native registration plan
- LLVM module model dumps

## 推荐包

```text
xyz.melodysky.backend.llvm
xyz.melodysky.backend.llvm.model
xyz.melodysky.backend.llvm.pass
xyz.melodysky.backend.llvm.protection
```

推荐类型：

- `LlvmTextBackend`
- `PerClassIrPartitioner`
- `LlvmModuleLowerer`
- `LlvmModule`
- `LlvmFunction`
- `LlvmBasicBlock`
- `LlvmInstruction`
- `LlvmDeclaration`
- `LlvmModuleEmitter`
- `LlvmFunctionEmitter`
- `LlvmTypeLowerer`
- `LlvmNameMangler`
- `LlvmHelperDeclarationCollector`
- `LlvmModulePassPipeline`
- `LlvmProtectionPipeline`

## Recommended flow

```text
IrProgram
  -> PerClassIrPartitioner
  -> LlvmModuleLowerer
  -> LlvmModulePassPipeline
  -> LlvmTextEmitter
  -> ZigToolchain native build
```

`LlvmModuleLowerer` 必须按原始 class 生成 LLVM module；`LlvmTextEmitter` 只负责打印 module model。LLVM-level name obfuscation、opaque predicates、indirect calls、global layout 和 visibility pass 都应操作 module model。

## Zig Toolchain Contract

LLVM backend 的正式 native build handoff 面向 managed Zig toolchain。Schema version 1 固定 Zig `0.15.2`，不提供 toolchain config。当前 `HostNativeLibraryBuilder` 只保留为兼容 facade；它必须委托 generated `build.zig` / managed `zig build` path，不能直接消费 `.ll` 并调用 host compiler：

- 每个原始 class 仍生成 class-aligned `.ll`，不先合成 monolithic LLVM file。
- `.ll`、Zig-managed `.o`、JNI wrapper C、runtime helper C 和 fallback blob carrier sources 由 `ZigToolchain` 统一编译/链接。
- `ZigToolchain` 从可执行 `j2ll.jar` 同级目录解析 `<j2ll-home>/zig/zig(.exe)`；缺失或版本不匹配时，先查找同目录官方 Zig `0.15.2` archive，没有再下载，并将官方 archive 根目录内容规范化到 `<j2ll-home>/zig`。
- backend 可以输出 object-ready metadata，但不能直接调用 host `cc`、platform linker、`clang`、`zig cc` 或 `llc` 作为公开契约。
- 如果 Zig 当前 target 不支持某种 `.ll` / `.o` 输入能力，必须在 preflight 阶段给出明确 diagnostic，不能静默退回其他 linker。
- Java method 对应 LLVM function 默认 `internal` / hidden；需要被 C wrapper 跨 object 调用的 native build artifact 可使用 `external hidden`，但 symbol audit 仍必须证明它不进入 dynamic export list。JNI wrapper 和 bootstrap symbols 才能进入 export allowlist。

## Current Implemented Slice

当前已有最小 `LLVM_NATIVE_PATH`：

- `NativeImplementationPlanner` 为每个注册方法记录 implementation path：`LLVM_NATIVE_PATH` 或 `TEMPLATE_JNI_PATH`，并写入 lowering report。
- `LLVM_NATIVE_PATH` 覆盖 ordinary static 和 instance method 的第一层 primitive/reference-handle shape：primitive scalar 参数和返回支持 `boolean` / `int` / `long` / `float` / `double` / `void`，reference/String/primitive-array/reference-array 值作为 opaque JNI handle 传递、返回或交给已登记 helper，不能 dereference。
- 已接实的运行路径是 Bytecode -> SSA IR -> per-class LLVM module / `.ll` -> hidden linkable LLVM function -> JNI wrapper -> `RegisterNatives` -> output JAR child JVM E2E。
- 启用 `protection.llvm.nameObfuscation` 时，`LlvmNameMangler` 在 planner、LLVM lowerer、Zig workspace writer 和 JNI wrapper generator 之间共享同一 deterministic symbol 来源；C wrapper 调用 `j2ll_f_<sha256>` hidden linkable function，raw Java method symbol 不作为 native ABI 公开。
- 启用 `protection.llvm.indirectCalls` 时，same-class selected static/private direct LLVM call 经过 `LlvmCallIndirectionPass` 默认变成 hidden signature-group function-pointer table `j2ll_cit_<sha256>` indirect call；caller 通过 deterministic table order load function pointer，再调用原 hidden LLVM function。dispatcher switch `j2ll_cid_<sha256>` 仍作为 fallback 形态。table/dispatcher symbol 和 Java method hidden function 都不得出现在 dynamic export list。该 pass 同时作用于 report/intermediate module 和 Zig workspace 使用的 per-class `.ll`，table 成功 reason code 为 `CALL_INDIRECTION_TABLE`。
- E2E 目前覆盖 static int add、long arithmetic、double arithmetic、boolean compare branch、void no-op、if/else return、nested if、block-parameter/phi merge、table/lookup switch terminator lowering、JVM numeric helper opcode `i2b/f2i/lcmp/fcmpl`、protected float/double constant raw-bit encryption through integer XOR + LLVM bitcast、protected CFF dispatcher switch for simple branch methods、static/instance/volatile field read/write/add through JNI field helpers, null receiver NPE ownership, synchronized block/method monitor helper calls through JNI `MonitorEnter` / `MonitorExit`, explicit `athrow` through JNI `Throw`, static reflection helpers for constant `Class.forName` / no-arg、reference、primitive 和 array 常量参数 descriptor 的 `getDeclaredMethod` / `getDeclaredConstructor` / `getDeclaredField` / `Method.invoke` / `Constructor.newInstance` / `Field.get` / `Field.set` / `Field.getInt` / `Field.setInt` / `setAccessible(true)`, Unsafe statically resolved field-token helpers for `objectFieldOffset` / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` plus `AllocObject`-backed `allocateInstance`, typed-int VarHandle helpers, String/reference field pass-through/null return, ordinary `CONST_STRING` encrypted helper path, `idiv` / `irem` / `ldiv` / `lrem` through ArithmeticException helpers, `byte[]` / `short[]` / `char[]` / `int[]` / `long[]` / `float[]` / `double[]` / reference array load/store/length helpers, `System.arraycopy` byte/int/long/double/object/overlap/null/oob/ArrayStoreException helper, selected primitive/reference array allocation helpers, ordinary-method object construction helper subset, `checkcast` / `instanceof` helpers, String `length` / `equals` / `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)` helpers, explicit StringBuilder append chain, StringConcatFactory `makeConcat` / common `makeConcatWithConstants`, LambdaMetafactory common `metafactory` helper, LDC MethodHandle + `invokeExact` direct call, Math `abs/min/max` int/long/float/double helpers, Integer/Long/Boolean/Double boxing-unboxing, Objects.requireNonNull/equals, selected same-class static/private-special caller -> selected callee direct LLVM internal call, and tokenized virtual/interface/default-interface JVM dispatch helpers for no-arg int, int-arg int, reference return and single-reference-argument/reference-return shapes, including conflict boundary reporting。

- JNI wrapper declaration and LLVM function signature must use the same env/owner-class policy. Non-env JVM numeric helpers such as `i2b` / `lcmp` do not make the hidden LLVM function receive `JNIEnv*`; field/array/allocation/type/dispatch/reflection/String/Unsafe/VarHandle/helper calls that actually need JNI state do. Regression coverage checks this with child JVM numeric helper E2E.
- Field access in LLVM uses tokenized generic helpers from `RuntimeHelperCatalog` (`j2ll_rt_field_*`) and passes explicit `JNIEnv*`, `jclass`/`jobject` handles and deterministic field tokens. Field helper symbols must not embed raw field names; field owner/name/descriptor may appear in reports/sidecars and the native helper lookup table.
- Unsafe field access in LLVM uses the same JVM-hosted discipline: `objectFieldOffset` returns a deterministic runtime metadata token for a statically resolved `Field`, and `getInt` / `putInt` / `compareAndSwapInt` resolve that token to JNI field access. LLVM/native code must never treat the token as a Java object address or byte offset.
- Call sites record a decision in reports: `DIRECT_LLVM_CALL`, `JVM_CALL_HELPER`, `DISPATCH_HELPER`, `DEFAULT_INTERFACE_DISPATCH_HELPER`, `DEFERRED_DISPATCH_HELPER` or `JVM_HELPER_FALLBACK`. Current real generic LLVM call support is same-class selected static and private/special callee direct call plus a tokenized JNI dispatch helper subset for no-arg int, int-arg int, reference return and single-reference-argument/reference-return virtual/interface calls. The dispatch helper calls JVM/JNI `Call<Type>Method` and never uses native vtables or object layout; inherited and overridden default-interface method smoke paths are covered by child JVM E2E. Default-interface conflict/diamond boundary is reported as `UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT` plus `DEFAULT_INTERFACE_DISPATCH_FALLBACK`; default-interface super `I.super.m()` is currently `frontendSkipped` with `UNSUPPORTED_DEFAULT_INTERFACE_SUPER` because helper-class bytecode copying cannot preserve verifier direct-superinterface rules. Broader dynamic dispatch remains helper/fallback boundary.
- String content operations beyond the listed helper subset、broader constructor/object semantics、complex finally/exception state merge、reflection shapes beyond the JVM bridge matrix、raw/off-heap Unsafe/VarHandle (`UNSAFE_RAW_MEMORY_FALLBACK` / `VAR_HANDLE_DYNAMIC_FALLBACK`)、complex MethodHandle / full `altMetafactory` runtime semantics 和更复杂 virtual/interface dispatch 仍走 `TEMPLATE_JNI_PATH` / JNI helper / fallback，不声明为 generic LLVM lowering。ordinary `halfLowered` methods whose bytecode body can be preserved now use encoded `nativeEmbeddedClassBlob` helper `invoke` bodies outside the generic LLVM path.

## 边界

- LLVM backend 只消费 IR 和 metadata。
- backend 不补 CFG。
- backend 不做 devirtualization decision。
- backend 不吞掉 validator 错误。
- backend 不负责 JVM 语义猜测；null/class-init/dispatch/exception 等语义必须来自 IR 或 runtime metadata。
- LLVM protection pass 不根据 `.ll` 文本搜索替换。
- Java method 对应 LLVM function 默认 internal/hidden；object-link artifact 可以使用 `external hidden`，但 JNI / C ABI wrapper 才可进入 export list。

## Runtime helper 对齐

后端声明 helper 时，runtime stub generator 必须能生成同签名实现。新增 helper 时至少同步：

- helper name schema
- argument ABI
- return ABI
- exception behavior
- reference lifetime policy
- backend declaration test
- runtime stub generator test

当前 backend 对 JVM 数值 helper、throw helper、monitor helper、exception factory helper、Thread happens-before helper、field helper、div/rem ArithmeticException helper、primitive/reference array helper、allocation helper、type helper、String helper、Math scalar helper 和 call-site helper 使用固定 `j2ll_rt_*` ABI，并在 `RuntimeHelperCatalog` 中登记声明。`LlvmModule` 持有 explicit declarations，`LlvmTextEmitter` 在 functions 前输出 `declare`，让 `.ll` 可以被 managed Zig/LLVM toolchain 直接编译。对 String/Class/MethodType/MethodHandle 常量、仍未覆盖的 array copy、复杂 constructor/object semantics 和动态分派 skeleton，backend 使用按 symbol hash 派生的 per-constant/per-type helper 名称；这些 helper 代表 JVM/JNI helper-backed 入口，不通过 `.ll` 文本后处理生成。

Block parameters lower 成 LLVM `phi`。`THROW` terminator 生成 env-backed `j2ll_rt_throw(ptr %j2ll_env, ptr throwable)`，随后返回 descriptor 对应的 pending-exception-safe placeholder；native wrapper 返回 JVM 后由 pending exception 进入 Java catch path。Integer div/rem 和 array/type/allocation/reflection helpers 使用同样的 pending exception convention。volatile/final/monitor JMM marker 生成 conservative LLVM `fence`；Thread start/join happens-before marker 生成固定 runtime helper call。

## 测试

- primitive/reference type lowering。
- branch/switch/throw/return。
- helper declaration collection。
- direct call thunk。
- per-class LLVM module emission。
- runtime stub generator 与 backend declaration 对齐。
- LLVM module model -> text golden test。
- LLVM protection pass deterministic seed test。
- symbol visibility preflight test。
- `LLVM_NATIVE_PATH` child JVM differential test，确认 JNI wrapper 调用 LLVM-generated hidden function 而不是模板化 C body。
