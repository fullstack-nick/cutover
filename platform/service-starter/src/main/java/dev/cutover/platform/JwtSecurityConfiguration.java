package dev.cutover.platform;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class JwtSecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${cutover.security.issuer}") String issuer,
                                @Value("${cutover.security.jwks-uri}") String jwks,
                                @Value("${cutover.security.audience}") String audience) {
        var decoder=NimbusJwtDecoder.withJwkSetUri(jwks).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(issuer),new JwtTimestampValidator(Duration.ofSeconds(15)),jwt -> {
            if (jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || !jwt.getAudience().contains(audience))
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token","Required time bounds or audience are absent.",null));
            return OAuth2TokenValidatorResult.success();
        }));
        return decoder;
    }
    @Bean SecurityFilterChain apiAccess(HttpSecurity http) throws Exception {
        var converter=new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities=new ArrayList<GrantedAuthority>();
            Object realm=jwt.getClaim("realm_access");
            if (realm instanceof Map< ?,? > access && access.get("roles") instanceof Collection< ? > roles)
                for (Object role:roles) if (role instanceof String text && text.matches("[a-z][a-z0-9_-]{0,59}")) authorities.add(new SimpleGrantedAuthority("ROLE_"+text));
            return authorities;
        });
        return http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers("/actuator/health/**","/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/v1/platform/storage","/internal/v1/platform/untrusted-deliveries").hasRole("platform-admin")
                        .requestMatchers("/internal/v1/sites/*/messaging","/internal/v1/sites/*/messaging/**").hasAnyRole("operator","supervisor","platform-admin","service")
                        .requestMatchers("/internal/**").hasRole("service").requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))).build();
    }
}
