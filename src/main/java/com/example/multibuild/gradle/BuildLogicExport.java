package com.example.multibuild.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Makes an existing Gradle build (a repo migrated earlier, maybe edited by hand) publish what the repositories
// inheriting its poms need: its build-logic (convention plugins, by plugin marker) and its
// gradle/libs.versions.toml as a version catalog (<group>:<rootProject.name>-catalog), both at the repo's
// version, by `publish` / `publishToMavenLocal` of the root build.
// The edits are additive and idempotent: missing pieces are inserted, nothing written by hand is rewritten.
final class BuildLogicExport {

    record Result(Map<Path, String> writes, List<String> warnings) {
        boolean isEmpty() { return writes.isEmpty(); }
    }

    private static final Pattern PLUGINS_OPEN = Pattern.compile("(?m)^[ \\t]*plugins\\s*\\{[ \\t]*$");
    private static final Pattern ROOT_NAME = Pattern.compile("rootProject\\.name\\s*=\\s*([\"'])([^\"']+)\\1");
    private static final Pattern ASSIGNED = Pattern.compile("(?m)^\\s*(group|version)\\s*=");

    private BuildLogicExport() {}

    // conventionByKey: parent pom g:a → the convention standing for it (gets the "// maven-parent:" marker).
    static Result export(Path repo, Map<String, String> conventionByKey) {
        Map<Path, String> writes = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String name = repo.getFileName().toString();
        boolean kts = Files.isRegularFile(repo.resolve("build-logic/build.gradle.kts"));
        Path script = repo.resolve("build-logic").resolve(kts ? "build.gradle.kts" : "build.gradle");
        String text = ParentBuildLogic.readOrNull(script);
        if (text == null) {
            warnings.add(name + ": no build-logic/build.gradle(.kts) — its convention plugins cannot be published "
                    + "for the repositories inheriting its poms");
            return new Result(writes, warnings);
        }
        Map<String, String> props = GradleProperties.read(repo.resolve(GradleProperties.FILE_NAME));
        String rootName = ParentBuildLogic.rootName(ParentBuildLogic.readOrNull(ParentBuildLogic.settingsFile(repo)));
        if (props.get("group") == null || props.get("version") == null || rootName == null) {
            warnings.add(name + ": group / version in gradle.properties and rootProject.name in settings are needed to "
                    + "publish build-logic and the version catalog at the repo's coordinates — add them, then migrate again");
            return new Result(writes, warnings);
        }

        if (!text.contains("version-catalog")) {
            String updated = withPlugins(text, kts);
            if (updated == null) {
                warnings.add(name + ": " + repo.relativize(script) + " has no plugins { } block — add maven-publish and "
                        + "version-catalog to publish build-logic and the catalog");
            } else {
                if (kts && !updated.contains("import java.util.Properties")) {
                    // `java` is the java extension inside the script, so java.util.Properties needs an import
                    Matcher m = PLUGINS_OPEN.matcher(updated);
                    if (m.find()) updated = updated.substring(0, m.start()) + "import java.util.Properties\n\n" + updated.substring(m.start());
                }
                updated = trimEnd(updated) + "\n\n" + publication(repo, rootName, updated, kts, warnings);
                writes.put(script, updated);
            }
        }

        Path settings = repo.resolve("build-logic").resolve(kts ? "settings.gradle.kts" : "settings.gradle");
        String settingsText = ParentBuildLogic.readOrNull(settings);
        String jarName = rootName + "-build-logic";
        if (settingsText == null) {
            writes.put(settings, "rootProject.name = \"" + jarName + "\"\n");
        } else {
            Matcher m = ROOT_NAME.matcher(settingsText);
            if (!m.find()) {
                writes.put(settings, trimEnd(settingsText) + "\n\n// artifactId of the published build-logic jar\n"
                        + "rootProject.name = \"" + jarName + "\"\n");
            } else if (m.group(2).equals("build-logic")) {
                // the default name would publish an artifact called just "build-logic"
                writes.put(settings, settingsText.substring(0, m.start(2)) + jarName + settingsText.substring(m.end(2)));
            }
        }

        Path rootBuild = repo.resolve(kts ? "build.gradle.kts" : "build.gradle");
        if (!Files.isRegularFile(rootBuild)) {
            Path other = repo.resolve(kts ? "build.gradle" : "build.gradle.kts");
            if (Files.isRegularFile(other)) rootBuild = other;
        }
        String rootText = ParentBuildLogic.readOrNull(rootBuild);
        if (rootText == null || !rootText.contains("includedBuild(")) {
            boolean rootKts = rootBuild.getFileName().toString().endsWith(".kts");
            writes.put(rootBuild, (rootText == null ? "" : trimEnd(rootText) + "\n\n") + wiring(rootKts));
        }

        conventionByKey.forEach((key, id) -> {
            Path file = repo.resolve("build-logic/src/main").resolve(kts ? "kotlin/" + id + ".gradle.kts" : "groovy/" + id + ".gradle");
            String convention = writes.containsKey(file) ? writes.get(file) : ParentBuildLogic.readOrNull(file);
            if (convention == null || convention.contains(ParentBuildLogic.MARKER + key)) return;
            writes.put(file, withMarker(convention, key));
        });
        return new Result(writes, warnings);
    }

    // maven-publish and version-catalog appended to the plugins { } block: after the plugin-development plugin
    // (groovy-gradle-plugin publishes no plugin markers when maven-publish comes first).
    private static String withPlugins(String text, boolean kts) {
        List<int[]> blocks = new GradleScript(null, text).blocks("plugins");
        if (blocks.isEmpty()) return null;
        int end = blocks.get(0)[1];
        int at = text.lastIndexOf('\n', end - 1) + 1;
        boolean oneLine = !text.substring(at, end).isBlank();     // plugins { a }
        if (oneLine) at = end;
        StringBuilder add = new StringBuilder(oneLine ? "\n" : "");
        if (!text.contains("maven-publish")) add.append(kts ? "    `maven-publish`\n" : "    id 'maven-publish'\n");
        add.append(kts ? "    `version-catalog`\n" : "    id 'version-catalog'\n");
        return text.substring(0, at) + add + text.substring(at);
    }

