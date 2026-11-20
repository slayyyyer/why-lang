package why.lang;

import java.util.List;

/**
 * A block passed to a def, invoked by {@code yield} or {@code block.call}. A template block
 * renders its markup; a language block runs its statements and returns the last expression.
 */
public interface Block {

    /** Runs or renders the block. Template blocks bind the first argument to {@code it}. */
    Object yield(List<Object> args);

    /** The helper's {@code {{else}}} section, or an empty string when there is none. */
    default Object inverse() {
        return "";
    }
}
