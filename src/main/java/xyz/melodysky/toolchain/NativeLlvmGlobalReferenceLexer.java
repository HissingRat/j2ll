package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.List;

/** Exact symbol lexer for opaque generated LLVM global definitions. */
final class NativeLlvmGlobalReferenceLexer {
    List<String> symbolReferences(String llvmDefinition) {
        ArrayList<String> result = new ArrayList<>();
        int cursor = 0;
        boolean inString = false;
        while (cursor < llvmDefinition.length()) {
            char current = llvmDefinition.charAt(cursor);
            if (inString) {
                if (current == '\\' && cursor + 1 < llvmDefinition.length()) {
                    cursor += 2;
                    continue;
                }
                if (current == '"') {
                    inString = false;
                }
                cursor++;
                continue;
            }
            if (current == '"') {
                inString = true;
                cursor++;
                continue;
            }
            if (current != '@') {
                cursor++;
                continue;
            }
            cursor = readSymbol(llvmDefinition, cursor + 1, result);
        }
        return List.copyOf(result);
    }

    private int readSymbol(
            String definition,
            int cursor,
            List<String> result) {
        if (cursor < definition.length()
                && definition.charAt(cursor) == '"') {
            return readQuotedSymbol(definition, cursor + 1, result);
        }
        int start = cursor;
        while (cursor < definition.length()
                && isUnquotedSymbolCharacter(definition.charAt(cursor))) {
            cursor++;
        }
        if (cursor > start) {
            result.add(definition.substring(start, cursor));
        }
        return cursor;
    }

    private int readQuotedSymbol(
            String definition,
            int cursor,
            List<String> result) {
        StringBuilder symbol = new StringBuilder();
        while (cursor < definition.length()) {
            char value = definition.charAt(cursor);
            if (value == '"') {
                result.add(symbol.toString());
                return cursor + 1;
            }
            if (value == '\\'
                    && cursor + 2 < definition.length()
                    && isHex(definition.charAt(cursor + 1))
                    && isHex(definition.charAt(cursor + 2))) {
                symbol.append((char) Integer.parseInt(
                        definition.substring(cursor + 1, cursor + 3),
                        16));
                cursor += 3;
                continue;
            }
            symbol.append(value);
            cursor++;
        }
        return cursor;
    }

    private boolean isUnquotedSymbolCharacter(char value) {
        return Character.isLetterOrDigit(value)
                || value == '_'
                || value == '.'
                || value == '$'
                || value == '-';
    }

    private boolean isHex(char value) {
        return value >= '0' && value <= '9'
                || value >= 'a' && value <= 'f'
                || value >= 'A' && value <= 'F';
    }
}
