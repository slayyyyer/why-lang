package why;

/** A load, syntax or runtime error in a page, with a source location when one is known. */
public final class WhyError extends RuntimeException {
    private final String file;
    private final int line;

    public WhyError(String message, String file, int line) {
        super(message);
        this.file = file;
        this.line = line;
    }

    public WhyError(String message) {
        this(message, null, 0);
    }

    public String file() {
        return file;
    }

    public int line() {
        return line;
    }

    /** {@code file:line}, or an empty string when the error has no location. */
    public String location() {
        if (file == null) return "";
        return line > 0 ? file + ":" + line : file;
    }

    @Override
    public String toString() {
        String location = location();
        return location.isEmpty() ? getMessage() : location + ": " + getMessage();
    }
}
