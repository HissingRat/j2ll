package xyz.melodysky.toolchain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable two-path, three-function entry route into registration. */
final class NativeRegistrationControlRoutePlan {
    static final int ROUTE_COUNT = 3;

    private final List<Route> routes;
    private final long rootGuardSalt;
    private final long rootSelectorSalt;
    private final long rootPostCallSalt;
    private final int rootSelectorShift;

    NativeRegistrationControlRoutePlan(
            List<Route> routes,
            long rootGuardSalt,
            long rootSelectorSalt,
            long rootPostCallSalt,
            int rootSelectorShift) {
        this.routes = List.copyOf(Objects.requireNonNull(
                routes,
                "routes"));
        this.rootGuardSalt = rootGuardSalt;
        this.rootSelectorSalt = rootSelectorSalt;
        this.rootPostCallSalt = rootPostCallSalt;
        this.rootSelectorShift = rootSelectorShift;
        validate();
    }

    static NativeRegistrationControlRoutePlan disabled() {
        return new NativeRegistrationControlRoutePlan(
                List.of(),
                0L,
                0L,
                0L,
                0);
    }

    boolean enabled() {
        return !routes.isEmpty();
    }

    List<Route> routes() {
        return routes;
    }

    Route route(int ordinal) {
        if (ordinal < 0 || ordinal >= routes.size()) {
            throw new IllegalArgumentException(
                    "registration route ordinal is invalid");
        }
        return routes.get(ordinal);
    }

    long rootGuardSalt() {
        return rootGuardSalt;
    }

    long rootSelectorSalt() {
        return rootSelectorSalt;
    }

    long rootPostCallSalt() {
        return rootPostCallSalt;
    }

    int rootSelectorShift() {
        return rootSelectorShift;
    }

    private void validate() {
        if (routes.isEmpty()) {
            if (rootGuardSalt != 0L
                    || rootSelectorSalt != 0L
                    || rootPostCallSalt != 0L
                    || rootSelectorShift != 0) {
                throw new IllegalArgumentException(
                        "disabled registration route carries material");
            }
            return;
        }
        if (routes.size() != ROUTE_COUNT
                || rootSelectorShift < 1
                || rootSelectorShift > 31) {
            throw new IllegalArgumentException(
                    "registration entry route shape is invalid");
        }
        Set<String> symbols = new HashSet<>();
        Set<List<Parameter>> orders = new HashSet<>();
        Set<NativeRegistrationPostCallRecipe> recipes =
                new HashSet<>();
        Set<Long> materials = new HashSet<>(List.of(
                rootGuardSalt,
                rootSelectorSalt,
                rootPostCallSalt));
        if (materials.size() != 3 || materials.contains(0L)) {
            throw new IllegalArgumentException(
                    "registration root material is invalid");
        }
        for (int ordinal = 0; ordinal < routes.size(); ordinal++) {
            Route route = routes.get(ordinal);
            if (route.ordinal() != ordinal
                    || !symbols.add(route.symbol())
                    || !orders.add(route.parameterOrder())
                    || !recipes.add(route.postCallRecipe())
                    || !materials.add(route.witnessSalt())
                    || !materials.add(route.postCallSalt())) {
                throw new IllegalArgumentException(
                        "registration entry route diversity is invalid");
            }
        }
        if (routes.get(0).targetKind() != TargetKind.AGGREGATE
                || routes.get(0).targetRouteOrdinal() != -1
                || routes.get(1).targetKind() != TargetKind.ROUTE
                || routes.get(1).targetRouteOrdinal() != 2
                || routes.get(2).targetKind() != TargetKind.AGGREGATE
                || routes.get(2).targetRouteOrdinal() != -1) {
            throw new IllegalArgumentException(
                    "registration entry route graph is invalid");
        }
    }

    enum Parameter {
        VM("JavaVM*", "vm"),
        RESERVED("void*", "reserved"),
        GUARD("uintptr_t", "guard");

        private final String cType;
        private final String cName;

        Parameter(String cType, String cName) {
            this.cType = cType;
            this.cName = cName;
        }

        String declaration() {
            return cType + " " + cName;
        }

        String argument() {
            return cName;
        }
    }

    enum TargetKind {
        AGGREGATE,
        ROUTE
    }

    record Route(
            int ordinal,
            String symbol,
            List<Parameter> parameterOrder,
            TargetKind targetKind,
            int targetRouteOrdinal,
            NativeRegistrationPostCallRecipe postCallRecipe,
            long witnessSalt,
            long postCallSalt) {
        Route {
            if (ordinal < 0) {
                throw new IllegalArgumentException(
                        "registration route ordinal must not be negative");
            }
            Objects.requireNonNull(symbol, "symbol");
            if (!symbol.matches("[a-p]{32}")) {
                throw new IllegalArgumentException(
                        "registration route symbol is not hash-only");
            }
            parameterOrder = List.copyOf(Objects.requireNonNull(
                    parameterOrder,
                    "parameterOrder"));
            if (parameterOrder.size() != Parameter.values().length
                    || new HashSet<>(parameterOrder).size()
                            != Parameter.values().length) {
                throw new IllegalArgumentException(
                        "registration route parameters are not a permutation");
            }
            Objects.requireNonNull(targetKind, "targetKind");
            Objects.requireNonNull(postCallRecipe, "postCallRecipe");
            if (witnessSalt == 0L || postCallSalt == 0L) {
                throw new IllegalArgumentException(
                        "registration route material must be non-zero");
            }
        }
    }
}
