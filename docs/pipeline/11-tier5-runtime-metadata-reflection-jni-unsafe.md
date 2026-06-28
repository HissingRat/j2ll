# Tier 5 Runtime Metadata / Reflection / JNI / Unsafe

本 guide 描述 JVM-hosted Tier 5 动态 metadata 层。当前实现以 helper-backed skeleton 和 conservative reachability 为边界，所有 Java object、Class、String、Throwable 和 array 都通过 JVM/JNI 语义访问。

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
- 动态字符串、动态参数数组或未解析 target 必须产生 fallback reason，不允许静默 skip。`setAccessible(true)` 当前有 bounded helper path：对 statically resolved `Method` / `Constructor` / `Field` object 调用 JVM `AccessibleObject.setAccessible`，并由 JVM 保留访问控制/security exception 语义；更动态的 accessible object flow 继续 fallback。
- `CallSiteCollector` 会把已解析 reflection target 加入 synthetic call-site，供 CHA/RTA 消费。
- `BytecodeToSsaLowerer` 将可证明静态 reflection API lower 到 runtime metadata helper；未知 reflection 保持 `halfLowered` + `JVM_HELPER_FALLBACK`。

## JNI ABI First Layer

包：`xyz.melodysky.runtime.jni`、`xyz.melodysky.packaging`

- `JniTypeMapper` 实现 JVM descriptor 到 JNI C type 映射。
- `JniMethodDescriptor` 记录 static/instance implicit `JNIEnv*` + `jclass` / `jobject` ABI。
- `JniReferencePolicy`、`JniLocalFramePlan` 和 pending exception policy 记录引用生命周期和异常传播策略。
- `RegisterNativesTableBuilder`、`JniOnLoadPlanner`、`BootstrapWrapperPlanner` 生成稳定 registration/bootstrap plan。
- Symbol allowlist 只允许 `JNI_OnLoad`、aggregate/register/bootstrap wrappers；Java method internal symbol 默认不能导出。
- `RuntimeHelperCatalog` 的 signature 是 helper ABI 的单一来源：Java reference token 使用 `jobject`、`jclass`、`jarray`、`jthrowable` 等 JNI handle；LLVM declaration 映射为 opaque `ptr`，C header/skeleton 显式接收 `JNIEnv* env`，并保留 local frame / pending exception policy TODO。
- Pipeline lowering report 会为 native registration 写入 `JNI_ABI_REGISTER_NATIVES` helper-backed fact，避免 JNI ABI 决策只存在于 packaging 内部。virtual/interface dispatch helper 当前只覆盖 tokenized no-arg int、int-arg int、reference return 和 single-reference-argument/reference-return subset，并通过 JNI `GetObjectClass` / `GetMethodID` / `Call<Type>Method` 执行 JVM dispatch；native runtime 不实现 vtable 或 object layout。

## MethodHandle / ConstantDynamic

- `LambdaMetafactoryBootstrap` 支持 `metafactory` 和 `altMetafactory` common flags：serializable、marker interfaces、bridge method descriptors。当前真实 runtime path 覆盖 `metafactory` common shape，经 `j2ll_rt_lambda_new` 调用 JVM `LambdaMetafactory` / `MethodHandle.invokeWithArguments` 生成 lambda object；unsupported `altMetafactory` two-capture serializable lambda 已通过 bytecode-preserving fallback E2E，不声明 native-side altMetafactory interpreter。
- LDC MethodHandle + `MethodHandle.invokeExact` direct shape lower 为 direct call target，并进入 call graph reachability；当前 child JVM E2E 覆盖 direct static target。MethodHandle `bindTo` / `asType` / `dropArguments` adapter chain 通过 enclosing ordinary method 的 encoded bytecode fallback 保持 JVM 语义并报告 `METHOD_HANDLE_CHAIN_FALLBACK`；`permuteArguments`、`filterArguments`、`foldArguments` 和 collector/spreader-style adapter boundary 分别报告 `METHOD_HANDLE_PERMUTE_FALLBACK`、`METHOD_HANDLE_FILTER_FALLBACK`、`METHOD_HANDLE_FOLD_FALLBACK`、`METHOD_HANDLE_COLLECTOR_UNSUPPORTED` 并 fallback，不实现 generic MethodHandle interpreter。
- `ConstantDynamic` 当前支持 `ConstantBootstraps.nullConstant` helper-backed skeleton；其他 bootstrap 明确 `halfLowered` fallback。

