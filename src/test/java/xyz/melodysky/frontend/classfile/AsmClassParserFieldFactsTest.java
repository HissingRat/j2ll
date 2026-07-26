package xyz.melodysky.frontend.classfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

class AsmClassParserFieldFactsTest implements Opcodes {
    @Test
    void preservesConstantValueSignatureAndAnnotationFactsNeededByProgramAnalysis() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "pkg/FieldFacts", null, "java/lang/Object", null);
        FieldVisitor field = writer.visitField(
                ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
                "value",
                "I",
                "TI;",
                7);
        field.visitAnnotation("Lpkg/Marker;", false).visitEnd();
        field.visitEnd();
        writer.visitEnd();

        ParsedClass parsedClass = new AsmClassParser()
                .parse(new ClassFileEntry("pkg/FieldFacts.class", writer.toByteArray(), "fixture"))
                .artifact()
                .orElseThrow();
        ParsedField parsedField = parsedClass.fields().get(0);

        assertEquals(7, parsedField.constantValue());
        assertEquals("TI;", parsedField.signature());
        assertTrue(parsedField.hasConstantValue());
        assertTrue(parsedField.hasAnnotations());
    }
}
