package xyz.melodysky.toolchain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import xyz.melodysky.packaging.NativeRegistrationPlan;

public record NativeImplementationPlan(List<NativeMethodImplementation> implementations) {
    public NativeImplementationPlan {
        implementations = implementations.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public NativeRegistrationPlan registrationPlan() {
        return new NativeRegistrationPlan(implementations.stream()
                .map(NativeMethodImplementation::entry)
                .toList());
    }

    public Optional<NativeMethodImplementation> implementationFor(String methodKey) {
        return implementations.stream()
                .filter(implementation -> implementation.methodKey().equals(methodKey))
                .findFirst();
    }

    public List<NativeMethodImplementation> llvmImplementations() {
        return implementations.stream()
                .filter(implementation -> implementation.path() == NativeImplementationPath.LLVM_NATIVE_PATH)
                .toList();
    }
}
