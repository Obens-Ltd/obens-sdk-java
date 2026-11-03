package so.obens.sdk;

/** A request the platform answered with an error status. {@code 401} = missing, wrong, revoked or expired key. */
public class ObensApiException extends RuntimeException {
    private final int status;
    private final String body;

    public ObensApiException(int status, String body, String method, String path) {
        super(method + " " + path + " failed with " + status + (body == null || body.isBlank() ? "" : ": " + body));
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
