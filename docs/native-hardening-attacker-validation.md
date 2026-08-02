# Native hardening and attacker validation

本文档把 j2ll native 产物的防逆向目标、实战基线、实现清单和验收方式固定下来。它不是“无法破解”的承诺；攻击者完全控制客户端、JVM 和进程时，JNI 参数、`RegisterNatives` 映射及运行时明文最终都可以被动态观察。我们的目标是：

- 不在产物里留下可直接恢复原方法语义的第二份 Java bytecode。
- 消除一次定位即可批量恢复全部 metadata、字符串或方法映射的静态捷径。
- 让默认正式构建具有 per-build 多样性，使上一构建的地址、表布局和提取脚本不能直接复用。
- 保持 Java object、GC、exception、monitor 和 class initialization 语义仍由 JVM/JNI 管理。
- 用自动化“攻击者视角”测试衡量恢复成本，而不是用反编译画面是否混乱来判断。
- 在语义和审计边界不退让的前提下控制代码膨胀：静态分析难度优先，但新变换
  必须优先使用table-free storage、bounded topology与同值组内复用，并记录
  final native/generated-C实际字节数和跨构建delta。

## 威胁模型与非目标

攻击者可以读取 JAR/DLL/SO/dylib、运行 Ghidra、调试或 hook 进程、构造最小 JavaVM/JNIEnv，并比较多个构建。攻击者不能修改 j2ll 构建时的可信输入或构建机。

下列事实不能作为安全边界：

- Java class、method 和 field descriptor 仍可从保留的 classfile 结构中获得。
- JVM 最终必须看到 `RegisterNatives` 的真实 name、descriptor 和 function pointer。
- JNI 调用前需要形成真实 class/member metadata，`NewStringUTF` 前需要形成字符串明文。
- 固定 XOR、节区改名、UPX、垃圾指令、anti-debug 或反虚拟机检测不能代替结构性防护。

长期秘密和最终授权不应只依赖客户端混淆，但服务端协议不属于 j2ll 的实现范围。

## 2026-07-27 实战基线

基线样本来自 v2 默认构建，并用 Ghidra headless、静态提取脚本和伪造 JavaVM/JNIEnv 探针验证：

| 指标 | 基线结果 |
| --- | ---: |
| fallback class/blob | 0 |
| 全局 encoded metadata 记录 | 695 |
| 可批量解码为有效 UTF-8 | 695 / 695 |
| native registration binding | 71 |
| 无真实 JVM/应用即可捕获的 binding | 71 / 71 |
| wrapper 有唯一直接 callee | 60 |
| 小型 inline/no-direct wrapper | 11 |
| dynamic exports | `JNI_OnLoad`, `j2ll_register` |
| control-flow flattening affected methods | 0 / 71 |
| basic-block splitting affected methods | 6 / 71 |
| fake branches affected methods | 7 / 71 |
| block-name obfuscation affected methods | 69 / 71 |
| string encryption affected methods | 24 / 71 |

这说明 native lowering 已提高单方法恢复成本，但仍存在三条批量捷径：

1. `j2ll_register` 是可直接调用的稳定导出入口。
2. `CMetadataStringObfuscator` 生成统一 `{pointer,length,key}` 目录，并在注册前一次性解码全部字符串。
3. `seed: null` 由稳定路径和 selector 派生；metadata codec、native symbol 和多种物理顺序还不消费 build identity。

## 2026-07-27 第二轮只读攻击审计

在第一次实现 H1-H8 的结构后，又按“不要信任源码中的保护意图，只看最终
攻击路径”做了一轮审计。下列问题会阻止更新 v2 或把对应清单标成 `DONE`：

| 优先级 | 发现 | 处置 |
| --- | --- | --- |
| Critical | 仅含 `CONST_CLASS` 的方法在 planner、LLVM 与 C bridge 间对 `JNIEnv*` 的判断不一致 | 使用共享 LLVM function ABI policy；planned/inferred ABI 不一致时在 Zig 前失败，并覆盖 static/instance class literal |
| High | instance wrapper 曾把 receiver runtime class 当作 internalized static field 的 defining class | wrapper 通过 registered native method 的 defining-loader context 解析声明 owner；不再使用 `GetObjectClass(self)` |
| High | reference-producing JNI helper 在 native loop 中可无界累积 local ref；循环内 `jvalue[] alloca` 也会持续增长native stack | 对每个method生成并验证dynamic ownership/release plan，在normal/parallel/handler/explicit-throw edge精确释放；ref-producing registered callee改走JNI nested activation；`jvalue[]` scratch只在activation prologue分配一次。无法证明的shape明确 `skipped`，reason 为 `UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME` |
| High | 单一 native-text decoder、固定常量与相邻 seed shares 仍可被一个提取器批量识别 | 改为 build/site-scoped codec plan；审计 decoder shape/fanout、seed/cipher 共址和跨构建自动恢复率 |
| High | method-table hiding 仍留下 token 数组与 function relocation 数组 | 最终产物不得持久化 token→function join database；owner-local 临时 `JNINativeMethod` 直接按 build-diverse 顺序构造并清零 |
| High | 所有 LLVM binding 都采用相同 wrapper→volatile slot→bridge→LLVM 拓扑 | 删除持久 function-pointer slot，按 build/binding 选择不同且语义等价的 local bridge shape；用最终 binary mapping reuse rate 验收 |
| Medium | local-ABI cookie 同时出现在 caller/callee，可作为 join key；旧 mismatch 路径静默返回默认值 | cookie 从 ABI 移除，只排列真实逻辑参数；任何未来 ABI 完整性失败必须抛出 linkage error，不能返回合法默认值 |
| Medium | rollback 忽略 `UnregisterNatives` 的 JNI 返回值 | 同时检查返回码与 pending exception；无法确认回滚完成时 fail closed，不能宣称 atomic success |
| Medium | runtime、registration 与 business-string 最终 material 未完全分域 | 独立传递 registration layout/text key、business native-text key 和 runtime metadata key，并测试单域扰动 |
| Medium | 一个 host C object 可能保留 runtime-dead helper | C 使用 function/data sections，final link 强制 section GC；最终六目标 artifact 再做 content-retention 验收 |

其中动态 hook `RegisterNatives`、在调用点抓取 JNI metadata/字符串明文仍属于
威胁模型内不可消除的观察能力。上表的目标是删除静态批量捷径和跨构建稳定
join key，不是把 JVM 必须看到的数据宣称为秘密。

## 2026-07-27 优化后产物复验

第二轮 source audit 通过后，真实五目标 `ReleaseSafe` 构建仍曾被最终 artifact
audit 阻断。该失败揭示了两个不能只靠 generated-C 目视检查发现的边界：

| 优先级 | 最终产物发现 | 已落实处置 |
| --- | --- | --- |
| High | ciphertext、length 与 codec key 全为编译期常量时，Zig/Clang 会把 inline decode loop 常量折叠，并在 PE/Mach-O 中重新物化 owner、descriptor 与业务字符串明文 | 所有 codec family 共用的 ciphertext read 改为 `const volatile unsigned char` lvalue runtime boundary；source audit 阻断缺失边界或 mixed direct read；`-O2` object 回归逐个扫描 UTF-8/UTF-16LE 12-byte sliding window |
| Medium | `lowering-report.json` 的完整 helper identity 携带 `j2ll_rt_string_constant|string:<literal>`，形成报告层业务字符串旁路 | helper evidence 改为 non-sensitive `helperKind` + domain-separated `helperIdentityHash`；完整 helper string 不序列化；lowering report 加入 blocking plaintext surface |
| Low | `native/zig-cache/**` 保存 flat final library 的 byte-identical duplicate，旧审计把同一命中报告两次 | cache copy 不参与 hit 枚举；flat `native/*.{dll,so,dylib}` 与 `native/zig-workspace/**` 仍逐 target 强制审计 |

修复后的同一 v2 样本完成 Windows x64、Linux x64/arm64、macOS x64/arm64
五目标构建。`artifact-audit.json` 返回
`FORBIDDEN_PLAINTEXT_ABSENT`；flat final libraries 对基线 owner、
`createCombinedDigest` 与 `MelodySky-WS` 的 raw scan 均为 0 命中。
`lowering-report.json` 的 626 条 helper evidence 均只有 kind/hash，业务文案与
旧 `"helper"` 字段均不存在。该结果证明的是静态 at-rest 门，不改变动态 hook
仍可观察 JNI 明文的威胁模型。

## 2026-07-27 第三轮 Ghidra 与动态探针复验

对 `build_2026-07-27_21-21-06` 与另一份默认随机 build 使用 Ghidra headless
PE/p-code scanner、`JNI_OnLoad` fake-JavaVM/JNIEnv probe 和跨构建 wrapper
fingerprint 做了真实攻击者复验：

