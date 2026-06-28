# Getting Started

This beta package is a JVM-hosted JAR obfuscator/native-lowering tool. The output is still a runnable JAR and loads embedded native libraries through the JVM, JNI, generated loader code, RegisterNatives, runtime helpers, and fallback blobs.

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
java -jar build/dist/j2ll/j2ll.jar validate docs/examples/minimal-config.json
```

Dry-run a config without building native artifacts or writing a final output JAR:

```sh
java -jar build/dist/j2ll/j2ll.jar dry-run docs/examples/minimal-config.json build/j2ll-dry-run
```

Build an input JAR:

```sh
java -jar build/dist/j2ll/j2ll.jar build docs/examples/minimal-config.json build/j2ll-workspace
java -jar build/j2ll-workspace/output/input.jar example.Main
```

## Managed Zig

The schema v1 native toolchain is managed Zig `0.15.2`.

- j2ll looks for `zig/zig` or `zig/zig.exe` next to `j2ll.jar`.
- If the executable is missing or has the wrong version, j2ll first looks for the official Zig `0.15.2` archive in the same directory.
- If no local archive is present, j2ll downloads from `https://ziglang.org/download/0.15.2/`.
- Local and downloaded archives are checked against the expected SHA-256 before extraction.
- Signature verification is reported as `notVerifiedBoundary` until signature verification is fully wired.
- Extracted archives are normalized so the final executable is `zig/zig` or `zig/zig.exe`.

Checksum mismatch, corrupt archive, missing metadata for the current host, or an unbuildable required target exits with code `4` and writes reports.

## Outputs And Reports

Successful build:

- `output/<input-name>.jar`
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

## Common Failures

- Invalid config: exit `2`; see `reports/diagnostics.json`.
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
