# Java Support Tiers

本文档定义 rewrite 后 j2ll 对 Java / JVM 特性的预期支持等级。所有 tier 都以 JVM-hosted 输出 JAR 为前提：GC、class loading、thread scheduling、monitor、object identity 和 Java object lifetime 均由 JVM 负责。它不是一次性交付承诺，而是功能路线和测试矩阵的分层依据。新增 Java 特性时，应先确认它属于哪个 tier，再补对应 stage 和 tier 测试。

## Tier 0: Classfile / JVM Core 基座

目标：可靠读懂 `.class`，建立后续所有阶段的事实模型。

功能范围：

- classfile parse：class、field、method、descriptor、access flag、constant pool。
- method bytecode CFG：branch、switch、return、throw edge、exception handler edge。
- StackMapTable / frame facts。
- JVM type model：primitive、reference、array、void、category-1/category-2。
- 基础 verifier-like checks。
- deterministic diagnostics / dumps。

暂不要求：

- 完整 bytecode lowering。
- JDK library 语义。
- runtime execution。

测试要求：

- ASM 构造最小 class/method fixture。
- descriptor / signature parse test。
- CFG golden test。
- malformed class / malformed method diagnostic test。
- deterministic output test。

## Tier 1: 基础 Java 语言子集

目标：普通 Java 方法能从 bytecode lowering 到 SSA，再到 LLVM。

功能范围：

- 基本类型：`boolean`、`byte`、`short`、`char`、`int`、`long`、`float`、`double`。
- arithmetic / compare / conversion。
- local、field、static field。
- object allocation：`new`、constructor、`this`。
- array：primitive array、object array、multi-dimensional array。
- method call：static、special、virtual。
- constructor lowering：`<init>` 使用合法 Java stub + native body helper，不把 constructor 本身改成 native。
- class init：`<clinit>` 使用 loader/bootstrap stub + native body helper，保持 JVM class initialization ordering。
- null check、cast、`instanceof`。
- `String` 常量和基础 string concat。

暂不要求：

- 完整 generics metadata。
- 完整 exception/synchronized/thread 语义。
- 完整 JDK library native 编译。

测试要求：

- 每个 primitive 的 arithmetic / conversion parity test。
- object/field/array lowering test。
- JVM 运行结果 vs lowered/native 结果 differential test。
- null/cast/array bounds exception test。
- `<init>` / `<clinit>` ordering test。

## Tier 2: 常见 Java 语言特性

目标：用户写的普通现代 Java 代码大部分能进入 pipeline。

功能范围：

- 泛型：erasure 后运行语义、bridge method、`Signature` metadata 保留。
- lambda：`LambdaMetafactory` 常见形态。
- invokedynamic：lambda、string concat、switch bootstrap 的常见 subset。
- Interface Default Method：有 Code 的 default/static/private interface method 使用 interface method stub + generated native helper；无 Code 的 interface declaration 记录 `notApplicable`。
- enum：`values`、`valueOf`、ordinal/name、switch over enum。
- Annotation：classfile metadata 保留；运行时 annotation 可先走 JVM/runtime helper。
- record / sealed class 可作为 metadata + 普通方法处理。

bridge、synthetic、enum-generated 和 record-generated methods 默认按普通有 Code 方法处理。它们的 flags 必须进入 sidecar/report，主要用于审计和测试覆盖，不代表默认 skip。

暂不要求：

- 动态 reflection 发现任意 generic/annotation metadata。
- 完整 MethodHandle 组合语义。
- 所有 invokedynamic bootstrap。

测试要求：

- generic bridge dispatch test。
- lambda capture / non-capture test。
- default method conflict / override test。
- interface static/private method stub test。
- enum switch / `valueOf` / `values` test。
- runtime visible/invisible annotation metadata test。
- invokedynamic bootstrap whitelist test。

## Tier 3: JVM 语义完整性层

目标：语义不能静默出错，哪怕性能先保守。

功能范围：

- Exception：try/catch/finally、multi-catch、rethrow、suppressed 基础路径。
- synchronized：`monitorenter` / `monitorexit`、异常退出释放 monitor。
- Thread：先支持 JVM-hosted thread 互操作，不自建完整线程 runtime。
- Java Memory Model：volatile、final field publication、monitor happens-before 的保守实现。
- GC：由 JVM GC 管理；native-lowered code 通过 JNI reference / runtime helper 持有和传递 Java object。
- runtime guard 预留：激进优化需要 guard/fallback 表达能力时，应保持可回退到 JVM 语义。

暂不要求：

- 完整 deoptimization。
- 完整 Java Memory Model 优化。

测试要求：

