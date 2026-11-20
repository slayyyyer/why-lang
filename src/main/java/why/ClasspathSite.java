package why;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the site that the Why Gradle plugin packages under {@code META-INF/why-site}.
 *
 * <p>Nothing is copied. An indexed folder is used where it is, and an indexed jar is opened as a
 * zip filesystem whose {@code META-INF/why-site} directory becomes a site root. Multiple jars are
 * merged by page name; a duplicate path is an error. Every filesystem opened here is handed back so
 * the owning {@link Site} can close it.
 */
final class ClasspathSite {
    static final String INDEX = "META-INF/why-site.index";
    private static final String SITE_DIR = "why-site";
    private static final String ROOT_DIR = "META-INF/" + SITE_DIR;
    static final String ROOT = ROOT_DIR + "/";

    private ClasspathSite() {}

    static List<Path> open(ClassLoader loader, List<FileSystem> opened) throws IOException {
        if (loader == null) throw new IOException("no class loader to read the Why site from");
        Enumeration<URL> indexes = loader.getResources(INDEX);
        if (!indexes.hasMoreElements()) {
            throw new IOException("no Why site resources on the classpath; missing " + INDEX);
        }

        List<Path> roots = new ArrayList<>();
        Map<String, Path> seen = new HashMap<>();
        Map<Path, FileSystem> jars = new HashMap<>();
        while (indexes.hasMoreElements()) {
            URL index = indexes.nextElement();
            Path root = rootOf(index, jars, opened);
            if (roots.contains(root)) continue;
            for (String name : readIndex(indexPath(index, jars, opened))) {
                String normalized = normalize(name);
                Path previous = seen.putIfAbsent(normalized, root);
                if (previous != null) {
                    throw new IOException("duplicate Why site resource '" + name + "' on the classpath");
                }
                if (!Files.isRegularFile(root.resolve(normalized))) {
                    throw new IOException("missing Why site resource '" + ROOT + normalized + "'");
                }
            }
            roots.add(root);
        }
        return roots;
    }

    /** The site root for one index URL, opening its jar the first time it is seen. */
    private static Path rootOf(URL index, Map<Path, FileSystem> jars, List<FileSystem> opened)
            throws IOException {
        if ("file".equals(index.getProtocol())) {
            // The index sits beside its folder: META-INF/why-site.index -> META-INF/why-site.
            return path(index).getParent().resolve(SITE_DIR);
        }
        if ("jar".equals(index.getProtocol())) {
            FileSystem filesystem = fileSystem(index, jars, opened);
            return filesystem.getPath("/" + ROOT_DIR);
        }
        throw new IOException("unsupported Why site resource URL: " + index);
    }

    /** The index file inside the same root the site will be read from. */
    private static Path indexPath(URL index, Map<Path, FileSystem> jars, List<FileSystem> opened)
            throws IOException {
        if ("file".equals(index.getProtocol())) return path(index);
        if ("jar".equals(index.getProtocol())) {
            return fileSystem(index, jars, opened).getPath("/" + INDEX);
        }
        throw new IOException("unsupported Why site resource URL: " + index);
    }

    private static FileSystem fileSystem(URL index, Map<Path, FileSystem> jars, List<FileSystem> opened)
            throws IOException {
        JarURLConnection connection = (JarURLConnection) index.openConnection();
        connection.setUseCaches(false);
        Path jar = path(connection.getJarFileURL());
        FileSystem filesystem = jars.get(jar);
        if (filesystem != null) return filesystem;
        try {
            filesystem = FileSystems.newFileSystem(jar);
        } catch (IOException e) {
            throw new IOException("cannot open Why site jar " + jar + ": " + e.getMessage(), e);
        }
        jars.put(jar, filesystem);
        opened.add(filesystem);
        return filesystem;
    }

    /**
     * A site-relative name, normalized to forward slashes. Absolute paths, backslashes, drive
     * letters and paths that climb above the root are rejected before anything touches the site.
     */
    private static String normalize(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) {
            throw new IOException("unsafe Why site resource name '" + name + "'");
        }
        Path relative = Path.of(name).normalize();
        String normalized = relative.toString().replace('\\', '/');
        if (relative.isAbsolute() || normalized.isEmpty() || normalized.equals("..")
                || normalized.startsWith("../")) {
            throw new IOException("unsafe Why site resource name '" + name + "'");
        }
        return normalized;
    }

    private static List<String> readIndex(Path index) throws IOException {
        try (InputStream in = Files.newInputStream(index)) {
            return readIndex(in);
        }
    }

    private static List<String> readIndex(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String name = line.strip();
                if (!name.isEmpty()) names.add(name);
            }
        }
        return names;
    }

    private static Path path(URL url) throws IOException {
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IOException("invalid Why site resource URL: " + url, e);
        }
    }
}
