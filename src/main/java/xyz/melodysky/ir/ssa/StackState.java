package xyz.melodysky.ir.ssa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import xyz.melodysky.ir.model.IrValue;

public final class StackState {
    private final ArrayDeque<IrValue> values = new ArrayDeque<>();

    public StackState() {
    }

    public StackState(List<IrValue> bottomToTop) {
        for (IrValue value : bottomToTop) {
            push(value);
        }
    }

    public void push(IrValue value) {
        values.push(value);
    }

    public IrValue pop() {
        if (values.isEmpty()) {
            throw new IllegalStateException("operand stack underflow");
        }
        return values.pop();
    }

    public IrValue peek() {
        if (values.isEmpty()) {
            throw new IllegalStateException("operand stack underflow");
        }
        return values.peek();
    }

    public void applyPop() {
        IrValue value = pop();
        if (isCategory2(value)) {
            throw new UnsupportedOperationException("POP cannot consume a category-2 value");
        }
    }

    public void applyPop2() {
        IrValue first = pop();
        if (isCategory2(first)) {
            return;
        }
        IrValue second = pop();
        if (isCategory2(second)) {
            throw new UnsupportedOperationException("POP2 cannot consume category-1 over category-2");
        }
    }

    public void applyDup() {
        IrValue value = popCategory1("DUP");
        push(value);
        push(value);
    }

    public void applyDupX1() {
        IrValue value1 = popCategory1("DUP_X1");
        IrValue value2 = popCategory1("DUP_X1");
        push(value1);
        push(value2);
        push(value1);
    }

    public void applyDupX2() {
        IrValue value1 = popCategory1("DUP_X2");
        IrValue value2 = pop();
        if (isCategory2(value2)) {
            push(value1);
            push(value2);
            push(value1);
            return;
        }
        IrValue value3 = popCategory1("DUP_X2");
        push(value1);
        push(value3);
        push(value2);
        push(value1);
    }

    public void applyDup2() {
        IrValue value1 = pop();
        if (isCategory2(value1)) {
            push(value1);
            push(value1);
            return;
        }
        IrValue value2 = popCategory1("DUP2");
        push(value2);
        push(value1);
        push(value2);
        push(value1);
    }

    public void applyDup2X1() {
        IrValue value1 = pop();
        if (isCategory2(value1)) {
            IrValue value2 = popCategory1("DUP2_X1");
            push(value1);
            push(value2);
            push(value1);
            return;
        }
        IrValue value2 = ensureCategory1(pop(), "DUP2_X1");
        IrValue value3 = popCategory1("DUP2_X1");
        push(value2);
        push(value1);
        push(value3);
        push(value2);
        push(value1);
    }

    public void applyDup2X2() {
        IrValue value1 = pop();
        if (isCategory2(value1)) {
            IrValue value2 = pop();
            if (isCategory2(value2)) {
                push(value1);
                push(value2);
                push(value1);
                return;
            }
            IrValue value3 = popCategory1("DUP2_X2");
            push(value1);
            push(value3);
            push(value2);
            push(value1);
            return;
        }
        IrValue value2 = ensureCategory1(pop(), "DUP2_X2");
        IrValue value3 = pop();
        if (isCategory2(value3)) {
            push(value2);
            push(value1);
            push(value3);
            push(value2);
            push(value1);
            return;
        }
        IrValue value4 = popCategory1("DUP2_X2");
        push(value2);
        push(value1);
        push(value4);
        push(value3);
        push(value2);
        push(value1);
    }

    public void applySwap() {
        IrValue value1 = popCategory1("SWAP");
        IrValue value2 = popCategory1("SWAP");
        push(value1);
        push(value2);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public List<IrValue> snapshotBottomToTop() {
        ArrayList<IrValue> snapshot = new ArrayList<>();
        Iterator<IrValue> iterator = values.descendingIterator();
        while (iterator.hasNext()) {
            snapshot.add(iterator.next());
        }
        return List.copyOf(snapshot);
    }

    private IrValue popCategory1(String opcodeName) {
        return ensureCategory1(pop(), opcodeName);
    }

    private IrValue ensureCategory1(IrValue value, String opcodeName) {
        if (isCategory2(value)) {
            throw new UnsupportedOperationException(opcodeName + " shape is invalid for category-2 value");
        }
        return value;
    }

    private boolean isCategory2(IrValue value) {
        return switch (value.type()) {
            case I64, F64 -> true;
            default -> false;
        };
    }
}
