package xyz.melodysky.testsupport.dummy;

import java.util.ArrayList;
import java.util.List;

/** Exact supported, unsupported, and ineligible method matrix exercised by Dummy E2E profiles. */
public final class DummyMethodMatrix {
    private DummyMethodMatrix() {}

    public static List<DummyMethodExpectation> basicExpectations() {
        ArrayList<String> selectors = new ArrayList<>(basicNativeSelectors());
        selectors.addAll(List.of(
                "zoo/basic/NumericConversionBasicCase#booleanMath!(BSC)Z",
                "zoo/basic/NumericConversionBasicCase#byteMath!(BB)B",
                "zoo/basic/NumericConversionBasicCase#shortMath!(SS)S",
                "zoo/basic/NumericConversionBasicCase#charMath!(CI)C",
                "zoo/basic/NumericConversionBasicCase#intMath!(II)I",
                "zoo/basic/NumericConversionBasicCase#longMath!(JJ)J",
                "zoo/basic/NumericConversionBasicCase#floatMath!(IJ)F",
                "zoo/basic/NumericConversionBasicCase#doubleMath!(FI)D",
                "zoo/basic/PrimitiveBasicCase#divZeroCode!()I",
                "zoo/basic/ArrayEdgeBasicCase#flip!([Z)Z",
                "zoo/basic/ArrayEdgeBasicCase#sum!([I[J)J",
                "zoo/basic/ArrayEdgeBasicCase#exceptionCodes!([I[Ljava/lang/Object;)Ljava/lang/String;",
                "zoo/basic/ReferenceIdentityBasicCase#<init>!()V",
                "zoo/basic/ReferenceIdentityBasicCase#<clinit>!()V",
                "zoo/basic/ReferenceIdentityBasicCase#checkedPayload!(Ljava/lang/Object;)Lzoo/basic/ReferenceIdentityBasicCase$Payload;",
                "zoo/basic/ReferenceIdentityBasicCase#same!(Ljava/lang/Object;Ljava/lang/Object;)Z",
                "zoo/basic/ReferenceIdentityBasicCase#different!(Ljava/lang/Object;Ljava/lang/Object;)Z",
                "zoo/basic/ExceptionBasicCase#singleExitFinally!()I",
                "zoo/basic/ObjectBasicCase#<init>!(IILjava/lang/String;)V",
                "zoo/basic/ObjectBasicCase#<clinit>!()V",
                "zoo/basic/ObjectBasicCase#sum!()I",
                "zoo/basic/InterfaceLambdaConcatBasicCase$MathOp#identity!(I)I",
                "zoo/basic/PolymorphismBasicCase$StringBox#value!()Ljava/lang/Object;",
                "zoo/basic/ReflectionFieldBasicCase#mutatePrimitives!(Lzoo/basic/ReflectionFieldBasicCase$Target;)Ljava/lang/String;",
                "zoo/basic/ReflectionFieldBasicCase#mutateReferences!(Lzoo/basic/ReflectionFieldBasicCase$Target;)Ljava/lang/String;",
                "zoo/basic/MonitorExceptionBasicCase#synchronizedIncrement!(I)V",
                "zoo/basic/MonitorExceptionBasicCase#synchronizedThrowAndRecover!()Ljava/lang/String;",
                "zoo/basic/MonitorExceptionBasicCase#catchAllCode!(Z)I",
                "zoo/basic/MonitorExceptionBasicCase#throwPassed!(Ljava/lang/RuntimeException;)V",
                "zoo/basic/MonitorExceptionBasicCase#sleepOnce!()V"));
        ArrayList<DummyMethodExpectation> expectations = new ArrayList<>(
                selectors.stream().map(DummyMethodExpectation::nativeLowered).toList());
        expectations.add(DummyMethodExpectation.skipped(
                "zoo/basic/ArrayEdgeBasicCase#makeMatrix!(II)[[I",
                "MULTIANEWARRAY_UNSUPPORTED"));
        return List.copyOf(expectations);
    }

