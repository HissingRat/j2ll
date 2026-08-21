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

This is a representative feature matrix, not an exhaustive enumeration of every JVM instruction, verifier shape or JDK API. Focused frontend, SSA, backend and runtime tests remain responsible for individual opcode and ABI coverage.

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

The three Dummy outcome contracts are asserted by exact owner, method name and descriptor:

- `nativeLowered` requires a final rewrite strategy and native implementation path, must not appear in the skipped-method report, and participates in original-versus-output child-JVM parity. The final JAR must also contain one uniquely registered `ACC_NATIVE` carrier without Code: `nativeOriginal` uses the original method as that carrier, while constructor/class-initializer/interface stubs must have changed Code and invoke the exact carrier after the required Loader guard.
- `skipped` requires the declared stable reason code, no native registration or native implementation metadata, and a canonical ASM comparison proving that access, signature, exceptions, typed instruction operands, control-flow targets, try/catch regions and code maxima remain unchanged. Debug information, stack-map frames, constant-pool indexes and label object identities are intentionally excluded from that comparison.
- `ineligible` covers selected declarations without a lowerable Code body, such as abstract interface/annotation declarations and already-native methods. It must appear only in selector eligibility evidence, remain absent from requested/skipped/registration sets and preserve its declaration shape in the output JAR.

The exact matrix includes representative primitive and numeric conversions, typed catches and single/multi/nested finally paths, constructor and class-initializer rewrites, final/volatile field initialization, private instance calls, a Code-bearing default interface method, a compiler-generated generic bridge, reference identity, reflection field access, MethodHandle adapters and an ordinary captured `Runnable` lambda invoked through JVM interface dispatch. Stable explicit unsupported boundaries cover thread construction/start/join, wait/notify, serializable `altMetafactory` semantics, raw Unsafe memory, dynamic VarHandle access, multi-dimensional array allocation, NIO/stream APIs without an exact bridge policy, default-interface super calls and multi-release classes. It also selects real top-level interface declarations (`zoo.Case` and `zoo.services.ZooService`) as ineligible evidence rather than relying only on nested fixtures.

These tasks prefer an explicitly configured real managed Zig home. Without one, they download the pinned Zig 0.15.2 archive, verify its SHA-256 and reuse a platform-specific cache below the Gradle user home. Parallel Gradle processes serialize cache publication. `--offline` uses an already populated cache and fails clearly when none exists. This automatic Dummy cache is not published as global `j2ll.realHome`, so it does not opt other heavyweight real-Zig suites into the default test run.

To use an existing j2ll distribution instead:

```powershell
$env:J2LL_REAL_HOME = 'C:\path\to\j2ll-distribution'
.\gradlew.bat dTestMethodInternalization
```

When a dummy test fails, the Gradle console prints a compact failure list with runtime failures, differential mismatches, missing report reason codes, artifact audit/readiness failures and the workspace path.

Expected successful reports include `lowering-report.json`, `protection-report.json`, `artifact-audit.json`, `release-readiness.json`, `support-matrix.json`, `known-blockers.json`, `summary.json` and `summary.md`. Sensitive protection seed and plaintext facts must remain hash-only.
