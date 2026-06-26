package xyz.melodysky.analysis.callgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.classfile.AsmClassParser;
import xyz.melodysky.frontend.classfile.ClassFileEntry;
import xyz.melodysky.frontend.classfile.ParsedProgram;
import xyz.melodysky.testsupport.AsmFixtureBuilder;

class CallSiteCollectorTest {
    @Test
    void collectsInvokeInstructionsWithStableIds() {
        ParsedProgram program = new ParsedProgram(List.of(new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Caller.class",
                        AsmFixtureBuilder.classWithVirtualCall("pkg/Caller", "pkg/Target"),
                        "fixture"))
                .artifact()
                .orElseThrow()));

        List<CallSite> callSites = new CallSiteCollector().collect(program);

        assertEquals(1, callSites.size());
        assertEquals("pkg/Caller#call!(Lpkg/Target;)V@1", callSites.get(0).id());
        assertEquals(InvokeKind.VIRTUAL, callSites.get(0).kind());
        assertEquals("pkg/Target", callSites.get(0).declaredOwner());
    }

    @Test
    void includesLambdaMetafactoryImplementationTarget() {
        ParsedProgram program = new ParsedProgram(List.of(new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Lambda.class",
                        AsmFixtureBuilder.classWithLambdaMetafactoryMethods("pkg/Lambda"),
                        "fixture"))
                .artifact()
                .orElseThrow()));

        List<CallSite> callSites = new CallSiteCollector().collect(program);

        assertEquals(11, callSites.size());
        assertEquals(
                1,
                callSites.stream()
                        .filter(site -> site.id().endsWith("$lambdaTarget")
                                && site.declaredOwner().equals("pkg/Lambda")
                                && site.declaredTarget().name().equals("targetRun"))
                        .count());
    }

    @Test
    void includesDirectMethodHandleInvokeExactTarget() {
        ParsedProgram program = new ParsedProgram(List.of(new AsmClassParser()
                .parse(new ClassFileEntry(
                        "pkg/Handles.class",
                        AsmFixtureBuilder.classWithMethodHandleInvokeExact("pkg/Handles"),
                        "fixture"))
                .artifact()
                .orElseThrow()));

        List<CallSite> callSites = new CallSiteCollector().collect(program);

        assertEquals(
                1,
                callSites.stream()
                        .filter(site -> site.id().endsWith("$methodHandleTarget")
                                && site.declaredOwner().equals("pkg/Handles")
                                && site.declaredTarget().name().equals("target"))
                        .count());
    }
}
