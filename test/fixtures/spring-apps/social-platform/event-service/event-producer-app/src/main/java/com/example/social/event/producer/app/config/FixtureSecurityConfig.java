package com.example.social.event.producer.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
public class FixtureSecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .addFilterBefore(new FixtureBearerTokenFilter(), AnonymousAuthenticationFilter.class)
        .build();
  }

  static final class FixtureBearerTokenFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
      String authorization = request.getHeader("Authorization");
      if (authorization != null && authorization.startsWith("Bearer ")) {
        UsernamePasswordAuthenticationToken authentication =
            authenticationFromToken(authorization.substring("Bearer ".length()).trim());
        if (authentication == null) {
          response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unknown fixture bearer token");
          return;
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
      filterChain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken authenticationFromToken(String token) {
      if (token == null || token.isBlank()) return null;
      if ("admin-token".equals(token)) return tokenFor("admin", List.of("ROLE_ADMIN", "ROLE_USER"));
      if (!token.endsWith("-token") || token.length() <= "-token".length()) return null;
      String username = token.substring(0, token.length() - "-token".length());
      return tokenFor(username, List.of("ROLE_USER"));
    }

    private UsernamePasswordAuthenticationToken tokenFor(String username, List<String> roles) {
      List<GrantedAuthority> authorities = new ArrayList<>();
      for (String role : roles) {
        authorities.add(new SimpleGrantedAuthority(role));
      }
      return new UsernamePasswordAuthenticationToken(username, "N/A", authorities);
    }
  }
}