    public static List<DummyMethodExpectation> advancedExpectations() {
        return List.of(
                nativeMethod("zoo/advanced/ReflectionAdvancedCase#run!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/MethodHandleAdvancedCase#methodHandleBoundary!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/UnsafeVarHandleAdvancedCase#run!()Ljava/lang/String;"),
                skipped("zoo/advanced/ThreadMonitorAdvancedCase#run!()Ljava/lang/String;", "THREAD_HELPER_UNSUPPORTED"),
                nativeMethod("zoo/advanced/InterfaceBoundaryAdvancedCase#run!()Ljava/lang/String;"),
                skipped("zoo/advanced/InterfaceBoundaryAdvancedCase$SuperCall#call!()Ljava/lang/String;", "UNSUPPORTED_DEFAULT_INTERFACE_SUPER"),
                nativeMethod("zoo/advanced/ComplexFinallyBoundaryCase#run!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/ComplexFinallyBoundaryCase#multiExitFinally!(I)I"),
                nativeMethod("zoo/advanced/ComplexFinallyBoundaryCase#nestedFinally!(Z)I"),
                nativeMethod("zoo/advanced/ComplexFinallyBoundaryCase#monitorFinally!()I"),
                nativeMethod("zoo/advanced/AnnotationEnumRecordAdvancedCase#run!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/ConstructorBoundaryAdvancedCase$GuardedBox#value!()I"),
                skipped("zoo/advanced/JdkSurfaceAdvancedCase#nioSmoke!()Ljava/lang/String;", "JVM_HELPER_UNSUPPORTED"),
                nativeMethod("zoo/advanced/JdkSurfaceAdvancedCase#resourceBundle!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/JdkSurfaceAdvancedCase#localeFormat!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/JdkSurfaceAdvancedCase#moduleApi!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/MethodHandleBoundaryMatrixCase#chainAdapter!()Ljava/lang/String;"),
                nativeMethod("zoo/advanced/MethodHandleBoundaryMatrixCase#permuteAdapter!()I"),
                nativeMethod("zoo/advanced/MethodHandleBoundaryMatrixCase#filterAdapter!()I"),
                nativeMethod("zoo/advanced/MethodHandleBoundaryMatrixCase#foldAdapter!()I"),
                nativeMethod("zoo/advanced/MethodHandleBoundaryMatrixCase#collectorAdapter!()I"),
                skipped("zoo/advanced/ThreadBoundaryMatrixCase#constructOnly!(Ljava/lang/Runnable;)Ljava/lang/Thread;", "THREAD_HELPER_UNSUPPORTED"),
                skipped("zoo/advanced/ThreadBoundaryMatrixCase#startOnly!(Ljava/lang/Thread;)V", "THREAD_HELPER_UNSUPPORTED"),
                skipped("zoo/advanced/ThreadBoundaryMatrixCase#joinOnly!(Ljava/lang/Thread;)V", "THREAD_HELPER_UNSUPPORTED"),
                skipped("zoo/advanced/ThreadBoundaryMatrixCase#waitOnly!(Ljava/lang/Object;)V", "WAIT_NOTIFY_UNSUPPORTED"),
                skipped("zoo/advanced/ThreadBoundaryMatrixCase#notifyOnly!(Ljava/lang/Object;)V", "WAIT_NOTIFY_UNSUPPORTED"),
                skipped("zoo/advanced/AltMetafactoryBoundaryCase#serializableLambda!(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", "ALT_METAFACTORY_UNSUPPORTED"),
                nativeMethod("zoo/advanced/AltMetafactoryBoundaryCase#supportedRunnableLambda!(Ljava/lang/String;)Ljava/lang/String;"),
                skipped("zoo/advanced/VarHandleBoundaryMatrixCase#getAndAdd!(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;I)I", "VAR_HANDLE_DYNAMIC_UNSUPPORTED"),
                skipped("zoo/advanced/UnsafeRawMemoryBoundaryCase#rawRoundTrip!(J)J", "UNSAFE_RAW_MEMORY_UNSUPPORTED"),
                skipped("zoo/advanced/ConstructorBoundaryAdvancedCase$GuardedBox#<init>!(I)V", "NATIVE_IMPLEMENTATION_UNAVAILABLE"),
                skipped("zoo/advanced/LanguageSurfaceAdvancedCase#multiArray!()Ljava/lang/String;", "MULTIANEWARRAY_UNSUPPORTED"),
                skipped("zoo/versioned/VersionedFeature#value!()Ljava/lang/String;", "MULTI_RELEASE_VERSIONED_CLASS"),
                ineligible("zoo/advanced/EligibilityAdvancedCase$AbstractBoundary#abstractValue!()I", "ABSTRACT_METHOD"),
                ineligible("zoo/advanced/EligibilityAdvancedCase$InterfaceBoundary#interfaceValue!()I", "ABSTRACT_METHOD"),
                ineligible("zoo/advanced/EligibilityAdvancedCase$AnnotationBoundary#number!()I", "ABSTRACT_METHOD"),
                ineligible("zoo/advanced/EligibilityAdvancedCase$NativeBoundary#nativeValue!(I)I", "ALREADY_NATIVE"),
                ineligible("zoo/Case#run!()Ljava/lang/String;", "ABSTRACT_METHOD"),
                ineligible("zoo/services/ZooService#message!()Ljava/lang/String;", "ABSTRACT_METHOD"));
    }

