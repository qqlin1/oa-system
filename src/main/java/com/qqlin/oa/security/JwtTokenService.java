package com.qqlin.oa.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtTokenService {
    private final SecretKey secretKey;
    private final long expiration;


    public JwtTokenService(@Value("${jwt.secret}") String secret,
                           @Value("${jwt.expiration}") long expiration) {
        this.secretKey = Keys.hmacShaKeyFor(
                Decoders.BASE64.decode(secret)
        );
        this.expiration = expiration;
    }
    public String generateToken(Long userId,String username){
        return generateToken(userId,username,0);
    }
    public String generateToken(Long userId,
                                String username,
                                Integer tokenVersion){
        Date now=new Date();
        Date expireTime=new Date(now.getTime()+expiration);
        return Jwts.builder().subject(String.valueOf(userId))
                .claim("username",username)
                .claim("tokenVersion",tokenVersion)
                .issuedAt(now)
                .expiration(expireTime)
                .signWith(secretKey)
                .compact();
    }
    public Claims parseToken(String token){
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    public Long getUserId(String token) {
        Claims claims=parseToken(token);
        return Long.valueOf(claims.getSubject());

    }
    public String getUsername(String token){
        Claims claims=parseToken(token);
        return claims.get("username", String.class);
    }
}
