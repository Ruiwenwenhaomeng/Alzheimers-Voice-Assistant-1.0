package com.alz.assistant.memory;

import com.alz.config.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ConversationOwnerResolver {

    private final StringRedisTemplate redisTemplate;

    public ConversationOwnerResolver(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public ConversationOwner resolve(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            if (!authorization.startsWith("Bearer ")) {
                throw new IllegalArgumentException("登录凭证格式无效");
            }
            String token = authorization.substring(7).trim();
            if (!JwtUtil.validate(token)) {
                throw new IllegalArgumentException("登录凭证无效或已过期");
            }
            String username = JwtUtil.getUsername(token);
            String role = JwtUtil.getRole(token);
            String sessionRole = redisTemplate.opsForValue().get("TOKEN:" + token);
            if (sessionRole == null || !sessionRole.equals(role)) {
                throw new IllegalArgumentException("登录已过期，请重新登录");
            }
            redisTemplate.expire("TOKEN:" + token, 30, TimeUnit.MINUTES);
            redisTemplate.expire("USER_TOKEN:" + username, 30, TimeUnit.MINUTES);
            return new ConversationOwner("USER", username);
        }
        String clientId = request.getHeader("X-Assistant-Client-Id");
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("匿名使用助手时缺少 X-Assistant-Client-Id");
        }
        try {
            return new ConversationOwner("ANONYMOUS", UUID.fromString(clientId.trim()).toString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("X-Assistant-Client-Id 必须是 UUID");
        }
    }
}