## Unsafe / VarHandle

包：`xyz.melodysky.runtime.unsafe`

当前真实 VarHandle path 覆盖 typed-int instance-field `get` / `set` / `getVolatile` / `setVolatile` / `compareAndSet`，native helper 通过 JVM `VarHandle.toMethodHandle(AccessMode)` 和 `MethodHandle.invokeWithArguments(Object[])` 调用，不把 field offset 解释成 native memory address。array view、byte order、coordinate transforms 和 raw memory shape 仍是 fallback 边界。

- `UnsafePolicy` 声明 supported subset：field/array offsets、primitive/object get/put、volatile get/put、CAS、`allocateInstance`。
- VarHandle common shapes：get/set、getVolatile/setVolatile、compareAndSet。
- 当前真实 JVM-hosted slice 覆盖 statically resolved `Field` 的 `objectFieldOffset` / `staticFieldOffset`、`getInt`、`putInt`、`compareAndSwapInt` / `compareAndSetInt` 和 `allocateInstance`：offset 是 runtime metadata 产生的 deterministic token，不是 JVM object memory offset；native helper 通过 JNI `GetFieldID` / `GetIntField` / `SetIntField` / `MonitorEnter` / `MonitorExit` 和 `AllocObject` 执行，不做 raw pointer arithmetic。
- Array base/index scale、object/long/volatile access、VarHandle common shapes 仍是 helper-shaped bounded subset；超出当前真实 JNI implementation 的 shape 必须保守 fallback 或保留明确 skeleton 边界。
- Volatile/CAS 必须保留 JMM marker through optimizer/backend。
- Unsupported raw/off-heap memory API 必须 diagnostic + `halfLowered` fallback 或更保守 skip；`allocateMemory/freeMemory/reallocateMemory/getLong(long)/putLong(long,long)/copyMemory/park/unpark` 等明确使用 `UNSAFE_RAW_MEMORY_FALLBACK`，不进入 field-offset helper path。

## Pipeline Reports And Fallback Blob Skeleton

- `RuntimeMetadataIndexBuilder` 和 `StaticReflectionResolver` 已接入主线 pipeline；runtime metadata dump 写入 `intermediates/runtime/runtime-metadata.json`，并包含 `reflectionReachability`。
- Lowering report 的 `helperBackedSites` 会用稳定 reason code 标注 runtime metadata helper、`REFLECTION_HELPER`、`REFLECTION_FIELD_HELPER`、`REFLECTION_METHOD_HELPER`、`REFLECTION_CONSTRUCTOR_HELPER`、`REFLECTION_ACCESSIBLE_HELPER`、`UNSAFE_HELPER`、`THROWABLE_HELPER`、`THREAD_HELPER`、`WAIT_NOTIFY_FALLBACK`、monitor/exception helper、dispatch helper、fallback 和 JNI ABI registration fact。
- Dynamic reflection、scan-style reflection (`getDeclaredMethods/getMethods/getDeclaredFields/getFields/getDeclaredConstructors/getConstructors`)、复杂 MethodHandle、unsupported ConstantDynamic、unsupported Unsafe raw memory API 和更复杂 dispatch shape 继续以 `halfLowered` + fallback reason 记录，不允许 silent skip；当 ordinary method bytecode 可安全保留时，reflection scan reason 使用 `REFLECTION_UNSUPPORTED_SCAN`，Unsafe raw memory fallback reason 使用 `UNSAFE_RAW_MEMORY_FALLBACK`，MethodHandle/lambda/JDK collection fallback reason 使用 `METHOD_HANDLE_CHAIN_FALLBACK`、`METHOD_HANDLE_PERMUTE_FALLBACK`、`METHOD_HANDLE_FILTER_FALLBACK`、`METHOD_HANDLE_FOLD_FALLBACK`、`METHOD_HANDLE_COLLECTOR_UNSUPPORTED`、`ALT_METAFACTORY_FALLBACK`、`LAMBDA_UNSUPPORTED_FALLBACK` 或 `JDK_HELPER_FALLBACK`。
- `nativeEmbeddedClassBlob` 当前有 planner/report 和真实 ordinary-method fallback path：packaging report 写入 fallback blob manifest metadata，包括 original method id/key、fallback helper class name、fallback invoke descriptor、fallback reason code、SHA-256、压缩/编码算法、required Java version、storage target、definition mechanism、definition mechanism reason code、`lazyPerClassLoaderReuse` policy，以及 `cacheScope` / `cacheKey` / `cacheLifetime` / `globalReferencePolicy` / `unloadAware=false` / `futurePath` lifecycle 字段。fallback blob decoder 会在分配 decoded class buffer 前校验 encoded SHA-256 和 compressed payload capacity，wrong fallback id/key、corrupted payload 和 hash mismatch 都是稳定失败边界。
- Schema v1 禁止输出明文 generated fallback `.class` entry；当前可 JNI 桥接的 ordinary `halfLowered` method body 会复制进同 owner package helper class 的 static synthetic `invoke` 方法，fallback helper class bytes 已以 v1 RLE + deterministic XOR key stream 编码进 host native artifact，native side 通过 JVM `MessageDigest` 做 encoded/decoded SHA-256 校验，再通过打包进 output JAR 的 `J2llFallbackSupport` 获取 owner-private `MethodHandles.Lookup` 并优先 `defineHiddenClass`；JDK 或 owner-private Lookup 不满足时清晰回退到 JNI `DefineClass`，并按 fallback id + classloader identity 复用。当前 cache 是 process-lifetime global-ref cache，明确 `unloadAware=false`；classloader unload-aware cache 仍是后续扩展，不做不可靠 weak-ref cleanup。

