package zoo.basic;

import zoo.Case;

public final class ObjectBasicCase implements Case {
    private static int initCounter;
    private static volatile int volatileCounter;

    static {
        initCounter = 41;
    }

    private final int left;
    private final int right;
    private final String label;
    private ObjectBasicCase peer;

    public ObjectBasicCase() {
        this(5, 8, "root");
    }

    public ObjectBasicCase(int left, int right, String label) {
        this.left = left;
        this.right = right;
        this.label = label;
    }

    @Override
    public String name() {
        return "ObjectBasicCase";
    }

    @Override
    public String run() {
        ObjectBasicCase root = new ObjectBasicCase();
        root.peer = new ObjectBasicCase(13, 21, "peer");
        volatileCounter = root.peer.right;
        return initCounter + ":" + root.sum() + ":" + root.peer.sum() + ":" + volatileCounter + ":" + root.label;
    }

    private int sum() {
        return left + right;
    }
}
