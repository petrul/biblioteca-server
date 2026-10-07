package ro.editii.scriptorium.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXParseException;

import java.sql.SQLException;

public class RestUtil {
    public static void throw404() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    public static void throw404(String message) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    public static void throw400(String message) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public static void throw500(Exception e) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, summarize(e));
    }

    public static void throw500(String message) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }

    /**
     * A read that hit Derby lock contention: a wait timeout
     * (SQLState 40XL1) or a deadlock victim (40001). Derby has no MVCC,
     * so while the importer's sweep holds write locks this is the
     * expected shape of "a request raced the import" - transient and
     * retryable, not a server failure.
     */
    public static boolean isDbBusy(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("40XL") || "40001".equals(sql.getSQLState()))) {
                return true;
            }
            // some driver paths surface the code only in the message
            final String message = cause.getMessage();
            if (message != null && (message.startsWith("ERROR 40XL") || message.startsWith("ERROR 40001"))) {
                return true;
            }
        }
        return false;
    }

    /** An expected, client-directed error (4xx) anywhere in the chain. */
    public static boolean isClientError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseStatusException status && status.getStatusCode().is4xxClientError()) {
                return true;
            }
        }
        return false;
    }

    /** A client-safe diagnostic: never expose a parser/database stack as the HTTP cause. */
    public static String summarize(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SAXParseException parse) {
                return String.format("XML parse error at line %d, column %d: %s",
                        parse.getLineNumber(), parse.getColumnNumber(), parse.getMessage());
            }
            current = current.getCause();
        }
        final String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
