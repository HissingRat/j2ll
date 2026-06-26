package xyz.melodysky.frontend.classfile;

import java.io.IOException;
import java.util.List;

public interface ClassFileSource {
    String description();

    List<ClassFileEntry> entries() throws IOException;
}
