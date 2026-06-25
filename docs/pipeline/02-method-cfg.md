# 02 Method CFG

本阶段在 lower 到 IR 前，为每个 method 构建 bytecode-level CFG。

## 输入

- `ParsedMethod`
- bytecode instruction list
- try/catch table

No-Code methods do not produce a `BytecodeCfg`. The CFG stage should emit an explicit no-Code/non-applicable fact for downstream reports rather than creating a fake empty CFG.

## 输出

- `BytecodeCfg`
- `BytecodeBasicBlock`
- `BytecodeEdge`
- `ExceptionRegionModel`
- CFG diagnostics

## 推荐包

```text
xyz.melodysky.frontend.cfg
```

推荐类型：

- `MethodCfgBuilder`
- `BlockStartCollector`
- `BytecodeLabelAllocator`
- `ExceptionEdgeBuilder`
- `BytecodeCfg`
- `BytecodeBasicBlock`
- `BytecodeEdge`
- `BytecodeCfgValidator`

## 构建规则

- entry instruction 是第一个 executable instruction。
- branch target、switch target、exception handler、fallthrough target 都是 block start。
- terminator 指令结束当前 block。
- handler block 需要标注捕获类型，catch-all 使用明确 sentinel。
- unreachable bytecode 可以保留为 block，但要标注 reachability。
- monitorenter/monitorexit、athrow、return、ret/jsr 等特殊控制流必须显式表达或诊断。

## 边界

- CFG 不模拟 operand stack。
- CFG 不分配 IR value id。
- CFG 不插入 synthetic local。
- CFG 不依赖 class hierarchy。
- CFG 不吞掉异常边；即使后续 lowering 暂不支持，也要在 CFG 中表达或诊断。

## Validator

`BytecodeCfgValidator` 至少检查：

- entry block 存在。
- block id 唯一。
- successor 指向已知 block。
- terminator 和 successor 一致。
- handler target 指向 handler block。
- instruction range 不重叠。

## 测试

- straight-line method。
- conditional branch。
- goto。
- table switch。
- lookup switch。
- try/catch。
- dead/unreachable code。
- malformed method diagnostic。

旧 `MethodIrBuilderTest` 中只验证 block 数、label、branch target 的测试，应迁移为新 `src/test/java` 下的 `MethodCfgBuilderTest`。