| 指标 | 结果 |
| --- | ---: |
| final library 中完整 owner 明文 | 0 / 10 |
| final library 中完整 descriptor 明文 | 0 / 27 |
| final library 中 native implementation symbol 明文 | 0 / 57 |
| Windows/Linux dynamic export | 仅 `JNI_OnLoad` |
| macOS 额外导出 | `__dso_handle`、`_mh_dylib_header` |
| 旧统一 encoded directory | 0 |
| 动态 `RegisterNatives` 捕获 | 10 owners / 57 bindings |
| 相同 wrapper RVA | 0 / 57 |
| 相同 coarse wrapper shape | 57 / 57 |
| 相同 normalized resolution fingerprint | 7 / 57（12.28%） |
| generated-plan ciphertext array 精确交集 | 0 / 1261 |
| generated-plan business helper ID 精确交集 | 0 / 58 |

4 个 non-executable-data function-pointer slot 在两次 build 中数量与 structure
hash 都固定；其中 2 个由 PE TLS directory 直接确认属于 TLS callback array，
其余 2 个呈固定 runtime/CRT-like 形态但尚未完成确定归因。4 个槽都不随
57 个 binding 扩张，因此目前没有 j2ll token→function table 的证据；轻量审计
仍继续保留该指标，不能把“没有表证据”写成“所有平台槽都已完成归因”。

动态 probe 仍能完整捕获 57 个 binding，这是 JVM 最终必须观察映射的既定边界。
静态上最强的剩余路径改为从唯一 `JNI_OnLoad` 遍历 registration-rooted closure：
两层闭包约 12 个函数，Ghidra 可产生约 237,639 字节反编译 C；其中观察到
98 个 decode loop、674 个 XOR、11 个 `RegisterNatives` JNI-slot 形态、
14 个 `FindClass` 形态和 22 个 `calloc`。这些局部 codec grammar 呈现出可被
模拟执行的结构，预计仍可脚本化逐 registrar 恢复；本轮尚未完成一份静态
decoder 并批量导出全部 owner/name/descriptor，因此不能把风险推断写成已完成
的静态恢复结果。

本轮还发现了两个跨构建锚点，并在第三轮样本生成后修复源码：

- 三条 registration rollback/exception-restore 文案原本是稳定明文。现在使用
  registration 专用 text domain、按 aggregate/owner identity 独立编码，在
  `FatalError` 前才解码；generated-C audit 以
  `STABLE_REGISTRATION_DIAGNOSTIC` 阻断回归。
- debug emitted LLVM 曾保留稳定 `%j2ll_str_token_<index>` value name。现在
  value name 由 method/site/token 与 build seed 派生为 hash-only identifier；
  同 build 可复现、不同 build 改变。该修改消除的是 debug emitted LLVM 的
  稳定文本 marker；numeric token retention 仍需单独审计，不能由 value-name
  改名推导为已随机化。

这些源码修复不算作旧 `21-21-06` 样本的既有证据；必须由更新后的 final
library/generated surfaces 另行复验后，才能确认稳定明文和 marker 已消失。

## 2026-07-28 carrier、affine storage 与 bounded topology 复验

在 `enc:v2` key-bound carrier、affine ciphertext physical storage和第四种
bounded branched local ABI topology接入后，完成了下列更新验证：

- 全套 Gradle test 成功。
- `ZigCrossTargetBuildTest` 的真实 managed-Zig 六目标构建成功，包含强制
  branched topology fixture；`ProtectionCrossTargetEvidenceTest` 的真实六目标
  protection build/content/privacy/export audit 同样成功。
- v2 默认随机构建
  `build_2026-07-28_09-53-53`和`build_2026-07-28_09-56-32`均完成：
  每次都是57个`nativeLowered`、14个`skipped`、5个selected targets，
  artifact audit与release readiness均通过。
- 两次debug LLVM各识别39个精确business-string carrier；跨构建carrier name
  overlap为0，numeric-token overlap也为0，符合randomized non-empty合同。
- 相对`build_2026-07-27_22-47-23`基线，两个新构建的flat native总量分别
  增加6.435%和6.747%，Windows DLL分别增加6.408%和6.636%，generated C分别
  增加9.341%和9.077%，LLVM分别增加0.0106%和0.00015%。实现结构检查确认
  affine storage没有per-site permutation table/cipher副本；聚合size结果只说明
  当前样本的增长主要出现在generated C与最终native，不能据此精确归因到某一个
  pass。当前增幅接受，但继续作为size regression evidence跟踪，不转化为放宽
  安全gate的理由。
- 两份Windows DLL的Ghidra/fake-JavaVM probe均以exit 0完成，各观察到10 owners /
  57 bindings，且没有`j2llRegister`稳定动态导出。这验证了唯一
  `JNI_OnLoad`入口与registration probe合同，没有证明注册映射在运行时不可见。
- Ghidra normalized p-code对两份更新DLL分别得到
  `direct/multiple/unresolved=30/19/8`与`26/26/5`；第三轮基线为
  `37/10/10`。两份更新构建的57个logical binding全部可对齐，但正式mapping
  reuse只有5/57（877 basis points）：14个shape changed、28个resolution
  changed、10个在任一构建中unresolved；相同coarse shape为36/57，相同RVA为
  1/57。该结果证明branched topology进入了最终DLL并降低了单一直连比例，不代表
  structured analyzer无法继续解析剩余wrapper。

动态hook `RegisterNatives`仍然可以捕获完整映射，这是JVM边界而不是待修复的
静态export漏洞。wrapper normalized-p-code指标与动态probe是两条独立证据；
业务字符串的final-binary批量恢复率仍未在本轮测量，不能从carrier零交集、
wrapper reuse下降或“probe exit 0”推断。

## Build identity 合同

schema v1 继续使用 `protection.seed: null|string`，但语义调整为：

- `null`：正式 build 每次创建新的 256-bit 随机构建 root，同一次六目标 matrix 共享该 root。
- 非空字符串：进入显式 reproducible 模式；相同输入内容、normalized protection config 和 seed 应生成相同保护 plan。
- `--validate` 不创建随机 build identity。
- `--dry-run` 不承诺与后续默认 build 具有相同的 native 物理布局。

所有随机化由 domain-separated KDF 派生，不把一个截断的 `long` 直接复用于全部阶段。domain 至少覆盖：

- IR/LLVM pass；
- LLVM/native wrapper symbol；
- function/block/owner registration order；
- method-table token、mask 和两个独立表顺序；
- registration metadata、runtime metadata 和 business strings；
- sidecar slot 物理顺序。

报告、JAR metadata 和 diagnostics 只写 mode 与 context-bound hash，不写 root、nonce 或 raw seed。逻辑 report 排序保持 canonical，不跟随物理布局随机化。

## 实现清单

状态使用 `DONE`、`IN_PROGRESS`、`TODO`。每项只有通过对应测试和攻击者验收后才能标记 `DONE`。

### H0 — 消灭 bytecode 恢复副本

状态：`DONE`

- selected Code-bearing method 最终只有 `nativeLowered` 或 `skipped`。
- `skipped` 保留原方法，native/JAR 不保存第二份 bytecode。
- 不再生成 fallback class blob、hidden-class definition 或 embedded-bytecode decoder。
- Zig 前稳定打印所有 skipped method，并要求显式 Y。

### H1 — 内部化 registration root

状态：`DONE`

- DLL/SO/dylib 只导出 JVM 必需的 `JNI_OnLoad`；macOS 固有 runtime 符号单独容忍。
- aggregate registration root 改为 build-scoped、hidden/static internal function。
- Zig retention root、symbol allowlist、artifact audit 和报告同步收口。
- JVM 仍通过 `JNI_OnLoad` 完成全部 owner 注册。

验收：

- 六目标 export audit 不再出现 `j2ll_register`。
- host child-JVM parity 和全部 binding 注册行为不变。
- 直接按稳定导出名调用 registration 的旧探针失效。

实现证据：aggregate registration root 与 per-owner helper 均为 C `static`，Zig 只保留 `JNI_OnLoad`，symbol allowlist 与 final artifact audit 只要求 `JNI_OnLoad`（目标格式固有 runtime 符号单独容忍）。

### H1b — 删除 native-only Java method entry

状态：`IN_PROGRESS`

`methodInternalization`针对“已经只被native-lowered caller使用”的严格子集，同时删除Java `method_info`和对应`RegisterNatives` binding。public入口只有在required exact `publicMethodInternalizationAllowList`中逐method授权后才参与，不能因启用普通pass或使用宽泛lowering selector而隐式删除。它降低三条静态恢复捷径：

- classfile不再直接暴露该method的name/descriptor/access与native declaration。
- registration observer/table只包含真正仍需JVM entry的bindings。
- native call graph不再强制呈现“Java binding wrapper -> hidden body”的一一映射。

当前安全/语义边界：

