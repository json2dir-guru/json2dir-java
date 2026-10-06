// json2dir: create the directory tree a JSON document describes, in the current directory.
// Implements RFC J2D-1 (https://github.com/kitsunoff/awesome-json2dir/blob/main/spec/rfc-json2dir.md).
// JDK only: the JSON parser is hand-written, because the JDK has none.

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Json2dir {
    static class Fail extends RuntimeException {
        Fail(String message) { super(message); }
    }

    // Values that have no meaning in json2dir; kept only to be reported by validate().
    record Other(String what) {}

    // ---- §3: strict UTF-8 and strict JSON ----

    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new Fail("input is not valid UTF-8");
        }
    }

    static class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
            // A leading byte order mark is ignored, as §3 allows.
            if (s.startsWith("﻿")) i = 1;
        }

        Fail error(String what) { return new Fail("input is not valid JSON: " + what + " at offset " + i); }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object document() {
            ws();
            Object v = value();
            ws();
            if (i != s.length()) throw error("unexpected data after the value");
            return v;
        }

        Object value() {
            if (i >= s.length()) throw error("unexpected end of input");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': literal("true"); return new Other("true");
                case 'f': literal("false"); return new Other("false");
                case 'n': literal("null"); return new Other("null");
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) { number(); return new Other("number"); }
                    throw error("unexpected character");
            }
        }

        void literal(String word) {
            if (!s.startsWith(word, i)) throw error("invalid literal");
            i += word.length();
        }

        void number() {
            if (s.charAt(i) == '-') i++;
            if (i < s.length() && s.charAt(i) == '0') i++;
            else if (!digits()) throw error("invalid number");
            if (i < s.length() && s.charAt(i) == '.') { i++; if (!digits()) throw error("invalid number"); }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (!digits()) throw error("invalid number");
            }
        }

        boolean digits() {
            int start = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            return i > start;
        }

        Map<String, Object> object() {
            i++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return map; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw error("expected a member name");
                String name = string();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw error("expected ':'");
                i++;
                ws();
                Object v = value();
                map.remove(name); // duplicates: the last occurrence wins
                map.put(name, v);
                ws();
                if (i >= s.length()) throw error("unexpected end of input");
                char c = s.charAt(i++);
                if (c == '}') return map;
                if (c != ',') { i--; throw error("expected ',' or '}'"); }
            }
        }

        List<Object> array() {
            i++; // [
            List<Object> list = new ArrayList<>();
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return list; }
            while (true) {
                ws();
                list.add(value());
                ws();
                if (i >= s.length()) throw error("unexpected end of input");
                char c = s.charAt(i++);
                if (c == ']') return list;
                if (c != ',') { i--; throw error("expected ',' or ']'"); }
            }
        }

        String string() {
            i++; // opening quote
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c < 0x20) { i--; throw error("control character in string"); }
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw error("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw error("invalid \\u escape");
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int d = Character.digit(s.charAt(i + k), 16);
                            if (d < 0 || s.charAt(i + k) > 'f') throw error("invalid \\u escape");
                            code = code * 16 + d;
                        }
                        i += 4;
                        b.append((char) code);
                        break;
                    default: i--; throw error("invalid escape");
                }
            }
            String r = b.toString();
            // §3.5: an escape that denotes an unpaired surrogate cannot be encoded as UTF-8.
            for (int k = 0; k < r.length(); k++) {
                char c = r.charAt(k);
                if (Character.isHighSurrogate(c) && k + 1 < r.length() && Character.isLowSurrogate(r.charAt(k + 1))) k++;
                else if (Character.isSurrogate(c)) throw new Fail("input contains an unpaired surrogate");
            }
            return r;
        }
    }

    // ---- §4, §6: validate the whole document before touching the file system ----

    static String quote(String name) { return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @SuppressWarnings("unchecked")
    static void validate(Object value, String where) {
        if (value instanceof String) return;
        if (value instanceof List<?> list) {
            if (list.size() != 2 || !(list.get(0) instanceof String kind) || !(list.get(1) instanceof String payload))
                throw new Fail(where + ": an array must be [\"link\", target] or [\"script\", content]");
            if (!kind.equals("link") && !kind.equals("script"))
                throw new Fail(where + ": unknown array kind " + quote(kind));
            if (kind.equals("link") && payload.indexOf('\0') >= 0)
                throw new Fail(where + ": a link target cannot contain NUL");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) map).entrySet()) {
                String name = e.getKey();
                String child = where.equals(".") ? name : where + "/" + name;
                // §4.2.1: names are used exactly; trailing "/" or "/." forms are rejected, not trimmed.
                if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\0') >= 0)
                    throw new Fail(child + ": invalid name " + quote(name));
                validate(e.getValue(), child);
            }
            return;
        }
        throw new Fail(where + ": " + ((Other) value).what() + " values are not allowed");
    }

    // ---- §5: create the tree ----

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static BasicFileAttributes lstat(Path p) throws IOException {
        try {
            return Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    // §5.2, §5.3: an existing non-directory is removed (a symlink itself, never its target);
    // §5.4: a directory in the way of a non-object is an error.
    static void clear(Path p, BasicFileAttributes existing) throws IOException {
        if (existing == null) return;
        if (existing.isDirectory()) throw new Fail(p + ": a directory is in the way");
        Files.delete(p);
    }

    static void writeFile(Path p, String content, boolean executable) throws IOException {
        // CREATE_NEW = O_CREAT | O_EXCL, mode 0666 & ~umask.
        try (SeekableByteChannel ch = Files.newByteChannel(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(utf8(content));
            while (buf.hasRemaining()) ch.write(buf);
        }
        if (executable) {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        }
    }

    @SuppressWarnings("unchecked")
    static void apply(Path dir, Map<String, Object> tree) throws IOException {
        // Members in ascending order of the UTF-8 bytes of their names, like the reference.
        List<String> names = new ArrayList<>(tree.keySet());
        names.sort((a, b) -> Arrays.compareUnsigned(utf8(a), utf8(b)));
        for (String name : names) {
            Path p = dir.resolve(name);
            Object value = tree.get(name);
            BasicFileAttributes existing = lstat(p);
            if (value instanceof String content) {
                clear(p, existing);
                writeFile(p, content, false);
            } else if (value instanceof List<?> list) {
                clear(p, existing);
                if (list.get(0).equals("link")) Files.createSymbolicLink(p, Path.of((String) list.get(1)));
                else writeFile(p, (String) list.get(1), true);
            } else {
                if (existing == null || !existing.isDirectory()) {
                    if (existing != null) Files.delete(p);
                    Files.createDirectory(p);
                }
                apply(p, (Map<String, Object>) value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        if (args.length > 0) {
            System.err.println("usage: json2dir < document.json");
            System.exit(2);
        }
        try {
            Object document = new Parser(decode(System.in.readAllBytes())).document();
            if (!(document instanceof Map)) throw new Fail("the root of the document must be an object");
            validate(document, ".");
            apply(Path.of(""), (Map<String, Object>) document);
            System.exit(0);
        } catch (Fail e) {
            System.err.println("json2dir: " + e.getMessage());
        } catch (StackOverflowError e) {
            System.err.println("json2dir: the document is nested too deeply");
        } catch (Exception e) {
            System.err.println("json2dir: " + e);
        }
        System.exit(1);
    }
}