    public static List<DummyMethodExpectation> allExpectations() {
        ArrayList<DummyMethodExpectation> all = new ArrayList<>(basicExpectations());
        all.addAll(advancedExpectations());
        return List.copyOf(all);
    }

    public static List<String> selectors(List<DummyMethodExpectation> expectations) {
        return expectations.stream().map(DummyMethodExpectation::selector).toList();
    }

    private static DummyMethodExpectation nativeMethod(String selector) {
        return DummyMethodExpectation.nativeLowered(selector);
    }

    private static DummyMethodExpectation skipped(String selector, String reason) {
        return DummyMethodExpectation.skipped(selector, reason);
    }

    private static DummyMethodExpectation ineligible(String selector, String reason) {
        return DummyMethodExpectation.ineligible(selector, reason);
    }

    private static List<String> basicNativeSelectors() {
        return List.of(
                "zoo/basic/PrimitiveBasicCase#simpleInt!(II)I",
                "zoo/basic/PrimitiveBasicCase#longMath!(JJ)J",
                "zoo/basic/PrimitiveBasicCase#lessThan!(II)Z",
                "zoo/basic/PrimitiveBasicCase#floatValue!()F",
                "zoo/basic/PrimitiveBasicCase#doubleValue!()D",
                "zoo/basic/ArrayBasicCase#run!()Ljava/lang/String;",
                "zoo/basic/ControlFlowBasicCase#negate!(I)I",
                "zoo/basic/ControlFlowBasicCase#table!(I)I",
                "zoo/basic/ControlFlowBasicCase#lookup!(I)I",
                "zoo/basic/ControlFlowBasicCase#regionAroundOwnedBoundary!(I[Ljava/lang/String;)Ljava/lang/String;",
                "zoo/basic/ControlFlowBasicCase#regionAroundTypedCatch!(II)I",
                "zoo/basic/ExceptionBasicCase#catchCode!()I",
                "zoo/basic/StringJdkBasicCase#stableStringOps!()Ljava/lang/String;",
                "zoo/basic/StringJdkBasicCase#bigEndianIntFrame!(I)[B",
                "zoo/basic/InterfaceLambdaConcatBasicCase#run!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#virtualDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#abstractDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#superDispatch!()Ljava/lang/String;",
                "zoo/basic/PolymorphismBasicCase#bridgeDispatch!()Ljava/lang/String;",
                "zoo/basic/ReflectionBasicCase#run!()Ljava/lang/String;");
    }
}
