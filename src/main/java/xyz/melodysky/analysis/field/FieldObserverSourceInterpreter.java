package xyz.melodysky.analysis.field;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Source interpreter that keeps unknown origins visible across CFG merges.
 *
 * <p>ASM's default parameter values have an empty source set. Merging such a
 * value with a constant can therefore erase the unknown branch. A synthetic
 * source marker makes the uncertainty participate in the normal SourceValue
 * union without changing bytecode or frames.</p>
 */
final class FieldObserverSourceInterpreter extends SourceInterpreter {
    private static final int MAX_TRACKED_SOURCES = 8;

    private final Set<AbstractInsnNode> unknownSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final SourceValue unknownOneSlot;
    private final SourceValue unknownTwoSlot;

    FieldObserverSourceInterpreter() {
        super(Opcodes.ASM9);
        unknownOneSlot = newUnknown(1);
        unknownTwoSlot = newUnknown(2);
    }

    @Override
    public SourceValue newParameterValue(
            boolean isInstanceMethod,
            int local,
            Type type) {
        return unknown(type);
    }

    @Override
    public SourceValue newExceptionValue(
            TryCatchBlockNode tryCatchBlockNode,
            Frame<SourceValue> handlerFrame,
            Type exceptionType) {
        return unknown(exceptionType);
    }

    @Override
    public SourceValue merge(SourceValue first, SourceValue second) {
        int size = Math.min(first.size, second.size);
        if (first.insns.isEmpty() && second.insns.isEmpty()) {
            return size == first.size ? first : new SourceValue(size);
        }
        if (first.insns.isEmpty() || second.insns.isEmpty()
                || first.insns.size() > MAX_TRACKED_SOURCES
                || second.insns.size() > MAX_TRACKED_SOURCES
                || containsUnknown(first)
                || containsUnknown(second)) {
            return unknown(size);
        }
        if (size == first.size && first.insns.containsAll(second.insns)) {
            return first;
        }
        if (size == second.size && second.insns.containsAll(first.insns)) {
            return second;
        }

        LinkedHashSet<AbstractInsnNode> sources = new LinkedHashSet<>();
        if (!addBounded(sources, first.insns) || !addBounded(sources, second.insns)) {
            return unknown(size);
        }
        return new SourceValue(size, Collections.unmodifiableSet(sources));
    }

    boolean isUnknownSource(AbstractInsnNode instruction) {
        return unknownSources.contains(instruction);
    }

    private SourceValue unknown(Type type) {
        return unknown(type == null ? 1 : type.getSize());
    }

    private SourceValue unknown(int size) {
        return size == 2 ? unknownTwoSlot : unknownOneSlot;
    }

    private SourceValue newUnknown(int size) {
        AbstractInsnNode marker = new InsnNode(Opcodes.NOP);
        unknownSources.add(marker);
        return new SourceValue(size, marker);
    }

    private boolean containsUnknown(SourceValue value) {
        for (AbstractInsnNode source : value.insns) {
            if (unknownSources.contains(source)) {
                return true;
            }
        }
        return false;
    }

    private boolean addBounded(
            Set<AbstractInsnNode> destination,
            Set<AbstractInsnNode> sources) {
        for (AbstractInsnNode source : sources) {
            destination.add(source);
            if (destination.size() > MAX_TRACKED_SOURCES) {
                return false;
            }
        }
        return true;
    }
}
