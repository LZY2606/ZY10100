package gsb.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Readable, structured validation failure. Carries a stable error code plus a list of specific
 * reasons so the UI and tests can show exactly why ingestion/comparison/release was rejected.
 */
public class ValidationException extends RuntimeException {
    public final String code;
    public final List<String> reasons;

    public ValidationException(String code, List<String> reasons) {
        super(code + ": " + String.join("; ", reasons));
        this.code = code;
        this.reasons = List.copyOf(reasons);
    }

    public static ValidationException of(String code, String reason) {
        return new ValidationException(code, List.of(reason));
    }

    public static Builder builder(String code) {
        return new Builder(code);
    }

    public static final class Builder {
        private final String code;
        private final List<String> reasons = new ArrayList<>();

        private Builder(String code) {
            this.code = code;
        }

        public Builder add(String reason) {
            reasons.add(reason);
            return this;
        }

        public boolean isEmpty() {
            return reasons.isEmpty();
        }

        public ValidationException build() {
            return new ValidationException(code, reasons);
        }
    }
}
