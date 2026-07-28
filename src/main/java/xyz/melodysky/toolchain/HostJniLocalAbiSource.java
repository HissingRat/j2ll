package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import xyz.melodysky.runtime.RuntimeTokenMapper;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiDomain;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlan;
import xyz.melodysky.runtime.jni.RuntimeLocalAbiPlanner;

/** Formats the C side of a concrete-binding runtime local ABI plan. */
final class HostJniLocalAbiSource {
    private HostJniLocalAbiSource() {}

    static Emission emit(
            RuntimeTokenMapper runtimeTokens,
            RuntimeLocalAbiDomain domain,
            String operation,
            String identity,
            List<Parameter> logicalParameters) {
        Objects.requireNonNull(logicalParameters, "logicalParameters");
        RuntimeLocalAbiPlan plan = new RuntimeLocalAbiPlanner().plan(
                runtimeTokens,
                domain,
                operation,
                identity,
                logicalParameters.size());
        String parameters = plan.arrange(logicalParameters).stream()
                .map(parameter -> parameter.cType()
                        + " "
                        + parameter.name())
                .collect(Collectors.joining(", "));
        return new Emission(plan, parameters);
    }

    record Parameter(String cType, String name) {
        Parameter {
            Objects.requireNonNull(cType, "cType");
            Objects.requireNonNull(name, "name");
            if (cType.isBlank()) {
                throw new IllegalArgumentException(
                        "C parameter type must not be blank");
            }
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                        "C parameter name must be an identifier");
            }
        }
    }

    record Emission(
            RuntimeLocalAbiPlan plan,
            String parameterDeclarations) {
        Emission {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(
                    parameterDeclarations,
                    "parameterDeclarations");
        }
    }
}
