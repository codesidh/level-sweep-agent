package com.levelsweep.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security wiring — Phase A.
 *
 * <p>The {@code spring-boot-starter-oauth2-resource-server} starter is on the
 * classpath (so the Phase A→B flip is a config change, not a build change).
 * Without an explicit {@link SecurityFilterChain} bean, Spring Boot's
 * auto-config wires a default chain that requires JWT on every request — the
 * Phase A {@link com.levelsweep.gateway.auth.BypassAuthFilter} would never
 * see the request because the JWT validator would 401 it first.
 *
 * <p>This config disables the default chain (everything permitted at the
 * Spring Security layer) and lets the custom filters handle authn /
 * authz:
 *
 * <ul>
 *   <li>Phase A: {@link com.levelsweep.gateway.auth.BypassAuthFilter} validates
 *       the {@code X-Tenant-Id} header.</li>
 *   <li>Phase B (when the {@code phase-b-jwt-auth} flag flips): replace this
 *       bean with one that wires the JWT decoder; bypass filter steps aside
 *       via its own feature-flag check.</li>
 * </ul>
 *
 * <p>CSRF is disabled — the BFF is stateless and accepts only same-origin
 * traffic from the Angular SPA (cookie-less; X-Tenant-Id header carries
 * everything). Sessions are NEVER created.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }
}
