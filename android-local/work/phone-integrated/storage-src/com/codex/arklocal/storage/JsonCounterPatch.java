package com.codex.arklocal.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Change only requested integer lexemes, preserving all other JSON text, including
 * large progress integers that org.json might otherwise round during serialization. */
final class JsonCounterPatch {
    private JsonCounterPatch() { }
    private static final class Range {
        final int start, end;
        Range(int start, int end) { this.start = start; this.end = end; }
    }

    static String set(String json, String section, String[] names, long[] values, boolean requireExisting) {
        if (names.length != values.length) throw bad();
        Parser parser = new Parser(json);
        Map<String, Range> root = parser.members(0);
        parser.space();
        if (parser.pos != json.length()) throw bad();
        Range node = root.get(section);
        if (node == null || json.charAt(node.start) != '{') throw bad();
        parser.pos = node.start;
        Map<String, Range> fields = parser.members(0);
        List<Range> edits = new ArrayList<Range>();
        List<String> replacements = new ArrayList<String>();
        StringBuilder added = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (!names[i].matches("[A-Za-z0-9_]+") || values[i] < 0) throw bad();
            Range field = fields.get(names[i]);
            if (field == null) {
                if (requireExisting) throw bad();
                if (!fields.isEmpty() || added.length() != 0) added.append(',');
                added.append('"').append(names[i]).append("\":").append(values[i]);
            } else {
                // Counter editing may not silently replace a structured or string value.
                if (!json.substring(field.start, field.end).matches("-?(?:0|[1-9][0-9]*)")) throw bad();
                edits.add(field);
                replacements.add(Long.toString(values[i]));
            }
        }
        if (added.length() != 0) {
            edits.add(new Range(node.end - 1, node.end - 1));
            replacements.add(added.toString());
        }
        StringBuilder output = new StringBuilder(json);
        // Descending edits keep all original offsets valid. There are at most five.
        while (!edits.isEmpty()) {
            int last = 0;
            for (int i = 1; i < edits.size(); i++) if (edits.get(i).start > edits.get(last).start) last = i;
            Range range = edits.remove(last);
            output.replace(range.start, range.end, replacements.remove(last));
        }
        return output.toString();
    }

    private static IllegalStateException bad() {
        return new IllegalStateException("Resource JSON is missing, ambiguous, or not an integer; no edit applied");
    }

    private static final class Parser {
        final String text;
        int pos;
        Parser(String text) { if (text == null) throw bad(); this.text = text; }
        void space() { while (pos < text.length() && " \r\n\t".indexOf(text.charAt(pos)) >= 0) pos++; }
        char peek() { space(); if (pos >= text.length()) throw bad(); return text.charAt(pos); }
        void take(char expected) { if (peek() != expected) throw bad(); pos++; }

        Map<String, Range> members(int depth) {
            if (depth > 128) throw bad();
            Map<String, Range> result = new LinkedHashMap<String, Range>();
            take('{');
            if (peek() == '}') { pos++; return result; }
            while (true) {
                String key = string();
                take(':');
                space();
                int start = pos;
                value(depth + 1);
                if (result.put(key, new Range(start, pos)) != null) throw bad();
                char delimiter = peek();
                pos++;
                if (delimiter == '}') return result;
                if (delimiter != ',') throw bad();
            }
        }

        void value(int depth) {
            if (depth > 128) throw bad();
            char first = peek();
            if (first == '{') { members(depth + 1); return; }
            if (first == '"') { string(); return; }
            if (first == '[') {
                pos++;
                if (peek() == ']') { pos++; return; }
                while (true) {
                    value(depth + 1);
                    char delimiter = peek();
                    pos++;
                    if (delimiter == ']') return;
                    if (delimiter != ',') throw bad();
                }
            }
            int start = pos;
            while (pos < text.length() && ",]} \r\n\t".indexOf(text.charAt(pos)) < 0) pos++;
            String token = text.substring(start, pos);
            if (!(token.equals("true") || token.equals("false") || token.equals("null")
                || token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))) throw bad();
        }

        String string() {
            take('"');
            StringBuilder result = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw bad();
                if (c == '\\') {
                    if (pos >= text.length()) throw bad();
                    char escape = text.charAt(pos++);
                    switch (escape) {
                        case '"': c = '"'; break;
                        case '\\': c = '\\'; break;
                        case '/': c = '/'; break;
                        case 'b': c = '\b'; break;
                        case 'f': c = '\f'; break;
                        case 'n': c = '\n'; break;
                        case 'r': c = '\r'; break;
                        case 't': c = '\t'; break;
                        case 'u':
                            if (pos + 4 > text.length()) throw bad();
                            for (int hex = pos; hex < pos + 4; hex++)
                                if (Character.digit(text.charAt(hex), 16) < 0) throw bad();
                            try { c = (char) Integer.parseInt(text.substring(pos, pos + 4), 16); }
                            catch (NumberFormatException e) { throw bad(); }
                            pos += 4;
                            break;
                        default: throw bad();
                    }
                }
                result.append(c);
            }
            throw bad();
        }
    }
}
