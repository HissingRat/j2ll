package xyz.melodysky.backend.llvm.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmTextEmitter;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

class LlvmCallIndirectionPassTest {
    @Test
    void rewritesDirectModuleCallThroughHiddenFunctionPointerTableByDefault() {
        LlvmModule module = module();

        LlvmCallIndirectionResult result = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.enabled(7));
        String text = new LlvmTextEmitter().emit(result.module());

        assertTrue(result.changed());
        assertEquals(List.of("j2ll_caller"), result.affectedFunctions());
        assertEquals("CALL_INDIRECTION_TABLE", result.reasonCode());
        assertEquals(1, result.dispatcherSymbols().size());
        String table = result.dispatcherSymbols().get(0);
        assertTrue(table.startsWith("j2ll_cit_"));
        assertTrue(text.contains("@" + table + " = internal constant [1 x ptr] [ptr @j2ll_callee]"));
        assertTrue(text.matches("(?s).*getelementptr inbounds \\[1 x ptr], ptr @" + table + ", i32 0, i32 0.*"));
        assertTrue(text.contains("load ptr, ptr %j2ll_indirect_slot_j2ll_callee_0"));
        assertTrue(text.contains("%r = call i32 (i32) %j2ll_indirect_fn_j2ll_callee_0(i32 %p0)"));
    }

    @Test
    void groupsSameSignatureTargetsBehindOneTableWithSeededOrder() {
        LlvmModule module = new LlvmModule(
                "pkg/MultiCallOps",
                List.of(
                        function(
                                "j2ll_left",
                                List.of(new LlvmBasicBlock(
                                        "entry",
                                        List.of(LlvmInstruction.raw(Optional.of("%sum"), "add i32 %p0, 1")),
                                        new LlvmTerminator(LlvmType.I32, Optional.of("%sum"))))),
                        function(
                                "j2ll_right",
                                List.of(new LlvmBasicBlock(
                                        "entry",
                                        List.of(LlvmInstruction.raw(Optional.of("%sum"), "add i32 %p0, 2")),
                                        new LlvmTerminator(LlvmType.I32, Optional.of("%sum"))))),
                        function(
                                "j2ll_caller",
                                List.of(new LlvmBasicBlock(
                                        "entry",
                                        List.of(
                                                LlvmInstruction.raw(Optional.of("%l"), "call i32 @j2ll_left(i32 %p0)"),
                                                LlvmInstruction.raw(Optional.of("%r"), "call i32 @j2ll_right(i32 %l)")),
                                        new LlvmTerminator(LlvmType.I32, Optional.of("%r")))))));

        LlvmCallIndirectionResult result = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.enabled(19));
        String text = new LlvmTextEmitter().emit(result.module());

        assertTrue(result.changed());
        assertEquals(List.of("j2ll_caller"), result.affectedFunctions());
        assertEquals(1, result.dispatcherSymbols().size());
        String table = result.dispatcherSymbols().get(0);
        assertTrue(table.startsWith("j2ll_cit_"));
        assertTrue(text.contains("@" + table + " = internal constant [2 x ptr] ["));
        assertTrue(text.contains("ptr @j2ll_left"));
        assertTrue(text.contains("ptr @j2ll_right"));
        assertEquals(2, countOccurrences(text, "load ptr, ptr %j2ll_indirect_slot_"));
        assertTrue(text.contains("%l = call i32 (i32) %j2ll_indirect_fn_j2ll_left_0(i32 %p0)"));
        assertTrue(text.contains("%r = call i32 (i32) %j2ll_indirect_fn_j2ll_right_1(i32 %l)"));
    }

    @Test
    void tableIndirectionKeepsInstanceReceiverSignatureOnIndirectCall() {
        LlvmFunction callee = new LlvmFunction(
                "j2ll_instance_callee",
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(new LlvmParameter(LlvmType.PTR, "%self"), new LlvmParameter(LlvmType.I32, "%value")),
                List.of(new LlvmBasicBlock(
                        "entry",
                        List.of(LlvmInstruction.raw(Optional.of("%sum"), "add i32 %value, 1")),
                        new LlvmTerminator(LlvmType.I32, Optional.of("%sum")))));
        LlvmFunction caller = new LlvmFunction(
                "j2ll_instance_caller",
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(new LlvmParameter(LlvmType.PTR, "%self"), new LlvmParameter(LlvmType.I32, "%value")),
                List.of(new LlvmBasicBlock(
                        "entry",
                        List.of(LlvmInstruction.raw(
                                Optional.of("%r"),
                                "call i32 @j2ll_instance_callee(ptr %self, i32 %value)")),
                        new LlvmTerminator(LlvmType.I32, Optional.of("%r")))));
        LlvmModule module = new LlvmModule("pkg/InstanceCallOps", List.of(callee, caller));

        LlvmCallIndirectionResult result = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.enabled(23));
        String text = new LlvmTextEmitter().emit(result.module());

        assertTrue(text.contains(
                "%r = call i32 (ptr, i32) %j2ll_indirect_fn_j2ll_instance_callee_0(ptr %self, i32 %value)"));
    }

    @Test
    void dispatcherFallbackStillUsesHiddenSwitch() {
        LlvmModule module = module();

        LlvmCallIndirectionResult result = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.dispatcher(7));
        String text = new LlvmTextEmitter().emit(result.module());

        assertTrue(result.changed());
        assertEquals("CALL_INDIRECTION_DISPATCHER", result.reasonCode());
        String dispatcher = result.dispatcherSymbols().get(0);
        assertTrue(dispatcher.startsWith("j2ll_cid_"));
        assertTrue(text.contains("define external hidden i32 @" + dispatcher + "(i32 %j2ll_selector, i32 %p0)"));
        assertTrue(text.matches("(?s).*%r = call i32 @" + dispatcher + "\\(i32 [0-9]+, i32 %p0\\).*"));
        assertTrue(text.contains("switch i32 %j2ll_selector"));
        assertTrue(text.contains("%j2ll_indirect_result = call i32 @j2ll_callee(i32 %p0)"));
    }

    @Test
    void disabledPassLeavesModuleUnchanged() {
        LlvmModule module = module();

        LlvmCallIndirectionResult result = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.disabled(7));

        assertFalse(result.changed());
        assertEquals("PROTECTION_PASS_DISABLED", result.reasonCode());
        assertEquals(new LlvmTextEmitter().emit(module), new LlvmTextEmitter().emit(result.module()));
    }

    @Test
    void dispatcherNameIsDeterministicBySeed() {
        LlvmModule module = module();

        LlvmCallIndirectionResult first = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.enabled(7));
        LlvmCallIndirectionResult second = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.enabled(7));
        LlvmCallIndirectionResult different = new LlvmCallIndirectionPass()
                .run(module, LlvmProtectionConfig.enabled(8));

        assertEquals(first.dispatcherSymbols(), second.dispatcherSymbols());
        assertFalse(first.dispatcherSymbols().equals(different.dispatcherSymbols()));
    }

    private LlvmModule module() {
        return new LlvmModule(
                "pkg/CallOps",
                List.of(
                        function(
                                "j2ll_callee",
                                List.of(new LlvmBasicBlock(
                                        "entry",
                                        List.of(LlvmInstruction.raw(Optional.of("%sum"), "add i32 %p0, 1")),
                                        new LlvmTerminator(LlvmType.I32, Optional.of("%sum"))))),
                        function(
                                "j2ll_caller",
                                List.of(new LlvmBasicBlock(
                                        "entry",
                                        List.of(LlvmInstruction.raw(Optional.of("%r"), "call i32 @j2ll_callee(i32 %p0)")),
                                        new LlvmTerminator(LlvmType.I32, Optional.of("%r")))))));
    }

    private LlvmFunction function(String name, List<LlvmBasicBlock> blocks) {
        return new LlvmFunction(
                name,
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                LlvmType.I32,
                List.of(new LlvmParameter(LlvmType.I32, "%p0")),
                blocks);
    }

    private int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
