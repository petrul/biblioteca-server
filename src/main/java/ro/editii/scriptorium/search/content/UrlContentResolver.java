package ro.editii.scriptorium.search.content;

import lombok.RequiredArgsConstructor;
import org.springframework.web.client.RestTemplate;


@RequiredArgsConstructor
public class UrlContentResolver implements ContentResolver {

    final RestTemplate restTemplate;

    @Override
    public String resolve(String id) {
        assert id != null;
        try {
            return restTemplate.getForObject(id + ".txt", String.class);
        } catch (Exception e) {
            // 404/unreachable: the source is gone (removed book, deleted
            // paragraph). null, not an error string, so the search hit
            // gets filtered out instead of presented as garbage (see
            // ContentResolver's own contract note).
            return null;
        }

    }
}
