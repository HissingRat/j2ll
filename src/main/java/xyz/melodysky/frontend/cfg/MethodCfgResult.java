package xyz.melodysky.frontend.cfg;

import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.frontend.classfile.ParsedMethod;

public record MethodCfgResult(
        ParsedMethod method,
        Optional<BytecodeCfg> cfg,
        CfgMethodStatus status,
        String reasonCode,
        String reason) {
    public MethodCfgResult {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(cfg, "cfg");
        Objects.requireNonNull(status, "status");
        if (status == CfgMethodStatus.BUILT && cfg.isEmpty()) {
            throw new IllegalArgumentException("built CFG result must contain a CFG");
        }
        if (status == CfgMethodStatus.NO_CODE && cfg.isPresent()) {
            throw new IllegalArgumentException("no-code CFG result must not contain a CFG");
        }
    }

    public static MethodCfgResult built(BytecodeCfg cfg) {
        return new MethodCfgResult(cfg.method(), Optional.of(cfg), CfgMethodStatus.BUILT, null, null);
    }

    public static MethodCfgResult noCode(ParsedMethod method, String reasonCode, String reason) {
        return new MethodCfgResult(method, Optional.empty(), CfgMethodStatus.NO_CODE, reasonCode, reason);
    }
}
