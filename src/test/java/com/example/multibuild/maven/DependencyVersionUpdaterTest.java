package com.example.multibuild.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DependencyVersionUpdaterTest {

    private final DependencyVersionUpdater updater = new DependencyVersionUpdater();

    @Test
    void updatesInlineVersion(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>mylib</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        updater.update(List.of(dir), Map.of("com.example:mylib", "1.1.0"));

        String result = Files.readString(pom);
        assertTrue(result.contains("<version>1.1.0</version>"));
    }

    @Test
    void updatesPropertyPlaceholderVersion(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <properties>
                    <!-- library version -->
                    <mylib.version>1.0.0</mylib.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>mylib</artifactId>
                      <version>${mylib.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        updater.update(List.of(dir), Map.of("com.example:mylib", "1.1.0"));

        String result = Files.readString(pom);
        // Property value updated
        assertTrue(result.contains("<mylib.version>1.1.0</mylib.version>"));
        // Placeholder in <version> left unchanged
        assertTrue(result.contains("<version>${mylib.version}</version>"));
        // Comment preserved
        assertTrue(result.contains("<!-- library version -->"));
    }

    @Test
    void sharedPropertyUpdatedOnlyOnce(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <properties>
                    <mylib.version>1.0.0</mylib.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>mylib-core</artifactId>
                      <version>${mylib.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>mylib-utils</artifactId>
                      <version>${mylib.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        updater.update(List.of(dir), Map.of(
                "com.example:mylib-core", "1.1.0",
                "com.example:mylib-utils", "1.1.0"));

        String result = Files.readString(pom);
        // Exactly one property definition in the output
        assertEquals(1, countOccurrences(result, "<mylib.version>1.1.0</mylib.version>"));
    }

    @Test
    void missingPropertyLogsWarnAndSkips(@TempDir Path dir) throws Exception {
        Path pom = dir.resolve("pom.xml");
        String original = """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>mylib</artifactId>
                      <version>${undefined.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        Files.writeString(pom, original);

        // Should not throw; pom is left unchanged
        updater.update(List.of(dir), Map.of("com.example:mylib", "1.1.0"));

        assertEquals(original, Files.readString(pom));
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) { count++; idx += sub.length(); }
        return count;
    }
}
