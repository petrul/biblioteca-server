package ro.editii.scriptorium;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import ro.editii.scriptorium.security.AdminUsers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Configuration
public class SecurityConfig {

    // Real authentication is now backed by AppUserDetailsService (real,
    // persisted AppUser rows - see that class and AdminUserSeeder), which
    // Spring Security auto-wires into a DaoAuthenticationProvider since
    // it's the only UserDetailsService bean in the context - no explicit
    // InMemoryUserDetailsManager needed anymore.

    // The shared secret the biblioteca-nestjs enrichment worker presents
    // as X-Internal-Token on /api/internal/** (INTERNAL_API_TOKEN on both
    // sides). Blank fails closed: every /api/internal request is denied
    // rather than silently opening a write path to the whole network.
    @Value("${internal.api.token:}")
    private String internalApiToken;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public HttpFirewall httpFirewall() {
        final var firewall = new StrictHttpFirewall();
        // StrictHttpFirewall otherwise rejects PROPFIND before it reaches the
        // read-only DAV controller. Mutating DAV verbs are admitted only so
        // that the controller can answer them correctly with 405.
        firewall.setAllowedHttpMethods(List.of(
                "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT",
                "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK"));
        return firewall;
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer(HttpFirewall httpFirewall) {
        return web -> web.httpFirewall(httpFirewall);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AdminUsers adminUsers) throws Exception {
        http
                .csrf(it -> it.disable())
                .cors(it  -> Customizer.withDefaults())
                .httpBasic(it -> Customizer.withDefaults())
                .formLogin(it -> Customizer.withDefaults())
                // A denied /api/internal/** request must 401, not redirect
                // to the login page: the caller is the NestJS worker, which
                // checks HTTP status - a 200 login-page body after the
                // redirect would look like a successful persistence POST.
                .exceptionHandling(it -> it
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.withDefaults().matcher("/api/internal/**")))
                .authorizeHttpRequests(it -> it
                    .requestMatchers("/admin/**").authenticated()
                    // The one deliberate exception under /api/admin: the
                    // non-secret shared-resource naming convention (Kafka
                    // topics, Milvus collection, embedder model) that
                    // biblioteca-nestjs fetches at startup to bootstrap its
                    // PROVIDER_SHARED_CONFIG - it has no user account and
                    // sends no credentials, and ConfigRestController exposes
                    // only names, never addresses or secrets. Matched before
                    // the /api/admin/** rule below on purpose.
                    .requestMatchers("/api/admin/config").permitAll()
                    // Was permitAll (fell through to anyRequest below) -
                    // adminUsers.isAdmin() is ADMIN_USERS (see AdminUsers/
                    // application.properties), not just "signed in" -
                    // reimport/reindex/prune are real, unattributed-cost
                    // operations, not something any logged-in reader
                    // should be able to trigger.
                    .requestMatchers("/api/admin/**")
                        .access((authentication, context) ->
                                new AuthorizationDecision(adminUsers.isAdmin(authentication.get().getName())))
                    .requestMatchers("/api/shell").authenticated()
                    .requestMatchers("/api/users/register").permitAll()
                    .requestMatchers("/api/auth/google").permitAll()
                    // A user's own collections (list/create/mutate) always
                    // require being logged in as that user - see
                    // DivCollectionRestController. System collections
                    // (by-repo/by-language/by-author) stay public below.
                    .requestMatchers("/api/collections/mine/**").authenticated()
                    // Reading position ("bookmark") per work - see
                    // ReadingProgress/ReadingProgressRestController. Same
                    // reasoning as collections/mine above: always scoped to
                    // a real signed-in reader, no anonymous concept here.
                    .requestMatchers("/api/reading-progress/**").authenticated()
                    // The NestJS enrichment worker's persistence boundary
                    // (EnrichmentRestController): a shared secret, not a
                    // user account - the worker has no credentials to log
                    // in with, and nothing else should ever write bios,
                    // summaries or image associations. internalTokenMatches
                    // fails closed on a blank internal.api.token.
                    .requestMatchers("/api/internal/**")
                        .access((authentication, context) ->
                                new AuthorizationDecision(internalTokenMatches(
                                        context.getRequest().getHeader("X-Internal-Token"))))
                    // Deliberately NOT in this authenticated list - /api/users/me
                    // must stay reachable while anonymous (that's exactly how a
                    // caller finds out it's anonymous); it reports its own
                    // authenticated:false rather than the security layer 401ing.
                    .anyRequest().permitAll()
                );

        return http.build();
    }

    // Constant-time comparison (the only security-relevant secret check in
    // this app that is not delegated to BCrypt/GoogleTokenInfoVerifier) - a
    // plain String.equals would leak the token one character at a time.
    private boolean internalTokenMatches(String presented) {
        return internalApiToken != null && !internalApiToken.isBlank() && presented != null
                && MessageDigest.isEqual(
                        internalApiToken.getBytes(StandardCharsets.UTF_8),
                        presented.getBytes(StandardCharsets.UTF_8));
    }
}
