package io.github.btpinyverde.notification_service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration 
class SecurityConfig {
        static final String HEADER = "X-API-Key";
    
    @Bean 
    SecurityFilterChain security(HttpSecurity http, @Value("${notifications.api-key}") String key ) throws Exception {
        if (key.isBlank()) {
            throw new IllegalStateException("notifications.api-key no puede estar vacio");
        }
        byte[] expected = key.getBytes(StandardCharsets.UTF_8);
        return http.csrf(AbstractHttpConfigurer::disable)
                    .requestCache(AbstractHttpConfigurer::disable)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(routes -> routes
                    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                    .anyRequest().authenticated())
                    .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
                        response.setStatus(401);
                        response.setHeader("WWW-Authenticate", "ApiKey realm=\"notifications\"");
                        response.setContentType("application/problem+json");
                        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        response.getWriter().write("""
                            {"status":401,"title":"Autenticación requerida","detail":"Enviá una clave válida en X-API-Key"}
                            """);
                }))
                                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        String supplied = request.getHeader(HEADER);
                        if (supplied != null && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                            var context = SecurityContextHolder.createEmptyContext();
                            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                    "notification-client", null, List.of()));
                            SecurityContextHolder.setContext(context);
                        }
                        chain.doFilter(request, response);
                    }
                }, AnonymousAuthenticationFilter.class)
                .build();
    }
    
}
