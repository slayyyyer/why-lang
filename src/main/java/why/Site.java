package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import why.lang.Db;

/**
 * A site's pages and the rules for turning a request path into a file inside it.
 *
 * <p>A folder site reads from the filesystem directly. A packaged site is read in place: an
 * exploded site is used where it is, and a jar is opened as a zip filesystem, so nothing is
 * extracted to a temporary folder. A site that owns opened jars keeps them open until
 * {@link #close()}.
 *
 * <p>Files whose name starts with {@code _} are import-only and are never returned by
 * {@link #resolve(String)}. Only pages and allowlisted public assets are served; database, schema
 * and other project files stay private.
 */
public final class Site implements AutoCloseable {

    /** The page file extension. Defined once, here. */
    public static final String PAGE_EXTENSION = ".why";

    private static final String INDEX_PAGE = "index" + PAGE_EXTENSION;
    private static final Set<String> PUBLIC_EXTENSIONS = Set.of(
            ".html", ".htm", ".css", ".js", ".mjs", ".json", ".svg", ".png", ".jpg",
            ".jpeg", ".gif", ".webp", ".ico", ".woff2", ".txt");

    private final List<Path> roots;
    private final Path dbFile;
    private final List<FileSystem> filesystems;

    public Site(Path root) {
        this(root, root.resolve("data.db"));
    }

    public Site(Path root, Path dbFile) {
        this(List.of(root), dbFile, List.of());
    }

    private Site(List<Path> roots, Path dbFile, List<FileSystem> filesystems) {
        this.roots = roots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        this.dbFile = dbFile.toAbsolutePath().normalize();
        this.filesystems = List.copyOf(filesystems);
    }

