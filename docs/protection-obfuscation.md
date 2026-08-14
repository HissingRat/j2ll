# Protection And Obfuscation Plan

Native 产物的攻击者模型、Ghidra 实测基线、per-build 多样性与逐项验收清单见
[`native-hardening-attacker-validation.md`](native-hardening-attacker-validation.md)。本文件描述各 pass
的语义边界；不能仅凭 pass 已启用就推断最终产物不存在批量恢复捷径。

本文档定义 rewrite 后 j2ll 的保护/混淆设计。目标是把混淆分成三个清晰层次，避免把 Java 语义、LLVM emission 和 binary packaging 混在一起。

```text
SSA IR protection passes
  -> LLVM module model protection passes
  -> binary symbol visibility / strip
```

核心原则：

- 先保证语义正确，再增加强度。
- schema v1 中每个 IR/LLVM pass 字段都是直接 boolean 开关；`seed: null` 的正式构建默认启用 per-build 多样性，需要可重复输出时显式配置 protection seed。
- Build-root派生只通过类型安全的`BuildProtectionDomain` registry；mainline
  使用集中`BuildProtectionMaterials`冻结各stage材料，不允许stage临时发明
  字符串domain或直接复用另一stage的seed。
- 提高静态批量分析成本优先于压缩产物，但变换必须有显式大小边界：优先
  table-free storage、bounded call topology和同值小组复用，并在攻击者回归中
  记录final native/generated-C字节数与dual-build delta。大小指标是回归证据，
  不是plaintext、export、语义或final-binary审计的替代品。
- 每个 pass 声明输入 IR 形态、输出 IR 形态、是否保持 SSA、是否改变 CFG、是否需要 runtime helper。
- 不做脆弱的 LLVM `.ll` 文本后处理。LLVM IR 混淆必须基于 LLVM module model。
- 对外只导出 loader/bootstrap 需要的 JNI / C ABI wrapper；Java method 对应的 LLVM function 在跨 object link 时可使用 `external hidden`，但不得进入 dynamic export list。

## Layer 1: SSA IR Protection

该层发生在 validated SSA IR 之后、LLVM lowering 之前。它最适合做需要 Java 语义、类型、CFG、call graph 和 runtime analysis facts 的混淆。

推荐包：

```text
xyz.melodysky.ir.pass.protection
```

推荐 pipeline：

```text
canonicalize
  -> validate
  -> semantics-preserving optimization
  -> protection passes
  -> protection-aware validation
  -> backend preparation
```

注意：保护 pass 后不要再跑会把混淆清掉的 aggressive cleanup。只允许必要的合法性修复和 validator。

### 控制流平坦化

目标：把 method CFG 转成 dispatcher-driven 结构，降低原始控制流可读性。

建议实现：

- `ControlFlowFlatteningPass`
- `ControlFlowFlatteningRegionPlanner`
- `ControlFlowFlatteningPlan`
- `ControlFlowFlatteningRegion`
- `ControlFlowFlatteningRegionRewriter`

边界：

- 必须保留 exception edge、JNI local-reference ownership和class-init ordering语义。
- 规划单位是bounded single-entry/multi-exit region，而不是强迫整method进入一个dispatcher。
  每个method最多4个互不重叠region，每个region含2到32个原始member block；超过单region
  预算的安全subgraph按稳定block顺序切片并重新验证single-entry，不生成无界dispatcher。
- 产生owned JNI local ref、含instruction exception site、exception edge/handler、
  monitor/JMM marker或class-init敏感操作的block不进入region。block parameter、edge
  target argument或跨block instruction-defined SSA value超出当前dispatcher ABI时也留在
  region外；这些block及其instruction/exception evidence保持原样。
- region entry通过自己的adapter进入region-local dispatcher；内部edge才编码为state
  transition。任意region exit直接进入原region外target，因此一个region可有多个exit，
  但敏感block不会被dispatcher synthetic cycle包围。

测试：

- full safe branch与夹在owned/exception边界旁的partial region runtime parity。
- multi-exit、region外exception path和owned local-reference release parity。
- validator 检查 CFG 和 SSA 合法。

当前 v1 使用`ControlFlowFlatteningRegionPlanner`先产生immutable region plan，再由
`ControlFlowFlatteningPass`只重写获准member blocks。每个region的block-to-state映射和
default target由build/method/region材料独立派生；state固定为
`[0, regionMemberCount)`的dense permutation，不增加状态空间、不生成permutation table，
也不增加每次transition的查表工作。相同显式seed保持相同plan与映射，不同build可改变
映射。成功改写至少一个region仍记录`CONTROL_FLOW_FLATTENING`；没有safe region按稳定
`CONTROL_FLOW_FLATTENING_*`原因记录`SKIPPED`并保留输入IR。coverage的`affected=true`
只表示至少一个region真实改写且整个pass输出通过validator，不能从requested或候选数量
推断。child JVM E2E同时覆盖完整safe if/else/nested branch，以及region外owned reference、
数组异常/pending-exception边界旁的partial region；不能用原始switch或其他pass的`RAN`
充当证据。

### 虚假分支

目标：插入 opaque predicate 和永不执行/极少执行的 branch。

建议实现：

- `FakeBranchesPass`
- `OpaquePredicateFactory`
- `FakeBranchInserter`

边界：

- predicate 不能依赖 undefined behavior。
- 不能触发 JVM 可见副作用。
- seed 固定时输出稳定。

测试：

- deterministic seed test。
- fake branch 不改变 observable behavior。
- protected SSA 中应出现独立的 predicate gate / detour，并通过 IR validator。
- 对使用动态参数的候选 shape 检查 native path；对纯常量 predicate form 不宣称 optimizer 后一定保留。

当前 v1 已由独立的 `FakeBranchesPass` 实现保守子集：对无 exception edge、无 block parameter、无 monitor/JMM/call/field/helper-sensitive opcode 的安全 method，在原 entry 前插入 deterministic predicate gate 和 detour。pass 优先使用可用的 boolean、`int` 或 reference 参数生成 runtime-dependent predicate；没有可用动态参数时使用纯常量 predicate form。这个变换在 protected IR 中真实有效，但 managed Zig 使用 `ReleaseSafe`，纯常量 form 可能被 LLVM optimizer 折叠并连同 native branch 一起消除，因此不能把它过度宣称为每个最终 binary 都稳定保留的 opaque branch。`<init>` / `<clinit>` body helper shape 只记录 `PROTECTION_STUB_BACKED_METHOD` skip，不改写 body-helper CFG。

### 基本块拆分

目标：把大 block 拆成多个小 block，扰乱线性阅读和 pattern matching。

建议实现：

- `BasicBlockSplittingPass`
- `SplitPointSelector`

边界：

- 不在 phi/block parameter 边界制造不一致。
- 不拆 required atomic region，例如 monitor enter/exit 的敏感片段。

测试：

- split 后 use/def、dominance、terminator 合法。
- runtime parity。

当前 v1 由独立的 `BasicBlockSplittingPass` 选择含至少两条安全指令的 eligible block，在 seed 决定的 instruction boundary 拆成 prefix/suffix，并用显式 `goto` 连接；该 pass 不插入 fake branch。它与 `FakeBranchesPass` 使用独立配置字段和独立 `protection-report.json` pass row，运行后分别通过 IR validator。后续 block-name obfuscation 可能重命名 split/fake block，所以测试和报告以 CFG shape 与 pass status 为准，不依赖临时 block 名。

### 基本块名称混淆

目标：移除 protected SSA 中带原始语义或 pass 形态提示的 basic-block 名称。

建议实现：

- `BlockNameObfuscationPass`

边界：

- 必须同时重映射 terminator target、block exception edge 和 instruction exception-site handler。
- candidate validation 失败时回退原 method，不留下部分重命名。
- 只改变 IR identity/display name，不改变 CFG、SSA value 或 JVM-visible 语义。

