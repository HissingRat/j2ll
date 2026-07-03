# 10 Packaging And Native Registration

本阶段把 compiler output 重新打包成可运行 JAR，并把 selected target 动态库嵌入到配置指定路径。它只消费前面阶段生成的 IR/backend/toolchain metadata，不重新做 bytecode lowering。

## 输入

- original input JAR entries
- rewritten method plan
- native registration plan
- selected target dynamic libraries
- fallback blob plan
- manifest/resource/signature policy
- symbol audit result

## 输出

- final output JAR
- rewritten owner classes
- generated loader/helper classes
- embedded dynamic libraries
- `reports/packaging-report.json`
- `reports/support-matrix.json`
- `reports/opcode-support-matrix.json`
- `reports/known-blockers.json`
- `reports/release-readiness.json`
- `reports/summary.json`
- JAR preservation summary and signature action report

## 推荐包

```text
xyz.melodysky.packaging
```

推荐类型：

- `Repackager`
- `JarRewriter`
- `MethodRewritePlanner`
- `MethodRewriteStrategy`
- `NativeMethodRewriter`
- `ConstructorStubRewriter`
- `ClassInitializerStubRewriter`
- `InterfaceMethodStubRewriter`
- `FallbackBlobPlanner`
- `NativeEmbeddedFallbackBlob`
- `FallbackClassDefiner`
- `LoaderClassGenerator`
- `NativeRegistrationPlanner`
- `RegisterNativesTableBuilder`
- `OutputJarLayout`

## Method Rewrite Strategy

Packaging must record one rewrite strategy per matched method:

- `nativeOriginal`: ordinary class method with Code. Remove Code, set `ACC_NATIVE`, register the original name/descriptor with `RegisterNatives`.
- `constructorStub`: keep `<init>` legal Java bytecode, preserve constructor delegation, then call a generated private native body helper after object initialization.
- `classInitializerStub`: keep or generate `<clinit>` as loader/bootstrap stub, then call generated native body helper.
- `interfaceMethodStub`: keep interface method legal Java bytecode and call a generated helper class that owns the native method.
- `notApplicable`: abstract, already-native, no-Code interface method, annotation element, or another no-body declaration.

`<init>`, `<clinit>` and interface methods must not be forced into `nativeOriginal`.

## Fallback Blobs

Schema version 1 uses `fallbackMode: "nativeEmbeddedClassBlob"`.

Rules:

- Do not emit plain generated fallback `.class` entries in the output JAR.
- Store fallback class bytes in selected target native libraries as encoded blobs.
- Record blob metadata in the native fallback manifest and packaging report, including original and encoded SHA-256 values.
- Define fallback helpers lazily per classloader and reuse them for later calls.
- If no implemented helper definition mechanism works for the target JDK, fail preflight with a clear diagnostic.

No other schema version 1 fallback modes are defined. Unknown fallback modes are config errors.

## Loader And Registration

Generated loader responsibilities:

- choose the embedded library for current OS/arch.
- extract it to a per-classloader content-addressed temp/cache path.
- verify SHA-256 before `System.load`.
- call exported bootstrap/JNI wrapper to register owner-class native methods.
- define fallback helper classes when a `halfLowered` method needs JVM helper fallback.

Registration rules:

- normal class methods register against their owner class.
- constructor and class initializer body helpers register as same-owner generated private static native helper methods.
- interface method helpers register against generated helper classes, not the interface method declaration.
- registration tables are grouped by owner/registration class and deterministic.
- registration class lookup must not trigger selected owner `<clinit>` before its generated native helper has been registered; current JNI registration uses a no-initialize `Class.forName(name, false, contextClassLoader)` lookup for owner classes.

Concurrency rules:

- loader state is per classloader.
- extraction/load/register is idempotent and thread-safe.
- failures throw `UnsatisfiedLinkError` with enough context to locate the target and class.

Current implemented slice:

