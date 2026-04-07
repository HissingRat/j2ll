# j2ll
Java `.jar` to native library transpiler and repacker for JNI-based deployment.

This tool runs an IR-only pipeline:

`JAR -> ASM -> custom IR -> IR passes -> LLVM IR -> native libs -> repacked JAR`

For large applications, `whiteList` and `blackList` filtering is still recommended. Native lowering and the protection layers add overhead, so avoid obfuscating an entire large game jar unless you have validated the result.

This tool provides string obfuscation, but it is not fundamentally irreversible because the key material still has to be derivable at runtime.

This tool now provides:
- Java/JAR side:
  - method native rewriting
  - loader injection
  - `RegisterNatives`-based binding
  - loader generation aligned to the input jar class version
- Native/DLL side:
  - string obfuscation
  - symbol de-semanticization
  - call indirection and dispatcher-based call routing
  - integer/long constant splitting
  - light CFG perturbation
  - runtime helper metadata obfuscation

---

### To run this tool, you need to have these tools installed:
1. JDK 25

    - For Windows:
        
        Install a JDK 25 distribution such as Oracle JDK 25.
    - For Linux/macOS:

        Install a JDK 25 distribution with your package manager or your preferred vendor package.
2. Zig 0.15.2

    If Zig 0.15.2 is not available, the tool can download it automatically from the
    [official website](https://ziglang.org/download/) into the same directory as the
    obfuscator jar under `zig-0.15.2/`

    You can also pre-download the host Zig archive into the same directory as the
    obfuscator jar. In that case, place both the official archive and its `.minisig`
    file there, and keep the original filenames exactly as published by Zig. For
    example on Windows: `zig-x86_64-windows-0.15.2.zip` and
    `zig-x86_64-windows-0.15.2.zip.minisig`

---

### General usage:
This project is intended to be driven through `Config.json`.

Run it as:

```
java -jar j2ll.jar
java -jar j2ll.jar --config /path/to/Config.json
```

If `Config.json` does not exist, the tool will create a template in the current directory.

#### Config file format:
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
    "enabled": true,
    "cacheStrings": false
  }
}
```

#### Arguments:
`jarFile` - input `.jar` file to obfuscate

`outputDirectory` - output root directory. Every run creates a separate timestamped workspace inside it, for example:

`out/build_2026-04-06_01-10-48`

`whiteList` - list of classes and methods to include

`blackList` - list of classes and methods to exclude

Both lists use entries like:
```
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
```
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

If you want to ship the jar with embedded native libraries, leave `libraryName` as `null`. The automatic Zig build step will place them into the output jar in the form of
```
x64-windows.dll
x64-linux.so
x64-macos.dylib
arm64-linux.so
arm64-windows.dll
arm64-macos.dylib
```
inside the directory printed in `stdout` (by default `native0/`, or the resolved `embeddedLibraryDirectory` value if present).

#### Automatic build flow:
1. The tool validates `Config.json`.
2. A timestamped workspace is created inside `outputDirectory`.
3. The input jar is parsed through ASM and lowered into custom IR.
4. IR validation and method-pass processing are applied.
5. Current method passes include:
   - CFG cleanup
   - string obfuscation
   - constant splitting
   - light CFG perturbation
6. LLVM IR is emitted into `program.ll`.
7. Runtime stubs are generated into `runtime/ir_runtime_stubs.c`.
8. Zig 0.15.2 is checked before native export starts.
9. If a matching Zig archive already exists next to the obfuscator jar, its `.minisig` is verified and the cached archive is reused.
10. Otherwise Zig is downloaded, its `.minisig` is verified, and it is extracted into `zig-0.15.2/` next to the obfuscator jar.
11. The selected targets from `config.target` are built automatically with Zig.
12. A loader is generated, native methods are rewritten, native libraries are embedded, and the output jar is repacked automatically.

#### Result files:
- Output jar: `<outputDirectory>/build_YYYY-MM-DD_HH-mm-ss/<input-jar-name>.jar`
- LLVM artifacts: `program.ll`, `runtime/ir_runtime_stubs.c`
- Frontend skip report: `frontend-skips.txt`
- Native libraries: `native/`

Notes:
- `frontend-skips.txt` is expected to be empty in the current strict bench workflow.
- The generated loader aligns to the input jar class version.
- Record-synthesized `equals`, `hashCode`, and `toString` are intentionally kept as bytecode during rewrite so their JVM `ObjectMethods` semantics remain exact in large whole-jar workloads.

---

### Building the tool by yourself
1. Run `gradlew assemble` to build the jar without tests
2. Run `gradlew shadowJar` to produce the runnable fat jar

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
