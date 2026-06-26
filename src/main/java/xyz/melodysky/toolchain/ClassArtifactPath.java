package xyz.melodysky.toolchain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

public final class ClassArtifactPath {
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final Function<String, String> hashFunction;

    public ClassArtifactPath() {
        this(ClassArtifactPath::sha256Default);
    }

    ClassArtifactPath(Function<String, String> hashFunction) {
        this.hashFunction = hashFunction;
    }

    public String classDirectory(String internalName) {
        return classDirectory(internalName, 16);
    }

    public String classDirectory(String internalName, int hashPrefixLength) {
        return safeInternalName(internalName) + "__" + fullHash(internalName).substring(0, hashPrefixLength);
    }

    public String methodId(String internalClassName, String methodName, String descriptor) {
        return methodId(internalClassName, methodName, descriptor, 16);
    }

    public String methodId(String internalClassName, String methodName, String descriptor, int hashPrefixLength) {
        return safeMethodName(methodName) + "__"
                + methodHash(internalClassName, methodName, descriptor).substring(0, hashPrefixLength);
    }

    public String fullHash(String input) {
        return hashFunction.apply(input);
    }

    public String methodHash(String internalClassName, String methodName, String descriptor) {
        return fullHash(internalClassName + "#" + methodName + "!" + descriptor);
    }

    public String safeInternalName(String internalName) {
        StringBuilder result = new StringBuilder();
        for (String segment : internalName.split("/", -1)) {
            if (!result.isEmpty()) {
                result.append('/');
            }
            result.append(safeSegment(segment));
        }
        return result.toString();
    }

    public String safeMethodName(String methodName) {
        if (methodName.equals("<init>")) {
            return "_init_";
        }
        if (methodName.equals("<clinit>")) {
            return "_clinit_";
        }
        return safeSegment(methodName);
    }

    public String safeSegment(String segment) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segment.length(); index++) {
            char ch = segment.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '$' || ch == '-' || ch == '.') {
                result.append(ch);
            } else {
                result.append("_u").append(String.format("%04x", (int) ch)).append('_');
            }
        }
        if (result.isEmpty() || result.toString().equals(".") || result.toString().equals("..")) {
            return "_";
        }
        String safe = result.toString();
        String upper = safe.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        if (dot >= 0) {
            upper = upper.substring(0, dot);
        }
        if (WINDOWS_RESERVED_NAMES.contains(upper)) {
            safe = "_" + safe;
        }
        if (safe.endsWith(".") || safe.endsWith(" ")) {
            safe = safe + "_";
        }
        return safe;
    }

    private static String sha256Default(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
