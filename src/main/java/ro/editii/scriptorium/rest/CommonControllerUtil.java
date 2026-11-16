package ro.editii.scriptorium.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;

@RequiredArgsConstructor
public class CommonControllerUtil {

    final Environment environment;

    protected String devOrProd(String path, UriComponentsBuilder uriComponentsBuilder) {
        if (!path.startsWith("/"))
            path = "/" + path;
        if (Arrays.stream(this.environment.getActiveProfiles())
                .anyMatch(it -> it.equals("dev"))) { // sorry
            return  "http://localhost:8080" + path;
        } else
            return uriComponentsBuilder.path(path).build().toUriString();
    }

    protected String getAuthorImage(String strid, UriComponentsBuilder uriComponentsBuilder, HttpServletRequest httpServletRequest) {
        // Bundled author portraits were removed from the application source.
        // Cover art now comes only from explicitly associated/enriched media.
        return null;
    }

    String getAuthorThumb(String strid, UriComponentsBuilder uriComponentsBuilder, HttpServletRequest httpServletRequest) {
        // Keep the catalogue contract nullable: there is no source-bundled
        // thumbnail to advertise or accidentally reuse as cover art.
        return null;
    }
}
