package why;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import why.lang.Db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClasspathSiteTest {

    @TempDir
    Path dir;

    @Test
    void readsSiteResourcesFromTheClasspath() throws Exception {
        try (Site site = Site.fromClasspath(Site.class.getClassLoader(), dir.resolve("classpath.db"))) {
            assertEquals("file", site.root().getFileSystem().provider().getScheme());
            assertEquals("<p>classpath site</p>\n", render(site));
            assertEquals("body{color:blue}\n",
                    Files.readString(site.root().resolve("style.css")).replace("\r\n", "\n"));
        }
    }

    @Test
    void readsAJarSiteInPlaceWithoutExtractingIt() throws Exception {
        Path jar = jar("library.jar",
                "META-INF/why-site.index", "index.why\nstyle.css\nschema.sql\n",
                "META-INF/why-site/index.why", "view:\n<p>from a jar</p>\n",
                "META-INF/why-site/style.css", "body{color:green}\n",
                "META-INF/why-site/schema.sql", "create table notes (id integer);\n");

        Path database = dir.resolve("library.db");
        try (Site site = Site.fromClasspath(loader(jar), database)) {
            // A zip filesystem is the jar itself: nothing was copied to a temporary folder.
            assertEquals("jar", site.root().getFileSystem().provider().getScheme());
            assertEquals("/META-INF/why-site", site.root().toString().replace('\\', '/'));

            assertEquals("<p>from a jar</p>\n", render(site));
            assertEquals("body{color:green}\n", Files.readString(site.root().resolve("style.css")));
            assertTrue(Files.isRegularFile(database));
            try (Db opened = new Db(database)) {
                assertEquals(1, opened.query(
                        "select name from sqlite_master where type = 'table' and name = 'notes'",
                        List.of()).size());
            }

            assertEquals("<p>from a jar</p>\n", get(site, "/"));
            assertEquals("body{color:green}\n", get(site, "/style.css"));
        }
    }

    @Test
    void appliesMigrationsFromAJar() throws Exception {
        Path jar = jar("migrated.jar",
                "META-INF/why-site.index", "index.why\nmigrations/001_notes.sql\n",
                "META-INF/why-site/index.why", "view:\n<p>migrated</p>\n",
                "META-INF/why-site/migrations/001_notes.sql", "create table notes (id integer);\n");

        Path database = dir.resolve("migrated.db");
        try (Site site = Site.fromClasspath(loader(jar), database)) {
            assertEquals("<p>migrated</p>\n", render(site));
            try (Db opened = new Db(database)) {
                assertEquals(1, opened.query("select name from schema_migrations", List.of()).size());
                assertEquals(1, opened.query(
                        "select name from sqlite_master where type = 'table' and name = 'notes'",
                        List.of()).size());
            }
        }
    }

    @Test
    void rejectsDuplicateResourceNamesAfterNormalization() throws Exception {
        Path jar = jar("duplicate.jar",
                "META-INF/why-site.index", "a/b.why\na/./b.why\n",
                "META-INF/why-site/a/b.why", "view:\n<p>one</p>\n");

        IOException error = assertThrows(IOException.class, () -> Site.fromClasspath(loader(jar), null));
        assertTrue(error.getMessage().contains("duplicate"));
    }

    private static String get(Site site, String path) throws Exception {
        WhyServer server = new WhyServer(site, 0);
        server.start();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + server.port() + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            return response.body().replace("\r\n", "\n");
        } finally {
            server.stop();
        }
    }

    private static String render(Site site) {
        Path page = site.resolve("/");
        assertNotNull(page);
        return new PageCompiler(site).compile(page).render(Map.of());
    }

    private Path jar(String name, String... entries) throws IOException {
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new JarEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static ClassLoader loader(Path jar) throws Exception {
        URL index = URI.create("jar:" + jar.toUri() + "!/" + ClasspathSite.INDEX).toURL();
        return new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) {
                if (name.equals(ClasspathSite.INDEX)) return Collections.enumeration(List.of(index));
                return Collections.emptyEnumeration();
            }
        };
    }
}
