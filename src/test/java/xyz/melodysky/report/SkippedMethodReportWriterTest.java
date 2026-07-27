package xyz.melodysky.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodNode;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.frontend.classfile.ParsedMethod;
import xyz.melodysky.ir.ssa.SsaMethodResult;
import xyz.melodysky.jvm.AccessFlags;

class SkippedMethodReportWriterTest {
    @Test
    void writesOnlySkippedMethodsInStableIdentityOrder() {
        SsaMethodResult zeta = SsaMethodResult.skipped(
                method("pkg/Zeta", "run", "()V"),
                DiagnosticStage.LOWERING,
                "UNSUPPORTED_NESTED_FINALLY",
                "nested finally is outside the current native-lowering boundary");
        SsaMethodResult alpha = SsaMethodResult.skipped(
                method("pkg/Alpha", "run", "(I)V"),
                DiagnosticStage.LOWERING,
                "UNSUPPORTED_MULTI_EXIT_FINALLY",
                "multi-exit finally is outside the current native-lowering boundary");

        assertEquals("""
                {
                  "schemaVersion": 1,
                  "reportVersion": 1,
                  "confirmationRequired": true,
                  "confirmationDecision": "approved",
                  "entries": [
                    {
                      "selector": "pkg/Alpha#run!(I)V",
                      "class": "pkg/Alpha",
                      "method": "run",
                      "descriptor": "(I)V",
                      "status": "skipped",
                      "hasCode": true,
                      "stage": "LOWERING",
                      "reasonCode": "UNSUPPORTED_MULTI_EXIT_FINALLY",
                      "reason": "multi-exit finally is outside the current native-lowering boundary",
                      "affectsCallers": true
                    },
                    {
                      "selector": "pkg/Zeta#run!()V",
                      "class": "pkg/Zeta",
                      "method": "run",
                      "descriptor": "()V",
                      "status": "skipped",
                      "hasCode": true,
                      "stage": "LOWERING",
                      "reasonCode": "UNSUPPORTED_NESTED_FINALLY",
                      "reason": "nested finally is outside the current native-lowering boundary",
                      "affectsCallers": true
                    }
                  ]
                }
                """, new SkippedMethodReportWriter().json(
                        List.of(zeta, alpha),
                        xyz.melodysky.pipeline.SkippedMethodGateDecision.APPROVED));
    }

    private ParsedMethod method(String owner, String name, String descriptor) {
        MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
        return new ParsedMethod(
                owner,
                name,
                descriptor,
                new AccessFlags(node.access),
                List.of(),
                List.of(),
                List.of(),
                true,
                1,
                1,
                node);
    }
}