当前 v1 已实现 `BlockNameObfuscationPass`，由必填 boolean `protection.ir.blockNameObfuscation` 独立控制，并产生独立 protection report row。

### 常量加密

目标：整数、长整型、浮点位模式等常量不直接以明文形式出现在 IR/LLVM。

建议实现：

- `ConstantEncryptionPass`
- `ConstantEncodingStrategy`
- `ConstantDecodeSequenceBuilder`

边界：

- 注意 Java numeric conversion、NaN、signed/unsigned shift、溢出语义。
- floating constant 可先使用 bit-level encoding，不做改变数值语义的变换。

测试：

- primitive constant parity。
- edge cases：`MIN_VALUE`、`MAX_VALUE`、`NaN`、`Infinity`。

当前 v1 已接实 `CONST_INT` / `CONST_LONG` 的 deterministic XOR split/decode sequence，并在 LLVM planner/backend 中支持 `XOR_I32` / `XOR_I64` 继续进入 `LLVM_NATIVE_PATH`。`CONST_FLOAT` / `CONST_DOUBLE` 使用 `Float.floatToRawIntBits` / `Double.doubleToRawLongBits` 取得原始位模式，经过 integer XOR decode 后通过 LLVM `bitcast i32 -> float` / `bitcast i64 -> double` 恢复值；child JVM E2E 覆盖普通值、`NaN`、`-0.0` 和 infinity 的 raw-bit parity。exception、monitor/JMM、field/call/helper-sensitive method 只跳过该 pass 并写入 reason code。

### 字符串加密

目标：字符串常量不以明文保留在 native artifact 中。

建议实现：

- `StringEncryptionPass`
- `StringLiteralCollector`
- `StringEncryptionStrategy`
- `StringDecodeHelperPlanner`

边界：

- Java `String` interning 语义需要明确策略。
- 可先走 runtime helper 解密，不急着做复杂 cache。
- seed 固定时密文稳定，便于测试。

测试：

- ASCII / UTF-16 / surrogate pair parity。
- repeated literal cache policy。
- final binary string audit，确认明文不出现。

当前 v1 已接实 native-side string encryption：SSA 中的 `j2ll_rt_string_constant|string:<literal>` carrier、普通 `CONST_STRING` / `ldc String`，以及安全的 TEMPLATE constructor body string literal 都会进入业务字符串 native representation。`StringEncryptionPass` 的新产出固定为 `enc:v2`；它的 64-bit carrier token 与 encrypted-payload key 绑定，token SSA 名称和数值都从 build/method/site 材料派生。`enc:v1` 解析只保留为 compiler-internal 兼容边界，不是生产 emission 合同。最终生成物不再包含统一 token dispatcher 或 encrypted table；每个不同 Java 字符串值使用 build-scoped hash-only helper symbol、独立 ciphertext/派生材料和 inline decode/volatile clear，LLVM call 与 generated C 由同一 build identity 映射。pass 写入 emitted LLVM 的 token SSA value name 同样由 build seed 与 method/site/token 派生为 hash-only identifier，不再保留稳定 `%j2ll_str_token_<index>` 跨构建锚点。

native-text emitter 不再生成统一固定 decoder 或相邻 XOR seed shares；每个 use identity 从四种 compact 32-bit codec family（Weyl ARX、dual-lane ARX、32-bit Feistel、fold/rotate）中选择，并派生 schedule、遍历方向、rotate/shift 与常量，解码形状直接内联到拥有 scratch 的调用点。Weyl/dual-lane/fold的schedule具有不同指令序列；Feistel schedule主要改变round material/rotation，不能把4 family × 3 schedule夸大为12种独立结构。32-bit unsigned arithmetic降低常量与寄存器宽度，但这层本来就是at-rest obfuscation而非密码学边界。多字节 ciphertext 仍通过 build/purpose/use-scoped affine bijection 按 `(offset + logicalIndex * stride) mod length` 物理存放；stride 与 length 互质。解码只维护 activation-local physical cursor，不生成 permutation table、额外 ciphertext byte、padding 或第二份副本。空/单字节输入只能使用 identity layout。`GeneratedNativeHardeningAudit` 要求生产 source 提供 `AFFINE_CIPHERTEXT_STORAGE` evidence，并以 `INVALID_AFFINE_CIPHERTEXT_STORAGE` 阻断多字节 identity/direct-index 回归。

通用 generated-C sensitive-literal rewrite 进一步把 decode 从 function prologue 推迟到
真实 use-site：同一 C function 内的同明文只对应一个activation-local singleton slot；
distinct literal只有在lexer证明它们位于同一个direct C call argument list、因此必然共同
求值时才组成tuple。tuple最多8个component/512 decoded bytes，单个超长文本独占；不同
call、assignment或分支保持独立use窗口。tuple只有一个affine ciphertext与主codec，但每个
component另有build/purpose/use-derived lane mask真实参与cipher bytes；offset只以内联常量
进入对应use，不生成pointer/offset table。emitter为每个record形成显式lifetime/use-count
plan：单次use直接在该use无条件解码；同一direct-call参数组只选择一个参数表达式解码，
其余参数只引用同一scratch base加编译期offset；只有跨call或assignment复用才保留
`ready` guard。直接调用参数在callee进入前均已完成求值，因此raw base参数不会在唯一
decode参数之前读取明文。每个function使用一个聚合scratch，统一cleanup
hook在normal/early/failure exit清零全部slot。不同function不共享tuple、slot、明文cache或
encoding identity，`CROSS_FUNCTION_NATIVE_TEXT_TUPLE_REUSE`为blocking source finding。
use-site codec仍是site-bound inline shape，不因此引入global decoder或集中pointer
directory。不同function可调用translation-unit内唯一metadata-free
`noinline` zeroizer/cleanup callback以减少重复清零骨架；该callback只处理
scratch地址/长度，不接收ciphertext、codec或JVM metadata，不能演化成共享decoder或
plaintext cache。

普通固定异常类型和错误文案属于窄化的低敏感例外。只有显式 closed allowlist
命中的 `j2ll_throw_new` pair 才会被 outline 为 build-scoped hash-only
`noinline,cold` leaf；leaf 只接收 `JNIEnv*`，不接收 owner、member、descriptor、
metadata token、业务字符串或 Java value。低敏感 leaf fragment可按相同明文共享
一个 lazy-once encoding，但每个 leaf function只调用它实际引用的 decoder。这样
减少错误冷路径在每个 localized helper 中重复的 codec/throw骨架，同时不恢复
全局 metadata resolver、集中业务字符串 decoder 或跨站点高敏感明文 cache。
未列入allowlist的错误文本继续使用activation-local scratch。

每次 ciphertext indexed read 都通过 `const volatile unsigned char` lvalue 形成跨平台 runtime boundary，防止 Zig/Clang 在 generated-C `ReleaseSmall` optimization 中把常量 ciphertext + key 解码循环折叠回最终 binary 明文；generated-C audit 对缺失或混用 direct read 的 source fail closed，`-O2` object test 扫描全部 12-byte UTF-8/UTF-16LE sliding windows。helper 在栈上恢复 JNI modified UTF-8，调用 `NewStringUTF` 后立即清零，包括 JNI 返回 `NULL` 且保留 pending exception 的路径。相同值允许在单次构建内共享一个小 helper group。通用 runtime metadata、business string、registration text 分别使用独立 build material；registration/runtime metadata 也保持不同 purpose/lifetime/lookup。generated-C audit 会阻断 reusable decoder fanout、固定 SplitMix shape、相邻 seed/cipher、optimizer-foldable read与非法affine storage。artifact audit 中 `LLVM_NATIVE_PATH`、`TEMPLATE_JNI_PATH_STABLE_SURFACE` 和 StringConcat constant carrier stable generated-C surface 是 blocking sensitive fact，并分别记录 `promotionReason=llvmNativeSurface`、`templateStableSurface`、`stableGeneratedCSurface`；report 只写 literal hash，lowering helper evidence只写 kind + identity hash。class name / descriptor / reflection metadata token / lambda 或 MethodHandle bootstrap metadata 仍不加密；reflection-sensitive method 的普通 `CONST_STRING` 记录 `STRING_ENCRYPTION_REFLECTION_SENSITIVE` skip，相关 metadata fact 只按 `metadataSensitiveObservedOnly` 进入 observed-only evidence，避免破坏静态 metadata 解析。

