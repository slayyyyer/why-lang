package why.lang;

import why.WhyError;

/**
 * The {@code response} global: per-page response controls. A page opts into a
 * Content-Security-Policy with {@code response.csp("...")}; everything else about the response
 * stays controlled by the server.
 */
public final class Response {
    private static final int MAX_CSP_LENGTH = 4_096;

    private String csp;

    /** Sets this page's policy; the last call wins. */
    public void csp(String policy, int line) {
        if (policy == null || policy.isBlank()) {
            throw new WhyError("response.csp needs a non-empty policy", null, line);
        }
        String trimmed = policy.strip();
        if (trimmed.length() > MAX_CSP_LENGTH) {
            throw new WhyError("response.csp is limited to " + MAX_CSP_LENGTH + " characters", null, line);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                throw new WhyError("response.csp must be printable ASCII on one line", null, line);
            }
        }
        this.csp = trimmed;
    }

    /** The policy the page opted into, or null for the server default. */
    public String csp() {
        return csp;
    }
}
