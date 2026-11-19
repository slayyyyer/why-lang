package why.lang;

/**
 * Thrown by the {@code redirect} builtin. Pages have no response object, so this signal travels
 * out of the interpreter and the server answers with a 303 instead of a rendered view.
 */
public final class Redirect extends RuntimeException {
    private final String location;

    public Redirect(String location) {
        super(null, null, false, false);
        this.location = location;
    }

    public String location() {
        return location;
    }
}
