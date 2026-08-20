package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Rejects compiler-created control helpers in the exact protected assembly input. */
final class NativeRegistrationAssemblyArtifactVerifier {
    private static final String OUTLINED_PREFIX = "OUTLINED_FUNCTION_";

    void rejectMachineOutlinerArtifacts(
            TargetTriple target,
            List<Path> protectedAssembly) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(protectedAssembly, "protectedAssembly");
        for (Path path : protectedAssembly) {
            if (!Files.isRegularFile(path)) {
                throw NativeRegistrationAssemblyIndex.failure(
                        "MISSING_ASSEMBLY_EVIDENCE",
                        path.toString());
            }
            scan(target, path, Files.readString(path, StandardCharsets.UTF_8));
        }
    }

    private void scan(
            TargetTriple target,
            Path path,
            String assembly) throws IOException {
        String[] lines = assembly.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String code = codeOnly(target, lines[index]);
            for (String token : identifierTokens(code)) {
                String canonical = canonical(target, token);
                if (isOutlinerFamily(canonical)) {
                    throw NativeRegistrationAssemblyIndex.failure(
                            "MACHINE_OUTLINER_ARTIFACT",
                            path.getFileName() + ":" + (index + 1) + ":" + canonical);
                }
            }
        }
    }

    private String codeOnly(TargetTriple target, String line) {
        int comment = target.archClassifier().equals("arm64")
                ? commentOutsideQuotes(line, "//")
                : commentOutsideQuotes(line, "#");
        if (target.osClassifier().equals("macos")) {
            int semicolon = commentOutsideQuotes(line, ";");
            if (semicolon >= 0 && (comment < 0 || semicolon < comment)) {
                comment = semicolon;
            }
        }
        return comment < 0 ? line : line.substring(0, comment);
    }

    private int commentOutsideQuotes(String line, String marker) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index + marker.length() <= line.length(); index++) {
            char ch = line.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && quote != 0) {
                escaped = true;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = quote == 0 ? ch : quote == ch ? 0 : quote;
                continue;
            }
            if (quote == 0 && line.startsWith(marker, index)) {
                return index;
            }
        }
        return -1;
    }

    private List<String> identifierTokens(String code) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        char quote = 0;
        boolean escaped = false;
        int cursor = 0;
        while (cursor < code.length()) {
            char ch = code.charAt(cursor);
            if (escaped) {
                escaped = false;
                cursor++;
                continue;
            }
            if (ch == '\\' && quote != 0) {
                escaped = true;
                cursor++;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = quote == 0 ? ch : quote == ch ? 0 : quote;
                cursor++;
                continue;
            }
            if (quote != 0 || !isIdentifierStart(ch)) {
                cursor++;
                continue;
            }
            int end = cursor + 1;
            while (end < code.length() && isIdentifierPart(code.charAt(end))) {
                end++;
            }
            result.add(code.substring(cursor, end));
            cursor = end;
        }
        return List.copyOf(result);
    }

    private boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_' || ch == '.' || ch == '$';
    }

    private boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch)
                || ch == '_' || ch == '.' || ch == '$' || ch == '@';
    }

    private String canonical(TargetTriple target, String token) {
        String value = token;
        if (target.osClassifier().equals("macos") && value.startsWith("_")) {
            value = value.substring(1);
        }
        int suffix = value.indexOf('@');
        return suffix < 0 ? value : value.substring(0, suffix);
    }

    private boolean isOutlinerFamily(String symbol) {
        return symbol.contains(OUTLINED_PREFIX);
    }
}
