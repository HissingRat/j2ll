package xyz.melodysky.config;

import java.util.Optional;

public final class SelectorParser {
    public Selector parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("selector must not be blank");
        }
        if (raw.endsWith(".class")) {
            throw new IllegalArgumentException("selector must use internal class names without .class: " + raw);
        }
        int hash = raw.indexOf('#');
        if (hash < 0) {
            validateClassPattern(raw, raw);
            return new Selector(raw, raw, Optional.empty(), Optional.empty());
        }
        if (raw.indexOf('#', hash + 1) >= 0) {
            throw new IllegalArgumentException("selector has multiple #: " + raw);
        }
        int bang = raw.indexOf('!', hash + 1);
        if (bang < 0) {
            throw new IllegalArgumentException("method selector requires a descriptor after !: " + raw);
        }
        String classPattern = raw.substring(0, hash);
        String methodName = raw.substring(hash + 1, bang);
        String descriptor = raw.substring(bang + 1);
        validateClassPattern(raw, classPattern);
        validateMethodName(raw, methodName);
        if (!isMethodDescriptor(descriptor)) {
            throw new IllegalArgumentException("method selector has invalid descriptor: " + raw);
        }
        return new Selector(raw, classPattern, Optional.of(methodName), Optional.of(descriptor));
    }

    private void validateClassPattern(String raw, String classPattern) {
        if (classPattern.isBlank()) {
            throw new IllegalArgumentException("selector has blank class pattern: " + raw);
        }
        for (String segment : classPattern.split("/", -1)) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("selector has empty class name segment: " + raw);
            }
            if (segment.contains("**") && !segment.equals("**")) {
                throw new IllegalArgumentException("** wildcard must be a whole segment: " + raw);
            }
            validateClassPatternSegment(raw, segment);
        }
    }

    private void validateClassPatternSegment(String raw, String segment) {
        if (segment.equals("*") || segment.equals("**")) {
            return;
        }
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            if (ch == '*') {
                continue;
            }
            if (ch == '.') {
                throw new IllegalArgumentException("selector must use / separated internal names, not dots: " + raw);
            }
            if (!isClassNameChar(ch)) {
                throw new IllegalArgumentException("selector has invalid class name character '" + ch + "': " + raw);
            }
        }
    }

    private boolean isClassNameChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    private void validateMethodName(String raw, String methodName) {
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("method selector has blank method name: " + raw);
        }
        if (methodName.equals("<init>") || methodName.equals("<clinit>")) {
            return;
        }
        for (int index = 0; index < methodName.length(); index++) {
            char ch = methodName.charAt(index);
            if (!(Character.isLetterOrDigit(ch) || ch == '_' || ch == '$')) {
                throw new IllegalArgumentException("method selector has invalid method name: " + raw);
            }
        }
    }

    private boolean isMethodDescriptor(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(")) {
            return false;
        }
        int[] index = {1};
        while (index[0] < descriptor.length() && descriptor.charAt(index[0]) != ')') {
            if (!parseFieldType(descriptor, index)) {
                return false;
            }
        }
        if (index[0] >= descriptor.length() || descriptor.charAt(index[0]) != ')') {
            return false;
        }
        index[0]++;
        if (index[0] >= descriptor.length()) {
            return false;
        }
        if (descriptor.charAt(index[0]) == 'V') {
            index[0]++;
            return index[0] == descriptor.length();
        }
        return parseFieldType(descriptor, index) && index[0] == descriptor.length();
    }

    private boolean parseFieldType(String descriptor, int[] index) {
        if (index[0] >= descriptor.length()) {
            return false;
        }
        char ch = descriptor.charAt(index[0]++);
        return switch (ch) {
            case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> true;
            case '[' -> parseFieldType(descriptor, index);
            case 'L' -> parseObjectType(descriptor, index);
            default -> false;
        };
    }

    private boolean parseObjectType(String descriptor, int[] index) {
        int start = index[0];
        while (index[0] < descriptor.length() && descriptor.charAt(index[0]) != ';') {
            char ch = descriptor.charAt(index[0]++);
            if (ch == '.' || ch == '[' || ch == ')' || ch == '(') {
                return false;
            }
        }
        if (index[0] >= descriptor.length() || descriptor.charAt(index[0]) != ';') {
            return false;
        }
        if (index[0] == start) {
            return false;
        }
        index[0]++;
        return true;
    }
}