## Minimal JVM-Hosted E2E

- 测试 harness 已能运行 original input JAR 和 output JAR 的 child JVM differential。
- 当前 host E2E 已真实经过 managed Zig `build.zig` dynamic library、generated loader、SHA-256 校验、`System.load`、`JNI_OnLoad` 和 `RegisterNatives`。
- Tier 5 first parity 覆盖常量 `Class.forName`、no-arg、reference、primitive 和 array 常量参数 descriptor 的 `getDeclaredMethod` / `getDeclaredConstructor`、`getDeclaredField`、`Method.invoke`、`Constructor.newInstance`、reference `Field.get` / `Field.set`、typed `Field.getInt` / `Field.setInt` / `Field.getBoolean` / `Field.setBoolean` / `Field.getLong` / `Field.setLong` / `Field.getDouble` / `Field.setDouble` 和 bounded `setAccessible(true)` 的 JVM reflection helper path。动态字符串/动态参数数组通过 encoded fallback 保持语义，更动态访问控制流和剩余 typed field accessor matrix 仍保守 fallback，并必须有 reason/report。
- Unsafe first parity 覆盖 selected ordinary method 中的 `objectFieldOffset` token lookup、`getInt` / `putInt` field access、monitor-backed `compareAndSwapInt` smoke path 和 `allocateInstance` JNI `AllocObject` path。当前 token lookup 依赖静态解析到 runtime metadata 的 `Field`；任意动态 `Field`、raw address memory API、off-heap memory 和更宽 typed accessor matrix 仍 fallback/skeleton，其中 raw memory boundary 使用 `UNSAFE_RAW_MEMORY_FALLBACK`。
- Release-readiness support facts are also summarized in `reports/support-matrix.json`, including Tier 5 rows for static reflection helper coverage, raw Unsafe fallback, dynamic VarHandle fallback, MethodHandle adapter fallback and unsupported finally reason codes. Strict release readiness v3 requires blocker reasons to map to release suite cases or weird-bytecode seeds and emits `suiteCoverageByBlocker`, so raw Unsafe, dynamic VarHandle, MethodHandle/altMetafactory, wait/notify, fallback-cache and non-host target boundaries are verifiable rather than free text.

## Remaining Conservative Boundaries

- 动态 reflection 字符串、动态参数数组和 reflection scan boundary 当前可通过 encoded bytecode-preserving fallback 或明确 unsupported scan reason 保持 no-silent-skip；更复杂 MethodHandle chain、unsupported ConstantDynamic bootstrap、unsupported Unsafe raw memory API 和更复杂 dispatch shape 仍 fallback；default-interface conflict/diamond boundary 当前报告 `UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT` / `DEFAULT_INTERFACE_DISPATCH_FALLBACK`，运行语义仍交给 JVM/JNI dispatch helper 或 fallback。default-interface super `I.super.m()` 当前不能安全复制进 helper class，保守 `frontendSkipped` 并报告 `UNSUPPORTED_DEFAULT_INTERFACE_SUPER`。
- 简单 catch-all rethrow/finally cleanup 已 lower；multi-exit finally、exception state merge、monitor/finally interaction 等仍 `frontendSkipped`，使用精确 reason code。
