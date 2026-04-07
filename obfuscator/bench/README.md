# obf-bench

Java 25 benchmark fixture for `j2ll`.

It intentionally mixes:

- ordinary classes and packages
- inner, nested, local, and anonymous classes
- enums
- records and sealed hierarchies
- arrays
- lambdas and method references
- generics and default interface methods
- exceptions and try-with-resources
- text blocks
- a `NativeSlice` subset that is friendly to the current IR pipeline
- regression slices for reference equality, `invokespecial`, constructor chains,
  method-reference propagation, annotation reflection, try/catch swallowing,
  concurrent native calls, `long` string concatenation, and supplementary-plane
  Unicode/emoji string concatenation

The jar entry point prints one line per scenario:

```text
array test -> pass
lambda test -> fail: ...
```

The benchmark sources now live under `obfuscator/bench/src/main/java` and are built through the Gradle `bench` source set.

Use `scripts/run-obf-bench.ps1` or `./gradlew obfBench` to:

1. build `obf-bench.jar`
2. run the original jar
3. build the obfuscator
4. obfuscate the jar with the IR pipeline
5. run the obfuscated jar
6. print frontend skips if present
7. fail the bench if any benchmark methods were not native-lowered

`obf-bench` now uses a strict gate:

- obfuscated runtime must pass
- at least some methods must actually be rewritten to `native`
- `frontend-skips.txt` must be empty

If any target methods still fall back to Java, the script exits non-zero and the report marks the bench as `FAIL`.
