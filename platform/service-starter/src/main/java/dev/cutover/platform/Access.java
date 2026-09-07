package dev.cutover.platform;

import java.util.Collection;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

public final class Access {
    private Access() {}
    public static String site(Jwt jwt, String site) {
        if (jwt==null || site==null || !site.matches("[a-z][a-z0-9-]{0,39}")) throw Problem.missing();
        Object sites=jwt.getClaim("sites");
        if (!(sites instanceof Collection< ? > allowed) || !allowed.contains(site)) throw Problem.missing();
        return site;
    }
    public static boolean hasRole(Jwt jwt, String role) {
        if (jwt==null) return false;
        Object realm=jwt.getClaim("realm_access");
        return realm instanceof Map< ?,? > access && access.get("roles") instanceof Collection< ? > roles && roles.contains(role);
    }
    public static void role(Jwt jwt,String... required) {
        for (String role:required) if (hasRole(jwt,role)) return;
        throw new Problem(403,"ROLE_REQUIRED","This identity cannot perform the requested action.");
    }
    public static String client(Jwt jwt) {
        String client=jwt.getClaimAsString("azp");
        if (client==null || client.isBlank()) throw new Problem(403,"SERVICE_IDENTITY_REQUIRED","An authenticated client identity is required.");
        return client;
    }
}
