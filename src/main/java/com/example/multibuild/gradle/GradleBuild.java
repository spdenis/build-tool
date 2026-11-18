package com.example.multibuild.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Project structure of a Gradle build: the root project plus every project included in
// settings.gradle(.kts), with their directories and build scripts.
final class GradleBuild {

    record Project(String path, String name, Path dir, Path buildFile) {
        boolean isRoot() { return ":".equals(path); }
    }

    private static final Pattern ROOT_NAME =
            Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern INCLUDE =
            Pattern.compile("\\binclude\\s*\\(?((?:\\s*[\"'][^\"'\\n]+[\"']\\s*,?)+)\\)?");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"'\\n]+)[\"']");
    private static final Pattern PROJECT_DIR = Pattern.compile(
            "project\\(\\s*[\"']([^\"']+)[\"']\\s*\\)\\.projectDir\\s*=\\s*(?:file|File)\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    final Path root;
    final String rootName;
    final List<Project> projects;   // root first, then subprojects in declaration order
    final Map<String, String> rootProperties;

    private GradleBuild(Path root, String rootName, List<Project> projects, Map<String, String> rootProperties) {
        this.root = root;
        this.rootName = rootName;
        this.projects = projects;
        this.rootProperties = rootProperties;
    }

    static GradleBuild load(Path repoRoot) {
        GradleScript settings = GradleScript.read(firstExisting(repoRoot, "settings.gradle", "settings.gradle.kts"));
        String rootName = repoRoot.getFileName().toString();
        Map<String, Path> dirByPath = new LinkedHashMap<>();

        if (settings != null) {
            Matcher nm = ROOT_NAME.matcher(settings.text);
            while (nm.find()) {
                if (settings.isCode(nm.start())) { rootName = nm.group(1); break; }
            }
            Matcher im = INCLUDE.matcher(settings.text);
            while (im.find()) {
                if (!settings.isCode(im.start())) continue;
                Matcher q = QUOTED.matcher(im.group(1));
                while (q.find()) {
                    String path = normalize(q.group(1));
                    // include ':a:b' implicitly includes ':a' as well.
                    String[] segments = path.substring(1).split(":");
                    StringBuilder prefix = new StringBuilder();
                    for (String seg : segments) {
                        prefix.append(':').append(seg);
                        String p = prefix.toString();
                        dirByPath.putIfAbsent(p, repoRoot.resolve(p.substring(1).replace(':', '/')));
                    }
                }
            }
            Matcher dm = PROJECT_DIR.matcher(settings.text);
            while (dm.find()) {
                if (!settings.isCode(dm.start())) continue;
                String path = normalize(dm.group(1));
                if (dirByPath.containsKey(path)) {
                    dirByPath.put(path, repoRoot.resolve(dm.group(2)).normalize());
                }
            }
        }

        List<Project> projects = new ArrayList<>();
        projects.add(new Project(":", rootName, repoRoot, buildFileIn(repoRoot)));
        dirByPath.forEach((path, dir) -> projects.add(
                new Project(path, path.substring(path.lastIndexOf(':') + 1), dir, buildFileIn(dir))));

        return new GradleBuild(repoRoot, rootName, projects,
                GradleProperties.read(repoRoot.resolve(GradleProperties.FILE_NAME)));
    }

    boolean isMultiProject() {
        return projects.size() > 1;
    }

    Project rootProject() {
        return projects.get(0);
    }

    static Path buildFileIn(Path dir) {
        return firstExisting(dir, "build.gradle", "build.gradle.kts");
    }

    private static Path firstExisting(Path dir, String... names) {
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static String normalize(String includePath) {
        String p = includePath.trim();
        return p.startsWith(":") ? p : ":" + p;
    }
}
