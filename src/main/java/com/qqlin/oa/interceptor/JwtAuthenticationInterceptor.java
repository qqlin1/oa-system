package com.qqlin.oa.interceptor;

import com.qqlin.oa.exception.UnauthorizedException;
import com.qqlin.oa.security.JwtTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {
    private final JwtTokenService jwtTokenService;

    public JwtAuthenticationInterceptor(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }
    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handle){
        String authorization = request.getHeader("Authorization");

        if(authorization==null ||!authorization.startsWith("Bearer ")){
            throw new UnauthorizedException("请先登录");
        }
        String token=authorization.substring(7);
        if(token.isBlank()){
            throw new UnauthorizedException("请先登录");
        }
        try{
            Claims claims=jwtTokenService.parseToken(token);
            Long userId=Long.valueOf(claims.getSubject());
            String username=claims.get("username", String.class);
            request.setAttribute("currentUserId", userId);
            request.setAttribute("currentUsername", username);
            return true;
        } catch (ExpiredJwtException e) {
            throw new UnauthorizedException("token已过期");
        }catch (JwtException  | IllegalArgumentException e){
            throw new UnauthorizedException("token无效");
        }

    }

}
