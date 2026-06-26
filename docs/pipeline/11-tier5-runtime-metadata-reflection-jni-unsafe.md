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

- 支持 `Foo.class`、常量 `Class.forName`、常量 `getDeclaredMethod` / `getDeclaredField` / `getDeclaredConstructor`。
- `Method.invoke` / `Constructor.newInstance` 在 receiver member 可静态解析时进入 reachability。
- 动态字符串、动态参数数组或未解析 target 必须产生 fallback reason，不允许静默 skip。
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
- Pipeline lowering report 会为 native registration 写入 `JNI_ABI_REGISTER_NATIVES` helper-backed fact，避免 JNI ABI 决策只存在于 packaging 内部。

## MethodHandle / ConstantDynamic

- `LambdaMetafactoryBootstrap` 支持 `metafactory` 和 `altMetafactory` common flags：serializable、marker interfaces、bridge method descriptors。当前真实 runtime path 覆盖 `metafactory` common shape，经 `j2ll_rt_lambda_new` 调用 JVM `LambdaMetafactory` / `MethodHandle.invokeWithArguments` 生成 lambda object；`altMetafactory` flags 仍是 metadata/skeleton 边界。
- LDC MethodHandle + `MethodHandle.invokeExact` direct shape lower 为 direct call target，并进入 call graph reachability；当前 child JVM E2E 覆盖 direct static target。
- `ConstantDynamic` 当前支持 `ConstantBootstraps.nullConstant` helper-backed skeleton；其他 bootstrap 明确 `halfLowered` fallback。

## Unsafe / VarHandle

包：`xyz.melodysky.runtime.unsafe`

当前真实 VarHandle path 覆盖 typed-int instance-field `get` / `set` / `getVolatile` / `setVolatile` / `compareAndSet`，native helper 通过 JVM `VarHandle.toMethodHandle(AccessMode)` 和 `MethodHandle.invokeWithArguments(Object[])` 调用，不把 field offset 解释成 native memory address。array view、byte order、coordinate transforms 和 raw memory shape 仍是 fallback 边界。

- `UnsafePolicy` 声明 supported subset：field/array offsets、primitive/object get/put、volatile get/put、CAS、`allocateInstance`。
- VarHandle common shapes：get/set、getVolatile/setVolatile、compareAndSet。
- 当前真实 JVM-hosted slice 覆盖 statically resolved `Field` 的 `objectFieldOffset` / `staticFieldOffset`、`getInt`、`putInt`、`compareAndSwapInt` / `compareAndSetInt` 和 `allocateInstance`：offset 是 runtime metadata 产生的 deterministic token，不是 JVM object memory offset；native helper 通过 JNI `GetFieldID` / `GetIntField` / `SetIntField` / `MonitorEnter` / `MonitorExit` 和 `AllocObject` 执行，不做 raw pointer arithmetic。
- Array base/index scale、object/long/volatile access、VarHandle common shapes 仍是 helper-shaped bounded subset；超出当前真实 JNI implementation 的 shape 必须保守 fallback 或保留明确 skeleton 边界。
- Volatile/CAS 必须保留 JMM marker through optimizer/backend。
- Unsupported memory API 必须 diagnostic + `halfLowered` fallback 或更保守 skip。

## Pipeline Reports And Fallback Blob Skeleton

- `RuntimeMetadataIndexBuilder` 和 `StaticReflectionResolver` 已接入主线 pipeline；runtime metadata dump 写入 `intermediates/runtime/runtime-metadata.json`，并包含 `reflectionReachability`。
- Lowering report 的 `helperBackedSites` 会用稳定 reason code 标注 runtime metadata helper、`REFLECTION_HELPER`、`UNSAFE_HELPER`、monitor/exception helper、fallback 和 JNI ABI registration fact。
- Dynamic reflection、复杂 MethodHandle、unsupported ConstantDynamic、unsupported Unsafe raw memory API 继续以 `halfLowered` + `JVM_HELPER_FALLBACK` 记录，不允许 silent skip。
- `nativeEmbeddedClassBlob` 当前有 planner/report 和最小真实 path：packaging report 写入 fallback blob manifest metadata，包括 original method id/key、fallback helper class name、SHA-256、压缩/编码算法、required Java version、storage target、definition mechanism、definition mechanism reason code 和 `lazyPerClassLoaderReuse` policy。
- Schema v1 禁止输出明文 generated fallback `.class` entry；当前 fallback helper class bytes 已以 v1 RLE + deterministic XOR key stream 编码进 host native artifact，native side 通过 JVM `MessageDigest` 做 encoded/decoded SHA-256 校验，再按 owner classloader lazy `DefineClass` 并复用。Hidden class definition path 和任意 fallback body 仍是后续扩展。

## Minimal JVM-Hosted E2E

- 测试 harness 已能运行 original input JAR 和 output JAR 的 child JVM differential。
- 当前 host E2E 已真实经过 managed Zig `build.zig` dynamic library、generated loader、SHA-256 校验、`System.load`、`JNI_OnLoad` 和 `RegisterNatives`。
- Tier 5 first parity 覆盖常量 `Class.forName`、no-arg `getDeclaredMethod` / `getDeclaredField` / `getDeclaredConstructor`、`Method.invoke`、`Constructor.newInstance`、`Field.get` / `Field.set` 和 `Field.getInt` / `Field.setInt` 的 JVM reflection helper path。带参数反射 metadata、动态字符串/参数数组和更宽的 typed field accessor matrix 仍保守 fallback，并必须有 reason/report。
- Unsafe first parity 覆盖 selected ordinary method 中的 `objectFieldOffset` token lookup、`getInt` / `putInt` field access、monitor-backed `compareAndSwapInt` smoke path 和 `allocateInstance` JNI `AllocObject` path。当前 token lookup 依赖静态解析到 runtime metadata 的 `Field`；任意动态 `Field`、raw address memory API、off-heap memory 和更宽 typed accessor matrix 仍 fallback/skeleton。

## Remaining Conservative Boundaries

- 动态 reflection 字符串、带参数 reflection metadata、复杂 MethodHandle chain、unsupported ConstantDynamic bootstrap、unsupported Unsafe raw memory API 仍 fallback。
- 简单 catch-all rethrow/finally cleanup 已 lower；multi-exit finally、exception state merge 等仍 `frontendSkipped`，使用精确 reason code。
