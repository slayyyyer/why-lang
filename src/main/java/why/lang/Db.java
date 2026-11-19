package why.lang;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import why.WhyError;

/** A SQLite database handle. The connection is opened on first use and closed after the page runs. */
public final class Db implements AutoCloseable {
    private final Path file;
    private Connection conn;

    public Db(Path file) {
        this.file = file;
    }

    /** Rows as hashes keyed by column label. */
    public List<Object> query(String sql, List<Object> args) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rows(rs);
            }
        } catch (SQLException e) {
            throw new WhyError("db.query failed: " + e.getMessage());
        }
    }

    /** Runs a statement and returns the number of affected rows. */
    public long exec(String sql, List<Object> args) {
        try (PreparedStatement ps = connection().prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new WhyError("db.exec failed: " + e.getMessage());
        }
    }

    /** The first row of a query, or nil. */
    public Object get(String sql, List<Object> args) {
        List<Object> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Runs a {@code schema.sql} file, one {@code ;}-separated statement at a time. */
    public static void applyScript(Path dbFile, String script) {
        try (Db db = new Db(dbFile)) {
            for (String statement : script.split(";")) {
                if (!statement.isBlank()) db.exec(statement, List.of());
            }
        }
    }

    @Override
    public void close() {
        if (conn == null) return;
        try {
            conn.close();
        } catch (SQLException ignored) {
            // Nothing useful to do while the page response is being finished.
        }
        conn = null;
    }

    private Connection connection() {
        if (conn != null) return conn;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException ignored) {
            // The service loader in the shaded jar registers the driver.
        }
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            return conn;
        } catch (SQLException e) {
            throw new WhyError("cannot open database '" + file + "': " + e.getMessage());
        }
    }

    private static void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            Object value = args.get(i);
            if (value == null) ps.setObject(i + 1, null);
            else if (value instanceof Boolean b) ps.setObject(i + 1, b ? 1 : 0);
            else ps.setObject(i + 1, value);
        }
    }

    private static List<Object> rows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columns = meta.getColumnCount();
        List<Object> rows = new ArrayList<>();
        while (rs.next()) {
            Map<Object, Object> row = new LinkedHashMap<>();
            for (int c = 1; c <= columns; c++) {
                row.put(meta.getColumnLabel(c), normalize(rs.getObject(c)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object normalize(Object value) {
        return value instanceof Integer i ? i.longValue() : value;
    }
}
