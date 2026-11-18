package com.example.multibuild.testutil;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

// Test fixtures: repositories under src/test/resources/repos and small file helpers.
public final class Fixtures {

    private Fixtures() {}

    public static Path reposDir() {
        try {
            URI uri = Fixtures.class.getClassLoader().getResource("repos").toURI();
            return Paths.get(uri);
        } catch (Exception e) {
            throw new RuntimeException("Cannot locate test resource repos/", e);
        }
    }

    // Copies fixture repo `name` to target/name and returns the copy.
    public static Path copy(String name, Path target) {
        Path dst = target.resolve(name);
        copyDirectory(reposDir().resolve(name), dst);
        return dst;
    }

    public static void copyDirectory(Path source, Path target) {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path dst = target.resolve(source.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Path write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
