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

`basic` covers stable primitive, array, control-flow, typed-catch, String/JDK, lambda, reflection, resource, ServiceLoader and multi-release JAR smoke paths.

`advanced` covers conservative JVM boundaries such as reflection scans, MethodHandle adapters, raw Unsafe memory, VarHandle, threads/monitors, wait/notify, default-interface super calls, complex finally shapes, annotations, enums, records and inner classes.

## j2ll Smoke Tasks

```sh
bash ./gradlew dTestBasic
bash ./gradlew dTestAdvanced
bash ./gradlew dummyTest
```

- `dTestBasic` builds `Dummy.jar`, runs original `basic`, runs j2ll with protection enabled, runs output `basic`, and compares exit/stdout/stderr.
- `dTestAdvanced` does the same for `advanced` and asserts expected fallback/frontend-skip reason codes.
- `dummyTest` runs the combined `all` mode.

When a dummy test fails, the Gradle console prints a compact failure list with runtime failures, differential mismatches, missing report reason codes, artifact audit/readiness failures and the workspace path.

Expected successful reports include `lowering-report.json`, `protection-report.json`, `artifact-audit.json`, `release-readiness.json`, `support-matrix.json`, `known-blockers.json`, `summary.json` and `summary.md`. Sensitive protection seed and plaintext facts must remain hash-only.
