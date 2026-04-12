# j2ll
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
```

If `Config.json` does not exist, the tool will create a template in the current directory.

`--debug` keeps native build intermediates and prints extra IR/native timing information.

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
    "enabled": true,
    "cacheStrings": false
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

`stringObfuscation.cacheStrings` - enables runtime string caching for decrypted string values

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

Notes:
- `frontend-skips.txt` is not generated when the frontend skip count is `0`.
- Annotation classes are intentionally skipped by the frontend today and are not native-lowered.
- Record-synthesized `equals`, `hashCode`, and `toString` are intentionally kept as bytecode so their JVM `ObjectMethods` semantics remain exact in large whole-jar workloads.

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
