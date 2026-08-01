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
- final native implementation/registration/retention plans
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
- `LlvmExceptionFlowLowerer`
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
- `NativeLlvmCompiler` 是 final LLVM compilation 的唯一生产者：它只接收 final `LLVM_NATIVE_PATH` implementation 与其可达 compiler-internal helper，并把同一组 module/pass result 同时交给 protection report、intermediate dump 和 Zig source writer。`TEMPLATE_JNI_PATH` 与 `skipped` method 不得另行 lower 后制造虚假 `RAN` 或只存在于报告中的 symbol。
- `.ll`、Zig-managed `.o`、JNI wrapper C 和 runtime helper C 由 `ZigToolchain` 统一编译/链接。Zig source graph 不包含任何原 class/method bytecode blob、carrier 或 decoder。
- `ZigToolchain` 从可执行 `j2ll.jar` 同级目录解析 `<j2ll-home>/zig/zig(.exe)`；缺失或版本不匹配时，先查找同目录官方 Zig `0.15.2` archive，没有再下载，并将官方 archive 根目录内容规范化到 `<j2ll-home>/zig`。
- backend 可以输出 object-ready metadata，但不能直接调用 host `cc`、platform linker、`clang`、`zig cc` 或 `llc` 作为公开契约。
- 如果 Zig 当前 target 不支持某种 `.ll` / `.o` 输入能力，必须在 preflight 阶段给出明确 diagnostic，不能静默退回其他 linker。
- Java method 对应 LLVM function 默认 `internal` / hidden；需要被 C wrapper 跨 object 调用的 native build artifact 可使用 `external hidden`，但 symbol audit 仍必须证明它不进入 dynamic export list。JNI wrapper 和 bootstrap symbols 才能进入 export allowlist。

## Current Implemented Slice

当前已有最小 `LLVM_NATIVE_PATH`：

