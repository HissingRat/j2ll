package xyz.melodysky.toolchain;

import java.util.List;
import java.util.Objects;
import xyz.melodysky.toolchain.nativetext.NativeTextBuildKey;
import xyz.melodysky.toolchain.nativetext.NativeTextCEmitter;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoder;
import xyz.melodysky.toolchain.nativetext.NativeTextEncoding;
import xyz.melodysky.toolchain.nativetext.NativeTextPurpose;

/**
 * Build-local cold leaves for the closed set of low-sensitivity registration
 * failure diagnostics.
 *
 * <p>The leaves accept only {@code JNIEnv*}; they cannot become metadata
 * resolvers, decoder fanout, or a plaintext cache. Each leaf owns one
 * independently derived ciphertext and activation-local scratch.</p>
 */
final class HostNativeRegistrationFailureLeafSource {
    private final NativeTextCEmitter textEmitter = new NativeTextCEmitter();

    Plan plan(NativeTextBuildKey buildKey) {
        Objects.requireNonNull(buildKey, "buildKey");
        NativeTextEncoder encoder = new NativeTextEncoder();
        return new Plan(
                leaf(
                        encoder,
                        buildKey,
                        "owner-rollback",
                        "native owner registration rollback failed"),
                leaf(
                        encoder,
                        buildKey,
                        "owner-exception-restore",
                        "native owner registration exception restore failed"),
                leaf(
                        encoder,
                        buildKey,
                        "aggregate-rollback",
                        "native registration rollback failed"),
                leaf(
                        encoder,
                        buildKey,
                        "aggregate-exception-restore",
                        "native registration exception restore failed"));
    }

    String emit(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        StringBuilder source = new StringBuilder();
        for (Leaf leaf : plan.leaves()) {
            source.append(textEmitter.ciphertextDeclaration(leaf.text()));
        }
        source.append('\n');
        for (Leaf leaf : plan.leaves()) {
            appendLeaf(source, leaf);
        }
        return source.toString();
    }

    private Leaf leaf(
            NativeTextEncoder encoder,
            NativeTextBuildKey buildKey,
            String identity,
            String plaintext) {
        NativeTextEncoding encoding = encoder.encode(
                buildKey,
                NativeTextPurpose.REGISTRATION_ERROR,
                "registration-cold-leaf:" + identity,
                plaintext);
        return new Leaf(
                "j2ll_registration_failure_"
                        + CIdentifier.forIdentity(encoding.symbol()),
                encoding);
    }

    private void appendLeaf(StringBuilder source, Leaf leaf) {
        source.append("static void ")
                .append(leaf.symbol())
                .append("(JNIEnv* env) __attribute__((noinline, cold));\n")
                .append("static void ")
                .append(leaf.symbol())
                .append("(JNIEnv* env) {\n")
                .append("    char message[sizeof(")
                .append(leaf.text().symbol())
                .append("_cipher)];\n")
                .append(textEmitter.decodeInto(
                        leaf.text(),
                        "message",
                        "    "))
                .append("    (*env)->FatalError(env, message);\n")
                .append("    j2ll_native_text_zero(message, sizeof(message));\n")
                .append("}\n\n");
    }

    record Plan(
            Leaf ownerRollback,
            Leaf ownerExceptionRestore,
            Leaf aggregateRollback,
            Leaf aggregateExceptionRestore) {
        List<Leaf> leaves() {
            return List.of(
                    ownerRollback,
                    ownerExceptionRestore,
                    aggregateRollback,
                    aggregateExceptionRestore);
        }
    }

    record Leaf(
            String symbol,
            NativeTextEncoding text) {}
}
