package ro.editii.scriptorium.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import ro.editii.scriptorium.dto.UserLoggedInDto;
import ro.editii.scriptorium.kafka.TextbaseEventsPublisher;

import java.time.Instant;

/** Publishes the login event for Spring Security's normal form/basic flow. */
@Component
@RequiredArgsConstructor
public class LoginNotificationListener {
    private final TextbaseEventsPublisher publisher;

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        final Authentication authentication = event.getAuthentication();
        publisher.signalUserLoggedIn(UserLoggedInDto.builder()
                .username(authentication.getName())
                .provider(provider(authentication))
                .loginAt(Instant.now())
                .build());
    }

    private static String provider(Authentication authentication) {
        return authentication.getClass().getSimpleName().toLowerCase().contains("remember")
                ? "remember-me" : "password";
    }
}
