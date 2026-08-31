package com.alz.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHENTICATED_ROLE_ATTRIBUTE = "authenticatedRole";
    public static final String AUTHENTICATED_USERNAME_ATTRIBUTE = "authenticatedUsername";

    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "/",
            "/index.html",
            "/app.css",
            "/app.js",
            "/user/login",
            "/user/register",
            "/user/reset-password"
    );

    private final StringRedisTemplate stringRedisTemplate;

    public JwtAuthenticationFilter(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        if (PUBLIC_ENDPOINTS.contains(uri) || uri.startsWith("/assistant/")
                || isPublicHealthEndpoint(uri) || isPublicStaticResource(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "未登录");
            return;
        }

        String token = authorization.substring(7).trim();
        String username;
        String tokenRole;
        try {
            if (!JwtUtil.validate(token)) {
                writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录凭据无效或已过期");
                return;
            }
            username = JwtUtil.getUsername(token);
            tokenRole = JwtUtil.getRole(token);
        } catch (RuntimeException exception) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录凭据无效或已过期");
            return;
        }

        String sessionRole = stringRedisTemplate.opsForValue().get("TOKEN:" + token);
        if (sessionRole == null || !sessionRole.equals(tokenRole)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "登录已过期");
            return;
        }

        stringRedisTemplate.expire("TOKEN:" + token, 30, TimeUnit.MINUTES);
        stringRedisTemplate.expire("USER_TOKEN:" + username, 30, TimeUnit.MINUTES);

        request.setAttribute(AUTHENTICATED_USERNAME_ATTRIBUTE, username);
        request.setAttribute(AUTHENTICATED_ROLE_ATTRIBUTE, sessionRole);
        filterChain.doFilter(request, response);
    }

    private boolean isPublicStaticResource(String uri) {
        String lowerUri = uri.toLowerCase();
        return lowerUri.endsWith(".jpg")
                || lowerUri.endsWith(".jpeg")
                || lowerUri.endsWith(".png")
                || lowerUri.endsWith(".gif")
                || lowerUri.endsWith(".webp")
                || lowerUri.endsWith(".svg")
                || lowerUri.endsWith(".ico")
                || lowerUri.endsWith(".woff")
                || lowerUri.endsWith(".woff2")
                || lowerUri.endsWith(".ttf");
    }

    private boolean isPublicHealthEndpoint(String uri) {
        return "/actuator/health".equals(uri) || uri.startsWith("/actuator/health/");
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
