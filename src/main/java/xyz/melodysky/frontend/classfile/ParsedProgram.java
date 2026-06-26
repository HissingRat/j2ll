package xyz.melodysky.frontend.classfile;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ParsedProgram(List<ParsedClass> classes) {
    public ParsedProgram {
        classes = classes.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ParsedClass::internalName))
                .toList();
    }

    public Optional<ParsedClass> findClass(String internalName) {
        return classes.stream()
                .filter(parsedClass -> parsedClass.internalName().equals(internalName))
                .findFirst();
    }
}
