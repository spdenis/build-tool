package com.example.multibuild.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Collects replacements of [start, end) ranges per script and writes them back in one pass.
final class ScriptEdits {

    private record Edit(int start, int end, String replacement) {}

    private final Map<Path, String> originals = new LinkedHashMap<>();
    private final Map<Path, List<Edit>> edits = new LinkedHashMap<>();

    void replace(GradleScript script, int start, int end, String replacement) {
        if (script.text.substring(start, end).equals(replacement)) return;
        originals.putIfAbsent(script.file, script.text);
        List<Edit> list = edits.computeIfAbsent(script.file, k -> new ArrayList<>());
        if (list.stream().noneMatch(e -> e.start() == start)) {
            list.add(new Edit(start, end, replacement));
        }
    }

    boolean isEmpty() {
        return edits.isEmpty();
    }

    // Writes all edited scripts. Returns the files written.
    List<Path> write() {
        List<Path> written = new ArrayList<>();
        for (Map.Entry<Path, List<Edit>> e : edits.entrySet()) {
            StringBuilder sb = new StringBuilder(originals.get(e.getKey()));
            e.getValue().stream()
                    .sorted((a, b) -> Integer.compare(b.start(), a.start()))
                    .forEach(edit -> sb.replace(edit.start(), edit.end(), edit.replacement()));
            try {
                Files.writeString(e.getKey(), sb.toString(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to write " + e.getKey(), ex);
            }
            written.add(e.getKey());
        }
        return written;
    }
}
