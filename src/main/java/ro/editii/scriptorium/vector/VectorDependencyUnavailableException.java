package ro.editii.scriptorium.vector;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class VectorDependencyUnavailableException extends RuntimeException {
    public VectorDependencyUnavailableException(String message) { super(message); }
    public VectorDependencyUnavailableException(String message, Throwable cause) { super(message, cause); }
}
