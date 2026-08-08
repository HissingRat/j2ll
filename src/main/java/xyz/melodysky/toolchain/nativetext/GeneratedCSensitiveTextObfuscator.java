package xyz.melodysky.toolchain.nativetext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rewrites verified C pointer literals to bounded use-coherent tuples. */
final class GeneratedCSensitiveTextObfuscator {
    static final int MAX_TUPLE_COMPONENTS =
            NativeTextTupleEncoder.MAX_COMPONENTS;
    static final int MAX_TUPLE_DECODED_BYTES =
            NativeTextTupleEncoder.MAX_DECODED_BYTES;

    private final GeneratedCFragmentLexer lexer = new GeneratedCFragmentLexer();
    private final NativeTextTupleEncoder tupleEncoder =
            new NativeTextTupleEncoder();
    private final NativeTextCEmitter emitter = new NativeTextCEmitter();

    String obfuscate(
            NativeTextBuildKey buildKey,
            String scope,
            String fragment,
            NativeTextPurpose purpose) {
        requireInputs(buildKey, scope, fragment, purpose);
        GeneratedCFragmentLexer.ScanResult scan = lexer.scan(fragment);
        if (scan.stringLiterals().isEmpty()) {
            return fragment;
        }
        for (GeneratedCFragmentLexer.StringLiteral literal
                : scan.stringLiterals()) {
            if (literal.use()
                    == GeneratedCFragmentLexer.LiteralUse.DIRECT_RETURN) {
                throw new IllegalArgumentException(
                        "sensitive generated C text cannot return activation-local scratch");
            }
            if (containingFunction(scan.functionBodies(), literal) == null) {
                throw new IllegalArgumentException(
                        "sensitive generated C text must be inside a function body");
            }
        }

        ArrayList<FunctionPlan> functions = new ArrayList<>();
        ArrayList<GeneratedCTextEdits.Edit> edits = new ArrayList<>();
        for (int functionIndex = 0;
                functionIndex < scan.functionBodies().size();
                functionIndex++) {
            GeneratedCFragmentLexer.FunctionBody function =
                    scan.functionBodies().get(functionIndex);
            List<Integer> indexes = literalIndexesIn(
                    scan.stringLiterals(),
                    function);
            if (indexes.isEmpty()) {
                continue;
            }
            PlannedFunction planned = planFunction(
                    buildKey,
                    purpose,
                    scope,
                    functionIndex,
                    indexes,
                    scan.stringLiterals());
            functions.add(planned.function());
            edits.add(GeneratedCTextEdits.Edit.insert(
                    function.start(),
                    prologue(planned.function())));
            for (int literalIndex : indexes) {
                GeneratedCFragmentLexer.StringLiteral literal =
                        scan.stringLiterals().get(literalIndex);
                ComponentUse use = planned.useByValue().get(literal.value());
                NativeTextEncoding record = use.group().tuple().record();
                int offset = recordSlice(use).offset();
                String base = use.group().usePlan().decodesAt(literalIndex)
                        ? useMacro(record) + "()"
                        : rawTupleBase(
                                planned.function(),
                                use.group());
                edits.add(GeneratedCTextEdits.Edit.replace(
                        literal.start(),
                        literal.end(),
                        "(const char*)("
                                + base
                                + " + "
                                + offset
                                + "u)"));
            }
        }
        return preamble(functions)
                + GeneratedCTextEdits.apply(fragment, edits);
    }

    private PlannedFunction planFunction(
            NativeTextBuildKey buildKey,
            NativeTextPurpose purpose,
            String scope,
            int functionIndex,
            List<Integer> indexes,
            List<GeneratedCFragmentLexer.StringLiteral> literals) {
        LinkedHashMap<LiteralGroupKey, List<Integer>> rawGroups =
                new LinkedHashMap<>();
        for (int literalIndex : indexes) {
            GeneratedCFragmentLexer.StringLiteral literal =
                    literals.get(literalIndex);
            LiteralGroupKey key = literal.use()
                            == GeneratedCFragmentLexer.LiteralUse.POINTER_ARGUMENT
                    ? LiteralGroupKey.call(literal.argumentListStart())
                    : LiteralGroupKey.assignment(literalIndex);
            rawGroups.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(literalIndex);
        }

        LinkedHashMap<String, Set<LiteralGroupKey>> groupsByValue =
                new LinkedHashMap<>();
        for (Map.Entry<LiteralGroupKey, List<Integer>> entry
                : rawGroups.entrySet()) {
            for (int literalIndex : entry.getValue()) {
                groupsByValue.computeIfAbsent(
                                literals.get(literalIndex).value(),
                                ignored -> new LinkedHashSet<>())
                        .add(entry.getKey());
            }
        }
        Set<String> sharedValues = groupsByValue.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        ArrayList<List<String>> componentGroups = new ArrayList<>();
        LinkedHashSet<String> emittedShared = new LinkedHashSet<>();
        for (int literalIndex : indexes) {
            String value = literals.get(literalIndex).value();
            if (sharedValues.contains(value) && emittedShared.add(value)) {
                componentGroups.add(List.of(value));
            }
        }
        for (List<Integer> rawIndexes : rawGroups.values()) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (int literalIndex : rawIndexes) {
                String value = literals.get(literalIndex).value();
                if (!sharedValues.contains(value)) {
                    values.add(value);
                }
            }
            appendBoundedGroups(componentGroups, List.copyOf(values));
        }

