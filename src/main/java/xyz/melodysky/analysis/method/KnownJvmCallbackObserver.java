package xyz.melodysky.analysis.method;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.analysis.hierarchy.ClassHierarchy;
import xyz.melodysky.analysis.hierarchy.HierarchyClass;
import xyz.melodysky.frontend.classfile.ParsedMethod;

/**
 * Recognizes Java method declarations that a JVM or supported JDK library may
 * enter without an ordinary in-scope bytecode call site.
 *
 * <p>The catalog is intentionally closed and signature-exact. It preserves
 * known callback entries without treating every overridable method as an
 * external entry point. Third-party framework callbacks outside this catalog
 * remain part of the configured world/allowlist risk boundary.</p>
 */
public final class KnownJvmCallbackObserver {
    private static final List<CallbackContract> CONTRACTS = List.of(
            contract("java/lang/Runnable", "run", "()V"),
            contract(
                    "java/util/concurrent/Callable",
                    "call",
                    "()Ljava/lang/Object;"),
            contract("java/lang/Thread", "run", "()V"),
            contract("java/util/TimerTask", "run", "()V"),
            contract(
                    "java/util/Comparator",
                    "compare",
                    "(Ljava/lang/Object;Ljava/lang/Object;)I"),
            contract(
                    "java/lang/Object",
                    "equals",
                    "(Ljava/lang/Object;)Z"),
            contract("java/lang/Object", "hashCode", "()I"),
            contract(
                    "java/lang/Object",
                    "toString",
                    "()Ljava/lang/String;"),
            contract("java/lang/Object", "finalize", "()V"),
            contract(
                    "java/io/Externalizable",
                    "readExternal",
                    "(Ljava/io/ObjectInput;)V"),
            contract(
                    "java/io/Externalizable",
                    "writeExternal",
                    "(Ljava/io/ObjectOutput;)V"),
            contract(
                    "java/io/ObjectInputValidation",
                    "validateObject",
                    "()V"),
            contract(
                    "java/io/Serializable",
                    "readObject",
                    "(Ljava/io/ObjectInputStream;)V"),
            contract(
                    "java/io/Serializable",
                    "readObjectNoData",
                    "()V"),
            contract(
                    "java/io/Serializable",
                    "readResolve",
                    "()Ljava/lang/Object;"),
            contract(
                    "java/io/Serializable",
                    "writeObject",
                    "(Ljava/io/ObjectOutputStream;)V"),
            contract(
                    "java/io/Serializable",
                    "writeReplace",
                    "()Ljava/lang/Object;"),
            contract(
                    "java/lang/reflect/InvocationHandler",
                    "invoke",
                    "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;"),
            contract(
                    "java/util/function/Function",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;"),
            contract(
                    "java/util/function/BiFunction",
                    "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            contract(
                    "java/util/function/Consumer",
                    "accept",
                    "(Ljava/lang/Object;)V"),
            contract(
                    "java/util/function/BiConsumer",
                    "accept",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V"),
            contract(
                    "java/util/function/Supplier",
                    "get",
                    "()Ljava/lang/Object;"),
            contract(
                    "java/util/function/Predicate",
                    "test",
                    "(Ljava/lang/Object;)Z"),
            contract(
                    "java/util/function/BiPredicate",
                    "test",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z"),
            contract(
                    "java/util/function/UnaryOperator",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;"),
            contract(
                    "java/util/function/BinaryOperator",
                    "apply",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
            contract(
                    "java/util/function/IntConsumer",
                    "accept",
                    "(I)V"),
            contract(
                    "java/util/function/LongConsumer",
                    "accept",
                    "(J)V"),
            contract(
                    "java/util/function/DoubleConsumer",
                    "accept",
                    "(D)V"),
            contract(
                    "java/util/function/ObjIntConsumer",
                    "accept",
                    "(Ljava/lang/Object;I)V"),
            contract(
                    "java/util/function/ObjLongConsumer",
                    "accept",
                    "(Ljava/lang/Object;J)V"),
            contract(
                    "java/util/function/ObjDoubleConsumer",
                    "accept",
                    "(Ljava/lang/Object;D)V"),
            contract(
                    "java/util/function/IntSupplier",
                    "getAsInt",
                    "()I"),
            contract(
                    "java/util/function/LongSupplier",
                    "getAsLong",
                    "()J"),
            contract(
                    "java/util/function/DoubleSupplier",
                    "getAsDouble",
                    "()D"),
            contract(
                    "java/util/function/BooleanSupplier",
                    "getAsBoolean",
                    "()Z"),
            contract(
                    "java/util/function/IntPredicate",
                    "test",
                    "(I)Z"),
            contract(
                    "java/util/function/LongPredicate",
                    "test",
                    "(J)Z"),
            contract(
                    "java/util/function/DoublePredicate",
                    "test",
                    "(D)Z"),
            contract(
                    "java/util/function/IntFunction",
                    "apply",
                    "(I)Ljava/lang/Object;"),
            contract(
                    "java/util/function/LongFunction",
                    "apply",
                    "(J)Ljava/lang/Object;"),
            contract(
                    "java/util/function/DoubleFunction",
                    "apply",
                    "(D)Ljava/lang/Object;"),
            contract(
                    "java/util/function/ToIntFunction",
                    "applyAsInt",
                    "(Ljava/lang/Object;)I"),
            contract(
                    "java/util/function/ToLongFunction",
                    "applyAsLong",
                    "(Ljava/lang/Object;)J"),
            contract(
                    "java/util/function/ToDoubleFunction",
                    "applyAsDouble",
                    "(Ljava/lang/Object;)D"),
            contract(
                    "java/util/function/ToIntBiFunction",
                    "applyAsInt",
                    "(Ljava/lang/Object;Ljava/lang/Object;)I"),
            contract(
                    "java/util/function/ToLongBiFunction",
                    "applyAsLong",
                    "(Ljava/lang/Object;Ljava/lang/Object;)J"),
            contract(
                    "java/util/function/ToDoubleBiFunction",
                    "applyAsDouble",
                    "(Ljava/lang/Object;Ljava/lang/Object;)D"),
            contract(
                    "java/util/function/IntToLongFunction",
                    "applyAsLong",
                    "(I)J"),
            contract(
                    "java/util/function/IntToDoubleFunction",
                    "applyAsDouble",
                    "(I)D"),
            contract(
                    "java/util/function/LongToIntFunction",
                    "applyAsInt",
                    "(J)I"),
            contract(
                    "java/util/function/LongToDoubleFunction",
                    "applyAsDouble",
                    "(J)D"),
            contract(
                    "java/util/function/DoubleToIntFunction",
                    "applyAsInt",
                    "(D)I"),
            contract(
                    "java/util/function/DoubleToLongFunction",
                    "applyAsLong",
                    "(D)J"),
            contract(
                    "java/util/function/IntUnaryOperator",
                    "applyAsInt",
                    "(I)I"),
            contract(
                    "java/util/function/LongUnaryOperator",
                    "applyAsLong",
                    "(J)J"),
            contract(
                    "java/util/function/DoubleUnaryOperator",
                    "applyAsDouble",
                    "(D)D"),
            contract(
                    "java/util/function/IntBinaryOperator",
                    "applyAsInt",
                    "(II)I"),
            contract(
                    "java/util/function/LongBinaryOperator",
                    "applyAsLong",
                    "(JJ)J"),
            contract(
                    "java/util/function/DoubleBinaryOperator",
                    "applyAsDouble",
                    "(DD)D"));

    public Optional<String> observedContract(
            ParsedMethod method,
            ClassHierarchy hierarchy) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(hierarchy, "hierarchy");
        if (method.accessFlags().isStatic()) {
            return Optional.empty();
        }
        return CONTRACTS.stream()
                .filter(contract -> contract.matches(method))
                .filter(contract -> isSubtypeOf(
                        method.owner(),
                        contract.owner(),
                        hierarchy))
                .map(CallbackContract::methodKey)
                .findFirst();
    }

    private boolean isSubtypeOf(
            String candidate,
            String target,
            ClassHierarchy hierarchy) {
        ArrayDeque<String> work = new ArrayDeque<>();
        HashSet<String> visited = new HashSet<>();
        work.add(candidate);
        while (!work.isEmpty()) {
            String current = work.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.equals(target)) {
                return true;
            }
            Optional<HierarchyClass> type = hierarchy.lookupClass(current);
            if (type.isEmpty()) {
                continue;
            }
            if (type.get().superName() != null) {
                work.addLast(type.get().superName());
            }
            work.addAll(type.get().interfaces());
        }
        return false;
    }

    private static CallbackContract contract(
            String owner,
            String name,
            String descriptor) {
        return new CallbackContract(owner, name, descriptor);
    }

    private record CallbackContract(
            String owner,
            String name,
            String descriptor) {
        private CallbackContract {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }

        private boolean matches(ParsedMethod method) {
            return method.name().equals(name)
                    && method.descriptor().equals(descriptor);
        }

        private String methodKey() {
            return owner + "#" + name + "!" + descriptor;
        }
    }
}
