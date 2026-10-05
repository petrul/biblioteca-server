package ro.editii.scriptorium.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ro.editii.scriptorium.dao.UserPreferenceRepository;
import ro.editii.scriptorium.model.UserPreference;

import java.util.HashMap;
import java.util.Map;

/**
 * The server half of the reader's PreferencesStore (the local half is the
 * browser's localStorage): one arbitrary JSON document per signed-in
 * reader, stored verbatim so its shape can evolve without ever touching
 * the schema again - the server never interprets a key. PUT replaces the
 * whole document as-is; the merge-not-overwrite contract lives in the
 * client store (get, merge, put), same interface either side.
 */
@RestController
@RequestMapping("/api/users/me/preferences")
@CrossOrigin
@RequiredArgsConstructor
@Log4j2
public class UserPreferencesRestController {

    final UserPreferenceRepository userPreferenceRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @GetMapping
    public Map<String, Object> get(Authentication authentication) {
        return this.userPreferenceRepository.findByUsername(authentication.getName())
                .map(UserPreference::getPreferences)
                .map(this::decode)
                .orElseGet(HashMap::new);
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody Map<String, Object> preferences, Authentication authentication) {
        final String username = authentication.getName();
        final UserPreference pref = this.userPreferenceRepository.findByUsername(username)
                .orElseGet(() -> UserPreference.builder().username(username).build());
        pref.setPreferences(encode(preferences));
        this.userPreferenceRepository.save(pref);
        log.debug("PUT /api/users/me/preferences: stored document for {}", username);
        return decode(pref.getPreferences());
    }

    private String encode(Object value) {
        try {
            return this.objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // A value the client sent as JSON but we can't re-encode is a
            // programming error, not a user error - store it as a plain
            // string rather than failing the request.
            return String.valueOf(value);
        }
    }

    private Map<String, Object> decode(String value) {
        try {
            return this.objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }
}