    /**
     * Opens a site packaged by the Why Gradle plugin without extracting it. The default database is
     * {@code data.db} in the working directory; pass a file to
     * {@link #fromClasspath(ClassLoader, Path)} to put it elsewhere.
     */
    public static Site fromClasspath() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return fromClasspath(loader == null ? Site.class.getClassLoader() : loader, null);
    }

    /**
     * Opens a packaged site using an explicit class loader and database file. A jar on the
     * classpath is opened in place; close the returned site to release it.
     */
    public static Site fromClasspath(ClassLoader loader, Path dbFile) throws IOException {
        List<FileSystem> opened = new ArrayList<>();
        List<Path> roots;
        try {
            roots = ClasspathSite.open(loader == null ? Site.class.getClassLoader() : loader, opened);
        } catch (IOException | RuntimeException e) {
            closeAll(opened);
            throw e;
        }
        Site site = new Site(roots, dbFile == null ? Path.of("data.db") : dbFile, opened);
        try {
            site.bootstrap();
        } catch (IOException | RuntimeException e) {
            try {
                site.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        return site;
    }

    /** Applies {@code schema.sql} to a fresh database, then any migrations, wherever they live. */
    private void bootstrap() throws IOException {
        Path schema = first("schema.sql");
        boolean migrations = roots.stream()
                .anyMatch(root -> Files.isDirectory(root.resolve(Migrations.DIRECTORY)));
        if (!migrations && schema != null && !Files.exists(dbFile)) {
            Path parent = dbFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            try {
                Db.applyScript(dbFile, Files.readString(schema, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                throw new IOException("could not apply schema.sql: " + e.getMessage(), e);
            }
        }
        try {
            Migrations.apply(roots, dbFile);
        } catch (RuntimeException e) {
            throw new IOException("could not apply migrations: " + e.getMessage(), e);
        }
    }

    /** The first root that carries a file with this name, or null. */
    private Path first(String name) {
        for (Path root : roots) {
            Path candidate = root.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (int i = filesystems.size() - 1; i >= 0; i--) {
            FileSystem filesystem = filesystems.get(i);
            if (!filesystem.isOpen()) continue;
            try {
                filesystem.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        if (failure != null) throw failure;
    }

    private static void closeAll(List<FileSystem> filesystems) {
        for (int i = filesystems.size() - 1; i >= 0; i--) {
            try {
                filesystems.get(i).close();
            } catch (IOException ignored) {
                // Opening already failed; the original error is the useful one.
            }
        }
    }

    /** The first root, which is the site folder for a folder site. */
    public Path root() {
        return roots.get(0);
    }

    /** Every root, in classpath order. A folder site has exactly one. */
    public List<Path> roots() {
        return roots;
    }

    public Path dbFile() {
        return dbFile;
    }

    /** The page name as written in error messages: the path relative to its root. */
    public String relative(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (!normalized.getFileSystem().equals(root.getFileSystem())) continue;
            if (normalized.startsWith(root)) {
                return root.relativize(normalized).toString().replace('\\', '/');
            }
        }
        Path name = normalized.getFileName();
        return name == null ? normalized.toString() : name.toString();
    }

    public boolean isPage(Path file) {
        return file.getFileName().toString().endsWith(PAGE_EXTENSION);
    }

    /**
     * Maps a URL path to a file inside the site, or null when there is no such page,
     * the path would escape the site folder, or the file is not public.
     *
     * <p>{@code /} and directories map to {@code index.why}; {@code /about} also tries
     * {@code /about.why}, which is the extension-free URL.
     */
    public Path resolve(String urlPath) {
        String path = urlPath == null || urlPath.isEmpty() ? "/" : urlPath;
        if (!path.startsWith("/")) path = "/" + path;
        for (Path root : roots) {
            Path found = resolve(root, path);
            if (found != null) return found;
        }
        return null;
    }

    private Path resolve(Path root, String path) {
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
        if (found == null || isImportOnly(found) || !isInsideSite(found) || isDatabase(found)) return null;
        return isPage(found) || isPublicAsset(found) ? found : null;
    }

    /** Every page in the site, across all roots, in no particular order. */
    public List<Path> pages() throws IOException {
        List<Path> pages = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(this::isPage)
                        .filter(file -> !isImportOnly(file))
                        .forEach(pages::add);
            }
        }
        return pages;
    }

    /**
     * Whether the real file is still under one of the site's roots. This rejects symlinks and
     * symlinked directories that point outside the site.
     */
    public boolean isInsideSite(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (!normalized.getFileSystem().equals(root.getFileSystem())) continue;
            try {
                if (normalized.toRealPath().startsWith(root.toRealPath())) return true;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /** One source line for a diagnostic, or null when the path is outside the site or unreadable. */
    String sourceLine(String file, int line) {
        if (file == null || line <= 0) return null;
        for (Path root : roots) {
            Path path = root.resolve(file).normalize();
            if (!path.startsWith(root) || !isInsideSite(path) || !Files.isRegularFile(path)) continue;
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                return line <= lines.size() ? lines.get(line - 1) : null;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** A page by site-relative name, without the extension, or null when no root has it. */
    Path page(String name) {
        for (Path root : roots) {
            Path candidate = root.resolve(name + PAGE_EXTENSION).normalize();
            if (candidate.startsWith(root) && isInsideSite(candidate)
                    && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** The configured database is never a public asset, whatever its filename or extension. */
    private boolean isDatabase(Path file) {
        try {
            return file.toRealPath().equals(dbFile.toRealPath());
        } catch (IOException e) {
            return file.toAbsolutePath().normalize().equals(dbFile);
        }
    }

    /** Files whose name starts with {@code _} can be imported but have no URL. */
    public static boolean isImportOnly(Path file) {
        return file.getFileName().toString().startsWith("_");
    }

    /**
     * The static asset allowlist. Pages are handled separately. Dotfiles, database
     * files, schema files, backups and unknown extensions are not public.
     */
    public static boolean isPublicAsset(Path file) {
        String name = file.getFileName().toString();
        if (name.startsWith(".")) return false;
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return false;
        return PUBLIC_EXTENSIONS.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }
}
