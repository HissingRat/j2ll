# dummy

`Dummy.jar` is a layered feature-zoo sample used by the rewrite tests. It is a normal runnable JAR built from source under `src/dummy`, not a checked-in binary fixture.

## Build

```sh
bash ./gradlew buildDummy
```

The JAR is written to:

```text
build/dummy/Dummy.jar
```

## Run Original

```sh
java --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 \
  -jar build/dummy/Dummy.jar basic
java --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 \
  -jar build/dummy/Dummy.jar advanced
java --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow \
  -Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8 \
  -jar build/dummy/Dummy.jar all
```

`basic` covers stable primitive, array, control-flow, typed-catch, String/JDK, lambda, class/interface polymorphism, reflection, resource, ServiceLoader and manifest smoke paths.

`advanced` covers conservative JVM boundaries such as reflection scans, MethodHandle adapters, raw Unsafe memory, VarHandle, threads/monitors, wait/notify, default-interface super calls, complex finally shapes, annotations, enums, records, inner classes, sealed classes, multi-dimensional arrays, text blocks, pattern matching, record patterns, pattern switches, try-with-resources suppressed exceptions, serialization, dynamic proxies, custom class loaders, resource bundles, fixed-locale formatting, NIO file APIs and module metadata.

## j2ll Smoke Tasks

```sh
bash ./gradlew dTestBasic
bash ./gradlew dTestAdvanced
bash ./gradlew dummyTest
bash ./gradlew dTestMethodInternalization
```

- `dTestBasic` builds `Dummy.jar`, runs original `basic`, runs j2ll with protection enabled, runs output `basic`, and compares exit/stdout/stderr.
- `dTestAdvanced` does the same for `advanced` and asserts expected skipped-method reason codes.
- `dummyTest` runs the combined `all` mode.
- `dTestMethodInternalization` runs two removal/parity cases: an explicitly allowlisted public static method under user-approved current-JAR-only analysis, and non-final public/protected same-owner instance plus protected static targets under a declared closed world. It verifies that the internal targets disappear from the output JAR while the child JVM output stays identical. The current-JAR-only case also asserts the aggregate unresolved-reflection risk warning; focused pipeline tests retain ambiguous-dispatch and cross-owner instance methods.

On Windows these runtime tests need a real managed Zig home because the test-only fake Zig fixture supports Linux and macOS only. Point it at a j2ll distribution containing `zig/zig.exe` before running the task:

```powershell
$env:J2LL_REAL_HOME = 'C:\path\to\j2ll-distribution'
.\gradlew.bat dTestMethodInternalization
```

When a dummy test fails, the Gradle console prints a compact failure list with runtime failures, differential mismatches, missing report reason codes, artifact audit/readiness failures and the workspace path.

Expected successful reports include `lowering-report.json`, `protection-report.json`, `artifact-audit.json`, `release-readiness.json`, `support-matrix.json`, `known-blockers.json`, `summary.json` and `summary.md`. Sensitive protection seed and plaintext facts must remain hash-only.
