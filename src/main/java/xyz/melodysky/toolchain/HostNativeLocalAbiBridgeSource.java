package xyz.melodysky.toolchain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

/**
 * Emits a build-scoped wrapper-to-LLVM call topology.
 *
 * <p>A topology is either a direct canonical call, one parameter-permuting
 * bridge, two small parameter-permuting bridges, or a bounded branched
 * topology selecting between a one-bridge and two-bridge route. Bridges
 * perform no JNI calls, do not dereference Java references and do not inspect
 * or clear pending exceptions.</p>
 */
final class HostNativeLocalAbiBridgeSource {
    Emission emit(
            NativeTextBuildKey buildKey,
            String methodKey,
            String returnType,
            String llvmFunctionSymbol,
            List<Parameter> parameters) {
        return emit(
                buildKey,
                methodKey,
                returnType,
                llvmFunctionSymbol,
                parameters,
                NativeLocalAbiProfile.COMPACT_DIVERSE);
    }

    Emission emit(
            NativeTextBuildKey buildKey,
            String methodKey,
            String returnType,
            String llvmFunctionSymbol,
            List<Parameter> parameters,
            NativeLocalAbiProfile profile) {
        Objects.requireNonNull(returnType, "returnType");
        if (returnType.isBlank()) {
            throw new IllegalArgumentException(
                    "returnType must not be blank");
        }
        requireIdentifier(llvmFunctionSymbol, "llvmFunctionSymbol");
        parameters = List.copyOf(
                Objects.requireNonNull(parameters, "parameters"));
        validateParameters(parameters);
        NativeLocalAbiPlan plan = new NativeLocalAbiPlanner().plan(
                buildKey,
                methodKey,
                parameters.size(),
                profile);

        String canonicalNames = joinCanonical(
                parameters,
                Parameter::name);
        String canonicalWrapperArguments = joinCanonical(
                parameters,
                Parameter::wrapperExpression);
        if (plan.shape()
                == NativeLocalAbiPlan.Shape.DIRECT_CANONICAL) {
            return new Emission(
                    "",
                    "",
                    llvmFunctionSymbol
                            + "("
                            + canonicalWrapperArguments
                            + ")",
                    plan);
        }

        if (plan.shape().branched()) {
            return branchedEmission(
                    returnType,
                    llvmFunctionSymbol,
                    parameters,
                    plan,
                    canonicalNames);
        }

        StringBuilder source = new StringBuilder();
        for (int bridge = plan.bridgeSymbols().size() - 1;
                bridge >= 0;
                bridge--) {
            List<Integer> incomingOrder =
                    plan.parameterOrders().get(bridge);
            source.append("static ")
                    .append(returnType)
                    .append(' ')
                    .append(plan.bridgeSymbols().get(bridge))
                    .append('(')
                    .append(join(
                            incomingOrder,
                            parameters,
                            parameter -> parameter.type()
                                    + " "
                                    + parameter.name(),
                            "void"))
                    .append(") {\n")
                    .append("    ");
            if (!returnType.equals("void")) {
                source.append("return ");
            }
            if (bridge == plan.bridgeSymbols().size() - 1) {
                source.append(llvmFunctionSymbol)
                        .append('(')
                        .append(canonicalNames)
                        .append(");\n");
            } else {
                source.append(plan.bridgeSymbols().get(bridge + 1))
                        .append('(')
                        .append(join(
                                plan.parameterOrders().get(bridge + 1),
                                parameters,
                                Parameter::name,
                                ""))
                        .append(");\n");
            }
            source.append("}\n\n");
        }

        return new Emission(
                source.toString(),
                "",
                plan.bridgeSymbols().get(0)
                        + "("
                        + join(
                                plan.parameterOrders().get(0),
                                parameters,
                                Parameter::wrapperExpression,
                                "")
                        + ")",
                plan);
    }

