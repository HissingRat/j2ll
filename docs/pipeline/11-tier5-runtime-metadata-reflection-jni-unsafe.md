# Tier 5 Runtime Metadata / Reflection / JNI / Unsafe

本 guide 描述 JVM-hosted Tier 5 动态 metadata 层。当前实现以已接实的 JVM/JNI helper 和明确的 `skipped` boundary 为边界，所有 Java object、Class、String、Throwable 和 array 都通过 JVM/JNI 语义访问。

## Runtime Metadata

包：`xyz.melodysky.runtime.metadata`

- `RuntimeMetadataIndexBuilder` 从 `ParsedProgram` 构建稳定 metadata index。
- Metadata 保留 class/member flags、Signature、runtime visible/invisible annotations、record components、nest/inner class metadata、bridge/synthetic/record-generated flags。
- `ClassInitMetadata` 记录 class object handle 和 class init state handle，供 class init skeleton、reflection 和 JNI 共享。
- `RuntimeMetadataDumpWriter` 输出稳定 dump；启用 static reflection plan 时可带 `reflectionReachability`。

## Static Reflection

包：`xyz.melodysky.analysis.reflection`

- 支持 `Foo.class`、常量 `Class.forName`、常量 `getDeclaredMethod` / `getDeclaredField` / `getDeclaredConstructor`。当前 helper path 覆盖 no-arg、reference、primitive `TYPE` field 和 array class literal 组成的常量参数 descriptor；native helper 使用 JVM `MethodType.fromMethodDescriptorString(...).parameterArray()` 构造参数 `Class[]`。
- `Method.invoke` / `Constructor.newInstance` 在 receiver member 可静态解析时进入 reachability。
- 动态 `Class.forName`、动态 member name/parameter array 和 scan-style reflection 普通调用在 descriptor 可通过 JNI bridge 表达时走 JVM dispatch bridge，并以 `DEFERRED_DISPATCH_HELPER` / `JVM_CALL_HELPER` 保留 JVM 反射语义；这种真实 JVM/JNI helper-backed native body 仍记为 `nativeLowered`。超出 bridge descriptor 或无法安全表达 owner/runtime context 的形态必须让完整 method 记录为 `skipped`，不允许静默忽略。`setAccessible(true)` 当前有 bounded helper path：对 statically resolved `Method` / `Constructor` / `Field` object 调用 JVM `AccessibleObject.setAccessible`，并由 JVM 保留访问控制/security exception 语义；更动态的 accessible object flow 当前为 `skipped`。
- `CallSiteCollector` 会把已解析 reflection target 加入 synthetic call-site，供 CHA/RTA 消费。
- `BytecodeToSsaLowerer` 将可证明静态 reflection API lower 到 runtime metadata helper；未知 reflection 如果没有完整 bridge implementation，则把完整 method 标记为 `skipped` 并保留原 Code。

## JNI ABI First Layer

包：`xyz.melodysky.runtime.jni`、`xyz.melodysky.packaging`

- `JniTypeMapper` 实现 JVM descriptor 到 JNI C type 映射。
- `JniMethodDescriptor` 记录 static/instance implicit `JNIEnv*` + `jclass` / `jobject` ABI。
- `JniReferencePolicy`、`JniLocalFramePlan` 和 pending exception policy 记录引用生命周期和异常传播策略。
- `HostNativeRegistrationSource` 只消费已冻结的 `NativeRegistrationControlTopologyPlan`；owner table 在activation内构造，control symbol 使用build-scoped hash-only名称，不再生成稳定bootstrap/table前缀。
- Symbol allowlist 只允许 `JNI_OnLoad` 和平台必需runtime symbol；aggregate、route、chunk、owner helper与Java method implementation 都不得导出。
- `RuntimeHelperCatalog` 的 signature 是 helper ABI 的单一来源：Java reference token 使用 `jobject`、`jclass`、`jarray`、`jthrowable` 等 JNI handle；LLVM declaration 映射为 opaque `ptr`，generated runtime C 显式接收 `JNIEnv* env` 并执行已验证的local-reference / pending-exception策略。
- Pipeline lowering report 会为 native registration 写入 `JNI_ABI_REGISTER_NATIVES` helper-backed fact，避免 JNI ABI 决策只存在于 packaging 内部。virtual/interface dispatch helper 当前只覆盖 tokenized no-arg int、int-arg int、reference return 和 single-reference-argument/reference-return subset，并通过 JNI `GetObjectClass` / `GetMethodID` / `Call<Type>Method` 执行 JVM dispatch；native runtime 不实现 vtable 或 object layout。

