package com.example.multibuild.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Lexical view of a Gradle build script (Groovy or Kotlin DSL) for regex-based reading and
// in-place editing. Offsets always refer to the original text, so edits preserve formatting.
//
// `code` is the text with comments and string-literal contents blanked out (quotes kept), which
// makes keyword searches and brace matching immune to braces/keywords inside strings or comments.
final class GradleScript {

    final Path file;
    final String text;
    final String code;
    // Offsets of the opening quote of every real string literal (not inside a comment).
    private final Set<Integer> stringStarts = new HashSet<>();

    GradleScript(Path file, String text) {
        this.file = file;
        this.text = text;
        this.code = mask(text);
    }

    static GradleScript read(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            return new GradleScript(file, Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + file, e);
        }
    }

    boolean isKotlin() {
        return file != null && file.getFileName().toString().endsWith(".kts");
    }

    // True when pos is the opening quote of a string literal in code (not in a comment or another string).
    boolean isStringStart(int pos) {
        return stringStarts.contains(pos);
    }

    // True when the (non-whitespace) character at pos is code, not part of a comment or string body.
    boolean isCode(int pos) {
        return pos >= 0 && pos < text.length() && !Character.isWhitespace(text.charAt(pos))
                && code.charAt(pos) == text.charAt(pos);
    }

    // Body ranges [start, end) of every `name { ... }` block, at any nesting depth.
    List<int[]> blocks(String name) {
        List<int[]> result = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*\\{").matcher(code);
        while (m.find()) {
            int open = m.end() - 1;
            int close = matchingBrace(open);
            result.add(new int[]{open + 1, close});
        }
        return result;
    }

    // `configure([project(':a'), project(':b')]) { ... }` / `configure(listOf(project(":a"))) { ... }`:
    // the project paths it targets and the block body range.
    record TargetedBlock(Set<String> paths, int[] range) {}

    private static final Pattern PROJECT_REF = Pattern.compile("project\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");

    List<TargetedBlock> configureBlocks() {
        List<TargetedBlock> result = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![\\w$.])configure\\s*\\(").matcher(code);
        while (m.find()) {
            int open = m.end() - 1;
            int depth = 0;
            int close = -1;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) { close = i; break; }
            }
            if (close < 0) continue;
            int brace = close + 1;
            while (brace < code.length() && Character.isWhitespace(code.charAt(brace))) brace++;
            if (brace >= code.length() || code.charAt(brace) != '{') continue;
            Set<String> paths = new LinkedHashSet<>();
            Matcher pm = PROJECT_REF.matcher(text.substring(open, close));
            while (pm.find()) {
                String path = pm.group(1);
                paths.add(path.startsWith(":") ? path : ":" + path);
            }
            if (!paths.isEmpty()) result.add(new TargetedBlock(paths, new int[]{brace + 1, matchingBrace(brace)}));
        }
        return result;
    }

    // Offset of the brace closing the one at `open` (text length when unbalanced).
    int matchingBrace(int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return code.length();
    }

    static boolean within(List<int[]> ranges, int pos) {
        for (int[] r : ranges) {
            if (pos >= r[0] && pos < r[1]) return true;
        }
        return false;
    }

    // Ranges of the whole text minus `excluded` ranges.
    List<int[]> complementOf(List<int[]> excluded) {
        List<int[]> sorted = new ArrayList<>(excluded);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> result = new ArrayList<>();
        int pos = 0;
        for (int[] r : sorted) {
            if (r[0] > pos) result.add(new int[]{pos, r[0]});
            pos = Math.max(pos, r[1]);
        }
        if (pos < text.length()) result.add(new int[]{pos, text.length()});
        return result;
    }

    // ── masking ──────────────────────────────────────────────────────────────

    private String mask(String s) {
        char[] out = s.toCharArray();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                int end = s.indexOf('\n', i);
                if (end < 0) end = n;
                blank(out, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                blank(out, i, end);
                i = end;
            } else if ((c == '"' || c == '\'') && s.startsWith(String.valueOf(c).repeat(3), i)) {
                String delim = String.valueOf(c).repeat(3);
                int end = s.indexOf(delim, i + 3);
                end = end < 0 ? n : end;
                stringStarts.add(i);
                blank(out, i + 3, end);
                i = Math.min(n, end + 3);
            } else if (c == '"' || c == '\'') {
                int end = stringEnd(s, i);
                stringStarts.add(i);
                blank(out, i + 1, Math.min(end, n));
                i = Math.min(n, end + 1);
            } else {
                i++;
            }
        }
        return new String(out);
    }

    // Offset of the closing quote of the string literal opening at `start`.
    private static int stringEnd(String s, int start) {
        char quote = s.charAt(start);
        int j = start + 1;
        while (j < s.length()) {
            char ch = s.charAt(j);
            if (ch == '\\') {
                j += 2;
            } else if (ch == quote || ch == '\n') {
                return j;
            } else if (quote == '"' && ch == '$' && j + 1 < s.length() && s.charAt(j + 1) == '{') {
                j = interpolationEnd(s, j + 2);
            } else {
                j++;
            }
        }
        return s.length();
    }

    // Offset just past the '}' closing a ${...} interpolation whose body starts at `from`.
    private static int interpolationEnd(String s, int from) {
        int depth = 1;
        int k = from;
        while (k < s.length() && depth > 0) {
            char ch = s.charAt(k);
            if (ch == '"' || ch == '\'') {
                k = stringEnd(s, k) + 1;
                continue;
            }
            if (ch == '{') depth++;
            else if (ch == '}') depth--;
            k++;
        }
        return k;
    }

    private static void blank(char[] out, int from, int to) {
        for (int k = from; k < to && k < out.length; k++) {
            if (out[k] != '\n') out[k] = ' ';
        }
    }
}
