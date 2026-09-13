package ro.editii.scriptorium;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

import java.util.List;

@Configuration
public class SecurityConfig {

    // Real authentication is now backed by AppUserDetailsService (real,
    // persisted AppUser rows - see that class and AdminUserSeeder), which
    // Spring Security auto-wires into a DaoAuthenticationProvider since
    // it's the only UserDetailsService bean in the context - no explicit
    // InMemoryUserDetailsManager needed anymore.

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
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(it -> it.disable())
                .cors(it  -> Customizer.withDefaults())
                .httpBasic(it -> Customizer.withDefaults())
                .formLogin(it -> Customizer.withDefaults())
                .authorizeHttpRequests(it -> it
                    .requestMatchers("/admin/**").authenticated()
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
                    // Deliberately NOT in this authenticated list - /api/users/me
                    // must stay reachable while anonymous (that's exactly how a
                    // caller finds out it's anonymous); it reports its own
                    // authenticated:false rather than the security layer 401ing.
                    .anyRequest().permitAll()
                );

        return http.build();
    }
}
