package xyz.melodysky.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import xyz.melodysky.analysis.method.NativeOnlyMethodCoalescingPlan;
import xyz.melodysky.ir.model.IrMethod;
import xyz.melodysky.report.ProtectionPassReport;
import xyz.melodysky.toolchain.NativeImplementationPlan;

public record NativeOnlyMethodCoalescingResult(
        Map<String, IrMethod> methods,
        NativeImplementationPlan implementationPlan,
        NativeOnlyMethodCoalescingPlan plan,
        ProtectionPassReport protectionReport) {
    public NativeOnlyMethodCoalescingResult {
        methods = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(methods, "methods")));
        Objects.requireNonNull(implementationPlan, "implementationPlan");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(protectionReport, "protectionReport");
    }
}
