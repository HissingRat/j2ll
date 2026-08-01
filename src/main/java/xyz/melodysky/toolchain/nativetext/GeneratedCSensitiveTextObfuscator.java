package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rewrites verified function-local pointer literals to activation-local
 * plaintext scratch with use-site decode and exit cleanup.
 */
final class GeneratedCSensitiveTextObfuscator {
    private final GeneratedCFragmentLexer lexer = new GeneratedCFragmentLexer();
    private final NativeTextEncoder encoder = new NativeTextEncoder();
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

        ArrayList<NativeTextEncoding> literalEncodings = new ArrayList<>();
        Map<LiteralGroup, NativeTextEncoding> groupedEncodings =
                new LinkedHashMap<>();
        ArrayList<GeneratedCTextEdits.Edit> edits = new ArrayList<>();
        for (int literalIndex = 0;
                literalIndex < scan.stringLiterals().size();
                literalIndex++) {
            GeneratedCFragmentLexer.StringLiteral literal =
                    scan.stringLiterals().get(literalIndex);
            if (literal.use()
                    == GeneratedCFragmentLexer.LiteralUse.DIRECT_RETURN) {
                throw new IllegalArgumentException(
                        "sensitive generated C text cannot return activation-local scratch");
            }
            GeneratedCFragmentLexer.FunctionBody function =
                    containingFunction(scan.functionBodies(), literal);
            if (function == null) {
                throw new IllegalArgumentException(
                        "sensitive generated C text must be inside a function body");
            }
            LiteralGroup group = new LiteralGroup(
                    function.start(),
                    function.end(),
                    literal.value());
            NativeTextEncoding encoding = groupedEncodings.get(group);
            if (encoding == null) {
                encoding = encoder.encode(
                        buildKey,
                        purpose,
                        scope + ":group:" + groupedEncodings.size(),
                        literal.value());
                groupedEncodings.put(group, encoding);
            }
            literalEncodings.add(encoding);
        }

        ArrayList<FunctionPlan> functionPlans = new ArrayList<>();
        for (GeneratedCFragmentLexer.FunctionBody function
                : scan.functionBodies()) {
            List<Integer> indexes = literalIndexesIn(
                    scan.stringLiterals(),
                    function);
            if (indexes.isEmpty()) {
                continue;
            }
            List<NativeTextEncoding> functionEncodings =
                    distinctEncodings(indexes, literalEncodings);
            String scratch = "j2ll_nt_local_"
                    + token(functionEncodings.get(0));
            functionPlans.add(new FunctionPlan(
                    scratch,
                    functionEncodings));
            StringBuilder prologue = new StringBuilder("\n");
            prologue.append("    struct {\n")
                    .append("        size_t length;\n");
            for (NativeTextEncoding encoding : functionEncodings) {
                prologue.append("        struct {\n")
                        .append("            unsigned char ready;\n")
                        .append("            char value[sizeof(")
                        .append(encoding.symbol())
                        .append("_cipher)];\n")
                        .append("        } ")
                        .append(slot(encoding))
                        .append(";\n");
            }
            prologue.append("    } ")
                    .append(scratch)
                    .append(" __attribute__((cleanup(")
                    .append(NativeScratchZeroizerSource
                            .CLEANUP_FUNCTION_NAME)
                    .append("))) = {\n")
                    .append("        .length = ");
            for (int index = 0; index < functionEncodings.size(); index++) {
                if (index > 0) {
                    prologue.append(" + ");
                }
                prologue.append("sizeof(")
                        .append(scratch)
                        .append('.')
                        .append(slot(functionEncodings.get(index)))
                        .append(')');
            }
            prologue.append("\n    };\n");
            for (int index : indexes) {
                NativeTextEncoding encoding = literalEncodings.get(index);
                GeneratedCFragmentLexer.StringLiteral literal =
                        scan.stringLiterals().get(index);
                edits.add(GeneratedCTextEdits.Edit.replace(
                        literal.start(),
                        literal.end(),
                        "(const char*)"
                                + useMacro(encoding)
                                + "()"));
            }
            edits.add(GeneratedCTextEdits.Edit.insert(
                    function.start(),
                    prologue.toString()));
        }
        return preamble(functionPlans)
                + GeneratedCTextEdits.apply(fragment, edits);
    }

    private List<NativeTextEncoding> distinctEncodings(
            List<Integer> indexes,
            List<NativeTextEncoding> literalEncodings) {
        LinkedHashMap<String, NativeTextEncoding> distinct =
                new LinkedHashMap<>();
        for (int index : indexes) {
            NativeTextEncoding encoding = literalEncodings.get(index);
            distinct.putIfAbsent(encoding.symbol(), encoding);
        }
        return List.copyOf(distinct.values());
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

    private String preamble(List<FunctionPlan> functionPlans) {
        StringBuilder source = new StringBuilder("""
                #if !defined(__clang__) && !defined(__GNUC__)
                #error "j2ll activation-local native text cleanup requires clang/gcc cleanup support"
                #endif

                """);
        for (FunctionPlan function : functionPlans) {
            for (NativeTextEncoding encoding : function.encodings()) {
                source.append(emitter.ciphertextDeclaration(encoding))
                        .append(lazyUseMacro(
                                encoding,
                                function.scratch()));
            }
        }
        return source.toString();
    }

    private String lazyUseMacro(
            NativeTextEncoding encoding,
            String scratch) {
        String slot = scratch + "." + slot(encoding);
        String body = new StringBuilder()
                .append("    __extension__ ({\n")
                .append("        if (")
                .append(slot)
                .append(".ready == 0u) {\n")
                .append(emitter.decodeInto(
                        encoding,
                        slot + ".value",
                        "            "))
                .append("            ")
                .append(slot)
                .append(".ready = 1u;\n")
                .append("        }\n")
                .append("        ")
                .append(slot)
                .append(".value;\n")
                .append("    })\n")
                .toString();
        String[] lines = body.split("\\n", -1);
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isEmpty()) {
            last--;
        }
        StringBuilder macro = new StringBuilder("#define ")
                .append(useMacro(encoding))
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

    private String token(NativeTextEncoding encoding) {
        return encoding.symbol().substring("j2ll_nt_".length());
    }

    private String slot(NativeTextEncoding encoding) {
        return "slot_" + token(encoding);
    }

    private String useMacro(NativeTextEncoding encoding) {
        return "j2ll_nt_use_" + token(encoding);
    }

    private record LiteralGroup(int functionStart, int functionEnd, String value) {}

    private record FunctionPlan(
            String scratch,
            List<NativeTextEncoding> encodings) {
        private FunctionPlan {
            encodings = List.copyOf(encodings);
        }
    }
}
