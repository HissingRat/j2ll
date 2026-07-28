package xyz.melodysky.toolchain.nativetext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lazy process-lifetime decoding for explicitly low-sensitivity runtime text.
 */
final class GeneratedCLazyRuntimeTextObfuscator {
    private final GeneratedCFragmentLexer lexer = new GeneratedCFragmentLexer();
    private final NativeTextEncoder encoder = new NativeTextEncoder();
    private final NativeTextCEmitter emitter = new NativeTextCEmitter();

    String obfuscate(
            NativeTextBuildKey buildKey,
            String scope,
            String fragment) {
        Objects.requireNonNull(buildKey, "buildKey");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(fragment, "fragment");
        if (scope.isBlank()) {
            throw new IllegalArgumentException(
                    "generated C text scope must not be blank");
        }

        GeneratedCFragmentLexer.ScanResult scan = lexer.scan(fragment);
        if (scan.stringLiterals().isEmpty()) {
            return fragment;
        }
        if (scan.functionBodies().isEmpty()) {
            throw new IllegalArgumentException(
                    "generated C fragment contains text but has no local function decode boundary");
        }

        ArrayList<NativeTextEncoding> encodings = new ArrayList<>();
        ArrayList<GeneratedCTextEdits.Edit> edits = new ArrayList<>();
        for (int literalIndex = 0;
                literalIndex < scan.stringLiterals().size();
                literalIndex++) {
            GeneratedCFragmentLexer.StringLiteral literal =
                    scan.stringLiterals().get(literalIndex);
            NativeTextEncoding encoding = encoder.encode(
                    buildKey,
                    NativeTextPurpose.RUNTIME_ERROR,
                    scope + ":literal:" + literalIndex,
                    literal.value());
            encodings.add(encoding);
            edits.add(GeneratedCTextEdits.Edit.replace(
                    literal.start(),
                    literal.end(),
                    "(char*)" + cipherSymbol(encoding)));
        }

        String scopeToken = encoder.encode(
                        buildKey,
                        NativeTextPurpose.RUNTIME_ERROR,
                        scope + ":scope",
                        "")
                .symbol()
                .substring("j2ll_nt_".length());
        for (GeneratedCFragmentLexer.FunctionBody function
                : scan.functionBodies()) {
            StringBuilder calls = new StringBuilder("\n");
            for (NativeTextEncoding encoding : encodings) {
                calls.append("    ")
                        .append(decoder(scopeToken, encoding))
                        .append("();\n");
            }
            edits.add(GeneratedCTextEdits.Edit.insert(
                    function.start(),
                    calls.toString()));
        }
        return preamble(scopeToken, encodings)
                + GeneratedCTextEdits.apply(fragment, edits);
    }

    private String preamble(
            String scopeToken,
            List<NativeTextEncoding> encodings) {
        StringBuilder source = new StringBuilder("""
                #include <stddef.h>
                #include <stdint.h>
                #include <stdatomic.h>

                """);
        for (NativeTextEncoding encoding : encodings) {
            String decoder = decoder(scopeToken, encoding);
            String state = "j2ll_gcf_low_once_"
                    + scopeToken
                    + '_'
                    + token(encoding);
            source.append(emitter.mutableCiphertextDeclaration(encoding))
                    .append("static _Atomic int ")
                    .append(state)
                    .append(" = 0;\n")
                    .append("static void ")
                    .append(decoder)
                    .append("(void) {\n")
                    .append("    int observed = atomic_load_explicit(&")
                    .append(state)
                    .append(", memory_order_acquire);\n")
                    .append("    if (observed == 2) {\n")
                    .append("        return;\n")
                    .append("    }\n")
                    .append("    int expected = 0;\n")
                    .append("    if (atomic_compare_exchange_strong_explicit(&")
                    .append(state)
                    .append(", &expected, 1, memory_order_acq_rel, memory_order_acquire)) {\n")
                    .append(emitter.decodeInPlace(
                            encoding,
                            cipherSymbol(encoding),
                            "        "))
                    .append("        atomic_store_explicit(&")
                    .append(state)
                    .append(", 2, memory_order_release);\n")
                    .append("        return;\n")
                    .append("    }\n")
                    .append("    while (atomic_load_explicit(&")
                    .append(state)
                    .append(", memory_order_acquire) != 2) {\n")
                    .append("    }\n")
                    .append("}\n\n");
        }
        return source.toString();
    }

    private String decoder(
            String scopeToken,
            NativeTextEncoding encoding) {
        return "j2ll_gcf_low_decode_"
                + scopeToken
                + '_'
                + token(encoding);
    }

    private String cipherSymbol(NativeTextEncoding encoding) {
        return encoding.symbol() + "_cipher";
    }

    private String token(NativeTextEncoding encoding) {
        return encoding.symbol().substring("j2ll_nt_".length());
    }
}
