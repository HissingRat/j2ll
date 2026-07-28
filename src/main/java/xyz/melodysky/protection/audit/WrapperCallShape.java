package xyz.melodysky.protection.audit;

/** Normalized wrapper-to-internal implementation shape. */
public enum WrapperCallShape {
    DIRECT_SINGLE_CALLEE("directSingleCallee"),
    INDIRECT_SLOT("indirectSlot"),
    INDIRECT_DISPATCH("indirectDispatch"),
    MULTIPLE_CALLEES("multipleCallees"),
    UNRESOLVED("unresolved");

    private final String wireName;

    WrapperCallShape(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
