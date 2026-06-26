package com.arthvritt.platform.infrastructure.security;

import com.arthvritt.platform.auth.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * WS-0 HTTP security (DL-BE-030). Replaces Spring Security's locked-down default (HTTP Basic + a generated
 * password — which would otherwise be the only thing standing between the app and either open or
 * un-loginable endpoints) with a <b>stateless</b>, bearer-authenticated API: the login routes are open
 * (they mint the session), Actuator health is open, everything else requires a live session bearer.
 *
 * <p>Authentication only — authorisation (roles, SoD, MFA-freshness) stays at the command boundary in
 * {@code CommandGateway}, so an endpoint cannot accidentally under-enforce by forgetting a security
 * annotation. The bearer filter + entry point are constructed here (not {@code @Component}s) so the filter
 * is added to this chain alone, not also auto-registered as a servlet filter.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SessionService sessions,
                                            ObjectMapper mapper) throws Exception {
        SessionBearerAuthFilter bearerFilter = new SessionBearerAuthFilter(sessions);
        BearerAuthenticationEntryPoint entryPoint = new BearerAuthenticationEntryPoint(mapper);
        B4AccessDeniedHandler accessDeniedHandler = new B4AccessDeniedHandler(mapper);

        http
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/auth/login/**").permitAll()
                        .requestMatchers("/webhooks/**").permitAll() // vendor-authenticated by HMAC, not a bearer (B4 §5)
                        .requestMatchers("/dev/**").permitAll() // dev-profile-only helpers; no handler exists in prod (404)
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
