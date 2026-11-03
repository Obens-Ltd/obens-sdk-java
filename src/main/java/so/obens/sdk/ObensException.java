package so.obens.sdk;

/** The request could not be completed (network failure, timeout, unreadable answer). */
public class ObensException extends RuntimeException {
    public ObensException(String message, Throwable cause) {
        super(message, cause);
    }
}
