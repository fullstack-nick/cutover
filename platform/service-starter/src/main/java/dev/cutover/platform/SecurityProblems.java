package dev.cutover.platform;

import java.io.IOException;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

final class SecurityProblems {
    private SecurityProblems() {}
    static AuthenticationEntryPoint authentication() {
        var standard=new BearerTokenAuthenticationEntryPoint();
        return (request,response,failure)->{
            standard.commence(request,response,failure);
            // Preserve the standard status/challenge without publishing token-decoder diagnostics.
            String challenge="Bearer realm=\"cutover\"";
            if(failure instanceof OAuth2AuthenticationException oauth){
                String code=oauth.getError().getErrorCode();
                if(java.util.Set.of("invalid_token","invalid_request","insufficient_scope").contains(code))challenge+=", error=\""+code+"\"";
            }
            response.setHeader("WWW-Authenticate",challenge);
            if(response.getStatus()==400)write(response,"MALFORMED_AUTHENTICATION","Malformed authentication","The authorization header is not a valid bearer credential.");
            else write(response,"AUTHENTICATION_REQUIRED","Authentication required","Supply a valid local access token for this operation.");
        };
    }
    static AccessDeniedHandler denied() {
        var standard=new BearerTokenAccessDeniedHandler();
        return (request,response,failure)->{
            standard.handle(request,response,failure);
            response.setHeader("WWW-Authenticate","Bearer realm=\"cutover\", error=\"insufficient_scope\"");
            write(response,"ACCESS_DENIED","Access denied","This identity cannot perform the requested action.");
        };
    }
    private static void write(HttpServletResponse response,String code,String title,String detail)throws IOException{
        response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonSupport.write(Map.of("type","urn:cutover:problem:"+code.toLowerCase(java.util.Locale.ROOT).replace('_','-'),"title",title,"status",response.getStatus(),"code",code,"detail",detail)));
    }
}
