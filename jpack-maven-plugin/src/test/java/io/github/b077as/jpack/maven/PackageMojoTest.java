package io.github.b077as.jpack.maven;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackageMojoTest {

    @Test
    void theValueInThePomWinsOverTheFileAndTheFileOverTheFallback() {
        assertEquals("pom", PackageMojo.first("pom", "file", "fallback"));
        assertEquals("file", PackageMojo.first(null, "file", "fallback"));
        assertEquals("fallback", PackageMojo.first(null, null, "fallback"));
        assertEquals(false, PackageMojo.first(false, true, true));
    }

    /** Maven passes an empty list, not null, for a list parameter nobody set: jpack.json must still be heard. */
    @Test
    void anEmptyListOrMapFromMavenDoesNotHideTheFile() {
        assertEquals(List.of("app", "launcher"), PackageMojo.first(List.<String>of(), List.of("app", "launcher"), List.<String>of()));
        assertEquals(List.of("-Xmx1g"), PackageMojo.first(List.<String>of(), List.of("-Xmx1g"), List.<String>of()));
        assertEquals(Map.of("A", "b"), PackageMojo.first(Map.<String, String>of(), Map.of("A", "b"), Map.<String, String>of()));
        assertEquals(List.of("from-pom"), PackageMojo.first(List.of("from-pom"), List.of("from-file"), List.<String>of()));
        assertEquals(List.of(), PackageMojo.first(List.<String>of(), List.<String>of(), List.<String>of()));
    }
}
