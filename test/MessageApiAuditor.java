import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Build-time guard against reintroducing the JOSM-crashing
 * {@code .message(title, tr(format, args))} idiom.
 *
 * <p>JOSM's {@code TestError.Builder.message(String, String, Object...)}
 * runs {@link java.text.MessageFormat} on its 2nd argument. Pre-substituting
 * via {@code tr(format, args)} produces a description string whose values
 * may contain literal '{' (e.g. tile-template URLs with {z}/{x}/{y}),
 * which then crashes {@code MessageFormat.<init>}. The correct idiom is
 * {@code .message(title, marktr(format), args)}, where args are passed
 * through to JOSM and inserted post-parse so braces in values are safe.
 *
 * <p>This auditor scans the two validator source files. It tokenises far
 * enough to skip Java string literals and char literals while balancing
 * parens, then for every {@code .message(...)} call inspects whether the
 * second argument starts with {@code tr(}. Any such site fails the build.
 *
 * <p>Run via {@code ant test}.
 */
public final class MessageApiAuditor {

    private static final String[] SOURCES = {
        "src/org/openstreetmap/josm/plugins/ohmtags/validation/TagConsistencyTest.java",
        "src/org/openstreetmap/josm/plugins/ohmtags/validation/DateTagTest.java",
    };

    public static void main(String[] args) throws Exception {
        List<String> violations = new ArrayList<>();
        int sites = 0;
        for (String src : SOURCES) {
            String text = Files.readString(Path.of(src));
            sites += scan(src, text, violations);
        }
        if (!violations.isEmpty()) {
            System.err.println("MessageApiAuditor: "
                + violations.size() + " misuse(s) of .message(title, tr(format, args)).");
            System.err.println("Use .message(title, marktr(format), args) instead so JOSM");
            System.err.println("controls MessageFormat parsing and brace-bearing values are safe.");
            System.err.println();
            for (String v : violations) {
                System.err.println("  " + v);
            }
            System.exit(1);
        }
        System.out.println("MessageApiAuditor: " + sites + " .message(...) sites scanned, no misuse found.");
    }

    private static int scan(String path, String src, List<String> violations) {
        int sites = 0;
        int idx = 0;
        while (true) {
            int call = indexOfMessageCall(src, idx);
            if (call < 0) break;
            int paren = src.indexOf('(', call);
            int close = findBalanced(src, paren);
            if (close < 0) break;
            sites++;
            String inner = src.substring(paren + 1, close);
            List<String> argSpans = splitTopLevelArgs(inner);
            if (argSpans.size() == 2) {
                String desc = argSpans.get(1).stripLeading();
                if (startsWithTrCall(desc)) {
                    violations.add(path + ":" + lineOf(src, call)
                        + " — .message(title, tr(format, args)) is a JOSM crash hazard");
                }
            }
            idx = close + 1;
        }
        return sites;
    }

    private static int indexOfMessageCall(String src, int from) {
        // Looks for the `.message(` token boundary, ignoring contents inside
        // string/char literals and comments.
        int i = from;
        while (i < src.length() - 9) {
            char c = src.charAt(i);
            if (c == '"') { i = skipString(src, i); continue; }
            if (c == '\'') { i = skipChar(src, i); continue; }
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '/') { while (i < src.length() && src.charAt(i) != '\n') i++; continue; }
                if (n == '*') { i = src.indexOf("*/", i + 2); if (i < 0) return -1; i += 2; continue; }
            }
            if (c == '.' && src.regionMatches(i + 1, "message", 0, 7)) {
                int j = i + 8;
                while (j < src.length() && Character.isWhitespace(src.charAt(j))) j++;
                if (j < src.length() && src.charAt(j) == '(') return i;
            }
            i++;
        }
        return -1;
    }

    private static int findBalanced(String src, int openParen) {
        int depth = 0;
        int i = openParen;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '"') { i = skipString(src, i); continue; }
            if (c == '\'') { i = skipChar(src, i); continue; }
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '/') { while (i < src.length() && src.charAt(i) != '\n') i++; continue; }
                if (n == '*') { i = src.indexOf("*/", i + 2); if (i < 0) return -1; i += 2; continue; }
            }
            if (c == '(') depth++;
            else if (c == ')') { depth--; if (depth == 0) return i; }
            i++;
        }
        return -1;
    }

    private static int skipString(String src, int start) {
        int i = start + 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"') return i + 1;
            i++;
        }
        return src.length();
    }

    private static int skipChar(String src, int start) {
        int i = start + 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '\'') return i + 1;
            i++;
        }
        return src.length();
    }

    private static List<String> splitTopLevelArgs(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < inner.length()) {
            char c = inner.charAt(i);
            if (c == '"') { i = skipString(inner, i); continue; }
            if (c == '\'') { i = skipChar(inner, i); continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        parts.add(inner.substring(start));
        return parts;
    }

    private static boolean startsWithTrCall(String desc) {
        // Match `tr(` as a standalone call, not e.g. `instr(` or `attr(`.
        if (!desc.startsWith("tr")) return false;
        int j = 2;
        while (j < desc.length() && Character.isWhitespace(desc.charAt(j))) j++;
        return j < desc.length() && desc.charAt(j) == '(';
    }

    private static int lineOf(String src, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (src.charAt(i) == '\n') line++;
        }
        return line;
    }

    private MessageApiAuditor() {}
}
