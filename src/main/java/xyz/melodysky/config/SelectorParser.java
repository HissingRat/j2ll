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
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("method selector has blank method name: " + raw);
        }
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
        }
    }

    private boolean isMethodDescriptor(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(")) {
            return false;
        }
        int end = descriptor.indexOf(')');
        return end > 0 && end < descriptor.length() - 1;
    }
}
