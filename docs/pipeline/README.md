# Pipeline Guides

这里是 rewrite 后新编译管线的实现 guide 索引。总路线图见 [`../rewrite-roadmap.md`](../rewrite-roadmap.md)，项目结构和类职责见 [`../project-structure.md`](../project-structure.md)，Java/JVM 特性分层见 [`../java-support-tiers.md`](../java-support-tiers.md)，保护/混淆设计见 [`../protection-obfuscation.md`](../protection-obfuscation.md)，输入/配置/输出契约见 [`../io-config-output-contract.md`](../io-config-output-contract.md)。

新主线只写入：

```text
src/main/java
src/test/java
```

旧目录只作为 legacy reference：

```text
obfuscator/src/main/java
obfuscator/src/test/java
obfuscator/bench
```

## 目标管线

```text
ClassFileSource
  -> AsmClassParser
  -> MethodCfgBuilder
  -> ClassHierarchyBuilder
  -> RuntimeAnalysisPipeline
  -> BytecodeToSsaLowerer
  -> OptimizationPipeline
  -> ProtectionPipeline
  -> LlvmModuleLowerer
  -> LlvmProtectionPipeline
  -> LlvmTextEmitter
  -> ZigNativeBuildAndSymbolAudit
  -> Repackager
```

## 分阶段 guide

1. [`00-overview.md`](00-overview.md)：主线编排、source tree、clean-room bootstrap。
2. [`01-classfile-parse.md`](01-classfile-parse.md)：`.class` / JAR 输入与 ASM parse。
3. [`02-method-cfg.md`](02-method-cfg.md)：method-level bytecode CFG。
4. [`03-class-hierarchy.md`](03-class-hierarchy.md)：class hierarchy、method lookup、world model。
5. [`04-callgraph-runtime-analysis.md`](04-callgraph-runtime-analysis.md)：CHA、RTA、points-to、escape、devirtualization。
6. [`05-bytecode-to-ssa.md`](05-bytecode-to-ssa.md)：栈式 bytecode 到三地址 SSA IR。
7. [`06-optimization-passes.md`](06-optimization-passes.md)：method/program optimization pass。
8. [`07-llvm-backend.md`](07-llvm-backend.md)：LLVM IR emission 和 backend 边界。
9. [`08-diagnostics-validation-testing.md`](08-diagnostics-validation-testing.md)：diagnostics、validator、测试矩阵。
10. [`09-debug-dumps-docs.md`](09-debug-dumps-docs.md)：debug dump、可观测性、文档维护。
11. [`10-packaging-native-registration.md`](10-packaging-native-registration.md)：JAR rewrite、loader、native registration、fallback blob。
12. [`11-tier5-runtime-metadata-reflection-jni-unsafe.md`](11-tier5-runtime-metadata-reflection-jni-unsafe.md)：runtime metadata、static reflection、JNI ABI、MethodHandle/ConstantDynamic、Unsafe/VarHandle 前置层。

## 当前语义快照

