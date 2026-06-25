# Java Support Tiers

本文档定义 rewrite 后 j2ll 对 Java / JVM 特性的预期支持等级。它不是一次性交付承诺，而是功能路线和测试矩阵的分层依据。新增 Java 特性时，应先确认它属于哪个 tier，再补对应 stage 和 tier 测试。

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
- GC：第一阶段交给 JVM GC，通过 JNI handle / runtime helper 管对象生命周期。
- safepoint/deopt 预留：先设计 IR 位置，不急着实现完整 deopt。

暂不要求：

- 自研 GC。
- 自研 thread scheduler。
- 完整 deoptimization。
- 完整 Java Memory Model 优化。

测试要求：

- exception edge golden CFG + runtime parity。
- finally 在正常/异常路径都执行。
- synchronized 异常退出释放锁。
- volatile read/write ordering smoke test。
- 多线程 counter / wait-notify smoke test。
- JNI local/global reference lifetime test。

## Tier 4: JDK Runtime Interop

目标：常见 Java Library 可用，但不急着把整个 JDK 编译进 native。

功能范围：

- JVM-hosted JDK interop：`String`、`StringBuilder`、`ArrayList`、`HashMap`、`Objects`、`Math`。
- JDK intrinsic mapping：`Math.*`、`System.arraycopy`、`Object.getClass`。
- library call policy：direct lowering / runtime helper / JVM fallback。
- partial metadata model：`Class`、`Method`、`Field` 的静态可解析子集。
- JDK class 不完整时保守 fallback。

暂不要求：

- 编译整个 JDK runtime。
- 自有 class library。
- 完整 classloader/module system。

测试要求：

- `String` / `StringBuilder` parity。
- `ArrayList` / `HashMap` common operation parity。
- `System.arraycopy` primitive/object array test。
- `Math` intrinsic test。
- fallback helper declaration + runtime stub test。

## Tier 5: 静态高级特性

目标：写死在代码里的动态特性可以通过 closed-world 分析或 metadata 提前处理。

功能范围：

- Reflection 静态解析：`Class.forName("a.B")`、`getDeclaredMethod("x", ...)`。
- MethodHandle / VarHandle 常见静态形态。
- JNI：native declaration、`RegisterNatives`、JNI call helper、reference lifetime。
- invokedynamic 扩展：MethodHandle chain、constant dynamic subset。
- Unsafe subset：array base offset、field offset、CAS、`allocateInstance` 需要强边界。
- serialization / service loader 可作为后续 closed-world metadata。

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
- Unsafe CAS / field offset guarded test。
- unsupported dynamic reflection diagnostic test。

## Tier 6: GraalVM-like Closed World Runtime

目标：远期 native-image 方向。该 tier 不应阻塞 Tier 0-5 的 JVM-hosted/runtime-helper 路线。

功能范围：

- 编译 JDK runtime subset。
- 自有 object model。
- 自有 GC。
- 自有 thread / monitor / safepoint runtime。
- closed-world reflection metadata。
- class initialization analysis。
- whole-program points-to / escape analysis。
- aggressive devirtualization + guarded fallback。
- Unsafe / MethodHandle / reflection 的大子集。

暂不要求：

- 兼容所有动态 Java 行为。
- 兼容任意 agent / instrumentation / dynamic class loading。
- 在早期版本提供生产可用的完整 native-image runtime。

测试要求：

- closed-world reachability corpus。
- JDK subset bootstrap tests。
- GC stress tests。
- multithread stress tests。
- devirtualization correctness test。
- reflection metadata completeness test。
- large real-world jar corpus test。

## 使用方式

- 任何新功能先标注所属 tier。
- 一个 tier 的功能只有在其核心测试稳定后，才可以在 README 或 release note 中宣称支持。
- 如果某个特性跨 tier，按最高风险部分归类。例如 lambda 的常见 lowering 属于 Tier 2，但复杂 MethodHandle chain 属于 Tier 5。
- 遇到不确定语义时优先 conservative fallback，不静默生成可能错误的 native code。
