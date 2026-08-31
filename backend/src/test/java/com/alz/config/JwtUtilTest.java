package com.alz.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    @Test
    void createsAndValidatesSignedToken() {
        String token = JwtUtil.generateToken("test-user", "USER");

        assertTrue(JwtUtil.validate(token));
        assertEquals("test-user", JwtUtil.getUsername(token));
        assertEquals("USER", JwtUtil.getRole(token));
    }

    @Test
    void rejectsTamperedToken() {
        String token = JwtUtil.generateToken("test-user", "USER");
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertFalse(JwtUtil.validate(tampered));
    }
}
