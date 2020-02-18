package sevts.terminal.tripod;

public class TripodException extends Exception {
    protected int code;

    public TripodException() {
    }

    public TripodException(int code, String message) {
        this(message);
        this.code = code;
    }

    public TripodException(int code) {
        this.code = code;
    }

    public TripodException(String message) {
        super(message);
    }

    public TripodException(String message, Throwable cause) {
        super(message, cause);
    }

    public TripodException(Throwable cause) {
        super(cause);
    }

    public TripodException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    public int getCode() {
        return this.code;
    }

    public void setCode(int code) {
        this.code = code;
    }
}
