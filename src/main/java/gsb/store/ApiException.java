package gsb.store;

import java.util.ArrayList;
import java.util.List;

/** Readable failure with an HTTP-ish error code and actionable diagnostics. */
public final class ApiException extends RuntimeException {
    public final int status;
    public final String code;
    public final List<String> details = new ArrayList<>();

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException detail(String... lines) {
        for (String line : lines) {
            details.add(line);
        }
        return this;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(409, code, message);
    }

    public static ApiException unprocessable(String code, String message) {
        return new ApiException(422, code, message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "NOT_FOUND", message);
    }
}
