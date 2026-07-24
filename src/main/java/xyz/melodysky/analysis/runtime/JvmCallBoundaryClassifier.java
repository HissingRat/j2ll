package xyz.melodysky.analysis.runtime;

/** Names JVM-owned call families that must retain helper or fallback semantics. */
final class JvmCallBoundaryClassifier {
    boolean isJdkCollectionCall(String target) {
        return target.contains("java/util/ArrayList#")
                || target.contains("java/util/HashMap#")
                || target.contains("java/util/Arrays#")
                || target.contains("java/util/Collections#")
                || target.contains("java/util/Optional#")
                || target.contains("java/lang/String#format");
    }

    boolean isThrowableCall(String target) {
        return target.contains("java/lang/Throwable#")
                || target.contains("java/lang/RuntimeException#")
                || target.contains("java/lang/IllegalArgumentException#");
    }

    boolean isThreadCall(String target) {
        return target.contains("java/lang/Thread#<init>!")
                || target.contains("java/lang/Thread#start!")
                || target.contains("java/lang/Thread#join!");
    }

    boolean isWaitNotifyCall(String target) {
        return target.contains("java/lang/Object#wait!")
                || target.contains("java/lang/Object#notify!");
    }
}
