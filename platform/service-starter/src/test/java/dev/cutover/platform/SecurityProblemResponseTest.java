package dev.cutover.platform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityProblemResponseTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @Import(JwtSecurityConfiguration.class)
    static class TestApplication {
        @Bean @Primary JwtDecoder fixtureDecoder(){return token->{
            if(!token.equals("fixture-operator"))throw new BadJwtException("synthetic-private-decoder-detail");
            return Jwt.withTokenValue(token).header("alg","RS256").subject("fixture-operator").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("realm_access",Map.of("roles",List.of("operator"))).build();
        };}
        @Bean Endpoint endpoint(){return new Endpoint();}
    }
    @RestController static class Endpoint {
        @GetMapping({"/api/v1/example","/internal/example"}) Map<String,Boolean> get(){return Map.of("ok",true);}
    }
    AnnotationConfigWebApplicationContext context;MockMvc mvc;
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture",Map.of("cutover.security.issuer","http://localhost/fixture","cutover.security.jwks-uri","http://localhost/unused-jwks","cutover.security.audience","fixture")));
        context.register(TestApplication.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).addFilter(context.getBean(FilterChainProxy.class)).build();
    }
    @AfterEach void close(){if(context!=null)context.close();}
    @Test void missingTokenIsAProblemWithBearerChallenge()throws Exception{
        mvc.perform(get("/api/v1/example")).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED")).andExpect(jsonPath("$.status").value(401)).andExpect(header().string("WWW-Authenticate","Bearer realm=\"cutover\""));
    }
    @Test void invalidTokenKeepsItsChallengeWithoutDecoderDetails()throws Exception{
        var response=mvc.perform(get("/api/v1/example").header("Authorization","Bearer invalid-fixture")).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED")).andExpect(header().string("WWW-Authenticate","Bearer realm=\"cutover\", error=\"invalid_token\"")).andReturn().getResponse();
        assertThat(response.getContentAsString()+response.getHeader("WWW-Authenticate")).doesNotContain("synthetic-private-decoder-detail","invalid-fixture");
    }
    @Test void malformedBearerTokenRemainsUnauthorized()throws Exception{
        mvc.perform(get("/api/v1/example").header("Authorization","Bearer two credentials")).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED")).andExpect(jsonPath("$.status").value(401));
    }
    @Test void authenticatedRoleFailureIsAProblemWhileAllowedAccessSucceeds()throws Exception{
        mvc.perform(get("/api/v1/example").header("Authorization","Bearer fixture-operator")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/internal/example").header("Authorization","Bearer fixture-operator")).andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andExpect(jsonPath("$.status").value(403));
    }
}
