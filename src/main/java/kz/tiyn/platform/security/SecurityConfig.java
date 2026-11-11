package kz.tiyn.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import kz.tiyn.platform.web.ErrorBody;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
class SecurityConfig {

    private static final OAuth2Error INVALID_CLAIMS =
            new OAuth2Error("invalid_token", "subject or role claim is invalid", null);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        AuthenticationEntryPoint unauthenticated =
                (req, res, e) -> write(json, res, HttpStatus.UNAUTHORIZED, "unauthenticated");
        AccessDeniedHandler forbidden = (req, res, e) -> write(json, res, HttpStatus.FORBIDDEN, "forbidden");
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a -> a.requestMatchers(HttpMethod.POST, "/auth/google", "/auth/refresh", "/auth/logout")
                                .permitAll()
                                .requestMatchers("/actuator/health/**")
                                .permitAll()
                                .anyRequest()
                                .authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(principalConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(
                        e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.signingKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(JwtProperties.ISSUER),
                requiredExpiry(),
                subjectAndRole()));
        return decoder;
    }

    private Converter<Jwt, AbstractAuthenticationToken> principalConverter() {
        return jwt -> {
            Principal principal =
                    new Principal(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")));
            return new PrincipalAuthentication(
                    principal, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
        };
    }

    private OAuth2TokenValidator<Jwt> requiredExpiry() {
        return jwt -> jwt.getExpiresAt() == null
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "exp is required", null))
                : OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidator<Jwt> subjectAndRole() {
        return jwt -> {
            String subject = jwt.getSubject();
            String role = jwt.getClaimAsString("role");
            if (subject == null || role == null) {
                return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
            }
            try {
                UUID.fromString(subject);
                Role.valueOf(role);
                return OAuth2TokenValidatorResult.success();
            } catch (IllegalArgumentException e) {
                return OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
            }
        };
    }

    private void write(ObjectMapper json, HttpServletResponse res, HttpStatus status, String code) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), new ErrorBody(code));
    }
}