## MethodHandle / ConstantDynamic

- `LambdaMetafactoryBootstrap` 支持 `metafactory` 和 `altMetafactory` common flags：serializable、marker interfaces、bridge method descriptors。当前真实 runtime path 覆盖 `metafactory` common shape，经 `j2ll_rt_lambda_new` 调用 JVM `LambdaMetafactory` / `MethodHandle.invokeWithArguments` 生成 lambda object；unsupported `altMetafactory` two-capture serializable lambda 使完整 method `skipped`，不声明 native-side altMetafactory interpreter。
- LDC MethodHandle + `MethodHandle.invokeExact` direct shape lower 为 direct call target，并进入 call graph reachability；当前 child JVM E2E 覆盖 direct static target。MethodHandle `bindTo` / `asType` / `dropArguments` / `permuteArguments` / `filterArguments` / `foldArguments` / collector-style common adapter chain 只有在 JVM `MethodHandle.invokeWithArguments` bridge 已完整接实后才是 `nativeLowered`；不复制 signature-polymorphic 字节码，也不假装存在 generic native MethodHandle interpreter。
- `MethodHandles.privateLookupIn`、`Lookup.defineHiddenClass` 与 `Lookup.lookupClass` 的精确descriptor可通过tokenized JVM dispatch bridge执行；class bytes仍由业务method在运行时产生并交给JVM。该支持不会向唯一Loader加入hidden/generated class API，也不会在native/JAR中保存selected method bytecode副本。
- `ConstantDynamic` 当前支持 `ConstantBootstraps.nullConstant` 的完整 helper-backed path；其他 bootstrap 使完整 method `skipped`。

## Unsafe / VarHandle

包：`xyz.melodysky.runtime.unsafe`

当前真实 VarHandle path 覆盖 typed-int instance-field `get` / `set` / `getVolatile` / `setVolatile` / `compareAndSet`，native helper 通过 JVM `VarHandle.toMethodHandle(AccessMode)` 和 `MethodHandle.invokeWithArguments(Object[])` 调用，不把 field offset 解释成 native memory address。array view、byte order、coordinate transforms 和 raw memory shape 仍是 `skipped` 边界。

- `UnsafePolicy` 声明 supported subset：field/array offsets、primitive/object get/put、volatile get/put、CAS、`allocateInstance`。
- VarHandle common shapes：get/set、getVolatile/setVolatile、compareAndSet。
- 当前真实 JVM-hosted slice 覆盖 statically resolved `Field` 的 `objectFieldOffset` / `staticFieldOffset`、`getInt`、`putInt`、`compareAndSwapInt` / `compareAndSetInt` 和 `allocateInstance`：offset 是 runtime metadata 产生的 deterministic token，不是 JVM object memory offset；native helper 通过 JNI `GetFieldID` / `GetIntField` / `SetIntField` / `MonitorEnter` / `MonitorExit` 和 `AllocObject` 执行，不做 raw pointer arithmetic。
- Array base/index scale、object/long/volatile access、VarHandle common shapes 仍是 helper-shaped bounded subset；超出当前真实 JNI implementation 的 shape 必须把完整 method 标记为 `skipped`。
- Volatile/CAS 必须保留 JMM marker through optimizer/backend。
- Unsupported raw/off-heap memory API 必须 diagnostic + `skipped`；`allocateMemory/freeMemory/reallocateMemory/getLong(long)/putLong(long,long)/copyMemory/park/unpark` 等不进入 field-offset helper path。

## Pipeline Reports And Skipped Boundaries