原有 carrier/storage contract 的 focused/full 与六目标证据继续保留；本轮
use-site lazy rewrite 另有 Clang/MinGW GCC 的 generated-C compile/run parity，
以及固定 Windows x64 实际构建的 artifact/readiness evidence。不能据此替代
non-host JVM runtime E2E。
`build_2026-07-28_09-53-53`和`build_2026-07-28_09-56-32`各有39个carrier，
跨构建name/token overlap均为0，且两次artifact/readiness audit通过。Windows
Ghidra动态probe均以exit 0捕获10 owners / 57 bindings并确认没有
`j2llRegister`动态导出；它证明registration入口合同，不证明业务字符串或
wrapper在静态上不可恢复。独立Ghidra normalized-p-code复验得到两份更新DLL的
`direct/multiple/unresolved=30/19/8`与`26/26/5`；正式mapping reuse为
5/57（8.77%），14个shape changed、28个resolution changed、10个在任一构建中
unresolved。最终machine-code业务字符串恢复率仍需单独测量，不能从probe成功或
wrapper reuse下降推导。

### 方法内联/拆分

目标：扰乱 method 边界，提高逆向难度。

建议实现：

- `MethodInliningPass`
- `MethodSplittingPass`
- `MethodOutlinePlanner`
- `SyntheticMethodAllocator`

边界：

- 第一版优先做小型 private/static method inline。
- 方法拆分需要明确 exception、monitor、local state 和 call ABI。
- 不改变 stack trace/reflective visibility 的用户可见语义，除非 feature gate 明确允许。

测试：

- inline/split 前后 runtime parity。
- reflection-sensitive method 默认不处理。
- exception stack behavior 有明确 policy test。

当前 v1 已接入两个受限的 program-level SSA pass：

- `MethodInliningPass` 只处理 caller/callee 都有 preliminary `LLVM_NATIVE_PATH` 证据的 `CALL_STATIC`，以及 same-owner private-self `CALL_SPECIAL`。callee 必须是小型 pure-scalar IR，递归、reflection、field/call/helper、non-native、exception、monitor/JMM、`<init>` / `<clinit>` shape 都稳定跳过。frontend direct call 的无 handler pending-exception evidence 只有在 callee 已证明 non-throwing、synthetic exception value 完全无 use 时才可随 call 删除；protected edge、specific exception kind 或 observable exception value 继续 fail closed。它支持 value/block remap 和多个 return 汇入 typed continuation，但不删除原 Java method 或仍需的 native registration。
- `MethodSplittingPass` 只 outline ordinary LLVM-native method 中单个 basic block 的 pure-scalar suffix；live-in 必须是可用 scalar，当前只允许一个 scalar live-out。outlined helper 是 compiler-internal IR/LLVM function，不是新的 Java method，也不进入 `RegisterNatives`。原 terminator/多个 successor 仍留在 caller，caller/helper 校验失败时整体回滚。

两者都由 `ProgramIrProtectionCoordinator` 在 per-method protection 之后运行，使用全局 seed，并分别产生 `METHOD_INLINING` / `METHOD_SPLITTING` report row。focused model、Windows real-Zig child-JVM parity/pass-RAN，以及六目标 feature-specific build-graph/content/privacy/export evidence 均已通过。

### 调用间接化

目标：把直接调用改成 dispatcher/table/helper 间接调用。

建议实现：

- `CallIndirectionPass`
- `CallTargetTableBuilder`
- `CallDispatcherPlanner`

边界：

- devirtualized call 可以再间接化，但不能丢 null check/class init/exception 行为。
- 多目标 virtual/interface call 的 runtime dispatch 与 protection dispatch 要分层，避免语义混淆。

测试：

- static/special/direct call indirect parity。
- devirtualized call indirect parity。
- unresolved external/non-native target。

IR `callIndirection` 当前已实现为显式 Java-call-semantics plan，但受 per-owner LLVM module 边界约束：只接受 caller/target 都有 native path 证据的 same-owner static/private-special bytecode-direct call。virtual/interface（包括已证明 single target 的 devirtualized call）和 cross-owner static/special 当前走原 JNI bridge，并以 `IR_CALL_INDIRECTION_BACKEND_UNSUPPORTED_SHAPE` fail closed；unresolved/multi-target、dynamic/helper、non-native target、constructor/class-initializer 和 signature mismatch 也稳定跳过。approved `IrInstruction` 携带 typed `IrCallIndirectionRef`，IR validator 校验 plan/group/entry/signature/invoke kind。group key 同时包含 Java/SSA signature 与 preliminary native plan 的 hidden `JNIEnv*` / owner-`jclass` ABI proof；表面 IR signature 相同、实际 LLVM function-pointer type 不同的 target 必须拆组，forged mixed-ABI group 由 validator fail closed。`LlvmIrCallIndirectionPass` 只消费该 metadata，生成 internal `j2ll_ircit_<sha256>` table，不在 backend 重新做 Java call resolution，且仍独立核对真实函数类型。

当前 v1 已接实 LLVM module model 层的保守子集：对 same-class selected static/private direct LLVM call，`LlvmCallIndirectionPass` 按 LLVM function signature 分组，默认在 module 中生成 deterministic hidden function-pointer table `j2ll_cit_<sha256>`。caller 按 seed 派生的 stable table order 取出 function pointer 并 indirect call 原 hidden LLVM function，成功记录 `CALL_INDIRECTION_TABLE`。如果 table 形态不可用，保留 deterministic hidden dispatcher switch `j2ll_cid_<sha256>` alternate form，caller 传入 selector，成功记录 `CALL_INDIRECTION_DISPATCHER`。该 pass 只操作 `LlvmModule` model 和 Zig workspace 使用的 `.ll` source，不做最终 `.ll` 文本 regex；table/dispatcher symbol 使用 protection seed 稳定生成，不进入 dynamic export allowlist。当前不处理 virtual/interface generic dispatch、non-native/unresolved call、lambda/MethodHandle bootstrap metadata shape、monitor/exception/JMM-sensitive shape。无适用 direct call 的 table mode 记录 `CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE` skip。

这两个开关保持独立：IR pass 产生 `IR_CALL_INDIRECTION` 和 `IR_CALL_INDIRECTION_BACKEND` 证据；LLVM pass 继续产生 `CALL_INDIRECTION` 证据。已经由 IR metadata lower 成 indirect call 的 site 不会再被 LLVM pass 重复变换。

### 虚表/方法表隐藏

目标：隐藏可读的 Java method mapping 和 dispatch table。

建议实现：

- `MethodTableHidingPlan`
- `HiddenMethodTableBuilder`
- `MethodTokenAllocator`

边界：

- 这更像 plan + runtime/backend 协作，不应只靠一个 IR pass。
- JNI wrapper/export name 仍必须可被 JVM 找到或注册。
- 内部 Java method 对应 LLVM function 必须 internal/hidden。

测试：

- method token deterministic seed test。
- symbol audit：内部 Java method name 不导出。
- RegisterNatives/JNI wrapper 仍可绑定。

