package zoo.advanced;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import zoo.Case;

public final class EligibilityAdvancedCase implements Case {
    @Override
    public String name() {
        return "EligibilityAdvancedCase";
    }

    @Override
    public String run() throws Exception {
        Method abstractMethod = AbstractBoundary.class.getDeclaredMethod("abstractValue");
        Method interfaceMethod = InterfaceBoundary.class.getDeclaredMethod("interfaceValue");
        Method annotationElement = AnnotationBoundary.class.getDeclaredMethod("number");
        Method nativeMethod = NativeBoundary.class.getDeclaredMethod("nativeValue", int.class);

        return Modifier.isAbstract(abstractMethod.getModifiers()) + ":"
                + Modifier.isAbstract(interfaceMethod.getModifiers()) + ":"
                + annotationElement.getDefaultValue() + ":"
                + Modifier.isNative(nativeMethod.getModifiers());
    }

    public abstract static class AbstractBoundary {
        public abstract int abstractValue();
    }

    public interface InterfaceBoundary {
        int interfaceValue();
    }

    public @interface AnnotationBoundary {
        int number() default 7;
    }

    public static final class NativeBoundary {
        private NativeBoundary() {
        }

        public static native int nativeValue(int value);
    }
}
