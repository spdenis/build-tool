package com.example.multibuild.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A Gradle version catalog (gradle/libs.versions.toml): read for dependency resolution and version
// updates, and written by the Maven migration. Supports the notations Gradle documents for [versions]
// and [libraries]:
//   spring = "6.1.5"
//   spring-context = { module = "org.springframework:spring-context", version.ref = "spring" }
//   guava = { group = "com.google.guava", name = "guava", version = "33.0.0-jre" }
//   jackson-bom = "com.fasterxml.jackson:jackson-bom:2.17.0"
// Rich versions ({ strictly = ... }) are read as their first literal and never rewritten.
public final class VersionCatalog {

    public static final String FILE = "gradle/libs.versions.toml";

    // A library entry. version is the literal version, or null when versionRef is set or the library is
    // versionless; [versionStart, versionEnd) locates an editable literal version in the file (-1 if none).
    public record Library(String alias, String group, String artifact, String version, String versionRef,
                          int versionStart, int versionEnd) {
        public String key() { return group + ":" + artifact; }
    }

    // A [versions] entry with the location of its value.
    public record Version(String name, String value, int valueStart, int valueEnd) {}

    private static final Pattern SECTION = Pattern.compile("^\\s*\\[([\\w.-]+)]\\s*(?:#.*)?$");
    private static final Pattern ENTRY = Pattern.compile("^\\s*([A-Za-z][\\w.-]*|\"[^\"]+\")\\s*=\\s*(.+?)\\s*(?:#[^\"]*)?$");
    private static final Pattern STRING = Pattern.compile("^\"([^\"]*)\"");
    private static final Pattern FIELD = Pattern.compile("([\\w.]+)\\s*=\\s*(\"([^\"]*)\"|\\{[^}]*})");

    public final Path file;
    public final String text;
    public final Map<String, Version> versions = new LinkedHashMap<>();
    public final Map<String, Library> libraries = new LinkedHashMap<>();

    private VersionCatalog(Path file, String text) {
        this.file = file;
        this.text = text;
        parse();
    }