- ordinary final `LLVM_NATIVE_PATH`的private/protected static，以及same-owner exact private/protected instance method可批准；exact allowlisted public进一步支持public static及same-owner exact public instance。
- static caller允许cross-owner，但必须走defining-class-aware bridge；reference result与pending exception通过nested JNI local frame promotion。
- public static可使用declared `CLOSED_WORLD`或本次current-JAR-only Y授权；public instance只接受declared `CLOSED_WORLD`及parse-complete input+全部configured classPath world。public instance不要求method/class为final，也不因存在可覆写slot本身拒绝，但所有调用点必须exact且caller仍须same-owner；实际non-exact dispatch仍保留Java entry。
- 已解析的exact reflection/MethodHandle/Handle/bootstrap/ConstantDynamic/EnclosingMethod observer、launcher/agent entry与closed exact catalog识别到的JVM/JDK callback会硬拒绝候选。callback catalog覆盖Object虚方法、Runnable/Callable、Thread/TimerTask、序列化、Comparator与常见`java.util.function`/primitive-function合同，并且同时要求真实hierarchy关系和exact descriptor；这不恢复blanket override-slot veto。catalog外第三方framework callback及无法穷举的reflection/JNI/agent动态观察面仍作为allowlist/world授权接受的风险进入warning/report；current-JAR-only还要明确记录configured classpath及JAR外caller/observer未被分析。
- `CLOSED_WORLD`或显式current-JAR-only Y授权只是分析scope声明，不是对外部agent/JNI hook不可见的安全承诺。
- implementation symbol仍存在于native code，攻击者可以通过native call graph和动态trace恢复；本项目标是减少稳定metadata与批量mapping，不是隐藏可执行语义。

在logical method internalization之上已加入第一个physical coalescing切片：若一个internal-only小方法恰有一个direct native call site，并能证明pure scalar、non-throwing、无field/nested-call/monitor/JNI-owned-reference、非递归且不超过bounded instruction budget，则直接复用严格inlining proof把callee合并进caller。lowering report改写为`coalescedNativeOnly`并记录stable caller method key；LLVM compiler、generated-C binding filter与artifact audit共同要求callee function/declaration/reference/wrapper/workspace symbol全部消失。A→B→C chain当前显式fail closed，避免nested coalescing产生悬空physical target；initializer-plan caller也暂不合并，防止caller IR变化后继续消费旧initializer plan。inline rewrite必须逐instruction保留未替换call的call-indirection metadata。reference/helper-backed target暂不为了覆盖率放宽。

验收：

- lowering report写`retentionMode=internalNativeOnly`、`javaMethodPresent=false`、`registrationPresent=false`；packaging registration groups中无该target。
- output JAR中没有declaration、`MethodInsn`、method Handle、bootstrap/ConstantDynamic或`EnclosingMethod`残留。
- selected-target库仍包含完整hidden implementation/caller closure且export allowlist不扩大。
- fake-JNI动态probe的binding数应按internalized target数量下降；剩余JVM entry仍可被hook，不把下降误报为`RegisterNatives`不可观察。
- attacker regression比较同一输入开启/关闭该pass后的classfile surface、registration bindings、wrapper分类、native/generated-C大小和runtime parity。
- 对`coalescedNativeOnly`另比较callee standalone LLVM/C/native symbol surface是否为零，并确认caller ABI在合并后重新按final IR推导。

2026-07-31当前v2实测：71个`nativeLowered`中12个转为
`internalNativeOnly`，0个`skipped`，registration由71降为59。
`LaoShuUtils.verifyJson/chatJson/updateJson/updateNickJson`四个protected
static target在input `javap`中存在，在output method table与registration groups中
均不存在；`jar.internalizedMethodResiduals`、五目标artifact/symbol/plaintext audit
全部通过。gated real-Zig fixture另在一次matrix invocation产出完整六目标动态库，
并在Windows child JVM覆盖cross-owner protected static、same-owner
protected-final instance、目标`<clinit>`先于call body执行，以及normal reference
return和两条pending-exception跨nested-local-frame恢复。

2026-08-01旧public扩展把exact allowlisted cross-owner public static与same-owner
public-final instance加入同一个real-Zig fixture：六目标一次编译/链接成功，Windows
child JVM parity验证删除后的normal reference result、pending exception与class-init
语义。旧v2业务样本曾刻意保留7个allowlisted `LaoShuUtils` public static：输入中
16个未解析reflection site使每个目标产生
`METHOD_INTERNALIZATION_PUBLIC_REFLECTION_SCOPE_UNRESOLVED`，最终仍是71
`nativeLowered`/0 `skipped`、10个`internalNativeOnly`和61个registration，artifact
audit通过。该结果是历史fail-closed证据，但“未解析reflection全局否决public”的
边界已被当前策略取代，不能再作为当前合同或验收；新回归必须分别验证exact observer
硬拒绝、unbounded动态observer warning/report，以及非final same-owner exact public
instance和allowlisted public static的删除与runtime parity。

2026-08-01 02:52的新策略回归已完成：focused tests覆盖resolved exact observer硬拒绝、
dynamic `Class.getMethod`/`MethodHandles.Lookup.find*` unsupported evidence、actual
non-exact/cross-owner拒绝、parse-incomplete hierarchy保留与closed known-JVM-callback
catalog；Windows real-Zig Dummy分别删除1个current-JAR allowlisted public static leaf，
以及declared-closed world中的non-final public instance、protected instance和protected
static共3项，child-JVM parity均通过。最新v2五目标构建仍为71 `nativeLowered`/0
`skipped`，但达到14个`internalNativeOnly`和57个registration；7个allowlisted
`LaoShuUtils` public static中`generateIV`、`encrypt`、`decrypt`和
`base64ToPublicKey`已从method table/registration删除，另外3个因没有native caller而
以`METHOD_INTERNALIZATION_NO_NATIVE_CALLER`保留。17个未解析reflection site只产生
一条aggregate accepted-risk warning，artifact/symbol/plaintext/residual audit全部通过。
五库raw总量2,570,726 B，Windows x64为496,640 B，output JAR为3,845,948 B；与前一
随机build相比变化很小，不能把差异单独归因于本策略。

本轮五库raw总量2,572,606 B，上一随机build为2,616,590 B；Windows x64
497,664 B对503,808 B，output JAR 3,850,660 B对3,878,018 B。由于两轮使用不同
随机build identity，这组下降只作方向性size evidence，不能单独归因于method
internalization。fake-JNI动态probe尚未对新DLL复跑，因此H1b保持`IN_PROGRESS`；
报告中的59个registration bindings不是动态hook验收的替代品。

### H2 — 删除全局 metadata 解码目录

状态：`IN_PROGRESS`

- 最终 generated C/native 中不得存在统一 `{data,length,key}` 全局目录。
- registration、runtime metadata 和 business string 使用不同 codec domain。
- 不允许 `JNI_OnLoad` 或 aggregate registration 一次性解码所有 domain。
- registration metadata 至少按 owner 临时解码；business string 按实际 token/call site 解码。
- generated-C 高敏感 metadata 的普通 literal 只在真实 use-site 首次到达时解码；
  同一 C function 内的同明文共享一个 activation-local slot，并在该 activation
  内最多解码一次。不同 function 不得共享 slot、明文 cache 或 encoding identity。
- 每个 function 使用聚合 scratch 与统一 cleanup hook，在 normal/early/failure
  return 时清零全部 slot；owner/table 等可明确界定更短 use window 的路径继续
  使用显式 decode/use/zero。只有低敏感普通 runtime error 文案可显式选择
  lazy-once lifetime。

验收：

- 静态审计找不到 `j2ll_encoded_metadata_strings` 或等价的全量目录。
- 调用 `JNI_OnLoad` 后不能从一个连续表批量导出全部业务字符串。
- 一个 owner 的注册失败不能促使其他 owner 的 metadata 提前解码。

已接入的 runtime metadata 切片：

- class/allocation/type-check、JVM field、method dispatch、reflection member
  lookup 与 lambda factory 都不再使用 JVM `String.hashCode()` 32-bit token。
  `RuntimeTokenMapper` 以 invocation build key、独立 domain 和完整 identity
  派生 64-bit token/纯哈希 helper symbol；同 domain 的不同 identity 若发生
  截断碰撞会在生成阶段以 `RUNTIME_TOKEN_COLLISION` fail closed。
- 生成 helper 统一使用固定 `j2ll_h_<16 hex>` 名称；operation、owner、member
  和 descriptor 只进入 KDF input，不进入 native symbol。
- 通用 runtime metadata、business string 与 registration text 现在分别消费
  `NATIVE_TEXT`、`BUSINESS_NATIVE_TEXT`、`NATIVE_REGISTRATION` 派生材料；生产
  `HostJniCSourceGenerator`/Zig builder 以三个显式 key 传递，兼容 overload
  不进入 mainline。扰动一个 text domain 不会复用另外两个 domain 的 key。
