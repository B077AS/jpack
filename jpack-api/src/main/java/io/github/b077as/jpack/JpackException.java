package io.github.b077as.jpack;

/**
 * Something the stub could not do: no connection, no network, a download that does not match its hash, an install folder
 * that cannot be written to. The message is written to be shown or logged as it is.
 */
public class JpackException extends RuntimeException {

    public JpackException(String message) {
        super(message);
    }

    public JpackException(String message, Throwable cause) {
        super(message, cause);
    }
}