    // Coordinates (from gradle.properties, like the modules), the catalog and the publication.
    private static String publication(Path repo, String rootName, String script, boolean kts, List<String> warnings) {
        StringBuilder s = new StringBuilder();
        s.append("// Published with the modules (by `publish` / `publishToMavenLocal` of the root build): the repositories\n")
                .append("// whose poms inherited this repo's poms apply these convention plugins and import this version catalog.\n");
        Matcher assigned = ASSIGNED.matcher(script);
        boolean group = false;
        boolean version = false;
        while (assigned.find()) {
            if (assigned.group(1).equals("group")) group = true;
            else version = true;
        }
        if (!group || !version) {
            if (kts) {
                s.append("val repoProperties = Properties()\n");
                s.append("file(\"../gradle.properties\").inputStream().use { repoProperties.load(it) }\n");
                if (!group) s.append("group = repoProperties.getProperty(\"group\")\n");
                if (!version) s.append("version = repoProperties.getProperty(\"version\")\n");
            } else {
                s.append("def repoProperties = new Properties()\n");
                s.append("file('../gradle.properties').withInputStream { repoProperties.load(it) }\n");
                if (!group) s.append("group = repoProperties.getProperty('group')\n");
                if (!version) s.append("version = repoProperties.getProperty('version')\n");
            }
            s.append('\n');
        }
        String q = kts ? "\"" : "'";
        s.append("catalog {\n    versionCatalog {\n        from(files(").append(q).append("../gradle/libs.versions.toml").append(q)
                .append("))\n    }\n}\n\n");
        s.append("publishing {\n    publications {\n");
        s.append(kts ? "        create<MavenPublication>(\"catalog\") {\n" : "        catalog(MavenPublication) {\n");
        s.append("            artifactId = ").append(q).append(rootName).append(ParentBuildLogic.CATALOG_SUFFIX).append(q).append('\n');
        s.append(kts ? "            from(components[\"versionCatalog\"])\n" : "            from components.versionCatalog\n");
        s.append("        }\n    }\n");
        if (!script.contains("publishing")) {
            String repositories = publishingRepositories(repo);
            if (repositories != null) {
                s.append("    // the repositories of the modules' publishing convention\n");
                for (String l : repositories.split("\n")) s.append(l.isBlank() ? "" : "    " + l.stripTrailing()).append('\n');
            } else {
                warnings.add(repo.getFileName() + ": no publishing repositories found in its convention plugins — "
                        + "build-logic and the catalog are only published by publishToMavenLocal; add "
                        + "publishing { repositories { } } to build-logic/" + (kts ? "build.gradle.kts" : "build.gradle"));
            }
        }
        s.append("}\n");
        return s.toString();
    }

    // `repositories { ... }` of the convention applying maven-publish, re-indented to column 0.
    private static String publishingRepositories(Path repo) {
        for (GradleScript convention : ConventionPlugins.load(repo).byId.values()) {
            if (!convention.text.contains("maven-publish")) continue;
            int publishing = Math.max(convention.text.indexOf("PublishingExtension"), convention.text.indexOf("publishing"));
            if (publishing < 0) continue;
            for (int[] body : convention.blocks("repositories")) {
                if (body[0] < publishing) continue;
                String inner = convention.text.substring(body[0], body[1]);
                return "repositories {\n" + dedent(inner).strip() .lines().map(l -> "    " + l).reduce((a, b) -> a + "\n" + b).orElse("")
                        + "\n}";
            }
        }
        return null;
    }

    private static String dedent(String text) {
        int min = Integer.MAX_VALUE;
        for (String l : text.split("\n")) {
            if (l.isBlank()) continue;
            int indent = 0;
            while (indent < l.length() && l.charAt(indent) == ' ') indent++;
            min = Math.min(min, indent);
        }
        if (min == Integer.MAX_VALUE || min == 0) return text;
        StringBuilder sb = new StringBuilder();
        for (String l : text.split("\n")) sb.append(l.length() >= min ? l.substring(min) : l.strip()).append('\n');
        return sb.toString();
    }

    static String wiring(boolean kts) {
        return kts
                ? "// the convention plugins and the version catalog are published with the modules: other repositories use them\n"
                + "listOf(\"publish\", \"publishToMavenLocal\").forEach { name ->\n"
                + "    tasks.maybeCreate(name).dependsOn(gradle.includedBuild(\"build-logic\").task(\":$name\"))\n"
                + "}\n"
                : "// the convention plugins and the version catalog are published with the modules: other repositories use them\n"
                + "['publish', 'publishToMavenLocal'].each { name ->\n"
                + "    tasks.maybeCreate(name).dependsOn(gradle.includedBuild('build-logic').task(\":$name\"))\n"
                + "}\n";
    }

    // The marker line after the leading comment lines of a convention script.
    private static String withMarker(String convention, String key) {
        int at = 0;
        while (at < convention.length() && convention.startsWith("//", at)) {
            int nl = convention.indexOf('\n', at);
            at = nl < 0 ? convention.length() : nl + 1;
        }
        return convention.substring(0, at) + ParentBuildLogic.MARKER + key + "\n"
                + (at == 0 ? "\n" : "") + convention.substring(at);
    }

    private static String trimEnd(String s) {
        return s.stripTrailing();
    }
}