- native text 不再使用一个固定 SplitMix decoder，也不再把两个 XOR seed share
  与 ciphertext 相邻存放。每个 use identity 从 build key 派生 codec family、
  schedule、遍历方向、rotate/shift 和常量；当前四个 family 为 `WEYL_ARX`、
  `DUAL_LANE_ARX`、`FEISTEL_32`、`FOLD_ROTATE`。解码逻辑直接内联到拥有该
  scratch 的调用点，不生成一个可覆盖多个 ciphertext 的通用 decoder。
- 四种 codec family 的公共 ciphertext indexed read 必须通过 volatile-qualified
  lvalue 执行；仅清零使用 volatile 不足以阻止优化器反推出明文。审计同时拒绝
  “先做一次 dummy volatile read、真实解码仍 direct read”的 mixed shape。
- class、field、dispatch、reflection 与 lambda 已改为 concrete-binding helper。
  最终 generated C 不再生成 `j2ll_class_table`、`j2ll_field_table`、
  `j2ll_method_table`、reflection table、lambda table 或对应的全局 token
  resolver。反射/lambda metadata 只在具体 helper activation 内形成，并由
  generated-C call-local scratch policy 在所有退出路径清零。
- instance field helper 始终按 classfile declared owner 调用 `FindClass` +
  `GetFieldID`，不会因 receiver subclass shadow field 而改取子类声明。
  dispatch 对 Z/B/C/S/I 分别使用 `CallBoolean/Byte/Char/Short/IntMethodA`，
  boolean 返回归一化为 0/1。
- reflection owner 解析使用 native caller defining-loader 上下文的
  `FindClass`；descriptor 参数类型使用 owner 的 `Class.getClassLoader()`，
  不依赖 TCCL。显式三参数 `Class.forName` 保留调用方传入的 loader。
- focused evidence 覆盖 `Aa`/`BB` Java-hash collision、强制 64-bit collision
  fail-closed、跨 build symbol/order diversity、hash-only 名称、field
  primitive/reference ABI、virtual/interface narrow-return family、reflection
  defining-loader source contract，以及 lambda strict text-lifetime audit。

保留验收边界：

- 上述代码与 focused generated-C/LLVM contract 已完成；真实 Zig
  child-first-loader parity、field-shadow child-JVM、interface initialization
  ordering和更新后 Ghidra 批量恢复脚本复测仍需实机执行，因此 H2 在这些攻击者
  验收完成前保持 `IN_PROGRESS`。

### H3 — 临时 registration metadata 与清零

状态：`IN_PROGRESS`

- 每个 owner 临时构造 `JNINativeMethod[]`。
- name、descriptor 和 owner 只在该 owner 注册窗口内形成明文。
- `RegisterNatives` 返回后以不可被优化删除的 zeroize helper 清理字符缓冲和临时表，再释放。
- 失败路径执行相同清理。
- 最终 C/native 不保留 token table、function-pointer array 或 nested runtime join。每个 owner
  由 build identity 派生物理 method 顺序，在注册窗口内以 straight-line assignment
  构造唯一临时 `JNINativeMethod[]`。
- `JNI_OnLoad` 中的 registration owner lookup 直接以 slash internal name 调用 `FindClass`，利用发起 `System.load` 的 defining-loader context；TCCL 不参与 registration resolution。
- multi-owner registration 是原子的：单个 owner 的 `RegisterNatives` 可能在失败前已完成部分 method 绑定，因此 owner helper 必须先保存并清除 pending exception、对当前 owner 调用 `UnregisterNatives` 并严格验证回滚；成功后恢复原异常并把失败交给外层。外层再按逆序仅对此前已成功 owner 调用 `UnregisterNatives`，释放 owner local refs，最后恢复原始 exception 并返回 `JNI_ERR`。
- rollback 只有在每次 `UnregisterNatives` 都返回 `JNI_OK` 且没有 pending exception 时
  才算成功；任何 status/exception failure 都进入显式 fail-closed `FatalError`，避免
  native function pointer 部分保留后继续运行。
- ordinary 模式的 owner-local method order 也由 build identity 派生；hidden 模式继续使用 method-table-hiding plan 自己的 metadata/function physical order。
- rollback 与 exception-restore 的三条低敏感错误文案也不再作为稳定 native
  明文/xref 锚点；aggregate/owner 各自使用 registration-domain、
  build-scoped encoding，只在对应 `FatalError` 路径的 local scratch 中恢复。
  generated-C hardening gate 对旧稳定文案 fail closed。
- method name/descriptor 同值只在同一 owner 与各自 purpose domain 内复用；
  不跨 owner 共享 encoding 或 decoded scratch。去重后的 owner-local layout
  在 `bindings <= 64` 且 `textScratch <= 16 KiB` 时使用有界栈 storage，
  任一上限超出时使用 heap；两条路径都清零 `JNINativeMethod[]` 与明文 scratch，
  heap 路径随后释放。

验收：

- focused C-source/fake-JNI test 覆盖成功、class lookup failure、token mismatch、当前 owner 部分 `RegisterNatives` failure、后续 owner failure、当前与外层逆序 rollback 的 status/pending-exception failure、原异常恢复和对应 owner local-ref 清理路径。
- real Zig/child-JVM fixture 由 child-first `URLClassLoader` 加载独立 JAR，并把 TCCL 设为 `null`，验证 `JNI_OnLoad` 的 `FindClass` 仍解析 defining-loader 中的 owner。
- ASan/host integration 不出现 use-after-free、double-free 或明文 lifetime 回归。

### H4 — 默认 per-build 多样性

状态：`DONE`

- `seed: null` 使用 `SecureRandom` 生成 build root。
- 显式 seed 保留 reproducible 模式。
- build identity 参与 metadata bytes/symbol、owner order、method-table order、wrapper/internal ordering以及适用的 IR/LLVM pass。
- CFF 的 block-to-state 与 default target 使用 per-build/per-method 派生的 dense
  permutation；状态集合始终为 `[0, blockCount)`，不增加 table、状态空间或
  transition work。
- 同一次 multi-target build 共享语义 plan，不能为每个平台生成不同 Java/native binding 语义。

验收：

- 默认模式对同一输入连续构建两次，metadata bytes、table/owner order、内部 symbol 和若干 function/block layout 均不同。
- 显式 seed 对同一输入/config dual-run 的计划、generated source 和 report 顺序一致。
- KDF domain collision test 覆盖所有声明的 domain。
- raw seed/build root 不出现在 generated C、LLVM、native、JAR 或 report。

已接入的基础：

- config 已区分 `randomized` / `reproducible` mode，默认 root 为 256-bit `SecureRandom`。
- `BuildProtectionIdentity` 通过 HMAC-SHA-256 提供显式 domain separation。
- `BuildProtectionDomain` 是类型安全的闭集；生产 KDF API 不再接受 ad-hoc
  domain 字符串。当前 registry 独立列出 IR method/program、field、
  business string identity、business native text、method table、wrapper、
  LLVM symbol/protection、通用 native text、registration 和 report identity。
- `BuildProtectionMaterials` 在 mainline 边界集中冻结各 domain/context
  的派生结果，pipeline 不再散落重复字符串。byte-array material 使用
  defensive copy，identity/material 的字符串表示不包含 raw root/seed。
- IR、program protection、field、business string、method table、LLVM symbol/pass 和 wrapper symbol 已使用不同派生域。
- native registration wrapper symbol 已支持 build-scoped seed；旧无 seed API 仅保留给 focused fixture。
- reference field sidecar index 已成为 final field plan 的显式 per-owner
  `FieldId -> 0..N-1` 映射；生产 planner 使用独立
  `FIELD_REFERENCE_SIDECAR_ORDER` domain 排序，同 seed 稳定、不同 seed
  可改变物理 slot 顺序。IR、LLVM、validator、Loader sizing 与 report
  只消费该 plan，不重新按字段名推导 index。
- focused dual-plan evidence 已验证：相同显式 root 的 mainline materials、
  production wrapper symbol 和 method-table plan 完全一致；不同 root 会改变
  wrapper source identifier 与 method-table plan id。
- `BuildProtectionRealZigDiversityIntegrationTest` 已使用 Windows x64 的 managed
  Zig 0.15.2 和纯算术 `LLVM_NATIVE_PATH` 最小 plan 完成三次真实 host build：
  相同显式 root/build identity 的两次 generated C、LLVM、内部 symbol/block
  layout fingerprint 与 raw DLL bytes 完全一致；不同 root/native-text key 会
  同时改变 generated C、内部 symbol/layout fingerprint 与 raw DLL fingerprint。
  三个 DLL 的动态导出都精确等于 `JNI_OnLoad`。
- gated test 对将来可能出现的 PE raw mismatch 只允许一个窄化的显式分支：
  仅归一化 PE COFF timestamp 与 optional-header checksum，且归一化后的全部
  bytes/fingerprint 必须相同；其他 byte 差异仍失败并报告
  `REPRODUCIBLE_BUILD_NATIVE_FINGERPRINT_CHANGED`。本次实跑 raw DLL 已完全一致，
  没有使用 timestamp normalization 作为通过或多样性证据。
