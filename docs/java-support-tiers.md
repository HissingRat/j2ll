# Java Support Tiers

本文档定义 rewrite 后 j2ll 对 Java/JVM 特性的支持等级。所有 tier 都以 Java 17 或更新版本上的 JVM-hosted 输出 JAR 为前提：GC、class loading、thread scheduling、monitor、object identity 和 Java object lifetime 均由 JVM 负责。唯一 `<embeddedLibraryDirectory>/Loader.class` 固定为 Java 17 classfile。

Tier 是 compiler-development 与 release-evidence 分类，不是用户 Config 选项。selector 命中的 Code-bearing method最终只有：

- `nativeLowered`：由 LLVM、生成式 template/stub 或经过验证的 JNI/runtime helper-backed native implementation完成。
- `skipped`：保留原 Java method/classfile 形态，不生成 native body，不进入 `RegisterNatives`。

`excluded` 只描述 selector 外方法；pipeline/toolchain/packaging failure 是 build status。Schema v1 不增加 `requiredNative`，也不在 native artifact 中保存 selected method 的 bytecode副本。

## Tier 0: Classfile / JVM Core 基座

目标：可靠读懂 `.class`，建立后续所有阶段的事实模型。

功能范围：

- classfile parse：class、field、method、descriptor、access flag、constant pool。
- method bytecode CFG：branch、switch、return、throw edge、exception handler edge。
- StackMapTable/frame facts。
- JVM type model：primitive、reference、array、void、category-1/category-2。
- verifier-like checks 与 deterministic diagnostics/dumps。

测试要求：

- ASM 构造最小 class/method fixture。
- descriptor/signature parse、CFG golden、malformed input diagnostic。
- deterministic output。

Tier 0 尚未承诺 executable native lowering，但 selector audit仍不能丢掉命中的 declaration。

## Tier 1: 基础 Java 语言子集

目标：普通 Java 方法能从 bytecode lower 到 SSA，再到 LLVM或生成式 native helper。

功能范围：

- `boolean/byte/short/char/int/long/float/double` arithmetic、compare、conversion。
- local、field、static field。
- JVM-managed object/array allocation、constructor、`this`。
- static/special/virtual call。
- `<init>` 合法 Java stub + native body helper。
- `<clinit>` loader/bootstrap stub + native body helper。
- null check、cast、`instanceof`、String constant/concat。

测试要求：

- primitive arithmetic/conversion parity。
- object/field/array、null/cast/bounds、constructor/class-init ordering。
- original JVM 与 output JAR child-JVM differential。

有 Code 的 method 只有在全部用户语义都由 native implementation承担时才是 `nativeLowered`；否则整个 method 为 `skipped`。

## Tier 2: 常见 Java 语言特性

目标：用户写的普通现代 Java 代码大部分能进入 native pipeline。

功能范围：

- generics erasure、bridge method 与 `Signature` metadata保留。
- LambdaMetafactory 常见 capture/non-capture shape。
- string-concat 与常见 invokedynamic bootstrap。
- 有 Code 的 default/static/private interface method使用合法 stub + generated native helper。
- enum、annotation metadata、record/sealed metadata与普通方法。

Bridge、synthetic、enum-generated 和 record-generated methods默认按普通有 Code method处理；flags只进入 audit/report，不导致 skip。selector 命中的 abstract、already-native、无 Code interface declaration或 annotation element只记录 eligibility evidence，无 method status且不触发 confirmation。

测试要求：

- bridge/default/interface conflict/override。
- lambda capture/non-capture、enum、annotation/record metadata。
- invokedynamic bootstrap allowlist与 unsupported caller preservation。

## Tier 3: JVM 语义完整性层

目标：exception、monitor、thread、JMM 和 GC语义不能静默出错。

功能范围：

- try/catch/finally、multi-catch、rethrow与受限 cleanup shape。
- `monitorenter`/`monitorexit`、`ACC_SYNCHRONIZED` 和异常退出释放。
- JVM-hosted Thread互操作，不实现 native scheduler。
- volatile、final publication、monitor happens-before的保守 marker/helper。
- JNI local/global reference lifetime。
- 激进优化未来需要的 guard/slow-path；slow path必须是显式 JVM/JNI helper语义，不能重放 selected caller bytecode。

当前边界：

- typed catch、handler parameter、显式 `athrow` 与 implicit exception-site metadata已进入 SSA/LLVM helper path。
- 复杂 exception state merge、multi-exit/nested finally、monitor-finally interaction在未实现前将整个 selected method标记为 `skipped`。
- synchronized method/block已有 JNI monitor E2E；尚未接入真实 helper matrix的 Thread/wait-notify shape统一 `skipped`，不伪造 native scheduler或 monitor queue。

测试要求：

- exception/finally/synchronized runtime parity。
- volatile/multithread smoke。
- skipped method原 body保留、无 registration/native bytecode copy。

## Tier 4: JDK Runtime Interop

目标：常见 Java library通过 JVM-hosted intrinsic、runtime helper与普通 JNI/JVM dispatch可用。

功能范围：

- String、StringBuilder、ArrayList、HashMap、Arrays、Collections、Optional、Objects、Math。
- `System.arraycopy`、`Object.getClass` 等 intrinsic/helper。
- direct lowering / runtime helper / validated JVM dispatch / skipped policy。
- Class/Method/Field 的静态可解析 metadata subset。

已接实 subset：

