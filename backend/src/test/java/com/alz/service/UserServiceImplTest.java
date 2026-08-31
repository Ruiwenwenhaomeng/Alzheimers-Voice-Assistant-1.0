package com.alz.service;

import com.alz.entity.User;
import com.alz.mapper.UserMapper;
import com.alz.mapper.UserProfileMapper;
import com.alz.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserProfileMapper userProfileMapper;

    private PasswordEncoder passwordEncoder;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        service = new UserServiceImpl(userMapper, userProfileMapper, passwordEncoder);
    }

    @Test
    void hashesPasswordWhenRegistering() {
        User user = user("new-user", "plain-password", null);

        service.register(user);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("USER", captor.getValue().getRole());
        assertNotEquals("plain-password", captor.getValue().getPassword());
        assertTrue(passwordEncoder.matches("plain-password", captor.getValue().getPassword()));
    }

    @Test
    void upgradesLegacyPlaintextPasswordAfterSuccessfulLogin() {
        User legacyUser = user("legacy", "old-password", "USER");
        when(userMapper.findByUsername("legacy")).thenReturn(legacyUser);

        User authenticated = service.login("legacy", "old-password");

        assertNotNull(authenticated);
        verify(userMapper).updateById(legacyUser);
        assertTrue(passwordEncoder.matches("old-password", legacyUser.getPassword()));
    }

    @Test
    void acceptsBcryptPasswordWithoutRewritingIt() {
        String hash = passwordEncoder.encode("correct-password");
        User user = user("hashed", hash, "USER");
        when(userMapper.findByUsername("hashed")).thenReturn(user);

        assertNotNull(service.login("hashed", "correct-password"));
        verify(userMapper, never()).updateById(user);
    }

    @Test
    void rejectsWrongPassword() {
        User user = user("user", passwordEncoder.encode("correct"), "USER");
        when(userMapper.findByUsername("user")).thenReturn(user);

        assertNull(service.login("user", "wrong"));
    }

    private User user(String username, String password, String role) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setPassword(password);
        user.setRole(role);
        return user;
    }
}
