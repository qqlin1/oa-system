package com.qqlin.oa.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtTokenServiceTest {

    private static final String TEST_SECRET =
            Base64.getEncoder().encodeToString(
                    "01234567890123456789012345678901"
                            .getBytes(StandardCharsets.UTF_8)
            );

    @Test
    void shouldGenerateAndParseToken() {
        JwtTokenService jwtTokenService =
                createJwtTokenService(7200000L);

        String token = jwtTokenService.generateToken(1L, "zhangsan");

        Claims claims = jwtTokenService.parseToken(token);

        assertEquals("1", claims.getSubject());
        assertEquals("zhangsan", claims.get("username", String.class));
    }

    @Test
    void shouldRejectTamperedToken() {
        JwtTokenService jwtTokenService =
                createJwtTokenService(7200000L);

        String token = jwtTokenService.generateToken(1L, "zhangsan");

        String tamperedToken = addFakeRoleToPayload(token);

        assertThrows(
                JwtException.class,
                () -> jwtTokenService.parseToken(tamperedToken)
        );
    }

    @Test
    void shouldRejectExpiredToken() {
        JwtTokenService jwtTokenService =
                createJwtTokenService(-1000L);

        String expiredToken =
                jwtTokenService.generateToken(1L, "zhangsan");

        assertThrows(
                ExpiredJwtException.class,
                () -> jwtTokenService.parseToken(expiredToken)
        );
    }

    private JwtTokenService createJwtTokenService(long expiration) {
        return new JwtTokenService(TEST_SECRET, expiration);
    }

    private String addFakeRoleToPayload(String token) {
        String[] parts = token.split("\\.");

        String payloadJson = new String(
                Base64.getUrlDecoder().decode(parts[1]),
                StandardCharsets.UTF_8
        );

        String tamperedPayloadJson =
                payloadJson.substring(0, payloadJson.length() - 1)
                        + ",\"role\":\"ADMIN\"}";

        String tamperedPayload =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                tamperedPayloadJson
                                        .getBytes(StandardCharsets.UTF_8)
                        );

        return parts[0] + "." + tamperedPayload + "." + parts[2];
    }
}