- String/StringBuilder/StringConcat、System.arraycopy、Math、boxing、Objects。
- common LambdaMetafactory、LDC MethodHandle direct target 与受限 adapter dispatch。
- validated descriptor matrix内的 collection/formatter/Throwable/Thread/JDK calls可从 native implementation经 JNI dispatch执行。

边界：

- native code不读取 JDK collection/Throwable/Thread object layout，不伪造 Java array、Throwable stack trace 或 thread scheduler。
- 超出 validated helper/dispatch matrix时，整个 selected caller为 `skipped`；原 method body留在原 class，不另存第二份。
- 完整 MethodHandle interpreter、完整 altMetafactory semantics和复杂 lambda capture仍是后续 native support任务。

Runtime Loader 只承载 native library选择、SHA-256校验、加载与注册；field internalization实际包含 reference/array slot时才按需加入 per-defining-Class `ClassValue<Object[]>` sidecar。Loader没有 class-definition或 embedded-bytecode decode API，也不生成 companion/nested runtime class。

测试要求：

- intrinsic/helper/dispatch runtime parity。
- unsupported JDK caller skipped reason与 preservation。
- Loader最小 API surface和双 ClassLoader isolation。

## Tier 5: 静态高级特性

目标：写死在代码里的动态特性可以通过静态 classpath analysis或 runtime metadata提前处理。

功能范围：

- 常量 reflection目标、method/constructor/field invoke。
- MethodHandle/VarHandle常见静态形态。
- JNI declaration、`RegisterNatives`、reference lifetime。
- ConstantDynamic与 invokedynamic扩展 subset。
- Unsafe field/array token、CAS、`allocateInstance` 的严格 helper边界。

当前状态：

- Runtime metadata index/dump覆盖 Signature、annotations、record、nest/inner、bridge/synthetic、class object/init facts。
- 常量 reflection与 bounded `setAccessible(true)`已有 helper E2E；动态 reflection和 scan APIs只在 validated descriptor bridge内保持 `nativeLowered`。
- MethodHandle common metadata/direct/adapter shape可走 native helper；unsupported bootstrap/adapter/capture shape的 selected caller为 `skipped`。
- Unsafe/VarHandle bounded subset使用 metadata token和 JNI helper，不使用 native object address；raw-memory或更宽动态 shape为 `skipped`。

Release evidence：

- support/opcode matrix用 `NATIVE_LOWERED`、`HELPER_BACKED` 与 `SKIPPED` evidence；前两者都映射到 method outcome `nativeLowered`。
- `reports/support-matrix.json` 与 `reports/opcode-support-matrix.json` 保存上述 machine-readable coverage；签名成功证据使用 `SIGNATURE_RESIGNED`。
- artifact audit校验 native implementation/registration closure、skipped-body preservation、Loader API surface、metadata/hash/export/PDB、sensitive plaintext，以及 generated C/native/JAR没有 selected method bytecode副本。
- release suite覆盖 native/helper paths、精确 skipped reasons、skipped confirmation的 Y/N/EOF、签名/target/audit failures与 realistic samples。
- 六目标结构性交叉产物与 non-host OS/JVM runtime E2E分开记录。

## Skipped-Method Build Gate

Default build在 final implementation plan确定后、创建 Zig workspace或调用 Zig前：

1. stderr按稳定顺序逐条打印 skipped method identity、reason code和 reason。
2. warning明确说明这些方法不会 native lowered，原 Java bytecode会保留在输出 JAR。
3. 提示 `continue? (Y/N)`；只有显式 `Y`继续，`N`或 EOF终止。

Piped `Y`是正式自动化入口，非 TTY/CI不能绕过。`--validate`与`--dry-run`不读取 stdin，也不形成 final skipped set；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。

当前仍需保持稳定、用于定位待实现 native coverage 的 reason code包括：

- exception/interface：`UNSUPPORTED_DEFAULT_INTERFACE_SUPER`、`UNSUPPORTED_MULTI_EXIT_FINALLY`、`UNSUPPORTED_EXCEPTION_STATE_MERGE`、`UNSUPPORTED_MONITOR_FINALLY_INTERACTION`；
- reflection/Unsafe：`REFLECTION_DYNAMIC_UNSUPPORTED`、`UNSAFE_RAW_MEMORY_UNSUPPORTED`、`VAR_HANDLE_DYNAMIC_UNSUPPORTED`；
- MethodHandle/lambda：`METHOD_HANDLE_PERMUTE_UNSUPPORTED`、`METHOD_HANDLE_FILTER_UNSUPPORTED`、`METHOD_HANDLE_FOLD_UNSUPPORTED`、`METHOD_HANDLE_COLLECTOR_UNSUPPORTED`、`ALT_METAFACTORY_UNSUPPORTED`；
- JVM/JDK boundaries：`JVM_HELPER_UNSUPPORTED`、`THREAD_HELPER_UNSUPPORTED`、`WAIT_NOTIFY_UNSUPPORTED`；
- toolchain：`ZIG_TARGET_UNBUILDABLE`。

这些 reason code只描述尚未实现的 native coverage；对应 selected caller的最终状态一律是 `skipped`，不表示存在第二种执行实现。

## 使用方式

- 新功能先标注所属 tier，并同时定义 direct/helper/native plan、skipped reason和测试落点。
- 一个 tier 的功能只有核心测试稳定后，才可在 README/release note宣称支持。
- 跨 tier功能按最高风险部分归类。
- 遇到不确定语义时把整个 selected method标记为 `skipped`，保留原 Java body并给稳定 reason。
- 后续工作的主线是按 reason code逐项扩大 native/helper coverage，持续减少 skipped methods。
