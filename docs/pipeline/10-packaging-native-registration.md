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
- Store fallback class bytes in selected target native libraries.
- Record blob metadata in the native fallback manifest and packaging report.
- Define fallback helpers lazily per classloader and reuse them for later calls.
- If no implemented helper definition mechanism works for the target JDK, fail preflight with a clear diagnostic.

`nativeSnippetInterpreter` is a future research direction and not near-term implementation work.

## Loader And Registration

Generated loader responsibilities:

- choose the embedded library for current OS/arch.
- extract it to a per-classloader content-addressed temp/cache path.
- verify SHA-256 before `System.load`.
- call exported bootstrap/JNI wrapper to register owner-class native methods.
- define fallback helper classes when a `halfLowered` method needs JVM helper fallback.

Registration rules:

- normal class methods register against their owner class.
- constructor and class initializer body helpers register as generated helper methods.
- interface method helpers register against generated helper classes, not the interface method declaration.
- registration tables are grouped by owner/registration class and deterministic.

Concurrency rules:

- loader state is per classloader.
- extraction/load/register is idempotent and thread-safe.
- failures throw `UnsatisfiedLinkError` with enough context to locate the target and class.

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