    private Emission branchedEmission(
            String returnType,
            String llvmFunctionSymbol,
            List<Parameter> parameters,
            NativeLocalAbiPlan plan,
            String canonicalNames) {
        String attributeMacro =
                plan.bridgeSymbols().get(0) + "_attributes";
        StringBuilder source = new StringBuilder()
                .append("#if defined(__clang__)\n")
                .append("#define ")
                .append(attributeMacro)
                .append(" __attribute__((noinline, used))\n")
                .append("#elif defined(__GNUC__)\n")
                .append("#define ")
                .append(attributeMacro)
                .append(" __attribute__((noinline, used))\n")
                .append("#else\n")
                .append("#define ")
                .append(attributeMacro)
                .append('\n')
                .append("#endif\n");

        appendBridge(
                source,
                attributeMacro,
                returnType,
                plan.bridgeSymbols().get(2),
                plan.parameterOrders().get(2),
                parameters,
                llvmFunctionSymbol,
                canonicalNames);
        appendBridge(
                source,
                attributeMacro,
                returnType,
                plan.bridgeSymbols().get(1),
                plan.parameterOrders().get(1),
                parameters,
                plan.bridgeSymbols().get(2),
                join(
                        plan.parameterOrders().get(2),
                        parameters,
                        Parameter::name,
                        ""));
        appendBridge(
                source,
                attributeMacro,
                returnType,
                plan.bridgeSymbols().get(0),
                plan.parameterOrders().get(0),
                parameters,
                llvmFunctionSymbol,
                canonicalNames);
        source.append("#undef ")
                .append(attributeMacro)
                .append("\n\n");

        String predicate =
                plan.bridgeSymbols().get(0) + "_predicate";
        int predicateBit =
                8 + ((plan.branchSalt() >>> 24) & 7);
        String wrapperPrelude = String.format(
                java.util.Locale.ROOT,
                "    volatile uintptr_t %s = "
                        + "((uintptr_t)(void*)&%s) ^ "
                        + "(uintptr_t)0x%08xu;\n",
                predicate,
                predicate,
                Integer.toUnsignedLong(plan.branchSalt()));
        String firstArguments = join(
                plan.parameterOrders().get(0),
                parameters,
                Parameter::wrapperExpression,
                "");
        String secondArguments = join(
                plan.parameterOrders().get(1),
                parameters,
                Parameter::wrapperExpression,
                "");
        String wrapperInvocation = "((("
                + predicate
                + " >> "
                + predicateBit
                + ") & (uintptr_t)1u) != 0u ? "
                + plan.bridgeSymbols().get(0)
                + "("
                + firstArguments
                + ") : "
                + plan.bridgeSymbols().get(1)
                + "("
                + secondArguments
                + "))";
        return new Emission(
                source.toString(),
                wrapperPrelude,
                wrapperInvocation,
                plan);
    }

    private void appendBridge(
            StringBuilder source,
            String attributeMacro,
            String returnType,
            String bridgeSymbol,
            List<Integer> incomingOrder,
            List<Parameter> parameters,
            String targetSymbol,
            String targetArguments) {
        source.append("static ")
                .append(attributeMacro)
                .append(' ')
                .append(returnType)
                .append(' ')
                .append(bridgeSymbol)
                .append('(')
                .append(join(
                        incomingOrder,
                        parameters,
                        parameter -> parameter.type()
                                + " "
                                + parameter.name(),
                        "void"))
                .append(") {\n")
                .append("    ");
        if (!returnType.equals("void")) {
            source.append("return ");
        }
        source.append(targetSymbol)
                .append('(')
                .append(targetArguments)
                .append(");\n")
                .append("}\n\n");
    }

    private <T> String joinCanonical(
            List<T> values,
            java.util.function.Function<T, String> mapper) {
        return values.isEmpty()
                ? ""
                : String.join(
                        ", ",
                        values.stream().map(mapper).toList());
    }

    private String join(
            List<Integer> order,
            List<Parameter> parameters,
            java.util.function.Function<Parameter, String> mapper,
            String emptyValue) {
        if (order.isEmpty()) {
            return emptyValue;
        }
        return String.join(
                ", ",
                order.stream()
                        .map(parameters::get)
                        .map(mapper)
                        .toList());
    }

    private void validateParameters(List<Parameter> parameters) {
        HashSet<String> names = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (!names.add(parameter.name())) {
                throw new IllegalArgumentException(
                        "local ABI parameter names must be unique");
            }
        }
    }

    private void requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    label + " must be a C identifier");
        }
    }

    record Parameter(
            String type,
            String name,
            String wrapperExpression) {
        Parameter {
            if (Objects.requireNonNull(type, "type").isBlank()
                    || Objects.requireNonNull(name, "name").isBlank()
                    || Objects.requireNonNull(
                                    wrapperExpression,
                                    "wrapperExpression")
                            .isBlank()) {
                throw new IllegalArgumentException(
                        "local ABI parameter fields must not be blank");
            }
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(
                        "local ABI parameter name must be a C identifier");
            }
        }
    }

    record Emission(
            String source,
            String wrapperPrelude,
            String wrapperInvocation,
            NativeLocalAbiPlan plan) {
        Emission {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(wrapperPrelude, "wrapperPrelude");
            Objects.requireNonNull(wrapperInvocation, "wrapperInvocation");
            Objects.requireNonNull(plan, "plan");
        }
    }
}
