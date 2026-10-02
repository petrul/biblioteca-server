package ro.editii.scriptorium.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.xml.sax.SAXParseException;

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
