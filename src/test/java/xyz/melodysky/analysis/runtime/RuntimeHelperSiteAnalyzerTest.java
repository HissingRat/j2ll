package xyz.melodysky.analysis.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.hierarchy.DefaultInterfaceAnalysis;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrInstruction;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.MethodRewriteDecision;
import xyz.melodysky.packaging.MethodRewriteStrategy;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.runtime.PureNativeJdkRuntimeHelpers;
import xyz.melodysky.testsupport.AsmFixtureBuilder;
import xyz.melodysky.toolchain.NativeImplementationPath;
import xyz.melodysky.toolchain.NativeMethodImplementation;

class RuntimeHelperSiteAnalyzerTest implements Opcodes {
    private static final DefaultInterfaceAnalysis NO_DEFAULT_INTERFACES =
            new DefaultInterfaceAnalysis(Set.of(), Set.of());

    private final RuntimeHelperSiteAnalyzer analyzer = new RuntimeHelperSiteAnalyzer();

    @Test
    void distinguishesDirectLlvmCallsFromJvmOwnedCallBoundaries() {
        ParsedMethod source = sourceMethod();
        String directTarget = "pkg/Callee#value!()I";
        String jdkTarget = "java/util/Arrays#copyOf!([II)[I";
        IrMethod irMethod = methodWith(
                source,
                IrInstruction.call(Optional.empty(), IrOpcode.CALL_STATIC, List.of(), directTarget),
                IrInstruction.call(Optional.empty(), IrOpcode.CALL_STATIC, List.of(), jdkTarget));

        List<RuntimeHelperSite> sites = analyzer.analyze(
                SsaMethodResult.nativeLowered(source, irMethod),
                Optional.empty(),
                Optional.of(implementationWithDirectTarget(source, directTarget)),
                NO_DEFAULT_INTERFACES);

        assertTrue(sites.contains(new RuntimeHelperSite("direct:" + directTarget, "DIRECT_LLVM_CALL")));
        assertTrue(sites.contains(new RuntimeHelperSite("call:" + jdkTarget, "JDK_COLLECTION_HELPER")));
    }

    @Test
    void classifiesNamedRuntimeHelpersThroughThePublicAnalyzer() {
        ParsedMethod source = sourceMethod();
        IrMethod irMethod = methodWith(
                source,
                IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_string_length"),
                IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        "j2ll_rt_thread_sleep"));

        List<RuntimeHelperSite> sites = analyzer.analyze(
                SsaMethodResult.nativeLowered(source, irMethod),
                Optional.empty(),
                Optional.empty(),
                NO_DEFAULT_INTERFACES);

        assertTrue(sites.contains(new RuntimeHelperSite("j2ll_rt_string_length", "STRING_HELPER")));
        assertTrue(sites.contains(new RuntimeHelperSite("j2ll_rt_thread_sleep", "THREAD_HELPER")));
    }

    @Test
    void reportsFinalIntrinsicIrInsteadOfTheRawJdkDispatchChain() {
        ParsedMethod source = sourceMethod();
        String rawTarget = "java/nio/ByteBuffer#array!()[B";
        IrMethod raw = methodWith(
                source,
                IrInstruction.call(
                        Optional.empty(),
                        IrOpcode.CALL_VIRTUAL,
                        List.of(),
                        rawTarget));
        IrMethod finalIr = methodWith(
                source,
                IrInstruction.operation(
                        Optional.empty(),
                        IrOpcode.CALL_RUNTIME_HELPER,
                        List.of(),
                        PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_FINISH));

        List<RuntimeHelperSite> sites = analyzer.analyze(
                SsaMethodResult.nativeLowered(source, raw),
                Optional.of(finalIr),
                Optional.empty(),
                Optional.empty(),
                NO_DEFAULT_INTERFACES);

        assertTrue(sites.contains(new RuntimeHelperSite(
                PureNativeJdkRuntimeHelpers.I32_BIG_ENDIAN_FRAME_FINISH,
                "JDK_INTRINSIC_HELPER")));
        assertFalse(sites.stream().anyMatch(site -> site.helper().contains(rawTarget)));
    }

    @Test
    void reportsUnsupportedWaitNotifyAndDefaultInterfaceBoundariesWithoutFallbackNames() {
        ParsedMethod source = sourceMethod();
        String defaultTarget = "pkg/DefaultApi#run!()V";
        String waitTarget = "java/lang/Object#wait!()V";
        IrMethod irMethod = methodWith(
                source,
                IrInstruction.call(Optional.empty(), IrOpcode.CALL_SPECIAL, List.of(), defaultTarget),
                IrInstruction.call(Optional.empty(), IrOpcode.CALL_INTERFACE, List.of(), defaultTarget),
                IrInstruction.call(Optional.empty(), IrOpcode.CALL_VIRTUAL, List.of(), waitTarget));

        List<RuntimeHelperSite> sites = analyzer.analyze(
                SsaMethodResult.nativeLowered(source, irMethod),
                Optional.empty(),
                Optional.empty(),
                new DefaultInterfaceAnalysis(Set.of(defaultTarget), Set.of("run!()V")));

        assertTrue(sites.contains(new RuntimeHelperSite("call:" + waitTarget, "WAIT_NOTIFY_UNSUPPORTED")));
        assertTrue(sites.contains(new RuntimeHelperSite(
                "defaultInterfaceSuperUnsupported:" + defaultTarget,
                "DEFAULT_INTERFACE_SUPER_UNSUPPORTED")));
        assertTrue(sites.contains(new RuntimeHelperSite(
                "defaultInterfaceDispatchUnsupported:" + defaultTarget,
                "DEFAULT_INTERFACE_DISPATCH_UNSUPPORTED")));
        assertFalse(sites.stream().anyMatch(site -> site.helper().contains("Fallback")
                || site.reasonCode().contains("FALLBACK")));
    }

    private ParsedMethod sourceMethod() {
        var parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Caller.class",
                        AsmFixtureBuilder.classWithVoidMethod(
                                "pkg/Caller",
                                "java/lang/Object",
                                null,
                                ACC_PUBLIC | ACC_SUPER,
                                "run",
                                ACC_PUBLIC | ACC_STATIC),
                        "fixture"))
                .artifact()
                .orElseThrow();
        return parsedClass.methods().stream()
                .filter(method -> method.name().equals("run"))
                .findFirst()
                .orElseThrow();
    }

    private IrMethod methodWith(ParsedMethod source, IrInstruction... instructions) {
        return new IrMethod(
                source.owner(),
                source.name(),
                source.descriptor(),
                IrType.VOID,
                List.of(),
                List.of(new IrBlock("entry", List.of(instructions), IrTerminator.returnVoid())));
    }

    private NativeMethodImplementation implementationWithDirectTarget(
            ParsedMethod source,
            String directTarget) {
        NativeRegistrationEntry entry =
                new NativeRegistrationEntry(source.owner(), source.name(), source.descriptor(), "j2ll_test_entry");
        MethodRewriteDecision decision = new MethodRewriteDecision(
                source,
                MethodRewriteStrategy.NATIVE_ORIGINAL,
                source.owner(),
                Optional.empty(),
                "TEST");
        return new NativeMethodImplementation(
                entry,
                decision,
                NativeImplementationPath.LLVM_NATIVE_PATH,
                Optional.of("j2ll_test_impl"),
                "TEST",
                false,
                false,
                List.of(),
                List.of(directTarget),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
    }
}