`methodTableHiding` 当前已实现为 packaging/native-registration 协作 plan，不伪装成单 method IR rewrite。`MethodTableHidingPlanner` 按 registration owner 生成 build-diverse 的物理 registration order；opaque binding token 只作为 hash-only report evidence，不进入 generated C 或 final native。每个 owner 在自己的注册窗口内以 straight-line assignment 构造唯一临时 `JNINativeMethod[]`，不生成 persistent token/function table，也不执行 nested runtime join。生成器只接受与 final registration plan 精确一致的 plan，mismatch fail closed。

owner/name/descriptor 是 JVM registration 必需信息，运行时仍会短暂形成明文；最终 generated C/native 不再使用全局 metadata 目录或 aggregate decode-all。每个 owner 独立保存 registration-domain、build-scoped encoded bytes，在该 owner 的注册窗口内解码到临时 scratch；owner name 在 defining-loader lookup 返回后立即清零，method/descriptor scratch 和 `JNINativeMethod[]` 则在 `RegisterNatives` 成功或失败后以 volatile zeroizer 清理再释放。aggregate root、per-owner helper 和 implementation symbol 均为 internal/hidden，动态导出仅保留 `JNI_OnLoad`。aggregate root使用activation-local `jclass[]`与`registered_count`统一执行成功清理和逆序rollback，避免按owner展开重复控制流。multi-owner rollback仍检查每次 `UnregisterNatives` 的 status 与 pending exception；rollback 不完整时通过 `FatalError` fail closed，完整时恢复原始 pending exception，并检查 `Throw` status 与 pending-exception evidence，恢复失败同样 fail closed。四种rollback/exception-restore失败文案由registration-domain、build-scoped、hash-only `noinline,cold` leaf拥有，每个leaf只接收`JNIEnv*`并只在对应`FatalError`路径恢复；`GeneratedNativeHardeningAudit` 以 `STABLE_REGISTRATION_DIAGNOSTIC` 阻断历史稳定明文及任意 direct/adjacent `FatalError` C string literal 重新成为跨构建 xref 锚点。这仍是 at-rest obfuscation，不是运行时内存保密。`protection-report.json` 使用 `METHOD_TABLE_HIDING_TRANSIENT_OWNER_LAYOUT`，`packaging-report.json` 明确写入 transient strategy、未生成 runtime token/function table，以及 hash/token-only report evidence。当前 gated real-Zig host E2E 已覆盖多 method 注册和双 ClassLoader；六目标专项已覆盖 two-owner generated-C/build-graph/privacy/export。多 owner + virtual/interface 的真实 runtime 与 non-host JVM runtime 仍待补。

method name 与 descriptor 的同值复用严格限制在同一 owner 和各自 purpose domain；
不同 owner 即使文本相同也会获得独立 encoding/scratch。去重文本按最多8项、最多
512 decoded bytes形成bounded group，每group使用一个encoding/decoder，单个超限文本
独占一组。完整owner-local layout不超过64个bindings且text scratch不超过16 KiB时，
registration helper使用有界栈上的`JNINativeMethod[]`与字符scratch，省去常规owner的
两次heap allocation；任一上限超出时保留heap路径。两条路径都在成功、失败和rollback
出口清零临时表与明文scratch，heap路径随后释放。

2026-08-02同一v2 workload/config的五目标正式build before/after中，动态库总raw size
从2,574,614 B降到2,044,414 B（-20.59%），各平台下降17.12%–22.59%。generated C
从4,745,384 B降到3,711,244 B（-21.79%），LLVM `.ll`总量只变化159 B；code section
下降23.95%–24.98%，readonly data下降3.10%–7.04%。最终仍为71个`nativeLowered`、
0个`skipped`并通过五目标artifact audit。该样本的native-only coalescing因严格
exception/reference/caller边界为0命中，所以不能把本轮下降归因于删除LLVM body；
生产method-splitting和其他保护适用策略保持不变。正式build identity为随机值，因此
该数据是workload-level before/after evidence，不宣称bit-identical controlled A/B。

### JNI wrapper local ABI topology

Final entry plan先处理6A闭集：ordinary standalone、descriptor仅`V/I/J/F/D`、
pure scalar/non-throwing且无semantic JNI/field/call/monitor/local-reference/native-caller
surface的registered method可把physical JNI entry与原bounded topology迁移进LLVM。
`RegisterNatives`指向build-scoped hash-only proxy；proxy再按原`NativeLocalAbiPlanner`
shape进入独立NOINLINE semantic body，不允许直接注册body或删除保护跳数。其余
`LLVM_NATIVE_PATH` wrapper与规范LLVM body之间继续按final native ABI选择bounded profile。
传递`JNIEnv*`或owner `jclass`的JVM/JNI semantic-surface binding强制使用bounded branched
参数重排，避免保留direct one-hop wrapper；不传递两者的pure-native scalar binding继续
由build identity从direct canonical、单层参数重排、
双层参数重排和branched四种local topology中选择，以保留较低成本的build
diversity。branched形态只使用一个activation-local volatile predicate，在“一层
route”和“两层route”之间选择；最多生成三个`static` bridge，且只重排调用本来
已有的native参数。这一上限用于控制代码膨胀，不引入持久function pointer、
cookie、额外JVM状态或native object representation。

生成的branched bridge使用`noinline,used`保留有界route边界，但不使用`optnone`；
generated C因此仍可由`ReleaseSmall`执行正常size optimization。per-class LLVM
input与final link module保持`ReleaseSafe`，不能把C侧优化策略解释为放宽LLVM
protection或artifact audit。

所有 bridge 都不得执行 JNI、解引用 Java reference、改变 local-reference
lifetime，或观察/清除 pending exception。它只增加静态 wrapper classifier
需要处理的 call topology，不是安全边界；动态 hook `RegisterNatives` 仍能获得
binding，结构化 binary analyzer 也可能恢复 route。当前 source/planner 与
cross-target fixture此前已覆盖该第四种形态；全套test、强制该shape的
`ZigCrossTargetBuildTest`和`ProtectionCrossTargetEvidenceTest`真实六目标均已
通过。`HostNativeLocalAbiBridgeCParityTest`另真实编译并分别执行两条branched
route，覆盖异构sentinel参数顺序、零参数`void` conditional与96-site optimized
object增长预算。

2026-07-31的v2双随机构建完成了本切片的final-binary复验。71个
`nativeLowered` binding中，source plan由旧样本的15/22/19/15
（direct/single/double/branched）变为4/3/5/59；Windows x64 Ghidra分类由
35 direct / 26 multiple-callee / 10 unresolved变为4 / 59 / 8。第二个随机
build为3 / 58 / 10；两次构建71个wrapper的相同RVA为0，双方可解析的60个
binding中只有3个保留相同resolution fingerprint。`decrypt`、`encrypt`、
`encryptRSA`、`verifyJson`和`createCombinedDigest`在两次build中都从direct
one-hop变为two-route multiple-callee。动态probe仍能捕获完整71个
`RegisterNatives` binding，因此该结果只证明静态批量分类成本提高，不把wrapper
topology当成安全边界。

6A是有界physical relocation，不是新的保护声明。Proxy为`external hidden noinline`，
bridge为`internal default noinline`，semantic body也强制`noinline`；branched predicate继续
activation-local volatile materialization，不使用`llvm.used`、cookie或持久function-pointer
root。Structured LLVM gate锁住exact call target、ordered arguments、caller closure和closed
CFG，generated-C gate阻断logical wrapper/body/bridge残留。新的v2、真实六目标与Ghidra复验
仍为pending，以下历史topology/size数字不能作为6A验收结果。

