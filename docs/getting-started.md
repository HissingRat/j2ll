# Getting Started

This beta package is a JVM-hosted JAR obfuscator/native-lowering tool. The output is still a runnable JAR for Java 17 or newer and loads embedded native libraries through the JVM, JNI, one generated `<embeddedLibraryDirectory>/Loader.class`, RegisterNatives, runtime helpers, and fallback blobs.

## Build The Beta Package

```sh
bash ./gradlew distJ2ll
```

The distribution is written to:

```text
build/dist/j2ll/
```

It contains `j2ll.jar`, the config schema, example configs, sample docs, and this guide. It does not include generated build workspaces, output JARs, reports, native artifacts, or a bundled Zig archive.

## Run The CLI

```sh
java -jar build/dist/j2ll/j2ll.jar --help
java -jar build/dist/j2ll/j2ll.jar --version
```

Validate a config:

```sh
java -jar build/dist/j2ll/j2ll.jar --validate --config docs/examples/minimal-config.json
```

Validation creates no workspace. If `--config` is omitted, j2ll reads `Config.json` from the current directory.

Dry-run a config without building native artifacts or writing a final output JAR:

```sh
java -jar build/dist/j2ll/j2ll.jar --dry-run --config docs/examples/minimal-config.json
```

Build an input JAR:

```sh
java -jar build/dist/j2ll/j2ll.jar --config docs/examples/minimal-config.json
# Run the outputJar=... path printed by j2ll:
java -jar <resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/input.jar example.Main
```

Build is the default mode. Add `--debug` to enable every intermediate output switch for the run: debug dumps, per-class SSA IR, LLVM IR, and generated C. This retains compiler diagnostics; it does not enable native debug symbols.

If `fieldInternalization` is enabled outside `CLOSED_WORLD`, a build asks `fieldInternalization requires CLOSED_WORLD, continue? (Y/N)`. Y keeps the configured world unchanged and authorizes only this run to analyze field references in the input JAR; configured classpath entries and external observers remain out of scope and are recorded in `field-internalization-report.json`. N or EOF exits with code `2` before a workspace is created. Ordinary terminals and PTYs are detected, a pre-supplied piped answer is accepted, and unattended runs with no input fail immediately instead of hanging. Validate and dry-run do not consume stdin; they report that the real build still needs confirmation.

During a full build, stderr shows progress across input inspection, parsing, selection, analysis, lowering/protection, LLVM emission, intermediates, target preflight, native build, packaging, audit, and reports. An interactive terminal follows the legacy lifecycle: a three-line compiler region (`Read bytecode`, `Lower to IR`, and `Emit LLVM IR`), a native region with one aggregate `Build native` bar and one row per selected target, then a one-line `Finalize JAR` region. Each target percentage is `completed Zig build-graph work units / total work units` for that target. Large source sets are deterministically balanced into no more than 64 observable compile units per target, bounding marker and polling overhead. The `building` to `linking` transition reflects a real graph boundary rather than elapsed-time inference; while linking, the bar may stay at the final compilation percentage until the link completes. The row reaches `100%` and `completed` only after the non-empty final DLL/SO/dylib has been installed. As soon as every target is complete, all target rows collapse to a short aggregate summary. Normal-width terminals use 28-character bars; narrow terminals shorten the bar before truncating useful detail. Redirected output uses one control-sequence-free `[current/total]` line per high-level stage without per-target log spam, so stdout stays machine-parseable. Managed Zig still runs the selected target matrix in one matrix-wide invocation and schedules independent build nodes internally; these percentages describe observable graph-unit completion, not Zig/Clang/LLVM compiler-internal progress. On failure the active region is cleared without an extra Gradle-style failure summary, allowing the primary diagnostic to be printed immediately.

## Managed Zig

The schema v1 native toolchain is managed Zig `0.15.2`.

