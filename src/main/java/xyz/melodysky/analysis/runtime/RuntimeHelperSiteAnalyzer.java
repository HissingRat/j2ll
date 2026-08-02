package xyz.melodysky.analysis.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import xyz.melodysky.analysis.hierarchy.DefaultInterfaceAnalysis;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrTerminatorKind;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.runtime.jni.JniTypeMapper;
import xyz.melodysky.toolchain.NativeMethodImplementation;

/** Collects helper-backed runtime evidence from an already-lowered method. */
public final class RuntimeHelperSiteAnalyzer {
    private final RuntimeHelperSiteClassifier classifier = new RuntimeHelperSiteClassifier();
    private final JniTypeMapper jniTypeMapper = new JniTypeMapper();

    public List<RuntimeHelperSite> analyze(
            SsaMethodResult result,
            Optional<NativeRegistrationEntry> registration,
            Optional<NativeMethodImplementation> implementation,
            DefaultInterfaceAnalysis defaultInterfaces) {
        return analyze(
                result,
                result.irMethod(),
                registration,
                implementation,
                defaultInterfaces);
    }

    public List<RuntimeHelperSite> analyze(
            SsaMethodResult result,
            Optional<IrMethod> finalIrMethod,
            Optional<NativeRegistrationEntry> registration,
            Optional<NativeMethodImplementation> implementation,
            DefaultInterfaceAnalysis defaultInterfaces) {
        ParsedMethod source = result.sourceMethod();
        if (finalIrMethod.isEmpty()) {
            return jniAbiSite(source, registration);
        }

        ArrayList<RuntimeHelperSite> sites = new ArrayList<>();
        addMethodBoundarySites(source, sites);
        var method = finalIrMethod.orElseThrow();
        method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(classifier::isReportable)
                .map(instruction -> classifier.classify(instruction, implementation))
                .distinct()
                .forEach(sites::add);
        method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_VIRTUAL
                        || instruction.opcode() == IrOpcode.CALL_INTERFACE)
                .map(instruction -> new RuntimeHelperSite(
                        "dispatch:" + instruction.symbol().orElse(instruction.opcode().name()),
                        "DISPATCH_HELPER"))
                .distinct()
                .forEach(sites::add);
        method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_INTERFACE)
                .map(instruction -> new RuntimeHelperSite(
                        "defaultInterfaceDispatch:" + instruction.symbol().orElse(instruction.opcode().name()),
                        "DEFAULT_INTERFACE_DISPATCH_HELPER"))
                .distinct()
                .forEach(sites::add);
        method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_SPECIAL)
                .filter(instruction -> instruction.symbol()
                        .map(defaultInterfaces.methodKeys()::contains)
                        .orElse(false))
                .flatMap(instruction -> defaultInterfaceSuperSites(instruction.symbol().orElse(instruction.opcode().name())))
                .distinct()
                .forEach(sites::add);
        method.blocks().stream()
                .flatMap(block -> block.instructions().stream())
                .filter(instruction -> instruction.opcode() == IrOpcode.CALL_INTERFACE)
                .filter(instruction -> instruction.symbol()
                        .map(this::methodSignatureFromKey)
                        .map(defaultInterfaces.conflictSignatures()::contains)
                        .orElse(false))
                .flatMap(instruction -> defaultInterfaceConflictSites(
                        instruction.symbol().orElse(instruction.opcode().name())))
                .distinct()
                .forEach(sites::add);
        method.blocks().stream()
                .filter(block -> block.terminator().kind() == IrTerminatorKind.THROW)
                .map(block -> new RuntimeHelperSite("exception:" + block.name(), "EXCEPTION_HELPER"))
                .distinct()
                .forEach(sites::add);
        sites.addAll(jniAbiSite(source, registration));
        return sites.stream()
                .distinct()
                .sorted(Comparator.comparing(RuntimeHelperSite::reasonCode).thenComparing(RuntimeHelperSite::helper))
                .toList();
    }

    private void addMethodBoundarySites(ParsedMethod source, List<RuntimeHelperSite> sites) {
        if (source.name().equals("<init>")) {
            sites.add(new RuntimeHelperSite("constructor:" + source.methodKey(), "CONSTRUCTOR_BODY_HELPER"));
        }
        if (source.name().equals("<clinit>")) {
            sites.add(new RuntimeHelperSite(
                    "classInitializer:" + source.methodKey(),
                    "CLASS_INITIALIZER_BODY_HELPER"));
        }
        if (source.accessFlags().isSynchronized()) {
            sites.add(new RuntimeHelperSite(
                    "synchronizedMethod:" + source.methodKey(),
                    "SYNCHRONIZED_METHOD_HELPER"));
        }
    }

    private Stream<RuntimeHelperSite> defaultInterfaceSuperSites(String target) {
        return Stream.of(
                new RuntimeHelperSite("defaultInterfaceSuper:" + target, "UNSUPPORTED_DEFAULT_INTERFACE_SUPER"),
                new RuntimeHelperSite(
                        "defaultInterfaceSuperUnsupported:" + target,
                        "DEFAULT_INTERFACE_SUPER_UNSUPPORTED"));
    }

    private Stream<RuntimeHelperSite> defaultInterfaceConflictSites(String target) {
        return Stream.of(
                new RuntimeHelperSite(
                        "defaultInterfaceConflict:" + target,
                        "UNSUPPORTED_DEFAULT_INTERFACE_CONFLICT"),
                new RuntimeHelperSite(
                        "defaultInterfaceDispatchUnsupported:" + target,
                        "DEFAULT_INTERFACE_DISPATCH_UNSUPPORTED"));
    }

    private List<RuntimeHelperSite> jniAbiSite(
            ParsedMethod source,
            Optional<NativeRegistrationEntry> registration) {
        return registration.map(entry -> {
            String prototype = jniTypeMapper
                    .methodDescriptor(source.owner(), source.name(), source.descriptor(), source.accessFlags().isStatic())
                    .cPrototype(entry.nativeSymbol());
            return List.of(new RuntimeHelperSite("jni:" + prototype, "JNI_ABI_REGISTER_NATIVES"));
        }).orElseGet(List::of);
    }

    private String methodSignatureFromKey(String methodKey) {
        int hash = methodKey.indexOf('#');
        return hash < 0 ? methodKey : methodKey.substring(hash + 1);
    }
}