同一实测中，五个selected-target原生库总raw size从3,502,027 B降到
2,611,446 B（-25.43%），Windows x64从686,080 B降到503,296 B
（-26.64%）；output JAR从3,969,705 B降到3,872,737 B（-2.44%）。
generated JNI C从4,800,630 B增至4,842,471 B（+0.87%），说明缩减来自
`ReleaseSmall`对重复runtime/wrapper机器码的优化，而不是source表面变小。
第二个随机build的native总量为2,616,590 B，五目标逐库差异保持在
0%–0.86%。两次artifact/symbol audit均通过，Windows动态导出仍只有
`JNI_OnLoad`。

### Field internalization

目标：当一个 Java field 的所有可观察访问都能被严格证明位于最终 native path 时，把状态迁移到 ClassLoader-isolated storage，并从 output classfile 删除字段声明。primitive 状态可位于 native raw-bit slot；reference/array 状态仍必须位于 JVM heap。

`protection.ir.fieldInternalization` 已与实现一起进入 schema，必填、默认 `false`。protection/IR root 都启用时，`CLOSED_WORLD` 直接满足 world requirement；其他 world 在实际 build 前提示 `fieldInternalization requires CLOSED_WORLD, continue? (Y/N)`。Y 只授权本次 invocation 使用 current-JAR-only reference scope，不改写配置的 `worldModel`，也不扫描 `classPath`；N 或 EOF 在创建 workspace、进入 pipeline 或调用 Zig 前退出。CLI 用 `System.console` 加 `isatty(stdin)` 兼容普通 terminal 与 PTY，并接受预先 pipe 的明确答案；无人值守且无输入时立即按 EOF 退出，不会挂住 CI。`--validate` / `--dry-run` 不执行该分析，只输出/记录 `confirmationRequired` warning。它是会改变 reflection surface 的显式 opt-in：批准字段不再出现在 `Class.getDeclaredField(s)` 中。

当前 v1 分为mutable slot与compile-time constant两条路径，并进一步要求：

- mutable slot接受input base class中的`private static boolean/byte/short/char/int/long/float/double`与JVM reference/array字段；它们必须非final/volatile/synthetic/enum-generated，无ConstantValue、Signature、annotation/type-annotation。
- compile-time constant路径接受`private static final` classfile `ConstantValue`。显式same-owner primitive读取在protection前折叠为typed SSA constant且无runtime slot；零field-reference primitive/String declaration可删除。显式String `GETSTATIC`因JVM intern/object identity边界保留。任意write、cross-owner/non-LLVM accessor或dynamic observer拒绝。已静态解析的观察访问精确阻断对应field；已知owner但成员未知的lookup/scan阻断该owner；观察值来源未知时全局fail closed，不能因caller owner不同而删除仍可被观察的常量字段。
- 两条路径的字段自身都不能被`<clinit>`访问，owner不能带serialization语义或multi-release counterpart。仅存在无关字段初始化的`<clinit>`不再owner-wide阻断。
- 每个 accessor 都必须是 same-owner static 或 instance method，最终 method status 为 `nativeLowered`，且 final implementation path 是支持对应 internalized-storage ABI 的 `LLVM_NATIVE_PATH`。instance wrapper必须传递field的declared defining `jclass`，不能用receiver runtime class作为sidecar key；普通 template、unselected/`skipped`、classpath/cross-owner access 任一存在都保留 JVM field。
- `CLOSED_WORLD` scope 扫描 input 与提供的 classpath；current-JAR-only scope 只扫描 input。后者忽略 owner 不在 input JAR 的 unresolved external field reference，同 symbolic owner 的 unresolved reference以及 reflection/Unsafe/VarHandle/MethodHandle/JNI/serialization/agent field-observer surface仍拒绝候选。已证明为普通method target的MethodHandle invocation和单纯`forName`/`loadClass`不单独阻断；来源未知的handle/bootstrap target及任何新bytecode定义入口仍global fail closed。
- Observer provenance是有界数据流：不含observer call的method在ASM frame前返回，
  合流最多保留8个producer，超限吸收到canonical unknown；单次查询在嵌套
  resolver间共享4096-step/128-depth budget。超限只损失精度并以GLOBAL/null
  拒绝internalization，不得超时后继续做乐观exact判断。
- approved mutable access改写为带exact storage-kind的opaque slot。primitive storage使用defining `jclass`的`jweak` + `IsSameObject`做ClassLoader隔离，以`_Atomic uint64_t` relaxed raw-bit load/store避免C data-race UB；`boolean`写入取low bit，`byte/short/char`分别执行8/16-bit截断与sign/sign/zero extension，`float/double`经LLVM `bitcast`保存raw bits。compile-time constant则通过raw-bit-safe SSA/LLVM constant emission承载，不创建native state。
- reference/array storage 由唯一生成的 `<embeddedLibraryDirectory>/Loader.class` 按需直接继承 `ClassValue`，为每个 defining `Class` 缓存一个 `Object[]`。每个 native function activation 使用 native stack cache cell，在首次实际执行字段访问时惰性获取一次 local ref并在退出时释放。所有批准访问都由最终 native implementation通过 JVM ObjectArray API执行，保留 GC barrier、Java identity和共享状态；不创建 native strong global ref，也不把 `jobject` 转成整数或裸指针。
- final native plan、packaging field removal 和 artifact residual-reference audit 使用同一批准 plan并全部 fail closed。

独立 `reports/field-internalization-report.json` 使用 hash-only field id 记录 `INTERNALIZED` / `KEPT`、internalization storage、storage kind/location、native slot/reference index、access/final-path、primitive/reference/constant/cache/lifecycle policy、field removal 和 reason；compile-time constant值本身不得进入report。`worldAnalysis` 另记录 configured world、实际 scope、authorization、classpath 是否被扫描以及 external-observer policy，不能把 current-JAR-only 伪装成 closed world。gated real-Zig host E2E覆盖支持类型、窄整数边界、float/double raw bits、Object identity/null/GC strong hold、并发和双 ClassLoader；六目标专项覆盖 LLVM calls、primitive storage、ClassValue/ObjectArray bridge、build graph、privacy和 export。non-host runtime与 deterministic real-Zig dual-run仍待补。

2026-08-01 18:36 的 v2 五目标实测把 `MelodyPlugin.userDigest`、
`handshakeDigest`、`yggdrasilDigest` 三个 `private static byte[]` 从 `KEPT`
改为 `INTERNALIZED`：三者均由 static `createCombinedDigest()` 读取、instance
`postApply(...)` 写入，最终走 `jvmClassValueSidecar`，并已从 output class
删除。全局 internalized field 数由 3 增至 6；71 个 selected method 仍全部
`nativeLowered`、0 `skipped`，五个 selected target、artifact/symbol/plaintext/
residual audit 全部通过。独立 host real-Zig child-JVM fixture 另验证了基类与
子类 receiver 共享 declared-owner sidecar、第二个 ClassLoader 保持隔离。

### Method internalization

目标：当一个selected method的全部input-visible入口都已经位于最终LLVM native closure中时，移除Java method declaration与`RegisterNatives`映射，使静态分析者不能再从classfile或registration表直接获得这层边界。它仍是JVM-hosted native lowering，不隐藏动态执行时的JNI/JVM语义，也不把该变换当作安全边界。

`protection.ir.methodInternalization`是schema v1必填boolean、默认`false`。required `protection.ir.publicMethodInternalizationAllowList`是exact method selector数组、示例默认`[]`；class selector、wildcard和重复项在config阶段失败。它与field internalization使用独立whole-program requirement：`CLOSED_WORLD`直接运行；其他world在build前提示`methodInternalization requires CLOSED_WORLD, continue? (Y/N)`。Y只授权本invocation的current-input-JAR-only call/override/observer分析，不修改`worldModel`，并明确忽略configured classpath与JAR外caller/subclass/reflection/JNI/agent observer；validate/dry-run不读取stdin。该Y可授权private/protected及exact allowlisted public static，不能授权public instance。

当前v1严格候选：