    // The catalog of a build rooted at buildRoot, or null when it has none.
    public static VersionCatalog read(Path buildRoot) {
        Path file = buildRoot.resolve(FILE);
        if (!Files.isRegularFile(file)) return null;
        try {
            return new VersionCatalog(file, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    static VersionCatalog parse(String text) {
        return new VersionCatalog(null, text);
    }

    private void parse() {
        String section = "";
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            int lineStart = offset;
            offset += line.length() + 1;
            Matcher s = SECTION.matcher(line);
            if (s.matches()) {
                section = s.group(1);
                continue;
            }
            Matcher e = ENTRY.matcher(line);
            if (!e.matches()) continue;
            String name = e.group(1).replace("\"", "");
            String value = e.group(2);
            int valueStart = lineStart + e.start(2);
            if (section.equals("versions")) {
                Matcher str = STRING.matcher(value);
                if (str.find()) {
                    versions.put(name, new Version(name, str.group(1), valueStart + 1, valueStart + 1 + str.group(1).length()));
                } else {
                    Matcher rich = Pattern.compile("\"([^\"]*)\"").matcher(value);
                    if (rich.find()) versions.put(name, new Version(name, rich.group(1), -1, -1));
                }
            } else if (section.equals("libraries")) {
                Library lib = library(name, value, valueStart);
                if (lib != null) libraries.put(name, lib);
            }
        }
    }

    private static Library library(String alias, String value, int valueStart) {
        Matcher str = STRING.matcher(value);
        if (str.find()) {
            String[] parts = str.group(1).split(":");
            if (parts.length < 2) return null;
            if (parts.length == 2) return new Library(alias, parts[0], parts[1], null, null, -1, -1);
            int vs = valueStart + 1 + parts[0].length() + 1 + parts[1].length() + 1;
            return new Library(alias, parts[0], parts[1], parts[2], null, vs, vs + parts[2].length());
        }
        if (!value.startsWith("{")) return null;
        String group = null;
        String artifact = null;
        String version = null;
        String versionRef = null;
        int vs = -1;
        int ve = -1;
        Matcher f = FIELD.matcher(value);
        while (f.find()) {
            String key = f.group(1);
            String literal = f.group(3);
            switch (key) {
                case "module" -> {
                    if (literal != null && literal.contains(":")) {
                        group = literal.substring(0, literal.indexOf(':'));
                        artifact = literal.substring(literal.indexOf(':') + 1);
                    }
                }
                case "group" -> group = literal;
                case "name" -> artifact = literal;
                case "version.ref" -> versionRef = literal;
                case "version" -> {
                    if (literal != null) {
                        version = literal;
                        vs = valueStart + f.start(3);
                        ve = valueStart + f.end(3);
                    } else {
                        Matcher rich = Pattern.compile("\"([^\"]*)\"").matcher(f.group(2));
                        if (rich.find()) version = rich.group(1);
                    }
                }
                default -> { }
            }
        }
        if (group == null || artifact == null) return null;
        return new Library(alias, group, artifact, version, versionRef, vs, ve);
    }

    // Effective version of a library (its literal, or the referenced [versions] entry).
    public String versionOf(Library lib) {
        if (lib.versionRef() != null) {
            Version v = versions.get(lib.versionRef());
            return v != null ? v.value() : null;
        }
        return lib.version();
    }

    // Library behind a script accessor ("libs.spring.context", "libs.jackson.asProvider()") or a
    // findLibrary("spring-context") alias. Gradle treats '-', '_' and '.' in aliases as the same separator.
    public Library byAccessor(String accessor) {
        String normalized = normalize(accessor);
        for (Library lib : libraries.values()) {
            if (normalize(lib.alias()).equals(normalized)) return lib;
        }
        return null;
    }

    static String normalize(String alias) {
        return alias.replaceAll("[-_.]", ".").toLowerCase(Locale.ROOT);
    }

    // ── editing ────────────────────────────────────────────────────────────

    // Sets the version of every library with the given key; versions shared through version.ref are
    // rewritten once. Returns the updated text, or the same text when nothing changed.
    public String withVersion(String key, String newVersion) {
        List<int[]> ranges = new ArrayList<>();
        for (Library lib : libraries.values()) {
            if (!lib.key().equals(key)) continue;
            if (lib.versionRef() != null) {
                Version v = versions.get(lib.versionRef());
                if (v != null && v.valueStart() >= 0 && !v.value().equals(newVersion)) {
                    ranges.add(new int[]{v.valueStart(), v.valueEnd()});
                }
            } else if (lib.versionStart() >= 0 && !newVersion.equals(lib.version())) {
                ranges.add(new int[]{lib.versionStart(), lib.versionEnd()});
            }
        }
        ranges.sort((a, b) -> Integer.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder(text);
        int last = Integer.MAX_VALUE;
        for (int[] r : ranges) {
            if (r[0] == last) continue;     // two libraries sharing one version.ref
            sb.replace(r[0], r[1], newVersion);
            last = r[0];
        }
        return sb.toString();
    }

    // ── writing (migration) ────────────────────────────────────────────────

    // Accumulates catalog entries while a build is converted and renders the TOML file.
    static final class Builder {
        private final Map<String, String> versionValues = new LinkedHashMap<>();
        private final Map<String, String[]> libs = new LinkedHashMap<>();   // alias → {group, artifact, version, versionRef}
        private final Map<String, String> aliasBySpec = new LinkedHashMap<>();

        // Registers [versions] entry `preferred` (adjusted when taken by another value); returns its name.
        String version(String preferred, String value) {
            String name = preferred;
            for (int i = 2; versionValues.containsKey(name) && !versionValues.get(name).equals(value); i++) {
                name = preferred + "-" + i;
            }
            versionValues.put(name, value);
            return name;
        }

        // Registers a library and returns its alias. A library is identified by group, artifact and how its
        // version is expressed; the alias derives from the artifactId, prefixed with the group on clashes.
        String library(String group, String artifact, String version, String versionRef) {
            String spec = group + ":" + artifact + "|" + version + "|" + versionRef;
            String known = aliasBySpec.get(spec);
            if (known != null) return known;
            String alias = null;
            for (String candidate : aliasCandidates(group, artifact, version)) {
                String[] taken = libs.get(candidate);
                if (taken == null && libs.keySet().stream().noneMatch(a -> normalize(a).equals(normalize(candidate)))) {
                    alias = candidate;
                    break;
                }
            }
            if (alias == null) {
                String base = aliasCandidates(group, artifact, version).get(0);
                alias = base;
                for (int i = 2; libs.containsKey(alias); i++) alias = base + "-v" + i;
            }
            libs.put(alias, new String[]{group, artifact, version, versionRef});
            aliasBySpec.put(spec, alias);
            return alias;
        }

        private static List<String> aliasCandidates(String group, String artifact, String version) {
            List<String> result = new ArrayList<>();
            String[] groupParts = group.split("\\.");
            String last = groupParts[groupParts.length - 1];
            result.add(sanitize(artifact));
            result.add(sanitize(last + "-" + artifact));
            result.add(sanitize(group + "-" + artifact));
            if (version != null) result.add(sanitize(group + "-" + artifact + "-" + version));
            return result;
        }

        // Valid catalog alias: lowercase segments starting with a letter, no reserved names.
        static String sanitize(String raw) {
            List<String> segments = new ArrayList<>();
            for (String s : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (s.isEmpty()) continue;
                if (Character.isDigit(s.charAt(0)) && !segments.isEmpty()) {
                    segments.set(segments.size() - 1, segments.get(segments.size() - 1) + s);
                } else {
                    segments.add(Character.isDigit(s.charAt(0)) ? "lib" + s : s);
                }
            }
            if (segments.isEmpty()) segments.add("lib");
            if (List.of("bundles", "versions", "plugins").contains(segments.get(0))) segments.add(0, "lib");
            segments.replaceAll(s -> List.of("extensions", "class", "convention").contains(s) ? s + "x" : s);
            return String.join("-", segments);
        }

        boolean hasVersion(String alias) {
            String[] l = libs.get(alias);
            return l != null && (l[2] != null || l[3] != null);
        }

        String versionOf(String alias) {
            String[] l = libs.get(alias);
            if (l == null) return null;
            return l[3] != null ? versionValues.get(l[3]) : l[2];
        }

        boolean isEmpty() {
            return libs.isEmpty() && versionValues.isEmpty();
        }

        // Accessor of an alias in build scripts: libs.spring.context, or .asProvider() when the alias is
        // also the prefix of another alias (libs.jackson.asProvider() next to libs.jackson.databind).
        String accessor(String alias) {
            String dotted = alias.replace('-', '.');
            boolean prefix = libs.keySet().stream().anyMatch(a -> !a.equals(alias) && a.startsWith(alias + "-"));
            return "libs." + dotted + (prefix ? ".asProvider()" : "");
        }

        String render(String header) {
            StringBuilder sb = new StringBuilder("# ").append(header).append('\n');
            sb.append("# Versions shared by the build scripts and build-logic; edit them here only.\n");
            if (!versionValues.isEmpty()) {
                sb.append("\n[versions]\n");
                versionValues.forEach((k, v) -> sb.append(k).append(" = ").append(quote(v)).append('\n'));
            }
            if (!libs.isEmpty()) {
                sb.append("\n[libraries]\n");
                libs.forEach((alias, l) -> {
                    sb.append(alias).append(" = { module = ").append(quote(l[0] + ":" + l[1]));
                    if (l[3] != null) sb.append(", version.ref = ").append(quote(l[3]));
                    else if (l[2] != null) sb.append(", version = ").append(quote(l[2]));
                    sb.append(" }\n");
                });
            }
            return sb.toString();
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