        ArrayList<NativeTextTupleEncoding> tuples = new ArrayList<>();
        LinkedHashMap<String, ComponentLocation> locationByValue =
                new LinkedHashMap<>();
        for (int groupIndex = 0;
                groupIndex < componentGroups.size();
                groupIndex++) {
            List<String> values = componentGroups.get(groupIndex);
            NativeTextTupleEncoding tuple = tupleEncoder.encode(
                    buildKey,
                    purpose,
                    scope + ":function:" + functionIndex + ":group:" + groupIndex,
                    values);
            tuples.add(tuple);
            for (int componentIndex = 0;
                    componentIndex < values.size();
                    componentIndex++) {
                locationByValue.put(
                        values.get(componentIndex),
                        new ComponentLocation(groupIndex, componentIndex));
            }
        }
        ArrayList<List<Integer>> literalUsesByGroup = new ArrayList<>();
        for (int groupIndex = 0;
                groupIndex < tuples.size();
                groupIndex++) {
            literalUsesByGroup.add(new ArrayList<>());
        }
        for (int literalIndex : indexes) {
            ComponentLocation location = locationByValue.get(
                    literals.get(literalIndex).value());
            literalUsesByGroup.get(location.groupIndex()).add(literalIndex);
        }
        ArrayList<TupleGroupPlan> groups = new ArrayList<>();
        for (int groupIndex = 0;
                groupIndex < tuples.size();
                groupIndex++) {
            groups.add(new TupleGroupPlan(
                    tuples.get(groupIndex),
                    NativeTextUsePlan.plan(
                            literalUsesByGroup.get(groupIndex),
                            literals)));
        }
        LinkedHashMap<String, ComponentUse> useByValue =
                new LinkedHashMap<>();
        for (Map.Entry<String, ComponentLocation> entry
                : locationByValue.entrySet()) {
            ComponentLocation location = entry.getValue();
            useByValue.put(
                    entry.getKey(),
                    new ComponentUse(
                            groups.get(location.groupIndex()),
                            location.componentIndex()));
        }
        String scratch = "j2ll_nt_local_"
                + token(groups.get(0).tuple().record());
        return new PlannedFunction(
                new FunctionPlan(scratch, groups),
                Map.copyOf(useByValue));
    }

    private void appendBoundedGroups(
            List<List<String>> destination,
            List<String> values) {
        ArrayList<String> current = new ArrayList<>();
        int currentBytes = 0;
        for (String value : values) {
            int decodedBytes = value.getBytes(StandardCharsets.UTF_8).length + 1;
            boolean oversizedSingleton = decodedBytes > MAX_TUPLE_DECODED_BYTES;
            if (!current.isEmpty()
                    && (current.size() == MAX_TUPLE_COMPONENTS
                            || currentBytes + decodedBytes
                                    > MAX_TUPLE_DECODED_BYTES
                            || oversizedSingleton)) {
                destination.add(List.copyOf(current));
                current.clear();
                currentBytes = 0;
            }
            if (oversizedSingleton) {
                destination.add(List.of(value));
                continue;
            }
            current.add(value);
            currentBytes += decodedBytes;
        }
        if (!current.isEmpty()) {
            destination.add(List.copyOf(current));
        }
    }

    private NativeTextTupleEncoding.Slice recordSlice(ComponentUse use) {
        return use.group().tuple().slice(use.componentIndex());
    }

    private String prologue(FunctionPlan function) {
        StringBuilder source = new StringBuilder("\n")
                .append("    struct {\n")
                .append("        size_t length;\n");
        for (TupleGroupPlan group : function.groups()) {
            source.append("        struct {\n")
                    .append(group.usePlan().requiresReadyGuard()
                            ? "            unsigned char ready;\n"
                            : "")
                    .append("            char value[sizeof(")
                    .append(group.tuple().record().symbol())
                    .append("_cipher)];\n")
                    .append("        } ")
                    .append(slot(group.tuple().record()))
                    .append(";\n");
        }
        source.append("    } ")
                .append(function.scratch())
                .append(" __attribute__((cleanup(")
                .append(NativeScratchZeroizerSource.CLEANUP_FUNCTION_NAME)
                .append(")));\n")
                .append("    ")
                .append(function.scratch())
                .append(".length = sizeof(")
                .append(function.scratch())
                .append(") - sizeof(size_t);\n");
        for (TupleGroupPlan group : function.groups()) {
            if (group.usePlan().requiresReadyGuard()) {
                source.append("    ")
                        .append(function.scratch())
                        .append('.')
                        .append(slot(group.tuple().record()))
                        .append(".ready = 0u;\n");
            }
        }
        return source.toString();
    }

    private String preamble(List<FunctionPlan> functions) {
        StringBuilder source = new StringBuilder("""
                #if !defined(__clang__) && !defined(__GNUC__)
                #error "j2ll activation-local native text cleanup requires clang/gcc cleanup support"
                #endif

                """);
        for (FunctionPlan function : functions) {
            for (TupleGroupPlan group : function.groups()) {
                NativeTextEncoding record = group.tuple().record();
                source.append(emitter.ciphertextDeclaration(record))
                        .append(tupleUseMacro(
                                group.tuple(),
                                function.scratch(),
                                group.usePlan()));
            }
        }
        return source.toString();
    }

    private String tupleUseMacro(
            NativeTextTupleEncoding tuple,
            String scratch,
            NativeTextUsePlan usePlan) {
        NativeTextEncoding record = tuple.record();
        String tupleSlot = scratch + "." + slot(record);
        StringBuilder body = new StringBuilder()
                .append("    __extension__ ({\n");
        if (usePlan.requiresReadyGuard()) {
            body.append("        if (")
                    .append(tupleSlot)
                    .append(".ready == 0u) {\n")
                    .append(emitter.decodeTupleInto(
                            tuple,
                            tupleSlot + ".value",
                            "            "))
                    .append("            ")
                    .append(tupleSlot)
                    .append(".ready = 1u;\n")
                    .append("        }\n");
        } else {
            body.append(emitter.decodeTupleInto(
                    tuple,
                    tupleSlot + ".value",
                    "        "));
        }
        body.append("        ")
                .append(tupleSlot)
                .append(".value;\n")
                .append("    })\n");
        String bodySource = body.toString();
        String[] lines = bodySource.split("\\n", -1);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isEmpty()) {
            last--;
        }
        StringBuilder macro = new StringBuilder("#define ")
                .append(useMacro(record))
                .append("() \\\n");
        for (int index = 0; index <= last; index++) {
            macro.append(lines[index]);
            if (index < last) {
                macro.append(" \\\n");
            } else {
                macro.append('\n');
            }
        }
        return macro.toString();
    }

    private String rawTupleBase(
            FunctionPlan function,
            TupleGroupPlan group) {
        return function.scratch()
                + "."
                + slot(group.tuple().record())
                + ".value";
    }

    private void requireInputs(
            NativeTextBuildKey buildKey,
            String scope,
            String fragment,
            NativeTextPurpose purpose) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fragment, "fragment");
        Objects.requireNonNull(purpose, "purpose");
        if (scope.isBlank()) {
            throw new IllegalArgumentException(
                    "generated C text scope must not be blank");
        }
    }

    private GeneratedCFragmentLexer.FunctionBody containingFunction(
            List<GeneratedCFragmentLexer.FunctionBody> functions,
            GeneratedCFragmentLexer.StringLiteral literal) {
        return functions.stream()
                .filter(function -> function.contains(literal))
                .findFirst()
                .orElse(null);
    }

    private List<Integer> literalIndexesIn(
            List<GeneratedCFragmentLexer.StringLiteral> literals,
            GeneratedCFragmentLexer.FunctionBody function) {
        ArrayList<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < literals.size(); index++) {
            if (function.contains(literals.get(index))) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private String token(NativeTextEncoding encoding) {
        return encoding.symbol().substring("j2ll_nt_".length());
    }

    private String slot(NativeTextEncoding encoding) {
        return "slot_" + token(encoding);
    }

    private String useMacro(NativeTextEncoding encoding) {
        return "j2ll_nt_use_" + token(encoding);
    }

    private record LiteralGroupKey(int argumentListStart, int assignmentIndex) {
        static LiteralGroupKey call(int argumentListStart) {
            return new LiteralGroupKey(argumentListStart, -1);
        }

        static LiteralGroupKey assignment(int literalIndex) {
            return new LiteralGroupKey(-1, literalIndex);
        }
    }

    private record TupleGroupPlan(
            NativeTextTupleEncoding tuple,
            NativeTextUsePlan usePlan) {}

    private record ComponentLocation(int groupIndex, int componentIndex) {}

    private record ComponentUse(TupleGroupPlan group, int componentIndex) {}

    private record FunctionPlan(
            String scratch,
            List<TupleGroupPlan> groups) {
        private FunctionPlan {
            groups = List.copyOf(groups);
        }
    }

    private record PlannedFunction(
            FunctionPlan function,
            Map<String, ComponentUse> useByValue) {}
}
