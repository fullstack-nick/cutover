package dev.cutover.platform;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.assertj.core.api.Assertions.*;

/** Exercises the real production decoder with a trusted ephemeral signer and local JWKS HTTP. */
class JwtSecurityTest {
    HttpServer server;KeyPair key;JwtDecoder decoder;String issuer,jwks;
    @BeforeEach void start() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
        var publicKey=(RSAPublicKey)key.getPublic();
        var jwk=Map.of("keys",List.of(Map.of("kty","RSA","kid","trusted-test-key","use","sig","alg","RS256","n",unsigned(publicKey.getModulus().toByteArray()),"e",unsigned(publicKey.getPublicExponent().toByteArray()))));
        byte[] body=JsonSupport.write(jwk).getBytes(StandardCharsets.UTF_8);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/jwks",exchange->{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);try(var stream=exchange.getResponseBody()){stream.write(body);}});server.start();
        issuer="http://127.0.0.1:"+server.getAddress().getPort()+"/test-issuer";jwks=issuer.replace("/test-issuer","/jwks");
        decoder=new JwtSecurityConfiguration().jwtDecoder(issuer,jwks,"cutover-core");
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    static String encoded(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String unsigned(byte[] bytes){return encoded(bytes[0]==0?java.util.Arrays.copyOfRange(bytes,1,bytes.length):bytes);}
    Map<String,Object> claims(){long now=Instant.now().getEpochSecond();var claims=new LinkedHashMap<String,Object>();claims.put("iss",issuer);claims.put("aud",List.of("cutover-core"));claims.put("sub","fictional-operator");claims.put("azp","operations-console");claims.put("iat",now);claims.put("nbf",now-1);claims.put("exp",now+300);claims.put("sites",List.of("site-a"));claims.put("realm_access",Map.of("roles",List.of("operator")));return claims;}
    String token(Map<String,Object> claims,String kid,KeyPair signer) throws Exception {
        String input=encoded(JsonSupport.write(Map.of("alg","RS256","typ","JWT","kid",kid)).getBytes(StandardCharsets.UTF_8))+"."+encoded(JsonSupport.write(claims).getBytes(StandardCharsets.UTF_8));
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(signer.getPrivate());signature.update(input.getBytes(StandardCharsets.US_ASCII));return input+"."+encoded(signature.sign());
    }
    String token(Map<String,Object> claims) throws Exception{return token(claims,"trusted-test-key",key);}
    @Test void validTrustedSignatureStillRequiresExactIssuerAndAudience() throws Exception {
        var good=claims();assertThat(decoder.decode(token(good)).getSubject()).isEqualTo("fictional-operator");
        for(var wrong:List.of(Map.entry("iss",(Object)(issuer+"/different")),Map.entry("aud",(Object)List.of("another-service")),Map.entry("aud",(Object)List.of()))){var rejected=claims();rejected.put(wrong.getKey(),wrong.getValue());String signed=token(rejected);assertThatThrownBy(()->decoder.decode(signed)).as("Trusted signature with invalid %s",wrong.getKey()).isInstanceOf(JwtException.class);}
    }
    @Test void trustedExpiredFutureAndMissingTimeBoundsFailWithoutChangingAnyClock() throws Exception {
        for(String field:List.of("exp","iat")){var missing=claims();missing.remove(field);String signed=token(missing);assertThatThrownBy(()->decoder.decode(signed)).as("Missing %s",field).isInstanceOf(JwtException.class);}
        for(var invalid:List.of(Map.entry("exp",Instant.now().minusSeconds(120).getEpochSecond()),Map.entry("nbf",Instant.now().plusSeconds(120).getEpochSecond()))){var changed=claims();changed.put(invalid.getKey(),invalid.getValue());String signed=token(changed);assertThatThrownBy(()->decoder.decode(signed)).as("Invalid %s",invalid.getKey()).isInstanceOf(JwtException.class);}
    }
    @Test void unknownKeysWrongSignaturesUnsignedAndTamperedSiteClaimsFailClosed() throws Exception {
        String unknown=token(claims(),"unrecognized-key",key);assertThatThrownBy(()->decoder.decode(unknown)).isInstanceOf(JwtException.class);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);String foreign=token(claims(),"trusted-test-key",generator.generateKeyPair());assertThatThrownBy(()->decoder.decode(foreign)).isInstanceOf(JwtException.class);
        String valid=token(claims()),parts[]=valid.split("\\.");var changed=claims();changed.put("sites",List.of("site-b"));String forged=parts[0]+"."+encoded(JsonSupport.write(changed).getBytes(StandardCharsets.UTF_8))+"."+parts[2];assertThatThrownBy(()->decoder.decode(forged)).isInstanceOf(JwtException.class);
        String unsigned=encoded("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))+"."+parts[1]+".";assertThatThrownBy(()->decoder.decode(unsigned)).isInstanceOf(JwtException.class);
        var jwt=decoder.decode(valid);assertThat(Access.site(jwt,"site-a")).isEqualTo("site-a");assertThatThrownBy(()->Access.site(jwt,"site-b")).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status()).isEqualTo(404));assertThatThrownBy(()->Access.role(jwt,"supervisor")).isInstanceOfSatisfying(Problem.class,p->assertThat(p.status()).isEqualTo(403));
    }
    @Test void unavailableKeySetCannotEstablishTrustForAnUnknownSigner() throws Exception {
        String valid=token(claims());assertThat(decoder.decode(valid)).isNotNull();server.stop(0);server=null;
        String unknown=token(claims(),"not-in-the-cached-keyset",key);assertThatThrownBy(()->decoder.decode(unknown)).isInstanceOf(JwtException.class);
        var cold=new JwtSecurityConfiguration().jwtDecoder(issuer,jwks,"cutover-core");assertThatThrownBy(()->cold.decode(valid)).isInstanceOf(JwtException.class);
    }
}
