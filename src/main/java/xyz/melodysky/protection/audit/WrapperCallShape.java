package xyz.melodysky.protection.audit;

/** Normalized registered-entry or wrapper-to-internal call shape. */
public enum WrapperCallShape {
    REGISTERED_ENTRY_NO_RESOLVED_EDGE("registeredEntryNoResolvedEdge"),
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
