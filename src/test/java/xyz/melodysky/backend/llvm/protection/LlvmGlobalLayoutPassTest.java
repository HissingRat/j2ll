package xyz.melodysky.backend.llvm.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

class LlvmGlobalLayoutPassTest {
    @Test
    void reordersOnlyPrivateAndInternalSlotsWhilePreservingDefinitionsAndReferences() {
        LlvmGlobal first = new LlvmGlobal(
                "hidden_counter",
                "internal global i32 1, section \".j2ll.data\", align 16");
        LlvmGlobal publicAbi =
                new LlvmGlobal("public_abi", "external global i32, align 4");
        LlvmGlobal second = new LlvmGlobal(
                "hidden_pointer",
                "private constant ptr @hidden_counter, section \".j2ll.rodata\", align 8");
        LlvmGlobal retentionRoot = new LlvmGlobal(
                "llvm.used",
                "appending global [2 x ptr] [ptr @hidden_counter, ptr @hidden_pointer], section \"llvm.metadata\"");
        LlvmGlobal third =
                new LlvmGlobal("hidden_long", "internal global i64 2, align 32");
        LlvmModule module = new LlvmModule(
                "pkg/Globals",
                List.of(),
                List.of(first, publicAbi, second, retentionRoot, third),
                List.of(reader()));
        Map<String, String> definitions = definitionsByName(module);

        LlvmGlobalLayoutResult result = new LlvmGlobalLayoutPass()
                .runDetailed(module, enabled(37));

        assertTrue(result.valid());
        assertTrue(result.changed());
        assertEquals(publicAbi, result.module().globals().get(1));
        assertEquals(retentionRoot, result.module().globals().get(3));
        assertEquals(definitions, definitionsByName(result.module()));
        assertEquals(
                List.of("hidden_counter", "hidden_long", "hidden_pointer"),
                result.affectedGlobals());

        String emitted = new LlvmTextEmitter().emit(result.module());
        assertTrue(emitted.contains("ptr @hidden_counter, section \".j2ll.rodata\", align 8"));
        assertTrue(emitted.contains("load i32, ptr @hidden_counter, align 16"));
        assertTrue(emitted.contains(
                "appending global [2 x ptr] [ptr @hidden_counter, ptr @hidden_pointer], section \"llvm.metadata\""));
    }

    @Test
    void sameSeedIsDeterministicAndSeedParticipatesInOrdering() {
        LlvmModule module = globalsOnly("pkg/Seeded", List.of(
                internal("g0", "i32 0"),
                internal("g1", "i32 1"),
                internal("g2", "i32 2"),
                internal("g3", "i32 3"),
                internal("g4", "i32 4")));

        List<String> first = names(new LlvmGlobalLayoutPass()
                .runDetailed(module, enabled(1))
                .module());
        List<String> same = names(new LlvmGlobalLayoutPass()
                .runDetailed(module, enabled(1))
                .module());

        assertEquals(first, same);
        assertTrue(java.util.stream.LongStream.range(2, 64)
                .mapToObj(seed -> names(new LlvmGlobalLayoutPass()
                        .runDetailed(module, enabled(seed))
                        .module()))
                .anyMatch(order -> !order.equals(first)));
    }

    @Test
    void disabledAndSingleCandidateShapesAreNoOps() {
        LlvmModule module = globalsOnly("pkg/Disabled", List.of(
                internal("only", "i32 0"),
                new LlvmGlobal("public_abi", "external global i32")));

        LlvmGlobalLayoutResult disabled = new LlvmGlobalLayoutPass()
                .runDetailed(module, LlvmProtectionConfig.disabled(11));
        LlvmGlobalLayoutResult noCandidate = new LlvmGlobalLayoutPass()
                .runDetailed(module, enabled(11));

        assertSame(module, disabled.module());
        assertFalse(disabled.changed());
        assertSame(module, noCandidate.module());
        assertFalse(noCandidate.changed());
    }

    @Test
    void orderKeyCollisionsUseStableNameTieBreakWithoutDroppingGlobals() {
        LlvmModule module = globalsOnly("pkg/Collision", List.of(
                internal("z", "i32 1"),
                internal("a", "i32 2"),
                internal("m", "i32 3")));
        LlvmGlobalLayoutPass pass = new LlvmGlobalLayoutPass(
                new LlvmModuleValidator(),
                (seed, moduleIdentifier, global) -> "same-order-key");

        LlvmGlobalLayoutResult result = pass.runDetailed(module, enabled(91));

        assertTrue(result.valid());
        assertEquals(List.of("a", "m", "z"), names(result.module()));
        assertEquals(3, result.module().globals().stream()
                .map(LlvmGlobal::name)
                .distinct()
                .count());
    }

    @Test
    void invalidInputIsRejectedBeforeLayout() {
        LlvmModule module = globalsOnly("pkg/Invalid", List.of(
                internal("duplicate", "i32 1"),
                internal("duplicate", "i32 2")));

        LlvmGlobalLayoutResult result = new LlvmGlobalLayoutPass()
                .runDetailed(module, enabled(5));

        assertSame(module, result.module());
        assertFalse(result.valid());
        assertEquals(List.of("duplicate global name: duplicate"), result.validationIssues());
    }

    private LlvmProtectionConfig enabled(long seed) {
        return LlvmProtectionConfig.selected(seed, false, false, false, false, true);
    }

    private LlvmGlobal internal(String name, String initializer) {
        return new LlvmGlobal(name, "internal global " + initializer + ", align 4");
    }

    private LlvmModule globalsOnly(String identifier, List<LlvmGlobal> globals) {
        return new LlvmModule(identifier, List.of(), globals, List.of());
    }

    private List<String> names(LlvmModule module) {
        return module.globals().stream().map(LlvmGlobal::name).toList();
    }

    private Map<String, String> definitionsByName(LlvmModule module) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (LlvmGlobal global : module.globals()) {
            result.put(global.name(), global.definition());
        }
        return result;
    }

    private LlvmFunction reader() {
        return new LlvmFunction(
                "read",
                LlvmLinkage.INTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(),
                List.of(new LlvmBasicBlock(
                        "entry",
                        List.of(LlvmInstruction.raw(
                                Optional.of("%value"),
                                "load i32, ptr @hidden_counter, align 16")),
                        new LlvmTerminator(LlvmType.I32, Optional.of("%value")))));
    }
}
