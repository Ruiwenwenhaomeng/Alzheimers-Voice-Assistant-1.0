package com.alz.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleAuthorizationInterceptorTest {

    private final RoleAuthorizationInterceptor interceptor = new RoleAuthorizationInterceptor();

    @Test
    void rejectsMissingAuthenticatedRole() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, handler());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void rejectsIncorrectRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.AUTHENTICATED_ROLE_ATTRIBUTE, "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, handler());

        assertFalse(allowed);
        assertEquals(403, response.getStatus());
    }

    @Test
    void acceptsRequiredRoleFromClassAnnotation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.AUTHENTICATED_ROLE_ATTRIBUTE, "ADMIN");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler()));
    }

    private HandlerMethod handler() throws NoSuchMethodException {
        return new HandlerMethod(
                new AdminOnlyController(), AdminOnlyController.class.getMethod("endpoint"));
    }

    @RequireRole("ADMIN")
    static class AdminOnlyController {
        public void endpoint() {
        }
    }
}
