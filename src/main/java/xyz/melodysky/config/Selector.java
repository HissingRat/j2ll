package xyz.melodysky.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import xyz.melodysky.frontend.classfile.ParsedMethod;

public record Selector(
        String raw,
        String classPattern,
        Optional<String> methodName,
        Optional<String> descriptor) {
    public Selector {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(classPattern, "classPattern");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(descriptor, "descriptor");
        if (methodName.isPresent() != descriptor.isPresent()) {
            throw new IllegalArgumentException("method selector requires both method name and descriptor");
        }
    }

    public boolean isMethodSelector() {
        return methodName.isPresent();
    }

    public boolean matchesClass(String internalName) {
        return matchesSegments(classPattern.split("/", -1), 0, internalName.split("/", -1), 0);
    }

    public boolean matchesMethod(ParsedMethod method) {
        if (!matchesClass(method.owner())) {
            return false;
        }
        return methodName.isEmpty()
                || (method.name().equals(methodName.orElseThrow())
                        && method.descriptor().equals(descriptor.orElseThrow()));
    }

    private boolean matchesSegments(String[] patternSegments, int patternIndex, String[] nameSegments, int nameIndex) {
        if (patternIndex == patternSegments.length) {
            return nameIndex == nameSegments.length;
        }
        String segment = patternSegments[patternIndex];
        if (segment.equals("**")) {
            for (int next = nameIndex; next <= nameSegments.length; next++) {
                if (matchesSegments(patternSegments, patternIndex + 1, nameSegments, next)) {
                    return true;
                }
            }
            return false;
        }
        if (nameIndex >= nameSegments.length) {
            return false;
        }
        return matchesSegment(segment, nameSegments[nameIndex])
                && matchesSegments(patternSegments, patternIndex + 1, nameSegments, nameIndex + 1);
    }

    private boolean matchesSegment(String patternSegment, String nameSegment) {
        if (patternSegment.equals("*")) {
            return !nameSegment.isEmpty();
        }
        if (!patternSegment.contains("*")) {
            return patternSegment.equals(nameSegment);
        }
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < patternSegment.length(); index++) {
            char ch = patternSegment.charAt(index);
            if (ch == '*') {
                regex.append("[^/]*");
            } else {
                regex.append(Pattern.quote(Character.toString(ch)));
            }
        }
        return Pattern.matches(regex.toString(), nameSegment);
    }

    static Selector implicitAll() {
        return new Selector("<implicit-all>", "**", Optional.empty(), Optional.empty());
    }

    List<String> classPatternSegments() {
        return List.of(classPattern.split("/", -1));
    }
}