- final implementation必须为ordinary Code-bearing `LLVM_NATIVE_PATH`和原始`nativeOriginal` strategy；constructor、class initializer、interface、package-private、synchronized、bridge/synthetic与multi-release owner不适用。
- 支持private/protected static；static caller可以cross-owner。支持same-owner private/protected instance；`invokespecial`必须exact，`invokevirtual`必须由当前analysis scope证明只有该target。cross-owner instance与interface dispatch保留Java入口。
- public只有命中一个无wildcard exact allowlist entry才成为候选。public static可使用declared `CLOSED_WORLD`或本次Y授权的current-JAR-only scope；public instance只接受declared `CLOSED_WORLD`，pipeline解析input与全部configured classPath并构建combined observer hierarchy/call surface。combined world缺失任一superclass/interface时，hierarchy保留conservative artifact与`MISSING_EXTERNAL_CLASS` diagnostic，但public instance候选以`METHOD_INTERNALIZATION_PUBLIC_INSTANCE_ANALYSIS_WORLD_INCOMPLETE`保留Java入口；该门槛不整批禁用public static/private/protected候选。public instance不要求method/class为final，也不因存在可覆写slot本身拒绝，但每个调用点必须exact解析到候选且caller仍须same-owner；实际造成non-exact dispatch的override会保留Java入口。
- 每个observed caller必须有final LLVM implementation，并在final plan中提供same-owner direct target、static-call bridge或exact virtual-dispatch bridge。零caller、unselected/`skipped`/template caller均不删除。
- scope内已知external Java entry、继承symbolic owner引用、已解析exact reflection target、direct/LDC MethodHandle、invokedynamic/递归ConstantDynamic bootstrap reference和EnclosingMethod metadata都会拒绝候选。无法穷举的reflection/JNI/agent动态观察面不再对exact allowlisted public做全局一票否决：它必须进入warning/report，并由exact allowlist与declared/current-JAR world授权明确接受。current-JAR-only public static还必须明确报告configured classpath及全部JAR外caller/observer不在分析范围，不能把范围外事实表述为不存在。

批准项仍以method outcome `nativeLowered`报告，但`rewriteStrategy=internalNativeOnly`、`javaMethodPresent=false`、`registrationPresent=false`。默认`retentionMode=internalNativeOnly`且LLVM body继续进入final compilation并保持hidden linkage；随后若恰有一个direct call site且callee能证明pure scalar/non-throwing、无field/nested-call/monitor/JNI-owned-reference、非递归且不超过96条instruction，则physical retention改为`coalescedNativeOnly`并将body合并进caller。A→B→C chain按bottom-up轮次继续合并，所有descendant implementation/report decision同步重定向到最终physical root；每caller最多64个site，轮数不超过internalized method数。initializer-plan caller仍显式保留standalone body；inline rewrite保留caller内其余call的call-indirection metadata，证明不完整则保留standalone body。registration与method-table hiding只消费其余registered implementations。same-owner scalar direct call继续走validated LLVM ABI；cross-owner static与exact same-owner instance helper改为调用build-scoped hash-only internal wrapper，不执行`GetMethodID`/`Call*MethodA`。reference/owned/pending-exception target使用nested local frame：descriptor-aware解包、`PushLocalFrame`、normal `PopLocalFrame(result)`提升、或清除并跨frame提升原exception后严格`Throw`恢复；恢复失败`FatalError`。

Packaging使用同一immutable logical plan原子删除MethodNode。final artifact audit阻断批准method的declaration、MethodInsn、LDC Handle、invokedynamic/ConstantDynamic bootstrap和EnclosingMethod residual；final-plan validator同时阻断残留registration或缺失native caller closure。独立immutable coalescing plan另要求`coalescedNativeOnly`没有callee LLVM function/declaration/reference、generated-C wrapper或workspace source symbol。

## Layer 2: LLVM Module Model Protection

LLVM IR 混淆不做文本 regex 后处理。rewrite 后应先把 backend 输出拆成一个轻量 LLVM module model，再由 emitter 输出 `.ll`。

推荐包：

```text
xyz.melodysky.backend.llvm.model
xyz.melodysky.backend.llvm.pass
xyz.melodysky.backend.llvm.protection
```

推荐 pipeline：

```text
IrProgram
  -> LlvmModuleModel
  -> LlvmModulePassPipeline
  -> LlvmTextEmitter
```

### LLVM module model

推荐模型：

- `LlvmModule`
- `LlvmFunction`
- `LlvmBasicBlock`
- `LlvmInstruction`
- `LlvmTerminator`
- `LlvmGlobal`
- `LlvmType`
- `LlvmValue`
- `LlvmLinkage`
- `LlvmVisibility`
- `LlvmAttribute`
- `LlvmComdat`
- `LlvmMetadata`

边界：

- model 只表达需要生成的 LLVM subset，不追求完整 LLVM parser。
- model 必须支持 stable ordering。
- text emitter 只负责把 model 打印成 `.ll`。

当前 v1 的 LLVM name obfuscation 通过共享 `LlvmNameMangler` 接入 planner、per-class LLVM lowering、Zig workspace `.ll` 和 JNI wrapper C。启用 `protection.llvm.nameObfuscation` 时，Java method implementation symbol 变成 deterministic `j2ll_f_<sha256>`，C wrapper 只调用该 hidden linkable symbol；不再通过后置 `.ll` 文本替换重命名。

Native-unwind omission也遵守同一model边界。Final LLVM pass完成后，function与
instruction使用闭集`PROVEN_ABSENT` / `REQUIRED` / `UNKNOWN`表达native unwind
semantics；兼容/raw shape默认`UNKNOWN`。`LlvmNativeUnwindAnalyzer`只分析final
canonical module，`LlvmModuleEmissionPlan`把proof与该model绑定。Retained text始终可以
发出；只有全部evidence为`PROVEN_ABSENT`时才允许结构化发出为function definitions增加
`nounwind`的第二文本变体。两种文本不对应两份authoritative model，也不得靠`.ll` regex
或字符串替换产生。

### LLVM 级混淆候选

适合 LLVM 层做：

- LLVM function/global name de-semanticization。
- helper/runtime ABI 名称混淆。
- indirect call lowering 的 native-level 形态。
- opaque predicate 的 LLVM-level 形态。
- global/string section layout 混淆。
- basic block order perturbation。

不适合 LLVM 层做：

- Java virtual dispatch 语义决策。
- class initialization 语义推断。
- exception/finally/monitor 语义修复。
- 根据 `.ll` 文本搜索替换实现混淆。

推荐类：

- `LlvmModulePass`
- `LlvmModulePassPipeline`
- `LlvmProtectionPipeline`
- `LlvmNameObfuscationPass`
- `LlvmOpaquePredicatePass`
- `LlvmBlockLayoutPerturbationPass`
- `LlvmIndirectCallPass`
- `LlvmGlobalLayoutPass`

三个原待实现字段现在都已接入 validated LLVM module pipeline：

- `LlvmBlockLayoutPerturbationPass` 固定 entry block，只按 seed 改变至少三个 block 的 function 中 non-entry block emission order；terminator/phi/reference 不变。它保证 generated `.ll` 的 order 发生变化，但不保证 LLVM optimizer/linker 后的最终 machine-code layout。
- `LlvmOpaquePredicatePass` 对 conditional branch 加入只使用 defined integer operation 的恒真 gate，再与原 condition 合并。它不依赖 poison/undef/overflow UB；但当前恒真表达式可被 `ReleaseSafe` optimizer 折叠，因此不能宣称 final binary 一定保留该 predicate。
- `LlvmGlobalLayoutPass` 只在原 candidate slots 间重排完整的 `private`/`internal` LLVM globals；definition、initializer、alignment、section、mutability、reference 和 non-local/retention root 保持不变。它当前不处理 generated C 中的 registration、string carrier 或 native-field tables。