- exception edge golden CFG + runtime parity。
- finally 在正常/异常路径都执行。
- synchronized 异常退出释放锁。
- volatile read/write ordering smoke test。
- 多线程 counter smoke test；wait/notify 当前是 JVM helper fallback boundary，不宣称 native monitor queue。
- JNI local/global reference lifetime test。

当前 clean-room 主线状态：

- typed catch、handler exception parameter、显式 `athrow`、implicit exception site metadata 已进入 SSA；显式 `athrow` 已有 env-backed LLVM/JNI `Throw` bridge E2E，复杂 finally/exception state merge 仍保守。
- catch-all/finally 复杂形状仍保守 `frontendSkipped`，避免漏掉异常路径语义。
- `monitorenter` / `monitorexit`、`ACC_SYNCHRONIZED` method、识别出的 synchronized exceptional cleanup、volatile read/write、final field publication、monitor happens-before、Thread.start/join happens-before 已有 IR marker 和 LLVM helper/fence golden tests；synchronized block/method 已通过 JNI `MonitorEnter` / `MonitorExit` helper path 的 child JVM E2E。Thread `start/join` common path 通过 bytecode-preserving fallback 保持 JVM scheduler semantics；`Object.wait/notify` 当前作为 `WAIT_NOTIFY_FALLBACK` boundary，不实现 native monitor queue。
- class initialization active-use skeleton 已覆盖 `getstatic` / `putstatic` / `invokestatic` / `new` guard，以及 `<clinit>` begin/end/failed helper；完整 recursive init runtime 和 classloader 并发仍是后续 runtime 工作。

## Tier 4: JDK Runtime Interop

目标：常见 Java Library 可用，通过 JVM-hosted helper、intrinsic 和 fallback 互操作。

功能范围：

- JVM-hosted JDK interop：`String`、`StringBuilder`、`ArrayList`、`HashMap`、`Arrays` narrow path、`Objects`、`Math`。
- JDK intrinsic mapping：`Math.*`、`System.arraycopy`、`Object.getClass`。
- library call policy：direct lowering / runtime helper / JVM fallback。
- partial metadata model：`Class`、`Method`、`Field` 的静态可解析子集。
- JDK class 不完整时保守 fallback。

暂不要求：

- 完整 classloader/module system。

测试要求：

- 当前 clean-room 主线已覆盖 `JdkIntrinsicRegistry` policy lookup、String/StringBuilder helper lowering、System.arraycopy helper、Math/boxing/Objects helper lowering、unsupported JDK fallback report、runtime helper declaration 和 stub generator。
- 当前 host E2E 覆盖 `String.length/equals/isEmpty/charAt/startsWith/endsWith/substring(int,int)`、显式 `StringBuilder` append chain、StringConcatFactory `makeConcat` / common `makeConcatWithConstants`、LambdaMetafactory common `metafactory` helper、LDC MethodHandle direct `invokeExact`、System.arraycopy byte/int/long/double/object/overlap/null/oob/ArrayStoreException、Integer/Long/Boolean/Double boxing-unboxing、Objects.requireNonNull/equals 和 Math int/long/float/double abs/min/max。
- `nativeEmbeddedClassBlob` fallback 已从 `String.substring(int)` smoke fixture 扩展为 ordinary `halfLowered` 方法的 bytecode-preserving helper `invoke` path；当前 child JVM E2E 覆盖 unsupported JDK call、dynamic Class.forName / dynamic getDeclaredMethod / dynamic parameter array reflection fallback、MethodHandle `bindTo` / `asType` / `dropArguments` adapter chain、`permuteArguments` / `filterArguments` reason-split fallback、collector-style `METHOD_HANDLE_COLLECTOR_UNSUPPORTED` fallback、unsupported altMetafactory two-capture serializable lambda（`ALT_METAFACTORY_FALLBACK`）、Throwable message/cause common path、Thread start/join common path、wait/notify boundary、instance receiver/reference return、fallback exception propagation 和 two-classloader isolation。完整 MethodHandle interpreter、完整 altMetafactory runtime class semantics 和复杂 lambda capture native helper 仍走 helper/fallback 边界。
- `ArrayList.add/get/size/contains`、`HashMap.put/get/containsKey/overwrite`、`Arrays.copyOf/equals/fill/asList`、`Collections.emptyList/singletonList`、`Optional.of/ofNullable/isPresent/get/orElse`、`String.format(String,Object...)`、Throwable constructor/message/cause 和 Thread constructor/start/join 当前是明确 JVM fallback policy，通过 encoded fallback 保持 JVM collection/Optional/formatter/array-library/Throwable/Thread semantics；native code 不读取 JDK collection internals，也不伪造 Java array、Throwable stack trace 或 thread scheduler。
- `System.arraycopy` primitive/object array test。
- `Math` intrinsic test。
- fallback helper declaration + runtime stub test。

