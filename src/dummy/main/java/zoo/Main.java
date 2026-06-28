package zoo;

import java.util.ArrayList;
import java.util.List;
import zoo.advanced.AnnotationEnumRecordAdvancedCase;
import zoo.advanced.ComplexFinallyBoundaryCase;
import zoo.advanced.InterfaceBoundaryAdvancedCase;
import zoo.advanced.MethodHandleAdvancedCase;
import zoo.advanced.ReflectionAdvancedCase;
import zoo.advanced.ThreadMonitorAdvancedCase;
import zoo.advanced.UnsafeVarHandleAdvancedCase;
import zoo.basic.ArrayBasicCase;
import zoo.basic.ControlFlowBasicCase;
import zoo.basic.ExceptionBasicCase;
import zoo.basic.InterfaceLambdaConcatBasicCase;
import zoo.basic.ObjectBasicCase;
import zoo.basic.PackagingBasicCase;
import zoo.basic.PrimitiveBasicCase;
import zoo.basic.ReflectionBasicCase;
import zoo.basic.StringJdkBasicCase;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        String mode = args.length == 0 ? "all" : args[0];
        boolean ok = true;
        if (mode.equals("basic") || mode.equals("all")) {
            ok &= runGroup("basic", basicCases());
        }
        if (mode.equals("advanced") || mode.equals("all")) {
            ok &= runGroup("advanced", advancedCases());
        }
        if (!mode.equals("basic") && !mode.equals("advanced") && !mode.equals("all")) {
            System.out.println("ARGUMENT=FAIL:IllegalArgumentException:mode");
            ok = false;
        }
        if (ok) {
            System.out.println("ALL_OK");
        } else {
            System.exit(1);
        }
    }

    private static List<Case> basicCases() {
        ArrayList<Case> cases = new ArrayList<>();
        cases.add(new PrimitiveBasicCase());
        cases.add(new ArrayBasicCase());
        cases.add(new ControlFlowBasicCase());
        cases.add(new ExceptionBasicCase());
        cases.add(new ObjectBasicCase());
        cases.add(new StringJdkBasicCase());
        cases.add(new InterfaceLambdaConcatBasicCase());
        cases.add(new ReflectionBasicCase());
        cases.add(new PackagingBasicCase());
        return cases;
    }

    private static List<Case> advancedCases() {
        ArrayList<Case> cases = new ArrayList<>();
        cases.add(new ReflectionAdvancedCase());
        cases.add(new MethodHandleAdvancedCase());
        cases.add(new UnsafeVarHandleAdvancedCase());
        cases.add(new ThreadMonitorAdvancedCase());
        cases.add(new InterfaceBoundaryAdvancedCase());
        cases.add(new ComplexFinallyBoundaryCase());
        cases.add(new AnnotationEnumRecordAdvancedCase());
        return cases;
    }

    private static boolean runGroup(String name, List<Case> cases) {
        boolean ok = true;
        System.out.println("GROUP " + name + " START");
        for (Case testCase : cases) {
            try {
                System.out.println(testCase.name() + "=OK:" + testCase.run());
            } catch (Throwable throwable) {
                ok = false;
                System.out.println(testCase.name() + "=FAIL:"
                        + throwable.getClass().getName() + ":" + stableMessage(throwable));
            }
        }
        System.out.println("GROUP " + name + (ok ? " OK" : " FAIL"));
        return ok;
    }

    private static String stableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return "no-message";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