- Host-only ordinary class methods can use `nativeOriginal` for covered executable implementations. Each registered method records `nativeImplementationPath` as `LLVM_NATIVE_PATH` or `TEMPLATE_JNI_PATH` in lowering reports.
- `LLVM_NATIVE_PATH` currently covers ordinary static and instance methods whose SSA IR contains supported constants, arithmetic, return, compare/branch/phi, table/lookup switch terminators, JVM numeric helper opcodes, supported field helper calls, monitor/synchronized helper calls, explicit throw bridge calls, static reflection helper calls including constant-parameter method/constructor descriptors, supported Unsafe token/int field helpers, volatile fence markers, div/rem ArithmeticException helper calls, broad primitive/reference array helper calls, selected primitive/reference/object allocation helpers, selected type helpers, selected String helpers, Math int/long scalar helpers, selected same-class static/private-special direct calls, and tokenized virtual/interface JVM dispatch helpers for no-arg int, int-arg int, reference return and single-reference-argument/reference-return shapes. JNI wrapper C only bridges `JNIEnv*` / `jclass` / `jobject` / `jarray` / primitive ABI and calls the LLVM-generated hidden function; `JNIEnv*` is only passed to hidden LLVM functions whose lowered body actually needs JNI/runtime state.
- Field helper-backed LLVM methods use deterministic field tokens plus JNI `GetFieldID` / `Get<Type>Field` / `Set<Type>Field` / static equivalents. They include `int`/`long`/reference field pass-through, own null receiver exception behavior, and do not read or write Java object memory directly.
- Unsafe helper-backed LLVM methods use deterministic reflection metadata tokens plus JNI field APIs for `getInt` / `putInt`; `compareAndSwapInt` is a conservative monitor-backed smoke path and `allocateInstance` uses JNI `AllocObject`. These helpers are packaged in the native library as JVM-hosted runtime support, not as native object layout access.
- `TEMPLATE_JNI_PATH` remains the covered path for String content operations beyond the current String helper subset, primitive `int[]` copy templates not emitted through LLVM helpers, exception bridge templates, nativeEmbeddedClassBlob fallback invocation/body bridge, generic straight-line/simple-branch constructor/class-initializer body helpers outside ordinary `nativeOriginal`, and object/reference-heavy semantics outside the current LLVM helper subset.
- The owner class receives a generated or prepended `<clinit>` trigger that calls the generated loader before the first native method call. Existing `<clinit>` bytecode remains after the loader trigger.
- The generated loader embeds selected target resource paths and SHA-256 values, chooses the current runtime OS/arch, rejects unsupported runtime OS/arch with `UnsatisfiedLinkError`, extracts to a per-classloader content-addressed cache path, and calls `System.load`.
- The host C skeleton registers methods through `JNI_OnLoad` / `RegisterNatives`; Java method JNI wrapper symbols are `static`/internal, LLVM implementation symbols are hidden and not exported, while bootstrap exports are audited. Monitor, throw, reflection and fallback helpers all operate through JNI APIs and pending-exception conventions, not native object memory.
- Protection v1 is part of this packaging path: LLVM implementation names may be deterministic `j2ll_f_<sha256>` hidden linkable symbols shared by the LLVM module and JNI wrapper; same-class selected static/private direct LLVM calls default to hidden `j2ll_cit_<sha256>` signature-group function-pointer tables in the per-class `.ll` consumed by Zig, with hidden `j2ll_cid_<sha256>` dispatcher switches retained as fallback; StringConcat/ordinary string constant carriers may be emitted as encrypted native constant tables and decoded through JNI `NewStringUTF`; fallback helper bytes remain encoded native blobs rather than plain JAR classes.
- Selected-target native build routes through managed `ZigToolchain`: Zig `0.15.2` is resolved from `<j2ll-home>/zig/zig(.exe)` or installed from an existing/downloaded official archive beside `j2ll.jar`; local/downloaded archives must match built-in official SHA-256 metadata before extraction and normalization into `<j2ll-home>/zig`. Signature verification is currently a reported boundary (`signatureStatus=notVerifiedBoundary`). `.ll`, Zig-managed `.o`, JNI wrapper C, runtime helper C and fallback blob carrier sources are compiled/linked by generated `build.zig` before packaging embeds the buildable selected target libraries. Java-side native build commands must remain managed `zig build`, with no direct host `cc` / `clang` / `zig cc` fallback. `NativeBuildPlan` records selected target preflight separately from buildable units, selected targets are required by default, and packaging report records `selectedTargets`, `requiredTargets`, `buildableTargets`, `skippedTargets`, `failedTargets`, Zig path/version, bootstrap events with archiveName/archiveSha256/checksumStatus/signatureStatus/source, OS/arch classifier, library extension, Zig target triple, required/buildable state, expected artifact path/name/resource path, loader extraction path policy, symbol visibility policy, failure kind, exact reason, required capability, platform SDK requirement, build log tail and Windows PDB exclusion policy. A required selected target that current preflight cannot build reports `ZIG_TARGET_UNBUILDABLE`, blocks final output JAR writing, and fails the pipeline rather than being treated as a successful skip.
- Runtime E2E currently covers `LLVM_NATIVE_PATH` static primitive scalar add/long/double/boolean compare/void no-op/if-else/nested-if/phi merge/table-switch/lookup-switch/JVM numeric helper opcodes, protected float/double constant raw-bit encryption through integer XOR + LLVM bitcast, protected CFF dispatcher switch for simple branch methods, static/instance/volatile field read/write/add through JNI field helpers, synchronized block/method monitor helpers, explicit throw bridge, static reflection method/constructor/field/setAccessible helper path with no-arg、reference、primitive 和 array constant-parameter method/constructor invoke, typed `Field.get/set` for int/boolean/long/double plus reference get/set, private method/constructor accessible smoke, dynamic reflection bridge path, Unsafe statically resolved `objectFieldOffset` token / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` / `allocateInstance`, typed-int VarHandle helpers, null receiver NPE ownership, String/reference field pass-through/null return, div/rem ArithmeticException helper semantics, broad primitive/reference array helpers, `System.arraycopy` byte/int/long/double/object/overlap/null/oob/ArrayStoreException helper, selected primitive/reference array allocation helpers, ordinary-method object construction helper subset, `checkcast` / `instanceof`, String `length` / `equals` / `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)` helpers, explicit StringBuilder append chain, StringConcatFactory `makeConcat` / common `makeConcatWithConstants`, LambdaMetafactory common `metafactory` helper, LDC MethodHandle + `invokeExact` direct call, MethodHandle adapter chain bridge path, Math int/long/float/double helpers, Integer/Long/Boolean/Double boxing-unboxing, Objects.requireNonNull/equals, selected static/private-special caller -> selected callee direct LLVM call through hidden table indirection, and tokenized virtual/interface/default-interface JVM dispatch helpers for no-arg int, int-arg int, reference return and single-reference-argument/reference-return shapes including conflict boundary reporting; default-interface super `I.super.m()` is covered as a `frontendSkipped` no-rewrite boundary. Template/helper E2E covers multiple owner classes and methods in one shared embedded library, String content `jstring` read/return via JNI APIs, `int[]` copy/new-array paths, `ThrowNew` exception bridge smoke path, generic straight-line/simple-branch constructor/class-initializer body helpers, repeated loader/register calls, and nativeEmbeddedClassBlob fallback for unsupported JDK call, ArrayList/HashMap/Arrays/Collections/Optional/String.format narrow policy, Throwable message/cause common path, Thread start/join common path, wait/notify boundary, unsupported altMetafactory capture shape, instance receiver/reference return, exception propagation and two-classloader isolation.
- Beta acceptance uses the distribution package rather than the test classpath: Gradle `betaAcceptance` depends on `distJ2ll`, executes `build/dist/j2ll/j2ll.jar` for `--help`, `--version`, config validation, dry-run, sample build, child JVM output comparison and report/readiness checks.
- `nativeEmbeddedClassBlob` has a real ordinary-method fallback body path: for `halfLowered` ordinary methods whose descriptor can be bridged through JNI, the original method bytecode is copied into a same-package helper class as static synthetic `invoke`. Static fallback wrappers call `invoke` with original parameters; instance fallback wrappers pass `self` as the first helper parameter. Primitive/reference parameters and returns are bridged through the matching `CallStatic<Type>Method`, and pending exceptions from the helper are left pending for the Java caller. Helper class bytes are encoded with v1 `j2ll-rle-byte-pairs-v1` plus `xor-sha256-key-stream-v1`, compiled into the native artifact, verified with native-side SHA-256 checks through JVM `MessageDigest`, decoded and defined lazily by packaged `J2llFallbackSupport` using owner-private `MethodHandles.Lookup#defineHiddenClass`; if that mechanism is unavailable it falls back to JNI `DefineClass` for the owner classloader. The helper is reused through a process-lifetime linked cache keyed by fallback id + classloader identity and is never emitted as a plain JAR `.class` entry. The packaging report records `fallbackInvokeDescriptor`, `fallbackReasonCode`, `definitionMechanism`, hidden-class capability fields (`definitionMechanismReasonCode`, `hiddenClassApiAvailable`, `ownerLookupSupported`, `definitionMechanismReason`), cache reason (`FALLBACK_CACHE_REUSE`) and cache lifecycle fields (`cacheScope`, `cacheKey`, `cacheLifetime`, `globalReferencePolicy`, `unloadAware=false`, `futurePath`). Runtime registration for isolated child classloaders relies on the thread context classloader during loader-triggered `JNI_OnLoad` / `RegisterNatives`.
- This slice is intentionally narrow. Constructor/class-initializer body helpers currently support straight-line simple assignment/arithmetic/String/int-array/static-field shapes plus simple no-block-arg branch/goto; interface method declaration stubs, default-interface super CallNonvirtual helper semantics, broader constructor/object LLVM semantics, complex finally/exception state merge/monitor-finally interaction/nested finally, reflection descriptor matrices beyond the current typed field/helper subset, broader virtual/interface dispatch, unload-aware fallback cache and real non-host cross-target link/strip remain separate runtime work. Safe single-exit catch-all cleanup is covered by release suite; multi-exit/state-merge/monitor/jsr finally shapes remain no-rewrite seed boundaries.

