package why.lang;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import why.AllocationLimits;

/**
 * Parses one {@code multipart/form-data} body into text fields and uploaded files.
 *
 * <p>Text parts become {@code form} fields, so a multipart form is a normal form with files added.
 * A file part with an empty filename — what a browser sends for an untouched file input — is
 * skipped, which leaves {@code files.avatar} nil instead of an empty upload.
 */
public final class Multipart {
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};
    private static final byte[] CRLF = {'\r', '\n'};

    /** The parts of one body: text fields by name and uploads by name. */
    public record Result(Map<String, String> fields, Map<String, Upload> files) {}

    private Multipart() {}

    /**
     * Parses a body. The base folder confines {@link Upload#save(String, int)} to a site.
     *
     * @throws IllegalArgumentException when the body is not well-formed multipart data
     */
    public static Result parse(byte[] body, String contentType, Path base) {
        String boundary = boundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("multipart body needs a boundary");
        }
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] separator = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        if (!startsWith(body, 0, delimiter)) {
            throw new IllegalArgumentException("multipart body must start with its boundary");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, Upload> files = new LinkedHashMap<>();

        int parts = 0;
        int at = 0;
        while (true) {
            int cursor = at + delimiter.length;
            if (cursor + 1 < body.length && body[cursor] == '-' && body[cursor + 1] == '-') break;
            if (!startsWith(body, cursor, CRLF)) {
                throw new IllegalArgumentException("malformed multipart part");
            }
            cursor += CRLF.length;
            int headerEnd = indexOf(body, HEADER_END, cursor);
            if (headerEnd < 0) throw new IllegalArgumentException("multipart part has no headers");
            String headers = new String(body, cursor, headerEnd - cursor, StandardCharsets.UTF_8);
            int contentStart = headerEnd + HEADER_END.length;
            int next = indexOf(body, separator, contentStart);
            if (next < 0) throw new IllegalArgumentException("multipart part is unterminated");
            if (++parts > AllocationLimits.UPLOAD_PARTS) {
                throw new IllegalArgumentException("multipart body has more than "
                        + AllocationLimits.UPLOAD_PARTS + " parts");
            }

            String disposition = header(headers, "content-disposition");
            String name = parameter(disposition, "name");
            String filename = parameter(disposition, "filename");
            if (name != null && !name.isEmpty()) {
                byte[] content = Arrays.copyOfRange(body, contentStart, next);
                if (filename == null) {
                    fields.put(name, new String(content, StandardCharsets.UTF_8));
                } else if (!filename.isEmpty()) {
                    String type = header(headers, "content-type");
                    files.put(name, new Upload(filename,
                            type == null || type.isBlank()
                                    ? "application/octet-stream" : type.strip(),
                            content, base));
                }
            }
            at = next + CRLF.length;
        }
        return new Result(fields, files);
    }

    private static String boundary(String contentType) {
        String value = parameter(contentType, "boundary");
        if (value == null || value.isEmpty() || value.length() > 70) return null;
        return value;
    }

    /** The first value of a header line, or null. Header names compare case-insensitively. */
    private static String header(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            if (line.substring(0, colon).strip().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).strip();
            }
        }
        return null;
    }

    /**
     * One {@code name=value} parameter. A bare word such as {@code form-data} never matches, and
     * neither does {@code name} inside {@code filename}.
     */
    private static String parameter(String header, String key) {
        if (header == null) return null;
        String lower = header.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from < lower.length()) {
            int at = lower.indexOf(key, from);
            if (at < 0) return null;
            from = at + key.length();
            boolean starts = at == 0 || header.charAt(at - 1) == ';' || header.charAt(at - 1) == ' ';
            if (!starts || from >= lower.length() || header.charAt(from) != '=') continue;
            int valueStart = from + 1;
            if (valueStart < lower.length() && header.charAt(valueStart) == '"') {
                int end = header.indexOf('"', valueStart + 1);
                return end < 0 ? header.substring(valueStart + 1) : header.substring(valueStart + 1, end);
            }
            int end = header.indexOf(';', valueStart);
            return (end < 0 ? header.substring(valueStart) : header.substring(valueStart, end)).strip();
        }
        return null;
    }

    private static boolean startsWith(byte[] body, int at, byte[] prefix) {
        if (at < 0 || at + prefix.length > body.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (body[at + i] != prefix[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
