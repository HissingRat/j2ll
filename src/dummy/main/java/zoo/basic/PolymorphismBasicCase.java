package zoo.basic;

import zoo.Case;

public final class PolymorphismBasicCase implements Case {
    @Override
    public String name() {
        return "PolymorphismBasicCase";
    }

    @Override
    public String run() {
        return virtualDispatch() + ":" + abstractDispatch() + ":" + superDispatch() + ":" + bridgeDispatch();
    }

    public static String virtualDispatch() {
        Animal first = new Cat();
        Animal second = new Dog();
        return first.sound() + second.sound() + first.kind();
    }

    public static String abstractDispatch() {
        Shape shape = new Square(4);
        return shape.name() + shape.area();
    }

    public static String superDispatch() {
        return new LoudCat().parentSound();
    }

    public static String bridgeDispatch() {
        GenericBox<String> box = new StringBox();
        return box.value();
    }

    private abstract static class Animal {
        String kind() {
            return "animal";
        }

        abstract String sound();
    }

    private static final class Cat extends Animal {
        @Override
        String sound() {
            return "meow";
        }
    }

    private static final class Dog extends Animal {
        @Override
        String sound() {
            return "woof";
        }
    }

    private static final class LoudCat extends CatParent {
        @Override
        String sound() {
            return "LOUD";
        }

        String parentSound() {
            return super.sound() + "!";
        }
    }

    private static class CatParent {
        String sound() {
            return "parent";
        }
    }

    private abstract static class Shape {
        abstract int area();

        String name() {
            return "shape";
        }
    }

    private static final class Square extends Shape {
        private final int side;

        private Square(int side) {
            this.side = side;
        }

        @Override
        int area() {
            return side * side;
        }
    }

    private static class GenericBox<T> {
        T value() {
            return null;
        }
    }

    private static final class StringBox extends GenericBox<String> {
        @Override
        String value() {
            return "bridge";
        }
    }
}