- j2ll looks for `zig/zig` or `zig/zig.exe` next to `j2ll.jar`.
- If the executable is missing or has the wrong version, j2ll first looks for the official Zig `0.15.2` archive in the same directory.
- If no local archive is present, j2ll downloads from `https://ziglang.org/download/0.15.2/`.
- Local and downloaded archives are checked against the expected SHA-256 before extraction.
- Signature verification is reported as `notVerifiedBoundary` until signature verification is fully wired.
- Extracted archives are normalized so the final executable is `zig/zig` or `zig/zig.exe`.
- One generated `build.zig` and one matrix-wide invocation can produce Windows GNU x86_64/AArch64, Linux GNU glibc 2.17 x86_64/AArch64, and macOS 10.15 x86_64/11.0 AArch64 libraries.
- Structural cross-build evidence verifies DLL/SO/dylib format, architecture and exports. Runtime child-JVM E2E is currently host-target evidence; non-host runtime execution remains separate.

Checksum mismatch, corrupt archive, missing bootstrap metadata for the current host Zig executable, or an actual required-target capability/compile/link failure exits with code `4` and writes reports. Non-host selection by itself is not a failure condition.

## Outputs And Reports

Successful build:

- `<workspace>/<input-name>.jar`
- `<workspace>/native/<library-file-name>` for each selected target
- exactly one Java 17 `<embeddedLibraryDirectory>/Loader.class` inside the output JAR; native loading is always present, while `defineHiddenFallback` is included only when `nativeEmbeddedClassBlob` was actually used
- no retired `J2llFallbackSupport.class`, `J2llNativeLoaderSupport.class`, or `j2ll/generated/**/NativeLoader.class` entry
- `reports/index.json`
- `reports/summary.md`
- `reports/release-readiness.json`
- `reports/packaging-report.json`
- `reports/artifact-audit.json`
- `intermediates/` when enabled by config

Failure before finalization:

- No final output JAR is retained.
- Reports are retained.
- `reports/failure-report.json` records the primary stage and reason.
- `reports/summary.json`, `reports/summary.md`, and `reports/index.json` remain available when the run reached report writing.

Dry-run:

- No output JAR is written.
- No native library is built.
- Reports are retained, including target package planning evidence.

Dry-run and build allocate `<resolved-outputDirectory>/build_yyyy-MM-dd_HH-mm-ss[-n]/` automatically. The numeric suffix is added only when a workspace with the same timestamp already exists.

`embeddedLibraryDirectory` is also the generated loader's Java package prefix, so it must be a canonical Java internal package path. Keep it application-unique when multiple different output artifacts may share one `ClassLoader`; otherwise they would request the same `<embeddedLibraryDirectory>/Loader` internal name.

## Common Failures

- Invalid config: exit `2`; see `reports/diagnostics.json`.
- Input base/MR Loader collision: rejected before Zig as `GENERATED_RUNTIME_LOADER_ENTRY_COLLISION` or `GENERATED_RUNTIME_LOADER_VERSIONED_SHADOW`.
- Frontend/lowering unsupported shape: exit `3`; see lowering and frontend skip reports.
- Zig checksum, missing toolchain, or required target unbuildable: exit `4`; see packaging report and failure report.
- Signing or packaging policy failure: exit `5`.
- Artifact audit failure, including blocking plaintext leaks: exit `6`; no final JAR is retained.
- Strict readiness failure: exit `7`; stderr prints the top missing evidence and the readiness report path.

Reports and final metadata use hash-only fields for sensitive protection seeds and plaintext facts. User-facing summaries avoid absolute workspace paths; explicit local paths are reserved for debug/intermediate manifests.

## Acceptance Command

The repository beta acceptance smoke is:

```sh
bash ./gradlew betaAcceptance
```

It builds the distribution, runs the dist `j2ll.jar`, validates the example config, dry-runs a sample, builds a sample input JAR, runs the output JAR in a child JVM, and verifies report/readiness metadata.
