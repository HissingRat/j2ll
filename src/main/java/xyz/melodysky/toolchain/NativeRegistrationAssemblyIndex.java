package xyz.melodysky.toolchain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A narrow lexical index over Zig/LLVM optimized assembly, not a general disassembler. */
final class NativeRegistrationAssemblyIndex {
    private final TargetTriple target;
    private final Map<String, Function> functions;

    private NativeRegistrationAssemblyIndex(
            TargetTriple target,
            Map<String, Function> functions) {
        this.target = target;
        this.functions = Map.copyOf(functions);
    }

    static NativeRegistrationAssemblyIndex read(
            TargetTriple target,
            List<Path> assemblyFiles,
            Set<String> expectedSymbols) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(assemblyFiles, "assemblyFiles");
        Objects.requireNonNull(expectedSymbols, "expectedSymbols");
        if (!target.archClassifier().equals("x64")
                && !target.archClassifier().equals("arm64")) {
            throw failure("UNSUPPORTED_TARGET_ARCHITECTURE", target.directoryName());
        }
        if (assemblyFiles.isEmpty()) {
            throw failure("MISSING_ASSEMBLY_EVIDENCE", target.directoryName());
        }
        LinkedHashMap<String, Function> functions = new LinkedHashMap<>();
        for (Path path : assemblyFiles) {
            if (!Files.isRegularFile(path)) {
                throw failure("MISSING_ASSEMBLY_EVIDENCE", path.toString());
            }
            parseFile(
                    target,
                    path,
                    Files.readString(path, StandardCharsets.UTF_8),
                    expectedSymbols,
                    functions);
        }
        for (String symbol : expectedSymbols) {
            if (!functions.containsKey(symbol)) {
                throw failure("MISSING_FUNCTION_DEFINITION", symbol);
            }
        }
        return new NativeRegistrationAssemblyIndex(target, functions);
    }

    Function function(String symbol) {
        Function function = functions.get(symbol);
        if (function == null) {
            throw new IllegalArgumentException("assembly function was not indexed: " + symbol);
        }
        return function;
    }

    String archClassifier() {
        return target.archClassifier();
    }

    String canonicalSymbol(String assemblySymbol) {
        String value = assemblySymbol;
        if (target.osClassifier().equals("macos") && value.startsWith("_")) {
            value = value.substring(1);
        }
        int suffix = value.indexOf('@');
        if (suffix >= 0) {
            value = value.substring(0, suffix);
        }
        return value;
    }

    private static void parseFile(
            TargetTriple target,
            Path path,
            String assembly,
            Set<String> expectedSymbols,
            Map<String, Function> functions) throws IOException {
        String[] lines = assembly.split("\\R", -1);
        MutableFunction current = null;
        for (int index = 0; index < lines.length; index++) {
            int lineNumber = index + 1;
            String code = codeOnly(target, lines[index]).trim();
            if (code.isEmpty()) {
                continue;
            }
            Label label = label(code);
            if (label != null) {
                String canonical = canonicalSymbol(target, label.name());
                if (expectedSymbols.contains(canonical)) {
                    if (current != null) {
                        finish(path, current, functions);
                    }
                    if (functions.containsKey(canonical)) {
                        throw failure("DUPLICATE_FUNCTION_DEFINITION", canonical);
                    }
                    current = new MutableFunction(canonical, path, lineNumber);
                } else if (current != null && isForeignTopLevelLabel(target, label.name())) {
                    finish(path, current, functions);
                    current = null;
                } else if (current != null) {
                    Integer previous = current.labelInstructionIndexes.putIfAbsent(
                            label.name(),
                            current.instructions.size());
                    if (previous != null) {
                        throw failure("DUPLICATE_LOCAL_LABEL", label.name());
                    }
                }
                code = label.remainder().trim();
                if (code.isEmpty()) {
                    continue;
                }
            }
            if (current == null) {
                continue;
            }
            if (isFunctionEnd(code, current.symbol, target)) {
                finish(path, current, functions);
                current = null;
                continue;
            }
            if (code.startsWith(".")) {
                continue;
            }
            Instruction instruction = instruction(code, lineNumber);
            if (instruction != null) {
                current.instructions.add(instruction);
            }
        }
        if (current != null) {
            finish(path, current, functions);
        }
    }

    private static void finish(
            Path path,
            MutableFunction current,
            Map<String, Function> functions) throws IOException {
        if (current.instructions.isEmpty()) {
            throw failure("EMPTY_OR_ALIASED_FUNCTION_RANGE", current.symbol);
        }
        Function previous = functions.putIfAbsent(
                current.symbol,
                new Function(
                        current.symbol,
                        path,
                        current.labelLine,
                        List.copyOf(current.instructions),
                        Map.copyOf(current.labelInstructionIndexes)));
        if (previous != null) {
            throw failure("DUPLICATE_FUNCTION_DEFINITION", current.symbol);
        }
    }

    private static String codeOnly(TargetTriple target, String line) {
        int comment = target.archClassifier().equals("arm64")
                ? line.indexOf("//")
                : line.indexOf('#');
        if (target.osClassifier().equals("macos")) {
            int semicolon = line.indexOf(';');
            if (semicolon >= 0 && (comment < 0 || semicolon < comment)) {
                comment = semicolon;
            }
        }
        return comment < 0 ? line : line.substring(0, comment);
    }

    private static Label label(String code) {
        int colon = code.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String candidate = code.substring(0, colon).trim();
        if (!isAssemblyIdentifier(candidate)) {
            return null;
        }
        return new Label(candidate, code.substring(colon + 1));
    }

    private static boolean isForeignTopLevelLabel(
            TargetTriple target,
            String label) {
        if (target.osClassifier().equals("macos")) {
            return label.startsWith("_");
        }
        return !label.startsWith(".L")
                && !label.startsWith("LBB")
                && !label.startsWith("Ltmp")
                && !Character.isDigit(label.charAt(0));
    }

    private static boolean isFunctionEnd(
            String code,
            String symbol,
            TargetTriple target) {
        if (code.equals(".cfi_endproc") || code.equals(".seh_endproc")) {
            return true;
        }
        if (code.startsWith(".size")) {
            String remainder = code.substring(".size".length()).trim();
            int comma = remainder.indexOf(',');
            String named = comma < 0 ? remainder : remainder.substring(0, comma).trim();
            return canonicalSymbol(target, named).equals(symbol);
        }
        return code.equals(".subsections_via_symbols");
    }

    private static Instruction instruction(String code, int lineNumber) {
        int split = 0;
        while (split < code.length() && !Character.isWhitespace(code.charAt(split))) {
            split++;
        }
        String mnemonic = code.substring(0, split).toLowerCase(java.util.Locale.ROOT);
        if (!isAssemblyIdentifier(mnemonic)) {
            return null;
        }
        String operands = split == code.length() ? "" : code.substring(split).trim();
        return new Instruction(mnemonic, operands, lineNumber);
    }

    private static boolean isAssemblyIdentifier(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (!Character.isLetter(first) && first != '_' && first != '.' && first != '$') {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!Character.isLetterOrDigit(ch)
                    && ch != '_'
                    && ch != '.'
                    && ch != '$'
                    && ch != '@') {
                return false;
            }
        }
        return true;
    }

    private static String canonicalSymbol(TargetTriple target, String symbol) {
        String value = symbol;
        if (target.osClassifier().equals("macos") && value.startsWith("_")) {
            value = value.substring(1);
        }
        int suffix = value.indexOf('@');
        return suffix < 0 ? value : value.substring(0, suffix);
    }

    static IOException failure(String code, String detail) {
        return new IOException(
                "native registration optimized assembly audit failed: "
                        + code + " (" + detail + ")");
    }

    record Function(
            String symbol,
            Path source,
            int labelLine,
            List<Instruction> instructions,
            Map<String, Integer> labelInstructionIndexes) {}

    record Instruction(String mnemonic, String operands, int lineNumber) {}

    private record Label(String name, String remainder) {}

    private static final class MutableFunction {
        private final String symbol;
        private final Path source;
        private final int labelLine;
        private final List<Instruction> instructions = new ArrayList<>();
        private final Map<String, Integer> labelInstructionIndexes =
                new LinkedHashMap<>();

        private MutableFunction(String symbol, Path source, int labelLine) {
            this.symbol = symbol;
            this.source = source;
            this.labelLine = labelLine;
        }
    }
}
