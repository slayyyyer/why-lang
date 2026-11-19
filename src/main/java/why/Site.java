package why;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A folder of pages, and the rules for turning a request path into a file inside it.
 * Files whose name starts with {@code _} are import-only and are never returned by
 * {@link #resolve(String)}.
 */
public final class Site {

    /** The page file extension. Defined once, here. */
    public static final String PAGE_EXTENSION = ".why";

    private static final String INDEX_PAGE = "index" + PAGE_EXTENSION;

    private final Path root;
    private final Path dbFile;

    public Site(Path root) {
        this(root, root.resolve("data.db"));
    }

    public Site(Path root, Path dbFile) {
        this.root = root.toAbsolutePath().normalize();
        this.dbFile = dbFile.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path dbFile() {
        return dbFile;
    }

    /** The page name as written in error messages: the path relative to the site root. */
    public String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public boolean isPage(Path file) {
        return file.getFileName().toString().endsWith(PAGE_EXTENSION);
    }

    /**
     * Maps a URL path to a file inside the site, or null when there is no such page
     * (or the path would escape the site folder).
     *
     * <p>{@code /} and directories map to {@code index.why}; {@code /about} also tries
     * {@code /about.why}, which is the old-school extension-free URL.
     */
    public Path resolve(String urlPath) {
        String path = urlPath == null || urlPath.isEmpty() ? "/" : urlPath;
        if (!path.startsWith("/")) path = "/" + path;

        Path candidate = root.resolve(path.substring(1)).normalize();
        if (!candidate.startsWith(root)) return null;

        if (Files.isDirectory(candidate)) candidate = candidate.resolve(INDEX_PAGE);
        Path found = Files.isRegularFile(candidate) ? candidate : null;
        if (found == null) {
            String name = candidate.getFileName().toString();
            if (!name.contains(".")) {
                Path withExtension = candidate.resolveSibling(name + PAGE_EXTENSION);
                if (Files.isRegularFile(withExtension)) found = withExtension;
            }
        }
        return found == null || isImportOnly(found) ? null : found;
    }

    /** Files whose name starts with {@code _} can be imported but have no URL. */
    public static boolean isImportOnly(Path file) {
        return file.getFileName().toString().startsWith("_");
    }
}