## JAR Preservation

The output JAR must remain runnable:

- preserve manifest main attributes unless j2ll explicitly owns a generated `J2LL-*` attribute.
- preserve `Main-Class`, agent attributes, `Automatic-Module-Name` and `Multi-Release`.
- preserve non-class resources byte-for-byte unless a documented policy owns them.
- preserve `META-INF/services/*` provider lines.
- preserve `module-info.class` unless module-aware rewrite is explicitly implemented.
- preserve `META-INF/versions/**`; versioned class lowering is a separate policy.
- when a base class has a `META-INF/versions/<n>/...` counterpart, selected base methods are recorded as `notApplicable` with `MULTI_RELEASE_VERSIONED_CLASS`; they are not rewritten and are not registered as native methods because the JVM may select the versioned class at runtime.

Signed input handling follows `signaturePolicy` from `docs/io-config-output-contract.md`.

Current implementation records `preservationSummary` in `reports/packaging-report.json` with manifest, service entry, module-info, multi-release and versioned-entry facts. Versioned classes are preserved as entries. If a class also appears under `META-INF/versions/**`, the base class remains bytecode-backed for matching methods and lowering reports `MULTI_RELEASE_VERSIONED_CLASS` rather than emitting a native registration that could target the runtime-selected versioned class. A child JVM multi-release/service fixture verifies that ServiceLoader, module-info and versioned class selection keep their original runtime behavior.