- `RuntimeMetadataIndexBuilder` 和 `StaticReflectionResolver` 已接入主线 pipeline；runtime metadata dump 写入 `intermediates/runtime/runtime-metadata.json`，并包含 `reflectionReachability`。
- Lowering report 的 `helperBackedSites` 会用稳定 reason code 标注 runtime metadata、reflection、Unsafe、Throwable、Thread、monitor/exception、dispatch helper 和 JNI ABI registration facts。最终方法状态仍只由完整 implementation 决定：成功是 `nativeLowered`，缺口是 `skipped`。
- Dynamic reflection 和 scan-style reflection (`getDeclaredMethods/getMethods/getDeclaredFields/getFields/getDeclaredConstructors/getConstructors`) 的普通 JVM calls 在 supported descriptor matrix 内通过 dispatch bridge 保持语义；MethodHandle common adapter chain 通过 JVM bridge 保持 runnable semantics。unsupported ConstantDynamic、raw memory API、unsupported altMetafactory/lambda shape 和更复杂 dispatch shape 以具体 reason 将完整 method 记录为 `skipped`，不允许 silent skip。
- `reports/skipped-method-report.json` 逐项记录 owner/name/descriptor、reasonCode 和 reason。默认 build 在 final plan 后、任何 Zig workspace/invocation 前把同一列表打印到 terminal 并询问 `continue? (Y/N)`；只有显式 `Y` 继续，`N`/EOF 终止。`--validate`/`--dry-run` 不读 stdin，也不形成 final skipped set；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 与 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。
- skipped method 的原 Code 只保留在原 owner class 中。pipeline 不复制、编码或嵌入可执行 class/method bytecode，也不生成 carrier、decoder、hidden-class definition 或运行时 class definition cache。

## Minimal JVM-Hosted E2E

- 测试 harness 已能运行 original input JAR 和 output JAR 的 child JVM differential。
- 当前 host E2E 已真实经过 managed Zig `build.zig` dynamic library、唯一 `<embeddedLibraryDirectory>/Loader.class`、SHA-256 校验、`System.load`、`JNI_OnLoad` 和 `RegisterNatives`。`embeddedLibraryDirectory` 必须是规范 Java internal package path；不同产物若在同一 defining `ClassLoader` 中复用同一目录，会请求同名 Loader，属于明确已知边界，建议使用应用唯一目录。
- Tier 5 first parity 覆盖常量 `Class.forName`、no-arg、reference、primitive 和 array 常量参数 descriptor 的 `getDeclaredMethod` / `getDeclaredConstructor`、`getDeclaredField`、`Method.invoke`、`Constructor.newInstance`、reference `Field.get` / `Field.set`、typed `Field.getInt` / `Field.setInt` / `Field.getBoolean` / `Field.setBoolean` / `Field.getLong` / `Field.setLong` / `Field.getDouble` / `Field.setDouble` 和 bounded `setAccessible(true)` 的 JVM reflection helper path。动态字符串/动态参数数组和 scan-style reflection 普通调用通过 JNI dispatch bridge 保持语义；更动态访问控制流和超出 bridge descriptor 的形态是明确 `skipped` boundary，并必须有 reason/report。
- Unsafe first parity 覆盖 selected ordinary method 中的 `objectFieldOffset` token lookup、`getInt` / `putInt` field access、monitor-backed `compareAndSwapInt` smoke path 和 `allocateInstance` JNI `AllocObject` path。当前 token lookup 依赖静态解析到 runtime metadata 的 `Field`；任意动态 `Field`、raw address memory API、off-heap memory 和更宽 typed accessor matrix 仍是 `skipped`/unimplemented boundary。
- Release-readiness support facts are also summarized in `reports/support-matrix.json`, including Tier 5 rows for static reflection helper coverage and raw Unsafe、dynamic VarHandle、MethodHandle adapter，以及无法形成一致throw-site frame/block arguments的exception边界。Strict release readiness requires blocker reasons to map to release suite cases or weird-bytecode seeds。Structural cross-build success for all six fixed targets is separate from the still-pending non-host runtime E2E evidence。

## Remaining Conservative Boundaries

- 动态 reflection 字符串、动态参数数组、reflection scan 普通调用和 MethodHandle common adapter chain 只要通过已实现 JNI/JVM bridge 完整保持语义，就仍是 `nativeLowered`。unsupported ConstantDynamic bootstrap、Unsafe raw memory API、超出 bridge descriptor 的 reflection shape、完整 MethodHandle interpreter 语义、更复杂 dispatch shape、default-interface conflict/diamond 和 `I.super.m()` 当前都使完整 method `skipped`。
- Protected JNI/runtime helper exception flow不再按“是否出现finally”一刀切：SSA能携带throwable与throw-site locals、LLVM能按序完成typed/catch-all dispatch和unmatched rethrow时继续`nativeLowered`。只有frame/block-argument merge失败、不可约exception state或复杂monitor/finally interaction等无法保持语义的shape才`skipped`，并使用精确reason code。
