package xyz.melodysky.toolchain;

import java.util.Set;
import java.util.TreeSet;
import xyz.melodysky.backend.llvm.model.LlvmBasicBlock;
import xyz.melodysky.backend.llvm.model.LlvmGlobal;
import xyz.melodysky.backend.llvm.model.LlvmInstruction;
import xyz.melodysky.backend.llvm.model.LlvmModule;
import xyz.melodysky.backend.llvm.model.LlvmTerminatorKind;

/**
 * Collects symbol references from the authoritative LLVM module model.
 *
 * <p>This deliberately does not inspect emitted {@code .ll} text. LLVM model
 * instructions still contain some raw instruction payloads, so this scanner
 * recognizes LLVM's direct {@code @symbol} syntax without applying a regex to
 * serialized IR.</p>
 */
final class LlvmModelSymbolReferenceCollector {
    Result collect(NativeLlvmCompilation compilation) {
        CollectionState state = new CollectionState();
        for (NativeLlvmModuleCompilation module : compilation.modules()) {
            collect(module.module(), state);
        }
        return new Result(
                Set.copyOf(state.references),
                state.complete);
    }

    private void collect(
            LlvmModule module,
            CollectionState state) {
        for (LlvmGlobal global : module.globals()) {
            collectText(global.definition(), state);
        }
        module.functions().forEach(function -> function.blocks()
                .forEach(block -> collect(block, state)));
    }

    private void collect(
            LlvmBasicBlock block,
            CollectionState state) {
        for (LlvmInstruction instruction : block.instructions()) {
            instruction.rawText().ifPresent(text ->
                    collectText(text, state));
            instruction.operands().forEach(operand ->
                    collectText(operand, state));
        }
        if (block.terminator().kind() == LlvmTerminatorKind.THROW) {
            /*
             * LlvmTextEmitter materializes this call from the structured
             * terminator, so it has no raw instruction to scan.
             */
            state.references.add("j2ll_rt_throw");
        }
    }

    private void collectText(
            String text,
            CollectionState state) {
        int cursor = 0;
        while (cursor < text.length()) {
            int marker = text.indexOf('@', cursor);
            if (marker < 0 || marker + 1 >= text.length()) {
                return;
            }
            int start = marker + 1;
            if (text.charAt(start) == '"') {
                int end = quotedSymbolEnd(text, start + 1);
                if (end < 0) {
                    state.complete = false;
                    return;
                }
                String quoted = text.substring(start + 1, end);
                /*
                 * LLVM quoted identifiers use byte escapes. The production
                 * runtime symbols are always hash-safe unquoted identifiers;
                 * guessing at an escaped spelling could hide a runtime root.
                 */
                if (quoted.indexOf('\\') >= 0) {
                    state.complete = false;
                    return;
                }
                state.references.add(quoted);
                cursor = end + 1;
                continue;
            }
            int end = start;
            while (end < text.length()
                    && isUnquotedSymbolCharacter(text.charAt(end))) {
                end++;
            }
            if (end > start) {
                state.references.add(text.substring(start, end));
            } else {
                state.complete = false;
            }
            cursor = Math.max(end, marker + 2);
        }
    }

    private int quotedSymbolEnd(String text, int start) {
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char value = text.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == '"') {
                return index;
            }
        }
        return -1;
    }

    private boolean isUnquotedSymbolCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '.'
                || value == '$'
                || value == '-';
    }

    record Result(Set<String> references, boolean complete) {
        Result {
            references = Set.copyOf(references);
        }
    }

    private static final class CollectionState {
        private final TreeSet<String> references = new TreeSet<>();
        private boolean complete = true;
    }
}
