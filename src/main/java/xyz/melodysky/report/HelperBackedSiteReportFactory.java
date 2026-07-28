package xyz.melodysky.report;

import java.util.Objects;
import xyz.melodysky.analysis.runtime.RuntimeHelperSite;
import xyz.melodysky.protection.audit.HashOnlyEvidence;

/** Converts compiler-private helper identities into report-safe evidence. */
final class HelperBackedSiteReportFactory {
    private static final String HASH_DOMAIN = "lowering-report-helper-identity";

    HelperBackedSiteReport create(RuntimeHelperSite site) {
        Objects.requireNonNull(site, "site");
        return new HelperBackedSiteReport(
                safeKind(site.helper()),
                HashOnlyEvidence.sha256(HASH_DOMAIN, site.helper()),
                site.reasonCode());
    }

    private String safeKind(String helper) {
        int boundary = firstBoundary(helper);
        if (boundary > 0) {
            String prefix = helper.substring(0, boundary);
            if (prefix.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                return prefix;
            }
        }
        if (helper.matches("j2ll_(?:rt|h)_[A-Za-z0-9_]{1,80}")) {
            return helper;
        }
        return "runtimeHelper";
    }

    private int firstBoundary(String helper) {
        int boundary = helper.length();
        for (char separator : new char[] {'|', ':', '('}) {
            int candidate = helper.indexOf(separator);
            if (candidate >= 0 && candidate < boundary) {
                boundary = candidate;
            }
        }
        return boundary == helper.length() ? -1 : boundary;
    }
}