## Tier 5: 静态高级特性

目标：写死在代码里的动态特性可以通过静态 classpath 分析或 runtime metadata 提前处理。

功能范围：

- Reflection 静态解析：`Class.forName("a.B")`、`getDeclaredMethod("x", ...)`。
- MethodHandle / VarHandle 常见静态形态。
- JNI：native declaration、`RegisterNatives`、JNI call helper、reference lifetime。
- invokedynamic 扩展：MethodHandle chain、constant dynamic subset。
- Unsafe subset：array base offset、field offset、CAS、`allocateInstance` 需要强边界。
- serialization / service loader 可作为后续静态 metadata 能力。

暂不要求：

- 任意动态字符串 reflection。
- 任意 classpath scanning。
- 完整 Unsafe。
- 完整 MethodHandle interpreter。

测试要求：

- static reflection metadata reachability test。
- reflective constructor/method invoke parity。
- JNI primitive/object argument ABI test。
- MethodHandle `invokeExact` common shape test。
- Unsafe CAS / field offset guarded test；offset must be asserted as a metadata token, not a native object layout offset.
- unsupported dynamic reflection diagnostic test。

当前 clean-room 主线状态：

- Runtime metadata index/dump 已覆盖 Signature、runtime visible/invisible annotations、record components、nest/inner metadata、bridge/synthetic/record-generated flags、class object handle 和 class init state handle。
- Static reflection resolver 已支持 class literal、常量 `Class.forName`、常量 `getDeclaredMethod` / `getDeclaredField` / `getDeclaredConstructor`、`Method.invoke` / `Constructor.newInstance` reachability；动态字符串/参数数组保留 fallback reason，其中 dynamic `Class.forName` ordinary method 已可通过 encoded nativeEmbeddedClassBlob fallback 保持 runnable semantics。
- Reflection first parity 已通过 child JVM E2E 覆盖常量 `Class.forName`、no-arg、reference、primitive 和 array 常量参数 descriptor 的 `getDeclaredMethod` / `getDeclaredConstructor`、`getDeclaredField`、`Method.invoke`、`Constructor.newInstance`、`Field.get` / `Field.set`、`Field.getInt` / `Field.setInt`、`Field.getBoolean` / `Field.setBoolean`、`Field.getLong` / `Field.setLong`、`Field.getDouble` / `Field.setDouble` helper path；`setAccessible(true)` 对 statically resolved Method/Constructor/Field object 走 JVM `AccessibleObject.setAccessible` helper，已覆盖 private method/constructor accessible smoke。动态 reflection 和动态参数数组可通过 encoded fallback 保持 runnable semantics；scan-style reflection (`getDeclaredMethods/getMethods/getDeclaredFields/getFields/getDeclaredConstructors/getConstructors`) 使用 `REFLECTION_UNSUPPORTED_SCAN` 边界，更动态访问控制流和剩余 typed field accessor 仍明确 fallback。
- JNI 第一层已覆盖 descriptor -> JNI C type、static/instance implicit ABI、RegisterNatives table、JNI_OnLoad/bootstrap wrapper plan、reference lifetime/local frame/pending exception policy 和 exported-symbol allowlist。
- MethodHandle/invokedynamic 已覆盖 altMetafactory common flags metadata、LDC MethodHandle + `invokeExact` direct target、ConstantDynamic `nullConstant` skeleton；MethodHandle `bindTo` / `asType` / `dropArguments` adapter chain、`permuteArguments`、`filterArguments`、`foldArguments` 和 unsupported altMetafactory capture shape 已通过可保留原 bytecode body 的 ordinary method encoded fallback path 接实，reason 分别使用 `METHOD_HANDLE_CHAIN_FALLBACK`、`METHOD_HANDLE_PERMUTE_FALLBACK`、`METHOD_HANDLE_FILTER_FALLBACK`、`METHOD_HANDLE_FOLD_FALLBACK`，collector/spreader-style unsupported adapter 使用 `METHOD_HANDLE_COLLECTOR_UNSUPPORTED` reason，复杂 chain/unsupported bootstrap 仍 `halfLowered`。
- Unsafe/VarHandle bounded subset 已 helper-backed：field/array offsets、get/put、volatile get/put、CAS、`allocateInstance`、VarHandle get/set/volatile/CAS；当前真实 child JVM E2E 覆盖 statically resolved `Field` 的 `objectFieldOffset` token、`getInt` / `putInt`、monitor-backed `compareAndSwapInt` 和 JNI `AllocObject`-backed `allocateInstance`。Unsupported raw memory API 走 `halfLowered` fallback 并报告 `UNSAFE_RAW_MEMORY_FALLBACK`；更宽 VarHandle/typed accessor matrix 继续保守 fallback。
- Release readiness additionally writes `reports/artifact-audit.json`, `reports/support-matrix.json`, `reports/opcode-support-matrix.json`, `reports/known-blockers.json`, `reports/release-readiness.json`, `reports/index.json`, `reports/summary.md` and `reports/summary.json`. The artifact audit verifies output JAR/native-resource hygiene, final JAR metadata/targetArtifacts consistency, reports manifest hash, hidden symbol export policy, native SHA-256 consistency, PDB exclusion, the ban on plaintext generated fallback classes, fallback blob binary metadata/carrier evidence and hash-only sensitive plaintext facts. `reports/index.json` v2 records report/config/intermediate paths, SHA-256, beta/RC/readiness required flags, failure-production flags and status; readiness validates required report hashes and final JAR reports-manifest consistency. Connected `LLVM_NATIVE_PATH` facts, stable TEMPLATE constructor/body helper string facts (`TEMPLATE_JNI_PATH_STABLE_SURFACE`) and StringConcat constant carrier stable generated-C facts are blocking when the literal is specific enough for stable audit; short/common literals and reflection/lambda/MethodHandle metadata facts remain observed-only. The support/opcode matrices include status (`LLVM_NATIVE_PATH` / `HELPER_BACKED` / `FALLBACK` / `FRONTEND_SKIPPED` / `NOT_APPLICABLE`), reason code, test coverage pointer, `coverageLevel` and `evidenceCount`. The blocker report includes stable ids plus severity/targetMilestone for current conservative boundaries such as `UNSAFE_RAW_MEMORY_FALLBACK`, `VAR_HANDLE_DYNAMIC_FALLBACK`, `UNSUPPORTED_MULTI_EXIT_FINALLY`, `UNSUPPORTED_EXCEPTION_STATE_MERGE`, `UNSUPPORTED_MONITOR_FINALLY_INTERACTION`, `UNSUPPORTED_NESTED_FINALLY`, `UNSUPPORTED_FINALLY_SUBROUTINE`, `ALT_METAFACTORY_FALLBACK`, `METHOD_HANDLE_CHAIN_FALLBACK`, `METHOD_HANDLE_PERMUTE_FALLBACK`, `METHOD_HANDLE_FILTER_FALLBACK`, `METHOD_HANDLE_FOLD_FALLBACK`, `METHOD_HANDLE_COLLECTOR_UNSUPPORTED`, `ZIG_TARGET_UNBUILDABLE`, `WAIT_NOTIFY_FALLBACK` and signing success `SIGNATURE_RESIGNED`; standalone/native-image and native object model/GC/thread scheduler are explicit non-goals. Release suite workspaces also write `reports/release-suite-summary.json` with profile (`smoke`/`standard`/`beta`/`rc`), required/missing categories and determinism evidence; strict readiness mode requires it and uses suite expected statuses plus report-location evidence, aggregate/determinism evidence and weird-bytecode seed coverage to prove Tier 0-5 coverage across minimal LLVM lowering, helper-backed paths, nativeEmbeddedClassBlob fallback, safe finally cleanup, signed fail/strip/resign packaging, service loader/multi-release/module preservation, reflection/MethodHandle/lambda fallback, raw Unsafe boundary, dynamic VarHandle boundary, wait/notify boundary, non-host Zig target preflight failure, artifact audit expected failure, narrow JDK fallback behavior and realistic CLI/reflection/packaging samples. Beta profile additionally requires dist CLI artifact smoke, docs example validation, report-index evidence and beta blocker evidence; uncovered beta blockers fail beta readiness, while future and non-goal rows remain visible and non-blocking. RC profile requires `missingCategories=[]` and beta/rc blockers require release-suite evidence. `release-readiness.json` records `missingEvidence` plus readiness fields `suiteCoverageByBlocker`, `blockerEvidenceComplete`, `targetEvidenceComplete`, `finalArtifactWritten`, `determinismEvidenceComplete`, `metadataConsistencyPassed`, `blockingSensitiveFactsPassed`, `targetPackagePlanComplete`, `betaProfilePassed`, `betaMissingEvidence`, `cliArtifactSmokePassed`, `docsExamplesValidated` and `strictModePassed`; primary reports write both `schemaVersion` and `reportVersion`, and protection/config reports write only seed hashes.

## 使用方式

- 任何新功能先标注所属 tier。
- 一个 tier 的功能只有在其核心测试稳定后，才可以在 README 或 release note 中宣称支持。
- 如果某个特性跨 tier，按最高风险部分归类。例如 lambda 的常见 lowering 属于 Tier 2，但复杂 MethodHandle chain 属于 Tier 5。
- 遇到不确定语义时优先 conservative fallback，不静默生成可能错误的 native code。
