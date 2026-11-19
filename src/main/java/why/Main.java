package why;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import why.lang.Db;

/** Command line entry point: {@code java -jar why.jar ./site}. */
public final class Main {
    public static void main(String[] args) {
        Path directory = Path.of(".");
        int port = 8080;
        Path db = null;
        boolean production = false;
        boolean secureCookies = false;
        boolean check = args.length > 0 && args[0].equals("check");

        for (int i = check ? 1 : 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h", "--help" -> {
                    usage();
                    return;
                }
                case "-p", "--port" -> {
                    if (check) {
                        fail("check does not take " + arg);
                        return;
                    }
                    String value = value(args, ++i, arg);
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        fail("port must be a number, got '" + value + "'");
                        return;
                    }
                }
                case "--db" -> {
                    if (check) {
                        fail("check does not take " + arg);
                        return;
                    }
                    db = Path.of(value(args, ++i, arg));
                }
                case "--production" -> {
                    if (check) {
                        fail("check does not take " + arg);
                        return;
                    }
                    production = true;
                }
                case "--secure-cookies" -> {
                    if (check) {
                        fail("check does not take " + arg);
                        return;
                    }
                    secureCookies = true;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        fail("unknown option '" + arg + "'");
                        return;
                    }
                    directory = Path.of(arg);
                }
            }
        }

        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            fail("site folder not found: " + root);
            return;
        }

        if (check) {
            int found = new Checker(new Site(root)).check(System.out);
            if (found != 0) System.exit(1);
            return;
        }

        Path dbFile = db == null ? root.resolve("data.db") : db.toAbsolutePath().normalize();
        try {
            Path parent = dbFile.getParent();
            if (parent != null) Files.createDirectories(parent);
        } catch (IOException e) {
            fail("cannot create the database folder: " + e.getMessage());
            return;
        }

        Path schema = root.resolve("schema.sql");
        if (!Files.exists(dbFile) && Files.isRegularFile(schema)) {
            try {
                Db.applyScript(dbFile, Files.readString(schema, StandardCharsets.UTF_8));
                System.out.println("why: created " + dbFile + " from schema.sql");
            } catch (RuntimeException | IOException e) {
                fail("could not apply schema.sql: " + e.getMessage());
                return;
            }
        }

        Site site = new Site(root, dbFile);
        try {
            WhyServer server = new WhyServer(site, port, production, secureCookies);
            server.start();
            System.out.println("Why is serving " + root + " at http://localhost:" + server.port());
            System.out.println("press Ctrl+C to stop");
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            new CountDownLatch(1).await();
        } catch (IOException e) {
            fail("cannot start server: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            fail("option " + option + " needs a value");
        }
        return args[index];
    }

    private static void fail(String message) {
        System.err.println("why: " + message);
        System.exit(1);
    }

    private static void usage() {
        System.out.println("""
                usage: java -jar why.jar [site] [options]
                       java -jar why.jar check [site]

                  site              folder of .why pages (default: .)
                  check             compile every page, report problems, print the page map
                  -p, --port PORT   port to listen on (default: 8080)
                      --db FILE     SQLite database file (default: <site>/data.db)
                      --production  return generic 500 pages; log details server-side
                      --secure-cookies  mark session cookies Secure (use with TLS)
                  -h, --help        show this help

                Drop a .why file in the site folder and it is served at its path.
                """);
    }
}
