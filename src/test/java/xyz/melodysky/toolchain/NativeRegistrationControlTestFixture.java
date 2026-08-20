package xyz.melodysky.toolchain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import xyz.melodysky.packaging.MethodTableHidingPlanner;
import xyz.melodysky.packaging.NativeRegistrationEntry;
import xyz.melodysky.packaging.NativeRegistrationPlan;
import xyz.melodysky.packaging.RuntimeLoaderPlan;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;

final class NativeRegistrationControlTestFixture {
    private NativeRegistrationControlTestFixture() {}

    static NativeTextBuildKey key(String identity) {
        return NativeTextBuildKey.fromUtf8(identity);
    }

    static NativeRegistrationControlTopologyPlan plan(
            int ownerCount,
            String identity) {
        NativeTextBuildKey key = key(identity);
        return new NativeRegistrationControlTopologyPlanner().plan(
                physicalOwners(ownerCount, key),
                key);
    }

    static HostNativeRegistrationSource.Emission emission(
            int ownerCount,
            String identity) {
        NativeRegistrationPlan registrations = registrations(ownerCount);
        return new HostNativeRegistrationSource().emitWithPlan(
                registrations,
                new MethodTableHidingPlanner().plan(
                        registrations,
                        false,
                        0L),
                RuntimeLoaderPlan.create("native0"),
                key(identity));
    }

    static List<NativeRegistrationTextPlan.Owner> physicalOwners(
            int ownerCount,
            NativeTextBuildKey key) {
        NativeRegistrationPlan registrations = registrations(ownerCount);
        return NativeRegistrationTextPlan.ordinary(registrations, key)
                .stream()
                .sorted(Comparator.comparing(owner ->
                        owner.ownerText().symbol()))
                .toList();
    }

    static NativeRegistrationPlan registrations(int ownerCount) {
        return registrations(ownerCount, index -> "native_fixture_" + index);
    }

    static NativeRegistrationPlan registrations(
            int ownerCount,
            java.util.function.IntFunction<String> nativeSymbol) {
        return new NativeRegistrationPlan(IntStream.range(0, ownerCount)
                .mapToObj(index -> new NativeRegistrationEntry(
                        "registration/control/Owner"
                                + String.format("%03d", index),
                        "method" + index,
                        "()V",
                        nativeSymbol.apply(index)))
                .toList());
    }

    static List<String> logicalOwnerOrder(
            NativeRegistrationControlTopologyPlan plan) {
        return plan.owners().stream()
                .map(owner -> owner.source().owner())
                .toList();
    }

    static Set<String> logicalOwners(
            NativeRegistrationControlTopologyPlan plan) {
        return plan.owners().stream()
                .map(owner -> owner.source().owner())
                .collect(Collectors.toSet());
    }

    static List<String> controlSymbols(
            NativeRegistrationControlTopologyPlan plan) {
        ArrayList<String> symbols = new ArrayList<>();
        symbols.add(plan.aggregateSymbol());
        symbols.addAll(plan.routePlan().routes().stream()
                .map(NativeRegistrationControlRoutePlan.Route::symbol)
                .toList());
        symbols.addAll(plan.owners().stream()
                .map(NativeRegistrationControlTopologyPlan.Owner::symbol)
                .toList());
        symbols.addAll(plan.chunks().stream()
                .map(NativeRegistrationControlTopologyPlan.Chunk::symbol)
                .toList());
        symbols.addAll(plan.failureSymbols().symbols());
        return List.copyOf(symbols);
    }

    static String function(String source, String symbol) {
        return functionAtHeader(
                source,
                "static jint " + symbol + "(");
    }

    static String functionAtHeader(String source, String marker) {
        int signature = -1;
        int searchFrom = 0;
        while ((signature = source.indexOf(marker, searchFrom)) >= 0) {
            int openingBrace = source.indexOf('{', signature);
            int semicolon = source.indexOf(';', signature);
            if (openingBrace >= 0
                    && (semicolon < 0 || openingBrace < semicolon)) {
                break;
            }
            searchFrom = signature + marker.length();
        }
        if (signature < 0) {
            throw new AssertionError("missing function definition: " + marker);
        }
        int openingBrace = source.indexOf('{', signature);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(signature, index + 1);
            }
        }
        throw new AssertionError("incomplete function: " + marker);
    }

    static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
