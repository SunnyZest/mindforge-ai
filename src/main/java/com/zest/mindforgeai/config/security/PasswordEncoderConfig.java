package com.zest.mindforgeai.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 单独抽出 PasswordEncoder，避免循环依赖：
 * SecurityConfig → JwtAuthenticationFilter → UserDetailsService → UserService → PasswordEncoder → SecurityConfig
 *
 * @author crx
 * @since 2026-10-03
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
