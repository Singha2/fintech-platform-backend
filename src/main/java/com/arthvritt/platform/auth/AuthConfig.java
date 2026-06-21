package com.arthvritt.platform.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auth wiring. Passwords and OTP codes are hashed with <b>argon2id</b> (DL-BE-016) via Spring
 * Security's {@link Argon2PasswordEncoder}; the encoder carries its own per-hash salt and parameters,
 * so verification is a single {@code matches()} call.
 */
@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
