package com.diamondbank;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean UserDetailsService users(JdbcTemplate db) {
        return email -> db.query("SELECT email,password_hash FROM customers WHERE email=?",
            (rs, row) -> User.withUsername(rs.getString(1)).password(rs.getString(2)).roles("CUSTOMER").build(),
            email.trim().toLowerCase(java.util.Locale.ROOT)).stream().findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a
                .requestMatchers("/", "/index.html", "/banking.html", "/landing.css", "/landing.js", "/assets/**", "/style.css", "/app.js", "/api/csrf", "/api/register", "/error").permitAll()
                .anyRequest().authenticated())
            .requestCache(c -> c.disable())
            .formLogin(f -> f.loginProcessingUrl("/api/login")
                .successHandler((req,res,auth) -> res.setStatus(204))
                .failureHandler((req,res,err) -> { res.setStatus(401); res.setContentType("application/json"); res.getWriter().write("{\"message\":\"Email or password is incorrect.\"}"); }).permitAll())
            .logout(l -> l.logoutUrl("/api/logout").invalidateHttpSession(true).deleteCookies("SESSION")
                .logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((req,res,err) -> res.sendError(401))
                .accessDeniedHandler((req,res,err) -> res.sendError(403))).build();
    }
}

