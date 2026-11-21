package why.lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import why.WhyError;

/**
 * One file from a {@code multipart/form-data} form, reached as {@code files.avatar} (or
 * {@code request.files.avatar}) on the page.
 *
 * <p>A page reads the original name with {@code filename}, the part's content type with
 * {@code type}, the byte count with {@code size} and the content with {@code text}.
 * {@code save(folder)} writes the bytes under the site folder and returns the name it chose, so
 * the page can build a URL such as {@code "/uploads/" + name}.
 */
public final class Upload {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String filename;
    private final String contentType;
    private final byte[] bytes;
    private final Path base;

    Upload(String filename, String contentType, byte[] bytes, Path base) {
        this.filename = baseName(filename);
        this.contentType = contentType;
        this.bytes = bytes;
        this.base = base;
    }

    /** The submitted name without any directory part; may be empty when the client sent none. */
    public String filename() {
        return filename;
    }

    /** The part's Content-Type, {@code application/octet-stream} when it sent none. */
    public String contentType() {
        return contentType;
    }

    public long size() {
        return bytes.length;
    }

    /** The bytes decoded as UTF-8, for text uploads such as CSV. */
    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Writes this upload into {@code folder}, relative to the site folder, and returns the name it
     * used. The folder is created when missing and may never leave the site, even through a link.
     */
    public String save(String folder, int line) {
        if (folder == null || folder.isBlank()) {
            throw new WhyError("upload.save needs a folder, as in upload.save(\"uploads\")", null, line);
        }
        Path root = base.toAbsolutePath().normalize();
        Path rootReal = realPath(root, folder, line);
        Path folderPath = root.resolve(folder).normalize();
        // Check the real location of the nearest folder that already exists before creating
        // anything, so a symlinked folder cannot carry the write outside the site. This also
        // rejects an absolute folder and a `..` that climbs out.
        Path existing = folderPath;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS) && existing.getParent() != null) {
            existing = existing.getParent();
        }
        if (!realPath(existing, folder, line).startsWith(rootReal)) {
            throw new WhyError("upload.save folder '" + folder + "' points outside the site", null, line);
        }
        try {
            Files.createDirectories(folderPath);
        } catch (IOException e) {
            throw new WhyError("upload.save cannot create '" + folder + "': " + e.getMessage(), null, line);
        }
        Path directory = realPath(folderPath, folder, line);
        if (!directory.startsWith(rootReal)) {
            throw new WhyError("upload.save folder '" + folder + "' points outside the site", null, line);
        }

        String name = generated();
        try {
            Files.write(directory.resolve(name), bytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new WhyError("upload.save cannot write '" + name + "': " + e.getMessage(), null, line);
        }
        return name;
    }

    /** The real location of a path, or a page error when it cannot be resolved. */
    private static Path realPath(Path path, String folder, int line) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new WhyError("upload.save cannot check '" + folder + "': " + e.getMessage(), null, line);
        }
    }

    /** A random name that keeps the upload's extension, which keeps it on the served allowlist. */
    private String generated() {
        byte[] random = new byte[8];
        RANDOM.nextBytes(random);
        return HexFormat.of().formatHex(random) + extension();
    }

    private String extension() {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) return "";
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (extension.length() > 10) return "";
        for (int i = 0; i < extension.length(); i++) {
            char c = extension.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return "";
        }
        return "." + extension;
    }

    private static String baseName(String name) {
        if (name == null) return "";
        String plain = name.replace('\\', '/');
        int slash = plain.lastIndexOf('/');
        return slash < 0 ? plain : plain.substring(slash + 1);
    }
}