- native-builder host 切片本身不替代 full CLI 默认随机 dual-run、report
  顺序检查或 Ghidra 跨构建脚本复测；下列 full CLI 证据已补齐该边界。
- full CLI 默认随机 dual-run 与 Ghidra 复测已经补齐：57 个 wrapper 的 RVA
  交集为 0，只有 7/57 normalized resolution fingerprint 可复用；1261 个
  ciphertext array 与 58 个 business helper ID 的精确跨构建交集都为 0。
  显式 identity A/A real-Zig build 的 generated C、LLVM 与 raw DLL 完全一致，
  因此默认随机和显式可复现两条合同均已验收。
- 显式 identity 的 A/A 证据来自 production native builder 加 focused
  mainline/report-order tests，不是 realistic v2 Config 的完整 CLI 双跑；完整
  CLI 显式-seed dual-run 仍应作为 release harness 扩展，但不改变当前
  build-identity/KDF 实现合同已经完成的判断。

### H5 — 分散业务字符串

状态：`IN_PROGRESS`

- 普通 `CONST_STRING` 与 pass-encrypted string 统一走 native encoded representation；不能留下 plaintext fallback table。
- 每个字符串或小组使用 method/site-bound 派生材料，不共享统一 key/cipher 目录。
- JNI 创建 `String` 后立即清零临时明文。
- registration metadata 与业务字符串不共用 codec 或 lookup。
- 高敏感 metadata（class/member/descriptor/reflection target）优先按调用点/owner 拆分；普通日志文本不以牺牲稳定性为代价做过度变换。
- 通用 generated-C literal rewriter 默认采用 function-local 聚合 scratch，并把
  decode 推迟到真实 use-site；同一 function 的同明文复用一个 slot、每个
  activation 最多解码一次。compiler-supported 统一 cleanup hook 覆盖所有函数
  退出。该 lifetime 仍宽于单次 JNI call；需要更短窗口的 owner/member/table
  generator 必须直接使用 per-use native-text emitter。不同 function 之间不共享
  slot、明文 cache 或 encoding identity。

验收：

- 一次定位业务字符串 helper 不能直接枚举并解密全部字符串。
- binary plaintext audit 继续阻断受保护 surface 的敏感原文。
- child-JVM 覆盖 Unicode/MUTF-8 边界、空字符串、异常路径和并发访问。

已接入的基础：

- 普通 `CONST_STRING` 与 plain carrier 经`StringEncryptionPass`后统一发出
  `enc:v2` carrier并映射为build-scoped hash-only helper symbol；LLVM
  declaration/call、native planner evidence和generated C都消费同一个
  `BusinessStringSymbolMapper`。`enc:v2` numeric token与encrypted-payload key
  绑定，token SSA name/value都从build/method/site材料派生。`enc:v1`解析仅保留
  compiler-internal兼容回归，不是新的production emission。
- 每个不同 Java 字符串值形成一个小型 local helper group；相同值可在同一构建内
  去重，但不存在统一 token dispatcher、全局 pointer directory、business
  decoder 或 plaintext cache。不同 build identity 会同时改变 helper symbol、
  ciphertext 与派生材料。
- 每个 helper 只持有自己的 ciphertext，在栈上解码 modified UTF-8，紧邻
  `NewStringUTF` 使用；无论 `NewStringUTF` 成功还是返回 `NULL`，随后都通过
  translation-unit内共享的metadata-free `noinline` zeroizer清零整段临时明文并
  保持JNI pending exception。通用generated-C聚合scratch另使用同一个metadata-free
  cleanup callback。两者只接收scratch地址/长度，不接收ciphertext、codec或JVM
  metadata，不构成shared decoder。
- registration/runtime metadata 继续使用独立的 native-text purpose、owner-local
  lifetime 与 codec/lookup；业务字符串 helper 不调用集中 metadata decoder。
- 每个 helper 的 use identity 还会选择四种 codec family 之一和独立 schedule；
  同一 build 内不要求所有 helper 呈现同一机器码形状，跨 build 会重新选择
  family/schedule/遍历方向和派生常量。codec 只在该 helper 的 local scratch
  窗口存在，随后沿原有 normal/early/failure cleanup 路径清零。
- 多字节ciphertext不再按logical顺序连续保存，而是按
  `(offset + logicalIndex * stride) mod length`的build/purpose/use-scoped
  affine bijection写入physical storage；stride与length互质。decode只维护
  activation-local physical cursor，不生成permutation table、额外ciphertext
  byte、padding或副本。空/单字节identity是不可避免的窄例外。
- generated-native hardening audit 会阻断旧 business string table、相邻 key/cipher
  table以及 `j2ll_rt_string_constant(JNIEnv*, token)` 集中 dispatcher；新增结构
  检查还会阻断改名后的 reusable decoder fanout、固定 SplitMix 常量形状和相邻
  XOR seed-share/cipher。生产source自身必须同时提供site-bound codec与
  `AFFINE_CIPHERTEXT_STORAGE` evidence；多字节identity/direct-index storage以
  `INVALID_AFFINE_CIPHERTEXT_STORAGE` fail closed。
- `lowering-report.json` 不再输出完整 compiler helper identity；业务 string
  carrier、owner/member target 与 JNI prototype 只参与 domain-separated hash，
  用户可见 evidence 是 non-sensitive helper kind、hash 与 reason code。
- focused tests 覆盖同构建对齐、跨构建多样性、重复值小组、空字符串编码边界、
  NUL 与 surrogate pair 的 JNI modified UTF-8；四种 codec 均有 Java/C parity、
  `-Wall -Wextra -Werror` 编译证据；优化产物 test 使用 `-O2` 并扫描敏感值的
  全部 12-byte UTF-8/UTF-16LE sliding windows。跨构建测试同时验证
  family/schedule reschedule。affine storage另覆盖0/1窄边界、2..1024 bijection、
  正/反向C parity、优化产物扫描与多site source/object size budget。child-JVM
  differential fixture覆盖 NUL + supplementary Unicode、LLVM path 与 template path。
- pass 生成的 token SSA value name 不再使用稳定
  `%j2ll_str_token_<index>`；它由 build seed 与 method/site/token 派生为
  hash-only identifier，避免 debug emitted LLVM 成为跨构建锚点。

保留边界：

- 当前去重粒度是“每个不同字符串值一个 helper”，不是强制每个 callsite
  复制一份 helper；这避免普通日志重复值导致无界代码膨胀，同时仍消除了
  一次遍历统一 dispatcher/table 即批量恢复全部字符串的捷径。
- 新carrier/affine代码链路、全套test与真实managed-Zig六目标已通过；两次v2
  默认构建均通过artifact/readiness gate，39个carrier的name/numeric-token
  跨构建交集均为0。两次Ghidra动态registration probe也成功，但它们不测量业务
  字符串的final-binary批量恢复率；因此H5在补齐该项静态攻击者指标前仍保持
  `IN_PROGRESS`，不能把carrier零交集直接写成“字符串不可恢复”。

### H6 — 降低统一 JNI 调用模板

状态：`IN_PROGRESS`

- 对稳定且高频的调用组合评估 fused helper；仍经 JNI 操作 Java object。
- 方法、字段、static field 和常见 JDK bridge 可使用多个 build-scoped local ABI variant。
- wrapper 与真实实现间的 internal indirection/order 由 build identity 派生。
- 不直接读取 JVM object memory，不复制 Java object 到 native heap。

已完成的第一个安全切片：

- final native ABI传递`JNIEnv*`或owner `jclass`的JVM/JNI semantic-surface
  binding固定采用bounded branched参数重排，使其不保留direct one-hop wrapper。
  不传递两者的pure-native scalar binding仍从direct canonical、单层参数重排、
  双层参数重排和branched四种shape中派生build-scoped call topology。非branched
  形态下wrapper按物理排列传参，最内层bridge恢复LLVM function的规范参数顺序。
- bridge 不执行 JNI 调用，不读取 Java object 内存，不改变 `jobject` /
  `jclass` 表示、ownership 或 local-reference lifetime，也不检查、清除或
  新建 pending exception。
- profile、shape内的bridge symbol、参数排列和wrapper物理顺序由invocation
  build key的独立domain派生；同build/binding可复现，pure-native binding在不同
  build可以选择不同shape，semantic-surface binding则保持branched profile并改变
  route细节。
- 不生成 persistent/volatile function-pointer data slot。bridge 保持 C
  `static`，不扩大 dynamic export allowlist；direct shape 直接调用规范 LLVM
  function。
- branched形态只使用wrapper activation-local volatile predicate，在一层route
  与两层route间选择；最多三个`static __attribute__((noinline, used))` bridge，
  不添加新的逻辑参数或JVM状态。bridge不使用`optnone`，避免把正常size
  optimization一并关闭。
