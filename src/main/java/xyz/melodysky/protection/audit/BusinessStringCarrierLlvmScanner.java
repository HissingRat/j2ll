package xyz.melodysky.protection.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the one supported business-string carrier shape from debug LLVM. */
public final class BusinessStringCarrierLlvmScanner {
    public static final String MALFORMED_DECLARATION =
            "MALFORMED_BUSINESS_STRING_CARRIER_DECLARATION";
    public static final String DUPLICATE_NAME =
            "DUPLICATE_BUSINESS_STRING_CARRIER_NAME";

    /*
     * j2ll_v_ is not a globally reserved user-SSA prefix. Only the generated
     * 24-hex identity shape enters strict carrier validation.
     */
    private static final Pattern CANDIDATE = Pattern.compile(
            "^\\s*%j2ll_v_[0-9a-f]{24}(?=\\s|=).*$");
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s*%(j2ll_v_[0-9a-f]{24})\\s*=\\s*"
                    + "add\\s+i64\\s+0\\s*,\\s*"
                    + "(-?(?:0|[1-9][0-9]*))\\s*$");
    private static final String NAME_HASH_DOMAIN =
            "business-string-carrier/name/v1";
    private static final String TOKEN_HASH_DOMAIN =
            "business-string-carrier/numeric-token/v1";

    public BusinessStringCarrierSnapshot scan(Path debugLlvm)
            throws IOException {
        Objects.requireNonNull(debugLlvm, "debugLlvm");
        return scan(Files.readString(debugLlvm, StandardCharsets.UTF_8));
    }

    public BusinessStringCarrierSnapshot scan(String debugLlvm) {
        Objects.requireNonNull(debugLlvm, "debugLlvm");
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> numericTokens = new ArrayList<>();
        HashSet<String> rawNames = new HashSet<>();
        for (String line : debugLlvm.lines().toList()) {
            if (!CANDIDATE.matcher(line).matches()) {
                continue;
            }
            Matcher declaration = DECLARATION.matcher(line);
            if (!declaration.matches()) {
                throw failure(MALFORMED_DECLARATION);
            }
            String name = declaration.group(1);
            if (!rawNames.add(name)) {
                throw failure(DUPLICATE_NAME);
            }
            long numericToken;
            try {
                numericToken = Long.parseLong(declaration.group(2));
            } catch (NumberFormatException exception) {
                throw failure(MALFORMED_DECLARATION);
            }
            names.add(HashOnlyEvidence.sha256(NAME_HASH_DOMAIN, name));
            numericTokens.add(HashOnlyEvidence.sha256(
                    TOKEN_HASH_DOMAIN,
                    Long.toString(numericToken)));
        }
        return new BusinessStringCarrierSnapshot(
                names.size(),
                names,
                numericTokens);
    }

    private BusinessStringCarrierScanException failure(String reasonCode) {
        return new BusinessStringCarrierScanException(reasonCode);
    }
}
