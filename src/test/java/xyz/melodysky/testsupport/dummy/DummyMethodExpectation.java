package xyz.melodysky.testsupport.dummy;

import java.util.Objects;

/** Exact lowering contract for one Code-bearing Dummy selector. */
public record DummyMethodExpectation(
        String selector,
        String expectedStatus,
        String expectedReasonCode) {
    public DummyMethodExpectation {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(expectedStatus, "expectedStatus");
        if (!expectedStatus.equals("nativeLowered")
                && !expectedStatus.equals("skipped")
                && !expectedStatus.equals("ineligible")) {
            throw new IllegalArgumentException("unsupported Dummy method status: " + expectedStatus);
        }
        if (!expectedStatus.equals("nativeLowered")
                && (expectedReasonCode == null || expectedReasonCode.isBlank())) {
            throw new IllegalArgumentException(expectedStatus
                    + " Dummy expectation requires a reason code: " + selector);
        }
        if (expectedStatus.equals("nativeLowered") && expectedReasonCode != null) {
            throw new IllegalArgumentException("native-lowered Dummy expectation cannot have a reason code: " + selector);
        }
        SelectorParts.parse(selector);
    }

    public static DummyMethodExpectation nativeLowered(String selector) {
        return new DummyMethodExpectation(selector, "nativeLowered", null);
    }

    public static DummyMethodExpectation skipped(String selector, String reasonCode) {
        return new DummyMethodExpectation(selector, "skipped", reasonCode);
    }

    public static DummyMethodExpectation ineligible(String selector, String reasonCode) {
        return new DummyMethodExpectation(selector, "ineligible", reasonCode);
    }

    SelectorParts parts() {
        return SelectorParts.parse(selector);
    }

    record SelectorParts(String selector, String owner, String method, String descriptor) {
        static SelectorParts parse(String selector) {
            int ownerEnd = selector.indexOf('#');
            int methodEnd = selector.indexOf('!', ownerEnd + 1);
            if (ownerEnd <= 0 || methodEnd <= ownerEnd + 1 || methodEnd == selector.length() - 1) {
                throw new IllegalArgumentException("invalid exact Dummy selector: " + selector);
            }
            return new SelectorParts(
                    selector,
                    selector.substring(0, ownerEnd),
                    selector.substring(ownerEnd + 1, methodEnd),
                    selector.substring(methodEnd + 1));
        }
    }
}
