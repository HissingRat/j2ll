package xyz.melodysky.toolchain;

import java.util.List;
import xyz.melodysky.ir.model.NativeFieldSlotRef;
import xyz.melodysky.packaging.LoaderClassValueSidecarInjector;
import xyz.melodysky.packaging.RuntimeLoaderPlan;

/**
 * JNI bridge from LLVM or encoded-fallback reference slots to the generated
 * Loader's {@code ClassValue<Object[]>}. Values never leave the JVM heap.
 */
final class HostNativeReferenceFieldStorageSource {
    private static final String NATIVE_SLOT_PREFIX = "native-slot:";

    private HostNativeReferenceFieldStorageSource() {
    }

    static int requiredSidecarSize(List<HostJniCSourceGenerator.Binding> bindings) {
        return referenceSlots(bindings).stream()
                .mapToInt(slot -> slot.referenceIndex() + 1)
                .max()
                .orElse(0);
    }

    static void append(
            StringBuilder builder,
            List<HostJniCSourceGenerator.Binding> bindings,
            RuntimeLoaderPlan loaderPlan) {
        int requiredSize = requiredSidecarSize(bindings);
        if (requiredSize == 0) {
            return;
        }
        if (loaderPlan.referenceSidecarSize() < requiredSize) {
            throw new IllegalArgumentException(
                    "runtime Loader reference sidecar is smaller than the native field plan");
        }
        builder.append("""
                static jobject j2ll_nfs_reference_sidecar(
                        JNIEnv* env, jclass defining_class) {
                    if (defining_class == NULL) {
                        j2ll_throw_new(env, "java/lang/IllegalStateException",
                                "reference field sidecar requires defining class");
                        return NULL;
                    }
                    jclass loader = (*env)->FindClass(env, "@LOADER_INTERNAL_NAME@");
                    if (loader == NULL) {
                        return NULL;
                    }
                    jmethodID accessor = (*env)->GetStaticMethodID(
                            env,
                            loader,
                            "@ACCESSOR_NAME@",
                            "@ACCESSOR_DESCRIPTOR@");
                    if (accessor == NULL) {
                        (*env)->DeleteLocalRef(env, loader);
                        return NULL;
                    }
                    jobjectArray sidecar = (jobjectArray)(*env)->CallStaticObjectMethod(
                            env, loader, accessor, defining_class);
                    (*env)->DeleteLocalRef(env, loader);
                    return sidecar;
                }

                jobject j2ll_nfs_reference_sidecar_cached(
                        JNIEnv* env,
                        jclass defining_class,
                        jobject* cache_slot) {
                    if (cache_slot == NULL) {
                        j2ll_throw_new(env, "java/lang/IllegalStateException",
                                "reference field sidecar cache is missing");
                        return NULL;
                    }
                    if (*cache_slot != NULL) {
                        return *cache_slot;
                    }
                    if ((*env)->ExceptionCheck(env)) {
                        return NULL;
                    }
                    jobject sidecar = j2ll_nfs_reference_sidecar(env, defining_class);
                    if (sidecar != NULL) {
                        *cache_slot = sidecar;
                    }
                    return sidecar;
                }

                void j2ll_nfs_release_reference_sidecar(
                        JNIEnv* env, jobject* cache_slot) {
                    if (cache_slot != NULL && *cache_slot != NULL) {
                        (*env)->DeleteLocalRef(env, *cache_slot);
                        *cache_slot = NULL;
                    }
                }

                jobject j2ll_nfs_get_ref(
                        JNIEnv* env, jobjectArray sidecar, int32_t slot_index) {
                    if (sidecar == NULL || (*env)->ExceptionCheck(env)) {
                        return NULL;
                    }
                    return (*env)->GetObjectArrayElement(env, sidecar, (jsize)slot_index);
                }

                void j2ll_nfs_put_ref(
                        JNIEnv* env,
                        jobjectArray sidecar,
                        int32_t slot_index,
                        jobject value) {
                    if (sidecar == NULL || (*env)->ExceptionCheck(env)) {
                        return;
                    }
                    (*env)->SetObjectArrayElement(
                            env, sidecar, (jsize)slot_index, value);
                }

                """
                .replace(
                        "@LOADER_INTERNAL_NAME@",
                        CSourceEscaper.stringContents(loaderPlan.internalName()))
                .replace(
                        "@ACCESSOR_NAME@",
                        LoaderClassValueSidecarInjector.ACCESSOR_NAME)
                .replace(
                        "@ACCESSOR_DESCRIPTOR@",
                        LoaderClassValueSidecarInjector.ACCESSOR_DESCRIPTOR));
    }

    private static List<NativeFieldSlotRef> referenceSlots(
            List<HostJniCSourceGenerator.Binding> bindings) {
        return bindings.stream()
                .flatMap(binding -> binding.fieldKeys().stream())
                .filter(fieldKey -> fieldKey.startsWith(NATIVE_SLOT_PREFIX))
                .map(fieldKey -> fieldKey.substring(NATIVE_SLOT_PREFIX.length()))
                .map(NativeFieldSlotRef::parse)
                .flatMap(java.util.Optional::stream)
                .filter(slot -> slot.kind().reference())
                .distinct()
                .toList();
    }
}