Signature handling records `signatureAction` in `reports/packaging-report.json`: `fail` rejects signed input before rewrite and does not emit a successful final JAR, `strip` removes `META-INF/*.SF/*.RSA/*.DSA/*.EC` and emits a warning, and `resign` runs signing config/keystore/password/alias preflight before rewrite then signs the generated output JAR with the current JDK `jarsigner`. Failed resign preflight or signer execution records `action: resignFailed`, `SIGNATURE_RESIGN_FAILED` and a precise reason such as `SIGNATURE_RESIGN_INVALID_KEYSTORE` or `SIGNATURE_RESIGN_TOOL_UNAVAILABLE`, and it does not keep a final JAR. Successful resign records `SIGNATURE_RESIGNED`; test coverage uses a deterministic temporary PKCS12 keystore and `jarsigner -verify`.

## Validator

Packaging validator checks:

- every `lowered` and `halfLowered` method has bytecode rewrite, native artifact and registration entry.
- every selected target library exists in workspace and output JAR.
- generated loader classes exist and are reachable from rewritten owner classes.
- no plain fallback class entries are emitted for `nativeEmbeddedClassBlob`.
- `reports/artifact-audit.json` records artifact audit v2.2 checks for no plaintext fallback classes, no legacy output paths, configured native resource path, embedded native SHA-256 consistency, j2ll metadata/targetArtifacts consistency, reports-manifest hash/entries, hidden/internal symbol export policy, Windows PDB exclusion and sensitive-plaintext facts. Connected `LLVM_NATIVE_PATH` facts, stable TEMPLATE constructor/body helper string facts (`TEMPLATE_JNI_PATH_STABLE_SURFACE`) and StringConcat constant carrier stable generated-C facts are blocking; reflection/lambda/MethodHandle metadata facts are hash-only observed-only. Fallback blob plaintext literal facts remain conservative, but binary metadata/carrier checks are blocking for encoded/original SHA, size/encoding policy and accidental original method/class plaintext in carrier C. The report also records generated C, helper C, per-class LLVM `.ll`, build.zig, native-resource, output-JAR, symbol-audit, packaging-report and metadata surface coverage, with skipped-surface reasons such as `surfaceNotGenerated`, `nonBlockingPathKind` or `unavailableOnTarget`. Artifact audit is a finalization gate: failure removes or withholds the final JAR, emits `ARTIFACT_AUDIT_FAILED` in `reports/failure-report.json`, and makes readiness report `finalArtifactWritten=false`.
- manifest/resource/signature policy was applied and reported.
- release target artifact policy was reported: selected/required/buildable/failed targets, OS/arch classifier, library extension, expected artifact path/name/resource path, loader extraction policy, symbol visibility policy, actual current-host SHA-256/exported symbols and Windows PDB exclusion policy.
- release-readiness reports were emitted: artifact audit, support matrix, opcode matrix, known blockers, readiness gate result and summary report with required report/field checks; release suite workspaces additionally include `reports/release-suite-summary.json` for strict suite readiness. Gate v6 requires release-blocking known blocker reasons (`beta-blocker` / `rc-blocker`) to be covered by suite expected statuses/diagnostics or weird-bytecode seed coverage; uncovered beta blockers make `betaProfilePassed=false`, while `future-blocker` and explicit `non-goal` rows are still reported but do not fail beta/RC strict readiness. It verifies expected config/toolchain/artifact failure cases do not produce output runs or final JARs and have failure report/stage/reason evidence, verifies successful cases have output runs and passed artifact audit, verifies target artifact evidence and requires `determinismEvidenceComplete`. RC profile additionally requires `missingCategories=[]` across the required release categories. It writes machine-readable `missingEvidence`, `suiteCoverageByBlocker`, `blockerEvidenceComplete`, `targetEvidenceComplete`, `finalArtifactWritten`, `determinismEvidenceComplete`, `metadataConsistencyPassed`, `blockingSensitiveFactsPassed`, `targetPackagePlanComplete` and `strictModePassed`.
- final artifact retention policy is part of packaging validation: config failure, Zig/toolchain target failure, signing failure, artifact audit failure, readiness failure and dry-run must report `finalArtifactWritten=false`; successful builds retain output JAR + reports + configured intermediates, while failed finalization keeps reports/failure sidecars and withholds or deletes the final JAR.
- output JAR entry ordering is deterministic.