三者都在 input/output 上运行 `LlvmModuleValidator`，并产生 `LLVM_BLOCK_LAYOUT_PERTURBATION`、`LLVM_OPAQUE_PREDICATES` 和 `LLVM_GLOBAL_LAYOUT` report row。focused model/text、Windows real-Zig child-JVM pass-RAN/parity/retention，以及六目标共享 LLVM build-graph/content/privacy/export evidence均已通过。linkage/visibility normalization 不是可配置 LLVM protection pass：Java implementation function 和 protection table 的 hidden/internal linkage是 backend baseline。

测试：

- model -> text golden test。
- pass deterministic seed test。
- pass 后 verifier/preflight test。
- generated `.ll` symbol audit。

## Layer 3: Binary Symbol Visibility And Strip

该层发生在 native build/link/package 阶段，和 IR pass 分开。

hidden LLVM linkage 和最终 dynamic export allowlist audit 是不可关闭的 JVM-hosted native 基线。schema v1 不提供 `protection.llvm.visibilityHardening`；关闭 protection master、LLVM protection 或 binary hardening 都不能允许 Java implementation、helper、dispatcher 或 protection table 进入 dynamic export。strip/remove-PDB 等额外 release hygiene 仍由 binary 配置控制。

`protection.binary.retainUnwindInfo`是requested final-native unwind policy，不是一个
能盲目覆盖所有输入的linker switch。设为`false`时，Linux/macOS generated-C compile
unit添加`-fno-unwind-tables -fno-asynchronous-unwind-tables`；final canonical LLVM
module只有在上述proof完整且安全时才由target build选择`nounwind`文本变体。
`REQUIRED`、`UNKNOWN`、proof不完整或unmodeled object input会保留对应unwind surface。
Windows因SEH始终强制保留；`--debug`和config requested retain也选择retained LLVM
variant。不能假设Zig module unwind setting或generated-C flags会修改经`addObjectFile`
输入的`.ll`。manifest必须按target同时写generated-C decision、LLVM omitted/retained
module counts、unmodeled object count、final omission expectation与reason，防止强制例外、
proof fallback或debug override被误解为config未生效。

当target plan明确`finalUnwindOmissionExpected=true`时，链接后section audit是blocking：
Linux ELF的`.eh_frame`/`.eh_frame_hdr`或macOS Mach-O的`__eh_frame`/
`__unwind_info`任一非空都使native build失败。PE的`.pdata`/`.xdata`同样结构化检查并
进入report，但Windows policy不要求删除。若plan因EH/unknown/object input保留，则section
存在不是失败；报告必须说明是proof-driven retention，不能把“config=false”误报为全库必然
没有unwind metadata。

两项size policy不能放宽上述protection基线：

- conditional libc只有在最终generated-C闭包不含已知allocation/memory/
  string routine时才使用`-ffreestanding -fno-builtin`/`.link_libc=false`。
  implicit declaration和shared-library undefined symbol仍fail closed；因此新增未分类
  external call不能静默进入成品。Linux/Windows可形成无libc/CRT import的库，
  Windows使用最小generated DLL entry。macOS仍强制platform `libSystem`例外，
  report不得将它声称为无系统依赖。
- machine outliner只对Linux/macOS generated-C `ReleaseSmall` unit开启，并使用
  16-byte最低收益阈值过滤低收益的短片段共享；Windows因SEH directive边界禁用，
  per-class LLVM `.ll`不参与。这个后端变换只合并
  目标机重复指令序列，不改变JNI/export ABI，也不允许source generator重新
  引入generic decoder、plaintext cache或集中metadata table。generated-C source audit与
  final binary/plaintext/export audit仍为blocking；outliner的攻击成本影响只能用实际
  binary/Ghidra证据声称，不从source体积减少直接推导。

目标：

```text
Java method -> internal LLVM function / hidden symbol
JNI / C ABI wrapper -> exported symbol only
```

推荐包：

```text
xyz.melodysky.toolchain.symbols
```

推荐类：

- `ExportedSymbol`
- `ExportList`
- `SymbolVisibilityPlan`
- `SymbolVisibilityPlanner`
- `SymbolAudit`
- `StripPlan`
- `StripCommandPlanner`
- `PlatformSymbolPolicy`
- `ElfSymbolPolicy`
- `MachOSymbolPolicy`
- `CoffSymbolPolicy`

### Platform policy

Linux / ELF：

- 默认 hidden visibility。
- 使用 version script 或 linker export list。
- release build 使用 strip unneeded。

macOS / Mach-O：

- 使用 exported symbols list。
- 只导出 JNI / C ABI wrapper。
- release build strip local symbols。

Windows / COFF：

- 使用 `.def` 或 linker export list。
- 只导出必要 JNI / C ABI wrapper。
- release artifact 不生成或不打包 PDB。
- 构建产物中清理 `.pdb`。

### Symbol audit

最终动态库必须跑 symbol audit：

- exported symbols 必须是 allowlist 子集。
- Java method internal symbol 不得导出。
- helper/internal dispatcher 不得导出，除非 runtime ABI 明确要求。
- Windows artifact 不包含 PDB。

测试：

- platform export list generation test。
- symbol audit allowlist test。
- hidden Java method symbol test。
- Windows PDB cleanup test。

## Implementation And Evidence Status

Schema v1 当前声明的 IR/LLVM protection booleans 均已有真实实现，不再有这批字段只能 warning + ignore 的状态。原 implementation checklist 的8项加上method internalization已进入不同层：

- program/IR/final native plan：`methodInlining`、`methodSplitting`、`callIndirection`、`fieldInternalization`、`methodInternalization`。
- packaging/native registration：`methodTableHiding`。
- LLVM module model：`opaquePredicates`、`blockLayoutPerturbation`、`globalLayout`。

这些都是明确受限的 v1 子集，不代表任意 method/module shape 都会运行；无候选或敏感 shape 继续以稳定 `SKIPPED` reason 保守处理。原8项已有通过的 Windows real-Zig host child-JVM 和六目标 feature-specific structural evidence；method internalization也已通过focused、Windows real-Zig child-JVM与真实六目标matrix，并在当前v2五目标构建中完成12个method/59个remaining-registration的业务artifact证据，non-host runtime仍待补。逐项边界和证据状态见 [`protection-implementation-checklist.md`](protection-implementation-checklist.md)。

## Configuration

推荐配置模型：

```text
ProtectionConfig
  enabled
  seed
  irProtection
  llvmProtection
  binaryProtection
```

每个 IR/LLVM pass 字段直接使用 boolean：`true` 启用，`false` 禁用；不再使用只包含 `enabled` 的嵌套对象。

Schema version 1 不提供 strength/intensity knob、per-pass seed override、include/exclude method filter、protection-specific unsupported-method policy 或 `requiredNative`。需要按方法筛选时，先通过 `whiteList` / `blackList` 控制 lowering 范围；需要 deterministic 输出时，使用全局 `protection.seed`。

Schema v1 的 IR pass booleans 都是必填字段：`controlFlowFlattening`、`fakeBranches`、`basicBlockSplitting`、`constantEncryption`、`stringEncryption`、`methodInlining`、`methodSplitting`、`callIndirection`、`fieldInternalization`、`methodInternalization`、`methodTableHiding` 和 `blockNameObfuscation`。其中 `fieldInternalization`与`methodInternalization`默认 `false`，其他已实现 pass 的 examples 默认可启用；`fakeBranches`、`basicBlockSplitting`、`blockNameObfuscation` 分别调度独立 pass。`publicMethodInternalizationAllowList`是同一IR config中的required非boolean exact-authorization数组，默认`[]`。

