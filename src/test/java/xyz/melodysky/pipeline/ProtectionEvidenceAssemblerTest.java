package xyz.melodysky.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.LlvmNameMangler;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.protection.LlvmGlobalLayoutResult;
import xyz.melodysky.ir.model.IrBlock;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.ir.model.IrTerminator;
import xyz.melodysky.ir.model.IrType;
import xyz.melodysky.protection.audit.ProtectionApplicability;

class ProtectionEvidenceAssemblerTest {
    private final LlvmNameMangler mangler = new LlvmNameMangler();
    private final ProtectionEvidenceAssembler assembler =
            new ProtectionEvidenceAssembler();

    @Test
    void llvmModelProducesExplicitFactForEveryMethod() {
        IrMethod affected = method("affected");
        IrMethod skipped = method("skipped");

        var report = assembler.llvmModel(
                "LLVM_OPAQUE_PREDICATES",
                true,
                "LLVM_OPAQUE_PREDICATES",
                "LLVM_OPAQUE_PREDICATES_NO_CANDIDATE",
                List.of(affected, skipped),
                List.of(mangler.functionName(affected)),
                List.of(),
                mangler,
                7L);

        assertEquals(2, report.coverageFacts().size());
        var affectedFact = report.coverageFacts().stream()
                .filter(fact -> fact.affected())
                .findFirst()
                .orElseThrow();
        assertTrue(affectedFact.requested());
        assertEquals(
                ProtectionApplicability.APPLICABLE,
                affectedFact.applicability());
        assertEquals("RAN", affectedFact.status());
        var skippedFact = report.coverageFacts().stream()
                .filter(fact -> !fact.affected())
                .findFirst()
                .orElseThrow();
        assertEquals(
                ProtectionApplicability.NOT_APPLICABLE,
                skippedFact.applicability());
        assertEquals("SKIPPED", skippedFact.status());
        assertEquals(
                "LLVM_OPAQUE_PREDICATES_NO_CANDIDATE",
                skippedFact.reasonCode());
    }

    @Test
    void disabledLlvmPassStillSuppliesExplicitUnevaluatedFacts() {
        IrMethod first = method("first");
        IrMethod second = method("second");

        var report = assembler.llvmModel(
                "LLVM_BLOCK_LAYOUT_PERTURBATION",
                false,
                "LLVM_BLOCK_LAYOUT_PERTURBATION",
                "LLVM_BLOCK_LAYOUT_NO_CANDIDATE",
                List.of(first, second),
                List.of(),
                List.of(),
                mangler,
                9L);

        assertEquals(2, report.coverageFacts().size());
        assertTrue(report.coverageFacts().stream()
                .noneMatch(fact -> fact.requested()));
        assertTrue(report.coverageFacts().stream()
                .allMatch(fact -> fact.applicability()
                        == ProtectionApplicability.UNKNOWN));
        assertFalse(report.coverageFacts().stream()
                .anyMatch(fact -> fact.affected()));
    }

    @Test
    void globalLayoutUsesOneModuleSubjectInsteadOfOverclaimingMethods() {
        var report = assembler.llvmGlobalLayout(
                true,
                List.of(method("first"), method("second")),
                new LlvmGlobalLayoutResult(
                        new LlvmModule("pkg/Evidence", List.of()),
                        List.of("j2ll_global"),
                        List.of()),
                11L);

        assertTrue(report.affectedMethods().isEmpty());
        assertEquals(1, report.coverageFacts().size());
        assertTrue(report.coverageFacts().get(0).affected());
        assertEquals(
                ProtectionApplicability.APPLICABLE,
                report.coverageFacts().get(0).applicability());
    }

    @Test
    void validationFailureDoesNotInventPerMethodApplicability() {
        var report = assembler.llvmModel(
                "LLVM_OPAQUE_PREDICATES",
                true,
                "LLVM_OPAQUE_PREDICATES",
                "LLVM_OPAQUE_PREDICATES_NO_CANDIDATE",
                List.of(method("first"), method("second")),
                List.of(),
                List.of("invalid module"),
                mangler,
                13L);

        assertEquals(2, report.coverageFacts().size());
        assertTrue(report.coverageFacts().stream()
                .allMatch(fact -> fact.requested()
                        && !fact.affected()
                        && fact.status().equals("FAILED")
                        && fact.applicability()
                                == ProtectionApplicability.UNKNOWN));
    }

    private IrMethod method(String name) {
        return new IrMethod(
                "pkg/Evidence",
                name,
                "()V",
                IrType.VOID,
                List.of(),
                List.of(new IrBlock(
                        "entry",
                        List.of(),
                        IrTerminator.returnVoid())));
    }
}