- 这个branch只增加静态classifier需要处理的topology，不是完整性检查或安全
  边界；动态hook与结构化binary analysis仍可观察或恢复实际route。
- `HostNativeLocalAbiBridgeCParityTest`会真实编译并分别执行branched的一层与
  两层route，用异构sentinel验证参数都恢复为canonical顺序；零参数`void`
  conditional形态也经过host C compile/run。96-site `-O2` object对照另以固定
  header allowance和per-site上限阻断无界拓扑膨胀；这不是跨平台final-library
  hard cap，最终库仍使用artifact size evidence观测。

本轮同时把generated C compile unit切到`ReleaseSmall`，per-class LLVM input与
final link module保持`ReleaseSafe`；observable compile unit按source kind同质分组，
避免C/LLVM在batch中共用错误optimization mode。

2026-07-31在当前v2输入上完成了两次默认随机build与Windows x64
fake-JNI/Ghidra复验：

- 两次build均为71 `nativeLowered`、0 `skipped`，五个selected target全部成功，
  artifact/symbol audit通过；Windows动态导出只有`JNI_OnLoad`，静态encoded
  metadata directory仍为0。
- 第一轮相对旧基线的五库raw总量由3,502,027 B降到2,611,446 B
  （-25.43%）；Windows x64由686,080 B降到503,296 B（-26.64%）。
  output JAR由3,969,705 B降到3,872,737 B（-2.44%）。generated JNI C由
  4,800,630 B增到4,842,471 B（+0.87%），表明最终体积收益来自C优化与重复
  machine-code合并，不是靠减少hardening source。
- 第二轮五库raw总量为2,616,590 B；相对第一轮，各目标库size差异为
  0%–0.86%，未出现randomized topology导致的无界体积波动。
- source topology由旧第一轮的15/22/19/15
  （direct/single/double/branched）变为4/3/5/59。Ghidra最终wrapper分类由
  35 direct / 26 multiple-callee / 10 unresolved变为4 / 59 / 8；第二个新
  build为3 / 58 / 10。
- 两个新build的71个wrapper相同RVA为0；双方可解析的60个binding中，相同
  resolution fingerprint只有3个。`decrypt`、`encrypt`、`encryptRSA`、
  `verifyJson`与`createCombinedDigest`在两次build中都呈现two-route
  multiple-callee，而不是旧样本的direct one-hop。
- fake JNI probe仍能完整观察11 owners / 71 bindings。这符合
  `RegisterNatives`的动态边界，不能把上述静态分析成本提升解释为隐藏运行时映射。

这个切片只覆盖 wrapper 到 LLVM implementation 的边界。field/static-field
与 method dispatch 已改为 concrete-binding hash-only helper，并移除了统一
token resolver。

已完成的第二个安全切片：

- concrete-binding field、static-field、virtual/interface/static/constructor
  dispatch 与 reflection member lookup 共享 `RuntimeLocalAbiPlanner`；
  LLVM declaration/call 和 generated C definition 对同一个 binding 消费同一
  build-scoped plan。
- plan 只对 binding 已有的真实逻辑参数做 per-binding 顺序排列，不插入 cookie、
  marker 或完整性参数，也不存在统一 validator/gate。这样不会把一个同时存在于
  caller/callee 的固定 cookie 变成新的静态 join key。
- 参数只做物理顺序变化，不改变 `JNIEnv*`、`jclass`、`jobject`、`jvalue*`
  或 primitive 的表示和 ownership；helper body 内 JNI 调用、pending-exception
  状态、local-ref 释放、class initialization 与 JMM 行为保持原顺序。
- planner/formatter/backend focused tests覆盖同 build 可复现、跨 build 和
  跨 family 分离、无 synthetic 参数，以及 LLVM/C 参数排列精确一致。只有一个
  真实参数的 binding 必然保持 canonical 顺序，不把它计作物理排列多样性。
- IR call-indirection 的 signature group 还绑定 preliminary native plan
  提供的 hidden `JNIEnv*` / owner-`jclass` ABI proof；同一 Java/SSA signature
  但实际 LLVM function-pointer type 不同的 target 会进入不同 group。IR
  validator 对 forged mixed-ABI group fail closed，backend 的真实类型检查没有
  放宽。

已完成的 source-size / compile-cost 切片：

- production builder 从 final validated LLVM module model 收集真实 referenced
  helper symbols，再按已声明 dependency closure 发出所需 host-JNI runtime
  source families；仅有 declaration 不会把 helper family 标记为 reachable。
  stable helper必须精确命中known-symbol集合，build-local helper必须有严格
  declaration evidence；binding-driven emitter还对其实际写出的entries补齐跨
  family dependency。
- 未知 `j2ll_rt_*` / `j2ll_h_*` reference 或不完整 model evidence 都 fail
  closed 到保守全量 source。直接 generator/fixture API 不具有 final-model
  evidence，默认同样发出全量 family。
- 该裁剪不改变 helper ABI、JNI 语义或 export allowlist；它只删除最终模型证明
  不可达的生成源码。2026-07-28 的同输入、同显式 seed、Windows x64 单次 A/B
  中，generated C 从 5,065,230 B 降到 4,119,787 B（-18.665%），DLL `.text`
  raw 从 652,800 B 降到 452,096 B（-30.745%），完整 DLL 从 713,728 B 降到
  513,024 B（-28.121%），output JAR 从 2,796,558 B 降到 2,735,286 B
  （-2.191%）。wall time 从 117.885 s 降到 106.824 s（-9.383%），但单次
  wall-clock 只作为方向性证据，不宣称跨机器/平台稳定比例。两边 method outcome
  均为 57 `nativeLowered` / 14 `skipped`，artifact/readiness audit 均通过。

已完成的低敏感错误冷路径共享切片：

- `HostJniGeneratedCFragmentEmitter`在fragment text protection前应用显式
  low-sensitivity allowlist；只有固定异常类型/固定错误文案pair会被outline，
  owner/member/descriptor、reflection target、metadata token和业务字符串不会
  进入共享leaf。
- 每个leaf使用build-scoped hash-only symbol、`noinline,cold`属性并且只接收
  `JNIEnv*`。低敏感lazy-once emitter按相同明文去重，且一个function只调用自己
  实际使用的decoder；高敏感或未allowlist文本继续保持activation-local scratch。
- v2五目标随机build `build_2026-07-28_19-12-20` 对上一份同输入随机build
  `build_2026-07-28_18-23-55`，generated C从6,596,235 B降到4,807,332 B
  （-27.12%）；五个flat native分别下降14.80%至17.25%，最终JAR从
  4,402,475 B降到3,969,147 B（-9.84%）。x64 Linux `.text`从725,188 B降到
  608,177 B（-16.14%）。
- 对应x64 Linux链接前C object中，446个localized helper由493,738 B降到
  379,670 B；新增20个共享低敏感leaf合计9,911 B，全部generated-C hardening、
  五目标artifact audit、71 `nativeLowered` / 0 `skipped`保持通过。由于两边使用
  默认随机identity，该结果是当前样本的实测窗口，不作为显式seed严格A/B或全局
  size保证。

第一个exact fused JDK组合已经接线：same-block、unique-use、non-escaping的
`ByteBuffer.allocate(4).putInt(i).array()`在ordinary optimization后、protection前
改为三个小型frame helper。helper仍用JNI `NewByteArray`创建真实JVM `byte[]`，只把
4-byte big-endian scratch放在native stack，并保留allocate/putInt/array三个原始
exception site的求值与pending-exception边界。其他capacity、alias/escape、cross-block、
indirected shape保持普通JVM dispatch。该切片减少一组稳定
token-resolver → `GetStaticMethodID`/`GetMethodID` → `Call*MethodA`模板，但不是通用
ByteBuffer native object model，也没有把Java对象复制到native heap。

固定的其他通用 JDK runtime helper 仍使用 canonical ABI；Cipher/digest/Base64等更高层
组合与更广泛 JNI helper 形态尚未实现，因此 H6 继续保持 `IN_PROGRESS`。

2026-08-02更新v2五目标实测命中7组exact ByteBuffer chain：`MelodyPlugin`最终LLVM
包含12个frame-helper call，`LaoShuUtils`包含9个，共替换21个原JDK call boundary。
五目标artifact audit通过，发布JAR与五个flat native library均无
`java/nio/ByteBuffer`明文命中。相对`build_2026-08-01_18-36-03`随机identity构建，
五库变化为Linux arm64 -816 B、Linux x64 -8 B、macOS两目标不变、Windows x64
-512 B，JAR -794 B；这只是同一样本的方向性窗口，不是显式seed受控A/B归因。

