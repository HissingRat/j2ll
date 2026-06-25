package xyz.melodysky.frontend.jar;

import org.junit.jupiter.api.Test;
import xyz.melodysky.frontend.bytecode.ClassIrBuilder;
import xyz.melodysky.ir.model.IrProgram;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrontendSkipReportTest {

    @Test
    public void testRendersTextAndJsonWithCategories() {
        FrontendSkipReport report = FrontendSkipReport.from(new JarIrBuilder.BuildResult(
                new IrProgram(List.of()),
                List.of(
                        new JarIrBuilder.ClassBuildResult(
                                "sample/Anno",
                                null,
                                List.of(new ClassIrBuilder.SkippedMethod(
                                        "value",
                                        "()Ljava/lang/String;",
                                        "annotation classes are not native-lowered yet"
                                ))
                        ),
                        new JarIrBuilder.ClassBuildResult(
                                "sample/Dynamic",
                                null,
                                List.of(new ClassIrBuilder.SkippedMethod(
                                        "make",
                                        "()Ljava/lang/String;",
                                        "invokedynamic lowering is not implemented yet"
                                ))
                        )
                )
        ));

        assertFalse(report.isEmpty());
        String text = report.toText();
        assertTrue(text.contains("sample/Anno\n  - value()Ljava/lang/String; :: annotation classes are not native-lowered yet\n"));
        assertTrue(text.contains("sample/Dynamic\n  - make()Ljava/lang/String; :: invokedynamic lowering is not implemented yet\n"));

        String json = report.toJson();
        assertTrue(json.contains("\"totalSkips\": 2"));
        assertTrue(json.contains("\"annotation-class\": 1"));
        assertTrue(json.contains("\"invokedynamic\": 1"));
        assertTrue(json.contains("\"className\": \"sample/Anno\""));
        assertTrue(json.contains("\"methodName\": \"make\""));
    }
}
