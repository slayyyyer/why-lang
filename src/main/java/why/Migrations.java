package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import why.lang.Db;

/**
 * Ordered {@code .sql} files under {@code migrations/}, applied once each in filename order and
 * recorded in {@code schema_migrations}. Each file runs in its own transaction, so a failure leaves
 * the database at the last good migration.
 */
public final class Migrations {
    /** Folder name, relative to the site root. */
    public static final String DIRECTORY = "migrations";

    static final String TABLE = "schema_migrations";

    private Migrations() {}

    /** Applies pending migrations and returns how many ran. A missing folder means nothing to do. */
    public static int apply(Path siteRoot, Path dbFile) throws IOException {
        return apply(List.of(siteRoot), dbFile);
    }

    /**
     * Applies migrations from every root, in filename order. A name repeated across roots is one
     * migration: the first root that carries it wins.
     */
    public static int apply(List<Path> siteRoots, Path dbFile) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        for (Path siteRoot : siteRoots) {
            Path directory = siteRoot.resolve(DIRECTORY);
            if (!Files.isDirectory(directory)) continue;
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".sql"))
                        .filter(Files::isRegularFile)
                        .forEach(path -> files.putIfAbsent(path.getFileName().toString(), path));
            }
        }
        if (files.isEmpty()) return 0;

        int applied = 0;
        try (Db db = new Db(dbFile)) {
            db.exec("create table if not exists " + TABLE
                    + " (name text primary key, applied_at text not null default current_timestamp)",
                    List.of());
            Set<String> done = new HashSet<>();
            for (Object row : db.query("select name from " + TABLE, List.of())) {
                if (row instanceof Map<?, ?> map && map.get("name") != null) {
                    done.add(String.valueOf(map.get("name")));
                }
            }

            for (Map.Entry<String, Path> entry : files.entrySet()) {
                String name = entry.getKey();
                if (done.contains(name)) continue;
                String script;
                try {
                    script = Files.readString(entry.getValue(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IOException("cannot read migration '" + name + "': " + e.getMessage(), e);
                }
                db.exec("begin", List.of());
                try {
                    for (String statement : script.split(";")) {
                        if (!statement.isBlank()) db.exec(statement, List.of());
                    }
                    db.exec("insert into " + TABLE + " (name) values (?)", List.of(name));
                    db.exec("commit", List.of());
                } catch (RuntimeException e) {
                    try {
                        db.exec("rollback", List.of());
                    } catch (RuntimeException ignored) {
                        // The original failure is the useful one.
                    }
                    throw new WhyError("migration '" + name + "' failed: " + e.getMessage());
                }
                applied++;
            }
        }
        return applied;
    }
}
