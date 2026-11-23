package com.example.multibuild.gradle;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Extracts declarations (dependencies, plugins, coordinates, property definitions) from Gradle
// build scripts. Supports the common Groovy and Kotlin DSL notations:
//   api 'g:a:v'            api("g:a:v")             api "g:a:${libVersion}"     api("g:a:$libVersion")
//   api platform('g:a:v')  constraints { api 'g:a:v' }
//   implementation group: 'g', name: 'a', version: 'v'      implementation(group = "g", name = "a", version = "v")
final class GradleDeclarations {

    private GradleDeclarations() {}

    // A dependency coordinate found in a script. versionStart/versionEnd delimit the version text
    // in the script (-1 when the dependency has no version, e.g. managed by a platform).
    record DependencyRef(String group, String artifact, String version, int versionStart, int versionEnd) {
        String key() { return group + ":" + artifact; }
        boolean hasVersion() { return version != null && !version.isEmpty(); }
        boolean isReference() { return hasVersion() && version.startsWith("$"); }
    }

    // A property definition (`ext.x = '1'`, `def x = '1'`, `val x = "1"`, `extra["x"] = "1"` ...).
    record Definition(String value, int valueStart, int valueEnd) {}

    private static final Pattern STRING_COORD = Pattern.compile(
            "([\"'])([\\w.\\-]+):([\\w.\\-]+)"
            + "(?::(\\$\\{[^}\\n]*\\}|\\$[A-Za-z_][\\w.]*|[\\w.\\-+\\[\\](),]+))?"
            + "(?::[\\w.\\-]+)?(?:@\\w+)?\\1");
    private static final Pattern MAP_COORD = Pattern.compile(
            "\\bgroup\\s*[:=]\\s*([\"'])([\\w.\\-]+)\\1\\s*,\\s*name\\s*[:=]\\s*([\"'])([\\w.\\-]+)\\3"
            + "(?:\\s*,\\s*version\\s*[:=]\\s*([\"'])([^\"'\\n]*)\\5)?");

    private static final Pattern PLUGIN_ID = Pattern.compile(
            "\\bid\\s*\\(?\\s*[\"']([\\w.\\-]+)[\"']"
            + "|`([\\w.\\-]+)`"
            + "|\\bapply\\s*\\(?\\s*plugin\\s*[:=]\\s*[\"']([\\w.\\-]+)[\"']"
            + "|\\bkotlin\\s*\\(\\s*\"([\\w.\\-]+)\"\\s*\\)"
            + "|^\\s*(java|groovy|scala|application|war)\\s*$", Pattern.MULTILINE);