- SSA merge 已使用 block parameters：branch/goto/switch target arguments 负责传递 predecessor frame value，LLVM backend lower 成 `phi`。
- Exception base 已支持 typed catch handler exception parameter、IR exceptional edge metadata、`THROW` terminator、implicit null/array/cast/div-zero exception site metadata，以及简单 catch-all rethrow / normal+exceptional cleanup shape。multi-exit finally、exception state merge 等仍精确 `frontendSkipped`。
- Monitor/JMM base 已支持 `monitorenter` / `monitorexit`、`ACC_SYNCHRONIZED` method、识别出的 synchronized exceptional cleanup helper-backed lowering、volatile read/write fence、final field publication fence、monitor happens-before marker、Thread.start/join happens-before helper。
- Class initialization skeleton 已支持 active-use guard：跨 owner `getstatic` / `putstatic` / `invokestatic` 和 `new` 插入 class object + guard；同 owner static field / static invoke 不递归 guard，因为当前 owner 已处于初始化语义内。`<clinit>` body 插入 begin/end/failed helper，同 owner `<clinit>` self access 不递归 guard。
- Runtime ABI 已有 catalog/signature/category model，可生成稳定 `runtime-helpers.h` 和 C skeleton；helper reference ABI token 使用 JNI handle 类型（`jobject` / `jclass` / `jarray` / `jthrowable`），C prototype 显式接收 `JNIEnv* env`，LLVM helper declaration 由同一 catalog 映射到 opaque `ptr`。
- JDK bootstrap 已有 `JdkIntrinsicRegistry` policy：Object/String/StringBuilder/System.arraycopy/Math/boxing/Objects 第一批走 direct/runtime helper；当前 host E2E 覆盖 String `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)`、显式 StringBuilder append chain、System.arraycopy primitive/object/overlap/异常、Integer/Long/Boolean/Double boxing-unboxing、Objects.requireNonNull/equals 和 Math int/long/float/double abs/min/max。未知 JDK call 走 `halfLowered` JVM helper fallback warning。
- Runtime metadata 已有稳定 index/dump，覆盖 Signature、runtime annotations、record、nest/inner、bridge/synthetic/record generated flags 和 class init/class object handle。Static reflection 可解析 class literal、常量 Class.forName、常量 getDeclaredMethod/Field/Constructor，并把反射 target 接入 call graph reachability；动态字符串/参数保持 fallback。
- JNI 第一层已有 descriptor -> JNI C type、reference/local-frame/pending-exception policy、RegisterNatives table、JNI_OnLoad/bootstrap wrapper plan 和 symbol allowlist。当前 JVM-hosted vertical slice 已能生成 JNI C skeleton、managed Zig `build.zig` workspace、当前 host 动态库、generated loader、SHA-256 校验、`System.load` 和 `JNI_OnLoad` / `RegisterNatives` runtime binding，覆盖 `LLVM_NATIVE_PATH` 的 static/instance primitive scalar add/long/double/boolean compare/void no-op、if/else、nested if、block-parameter/phi merge、static/instance/volatile field helper path、monitor/synchronized helper path、explicit `athrow` -> JNI `Throw` bridge、static reflection method/constructor/field helper path、Unsafe `objectFieldOffset` token / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` / `allocateInstance` helper path、typed-int VarHandle helper path、div/rem ArithmeticException helper、String/reference field pass-through、broad primitive/reference array helper subset、selected primitive/reference array allocation helpers、ordinary-method object construction helper subset、`checkcast` / `instanceof` helper subset、String `length` / `equals` helpers、StringConcatFactory `makeConcat` / common `makeConcatWithConstants` helper path、LambdaMetafactory common `metafactory` helper path、LDC MethodHandle + `invokeExact` direct path、Math `abs/min/max` int/long helper subset、same-class selected static/private-special direct call 和 no-arg `int` virtual/interface dispatch helper，以及 `TEMPLATE_JNI_PATH` / helper path 的 String content JNI path、`int[]` copy/new-array API、`ThrowNew` exception bridge smoke path、generic straight-line/simple-branch constructor/class-initializer body helper first layer；更广泛 helper implementation 仍是 skeleton/fallback 边界。
- Native build 的正式路径是 managed Zig `0.15.2` 统一接管 `.ll`、Zig-managed `.o`、JNI wrapper C、runtime helper C 和 fallback blob carrier sources。Zig 安装目录固定为可执行 `j2ll.jar` 同级的 `zig/zig(.exe)`；缺失时先使用同目录已有官方 Zig archive，仍缺失再从 Zig 官方 download path 下载，并将官方 archive 根目录内容规范化到 `zig/`。Java 侧只生成一个 `build.zig` workspace 并执行 managed `zig build`；不要新增 host `cc` / `clang` / `zig cc` ad-hoc path。
- invokedynamic base 已支持 StringConcatFactory `makeConcat` 到真实 StringBuilder helper E2E；`makeConcatWithConstants` 常见 recipe 已通过 tokenized native constant carrier + StringBuilder helper E2E。LambdaMetafactory `metafactory` common path 已通过 JVM `LambdaMetafactory` helper E2E；`altMetafactory` common flags、复杂 MethodHandle chain 或 unsupported ConstantDynamic 仍按 skeleton/fallback 边界处理。
- Unsafe/VarHandle bounded subset 已 helper-backed lowering，并对 volatile/CAS 发 JMM marker；当前真实 E2E 覆盖 statically resolved field-token `Unsafe` int access/CAS、`allocateInstance` 和 typed-int VarHandle get/set/volatile/CAS，offset/token 不表示 native object address；unsupported memory access API 走明确 fallback 诊断。
- catch-all/finally 已支持简单 rethrow 和 normal/exceptional cleanup shape；multi-exit/state-merge 仍精确 `frontendSkipped`。
- Runtime metadata/static reflection/JNI ABI/Unsafe/helper facts 已进入主线 reports：runtime metadata dump 写入 `intermediates/runtime/runtime-metadata.json`，lowering report 的 `helperBackedSites` 标注 `RUNTIME_METADATA_HELPER`、`REFLECTION_HELPER`、`UNSAFE_HELPER`、`VARHANDLE_HELPER`、`JNI_ABI_REGISTER_NATIVES`、`FIELD_HELPER`、`ARRAY_HELPER`、`ARRAYCOPY_HELPER`、`ALLOCATION_HELPER`、`TYPE_HELPER`、`STRING_HELPER`、`STRING_BUILDER_HELPER`、`STRING_CONCAT_CONSTANTS_HELPER`、`LAMBDA_METAFACTORY_HELPER`、`JDK_INTRINSIC_HELPER`、`JMM_FENCE`、`MONITOR_HELPER`、`SYNCHRONIZED_METHOD_HELPER`、`EXCEPTION_HELPER`、`DIV_REM_EXCEPTION_HELPER`、`DIRECT_LLVM_CALL`、`JVM_CALL_HELPER`、`DEFERRED_DISPATCH_HELPER`、`CONSTRUCTOR_CALL_HELPER`、`CONSTRUCTOR_BODY_HELPER` 和 `CLASS_INITIALIZER_BODY_HELPER`。Packaging report 的 fallback blob 记录 `FALLBACK_DEFINE_CLASS` / 后续 `FALLBACK_HIDDEN_CLASS` definition mechanism reason code；target preflight 通过 diagnostics/report 记录 `ZIG_TARGET_PREFLIGHT`。
- `nativeEmbeddedClassBlob` fallback 目前有稳定 planner/report 和最小真实路径：helper class bytes 不作为 JAR 明文 `.class` entry 输出，而是以 v1 `j2ll-rle-byte-pairs-v1` + `xor-sha256-key-stream-v1` encoded blob 编入 host native artifact，由 JNI 在 native-side SHA-256 校验后解码，再用 owner classloader `DefineClass` lazy define/reuse 并调用。当前 definition mechanism reason code 是 `FALLBACK_DEFINE_CLASS`；hidden-class path 和任意 fallback body 仍待扩展。
- 最小 JVM-hosted E2E 当前已接实当前 host target vertical slice：original input JAR 与 output JAR 都在 child JVM 中运行，output JAR 内嵌 managed Zig build 产出的当前 host 动态库，generated loader 真实 `System.load`，`JNI_OnLoad` 执行 `RegisterNatives`。E2E 覆盖 `LLVM_NATIVE_PATH` 的 static primitive scalar add/long/double/boolean compare/void no-op、if/else、nested if、phi merge、static/instance/volatile field helper path、monitor/synchronized helper path、explicit throw bridge、static reflection method/constructor/field helper path、Unsafe statically resolved int field token helper path、typed-int VarHandle helper path、direct static/private-special callee call、LDC MethodHandle direct `invokeExact`、div/rem ArithmeticException helper、String/reference field pass-through、broad primitive/reference array helper subset、selected primitive/reference array allocation helpers、ordinary-method object construction helper subset、`checkcast` / `instanceof` helper subset、String `length` / `equals` helpers、StringConcatFactory `makeConcat` / `makeConcatWithConstants`、LambdaMetafactory common `metafactory` helper、Math int/long helper subset 和 no-arg `int` virtual/interface dispatch helper，和 template/helper path 的 multi-class/multi-method registration、String content JNI path、primitive int array copy/new-array path、exception bridge smoke、generic straight-line/simple-branch constructor/class-initializer body helper first layer 与 encoded fallback smoke path。selected target matrix 的 build plan、layout、preflight 和 report 已稳定；非当前 host target 先进入 `selectedTargets` / `skippedTargets` 和 `ZIG_TARGET_PREFLIGHT` diagnostics，真实交叉编译成功与否由后续 managed Zig capability/SDK 接实决定。
- Protection v1 已有真实接线：IR protection 默认启用 String constant carrier encryption、primitive int/long constant XOR split、safe single-block fake branch/basic-block splitting 和 block-name obfuscation；monitor/JMM/exception/call/field/helper-sensitive shape 以及 `<init>` / `<clinit>` body helper shape 按 pass 记录 skip reason，不改变 lowering status。LLVM name obfuscation 通过共享 `LlvmNameMangler` 贯通 planner、LLVM lowerer、Zig workspace 和 JNI wrapper，生成 hidden `j2ll_f_<sha256>` linkable symbols；LLVM 混淆仍必须基于 LLVM module model，不能做 `.ll` 文本 regex。

## 维护规则

- 新增 stage、fallback 策略、validator、测试落点或目录边界时，先更新 `AGENTS.md`，再更新对应 stage guide。
- README 保持用户视角；内部 rewrite 计划和 compiler 设计只放在 `docs/`。
- 单个 stage guide 应保持 focused。跨阶段规则放在本索引、`00-overview.md` 或 `08-diagnostics-validation-testing.md`。
