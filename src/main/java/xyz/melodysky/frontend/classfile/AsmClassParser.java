package xyz.melodysky.frontend.classfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import xyz.melodysky.diagnostic.Diagnostic;
import xyz.melodysky.diagnostic.DiagnosticLocation;
import xyz.melodysky.diagnostic.DiagnosticStage;
import xyz.melodysky.jvm.AccessFlags;
import xyz.melodysky.pipeline.StageResult;

public final class AsmClassParser {
    public StageResult<ParsedClass> parse(ClassFileEntry entry) {
        try {
            ClassReader reader = new ClassReader(entry.bytes());
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, ClassReader.EXPAND_FRAMES);
            ParsedClass parsedClass = toParsedClass(entry, classNode);
            return StageResult.complete(DiagnosticStage.PARSE, parsedClass);
        } catch (RuntimeException exception) {
            Diagnostic diagnostic = Diagnostic.error(
                            DiagnosticStage.PARSE,
                            ClassParseDiagnostics.CLASS_PARSE_FAILED,
                            "failed to parse class entry " + entry.entryName() + ": " + exception.getMessage())
                    .at(DiagnosticLocation.classLocation(entry.entryName()));
            return StageResult.failed(DiagnosticStage.PARSE, List.of(diagnostic));
        }
    }

    public StageResult<ClassParseResult> parseAll(ClassFileSource source) {
        List<ClassFileEntry> entries;
        try {
            entries = source.entries();
        } catch (IOException | RuntimeException exception) {
            Diagnostic diagnostic = Diagnostic.error(
                    DiagnosticStage.INPUT_DISCOVERY,
                    ClassParseDiagnostics.CLASS_SOURCE_READ_FAILED,
                    "failed to read class source " + source.description() + ": " + exception.getMessage());
            return StageResult.failed(DiagnosticStage.INPUT_DISCOVERY, List.of(diagnostic));
        }

        ArrayList<ParsedClass> parsedClasses = new ArrayList<>();
        ArrayList<Diagnostic> diagnostics = new ArrayList<>();
        for (ClassFileEntry entry : entries) {
            StageResult<ParsedClass> classResult = parse(entry);
            diagnostics.addAll(classResult.diagnostics());
            classResult.artifact().ifPresent(parsedClasses::add);
        }

        ParsedProgram program = new ParsedProgram(parsedClasses);
        StageResult<ClassParseResult> result = diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity().wireName().equals("error"))
                ? StageResult.failed(DiagnosticStage.PARSE, diagnostics)
                : StageResult.complete(DiagnosticStage.PARSE, new ClassParseResult(program), diagnostics);
        return result;
    }

    private ParsedClass toParsedClass(ClassFileEntry entry, ClassNode classNode) {
        String owner = classNode.name;
        ArrayList<ParsedField> fields = new ArrayList<>();
        for (FieldNode field : classNode.fields) {
            fields.add(new ParsedField(owner, field.name, field.desc, new AccessFlags(field.access), field.signature));
        }

        ArrayList<ParsedMethod> methods = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            methods.add(toParsedMethod(owner, fields, method));
        }

        return new ParsedClass(
                owner,
                new AccessFlags(classNode.access),
                classNode.version & 0xFFFF,
                classNode.version >>> 16,
                classNode.superName,
                classNode.interfaces,
                fields,
                methods,
                entry.entryName(),
                entry.sourceDescription(),
                classNode);
    }

    private ParsedMethod toParsedMethod(String owner, List<ParsedField> fields, MethodNode method) {
        ArrayList<ParsedExceptionHandler> handlers = new ArrayList<>();
        for (TryCatchBlockNode handler : method.tryCatchBlocks) {
            handlers.add(new ParsedExceptionHandler(handler.type));
        }

        AccessFlags accessFlags = new AccessFlags(method.access);
        boolean hasCode = !accessFlags.isAbstract()
                && !accessFlags.isNative()
                && method.instructions != null
                && method.instructions.size() > 0;

        return new ParsedMethod(
                owner,
                method.name,
                method.desc,
                accessFlags,
                fields,
                method.exceptions,
                handlers,
                hasCode,
                method.maxLocals,
                method.maxStack,
                method);
    }
}
