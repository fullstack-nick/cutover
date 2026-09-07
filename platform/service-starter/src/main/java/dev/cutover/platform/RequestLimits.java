package dev.cutover.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Read a bounded body before controllers can write any business state, including chunked requests. */
public final class RequestLimits extends OncePerRequestFilter {
    private static final int MAX_BYTES = 65536;
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!java.util.Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) { chain.doFilter(request,response); return; }
        byte[] bytes = request.getContentLengthLong()>MAX_BYTES ? new byte[MAX_BYTES+1] : request.getInputStream().readNBytes(MAX_BYTES+1);
        if (bytes.length>MAX_BYTES) {
            response.setStatus(413); response.setContentType("application/problem+json");
            response.getWriter().write(JsonSupport.write(Map.of("type","urn:cutover:problem:payload-too-large","title","Payload too large","status",413,"code","PAYLOAD_TOO_LARGE","detail","The request body exceeds 64 KiB.")));
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public ServletInputStream getInputStream() {
                var source = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return source.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return source.read(target,offset,length); }
                    @Override public boolean isFinished() { return source.available()==0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Only blocking MVC request reads are supported."); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
}