## 测试

- ordinary method `nativeOriginal` rewrite.
- `<init>` constructor stub rewrite.
- `<clinit>` loader/body-helper rewrite.
- interface default/static/private method stub rewrite.
- abstract/already-native/no-Code method `notApplicable` report.
- fallback blob manifest and no plain fallback class entry.
- loader idempotency and failure diagnostic.
- manifest/resource/service preservation.
- signed input `fail`, `strip` and `resign` policy tests.
- output JAR layout and embedded library naming tests.
- release-readiness gate/report golden tests, including strict suite mode.
- deterministic corpus/release suite runner tests for original/output child JVM exit code/stdout/stderr parity, stable suite manifest ordering, blocker coverage and useful failure diagnostics.
- release suite cases for config validation failure, unknown-field warning success, signed fail/strip/resign, service loader + multi-release + module-info preservation, reflection/MethodHandle/lambda fallback, raw Unsafe boundary, wait/notify boundary, non-host target preflight failure, JDK collection/Optional/Throwable/Thread fallback, and realistic CLI/reflection/packaging samples.
- pipeline finalization-gate tests proving artifact audit failure removes the final JAR and aligns failure/readiness reports.
- beta acceptance test proving the dist package can validate, dry-run, build and run a sample output JAR while report index, summary, readiness and metadata stay privacy-safe.
- child JVM differential tests for covered current-host runtime paths.
