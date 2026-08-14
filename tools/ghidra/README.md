# Ghidra final-library black-box audit

These Ghidra 12 headless scripts measure static recovery shortcuts in a native
library extracted from a published JAR. They are intentionally outside the
compiler pipeline: they do not read j2ll source, configuration, reports,
generated C/LLVM, or build intermediates.

The scripts do not claim that a library is uncrackable. In particular, a
runtime observer can still hook `RegisterNatives`, JNI calls, and plaintext use
windows. Static decompilation success is not the same as recovered Java
semantics.

## Scripts

- `BlackBoxNativeAudit.java` writes bounded JSON evidence for binary format,
  architecture/language, sections and sampled entropy, imports/exports,
  `JNI_OnLoad`-rooted resolved call closure, indirect-call sites, printable
  ASCII/UTF-16 strings, non-executable code-pointer cells, persistent
  `{name,descriptor,function}` registration candidates, decoder-like functions,
  normalized p-code clone families, and root-closure decompiler success.
- `BlackBoxRootDecompile.java` writes a bounded manual-review corpus for the
  resolved call closure rooted at `JNI_OnLoad` (or another explicitly selected
  public ABI root). It records direct versus indirect p-code calls alongside
  each pseudocode body.

Neither script contains an application method name, application address, or a
j2ll-internal symbol name. `JNI_OnLoad` is used only because it is the public
JVM native-library ABI root.

## Isolated input layout

Create a fresh audit directory for each published artifact:

```text
audit_yyyyMMdd_HHmmss/
  input/sample.jar
  extracted/native/<library>
  evidence/
  logs/
  ghidra-projects/
```

Copy only the final JAR into `input/`, then extract the dynamic libraries from
that copied JAR. Do not copy reports or any build directory into the audit
workspace. Preserve hashes of the copied JAR and extracted libraries in the
run log.

## Headless invocation

On Windows, use a fresh project name per target. Quote paths that contain
spaces. The `-deleteProject` flag keeps the audit workspace from accumulating
analysis databases after evidence has been written.

```powershell
& 'D:\Ghidra\support\analyzeHeadless.bat' `
  'C:\audit\ghidra-projects' 'sample-windows-x64' `
  -import 'C:\audit\extracted\native\x64-windows.dll' `
  -scriptPath 'F:\.projects\j2ll\tools\ghidra' `
  -postScript BlackBoxNativeAudit.java `
    '--out=C:\audit\evidence\windows-x64.json' `
    '--max-functions=20000' `
    '--max-root-functions=4096' `
    '--max-root-depth=16' `
    '--max-decompile-functions=96' `
    '--decompile-timeout-seconds=15' `
  -postScript BlackBoxRootDecompile.java `
    '--out=C:\audit\evidence\windows-x64-root.txt' `
    '--root-name=JNI_OnLoad' `
    '--max-functions=256' `
    '--max-depth=12' `
    '--timeout-seconds=30' `
  -deleteProject
```

Run `BlackBoxNativeAudit` against every extracted platform library. Deep root
decompilation is usually most useful for one host architecture first, followed
by other targets only when the structural metrics diverge.

## Budgets

Every bounded operation records its configured budget and whether evidence was
truncated. Important `BlackBoxNativeAudit` options are:

| Option | Default | Meaning |
| --- | ---: | --- |
| `--max-functions` | 20000 | Maximum internal functions inspected |
| `--max-root-functions` | 4096 | Maximum functions in the ABI-rooted closure |
| `--max-root-depth` | 16 | Maximum resolved call depth |
| `--max-entropy-bytes-per-section` | 1048576 | Deterministic section entropy sample |
| `--max-string-scan-bytes-per-section` | 16777216 | Raw ASCII/UTF-16 scan budget |
| `--max-strings` | 4096 | Maximum strings serialized |
| `--max-pointer-scan-bytes-per-section` | 16777216 | Aligned pointer scan budget |
| `--max-code-pointer-cells` | 4096 | Maximum code-pointer cells serialized |
| `--max-registration-candidates` | 1024 | Maximum persistent JNI triplets serialized |
| `--max-decoder-candidates` | 256 | Maximum decoder-like functions serialized |
| `--min-clone-pcode-ops` | 12 | Minimum normalized function size for clone grouping |
| `--max-clone-families` | 256 | Maximum clone families serialized |
| `--max-decompile-functions` | 96 | Maximum root-closure functions decompiled for JSON metrics |
| `--decompile-timeout-seconds` | 15 | Timeout for each metric-only decompilation |

Use `0` only for options documented by the parser as disabling a bounded
output (for example `--max-decompile-functions=0`). A larger budget can improve
coverage but does not change the evidence boundary.

## Completion and coverage schema

`BlackBoxNativeAudit` writes three top-level completion fields:

- `status=COMPLETE` means every requested measurement finished and its evidence
  covered the full Ghidra-visible input.
- `status=COMPLETE_WITH_PARTIAL_COVERAGE` means the script finished normally,
  but at least one configured bound, partial memory read, inventory dependency,
  per-function decompilation failure, or output listing limit left incomplete
  evidence.
- `status=INCOMPLETE` means an unexpected decompiler subsystem error was
  recorded. `completed` is then false. A Ghidra cancellation is never converted
  into partial evidence: it propagates out of the script and no JSON is written.
- `coverageComplete` is true only when every section in the top-level `coverage`
  object is complete. Each section also records `completed`, `coverageComplete`,
  and `truncated`; metric-specific objects retain their more precise coverage
  fields.

Function-inventory truncation propagates into decoder and normalized-clone
metrics. The `JNI_OnLoad` closure separately records whether any closure
function lacked inventory facts, so an `unresolvedIndirectCallSites` count is
never silently presented as complete. Pointer evidence distinguishes scan
truncation from listing truncation and reports both `detectedCount` and
`listedCount`. Static registration evidence uses the corresponding detected and
listed JNI-triplet counts.

`BlackBoxRootDecompile` places the same `status`, `completed`, and
`coverageComplete` fields at the top of its text corpus. A missing root is a
complete empty result and remains explicit as `[NO ROOT FOUND]`; a closure
budget, failed/timeout decompilation, or truncated pseudocode body produces
partial coverage. An unexpected exception or cancellation propagates and does
not write the output corpus.

## Interpreting evidence

- A single `JNI_OnLoad` export with no persistent registration triplets removes
  an obvious static directory, but a runtime hook can still observe registration.
- Many callers of one decoder candidate, a large normalized clone family, or
  long adjacent code-pointer runs are investigation leads, not automatic
  vulnerabilities. Platform runtime cells and compiler-generated thunks need
  attribution.
- `unresolvedIndirectCallSites` measures a real static-analysis boundary. It
  must not be silently converted into a recovered call edge.
- Raw strings reveal only plaintext present at rest. They do not measure text
  produced immediately before a JNI call.
- Cross-build resistance requires running the same scripts against two default
  randomized builds and comparing addresses, normalized p-code families,
  decoder fanout, code-pointer layouts, and the amount of manual attribution
  needed. One build alone cannot establish per-build diversity.

Store the JSON, pseudocode corpus, Ghidra console log, input hashes, Ghidra
version, and exact command line together. Findings used in release claims must
state which target libraries were actually analyzed and any truncation.