- `NativeImplementationPlanner` 为每个注册方法记录 implementation path：`LLVM_NATIVE_PATH` 或 `TEMPLATE_JNI_PATH`，并写入 lowering report。两者都必须是完整、可执行的 native implementation，成功时 method 状态统一为 `nativeLowered`。
- `LLVM_NATIVE_PATH` 覆盖 ordinary static 和 instance method 的第一层 primitive/reference-handle shape：primitive scalar 参数和返回支持 `boolean` / `int` / `long` / `float` / `double` / `void`，reference/String/primitive-array/reference-array 值作为 opaque JNI handle 传递、返回或交给已登记 helper，不能 dereference。
- 已接实的常规运行路径是 Bytecode -> SSA IR -> per-class LLVM module / `.ll` -> hidden linkable LLVM function -> JNI wrapper -> `RegisterNatives` -> output JAR child JVM E2E。`internalNativeOnly`方法保留同一个hidden LLVM function，但不再保留Java declaration或registration binding。
- 启用 `protection.llvm.nameObfuscation` 时，`LlvmNameMangler` 在 planner、LLVM lowerer、Zig workspace writer 和 JNI wrapper generator 之间共享同一 deterministic symbol 来源；C wrapper 调用 `j2ll_f_<sha256>` hidden linkable function，raw Java method symbol 不作为 native ABI 公开。
- 启用 `protection.llvm.indirectCalls` 时，same-class selected static/private direct LLVM call 经过 `LlvmCallIndirectionPass` 默认变成 hidden signature-group function-pointer table `j2ll_cit_<sha256>` indirect call；caller 通过 deterministic table order load function pointer，再调用原 hidden LLVM function。dispatcher switch `j2ll_cid_<sha256>` 是另一种完全 native 的实现形态。table/dispatcher symbol 和 Java method hidden function 都不得出现在 dynamic export list。该 pass 同时作用于 report/intermediate module 和 Zig workspace 使用的 per-class `.ll`，table 成功 reason code 为 `CALL_INDIRECTION_TABLE`。
- E2E 目前覆盖 static int add、long arithmetic、double arithmetic、boolean compare branch、void no-op、if/else return、nested if、block-parameter/phi merge、table/lookup switch terminator lowering、JVM numeric helper opcode `i2b/f2i/lcmp/fcmpl`、protected float/double constant raw-bit encryption through integer XOR + LLVM bitcast、protected CFF dispatcher switch for simple branch methods、static/instance/volatile field read/write/add through JNI field helpers, null receiver NPE ownership, synchronized block/method monitor helper calls through JNI `MonitorEnter` / `MonitorExit`, explicit `athrow` through JNI `Throw`, static reflection helpers for constant `Class.forName` / no-arg、reference、primitive 和 array 常量参数 descriptor 的 `getDeclaredMethod` / `getDeclaredConstructor` / `getDeclaredField` / `Method.invoke` / `Constructor.newInstance` / `Field.get` / `Field.set` / `Field.getInt` / `Field.setInt` / `setAccessible(true)`, Unsafe statically resolved field-token helpers for `objectFieldOffset` / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` plus `AllocObject`-backed `allocateInstance`, typed-int VarHandle helpers, String/reference field pass-through/null return, ordinary `CONST_STRING` encrypted helper path, `idiv` / `irem` / `ldiv` / `lrem` through ArithmeticException helpers, `byte[]` / `short[]` / `char[]` / `int[]` / `long[]` / `float[]` / `double[]` / reference array load/store/length helpers, `System.arraycopy` byte/int/long/double/object/overlap/null/oob/ArrayStoreException helper, selected primitive/reference array allocation helpers, ordinary-method object construction helper subset, `checkcast` / `instanceof` helpers, String `length` / `equals` / `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)` helpers, explicit StringBuilder append chain, StringConcatFactory `makeConcat` / common `makeConcatWithConstants`, LambdaMetafactory common `metafactory` helper, LDC MethodHandle + `invokeExact` direct call, Math `abs/min/max` int/long/float/double helpers, Integer/Long/Boolean/Double boxing-unboxing, Objects.requireNonNull/equals, env-backed `Object.getClass()` with null-receiver NPE, selected same-class static/private-special caller -> selected callee direct LLVM internal call, and tokenized virtual/interface/default-interface JVM dispatch helpers for no-arg int, int-arg int, reference return and single-reference-argument/reference-return shapes, including conflict boundary reporting。
- Protected JVM exception flow把每个受保护helper instruction拆成LLVM physical blocks：normal path进入continuation；exception path调用`j2ll_rt_pending_exception`取得throwable，清除JNI pending state，按classfile顺序调用`j2ll_rt_instanceof`匹配typed handlers或直接进入catch-all adapter，并把throwable与throw-site locals作为handler `phi` incoming。没有匹配项时调用`j2ll_rt_rethrow`并返回descriptor-safe placeholder。显式`athrow`从已有throwable开始同一dispatch，不重复读取pending state。
- Constructor/class-initializer的LLVM body由专门initializer plan提供。Constructor Java stub保留唯一线性verifier prefix到真实`this(...)`/`super(...)` invocation，LLVM function只包含post-init body；`<clinit>` loader/bootstrap stub调用承载完整supported initializer IR的same-owner native helper。
- 上述新增exception与initializer路径已有focused model/ABI tests和Windows real-Zig host child-JVM differential；`Object.getClass()`与`Thread.sleep(J)V`当前有focused planner/LLVM/C ABI evidence。其他五个固定target目前只有format/architecture/export/build-graph结构性证据，不得宣称已完成对应OS/JVM runtime E2E。

- JNI wrapper declaration and LLVM function signature must use the same env/owner-class policy. Non-env JVM numeric helpers such as `i2b` / `lcmp` do not make the hidden LLVM function receive `JNIEnv*`; field/array/allocation/type/dispatch/reflection/String/Unsafe/VarHandle/helper calls that actually need JNI state do. Regression coverage checks this with child JVM numeric helper E2E.
- Native instance wrapper传入LLVM/native-field ABI的owner表示method/field的declared defining class。它必须在registered native method的defining-loader context解析，不能用`GetObjectClass(self)`替代，否则base method在subclass receiver上会把同一个internalized static field分裂成多个storage key。
- JVM field access in LLVM uses build-scoped concrete-binding helpers and passes explicit `JNIEnv*` plus `jclass`/`jobject` handles. Final native source不得生成global field token/metadata lookup table；helper symbol只含hash，declared owner/name/descriptor只在该binding的encoded call-local metadata中形成。
- Internalized static fields use the separate `j2ll_nfs_*` ABI. `boolean/byte/short/char/int/long` are lowered with descriptor-correct truncation/extension, `float/double` move through integer raw bits and LLVM `bitcast`, and reference/array values use JNI `ObjectArray` access through the generated Loader's `ClassValue<Object[]>`. A generated no-predecessor LLVM prologue allocates only a null native-stack cache cell, so an IR backedge to its original entry cannot reinitialize the cache. The first field access actually executed obtains the sidecar local ref, later accesses in the same native function activation reuse it, and every function exit releases it. No strong native global reference is created.
- Unsafe field access in LLVM uses the same JVM-hosted discipline: `objectFieldOffset` returns a deterministic runtime metadata token for a statically resolved `Field`, and `getInt` / `putInt` / `compareAndSwapInt` resolve that token to JNI field access. LLVM/native code must never treat the token as a Java object address or byte offset.
- Call sites record a decision in reports: `DIRECT_LLVM_CALL`, `JVM_CALL_HELPER`, `DISPATCH_HELPER`, `DEFAULT_INTERFACE_DISPATCH_HELPER` or `DEFERRED_DISPATCH_HELPER`; unsupported shape records a specific reason and makes the complete method `skipped`. Current real generic LLVM call support is same-class selected static and private/special callee direct call plus a tokenized JNI dispatch helper subset for no-arg int, int-arg int, reference return and single-reference-argument/reference-return virtual/interface calls. The dispatch helper calls JVM/JNI `Call<Type>Method` and never uses native vtables or object layout; inherited and overridden default-interface method smoke paths are covered by child JVM E2E. Default-interface conflict/diamond and default-interface super `I.super.m()` are current `skipped` boundaries with explicit reasons and no registration.
- Final-plan method internalization retargets an approved call from JVM dispatch to the retained hidden native entry. Authorization and observer-risk decisions are fixed before backend lowering: exact allowlisted public static may come from declared-closed or current-JAR-only scope, while public instance requires declared closed world and exact same-owner call sites but no final method/class or blanket closed-slot proof. Same-owner pure direct calls keep their existing LLVM ABI. Static cross-owner and reference/pending-exception-sensitive calls use a generated bridge with the exact JNI descriptor, defining `jclass` lookup when static, and `PushLocalFrame`/`PopLocalFrame` promotion; it does not use `GetMethodID`/`Call<Type>Method` for the removed target. Only wrappers reachable from such internal dispatch remain in generated C, keeping size growth bounded.
- String content operations beyond the listed helper subset、无法安全分割的constructor pre-init prefix、无法形成一致throw-site frame/block arguments的exception state、复杂monitor/finally interaction、reflection shapes beyond the JVM bridge matrix、raw/off-heap Unsafe/VarHandle、complex MethodHandle / full `altMetafactory` runtime semantics 和更复杂 virtual/interface dispatch 只有在 `LLVM_NATIVE_PATH`、`TEMPLATE_JNI_PATH` 或 JNI helper 提供完整 native implementation 时才可标记为 `nativeLowered`；否则整 method `skipped` 并保留原 Code。
- JNI helper产生的owned local reference只会在registered native method返回时由JVM自动回收，helper返回、internal LLVM callee返回和loop iteration本身不会释放它。当前per-method ownership-aware release planning在frozen native IR上跟踪reference origin、dynamic ownership、last use与唯一ownership transfer；site-sensitive fixed point把instruction handler requirement回传到block live-in但不污染normal live-out，从而在异常路径保留并在protected call正常完成后及时释放。对可证明的普通路径、parallel edge adapter、loop/backedge重定义、typed/catch-all handler transfer/exit与显式`athrow`发出`DeleteLocalRef`。重复transfer、handler live-set不一致或其他无法证明所有路径有界释放的shape，由final native coverage以`UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME`整方法跳过。返回reference或内部产生owned/pending-exception reference的registered native callee通过JVM/JNI bridge建立嵌套native activation；direct LLVM call只用于不产生这些reference的callee，无法桥接的compiler-internal shape在Zig前fail closed。
- JNI bridge的`jvalue[]`临时参数区由独立scratch planner按function内最大arity规划，并只在registered activation prologue执行一次固定`alloca`；loop/catch/backedge block只复用该stack slot并执行GEP/store。不得在循环内重复`alloca`，也不得改用native heap长期保存Java reference。
- CFF的dispatcher会人为引入synthetic cycle。对其余structural条件原本可应用、但会产生owned JNI local ref的方法，`ControlFlowFlatteningPass`保持输入IR并以`CONTROL_FLOW_FLATTENING_OWNED_LOCAL_REFERENCE`记录pass-level `SKIPPED`；其他适用pass与native implementation planning继续执行。该protection结果不把原本可安全释放local ref的方法改成method-level `skipped`。

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

当前 backend 对 JVM 数值 helper、throw/pending-clear-rethrow helper、monitor helper、exception factory helper、`Thread.sleep(J)V`与Thread happens-before helper、field helper、div/rem ArithmeticException helper、primitive/reference array helper、allocation helper、type helper、String helper、`Object.getClass()`、Math scalar helper 和 call-site helper 使用固定 `j2ll_rt_*` ABI，并在 `RuntimeHelperCatalog` 中登记声明。`LlvmModule` 持有 explicit declarations，`LlvmTextEmitter` 在 functions 前输出 `declare`，让 `.ll` 可以被 managed Zig/LLVM toolchain 直接编译。对 String/Class/MethodType/MethodHandle 常量、array copy、复杂 constructor/object semantics 和动态分派，只有对应 C/JNI runtime implementation 已接实并通过 ABI 校验时，backend 才能使用按 symbol hash 派生的 per-constant/per-type helper 名称；仅有 declaration/skeleton 的 shape 必须使完整 method `skipped`。这些 helper 不通过 `.ll` 文本后处理生成。

Block parameters lower 成 LLVM `phi`。无user handler的 `THROW` terminator调用env-backed `j2ll_rt_rethrow(ptr %j2ll_env, ptr throwable)`，随后返回descriptor对应的pending-exception-safe placeholder；有handler的显式throw走有序typed/catch-all dispatch。Integer div/rem 和 array/type/allocation/reflection/call helpers位于user try region时，LLVM在每个helper site后读取pending exception并按IR提供的handler arguments转移；不在try region时仍使用pending exception + placeholder return convention。volatile/final/monitor JMM marker 生成 conservative LLVM `fence`；Thread start/join happens-before marker 生成固定 runtime helper call。

Planner只接受exception-semantics分类器认可、拥有exception value、非空handler列表和完整edge arguments的protected instruction。Frontend frame merge或backend dispatch invariant失败仍使完整method `skipped`；backend不得修补缺失locals、重排handler或猜测catch类型。仅用于`ACC_SYNCHRONIZED` unwind的`$sync_cleanup`与`<clinit>` unwind的`$class_init_failed` synthetic edge继续保持各自语义。

`fieldInternalization` 的 final-plan validator 只接受状态为 `nativeLowered`、且真实使用合格 native storage ABI 的 accessor。任何 `skipped`、缺失 implementation 或仍访问 JVM field 的 accessor 都使该 field 保留在 classfile。

`methodInternalization` 的 final-plan validator要求approved target仍存在于LLVM user-method closure、rewrite strategy为`internalNativeOnly`、registration plan中不存在该binding，且每个caller route有对应direct/dispatch implementation。public instance的每个route必须携带same-owner exact-target evidence；validator不得重新引入final method/class或override-slot blanket限制。已解析exact observer应已在analysis阶段阻断，unsupported/unbounded动态observer则只携带用户接受的warning/report evidence，backend不得把两者混为一谈。reference result与pending exception必须能从nested local frame promote；证据不完整时保留普通`nativeOriginal`与registration，而不是生成不完整native call。

## 测试

- primitive/reference type lowering。
- branch/switch/throw/return。
- pending exception capture/clear、ordered typed/catch-all dispatch、unmatched rethrow和handler phi incoming。
- helper declaration collection。
- direct call thunk。
- per-class LLVM module emission。
- runtime stub generator 与 backend declaration 对齐。
- LLVM module model -> text golden test。
- LLVM protection pass deterministic seed test。
- symbol visibility preflight test。
- `LLVM_NATIVE_PATH` child JVM differential test，确认 JNI wrapper 调用 LLVM-generated hidden function 而不是模板化 C body。
- constructor verifier-prefix/post-init split、`<clinit>` native helper与`Object.getClass()` ABI/runtime parity。
- internal-only direct/static/instance descriptor bridge、nested-local-frame exception/reference promotion与wrapper reachability。
