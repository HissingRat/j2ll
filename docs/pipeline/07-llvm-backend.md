# 07 LLVM Backend

本阶段只消费验证后的 optimized IR 和 runtime/helper metadata，输出 LLVM IR 和 native build 所需 artifact。LLVM IR 混淆必须基于 LLVM module model，不做 `.ll` 文本 regex 后处理。保护/混淆的完整设计见 [`../protection-obfuscation.md`](../protection-obfuscation.md)。

## 输入

- validated optimized `IrProgram`
- runtime helper metadata
- direct call/devirtualization metadata
- per-class output layout metadata

## 输出

- per-class LLVM module model
- per-class LLVM text
- runtime stub C sources
- native registration plan
- LLVM module model dumps

## 推荐包

```text
xyz.melodysky.backend.llvm
xyz.melodysky.backend.llvm.model
xyz.melodysky.backend.llvm.pass
xyz.melodysky.backend.llvm.protection
```

推荐类型：

- `LlvmTextBackend`
- `PerClassIrPartitioner`
- `LlvmModuleLowerer`
- `LlvmModule`
- `LlvmFunction`
- `LlvmBasicBlock`
- `LlvmInstruction`
- `LlvmModuleEmitter`
- `LlvmFunctionEmitter`
- `LlvmTypeLowerer`
- `LlvmNameMangler`
- `LlvmHelperDeclarationCollector`
- `LlvmModulePassPipeline`
- `LlvmProtectionPipeline`

## Recommended flow

```text
IrProgram
  -> PerClassIrPartitioner
  -> LlvmModuleLowerer
  -> LlvmModulePassPipeline
  -> LlvmTextEmitter
  -> native build
```

`LlvmModuleLowerer` 必须按原始 class 生成 LLVM module；`LlvmTextEmitter` 只负责打印 module model。LLVM-level name obfuscation、opaque predicates、indirect calls、global layout 和 visibility pass 都应操作 module model。

## 边界

- LLVM backend 只消费 IR 和 metadata。
- backend 不补 CFG。
- backend 不做 devirtualization decision。
- backend 不吞掉 validator 错误。
- backend 不负责 JVM 语义猜测；null/class-init/dispatch/exception 等语义必须来自 IR 或 runtime metadata。
- LLVM protection pass 不根据 `.ll` 文本搜索替换。
- Java method 对应 LLVM function 默认 internal/hidden；JNI / C ABI wrapper 才可进入 export list。

## Runtime helper 对齐

后端声明 helper 时，runtime stub generator 必须能生成同签名实现。新增 helper 时至少同步：

- helper name schema
- argument ABI
- return ABI
- exception behavior
- reference lifetime policy
- backend declaration test
- runtime stub generator test

## 测试

- primitive/reference type lowering。
- branch/switch/throw/return。
- helper declaration collection。
- direct call thunk。
- per-class LLVM module emission。
- runtime stub generator 与 backend declaration 对齐。
- LLVM module model -> text golden test。
- LLVM protection pass deterministic seed test。
- symbol visibility preflight test。
