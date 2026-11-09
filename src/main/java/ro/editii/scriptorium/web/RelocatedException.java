package ro.editii.scriptorium.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A request for a division path that was relocated (see
 * {@link ro.editii.scriptorium.service.RelocationService}): answered 301 with
 * the new URL in {@code Location}, so browsers, crawlers and the reader land
 * on the division's current address and replace the old one.
 */
public class RelocatedException extends ResponseStatusException {

    private final String location;

    public RelocatedException(String location) {
        super(HttpStatus.MOVED_PERMANENTLY, "relocated to " + location);
        this.location = location;
    }

    public String getLocation() {
        return this.location;
    }

    @Override
    public HttpHeaders getHeaders() {
        final HttpHeaders headers = new HttpHeaders();
        headers.addAll(super.getHeaders());
        headers.set(HttpHeaders.LOCATION, this.location);
        return headers;
    }
}
