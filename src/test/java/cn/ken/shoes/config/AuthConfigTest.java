package cn.ken.shoes.config;

import cn.ken.shoes.util.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigTest {

    private AuthConfig auth;

    @BeforeEach
    void setUp() {
        auth = new AuthConfig();
        ReflectionTestUtils.setField(auth, "apiToken", "secret-api-token");
    }

    private boolean pass(MockHttpServletRequest req, MockHttpServletResponse resp) throws Exception {
        return auth.preHandle(req, resp, new Object());
    }

    @Test
    void rejectsAnonymousWith401() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(pass(new MockHttpServletRequest("GET", "/setting/stockx/accounts"), resp));
        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("请先登录"));
    }

    @Test
    void acceptsApiTokenHeaderOrParam() throws Exception {
        MockHttpServletRequest header = new MockHttpServletRequest("POST", "/stockx/token");
        header.addHeader("api-token", "secret-api-token");
        assertTrue(pass(header, new MockHttpServletResponse()));

        MockHttpServletRequest param = new MockHttpServletRequest("POST", "/stockx/token");
        param.setParameter("api-token", "secret-api-token");
        assertTrue(pass(param, new MockHttpServletResponse()));

        MockHttpServletRequest wrong = new MockHttpServletRequest("POST", "/stockx/token");
        wrong.addHeader("api-token", "nope");
        assertFalse(pass(wrong, new MockHttpServletResponse()));
    }

    @Test
    void acceptsJwtFromBearerOrCookie() throws Exception {
        String jwt = JwtUtil.generateToken(1L);
        MockHttpServletRequest header = new MockHttpServletRequest("GET", "/x");
        header.addHeader("Authorization", "Bearer " + jwt);
        assertTrue(pass(header, new MockHttpServletResponse()));

        MockHttpServletRequest cookie = new MockHttpServletRequest("GET", "/x");
        cookie.setCookies(new Cookie(AuthConfig.TOKEN_COOKIE, jwt));
        assertTrue(pass(cookie, new MockHttpServletResponse()));

        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/x");
        forged.setCookies(new Cookie(AuthConfig.TOKEN_COOKIE, jwt + "x"));
        assertFalse(pass(forged, new MockHttpServletResponse()));
    }

    @Test
    void preflightPassesAndPublicPathsCoverCallbacks() throws Exception {
        assertTrue(pass(new MockHttpServletRequest("OPTIONS", "/x"), new MockHttpServletResponse()));
        assertTrue(java.util.List.of(AuthConfig.PUBLIC_PATHS).containsAll(java.util.List.of(
                "/user/login", "/ebay/notifications/**", "/ebay/oauth/callback", "/poison/callback")));
    }
}
