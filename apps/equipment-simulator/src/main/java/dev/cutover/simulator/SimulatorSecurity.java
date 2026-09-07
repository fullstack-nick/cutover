package dev.cutover.simulator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SimulatorSecurity {
    @Bean SecurityFilterChain simulatorAccess(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable()).sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/sim/v1/test-controls/**").hasRole("TEST_CONTROL")
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/sim/v1/**").hasAnyRole("ADAPTER","TEST_CONTROL")
                        .requestMatchers("/sim/v1/**").hasRole("ADAPTER").anyRequest().denyAll())
                .x509(x509 -> x509.subjectPrincipalRegex("CN=(.*?)(?:,|$)").userDetailsService(name -> switch (name) {
                    case "cutover-adapter" -> User.withUsername(name).password("").roles("ADAPTER").build();
                    case "cutover-scenario" -> User.withUsername(name).password("").roles("TEST_CONTROL").build();
                    default -> throw new UsernameNotFoundException("Untrusted equipment identity");
                })).build();
    }
}
