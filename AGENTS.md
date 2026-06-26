# j2ll Agent Guide

本项目正在进行 clean-room rewrite：旧实现先备份为 legacy reference，新主线从清晰的 compiler pipeline 重新实现。新代码和新测试直接另起根目录 source tree，不继续写进旧 `obfuscator/src`。开始任何重写工作前，先阅读：

- `docs/rewrite-roadmap.md`
- `docs/pipeline/README.md`
- `docs/project-structure.md`
- `docs/java-support-tiers.md`
- `docs/protection-obfuscation.md`
- `docs/io-config-output-contract.md`

## 当前 Rewrite 方向

项目定位：

- j2ll 是 JVM-hosted JAR 混淆 / native lowering 工具；输出产物始终在 JVM 上运行。
- 输出产物仍是 JAR，并且必须在有 JVM 的环境中运行。
- Java object、array、Class、String、Throwable、Thread、monitor 和 GC 语义属于 JVM；native-lowered method 只能通过 JNI / runtime helper 操作这些 JVM 对象。
- `new` / `newarray` / `anewarray` / `multianewarray` / `Unsafe.allocateInstance` 等 Java-visible allocation 必须走 JVM/JNI helper，不允许用 `malloc` / `alloca` / native heap 伪造 Java object。
- `alloca` / native stack allocation 只能用于 native 临时数据，例如 JNI argument scratch buffer，不能保存或返回 Java-visible object。
- 文档和实现不得引入脱离 JVM 运行的 runtime 路线；所有 Java-visible allocation、object identity 和 object lifetime 都由 JVM/JNI 语义约束。

目标管线：

```text
.class
  -> ASM parse
  -> method CFG
  -> class hierarchy
  -> call graph / runtime analysis
  -> stack bytecode to three-address SSA IR
  -> optimization passes
  -> SSA IR protection passes
  -> LLVM module model
  -> LLVM protection passes
  -> LLVM IR
  -> native link / symbol visibility / strip
  -> output JAR repackaging / native registration
```

旧代码只作为行为参考和测试迁移来源。不要继续在旧的大型 lowering 类上堆新职责。

## Source Tree 边界

- 新生产代码放在根目录 `src/main/java`。
- 新测试代码放在根目录 `src/test/java`。
- 旧生产代码 `obfuscator/src/main/java` 只作为 legacy reference。
- 旧测试代码 `obfuscator/src/test/java` 只作为测试意图和行为样本参考。
- 不在 `obfuscator/src/main/java` 或 `obfuscator/src/test/java` 中添加新架构代码。
- 后续改 Gradle 时，主线 source set 应指向新的 `src/main/java` 和 `src/test/java`；旧 source tree 不进入主线编译，除非显式创建 legacy-only 对照任务。

## Legacy 边界

- 开始删除或移动旧源码前，先创建 legacy backup 分支或 tag。
- 不把 legacy package 放进生产 classpath。
- 不把旧源码复制到新源码树中作为“临时实现”。
- 可以参考旧测试意图，但新测试应落在新的 stage 边界。
- 从旧实现复制小型纯函数前，先确认它属于哪个目标 stage，并补对应测试。

## 推荐扩展路径