同一构建把177个`private static final ConstantValue`声明折叠/移除为
`ssaFoldedNoRuntimeStorage`，另有6个可变reference field继续使用ClassValue sidecar。
`LaoShuUtils`输出中`aesKey`、`ALGORITHM`、`TRANSFORMATION`与`KEY_SIZE`均消失，
`MelodyPlugin`的3个digest数组也继续消失。71个selected method保持
`nativeLowered`、0个`skipped`，最终artifact audit通过。

native-only coalescing在dummy real-Zig路径已有实际命中与standalone callee residual
为零的证据；本次v2的14个`internalNativeOnly`目标则0命中。逐目标reason为5个
`METHOD_INLINING_EXCEPTION_SENSITIVE`、4个非single-caller、2个callee-too-large，
以及各1个caller-chain、local-reference和invoke-kind边界。这里不为追求数字放宽
reference/helper/exception语义；下一切片必须先提供exception transfer与owned local-ref
remap proof。
第三轮双构建中 57/57 wrapper 的 coarse shape 分类仍相同：地址/RVA 提取器
已无法直接复用，但高层 wrapper classifier 仍可 100% 复用；只有 normalized
resolution fingerprint 降到 7/57。第四种branched topology接入后的两份更新
DLL中，相同coarse shape降为36/57，正式mapping reuse为5/57（8.77%）；
`directSingleCallee`也从37/57降到30/57与26/57。它说明最终机器码形态确实变化，
但仍有5个mapping可直接复用，不能把降低复用率写成classifier已经失效。

JNI local-reference lifetime 现在以per-method ownership plan fail closed：
classifier区分borrowed/owned/null，site-sensitive CFG facts与release scheduler
区分normal live-out和instruction exceptional needs，并跟踪dynamic ownership、
last use、normal/parallel edge、loop/backedge、typed/catch-all handler与显式
`athrow`；validator拒绝重复ownership transfer、handler live-set不一致和
其他无法证明有界释放的shape。`FinalNativeCoverageResolver`使用planner保存的
`UNBOUNDED_JNI_LOCAL_REFERENCE_LIFETIME`精确reason保留这类Java body。
返回reference或内部产生owned/pending-exception reference的registered native
callee改走JVM/JNI bridge以获得嵌套local frame；direct LLVM call只用于无此类
reference production的callee，无法桥接的compiler-internal shape fail closed。
JNI bridge的`jvalue[]` scratch按function最大arity只在activation prologue执行
一次`alloca`，loop/catch/backedge只复用该slot。

2026-07-28的当前v2样本验证中，原57个`nativeLowered`/14个`skipped`
提升为71个`nativeLowered`/0个`skipped`；Windows x64、Linux x64/arm64与
macOS x64/arm64五目标均完成build/link，artifact audit通过并保留final JAR。
独立real-Zig fixture覆盖6个bounded local-reference方法，其中包含25万次普通
loop与10万次caught-exception backedge；child JVM使用`-Xcheck:jni`且与原JAR
输出一致。

实现证据：focused planner/source tests覆盖同 build可复现、跨 build shape/
symbol/permutation变化、四种 topology、零参数 `void` ABI、非法 plan fail
closed，以及 bridge不包含 JNI/reference/exception操作或持久函数指针槽；
generated-wrapper host C compile继续通过。六目标 real-Zig fixture已扩展为强制
第四种shape，并使用`jint`/`jlong`/`jdouble`/`jobject`混合排列检查优化后route、
编译、链接与export allowlist；本轮`ZigCrossTargetBuildTest`和
`ProtectionCrossTargetEvidenceTest`真实六目标执行均成功。两次Ghidra动态probe
各捕获10 owners / 57 bindings、无`j2llRegister`export且exit 0；该结果验证
registration入口合同，不是wrapper normalized-p-code reuse结果。
Windows上的 child-JVM parity fixture因现有
`FakeManagedZig`仅支持Linux/macOS host而保持gated，不能伪报为本机已执行。

验收：

- helper ABI focused test、pending-exception flow 和 reference lifetime parity 通过。
- 攻击脚本不能仅凭单一 token-resolver/JNI-call 模板覆盖绝大多数方法。

### H7 — 提高 protection 实际覆盖

状态：`IN_PROGRESS`

- 对本次 native-lowered 样本逐个解释 pass 不适用 reason。
- 在不破坏 exception、monitor、JMM、class-init 和 JNI pending-exception 语义的前提下扩大 CFF、fake branch、block splitting、LLVM layout/indirection 覆盖。
- “pass 不适用”继续是 protection `SKIPPED`，不能伪造成 method native-lowering failure。
- report 同时记录 requested、applicable、affected 和最终 native-retention evidence。

验收：

- 固定样本的 coverage diff 由攻击者回归测试记录；覆盖下降需要显式审阅。
- 每个新增 shape 有 IR validator/backend/child-JVM 或六目标 structural evidence。
- 不以常量可折叠 fake branch 计作最终 binary retained evidence。

已接入的安全扩展：

- Fake branch gate 不再因原 body 含普通 helper/call/field/reference 或 exception metadata 整体跳过；原 body 与 exception evidence 原对象保留。
- Basic-block splitting 已支持普通 helper/call/field/reference 和 instruction-level exception site；原 block terminator/exception edge 只归 suffix。
- CFF 对支持 shape 的 dispatcher state 使用 build/method-scoped dense
  permutation，并独立派生 default target；状态数量与原实现一致，不以 sparse
  state、查表或额外 transition 换取多样性。
- handler、monitor、volatile/final publication、monitor happens-before、initializer 与危险 class-init 邻接继续保守跳过。
- focused IR validator/backend tests 覆盖 protected body preservation、显式 throw edge、parameterized prefix/LLVM phi 与各保守边界。
- method inlining 不再被 frontend 为所有 direct call 添加的无 handler
  pending-exception evidence 一刀切禁用。只有 pure callee、无 handler 且
  synthetic exception value 完全无 use 的 site 才删除该 evidence；protected
  exception edge、specific check 或 observable exception value 仍然跳过。
- 正式 `protection-report.json` 现在直接包含稳定 hash-only `coverage` 事实。
  per-method IR producer 显式记录 `requested/applicability/affected/status/reasonCode`；
  disabled pass 使用 `requested=false` 与 `applicability=unknown`，已执行但不适用
  使用 `notApplicable`，真实改写才写 `affected=true`。
- `ProgramIrProtectionCoordinator` 的 method inlining、method splitting 与
  IR call indirection 现在按 producer decision/site 逐 method 写显式事实；
  不从汇总 `SKIPPED` 状态反推 applicability。
- LLVM function pass 按 `affectedFunctions` 精确映射逐 method 事实；validation
  failure 因无法确定各 method applicability 而显式写 `unknown`，不伪报
  `applicable`。`LLVM_GLOBAL_LAYOUT` 以单一稳定 hash-only module subject
  记录，不因一个 global 改变而把模块内所有 method 伪报为 affected。
- Windows 上的 real Zig 0.15.2 六目标专项已完成全部 compile/link、privacy 与
  export audit；`METHOD_INLINING`、`METHOD_SPLITTING`、
  `IR_CALL_INDIRECTION`/backend 和 LLVM protection rows 均由真实候选产生
  `RAN`。该复验同时覆盖同 IR signature、不同 hidden native ABI 的分组，未再
  出现 mixed function-pointer signature。

保留验收边界：最终 machine-code retention 与更新后真实产物的必要实机攻击者
验收尚未接入；因此 H7 继续保持 `IN_PROGRESS`。

### H8 — 自动化攻击者回归

状态：`IN_PROGRESS`

新增独立的 attacker-audit harness，至少检查：

- fallback/blob/embedded class magic；
- dynamic export allowlist；
- 统一 metadata directory 与 decode-all loop；
- printable strings 和敏感 metadata；
- 伪 JavaVM/JNIEnv 对 registration mapping 的捕获数量；
- wrapper → internal callee 映射复用率；
- 两次默认构建的 symbol/table/layout 差异；
- 显式 seed reproducibility；
- protection pass 实际覆盖率及 reason 分布。

CI 不要求安装交互式 Ghidra；常规 suite 使用轻量 binary/source scanners，gated reverse suite 使用 Ghidra headless。验收关注“换一次构建后旧提取器还能恢复多少、需要多少人工介入”，不关注反编译画面是否美观。

已接入的轻量切片：

- `protection.audit.AttackerAuditHarness`直接消费一个final native library和对应generated C，复用`NativeSymbolInspector`、`SymbolAudit`、target export allowlist与`GeneratedNativeHardeningAudit`。
- 稳定machine-readable metrics覆盖fallback carrier、classfile magic、旧全局metadata directory、decode-all routine、native printable-string run、generated-C string literal、dynamic exports，以及caller提供的敏感明文在native UTF-8/UTF-16LE与generated C中的hash-only occurrence count。native-text 子指标另行记录 ciphertext/site-bound codec/family/decoder 数量、最大 decoder fanout、固定 decoder shape 与相邻 seed/cipher occurrence，使一次定位即可批量恢复的回归成为 blocking evidence。
- native-text source gate 另记录每个 production cipher 是否具有唯一真实
  runtime-bound read；缺失 volatile boundary 或额外 direct indexed read 使用
  `OPTIMIZER_FOLDABLE_NATIVE_TEXT` 阻断。artifact gate 扫描 flat final libraries，
  因此即使 generated C/LLVM 无明文、优化器在 binary 中重新物化明文也会失败。
