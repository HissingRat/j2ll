package xyz.melodysky.toolchain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable physical control topology for one native registration build. */
final class NativeRegistrationControlTopologyPlan {
    static final int TARGET_MAX_OWNERS_PER_CHUNK = 4;
    static final int MAX_CHUNKS = 8;

    private final String aggregateSymbol;
    private final List<Owner> owners;
    private final List<Chunk> chunks;
    private final NativeRegistrationControlRoutePlan routePlan;
    private final FailureSymbols failureSymbols;

    NativeRegistrationControlTopologyPlan(
            String aggregateSymbol,
            List<Owner> owners,
            List<Chunk> chunks,
            NativeRegistrationControlRoutePlan routePlan,
            FailureSymbols failureSymbols) {
        this.aggregateSymbol = requireSymbol(
                aggregateSymbol,
                "aggregateSymbol");
        this.owners = List.copyOf(Objects.requireNonNull(
                owners,
                "owners"));
        this.chunks = List.copyOf(Objects.requireNonNull(
                chunks,
                "chunks"));
        this.routePlan = Objects.requireNonNull(
                routePlan,
                "routePlan");
        this.failureSymbols = Objects.requireNonNull(
                failureSymbols,
                "failureSymbols");
        validate();
    }

    String aggregateSymbol() {
        return aggregateSymbol;
    }

    List<Owner> owners() {
        return owners;
    }

    List<Chunk> chunks() {
        return chunks;
    }

    NativeRegistrationControlRoutePlan routePlan() {
        return routePlan;
    }

    FailureSymbols failureSymbols() {
        return failureSymbols;
    }

