package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Rewrites verified function-local pointer literals to activation-local
 * plaintext scratch with exit cleanup.
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

        ArrayList<NativeTextEncoding> encodings = new ArrayList<>();
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
            NativeTextEncoding encoding = encoder.encode(
                    buildKey,
                    purpose,
                    scope + ":literal:" + literalIndex,
                    literal.value());
            encodings.add(encoding);
            String token = token(encoding);
            String scratch = "j2ll_nt_local_" + token;
            edits.add(GeneratedCTextEdits.Edit.replace(
                    literal.start(),
                    literal.end(),
                    "(const char*)" + scratch + ".bytes"));
        }

        for (GeneratedCFragmentLexer.FunctionBody function
                : scan.functionBodies()) {
            List<Integer> indexes = literalIndexesIn(
                    scan.stringLiterals(),
                    function);
            if (indexes.isEmpty()) {
                continue;
            }
            StringBuilder prologue = new StringBuilder("\n");
            for (int index : indexes) {
                NativeTextEncoding encoding = encodings.get(index);
                String token = token(encoding);
                String scratch = "j2ll_nt_local_" + token;
                prologue.append("    j2ll_nt_scratch_")
                        .append(token)
                        .append(' ')
                        .append(scratch)
                        .append(" __attribute__((cleanup(j2ll_nt_cleanup_")
                        .append(token)
                        .append("))) = {{0}};\n")
                        .append(emitter.decodeInto(
                                encoding,
                                scratch + ".bytes",
                                "    "));
            }
            edits.add(GeneratedCTextEdits.Edit.insert(
                    function.start(),
                    prologue.toString()));
        }
        return preamble(encodings)
                + GeneratedCTextEdits.apply(fragment, edits);
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

    private String preamble(List<NativeTextEncoding> encodings) {
        StringBuilder source = new StringBuilder("""
                #if !defined(__clang__) && !defined(__GNUC__)
                #error "j2ll activation-local native text cleanup requires clang/gcc cleanup support"
                #endif

                """);
        for (NativeTextEncoding encoding : encodings) {
            String token = token(encoding);
            source.append(emitter.ciphertextDeclaration(encoding))
                    .append("typedef struct { char bytes[sizeof(")
                    .append(encoding.symbol())
                    .append("_cipher)]; } j2ll_nt_scratch_")
                    .append(token)
                    .append(";\n")
                    .append("static void j2ll_nt_cleanup_")
                    .append(token)
                    .append("(j2ll_nt_scratch_")
                    .append(token)
                    .append("* scratch) {\n")
                    .append("    j2ll_native_text_zero(scratch->bytes, sizeof(scratch->bytes));\n")
                    .append("}\n");
        }
        return source.append('\n').toString();
    }

    private String token(NativeTextEncoding encoding) {
        return encoding.symbol().substring("j2ll_nt_".length());
    }
}