    private static final Pattern GROUP_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:project\\.)?group\\s*(?:=\\s*)?([\"'])([^\"'$\\n]+)\\1");
    private static final Pattern VERSION_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:project\\.)?version\\s*(?:=\\s*)?([\"'])([^\"'$\\n]+)\\1");
    private static final Pattern ARTIFACT_ID_DECL = Pattern.compile(
            "\\bartifactId\\s*(?:=\\s*)?[\"']([\\w.\\-]+)[\"']");
    private static final Pattern ARCHIVES_NAME_DECL = Pattern.compile(
            "\\b(?:archivesName(?:\\.set\\(|\\s*=)|archivesBaseName\\s*=)\\s*[\"']([\\w.\\-]+)[\"']");

    // Dependency coordinates inside `dependencies { }` blocks within `include` ranges
    // (whole script when null), ignoring buildscript classpath dependencies.
    static List<DependencyRef> dependencies(GradleScript script, List<int[]> include) {
        List<int[]> depBlocks = script.blocks("dependencies");
        List<int[]> buildscript = script.blocks("buildscript");
        List<DependencyRef> result = new ArrayList<>();

        Matcher m = STRING_COORD.matcher(script.text);
        while (m.find()) {
            int pos = m.start();
            if (!accept(script, pos, depBlocks, buildscript, include)) continue;
            String version = m.group(4);
            result.add(new DependencyRef(m.group(2), m.group(3), version,
                    version != null ? m.start(4) : -1, version != null ? m.end(4) : -1));
        }
        Matcher mm = MAP_COORD.matcher(script.text);
        while (mm.find()) {
            if (!script.isCode(mm.start()) || !accept(script, mm.start(1), depBlocks, buildscript, include)) continue;
            String version = mm.group(6);
            result.add(new DependencyRef(mm.group(2), mm.group(4), version,
                    version != null ? mm.start(6) : -1, version != null ? mm.end(6) : -1));
        }
        result.sort((a, b) -> Integer.compare(a.versionStart(), b.versionStart()));
        return result;
    }

    private static final Pattern CATALOG_ACCESSOR = Pattern.compile(
            "\\blibs\\.((?:[A-Za-z][\\w]*)(?:\\.[A-Za-z0-9]\\w*)*)");
    private static final Pattern FIND_LIBRARY = Pattern.compile(
            "\\bfindLibrary\\(\\s*[\"']([^\"'\\n]+)[\"']\\s*\\)");

    // Version catalog references inside `dependencies { }` blocks — libs.spring.context,
    // libs.jackson.asProvider(), libs.findLibrary("spring-context") — resolved through the catalog. The
    // version is the catalog's (no position: catalog versions are edited in the TOML file).
    static List<DependencyRef> catalogDependencies(GradleScript script, List<int[]> include, VersionCatalog catalog) {
        List<DependencyRef> result = new ArrayList<>();
        if (catalog == null) return result;
        List<int[]> depBlocks = script.blocks("dependencies");
        List<int[]> buildscript = script.blocks("buildscript");
        for (Pattern p : List.of(CATALOG_ACCESSOR, FIND_LIBRARY)) {
            Matcher m = p.matcher(script.text);
            while (m.find()) {
                int pos = m.start();
                if (!script.isCode(pos) || !GradleScript.within(depBlocks, pos) || GradleScript.within(buildscript, pos)) continue;
                if (include != null && !GradleScript.within(include, pos)) continue;
                String alias = m.group(1).replaceFirst("\\.(?:asProvider|get)$", "");
                if (p == CATALOG_ACCESSOR && alias.matches("(?:versions|plugins|bundles|findLibrary|findVersion)\\b.*")) continue;
                VersionCatalog.Library lib = catalog.byAccessor(alias);
                if (lib == null) continue;
                result.add(new DependencyRef(lib.group(), lib.artifact(), catalog.versionOf(lib), -1, -1));
            }
        }
        return result;
    }

    private static boolean accept(GradleScript script, int quotePos, List<int[]> depBlocks,
                                  List<int[]> buildscript, List<int[]> include) {
        return script.isStringStart(quotePos)
                && GradleScript.within(depBlocks, quotePos)
                && !GradleScript.within(buildscript, quotePos)
                && (include == null || GradleScript.within(include, quotePos));
    }

    // Plugin ids applied in `plugins { }` blocks or via `apply plugin:` within the given ranges.
    static Set<String> plugins(GradleScript script, List<int[]> include) {
        Set<String> result = new LinkedHashSet<>();
        List<int[]> pluginBlocks = script.blocks("plugins");
        Matcher m = PLUGIN_ID.matcher(script.text);
        while (m.find()) {
            int group = m.group(5) != null ? 5 : 0;
            int pos = m.start(group);
            if (!script.isCode(pos)) continue;
            if (include != null && !GradleScript.within(include, pos)) continue;
            if (m.group(3) != null) {
                result.add(m.group(3));
                continue;
            }
            if (!GradleScript.within(pluginBlocks, pos)) continue;
            if (m.group(1) != null) result.add(m.group(1));
            else if (m.group(2) != null) result.add(m.group(2));
            else if (m.group(4) != null) result.add("org.jetbrains.kotlin." + m.group(4));
            else if (m.group(5) != null) result.add(m.group(5));
        }
        return result;
    }

    static String group(GradleScript script, List<int[]> include) {
        return firstValue(script, GROUP_DECL, 2, include, nonProjectBlocks(script));
    }

    static String version(GradleScript script, List<int[]> include) {
        return firstValue(script, VERSION_DECL, 2, include, nonProjectBlocks(script));
    }

    // Blocks whose `group = ...` / `version = ...` lines do not describe the project itself.
    private static List<int[]> nonProjectBlocks(GradleScript script) {
        List<int[]> excluded = new ArrayList<>(script.blocks("dependencies"));
        excluded.addAll(script.blocks("plugins"));
        excluded.addAll(script.blocks("buildscript"));
        return excluded;
    }

    // Every literal `version = '...'` declaration outside dependency/plugin blocks: [valueStart, valueEnd).
    static List<int[]> versionDeclarations(GradleScript script) {
        List<int[]> excluded = nonProjectBlocks(script);
        List<int[]> result = new ArrayList<>();
        Matcher m = VERSION_DECL.matcher(script.text);
        while (m.find()) {
            int quote = m.start(1);
            if (!script.isStringStart(quote) || GradleScript.within(excluded, quote)) continue;
            result.add(new int[]{m.start(2), m.end(2)});
        }
        return result;
    }

    static String artifactId(GradleScript script) {
        String id = firstValue(script, ARTIFACT_ID_DECL, 1, null, List.of());
        return id != null ? id : firstValue(script, ARCHIVES_NAME_DECL, 1, null, List.of());
    }

    private static String firstValue(GradleScript script, Pattern p, int group,
                                     List<int[]> include, List<int[]> excluded) {
        Matcher m = p.matcher(script.text);
        while (m.find()) {
            int valuePos = m.start(group);
            if (!script.isStringStart(valuePos - 1)) continue;
            if (include != null && !GradleScript.within(include, valuePos)) continue;
            if (GradleScript.within(excluded, valuePos)) continue;
            return m.group(group);
        }
        return null;
    }

    // Name of the property a version reference points to: "$x", "${x}", "${project.x}",
    // "${property('x')}", "${rootProject.ext.x}", "${extra["x"]}" → "x". Null for literals.
    static String referenceName(String version) {
        if (version == null || !version.startsWith("$")) return null;
        String s = version.startsWith("${") && version.endsWith("}")
                ? version.substring(2, version.length() - 1).trim()
                : version.substring(1);
        s = s.replaceFirst("^(?:project|rootProject)\\.", "");
        Matcher call = Pattern.compile("^(?:(?:find)?[Pp]roperty|(?:ext|extra)\\.get)\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
                + "(?:\\s*as\\s+String)?$").matcher(s);
        if (call.matches()) return call.group(1);
        Matcher index = Pattern.compile("^(?:ext|extra)\\[\\s*[\"']([^\"']+)[\"']\\s*\\]$").matcher(s);
        if (index.matches()) return index.group(1);
        s = s.replaceFirst("^(?:ext|extra)\\.", "");
        return s.matches("[A-Za-z_][\\w.\\-]*") ? s : null;
    }

    // Literal definition of property `name` in the script, or null.
    static Definition definition(GradleScript script, String name) {
        String q = Pattern.quote(name);
        List<Pattern> patterns = List.of(
                Pattern.compile("(?<![\\w.$])(?:(?:project\\.|rootProject\\.)?ext\\.|def\\s+|val\\s+|var\\s+|String\\s+)?"
                        + q + "\\s*(?::\\s*String\\s*)?=\\s*([\"'])([^\"'$\\n]*)\\1"),
                Pattern.compile("\\bextra\\[\\s*\"" + q + "\"\\s*\\]\\s*=\\s*(\")([^\"$\\n]*)\""),
                Pattern.compile("\\bset\\(\\s*[\"']" + q + "[\"']\\s*,\\s*([\"'])([^\"'$\\n]*)\\1\\s*\\)"),
                Pattern.compile("\\bval\\s+" + q + "\\s+by\\s+extra\\(\\s*(\")([^\"$\\n]*)\"\\s*\\)"));
        for (Pattern p : patterns) {
            Matcher m = p.matcher(script.text);
            while (m.find()) {
                if (script.isStringStart(m.start(1)) && script.isCode(m.start())) {
                    return new Definition(m.group(2), m.start(2), m.end(2));
                }
            }
        }
        return null;
    }
}
