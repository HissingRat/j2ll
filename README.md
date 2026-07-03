# j2ll

[English](#j2ll) | [中文](#j2ll-中文)

Java `.jar` to native library transpiler and repacker for JNI-based deployment.

The tool runs an IR-only pipeline:

`JAR -> ASM -> custom IR -> IR passes -> LLVM IR -> native libs -> repacked JAR`

For large applications, `whiteList` and `blackList` filtering is still recommended. Native lowering and the protection layers add overhead, so avoid obfuscating an entire large game jar unless you have validated the result.

This tool provides string obfuscation, but it is not fundamentally irreversible because the key material still has to be derivable at runtime.

This tool currently provides:
- Java/JAR side:
  - method native rewriting
  - loader injection
  - `RegisterNatives`-based binding
  - loader generation aligned to the input jar class version
- Native side:
  - string obfuscation
  - symbol de-semanticization
  - call indirection and dispatcher-based call routing
  - integer/long constant splitting
  - light CFG perturbation
  - runtime helper metadata obfuscation

---

### Lowering status terminology

j2ll reports every selected method with an explicit status. The important ones are:

- `lowered`: the method body was native-lowered. The original Java method is rewritten to a native/stub entry, registered through JNI, and its behavior is implemented by generated native code plus JVM/JNI runtime helpers.
- `halfLowered`: the method was still wrapped through the native path, but at least one operation or call site is preserved through a JVM fallback. In schema v1 this usually means the original bytecode needed for that fallback is encoded into the native artifact as a `nativeEmbeddedClassBlob`, then invoked through a hidden/helper class at runtime.
- `frontendSkipped`: j2ll kept the original bytecode and did not rewrite/register that method. This is used for shapes that are not safe to native-wrap yet, such as some complex finally/default-interface-super cases.
- `notApplicable`: the selector matched a method that has no lowerable Java body or does not need rewriting, such as abstract or already-native methods.
- `failed`: j2ll could not preserve a safe output for that method or stage.

In short:

```text
lowered:
  Java method -> native stub -> generated LLVM/JNI/helper implementation

halfLowered:
  Java method -> native stub -> native bridge -> encoded JVM fallback helper

frontendSkipped:
  Java method remains ordinary bytecode
```

`halfLowered` is not a silent skip. It is a conservative safety path: j2ll still hides the original method entry behind native registration, but keeps hard JVM semantics correct by delegating the unsupported part to encoded bytecode-preserving fallback. For stronger obfuscation, prefer more `lowered` methods and fewer `halfLowered`/`frontendSkipped` methods, but `failed` is the status that indicates a real build problem.

---

### Requirements
1. JDK 25

   - Windows:
     install a JDK 25 distribution such as Oracle JDK 25.
   - Linux/macOS:
     install a JDK 25 distribution with your package manager or your preferred vendor package.

2. Zig 0.15.2

   If Zig 0.15.2 is not available, the tool can download it automatically from the
   [official website](https://ziglang.org/download/) into the same directory as the
   obfuscator jar under `zig-0.15.2/`.

   You can also pre-download the host Zig archive into the same directory as the
   obfuscator jar. In that case, place both the official archive and its `.minisig`
   file there, and keep the original filenames exactly as published by Zig. For
   example on Windows:

   - `zig-x86_64-windows-0.15.2.zip`
   - `zig-x86_64-windows-0.15.2.zip.minisig`

---

### General usage
This project is intended to be driven through `Config.json`.

Run it as:

```text
java -jar j2ll.jar
java -jar j2ll.jar --config /path/to/Config.json
java -jar j2ll.jar --debug --config /path/to/Config.json
java -jar j2ll.jar --analyze --config /path/to/Config.json
```

If `Config.json` does not exist, the tool will create a template in the current directory.

`--debug` keeps native build intermediates and prints extra IR/native timing information.

`--analyze` runs the frontend analysis only. It writes `analysis-report.json`
and any frontend skip reports into the timestamped workspace without building
native libraries or repacking the input jar.

#### Config file format
```json
{
  "jarFile": "/absolute/path/to/input.jar",
  "outputDirectory": "out",
  "blackList": [],
  "whiteList": [],
  "target": {
    "windowsX64": true,
    "windowsArm64": false,
    "linuxX64": true,
    "linuxArm64": false,
    "macosX64": true,
    "macosArm64": true
  },
  "libraryName": null,
  "embeddedLibraryDirectory": "native0",
  "stringObfuscation": {
    "enabled": true
  },
  "maxShardMB": 16
}
```

#### Config fields
`jarFile` - input `.jar` file to obfuscate

`outputDirectory` - output root directory. Every run creates a separate timestamped workspace inside it, for example:

`out/build_2026-04-12_14-42-24`

`whiteList` - list of classes and methods to include

`blackList` - list of classes and methods to exclude

Both lists use entries like:
```json
"whiteList": [
  "<class>",
  "<class>#<method name>!<method descriptor>",
  "mypackage/myotherpackage/Class1",
  "mypackage/myotherpackage/Class1#doSomething!()V",
  "mypackage/myotherpackage/Class1$SubClass#doOther!(I)V"
]
```

Filtering uses JVM internal class names and method descriptors.

Wildcard matchers are also supported:
```json
"whiteList": [
  "mypackage/myotherpackage/*",
  "mypackage/myotherpackagewithnested/**",
  "mypackage/myotherpackage/*/Class1",
  "mypackage/myotherpackagewithnested/**/Class1",
  "mypackage/myotherpackage/Class*"
]
```

`*` matches a single entry separated by `/`

`**` matches multiple nested entries

`libraryName` - if the output jar should load native libraries from the system library path, set the plain library name used by `LoaderPlain`

`embeddedLibraryDirectory` - sets the embedded native library directory inside the output jar

`stringObfuscation.enabled` - enables string obfuscation pass

`maxShardMB` - best-effort upper bound for generated LLVM shards and runtime helper shards, in megabytes. This is useful when you want smaller compile/link units for large jars.

If you want to ship the jar with embedded native libraries, leave `libraryName` as `null`. The automatic Zig build step will place them into the output jar in the form of:

```text
x64-windows.dll
x64-linux.so
x64-macos.dylib
arm64-linux.so
arm64-windows.dll
arm64-macos.dylib
```

inside the directory printed in `stdout` (by default `native0/`, or the resolved `embeddedLibraryDirectory` value if present).

---

### Automatic build flow
1. The tool validates `Config.json`.
2. A timestamped workspace is created inside `outputDirectory`.
3. The input jar is parsed through ASM and lowered into custom IR.
4. IR validation and method-pass processing are applied.
5. Current method passes include:
   - CFG cleanup
   - string obfuscation
   - constant splitting
   - light CFG perturbation
6. LLVM IR is emitted into `llvm-modules/program.ll` and split into shard modules under `llvm-modules/`.
7. Runtime C sources are generated into `runtime/`.
8. Zig 0.15.2 is checked before native export starts.
9. If a matching Zig archive already exists next to the obfuscator jar, its `.minisig` is verified and the cached archive is reused.
10. Otherwise Zig is downloaded, its `.minisig` is verified, and it is extracted into `zig-0.15.2/` next to the obfuscator jar.
11. The selected targets from `config.target` are built automatically with Zig.
12. A loader is generated, native methods are rewritten, native libraries are embedded, and the output jar is repacked automatically.

`Ctrl+C` cancels the Java process and also terminates spawned Zig child processes.

---

### Workspace layout
Each run creates a build workspace like:

```text
out/build_YYYY-MM-DD_HH-mm-ss/
```

Important files and directories:
- Repacked jar: `<workspace>/<input-jar-name>.jar`
- Native libraries: `<workspace>/native/`
- Logs: `<workspace>/logs/`
- LLVM monolithic IR: `<workspace>/llvm-modules/program.ll`
- LLVM shard modules: `<workspace>/llvm-modules/*.ll`
- Runtime C sources: `<workspace>/runtime/*.c`
- Frontend skip report: `<workspace>/frontend-skips.txt` when skips are present
- Structured frontend skip report: `<workspace>/frontend-skips.json` when skips are present

Notes:
- `frontend-skips.txt` and `frontend-skips.json` are not generated when the frontend skip count is `0`.
- Annotation classes are intentionally skipped by the frontend today and are not native-lowered.
- Record-synthesized `equals`, `hashCode`, and `toString` are intentionally kept as bytecode so their JVM `ObjectMethods` semantics remain exact in large whole-jar workloads.

`frontend-skips.json` contains the total skip count, counts grouped by reason category,
and one entry per skipped method with class name, method name, descriptor, raw reason,
and category.

Intermediate native build directories:
- `native-obj/`
- `zig-cache/`
- `zig-build/`

These are deleted automatically after the build unless `--debug` is enabled.

---

### Building the tool
Common commands:

1. `./gradlew clean build`
   Builds the project and runs the full test suite.
2. `./gradlew assemble`
   Builds the project jar without tests.
3. `./gradlew shadowJar`
   Produces the runnable fat jar.

The runnable jar is written to:

`build/libs/j2ll.jar`

---

### Tests
Run `./gradlew test` for the unit and integration suite.

You can also run the benchmark fixture end to end with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-obf-bench.ps1
```

or:

```text
./gradlew obfBench
```

`obfBench` currently acts as a strict gate:
- the obfuscated benchmark must run successfully
- frontend skips must be zero
- native rewriting must succeed

---

# j2ll 中文

[English](#j2ll) | [中文](#j2ll-中文)

j2ll 是一个面向 JNI 部署的 Java `.jar` 到 native library 转换与 repack 工具。

工具运行一条 IR-only 管线：

`JAR -> ASM -> custom IR -> IR passes -> LLVM IR -> native libs -> repacked JAR`

对于大型应用，仍然建议使用 `whiteList` 和 `blackList` 过滤需要处理的范围。Native lowering 和 protection 层都会带来额外开销，所以在没有验证结果前，不建议直接混淆整个大型游戏 JAR。

本工具提供字符串混淆，但它并不是根本不可逆的，因为运行时仍然需要能推导出解密所需的 key material。

当前提供的能力：

- Java/JAR 侧：
  - method native rewriting
  - loader injection
  - 基于 `RegisterNatives` 的绑定
  - 与输入 JAR class version 对齐的 loader generation
- Native 侧：
  - string obfuscation
  - symbol de-semanticization
  - call indirection 和 dispatcher-based call routing
  - integer/long constant splitting
  - light CFG perturbation
  - runtime helper metadata obfuscation

---

### Lowering 状态说明

j2ll 会为每个被 selector 命中的方法记录明确状态。重要状态包括：

- `lowered`：方法主体已经 native-lowered。原 Java method 会被改写成 native/stub entry，并通过 JNI 注册；实际语义由生成的 native 代码和 JVM/JNI runtime helper 完成。
- `halfLowered`：方法已经进入 native 包装路径，但至少有一个 operation 或 call site 仍通过 JVM fallback 保持语义。schema v1 中，这通常意味着 fallback 所需的原始 bytecode 会被编码进 native artifact，作为 `nativeEmbeddedClassBlob`，运行时再通过 hidden/helper class 调回 JVM 执行。
- `frontendSkipped`：j2ll 保留原始 Java bytecode，不 rewrite、不 RegisterNatives。这个状态用于当前还不能安全 native-wrap 的方法形状，例如复杂 finally 或 default-interface-super 边界。
- `notApplicable`：selector 命中了方法，但该方法没有可 lower 的 Java body，或不需要 rewrite，例如 abstract 或 already-native method。
- `failed`：j2ll 无法为该方法或阶段生成安全输出。

简化理解：

```text
lowered:
  Java method -> native stub -> generated LLVM/JNI/helper implementation

halfLowered:
  Java method -> native stub -> native bridge -> encoded JVM fallback helper

frontendSkipped:
  Java method remains ordinary bytecode
```

`halfLowered` 不是静默跳过，也不是失败。它是保守安全路径：j2ll 仍然把原方法入口隐藏在 native registration 后面，但对暂时不能安全 native lowering 的复杂 JVM 语义，交给 encoded bytecode-preserving fallback 兜底。想要更强混淆时，应尽量提高 `lowered` 比例，减少 `halfLowered` 和 `frontendSkipped`；但真正需要修复的红线是 `failed`。

---

### 环境要求

1. JDK 25

   - Windows：安装 Oracle JDK 25 等 JDK 25 发行版。
   - Linux/macOS：通过包管理器或你偏好的 vendor package 安装 JDK 25。

2. Zig 0.15.2

   如果 Zig 0.15.2 不可用，工具可以从 [Zig 官方网站](https://ziglang.org/download/) 自动下载，并放到 obfuscator jar 同目录下的 `zig-0.15.2/`。

   你也可以提前把 host Zig archive 放在 obfuscator jar 同目录下。这种情况下，请把官方 archive 和对应 `.minisig` 文件都放在那里，并保持 Zig 官方发布的原始文件名。例如 Windows：

   - `zig-x86_64-windows-0.15.2.zip`
   - `zig-x86_64-windows-0.15.2.zip.minisig`

---

### 基本使用

本项目主要通过 `Config.json` 驱动。

运行方式：

```text
java -jar j2ll.jar
java -jar j2ll.jar --config /path/to/Config.json
java -jar j2ll.jar --debug --config /path/to/Config.json
java -jar j2ll.jar --analyze --config /path/to/Config.json
```

如果 `Config.json` 不存在，工具会在当前目录创建一个模板。

`--debug` 会保留 native build intermediates，并打印额外的 IR/native timing 信息。

`--analyze` 只运行 frontend analysis。它会把 `analysis-report.json` 和 frontend skip reports 写入带时间戳的 workspace，不会构建 native libraries，也不会 repack 输入 JAR。

#### Config 文件格式

```json
{
  "jarFile": "/absolute/path/to/input.jar",
  "outputDirectory": "out",
  "blackList": [],
  "whiteList": [],
  "target": {
    "windowsX64": true,
    "windowsArm64": false,
    "linuxX64": true,
    "linuxArm64": false,
    "macosX64": true,
    "macosArm64": true
  },
  "libraryName": null,
  "embeddedLibraryDirectory": "native0",
  "stringObfuscation": {
    "enabled": true
  },
  "maxShardMB": 16
}
```

#### Config 字段

`jarFile` - 要混淆的输入 `.jar` 文件。

`outputDirectory` - 输出根目录。每次运行都会在里面创建一个独立的带时间戳 workspace，例如：

`out/build_2026-04-12_14-42-24`

`whiteList` - 要包含的 class 和 method 列表。

`blackList` - 要排除的 class 和 method 列表。

两个列表都使用类似下面的条目：

```json
"whiteList": [
  "<class>",
  "<class>#<method name>!<method descriptor>",
  "mypackage/myotherpackage/Class1",
  "mypackage/myotherpackage/Class1#doSomething!()V",
  "mypackage/myotherpackage/Class1$SubClass#doOther!(I)V"
]
```

过滤使用 JVM internal class name 和 method descriptor。

也支持 wildcard matcher：

```json
"whiteList": [
  "mypackage/myotherpackage/*",
  "mypackage/myotherpackagewithnested/**",
  "mypackage/myotherpackage/*/Class1",
  "mypackage/myotherpackagewithnested/**/Class1",
  "mypackage/myotherpackage/Class*"
]
```

`*` 匹配由 `/` 分隔的单个 entry。

`**` 匹配多个嵌套 entry。

`libraryName` - 如果输出 JAR 应该从 system library path 加载 native library，则设置 `LoaderPlain` 使用的普通 library name。

`embeddedLibraryDirectory` - 设置输出 JAR 内嵌 native library 的目录。

`stringObfuscation.enabled` - 启用 string obfuscation pass。

`maxShardMB` - 生成的 LLVM shard 和 runtime helper shard 的 best-effort 大小上限，单位 MB。大型 JAR 想要更小的 compile/link unit 时会有用。

如果你想随 JAR 一起发布 embedded native libraries，请把 `libraryName` 保持为 `null`。自动 Zig build step 会把 native library 放进输出 JAR，形式类似：

```text
x64-windows.dll
x64-linux.so
x64-macos.dylib
arm64-linux.so
arm64-windows.dll
arm64-macos.dylib
```

这些文件会放在 stdout 打印的目录中，默认是 `native0/`，或 resolved `embeddedLibraryDirectory` 的值。

---

### 自动构建流程

1. 工具验证 `Config.json`。
2. 在 `outputDirectory` 内创建带时间戳的 workspace。
3. 输入 JAR 通过 ASM 解析，并 lower 到 custom IR。
4. 执行 IR validation 和 method-pass processing。
5. 当前 method passes 包括：
   - CFG cleanup
   - string obfuscation
   - constant splitting
   - light CFG perturbation
6. LLVM IR 输出到 `llvm-modules/program.ll`，并拆分为 `llvm-modules/` 下的 shard modules。
7. runtime C sources 输出到 `runtime/`。
8. native export 开始前会检查 Zig 0.15.2。
9. 如果 obfuscator jar 旁边已经存在匹配的 Zig archive，会验证它的 `.minisig` 并复用缓存 archive。
10. 否则会下载 Zig，验证 `.minisig`，并解压到 obfuscator jar 旁边的 `zig-0.15.2/`。
11. 使用 Zig 自动构建 `config.target` 中选择的 targets。
12. 工具生成 loader、rewrite native methods、嵌入 native libraries，并自动 repack 输出 JAR。

`Ctrl+C` 会取消 Java 进程，并终止已启动的 Zig child processes。

---

### Workspace 布局

每次运行会创建如下 build workspace：

```text
out/build_YYYY-MM-DD_HH-mm-ss/
```

重要文件和目录：

- Repacked jar：`<workspace>/<input-jar-name>.jar`
- Native libraries：`<workspace>/native/`
- Logs：`<workspace>/logs/`
- LLVM monolithic IR：`<workspace>/llvm-modules/program.ll`
- LLVM shard modules：`<workspace>/llvm-modules/*.ll`
- Runtime C sources：`<workspace>/runtime/*.c`
- Frontend skip report：有 skip 时生成 `<workspace>/frontend-skips.txt`
- Structured frontend skip report：有 skip 时生成 `<workspace>/frontend-skips.json`

注意：

- frontend skip 数量为 `0` 时，不会生成 `frontend-skips.txt` 和 `frontend-skips.json`。
- annotation classes 当前会被 frontend 有意跳过，不做 native-lowered。
- record-synthesized `equals`、`hashCode` 和 `toString` 当前会保留为 bytecode，以便在大型 whole-jar workload 中保持 JVM `ObjectMethods` 语义完全一致。

`frontend-skips.json` 包含总 skip 数、按 reason category 分组的计数，以及每个 skipped method 的 class name、method name、descriptor、raw reason 和 category。

中间 native build 目录：

- `native-obj/`
- `zig-cache/`
- `zig-build/`

除非开启 `--debug`，这些目录会在构建后自动删除。

---

### 构建工具本身

常用命令：

1. `./gradlew clean build`
   构建项目并运行完整测试套件。
2. `./gradlew assemble`
   构建项目 JAR，但不运行测试。
3. `./gradlew shadowJar`
   生成 runnable fat jar。

runnable jar 输出到：

`build/libs/j2ll.jar`

---

### 测试

运行 unit 和 integration suite：

```text
./gradlew test
```

也可以运行 benchmark fixture 的端到端测试：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-obf-bench.ps1
```

或：

```text
./gradlew obfBench
```

`obfBench` 当前是 strict gate：

- obfuscated benchmark 必须成功运行
- frontend skips 必须为 zero
- native rewriting 必须成功