LLVM pass booleans 为 `nameObfuscation`、`opaquePredicates`、`blockLayoutPerturbation`、`indirectCalls` 和 `globalLayout`。`visibilityHardening` 已从 Config/schema 删除；hidden LLVM linkage 和 export allowlist audit 不接受关闭。

启用/可用性语义：

- 默认配置启用不改变Java reflection surface的已实现 protection pass；会移除reflection-visible member的`fieldInternalization`与`methodInternalization`例外，默认关闭。
- 当前 schema v1 的已知 IR/LLVM pass 字段都已实现。未来 schema 若声明已知但当前 build 不可用的 pass，仍必须 warning + ignore，不能 silent skip。
- pass 对某个 method 不适用时，只跳过该 protection pass 并 warning；不要因此改变 method 的 `nativeLowered` / `skipped` outcome。只有 compiler/runtime implementation本身无法保持语义时才把 selected method 标记为 `skipped`。
- pass 缺少硬依赖时，例如 `classPath`、JDK metadata、target toolchain capability，preflight error 并提示补齐输入或关闭该 pass。

当前 `reports/protection-report.json` 为 stable schema v1，按 pass 记录 `passName`、`layer`、`status`、`reasonCode`、`affectedMethods`、`affectedSymbols` 和 `seedHash`，并在 root `coverage` 中稳定聚合 hash-only per-subject `requested`、`applicability`、`affected`、`status` 与 `reasonCode`；`FAKE_BRANCHES`、`BASIC_BLOCK_SPLITTING` 与 `BLOCK_NAME_OBFUSCATION` 是独立 pass row。IR per-method producer直接持久化真实applicability，`METHOD_INTERNALIZATION`也由final-plan producer逐method写`APPLICABLE`/`NOT_APPLICABLE`；尚未迁移的program/LLVM producer使用`UNKNOWN`，report adapter不得从`SKIPPED`推断。program/backend协作项还会分别记录`METHOD_INLINING`、`METHOD_SPLITTING`、`IR_CALL_INDIRECTION`、`IR_CALL_INDIRECTION_BACKEND`、`FIELD_INTERNALIZATION`、`METHOD_INTERNALIZATION`、`METHOD_TABLE_HIDING`、`LLVM_OPAQUE_PREDICATES`、`LLVM_BLOCK_LAYOUT_PERTURBATION`和`LLVM_GLOBAL_LAYOUT`。raw protection seed不写入report、final JAR metadata、library default name或summary。已接实status使用`RAN`/`SKIPPED`/`FAILED`；method/module级不适用使用稳定reason code，包括`METHOD_INTERNALIZATION_NO_CANDIDATE`及其逐method拒绝reason。Protection pass输入非法时仍保留build-level validation error；candidate validation失败则fail closed。

Protection 不再为 unsupported selected Code-bearing method保存或编码原 Java bytecode。Final implementation plan只能把这类 method标记为 `nativeLowered` 或 `skipped`：前者必须由 LLVM、生成式 template/stub或 approved JNI/runtime helper完整承担语义；后者保留原 method body、不注册且不生成 native bytecode副本。No-Code eligibility没有 method status。唯一 Loader只负责 native loading/registration，并在 field plan需要时加入 `ClassValue<Object[]>` sidecar；没有 class-definition/blob-decoding API。Artifact audit必须阻断任何 embedded method bytecode、generated fallback class/carrier/decoder重新进入 native或 JAR。

Default build在 final plan形成后、Zig workspace创建前按稳定顺序列出 skipped methods并明确其 Java bytecode会保留，然后提示 `continue? (Y/N)`。只有显式 `Y`继续；`N`/EOF终止。`--validate` / `--dry-run`不读取 stdin；dry-run 记录 `skippedMethodAnalysisPerformed=false`、`skippedMethodConfirmation=deferredUntilDefaultBuild` 和 `skippedMethodConfirmationDecision=confirmationRequiredIfSkippedMethodsAreFound`。该 gate与 protection pass自身的 per-method `SKIPPED` status不同：protection pass不适用不会自动改变 method outcome。

Release-readiness reports record artifact hygiene plus protection-sensitive native/helper/skipped boundaries as stable feature/opcode/status/reason/testCoverage rows，so enabled protection不会隐藏 unsupported shape或 silent skip。Protection reports保留 hash-only seed identity和 `sensitivePlaintextFacts`；artifact audit消费 connected LLVM/template/helper facts，阻断 covered generated C/LLVM/native/JAR/report surface中的 plaintext泄漏。

独立 attacker-audit 还提供两个不改变编译产物的回归面：debug LLVM 中精确
`%j2ll_v_<24-hex> = add i64 0, <token>` carrier 的 name/numeric-token
hash-only 跨构建复用统计，以及 final native/generated-C 字节数与 dual-build
delta。默认随机的非空 carrier 集要求两类交集都为零；显式 seed 模式要求集合
精确一致。该 scanner 不把普通用户 `%j2ll_v_*` 名称当作 carrier，也不在报告中
写 raw token。大小 evidence 当前用于发现无界膨胀和比较安全收益，不设一个会
绕过安全审计的全局 hard cap。相对`build_2026-07-27_22-47-23`基线，
`09-53-53` / `09-56-32`的flat native分别增加6.435% / 6.747%，Windows DLL
增加6.408% / 6.636%，generated C增加9.341% / 9.077%，LLVM增加0.0106% /
0.00015%。该实测给出当前样本的成本窗口：“native约+6%至+7%、generated C约
+9%”；它不是所有输入/平台的全局上限。后续跨平台/样本回归继续跟踪，但不得
为了压回体积而放宽安全gate。

生产 native builder 还从 final validated LLVM module model 收集真实 referenced
helper symbols，并按 dependency closure 只发出需要的 host-JNI runtime source
families；LLVM declaration 本身不作为 root，stable symbol 必须精确命中已知集合，
build-local symbol 必须有严格 declaration evidence。选中 binding-driven emitter
后还会按其实际写出的 entries 补齐跨 family dependency。若 model evidence 不完整，
或出现未知 `j2ll_rt_*` / `j2ll_h_*` reference，则 fail closed 到保守全量 source。直接调用
`HostJniCSourceGenerator` 的兼容/fixture API 也默认保守全量，只有生产 builder
显式传入 final-model reachability plan 才能裁剪。该项减少未使用 source 的编译工作
与产物体积。2026-07-28 同输入、同显式 seed、Windows x64 单次 A/B 中，
generated C 减少 18.665%，DLL 减少 28.121%（`.text` raw 减少 30.745%），
output JAR 减少 2.191%；LLVM 总字节不变，57 `nativeLowered` / 14 `skipped`
与 artifact/readiness 结果也不变。wall time 减少 9.383%，但单次 wall-clock
只作为方向性证据，不外推为跨机器或跨平台保证。

All-on protection release suite至少覆盖一个 LLVM-native method、一个 JNI/runtime helper-backed native method、一个明确 `skipped` boundary、dynamic reflection/MethodHandle/lambda/Unsafe/VarHandle/wait-notify boundary、confirmation Y/N/EOF和 artifact-audit expected failure。Artifact audit分别验证registered native的implementation/registration closure、`internalNativeOnly`的hidden implementation/native-caller closure与零classfile residual、以及`skipped` method的原 body保留且无 registration，并拒绝任何 selected method bytecode副本、hidden symbol export、legacy output path、metadata/native SHA mismatch和 packaged PDB。

## Required Tests

每个 protection pass 至少需要：

- deterministic seed test。
- disabled pass no-op test。
- focused IR/model golden test。
- runtime parity test，如果改变 executable behavior path。
- validator/preflight test。
- dump test，确保调试输出可读。

binary protection 至少需要：

- export list generation test。
- per-platform command/flag generation test。
- final LLVM unwind proof/dual-emission test，以及ELF/PE/Mach-O section inspection和expected-omission blocking test。
- symbol audit test。
- artifact cleanup test。
