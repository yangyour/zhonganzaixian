package cn.mop.domain;
import java.util.List;
public class DomainException extends RuntimeException {
    public final int status;
    public final String code;
    public final List<?> details;
    public DomainException(int status, String code, String message, List<?> details) {
        super(message); this.status=status; this.code=code; this.details=details;
    }
    public DomainException(int status, String code, String message) { this(status, code, message, List.of()); }
}
