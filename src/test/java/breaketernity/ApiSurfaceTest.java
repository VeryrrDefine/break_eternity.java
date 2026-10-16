package breaketernity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Completeness sweep: every member name declared in the original's index.d.ts must exist
 * in the Java port (as a method of any arity, static or instance). The name list is
 * generated from the original by tools/gen-api-surface.mjs.
 */
class ApiSurfaceTest {

    @Test
    void everyOriginalMemberExists() throws IOException {
        Set<String> javaNames = new HashSet<>();
        for (Method method : Decimal.class.getDeclaredMethods()) {
            javaNames.add(method.getName());
        }
        // Getters/setters and constructors appear in the .d.ts but are represented by
        // public fields, accessors or factories here.
        javaNames.add("sign");
        javaNames.add("mag");
        javaNames.add("layer");

        List<String> missing = new ArrayList<>();
        try (Stream<String> lines =
                Files.lines(Paths.get("src", "test", "resources", "api_surface.txt"))) {
            lines.filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .forEach(
                            name -> {
                                if (!javaNames.contains(name)) {
                                    missing.add(name);
                                }
                            });
        }
        assertTrue(
                missing.isEmpty(),
                "these members of the original are missing from the Java port: " + missing);
    }
}
