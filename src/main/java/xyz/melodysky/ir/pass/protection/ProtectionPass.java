package xyz.melodysky.ir.pass.protection;

import xyz.melodysky.ir.model.IrMethod;

public interface ProtectionPass {
    String name();

    default boolean enabled(ProtectionConfig config) {
        return config.enabled();
    }

    default boolean applicable(IrMethod method) {
        return true;
    }

    default String skipReasonCode(IrMethod method) {
        return "PROTECTION_PASS_NOT_APPLICABLE";
    }

    IrMethod run(IrMethod method, ProtectionConfig config);
}
