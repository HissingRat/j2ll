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
- `LLVM_NATIVE_PATH` currently covers ordinary static and instance methods whose SSA IR contains supported constants, arithmetic, return, compare/branch/phi, supported field helper calls, monitor/synchronized helper calls, explicit throw bridge calls, static reflection helper calls, supported Unsafe token/int field helpers, volatile fence markers, div/rem ArithmeticException helper calls, broad primitive/reference array helper calls, selected primitive/reference/object allocation helpers, selected type helpers, selected String helpers, Math int/long scalar helpers, selected same-class static/private-special direct calls, and narrow no-arg `int` virtual/interface dispatch helpers. JNI wrapper C only bridges `JNIEnv*` / `jclass` / `jobject` / `jarray` / primitive ABI and calls the LLVM-generated hidden function.
- Field helper-backed LLVM methods use deterministic field tokens plus JNI `GetFieldID` / `Get<Type>Field` / `Set<Type>Field` / static equivalents. They include `int`/`long`/reference field pass-through, own null receiver exception behavior, and do not read or write Java object memory directly.
- Unsafe helper-backed LLVM methods use deterministic reflection metadata tokens plus JNI field APIs for `getInt` / `putInt`; `compareAndSwapInt` is a conservative monitor-backed smoke path and `allocateInstance` uses JNI `AllocObject`. These helpers are packaged in the native library as JVM-hosted runtime support, not as native object layout access.
- `TEMPLATE_JNI_PATH` remains the covered path for String content operations beyond the current String helper subset, primitive `int[]` copy templates not emitted through LLVM helpers, exception bridge templates, nativeEmbeddedClassBlob fallback smoke path, generic straight-line/simple-branch constructor/class-initializer body helpers outside ordinary `nativeOriginal`, and object/reference-heavy semantics outside the current LLVM helper subset.
- The owner class receives a generated or prepended `<clinit>` trigger that calls the generated loader before the first native method call. Existing `<clinit>` bytecode remains after the loader trigger.
- The generated loader embeds selected target resource paths and SHA-256 values, chooses the current runtime OS/arch, rejects unsupported runtime OS/arch with `UnsatisfiedLinkError`, extracts to a per-classloader content-addressed cache path, and calls `System.load`.
- The host C skeleton registers methods through `JNI_OnLoad` / `RegisterNatives`; Java method JNI wrapper symbols are `static`/internal, LLVM implementation symbols are hidden and not exported, while bootstrap exports are audited. Monitor, throw, reflection and fallback helpers all operate through JNI APIs and pending-exception conventions, not native object memory.
- Protection v1 is part of this packaging path: LLVM implementation names may be deterministic `j2ll_f_<sha256>` hidden linkable symbols shared by the LLVM module and JNI wrapper; StringConcat constant carriers may be emitted as encrypted native constant tables and decoded through JNI `NewStringUTF`; fallback helper bytes remain encoded native blobs rather than plain JAR classes.
- Selected-target native build routes through managed `ZigToolchain`: Zig `0.15.2` is resolved from `<j2ll-home>/zig/zig(.exe)` or installed from an existing/downloaded official archive beside `j2ll.jar` and normalized into `<j2ll-home>/zig`; `.ll`, Zig-managed `.o`, JNI wrapper C, runtime helper C and fallback blob carrier sources are compiled/linked by generated `build.zig` before packaging embeds the buildable selected target libraries. Java-side native build commands must remain managed `zig build`, with no direct host `cc` / `clang` / `zig cc` fallback. `NativeBuildPlan` records selected target preflight separately from buildable units, and packaging report records `selectedTargets`, `buildableTargets`, `skippedTargets`, `failedTargets`, Zig path/version and `ZIG_TARGET_PREFLIGHT` diagnostics.
- Runtime E2E currently covers `LLVM_NATIVE_PATH` static primitive scalar add/long/double/boolean compare/void no-op/if-else/nested-if/phi merge, static/instance/volatile field read/write/add through JNI field helpers, synchronized block/method monitor helpers, explicit throw bridge, static reflection method/constructor/field helper path, Unsafe statically resolved `objectFieldOffset` token / `getInt` / `putInt` / monitor-backed `compareAndSwapInt` / `allocateInstance`, typed-int VarHandle helpers, null receiver NPE ownership, String/reference field pass-through/null return, div/rem ArithmeticException helper semantics, broad primitive/reference array helpers, `System.arraycopy` primitive/object/overlap/异常 helper, selected primitive/reference array allocation helpers, ordinary-method object construction helper subset, `checkcast` / `instanceof`, String `length` / `equals` / `isEmpty` / `charAt` / `startsWith` / `endsWith` / `substring(int,int)` helpers, explicit StringBuilder append chain, StringConcatFactory `makeConcat` / common `makeConcatWithConstants`, LambdaMetafactory common `metafactory` helper, LDC MethodHandle + `invokeExact` direct call, Math int/long/float/double helpers, Integer/Long/Boolean/Double boxing-unboxing, Objects.requireNonNull/equals, selected static/private-special caller -> selected callee direct LLVM call, and no-arg `int` virtual/interface dispatch helpers; template/helper E2E covers multiple owner classes and methods in one shared embedded library, String content `jstring` read/return via JNI APIs, `int[]` copy/new-array paths, `ThrowNew` exception bridge smoke path, generic straight-line/simple-branch constructor/class-initializer body helpers, and repeated loader/register calls.
- `nativeEmbeddedClassBlob` has a minimum real path for one `halfLowered` fallback fixture: helper class bytes are encoded with v1 `j2ll-rle-byte-pairs-v1` plus `xor-sha256-key-stream-v1`, compiled into the native artifact, verified with native-side SHA-256 checks through JVM `MessageDigest`, decoded and defined lazily with JNI `DefineClass` for the owner classloader, reused through per-classloader cache slots, and never emitted as a plain JAR `.class` entry. The packaging report records `definitionMechanism: "DefineClass"` and `definitionMechanismReasonCode: "FALLBACK_DEFINE_CLASS"` until hidden-class definition is implemented.
- This slice is intentionally narrow. Constructor/class-initializer body helpers currently support default-super, straight-line simple assignment/arithmetic/String/int-array/static-field shapes plus simple no-block-arg branch/goto; interface method declaration stubs, broader constructor/object LLVM semantics, complex finally/exception state merge, dynamic or parameterized reflection, hidden-class fallback definition and real non-host cross-target link/strip remain separate runtime work.

## JAR Preservation

The output JAR must remain runnable:

- preserve manifest main attributes unless j2ll explicitly owns a generated `J2LL-*` attribute.
- preserve `Main-Class`, agent attributes, `Automatic-Module-Name` and `Multi-Release`.
- preserve non-class resources byte-for-byte unless a documented policy owns them.
- preserve `META-INF/services/*` provider lines.
- preserve `module-info.class` unless module-aware rewrite is explicitly implemented.
- preserve `META-INF/versions/**`; versioned class lowering is a separate policy.

Signed input handling follows `signaturePolicy` from `docs/io-config-output-contract.md`.

## Validator

Packaging validator checks:

- every `lowered` and `halfLowered` method has bytecode rewrite, native artifact and registration entry.
- every selected target library exists in workspace and output JAR.
- generated loader classes exist and are reachable from rewritten owner classes.
- no plain fallback class entries are emitted for `nativeEmbeddedClassBlob`.
- manifest/resource/signature policy was applied and reported.
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
- child JVM differential tests for covered current-host runtime paths.
