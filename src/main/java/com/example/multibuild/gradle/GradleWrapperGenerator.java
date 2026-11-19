package com.example.multibuild.gradle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// Adds the Gradle wrapper (gradlew, gradlew.bat, gradle/wrapper/*) to a repo WITHOUT needing Gradle
// installed. The wrapper is just four files; gradlew downloads the distribution itself on first use.
// Sources, first one that works:
//   1. migrate.gradle.wrapper.template — a directory holding gradlew, gradlew.bat and
//      gradle/wrapper/gradle-wrapper.jar (e.g. any repo that already has a wrapper),
//   2. migrate.gradle.wrapper.source  — base URL serving those files (default: the Gradle GitHub repo at
//      the release tag); downloads are cached under migrate.gradle.wrapper.cache,
//   3. `gradle wrapper`, when gradle happens to be on PATH.
// gradle-wrapper.properties is always written by us so migrate.gradle.distribution.url (e.g. an internal
// mirror) is honoured.
@Component
public class GradleWrapperGenerator {

    private static final Logger log = LoggerFactory.getLogger(GradleWrapperGenerator.class);

    static final String GRADLEW = "gradlew";
    static final String GRADLEW_BAT = "gradlew.bat";
    static final String WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar";
    static final String WRAPPER_PROPERTIES = "gradle/wrapper/gradle-wrapper.properties";
    private static final List<String> FILES = List.of(GRADLEW, GRADLEW_BAT, WRAPPER_JAR);

    @Value("${migrate.gradle.version:8.10.2}")
    private String gradleVersion;

    @Value("${migrate.gradle.wrapper.template:}")
    private String template;

    @Value("${migrate.gradle.wrapper.source:https://raw.githubusercontent.com/gradle/gradle/v{version}}")
    private String source;

    @Value("${migrate.gradle.distribution.url:https://services.gradle.org/distributions/gradle-{version}-bin.zip}")
    private String distributionUrl;

    @Value("${migrate.gradle.wrapper.cache:${user.home}/.multibuild/gradle-wrapper}")
    private String cacheDir;

    public static boolean hasWrapper(Path repoDir) {
        return Files.isRegularFile(repoDir.resolve(GRADLEW)) && Files.isRegularFile(repoDir.resolve(WRAPPER_JAR));
    }

    public String gradleVersion() {
        return gradleVersion;
    }

    // Returns null on success, otherwise a warning explaining how to add the wrapper by hand.
    public String generate(Path repoDir) {
        List<String> attempts = new ArrayList<>();
        Path from = null;
        if (!template.isBlank()) {
            Path dir = Path.of(template);
            if (FILES.stream().allMatch(f -> Files.isRegularFile(dir.resolve(f)))) from = dir;
            else attempts.add("template " + dir + " lacks " + String.join(", ", FILES));
        }
        if (from == null) {
            try {
                from = downloaded();
            } catch (IOException e) {
                attempts.add("download from " + expand(source) + " failed: " + e.getMessage());
            }
        }
        try {
            if (from != null) {
                copy(from, repoDir);
            } else if (!runGradleWrapper(repoDir)) {
                attempts.add("'gradle' not available on PATH");
                return "Gradle wrapper not generated (" + String.join("; ", attempts) + ") — set "
                        + "migrate.gradle.wrapper.template to a directory containing a Gradle wrapper "
                        + "(gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar) and run migrate-gradle again";
            }
            writeProperties(repoDir);
            writeGitattributes(repoDir);
            log.info("  [{}] Gradle wrapper added (Gradle {})", repoDir.getFileName(), gradleVersion);
            return null;
        } catch (IOException e) {
            return "Gradle wrapper not generated: " + e.getMessage();
        }
    }

    // Wrapper files fetched from `source` into the per-version cache (fetched once per machine).
    private Path downloaded() throws IOException {
        Path dir = Path.of(cacheDir).resolve(gradleVersion);
        if (FILES.stream().allMatch(f -> Files.isRegularFile(dir.resolve(f)))) return dir;
        String base = expand(source).replaceAll("/+$", "");
        for (String f : FILES) {
            Path target = dir.resolve(f);
            if (Files.isRegularFile(target)) continue;
            byte[] bytes = fetch(base + "/" + f);
            validate(f, bytes);
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "dl", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return dir;
    }

    private static byte[] fetch(String url) throws IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    // Guards against caching an HTML error / proxy login page instead of the real file.
    private static void validate(String file, byte[] bytes) throws IOException {
        boolean ok = file.endsWith(".jar")
                ? bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K'
                : file.equals(GRADLEW)
                ? new String(bytes, 0, Math.min(bytes.length, 2), StandardCharsets.US_ASCII).equals("#!")
                : bytes.length > 0 && !new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.UTF_8)
                        .toLowerCase().contains("<html");
        if (!ok) throw new IOException(file + " is not a valid wrapper file (proxy or error page?)");
    }

    private static void copy(Path from, Path repoDir) throws IOException {
        for (String f : FILES) {
            Path target = repoDir.resolve(f);
            Files.createDirectories(target.getParent());
            Files.copy(from.resolve(f), target, StandardCopyOption.REPLACE_EXISTING);
        }
        makeExecutable(repoDir.resolve(GRADLEW));
    }

    private static void makeExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.copyOf(Files.getPosixFilePermissions(file));
            perms.addAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_EXECUTE));
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException e) {
            // Windows: no POSIX permissions — git keeps the mode from the template/core.filemode.
        }
    }

    void writeProperties(Path repoDir) throws IOException {
        Path file = repoDir.resolve(WRAPPER_PROPERTIES);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                distributionBase=GRADLE_USER_HOME
                distributionPath=wrapper/dists
                distributionUrl=%s
                networkTimeout=10000
                validateDistributionUrl=true
                zipStoreBase=GRADLE_USER_HOME
                zipStorePath=wrapper/dists
                """.formatted(expand(distributionUrl).replace(":", "\\:")), StandardCharsets.UTF_8);
    }

    // Same line-ending rules `gradle wrapper` / `gradle init` write; existing files are left alone.
    private static void writeGitattributes(Path repoDir) throws IOException {
        Path file = repoDir.resolve(".gitattributes");
        if (Files.exists(file)) return;
        Files.writeString(file, """
                /gradlew        text eol=lf
                *.bat           text eol=crlf
                *.jar           binary
                """, StandardCharsets.UTF_8);
    }

    private boolean runGradleWrapper(Path repoDir) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = List.of(windows ? "gradle.bat" : "gradle", "wrapper",
                "--gradle-version", gradleVersion, "--no-daemon", "-q");
        try {
            Process process = new ProcessBuilder(cmd).directory(repoDir.toFile()).inheritIO().start();
            if (!process.waitFor(10, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0 && hasWrapper(repoDir);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String expand(String s) {
        return s.replace("{version}", gradleVersion);
    }
}
