package com.example.multibuild.gradle;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads and edits gradle.properties files. Edits are line-based so comments, ordering and
// formatting of the rest of the file are preserved.
public final class GradleProperties {

    public static final String FILE_NAME = "gradle.properties";

    private GradleProperties() {}

    public static Map<String, String> read(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) return result;
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(file, StandardCharsets.UTF_8)));
            for (String name : p.stringPropertyNames()) result.put(name, p.getProperty(name).trim());
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }

    // Sets key=value, replacing the existing entry or appending a new one (creating the file if
    // needed). Returns true when the file changed.
    public static boolean set(Path file, String key, String value) {
        try {
            String original = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
            String updated = set(original, key, value);
            if (updated.equals(original)) return false;
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + file, e);
        }
    }

    static String set(String content, String key, String value) {
        Pattern entry = Pattern.compile("^(\\s*" + Pattern.quote(key) + "\\s*[=:]\\s*)(.*)$");
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = entry.matcher(lines.get(i));
            if (m.matches()) {
                found = true;
                if (!m.group(2).trim().equals(value)) {
                    lines.set(i, m.group(1) + value);
                }
            }
        }
        if (found) return String.join("\n", lines);
        String prefix = content.isEmpty() || content.endsWith("\n") ? content : content + "\n";
        return prefix + key + "=" + value + "\n";
    }
}
