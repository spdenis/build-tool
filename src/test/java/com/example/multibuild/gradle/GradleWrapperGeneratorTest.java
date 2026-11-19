package com.example.multibuild.gradle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.example.multibuild.testutil.Fixtures.read;
import static com.example.multibuild.testutil.Fixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

class GradleWrapperGeneratorTest {

    static final byte[] JAR = {'P', 'K', 3, 4, 0, 0};

    @TempDir
    Path tempDir;

    private GradleWrapperGenerator generator;
    private Path repo;

    // A directory laid out like a repo with a wrapper (usable as template or as download source).
    static Path wrapperDir(Path dir) throws Exception {
        write(dir.resolve("gradlew"), "#!/bin/sh\necho gradlew\n");
        write(dir.resolve("gradlew.bat"), "@echo off\r\n");
        Files.createDirectories(dir.resolve("gradle/wrapper"));
        Files.write(dir.resolve("gradle/wrapper/gradle-wrapper.jar"), JAR);
        return dir;
    }

    static GradleWrapperGenerator generator(Path tempDir, String template, String source) {
        GradleWrapperGenerator g = new GradleWrapperGenerator();
        ReflectionTestUtils.setField(g, "gradleVersion", "8.10.2");
        ReflectionTestUtils.setField(g, "template", template);
        ReflectionTestUtils.setField(g, "source", source);
        ReflectionTestUtils.setField(g, "distributionUrl", "https://services.gradle.org/distributions/gradle-{version}-bin.zip");
        ReflectionTestUtils.setField(g, "cacheDir", tempDir.resolve("cache").toString());
        return g;
    }

    @BeforeEach
    void setUp() {
        repo = write(tempDir.resolve("repo/build.gradle"), "plugins { id 'java' }\n").getParent();
    }

    @Test
    void template_isCopiedAndPropertiesWritten() throws Exception {
        Path template = wrapperDir(tempDir.resolve("template"));
        generator = generator(tempDir, template.toString(), "file:///nonexistent");

        assertThat(generator.generate(repo)).isNull();

        assertThat(GradleWrapperGenerator.hasWrapper(repo)).isTrue();
        assertThat(read(repo.resolve("gradlew"))).isEqualTo("#!/bin/sh\necho gradlew\n");
        assertThat(Files.isExecutable(repo.resolve("gradlew"))).isTrue();
        assertThat(repo.resolve("gradlew.bat")).exists();
        assertThat(Files.readAllBytes(repo.resolve("gradle/wrapper/gradle-wrapper.jar"))).isEqualTo(JAR);
        assertThat(read(repo.resolve("gradle/wrapper/gradle-wrapper.properties")))
                .contains("distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip");
        assertThat(read(repo.resolve(".gitattributes"))).contains("/gradlew        text eol=lf", "*.jar           binary");
    }

    @Test
    void source_isDownloadedOnceIntoCache_andMirrorUrlIsUsed() throws Exception {
        Path served = wrapperDir(tempDir.resolve("served/v8.10.2"));
        generator = generator(tempDir, "", tempDir.resolve("served").toUri() + "v{version}/");
        ReflectionTestUtils.setField(generator, "distributionUrl", "https://nexus.acme/gradle/gradle-{version}-bin.zip");
        write(repo.resolve(".gitattributes"), "* text=auto\n");

        assertThat(generator.generate(repo)).isNull();
        assertThat(tempDir.resolve("cache/8.10.2/gradle/wrapper/gradle-wrapper.jar")).exists();
        assertThat(read(repo.resolve("gradle/wrapper/gradle-wrapper.properties")))
                .contains("distributionUrl=https\\://nexus.acme/gradle/gradle-8.10.2-bin.zip");
        assertThat(read(repo.resolve(".gitattributes"))).isEqualTo("* text=auto\n");

        // second repo is served from the cache even though the source is gone
        deleteRecursively(served);
        Path other = write(tempDir.resolve("other/build.gradle"), "").getParent();
        assertThat(generator.generate(other)).isNull();
        assertThat(GradleWrapperGenerator.hasWrapper(other)).isTrue();
    }

    @Test
    void invalidDownload_isRejectedAndNotCached() throws Exception {
        Path served = wrapperDir(tempDir.resolve("served"));
        write(served.resolve("gradle/wrapper/gradle-wrapper.jar"), "<html>proxy login</html>");
        generator = generator(tempDir, "", served.toUri().toString());

        String warning = generator.generate(repo);

        // falls through to `gradle wrapper`; only when that is unavailable is a warning returned
        if (warning != null) {
            assertThat(warning).contains("not a valid wrapper file", "migrate.gradle.wrapper.template");
            assertThat(GradleWrapperGenerator.hasWrapper(repo)).isFalse();
        }
        assertThat(tempDir.resolve("cache/8.10.2/gradle/wrapper/gradle-wrapper.jar")).doesNotExist();
    }

    @Test
    void incompleteTemplate_fallsBackToSource() throws Exception {
        Path template = write(tempDir.resolve("template/gradlew"), "#!/bin/sh\n").getParent();
        Path served = wrapperDir(tempDir.resolve("served"));
        generator = generator(tempDir, template.toString(), served.toUri().toString());

        assertThat(generator.generate(repo)).isNull();
        assertThat(GradleWrapperGenerator.hasWrapper(repo)).isTrue();
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
