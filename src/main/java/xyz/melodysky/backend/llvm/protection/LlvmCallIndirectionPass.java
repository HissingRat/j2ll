package xyz.melodysky.backend.llvm.protection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmFunction;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmLinkage;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmNativeUnwindSemantics;
import xyz.melodysky.backend.llvm.model.LlvmParameter;
import xyz.melodysky.backend.llvm.model.LlvmSwitchCase;
import xyz.melodysky.backend.llvm.model.LlvmTerminator;
import xyz.melodysky.backend.llvm.model.LlvmType;
import xyz.melodysky.backend.llvm.model.LlvmVisibility;

public final class LlvmCallIndirectionPass {
    public String name() {
        return "CALL_INDIRECTION";
    }

    public LlvmCallIndirectionResult run(LlvmModule module, LlvmProtectionConfig config) {
        if (!config.enabled() || !config.indirectCalls()) {
            return new LlvmCallIndirectionResult(module, List.of(), List.of(), "PROTECTION_PASS_DISABLED");
        }
        Map<String, LlvmFunction> functionsByName = new LinkedHashMap<>();
        for (LlvmFunction function : module.functions()) {
            functionsByName.put(function.name(), function);
        }
        Map<Signature, List<String>> targetsBySignature = targetsBySignature(module, functionsByName.keySet());
        if (targetsBySignature.isEmpty()) {
            return new LlvmCallIndirectionResult(module, List.of(), List.of(), unsupportedReason(config));
        }

        Map<String, IndirectTarget> indirectionByTarget = indirectionByTarget(module, config, targetsBySignature);
        LinkedHashSet<String> affectedFunctions = new LinkedHashSet<>();
        ArrayList<LlvmFunction> rewrittenFunctions = new ArrayList<>();
        for (LlvmFunction function : module.functions()) {
            RewriteResult rewrite = rewriteFunction(function, functionsByName.keySet(), indirectionByTarget);
            if (rewrite.changed()) {
                affectedFunctions.add(function.name());
            }
            rewrittenFunctions.add(rewrite.function());
        }

        ArrayList<LlvmFunction> allFunctions = new ArrayList<>(rewrittenFunctions);
        ArrayList<LlvmGlobal> allGlobals = new ArrayList<>(module.globals());
        if (config.callIndirectionMode() == CallIndirectionMode.DISPATCHER) {
            targetsBySignature.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> dispatcherFunction(
                            dispatcherName(module.identifier(), entry.getKey(), config.seed()),
                            entry.getKey(),
                            entry.getValue(),
                            indirectionByTarget))
                    .forEach(allFunctions::add);
        } else {
            targetsBySignature.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> tableGlobal(
                            tableName(module.identifier(), entry.getKey(), config.seed()),
                            entry.getValue(),
                            indirectionByTarget))
                    .forEach(allGlobals::add);
        }
        List<String> indirectionSymbols = targetsBySignature.keySet().stream()
                .map(signature -> config.callIndirectionMode() == CallIndirectionMode.TABLE
                        ? tableName(module.identifier(), signature, config.seed())
                        : dispatcherName(module.identifier(), signature, config.seed()))
                .sorted()
                .toList();
        return new LlvmCallIndirectionResult(
                new LlvmModule(module.identifier(), module.declarations(), allGlobals, allFunctions),
                affectedFunctions.stream().sorted().toList(),
                indirectionSymbols,
                config.callIndirectionMode() == CallIndirectionMode.TABLE
                        ? "CALL_INDIRECTION_TABLE"
                        : "CALL_INDIRECTION_DISPATCHER");
    }

    private Map<Signature, List<String>> targetsBySignature(LlvmModule module, Set<String> functionNames) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (LlvmFunction function : module.functions()) {
            for (LlvmBasicBlock block : function.blocks()) {
                for (LlvmInstruction instruction : block.instructions()) {
                    directCallTarget(instruction, function.name(), functionNames).ifPresent(targets::add);
                }
            }
        }
        Map<Signature, List<String>> bySignature = new LinkedHashMap<>();
        targets.stream().sorted().forEach(target -> {
            LlvmFunction function = module.functions().stream()
                    .filter(candidate -> candidate.name().equals(target))
                    .findFirst()
                    .orElseThrow();
            bySignature.computeIfAbsent(Signature.of(function), ignored -> new ArrayList<>()).add(target);
        });
        return bySignature;
    }

    private Map<String, IndirectTarget> indirectionByTarget(
            LlvmModule module,
            LlvmProtectionConfig config,
            Map<Signature, List<String>> targetsBySignature) {
        Map<String, IndirectTarget> result = new LinkedHashMap<>();
        for (Map.Entry<Signature, List<String>> entry : targetsBySignature.entrySet()) {
            String symbol = config.callIndirectionMode() == CallIndirectionMode.TABLE
                    ? tableName(module.identifier(), entry.getKey(), config.seed())
                    : dispatcherName(module.identifier(), entry.getKey(), config.seed());
            LinkedHashSet<Integer> usedSelectors = new LinkedHashSet<>();
            List<String> orderedTargets = orderedTargets(entry.getValue(), module.identifier(), config.seed());
            for (int index = 0; index < orderedTargets.size(); index++) {
                String target = orderedTargets.get(index);
                int selector = uniqueSelector(module.identifier(), target, config.seed(), usedSelectors);
                result.put(target, new IndirectTarget(
                        symbol,
                        config.callIndirectionMode() == CallIndirectionMode.TABLE ? index : selector,
                        orderedTargets.size(),
                        config.callIndirectionMode(),
                        entry.getKey()));
            }
        }
        return result;
    }

    private RewriteResult rewriteFunction(
            LlvmFunction function,
            Set<String> functionNames,
            Map<String, IndirectTarget> indirectionByTarget) {
        boolean changed = false;
        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        int rewriteIndex = 0;
        for (LlvmBasicBlock block : function.blocks()) {
            ArrayList<LlvmInstruction> instructions = new ArrayList<>();
            for (LlvmInstruction instruction : block.instructions()) {
                Optional<String> target = directCallTarget(instruction, function.name(), functionNames);
                if (target.isPresent() && indirectionByTarget.containsKey(target.orElseThrow())) {
                    instructions.addAll(rewriteCall(
                            instruction,
                            target.orElseThrow(),
                            indirectionByTarget.get(target.orElseThrow()),
                            rewriteIndex++));
                    changed = true;
                } else {
                    instructions.add(instruction);
                }
            }
            blocks.add(new LlvmBasicBlock(block.name(), instructions, block.terminator()));
        }
        return new RewriteResult(new LlvmFunction(
                function.name(),
                function.linkage(),
                function.visibility(),
                function.returnType(),
                function.parameters(),
                blocks,
                function.nativeUnwindSemantics()), changed);
    }

    private Optional<String> directCallTarget(
            LlvmInstruction instruction,
            String currentFunction,
            Set<String> functionNames) {
        if (instruction.rawText().isEmpty()) {
            return Optional.empty();
        }
        String raw = instruction.rawText().orElseThrow();
        if (!raw.startsWith("call ")) {
            return Optional.empty();
        }
        int at = raw.indexOf('@');
        if (at < 0) {
            return Optional.empty();
        }
        int open = raw.indexOf('(', at);
        if (open < 0) {
            return Optional.empty();
        }
        String target = raw.substring(at + 1, open);
        if (target.equals(currentFunction) || !functionNames.contains(target)) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    private List<LlvmInstruction> rewriteCall(
            LlvmInstruction instruction,
            String target,
            IndirectTarget indirection,
            int rewriteIndex) {
        if (indirection.mode() == CallIndirectionMode.TABLE) {
            return rewriteTableCall(instruction, target, indirection, rewriteIndex);
        }
        return List.of(rewriteDispatcherCall(instruction, indirection));
    }

    private LlvmInstruction rewriteDispatcherCall(LlvmInstruction instruction, IndirectTarget dispatcher) {
        String raw = instruction.rawText().orElseThrow();
        int at = raw.indexOf('@');
        int open = raw.indexOf('(', at);
        int close = raw.lastIndexOf(')');
        String beforeAt = raw.substring(0, at);
        String arguments = raw.substring(open + 1, close).trim();
        String extra = raw.substring(close + 1);
        String rewrittenArguments = "i32 " + dispatcher.indexOrSelector()
                + (arguments.isEmpty() ? "" : ", " + arguments);
        return LlvmInstruction.raw(
                instruction.result(),
                beforeAt + "@" + dispatcher.symbol() + "(" + rewrittenArguments + ")" + extra,
                instruction.nativeUnwindSemantics());
    }

    private List<LlvmInstruction> rewriteTableCall(
            LlvmInstruction instruction,
            String target,
            IndirectTarget table,
            int rewriteIndex) {
        String raw = instruction.rawText().orElseThrow();
        int at = raw.indexOf('@');
        int open = raw.indexOf('(', at);
        int close = raw.lastIndexOf(')');
        String beforeAt = raw.substring(0, at);
        String arguments = raw.substring(open + 1, close).trim();
        String extra = raw.substring(close + 1);
        String suffix = safeLabel(target) + "_" + rewriteIndex;
        String slot = "%j2ll_indirect_slot_" + suffix;
        String callee = "%j2ll_indirect_fn_" + suffix;
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(slot),
                "getelementptr inbounds [" + table.tableSize() + " x ptr], ptr @"
                        + table.symbol() + ", i32 0, i32 " + table.indexOrSelector()));
        instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                Optional.of(callee),
                "load ptr, ptr " + slot));
        instructions.add(LlvmInstruction.raw(
                instruction.result(),
                beforeAt + table.signature().parameterList() + " " + callee + "(" + arguments + ")" + extra,
                instruction.nativeUnwindSemantics()));
        return instructions;
    }

    private LlvmFunction dispatcherFunction(
            String dispatcher,
            Signature signature,
            List<String> targets,
            Map<String, IndirectTarget> dispatchersByTarget) {
        ArrayList<LlvmParameter> parameters = new ArrayList<>();
        parameters.add(new LlvmParameter(LlvmType.I32, "%j2ll_selector"));
        parameters.addAll(signature.parameters());
        ArrayList<LlvmBasicBlock> blocks = new ArrayList<>();
        List<LlvmSwitchCase> cases = targets.stream()
                .sorted()
                .map(target -> new LlvmSwitchCase(dispatchersByTarget.get(target).indexOrSelector(), caseName(target)))
                .toList();
        blocks.add(new LlvmBasicBlock(
                "entry",
                List.of(),
                LlvmTerminator.switchOn("%j2ll_selector", caseName(targets.stream().sorted().findFirst().orElseThrow()), cases)));
        targets.stream()
                .sorted()
                .map(target -> dispatcherCaseBlock(target, signature))
                .forEach(blocks::add);
        return new LlvmFunction(
                dispatcher,
                LlvmLinkage.EXTERNAL,
                LlvmVisibility.HIDDEN,
                signature.returnType(),
                parameters,
                blocks,
                LlvmNativeUnwindSemantics.PROVEN_ABSENT);
    }

    private LlvmGlobal tableGlobal(
            String table,
            List<String> targets,
            Map<String, IndirectTarget> indirectionByTarget) {
        List<String> ordered = targets.stream()
                .sorted(java.util.Comparator.comparingInt(target -> indirectionByTarget.get(target).indexOrSelector()))
                .toList();
        String initializer = ordered.stream()
                .map(target -> "ptr @" + target)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return new LlvmGlobal(
                table,
                "internal constant [" + ordered.size() + " x ptr] [" + initializer + "]");
    }

    private LlvmBasicBlock dispatcherCaseBlock(String targetName, Signature signature) {
        String typedArguments = signature.parameters().stream()
                .map(parameter -> parameter.type().text() + " " + parameter.name())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        ArrayList<LlvmInstruction> instructions = new ArrayList<>();
        LlvmTerminator terminator;
        if (signature.returnType() == LlvmType.VOID) {
            instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.empty(),
                    "call void @" + targetName + "(" + typedArguments + ")"));
            terminator = new LlvmTerminator(LlvmType.VOID, Optional.empty());
        } else {
            instructions.add(LlvmInstruction.rawProvenNoNativeUnwind(
                    Optional.of("%j2ll_indirect_result"),
                    "call " + signature.returnType().text() + " @" + targetName + "(" + typedArguments + ")"));
            terminator = new LlvmTerminator(signature.returnType(), Optional.of("%j2ll_indirect_result"));
        }
        return new LlvmBasicBlock(caseName(targetName), instructions, terminator);
    }

    private String caseName(String target) {
        return "case_" + safeLabel(target);
    }

    private String safeLabel(String value) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            builder.append(Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        }
        return builder.toString();
    }

    private String dispatcherName(String moduleId, Signature signature, long seed) {
        return "j2ll_cid_" + hash(seed, name(), moduleId, signature.toString()).substring(0, 32);
    }

    private String tableName(String moduleId, Signature signature, long seed) {
        return "j2ll_cit_" + hash(seed, name(), "table", moduleId, signature.toString()).substring(0, 32);
    }

    private List<String> orderedTargets(List<String> targets, String moduleId, long seed) {
        return targets.stream()
                .sorted(java.util.Comparator
                        .comparing((String target) -> hash(seed, name(), "order", moduleId, target))
                        .thenComparing(target -> target))
                .toList();
    }

    private String unsupportedReason(LlvmProtectionConfig config) {
        return config.callIndirectionMode() == CallIndirectionMode.TABLE
                ? "CALL_INDIRECTION_TABLE_UNSUPPORTED_SHAPE"
                : "CALL_INDIRECTION_UNSUPPORTED_SHAPE";
    }

    private int selector(String moduleId, String target, long seed) {
        String hex = hash(seed, name(), moduleId, target).substring(0, 8);
        return (int) (Long.parseUnsignedLong(hex, 16) & 0x7fffffffL);
    }

    private int uniqueSelector(String moduleId, String target, long seed, Set<Integer> usedSelectors) {
        int selector = selector(moduleId, target, seed);
        while (!usedSelectors.add(selector)) {
            selector = selector == Integer.MAX_VALUE ? 1 : selector + 1;
        }
        return selector;
    }

    private String hash(long seed, String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
            for (String part : parts) {
                digest.update((byte) ':');
                digest.update(part.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Signature(LlvmType returnType, List<LlvmParameter> parameters) implements Comparable<Signature> {
        private Signature {
            parameters = List.copyOf(parameters);
        }

        static Signature of(LlvmFunction function) {
            ArrayList<LlvmParameter> canonicalParameters = new ArrayList<>();
            for (int index = 0; index < function.parameters().size(); index++) {
                canonicalParameters.add(new LlvmParameter(
                        function.parameters().get(index).type(),
                        "%j2ll_arg_" + index));
            }
            return new Signature(function.returnType(), canonicalParameters);
        }

        @Override
        public int compareTo(Signature other) {
            int byReturn = returnType.text().compareTo(other.returnType().text());
            if (byReturn != 0) {
                return byReturn;
            }
            return parameterTypes().compareTo(other.parameterTypes());
        }

        private String parameterTypes() {
            return parameters.stream().map(parameter -> parameter.type().text()).reduce((left, right) -> left + "," + right).orElse("");
        }

        private String parameterList() {
            return parameters.stream()
                    .map(parameter -> parameter.type().text())
                    .reduce((left, right) -> left + ", " + right)
                    .map(types -> "(" + types + ")")
                    .orElse("()");
        }

        @Override
        public String toString() {
            return returnType.text() + "(" + parameterTypes() + ")";
        }
    }

    private record IndirectTarget(
            String symbol,
            int indexOrSelector,
            int tableSize,
            CallIndirectionMode mode,
            Signature signature) {
    }

    private record RewriteResult(LlvmFunction function, boolean changed) {
    }
}
