package xyz.melodysky.analysis.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import xyz.melodysky.analysis.callgraph.CallResolution;
import xyz.melodysky.analysis.callgraph.CallSite;
import xyz.melodysky.analysis.callgraph.CallTarget;
import xyz.melodysky.analysis.callgraph.ChaCallResolver;
import xyz.melodysky.analysis.callgraph.InvokeKind;
import xyz.melodysky.analysis.hierarchy.AnalysisWorld;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.ClassHierarchyBuilder;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.jvm.MethodSignature;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class RtaCallResolverTest implements Opcodes {
    private static final MethodSignature RUN = new MethodSignature("run", "()V");
    private static final MethodSignature CALLER = new MethodSignature("caller", "()V");

    @Test
    void excludesUninstantiatedSubtypeTargets() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                objectEntry(),
                entry("pkg/Base", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Base",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC)),
                entry("pkg/Child", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Child",
                        "pkg/Base",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC)),
                entry("pkg/Other", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Other",
                        "pkg/Base",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC))));
        CallSite site = new CallSite("site", "pkg/Caller", CALLER, 0, InvokeKind.VIRTUAL, "pkg/Base", RUN);
        CallResolution cha = new ChaCallResolver(hierarchy).resolve(site);
        RuntimeTypeResult runtimeTypes = new RuntimeTypeResult(Set.of("pkg/Child"), false, List.of());

        CallResolution rta = new RtaCallResolver(hierarchy, runtimeTypes).refine(cha);

        assertEquals(List.of("pkg/Child#run!()V"), rta.targets().stream().map(CallTarget::displayName).toList());
    }

    @Test
    void unknownAllocationKeepsConservativeFallback() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                objectEntry(),
                entry("pkg/Base", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Base",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC))));
        CallSite site = new CallSite("site", "pkg/Caller", CALLER, 0, InvokeKind.VIRTUAL, "pkg/Base", RUN);
        CallResolution cha = new ChaCallResolver(hierarchy).resolve(site);
        RuntimeTypeResult runtimeTypes = new RuntimeTypeResult(
                Set.of("pkg/Base"),
                true,
                List.of(AllocationSite.unknown("pkg/Caller", CALLER, 1)));

        CallResolution rta = new RtaCallResolver(hierarchy, runtimeTypes).refine(cha);

        assertTrue(rta.conservative());
        assertTrue(rta.hasUnknownTarget());
    }

    @Test
    void chaUnknownTargetIsNeverDiscardedByPreciseRuntimeTypes() {
        ClassHierarchy hierarchy = hierarchy(List.of(
                objectEntry(),
                entry("pkg/Base", AsmFixtureBuilder.classWithVoidMethod(
                        "pkg/Base",
                        "java/lang/Object",
                        null,
                        ACC_PUBLIC,
                        "run",
                        ACC_PUBLIC))));
        CallSite site = new CallSite(
                "site",
                "pkg/Caller",
                CALLER,
                0,
                InvokeKind.VIRTUAL,
                "pkg/Base",
                RUN);
        CallResolution cha = new CallResolution(
                site,
                List.of(
                        CallTarget.known("pkg/Base", RUN),
                        CallTarget.unknownExternal("HIERARCHY_INCOMPLETE")),
                true,
                "CHA_VIRTUAL");

        CallResolution rta = new RtaCallResolver(
                        hierarchy,
                        new RuntimeTypeResult(Set.of("pkg/Base"), false, List.of()))
                .refine(cha);

        assertEquals("RTA_PRESERVED_CHA_UNKNOWN", rta.reason());
        assertEquals(cha.targets(), rta.targets());
        assertTrue(rta.hasUnknownTarget());
    }

    private ClassHierarchy hierarchy(List<ClassFileEntry> entries) {
        AsmClassParser parser = new AsmClassParser();
        ParsedProgram program = new ParsedProgram(entries.stream()
                .map(entry -> parser.parse(entry).artifact().orElseThrow())
                .toList());
        return new ClassHierarchyBuilder().build(program, AnalysisWorld.PARTIAL_WORLD).artifact().orElseThrow();
    }

    private ClassFileEntry objectEntry() {
        return entry("java/lang/Object", AsmFixtureBuilder.classHeader("java/lang/Object", null, null, ACC_PUBLIC));
    }

    private ClassFileEntry entry(String internalName, byte[] bytes) {
        return new ClassFileEntry(internalName + ".class", bytes, "fixture");
    }
}
