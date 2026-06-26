package xyz.melodysky.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SelectorParserTest {
    private final SelectorParser parser = new SelectorParser();

    @Test
    void parsesExactMethodSelector() {
        Selector selector = parser.parse("pkg/Foo#add!(II)I");

        assertTrue(selector.isMethodSelector());
        assertTrue(selector.matchesClass("pkg/Foo"));
        assertFalse(selector.matchesClass("pkg/Other"));
    }

    @Test
    void rejectsMethodSelectorWithoutDescriptor() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("pkg/Foo#add"));
    }

    @Test
    void supportsDocumentedClassWildcards() {
        assertTrue(parser.parse("pkg/*").matchesClass("pkg/Foo"));
        assertFalse(parser.parse("pkg/*").matchesClass("pkg/nested/Foo"));
        assertTrue(parser.parse("pkg/**").matchesClass("pkg/nested/Foo"));
        assertTrue(parser.parse("pkg/**/Foo").matchesClass("pkg/Foo"));
        assertTrue(parser.parse("pkg/**/Foo").matchesClass("pkg/a/b/Foo"));
        assertTrue(parser.parse("pkg/Class*").matchesClass("pkg/ClassImpl"));
        assertFalse(parser.parse("pkg/Class*").matchesClass("pkg/OtherClass"));
    }

    @Test
    void rejectsDoubleStarInsideSegment() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("pkg/Foo**"));
    }
}