- primary report gate 现在覆盖 `lowering-report.json`。helper evidence 的完整
  identity 只在 pipeline memory 中存在，JSON 仅保留 kind/hash；Zig cache
  duplicate 被排除时仍要求对应 flat final library 独立通过。
- `BuildArtifactFingerprint`与`DualBuildFingerprintAudit`提供dual-run测试接口：`randomized`模式至少要求generated-C fingerprint变化，raw native变化只作为独立evidence，不能用linker timestamp/noise冒充保护多样性；`reproducible`模式当前采用strict contract，要求generated C与raw native都一致，并用不同reason区分source变化和可能的toolchain binary nondeterminism。若后续确认某目标格式含不可消除timestamp，必须先增加显式normalized-binary fingerprint再调整该边界，不能静默放宽。报告不写raw seed或敏感明文。
- `BusinessStringCarrierLlvmScanner`只识别debug LLVM中的精确
  `%j2ll_v_<24-lowercase-hex> = add i64 0, <signed-long>` declaration；
  name和numeric token分别做domain-separated hash。独立
  `BusinessStringCarrierReuseAudit`要求默认随机non-empty双构建两类交集都为零，
  显式seed模式则要求集合精确一致，并用count/overlap/basis-points/reason writer
  输出。普通用户`%j2ll_v_*`不被当作carrier，raw name/token不进入报告。
- `AttackerAuditMetrics`、`BuildArtifactFingerprint`和dual-build writer同时记录
  final native与generated-C字节数，以及first/second/delta
  `artifactSizeEvidence`。这些值用于发现无界膨胀、比较security/size取舍；目前
  不是会压过plaintext/export/semantic gate的hard cap，也尚未建立跨平台统一阈值。
- `BuildProtectionRealZigDiversityIntegrationTest`复用生产
  `ZigNativeLibraryBuilder`，对同一纯LLVM语义输入执行显式identity
  A/A/B三次host build；它同时断言generated C、LLVM内部symbol/block布局、
  native fingerprint与精确export allowlist。Windows PE的条件归一化只覆盖
  COFF timestamp和optional-header checksum两个已列明字段，并要求其余完整
  image逐byte一致；当前Zig 0.15.2实跑的A/A raw DLL本身已完全一致。
- `GhidraHeadlessCommandAdapter`只为显式gated reverse suite构造`analyzeHeadless`命令；Ghidra缺失时返回unavailable，普通test和轻量scanner没有Ghidra安装依赖。
- generated-C structural audit 除旧名字外还拒绝 token + multiple-text-pointer
  directory、旧 class/method/field/reflection 集中表、generic persistent decoder
  及单 decoder 覆盖大量数组。decoder structural scanner 不依赖历史函数名：
  改名后的 pointer/loop/XOR decoder 若从 call sites 覆盖两个以上 ciphertext，
  或仍携带固定 SplitMix 常量/相邻 XOR seed shares，同样失败。lazy-once 只有
  明确的 low-sensitivity runtime-error policy 才记录窄化证据，不能再无条件
  作为 hardening 正证据。
- registration diagnostic gate 除精确阻断历史稳定文案，还结构化拒绝
  `FatalError` 的 direct/adjacent C string-literal 第二参数；使用 decoded local
  variable 的失败路径不被误报，注释中的伪调用不作为代码。
- `FakeJniRegistrationProbe`只在调用方提供的动态`JNI_OnLoad` invocation窗口内暴露
  fake-JNIEnv `RegisterNatives` observer；窗口结束后observer失效。它记录captured
  owner/binding数量、hash-only owner/name/descriptor/function identity、实际dynamic
  exports，以及是否仍存在稳定`j2ll_register`入口。只有最终library导出
  `JNI_OnLoad`、没有稳定direct registration export、动态调用确实观察到binding且
  entrypoint返回合同要求的`JNI_VERSION_1_8`时，本项才通过；失败/意外版本有独立
  reason。`mappingAvailableOnlyAfterJniOnLoadObservation`只描述映射的观察通道，
  不替代entrypoint成功判定。这项指标明确承认dynamic hook仍能获取映射，不把静态
  generated-C扫描冒充binding capture。
- wrapper mapping指标只消费显式`WrapperCallEvidence`。证据来源必须标记为
  Ghidra normalized p-code、其他binary control-flow analyzer或generated native plan；
  `WrapperCallEvidenceJsonReader`消费相同hash-only JSON合同。每条记录包含稳定logical
  binding hash、direct/indirect/multi/unresolved shape和由producer规范化的resolution
  fingerprint hash。跨构建报告给出common/reusable/shape-changed/
  resolution-changed/unresolved mapping与basis-point reuse rate。generated-plan证据会
  明确写`finalBinaryEvidence=false`，不能靠C/反汇编文本regex伪装成最终binary事实。
- round2 Ghidra harness另按最终PE内存结构扫描non-executable data中指向
  executable code的持久pointer cell，并分别统计相邻scalar与writable slot。
  该指标不依赖local symbol，可同时发现token/function静态数组和旧的
  per-wrapper volatile slot；平台固有cell通过双构建baseline人工区分，不能把
  任意非零计数直接等同于漏洞。
- protection coverage指标要求producer逐pass/subject显式提供`requested`、
  `applicability`、`affected`、`status`和`reasonCode`。未持久化applicability时必须写
  `UNKNOWN`，不能从`SKIPPED`反推“不适用”。聚合报告稳定统计pass/status/reason与
  affected rate；dual-build diff按hash-only logical subject比较新增/移除以及
  requested/applicability/affected/status/reason变化。

上述三类指标已有 focused fixture 与 stable hash-only writer。本轮又用真实
Windows DLL 完成 Ghidra headless wrapper evidence 和外部 fake-JavaVM/JNIEnv
probe：10 owners / 57 bindings 的动态映射可观察；这是同输入语义和 JVM
必然 registration 边界，不是静态复用指标。静态 encoded directory 为 0，
两次随机 build 的相同 wrapper RVA 为 0/57、normalized resolution fingerprint
复用为 7/57。4 个 persistent code-pointer slot 中 2 个已由 PE TLS directory
直接归因，另 2 个尚未确定归因；全部槽数量都不随 binding 扩张，目前没有
mapping-table 证据。H8 仍保持 `IN_PROGRESS`，因为该实机 harness 尚未成为
release gate，也尚未在 Linux/macOS final binary 上执行等价 p-code/fake-JVM
攻击回归；轻量 fixture 不能替代这些 gated final-binary 证据。

`enc:v2` carrier reuse、affine storage和artifact size evidence是在上述第三轮
样本之后新增的独立回归能力。更新后的两次v2默认构建已采集39/39 carrier、
name/token零交集与native/generated-C实际增幅；全套test、两个真实六目标fixture
和两次Windows Ghidra动态registration probe也已通过。更新topology的Ghidra
normalized-p-code证据也已补齐：5/57 mapping可复用，14个shape changed，
28个resolution changed，10个在任一构建中unresolved。H8仍为`IN_PROGRESS`的
原因已收窄为：这些工具尚未成为统一release gate、缺少业务字符串final-binary
批量恢复指标，以及Linux/macOS final-binary等价攻击回归。动态probe捕获57个
binding再次确认运行时映射可观察，不能被报告成隐藏成功。

## 实施顺序

1. H1/H1b：移除稳定导出入口，并删除只在native closure内使用的Java method entry。
2. H2/H3：按 owner 拆 registration metadata，删除全局 decode-all 目录并清零。
3. H4：引入 build identity/KDF，默认随机、显式 seed 可复现。
4. H5：业务字符串改为按 token/site 临时解码。
5. H7：按实测 reason 扩 protection 覆盖。
6. H6：基于新攻击数据选择有收益的 fused/local ABI 变体。
7. H8：每一阶段都扩展攻击者回归，最终用新 v2 产物和 Ghidra 复测。

## 完成定义

本清单完成不代表产物不可逆向。完成表示：

- 没有原始 bytecode 副本或 silent fallback；
- 没有单一静态全局表/入口能恢复绝大多数 metadata、字符串或 binding；
- 默认两个构建显著不同，显式 seed 可复现；
- dynamic observation 仍然可能；internal-only target不再出现在registration hook中，但剩余JVM entry仍可捕获，其他语义需要按owner、native调用点或运行路径采集；
- 所有改变通过 JVM 语义 parity、六目标构建/export audit、artifact audit 和 attacker regression。