- 解析 `.class`：放在 `xyz.melodysky.frontend.classfile`。
- 构建 method CFG：放在 `xyz.melodysky.frontend.cfg`。
- Class hierarchy：放在 `xyz.melodysky.analysis.hierarchy`。
- Call graph / CHA / RTA / devirtualization：放在 `xyz.melodysky.analysis.callgraph` 或 `xyz.melodysky.analysis.runtime`。
- 栈式 bytecode 到 SSA：放在 `xyz.melodysky.ir.ssa`。
- IR model、validator、printer：放在 `xyz.melodysky.ir.*`。
- Optimization pass：放在 `xyz.melodysky.ir.pass`，区分 method pass 和 program pass。
- SSA IR 保护/混淆：放在 `xyz.melodysky.ir.pass.protection`，每个 pass 必须有开关、强度参数和固定 seed。
- LLVM 输出：放在 `xyz.melodysky.backend.llvm`。
- LLVM IR 混淆：必须基于 `xyz.melodysky.backend.llvm.model` / `backend.llvm.protection`，不要做 `.ll` 文本后处理。
- Runtime helper 生成：放在 `xyz.melodysky.runtime`。
- Packaging / repack / native registration：放在 `xyz.melodysky.packaging`。
- Zig/native build orchestration：放在 `xyz.melodysky.toolchain`；Zig `0.15.2` 是 schema v1 的固定统一 native build driver，安装目录固定为可执行 `j2ll.jar` 同级的 `zig/zig(.exe)`。缺失时先使用同目录已有 Zig archive，仍缺失再从 `https://ziglang.org/download/0.15.2/` 下载；解压后将官方目录内容规范化到 `zig/`。Zig 通过 j2ll 生成的 `build.zig` workspace 负责一次性按 selected target matrix 接管 per-class `.ll`、Zig-managed `.o`、JNI wrapper C、runtime helper C 和 fallback blob carrier C 的编译/链接；不要新增 host `cc` / `clang` 直连路径。
- Binary export/strip/symbol audit：放在 `xyz.melodysky.toolchain.symbols`；只导出 JNI / C ABI wrapper，Java method internal symbol 默认隐藏。
- 报告 JSON / resolved config / artifact sidecar writer：放在 `xyz.melodysky.report`，字段顺序和 wire name 必须稳定并由 golden tests 覆盖。
- 输入/配置/输出契约：按 `docs/io-config-output-contract.md` 实现；selector 命中的每个方法必须记录为 `lowered`、`halfLowered`、`frontendSkipped`、`notApplicable` 或 `failed`，不能静默 skip。
- JVM helper fallback：schema version 1 使用 `nativeEmbeddedClassBlob`，不要退回到明文 generated fallback class。
- 方法改写：普通 class method 可走 `nativeOriginal`；`<init>`、`<clinit>` 和有 Code 的 interface method 必须走 stub/helper 策略；abstract、already-native 和无 Code 的 interface method 记录 `notApplicable`。
- SSA merge 使用 block parameters 表达 stack/local merge；branch/goto/switch terminator 携带 target arguments。merge mismatch 必须显式 `frontendSkipped`，reason code 使用 `SSA_MERGE_STACK_HEIGHT_MISMATCH`、`SSA_MERGE_TYPE_MISMATCH` 或 `SSA_MERGE_LOCAL_SLOT_MISMATCH`。
- Exception 语义当前支持 typed catch handler block exception parameter、IR exceptional edge metadata、显式 `athrow` -> `THROW` terminator，以及 null/array/cast/div-zero implicit exception site metadata。catch-all/finally 复杂形状仍保守 `frontendSkipped`，reason code 使用 `UNSUPPORTED_COMPLEX_EXCEPTION_SHAPE`。
- Monitor/JMM 当前是 helper/fence-backed base：`monitorenter` / `monitorexit`、`ACC_SYNCHRONIZED` method、识别出的 synchronized exceptional unlock handler 都 lower 到 runtime helper，并带 monitor happens-before marker；volatile read/write、final field publication、Thread.start/join happens-before lower 到 conservative marker。复杂 catch-all/finally 仍不猜测。
- Class initialization 当前有 helper-backed skeleton：跨 owner `getstatic` / `putstatic` / `invokestatic` 和 `new` 插入 class object + class init guard；同 owner static field / static invoke 不递归 guard，因为当前 owner 已处于初始化语义内。`<clinit>` body 插入 begin/end/failed helper，并避免同 owner `<clinit>` 递归 guard。
- JDK bootstrap 当前通过 `JdkIntrinsicRegistry` 明确 policy：Object/String/StringBuilder/System.arraycopy/Math/boxing/Objects 常见路径走 runtime helper 或 direct lowering；当前 host E2E 已覆盖 String `length` / `equals` / `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)`、显式 StringBuilder append chain、System.arraycopy primitive/object/overlap/异常、Integer/Long/Boolean/Double boxing-unboxing、Objects.requireNonNull/equals 和 Math int/long/float/double abs/min/max。未支持的 JDK call 记录 `halfLowered` + `JVM_HELPER_FALLBACK`，fallback mode 仍按 `nativeEmbeddedClassBlob`。
- invokedynamic 当前支持 StringConcatFactory `makeConcat` 到真实 StringBuilder helper path；`makeConcatWithConstants` 常见 recipe 已通过 tokenized `j2ll_rt_string_constant` native carrier + StringBuilder helper 接入 child JVM E2E。LambdaMetafactory `metafactory` 的非捕获、单引用捕获、static method reference、JDK public instance method reference 和 constructor reference 已通过 JVM `LambdaMetafactory` / `MethodHandle.invokeWithArguments` helper 接入 child JVM E2E；`altMetafactory` serializable/marker/bridge metadata 仍只声明 skeleton/fallback 边界，复杂 capture 走 `halfLowered` fallback。
- Runtime metadata 当前有 `xyz.melodysky.runtime.metadata` index/dump：保留 class/member flags、Signature、runtime annotations、record、nest/inner、bridge/synthetic/record generated flags，以及 class object/init state handle。Static reflection 当前有 `xyz.melodysky.analysis.reflection` resolver：`Foo.class`、常量 `Class.forName`、常量 `getDeclaredMethod/Field/Constructor`、`Method.invoke`/`Constructor.newInstance` 可进入 reachability；动态字符串/参数保持 fallback 诊断。
- JNI 第一层当前有 `xyz.melodysky.runtime.jni` ABI model 和 packaging planner：descriptor -> JNI C type、RegisterNatives table、JNI_OnLoad/bootstrap wrapper、reference lifetime/local frame/pending exception policy、symbol allowlist。Runtime helper catalog 的 reference ABI token 使用 `jobject` / `jclass` / `jarray` / `jthrowable` 等 JNI handle；LLVM declaration 仍映射为 opaque `ptr`，helper header/C skeleton 显式包含 `JNIEnv* env`、pending exception 和 local-frame policy TODO。当前 JVM-hosted vertical slice 已通过 managed Zig `build.zig` path 扩展到 static/instance `nativeOriginal`、`jclass`/`jobject` implicit receiver、primitive `void`/`boolean`/`int`/`long`/`float`/`double` 参数和返回、multi-class/multi-method `RegisterNatives`、String `jstring` 读写、primitive/reference array JNI helper subset、`ThrowNew` exception bridge、generic constructor/`<clinit>` body helpers，以及 v1 encoded native-embedded fallback helper class bytes 的 lazy `DefineClass` / reuse smoke path。`LLVM_NATIVE_PATH` 现在覆盖 ordinary static/instance primitive scalar method、直线算术、compare branch、if/else、nested if、block-parameter/phi merge、JNI field helper-backed `int`/`long`/reference field access ABI（真实 E2E 覆盖 static/instance `int` read/write/add、volatile read/write fence、null receiver NPE ownership、String/reference field pass-through/read/write/null return）、`idiv`/`irem`/`ldiv`/`lrem` 的 ArithmeticException helper-backed semantics、`monitorenter` / `monitorexit` block 和 `ACC_SYNCHRONIZED` method 的 JNI `MonitorEnter` / `MonitorExit` helper path、显式 `athrow` -> JNI `Throw` bridge、常量 `Class.forName` / no-arg `getDeclaredMethod` / `getDeclaredField` / no-arg `getDeclaredConstructor` / `Method.invoke` / `Constructor.newInstance` / `Field.get` / `Field.set` / `Field.getInt` / `Field.setInt` reflection helper path、Unsafe statically resolved `Field` token 的 `objectFieldOffset` / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` / `allocateInstance` JNI `AllocObject` path、typed-int VarHandle `get` / `set` / volatile get-set / `compareAndSet` helper path、`byte[]`/`short[]`/`char[]`/`int[]`/`long[]`/`float[]`/`double[]`/reference array load/store/length helper subset、selected primitive/reference array allocation helpers、ordinary-method `new T(int,int)` constructor helper subset、`checkcast` / `instanceof` helper subset、String `length` / `equals` helpers、StringConcatFactory `makeConcat` / common `makeConcatWithConstants` helper path、LambdaMetafactory common `metafactory` helper path、Math `abs/min/max` int/long helper subset、LDC MethodHandle + `invokeExact` direct target、同 class selected static 和 private/special callee direct LLVM internal call，以及 no-arg `int` virtual/interface dispatch helper smoke path。JNI wrapper 只桥接 `JNIEnv*` / `jclass` / `jobject` / `jarray` 和 primitive ABI；field/array/allocation/type/String/div-rem/monitor/reflection/Unsafe/VarHandle/lambda/call Java-visible 语义通过 helper token、JNI API 和 pending-exception convention，不能 dereference Java object memory。更广泛 constructor shapes、复杂 finally/exception state merge、hidden-class fallback definition、带参数 reflection metadata、raw memory Unsafe/VarHandle、复杂 MethodHandle chain、`altMetafactory` runtime semantics 和复杂 virtual/interface dispatch 仍通过 template/helper/fallback 或后续 managed Zig toolchain/preflight 接实。
- Protection/obfuscation v1 当前已真实接入主线并默认启用已实现 pass：IR 层支持 StringConcat constant carrier encryption、primitive `int`/`long` constant XOR split、safe single-block fake branch/basic-block splitting 和 block-name obfuscation；monitor/JMM/exception/call/field/helper-sensitive shape 以及 `<init>` / `<clinit>` body-helper shape 按 pass 记录 skip reason，不改变 method lowering status。LLVM name obfuscation 通过共享 `LlvmNameMangler` 贯通 planner、LLVM lowerer、Zig workspace 和 JNI wrapper，生成 deterministic hidden `j2ll_f_<sha256>` linkable symbol；raw Java method symbol 不作为 dynamic export。`reports/protection-report.json` 记录 `passName`、`layer`、`status`、`reasonCode`、affected methods/symbols 和 seed；未实现 pass warning + ignore，不 silent skip。
- MethodHandle/invokedynamic 当前支持 `metafactory` common lambda helper E2E、`altMetafactory` 常见 `FLAG_SERIALIZABLE` / `FLAG_MARKERS` / `FLAG_BRIDGES` metadata skeleton、LDC MethodHandle + `invokeExact` direct target E2E、ConstantDynamic `ConstantBootstraps.nullConstant` skeleton；复杂 MethodHandle chain / unsupported ConstantDynamic / `altMetafactory` runtime class semantics 仍 `halfLowered` fallback。
- Unsafe/VarHandle 当前支持有边界 helper-backed subset：field/array offset、primitive/object get/put、volatile get/put、CAS、`allocateInstance`、typed-int VarHandle get/set/volatile/CAS common shape；真实 E2E 已接实 statically resolved `Field` token 的 Unsafe int get/put/CAS、`AllocObject`-backed `allocateInstance` 和 typed-int VarHandle field access。Unsafe offset 是 metadata token，不是 native memory offset；VarHandle helper 通过 JVM `VarHandle.toMethodHandle` / `MethodHandle.invokeWithArguments`，不是 native field offset；unsupported raw memory API 必须 warning + `halfLowered` fallback，volatile/CAS 保留 JMM marker。
- Exception/finally 当前支持简单 catch-all rethrow 和 normal/exceptional cleanup shape；multi-exit finally、exception state merge 等仍 `frontendSkipped`，reason code 使用更精确的 `UNSUPPORTED_MULTI_EXIT_FINALLY`、`UNSUPPORTED_EXCEPTION_STATE_MERGE` 等。
- helper-shaped lowering 可先把 String/Class/MethodType/MethodHandle 常量、对象/数组/type op、JDK intrinsic、StringConcat/Lambda 和 virtual/interface/indy fallback 表达为 runtime skeleton；这不代表已经实现完整 JVM dynamic dispatch、native exception bridge 或完整 JDK helper semantics。所有 reference value 仍是 JVM object / JNI handle。
- `nativeEmbeddedClassBlob` 当前有 planner/report 和最小真实 fallback path：`halfLowered` `String.substring(int)` fixture 的 helper class bytes 先按 v1 `j2ll-rle-byte-pairs-v1` 压缩，再用 `xor-sha256-key-stream-v1` deterministic key stream 编码，作为 native artifact 中的 encoded blob，由 JNI 解码后 `DefineClass` 并用 global ref 复用；schema v1 仍禁止输出明文 generated fallback `.class`。完整 per-classloader cache map、hidden-class path 和任意 fallback body 仍待扩展。
- 最小 JVM-hosted differential harness 当前覆盖 original input JAR 与 output JAR 的 child JVM 对比。当前 host E2E 已通过 generated `build.zig`/managed Zig invocation 产出嵌入库，覆盖 `LLVM_NATIVE_PATH` 的 static primitive scalar add/long/double/boolean compare/void no-op、if/else、nested if、phi merge、static/instance/volatile field helper path、monitor/synchronized helper path、explicit throw bridge、static reflection method/constructor/field helper path、Unsafe statically resolved int field token helper path、typed-int VarHandle helper path、LDC MethodHandle direct `invokeExact`、direct static/private-special callee call、div/rem ArithmeticException helper、String/reference field pass-through、broad primitive/reference array helper subset、selected primitive/reference array allocation helpers、ordinary-method object construction helper subset、`checkcast` / `instanceof`、String `length` / `equals` helpers、StringConcatFactory `makeConcat` / `makeConcatWithConstants`、LambdaMetafactory common helper、Math int/long helper subset，以及 no-arg `int` virtual/interface dispatch helper；template/helper path 覆盖 multi-class registration、String 内容 JNI path、primitive int array copy/new-array path、exception bridge smoke、generic straight-line and simple-branch constructor/class-initializer body helper first layer 和 encoded nativeEmbeddedClassBlob fallback smoke path。当前限制是已验证真实产物为当前 host target；selected target matrix 的 build plan、layout、target preflight 和 report 已稳定，非当前 host target 先记录 `ZIG_TARGET_PREFLIGHT` buildable/skipped reason，真实交叉编译取决于后续 Zig capability/SDK 接实。hidden-class fallback definition、复杂 finally/exception state merge、带参数/dynamic reflection、raw memory Unsafe/VarHandle、复杂 MethodHandle/altMetafactory 和复杂 virtual/interface dispatch 仍走 template/helper/fallback 或待扩展。

如果新增边界或更好的推荐路径，先更新本文件，再更新 `docs/pipeline/` 下对应 stage guide 的详细说明。

## 测试要求

- 添加功能、opcode lowering、runtime helper、LLVM emission、analysis 或 optimization pass 后，必须添加对应测试。
- 添加 opcode lowering：至少补 frontend/SSA 层测试。
- 添加 IR instruction 或 terminator：补 validator 测试和 LLVM backend 测试。
- 添加 runtime helper：补 backend declaration 测试和 runtime stub generator 测试。
- 添加 analysis：补 focused analysis unit test；涉及 invoke/devirtualization 时补 pipeline 测试。
- 添加 optimization pass：补 pass unit test，并确认 pass 后 validator 可通过。
- 改 pipeline 编排：补 pipeline test。
- 改 packaging/native build：补 packaging/toolchain test，必要时再跑 end-to-end benchmark。

测试要跟风险匹配，不要求每个小文档或局部改动都跑完整套件。

## Git 和检查习惯

- 不需要每次写完代码都检查 `git diff` 或 `git status`。
- 在阶段性完成、准备交付、准备提交、或怀疑工作区有并发改动时，再检查 git 状态。
- 不要还原用户未要求还原的改动。
- 不要使用破坏性 git 命令，除非用户明确要求。

## 健壮性原则

- 正确性优先于激进优化；不确定时使用 conservative fallback。
- 每个 stage 应有清楚的 diagnostics、validator 或 focused tests。
- 不让 LLVM backend 修补前端或 IR 的非法状态。
- 不静默忽略 JVM 可见语义，例如 exception、null check、class initialization、monitor、array store check、dynamic dispatch。
- 同一输入应尽量产生稳定排序的 diagnostics、IR、LLVM symbol 和 dump，方便回归测试。

## 文档维护

- 内部架构和 rewrite 计划写在 `docs/`，README 保持用户视角。
- 当实现和文档不一致时，先判断代码是否代表新的真实边界；如果是，更新文档。
- 每次引入新的 stage、fallback 策略、validator、测试落点或不支持边界，都要同步维护本文件和相关 docs。
