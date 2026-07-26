package xyz.melodysky.backend.llvm.protection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmIrCallIndirectionRef;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmModuleValidator;

/**
 * Lowers explicit IR call-plan metadata to hidden LLVM pointer tables.
 *
 * <p>This pass does no Java call resolution. It consumes only call sites that
 * the IR planner already approved and leaves all unmarked calls untouched, so
 * the independent LLVM {@code indirectCalls} option cannot double-transform
 * the same site.</p>
 */
public final class LlvmIrCallIndirectionPass {
    public LlvmIrCallIndirectionResult runDetailed(LlvmModule module) {
        List<String> inputIssues = new LlvmModuleValidator().validate(module);
        if (!inputIssues.isEmpty()) {
            return new LlvmIrCallIndirectionResult(module, List.of(), List.of(), inputIssues);
        }
        Map<String, LlvmFunction> functions = module.functions().stream()
                .collect(java.util.stream.Collectors.toMap(
                        LlvmFunction::name,
                        function -> function,
                        (left, right) -> left,
                        LinkedHashMap::new));
        Planning planning = plan(module, functions);
        if (!planning.issues().isEmpty() || planning.groups().isEmpty()) {
            return new LlvmIrCallIndirectionResult(
                    module,
                    List.of(),
                    List.of(),
                    planning.issues());
        }

        ArrayList<LlvmFunction> rewritten = new ArrayList<>();
        ArrayList<String> affected = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            FunctionRewrite result = rewriteFunction(function, planning.groups());
            rewritten.add(result.function());
            if (result.changed()) {
                affected.add(function.name());
            }
        }
        ArrayList<LlvmGlobal> globals = new ArrayList<>(module.globals());
        planning.groups().values().stream()
                .sorted(Comparator.comparing(GroupPlan::groupId))
                .map(this::tableGlobal)
                .forEach(globals::add);
        LlvmModule candidate =
                new LlvmModule(module.identifier(), module.declarations(), globals, rewritten);
        List<String> outputIssues = new LlvmModuleValidator().validate(candidate);
        if (!outputIssues.isEmpty()) {
            return new LlvmIrCallIndirectionResult(module, List.of(), List.of(), outputIssues);
        }
        return new LlvmIrCallIndirectionResult(
                candidate,
                affected,
                planning.groups().values().stream().map(GroupPlan::tableSymbol).toList(),
                List.of());
    }

    public LlvmModule run(LlvmModule module) {
        return runDetailed(module).module();
    }

    private Planning plan(
            LlvmModule module,
            Map<String, LlvmFunction> functions) {
        Map<String, Map<String, EntryPlan>> entriesByGroup = new LinkedHashMap<>();
        ArrayList<String> issues = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            for (LlvmBasicBlock block : function.blocks()) {
                for (LlvmInstruction instruction : block.instructions()) {
                    if (instruction.irCallIndirection().isEmpty()) {
                        continue;
                    }
                    LlvmIrCallIndirectionRef reference =
                            instruction.irCallIndirection().orElseThrow();
                    Optional<String> targetName = directCallTarget(instruction);
                    if (targetName.isEmpty() || !functions.containsKey(targetName.orElseThrow())) {
                        issues.add("IR call-indirection site is not a local direct LLVM call in "
                                + function.name());
                        continue;
                    }
                    LlvmFunction target = functions.get(targetName.orElseThrow());
                    EntryPlan candidate =
                            new EntryPlan(reference.entryId(), target.name(), Signature.of(target));
                    EntryPlan previous = entriesByGroup
                            .computeIfAbsent(reference.groupId(), ignored -> new LinkedHashMap<>())
                            .putIfAbsent(reference.entryId(), candidate);
                    if (previous != null && !previous.equals(candidate)) {
                        issues.add("IR call-indirection entry maps to conflicting targets: "
                                + reference.entryId());
                    }
                }
            }
        }
        LinkedHashMap<String, GroupPlan> groups = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, EntryPlan>> group : entriesByGroup.entrySet()) {
            List<EntryPlan> entries =
                    group.getValue().values().stream().sorted().toList();
            long signatures = entries.stream().map(EntryPlan::signature).distinct().count();
            if (signatures != 1) {
                issues.add("IR call-indirection group mixes function signatures: " + group.getKey());
                continue;
            }
            groups.put(group.getKey(), new GroupPlan(
                    group.getKey(),
                    tableSymbol(module.identifier(), group.getKey()),
                    entries));
        }
        return new Planning(
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(groups)),
                List.copyOf(issues));
    }

    private FunctionRewrite rewriteFunction(
            LlvmFunction function,
            Map<String, GroupPlan> groups) {
        boolean changed = false;
        int siteIndex = 0;
        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        for (LlvmBasicBlock block : function.blocks()) {
            ArrayList<LlvmInstruction> instructions = new ArrayList<>();
            for (LlvmInstruction instruction : block.instructions()) {
                if (instruction.irCallIndirection().isEmpty()) {
                    instructions.add(instruction);
                    continue;
                }
                LlvmIrCallIndirectionRef reference =
                        instruction.irCallIndirection().orElseThrow();
                GroupPlan group = groups.get(reference.groupId());
                if (group == null) {
                    instructions.add(instruction);
                    continue;
                }
                int entryIndex = group.indexOf(reference.entryId());
                String suffix = hash(function.name() + ":" + block.name() + ":" + siteIndex++, 16);
                String slot = "%j2ll_irci_slot_" + suffix;
                String callee = "%j2ll_irci_fn_" + suffix;
                instructions.add(LlvmInstruction.raw(
                        Optional.of(slot),
                        "getelementptr inbounds [" + group.entries().size()
                                + " x ptr], ptr @" + group.tableSymbol()
                                + ", i32 0, i32 " + entryIndex));
                instructions.add(LlvmInstruction.raw(
                        Optional.of(callee),
                        "load ptr, ptr " + slot));
                instructions.add(indirectCall(instruction, callee));
                changed = true;
            }
            blocks.add(new LlvmBasicBlock(block.name(), instructions, block.terminator()));
        }
        if (!changed) {
            return new FunctionRewrite(function, false);
        }
        return new FunctionRewrite(new LlvmFunction(
                function.name(),
                function.linkage(),
                function.visibility(),
                function.returnType(),
                function.parameters(),
                blocks), true);
    }

    private LlvmInstruction indirectCall(LlvmInstruction instruction, String callee) {
        String raw = instruction.rawText().orElseThrow();
        int at = raw.indexOf('@');
        int open = raw.indexOf('(', at);
        String rewritten = raw.substring(0, at) + callee + raw.substring(open);
        return LlvmInstruction.raw(instruction.result(), rewritten);
    }

    private LlvmGlobal tableGlobal(GroupPlan group) {
        String initializer = group.entries().stream()
                .map(entry -> "ptr @" + entry.targetFunction())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return new LlvmGlobal(
                group.tableSymbol(),
                "internal constant [" + group.entries().size() + " x ptr] ["
                        + initializer + "], align 8");
    }

    private Optional<String> directCallTarget(LlvmInstruction instruction) {
        if (instruction.rawText().isEmpty()) {
            return Optional.empty();
        }
        String raw = instruction.rawText().orElseThrow();
        if (!raw.startsWith("call ")) {
            return Optional.empty();
        }
        int at = raw.indexOf('@');
        int open = raw.indexOf('(', at);
        if (at < 0 || open < 0) {
            return Optional.empty();
        }
        return Optional.of(raw.substring(at + 1, open));
    }

    private String tableSymbol(String module, String groupId) {
        return "j2ll_ircit_" + hash(module + ":" + groupId, 32);
    }

    private String hash(String input, int chars) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, chars);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Planning(Map<String, GroupPlan> groups, List<String> issues) {
    }

    private record GroupPlan(
            String groupId,
            String tableSymbol,
            List<EntryPlan> entries) {
        private int indexOf(String entryId) {
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).entryId().equals(entryId)) {
                    return index;
                }
            }
            throw new IllegalArgumentException("unknown IR call-indirection entry " + entryId);
        }
    }

    private record EntryPlan(
            String entryId,
            String targetFunction,
            Signature signature) implements Comparable<EntryPlan> {
        @Override
        public int compareTo(EntryPlan other) {
            return entryId.compareTo(other.entryId);
        }
    }

    private record Signature(
            xyz.melodysky.backend.llvm.model.LlvmType returnType,
            List<xyz.melodysky.backend.llvm.model.LlvmType> parameters) {
        private static Signature of(LlvmFunction function) {
            return new Signature(
                    function.returnType(),
                    function.parameters().stream().map(parameter -> parameter.type()).toList());
        }
    }

    private record FunctionRewrite(LlvmFunction function, boolean changed) {
    }
}
