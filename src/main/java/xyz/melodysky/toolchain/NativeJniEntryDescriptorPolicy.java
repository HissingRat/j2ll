package xyz.melodysky.toolchain;

import xyz.melodysky.runtime.jni.JniTypeMapper;

/** Descriptor closed set for JVM-to-LLVM proxy entry points. */
final class NativeJniEntryDescriptorPolicy {
    private static final JniTypeMapper TYPE_MAPPER = new JniTypeMapper();

    private NativeJniEntryDescriptorPolicy() {}

    static boolean supports(String descriptor) {
        return TYPE_MAPPER.parameterDescriptors(descriptor).stream()
                        .allMatch(NativeJniEntryDescriptorPolicy
                                ::supportsValue)
                && (TYPE_MAPPER.returnDescriptor(descriptor).equals("V")
                        || supportsValue(
                                TYPE_MAPPER.returnDescriptor(descriptor)));
    }

    private static boolean supportsValue(String descriptor) {
        return descriptor.equals("I")
                || descriptor.equals("J")
                || descriptor.equals("F")
                || descriptor.equals("D");
    }
}