    private void validate() {
        int expectedChunks = expectedChunkCount(owners.size());
        if (chunks.size() != expectedChunks) {
            throw new IllegalArgumentException(
                    "registration control chunk count is invalid");
        }
        Set<String> symbols = new HashSet<>();
        addUnique(symbols, aggregateSymbol);
        for (String symbol : failureSymbols.symbols()) {
            addUnique(symbols, symbol);
        }
        if (owners.isEmpty() == routePlan.enabled()) {
            throw new IllegalArgumentException(
                    "registration entry route does not match owner presence");
        }
        for (NativeRegistrationControlRoutePlan.Route route
                : routePlan.routes()) {
            addUnique(symbols, route.symbol());
        }

        int expectedOwnerIndex = 0;
        for (Owner owner : owners) {
            if (owner.index() != expectedOwnerIndex++) {
                throw new IllegalArgumentException(
                        "registration control owner order is invalid");
            }
            addUnique(symbols, owner.symbol());
        }

        int expectedStart = 0;
        int minimumSize = Integer.MAX_VALUE;
        int maximumSize = 0;
        Set<NativeRegistrationChunkPostCallVariant> chunkVariants =
                new HashSet<>();
        for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
            Chunk chunk = chunks.get(ordinal);
            if (chunk.ordinal() != ordinal
                    || chunk.startInclusive() != expectedStart
                    || chunk.endExclusive() <= chunk.startInclusive()
                    || chunk.endExclusive() > owners.size()
                    || !chunk.owners().equals(owners.subList(
                            chunk.startInclusive(),
                            chunk.endExclusive()))) {
                throw new IllegalArgumentException(
                        "registration control chunks are not one contiguous partition");
            }
            int size = chunk.endExclusive() - chunk.startInclusive();
            minimumSize = Math.min(minimumSize, size);
            maximumSize = Math.max(maximumSize, size);
            expectedStart = chunk.endExclusive();
            addUnique(symbols, chunk.symbol());
            if (!chunkVariants.add(chunk.postCallVariant())
                    || chunk.witnessSalt() == 0L
                    || chunk.postCallSalt() == 0L) {
                throw new IllegalArgumentException(
                        "registration chunk post-call material is invalid");
            }
        }
        for (Owner owner : owners) {
            rejectRegistrationSymbolCollision(owner, symbols);
        }
        if (expectedStart != owners.size()) {
            throw new IllegalArgumentException(
                    "registration control owner conservation failed");
        }
        if (!chunks.isEmpty() && maximumSize - minimumSize > 1) {
            throw new IllegalArgumentException(
                    "registration control chunks are not balanced");
        }
        if (owners.size()
                        <= TARGET_MAX_OWNERS_PER_CHUNK * MAX_CHUNKS
                && maximumSize > TARGET_MAX_OWNERS_PER_CHUNK) {
            throw new IllegalArgumentException(
                    "registration control chunk target fanout exceeded");
        }
    }

    private void rejectRegistrationSymbolCollision(
            Owner owner,
            Set<String> controlSymbols) {
        NativeRegistrationTextPlan.Owner source = owner.source();
        java.util.ArrayList<String> registrationSymbols =
                new java.util.ArrayList<>();
        registrationSymbols.add(source.ownerText().symbol());
        for (NativeRegistrationTextPlan.Binding binding
                : source.bindings()) {
            registrationSymbols.add(
                    binding.registration().nativeSymbol());
            registrationSymbols.add(binding.nameText().symbol());
            registrationSymbols.add(
                    binding.descriptorText().symbol());
        }
        for (NativeRegistrationTextPlan.TextGroup group
                : source.textGroups()) {
            registrationSymbols.add(group.encoding().symbol());
        }
        for (String symbol : registrationSymbols) {
            if (controlSymbols.contains(symbol)) {
                throw new IllegalArgumentException(
                        "registration control symbol collides with registration material");
            }
        }
    }

    private void addUnique(
            Set<String> symbols,
            String symbol) {
        requireSymbol(symbol, "symbol");
        if (!symbols.add(symbol)) {
            throw new IllegalArgumentException(
                    "registration control symbol collision");
        }
    }

    private static String requireSymbol(
            String symbol,
            String name) {
        Objects.requireNonNull(symbol, name);
        if (!symbol.matches("[a-p]{32}")) {
            throw new IllegalArgumentException(
                    "registration control symbol is not hash-only");
        }
        return symbol;
    }

    static int expectedChunkCount(int ownerCount) {
        if (ownerCount < 0) {
            throw new IllegalArgumentException(
                    "registration owner count must not be negative");
        }
        if (ownerCount == 0) {
            return 0;
        }
        int needed = (ownerCount - 1)
                / TARGET_MAX_OWNERS_PER_CHUNK + 1;
        return Math.min(MAX_CHUNKS, needed);
    }

    record Owner(
            int index,
            NativeRegistrationTextPlan.Owner source,
            String symbol) {
        Owner {
            if (index < 0) {
                throw new IllegalArgumentException(
                        "registration owner index must not be negative");
            }
            Objects.requireNonNull(source, "source");
            requireSymbol(symbol, "symbol");
        }
    }

    record Chunk(
            int ordinal,
            int startInclusive,
            int endExclusive,
            String symbol,
            List<Owner> owners,
            NativeRegistrationChunkPostCallVariant postCallVariant,
            long witnessSalt,
            long postCallSalt) {
        Chunk {
            if (ordinal < 0
                    || startInclusive < 0
                    || endExclusive <= startInclusive) {
                throw new IllegalArgumentException(
                        "registration chunk range is invalid");
            }
            requireSymbol(symbol, "symbol");
            owners = List.copyOf(Objects.requireNonNull(
                    owners,
                    "owners"));
            Objects.requireNonNull(postCallVariant, "postCallVariant");
        }
    }

    record FailureSymbols(
            String ownerRollback,
            String ownerExceptionRestore,
            String aggregateRollback,
            String aggregateExceptionRestore) {
        FailureSymbols {
            requireSymbol(ownerRollback, "ownerRollback");
            requireSymbol(
                    ownerExceptionRestore,
                    "ownerExceptionRestore");
            requireSymbol(
                    aggregateRollback,
                    "aggregateRollback");
            requireSymbol(
                    aggregateExceptionRestore,
                    "aggregateExceptionRestore");
        }

        List<String> symbols() {
            return List.of(
                    ownerRollback,
                    ownerExceptionRestore,
                    aggregateRollback,
                    aggregateExceptionRestore);
        }
    }
}
