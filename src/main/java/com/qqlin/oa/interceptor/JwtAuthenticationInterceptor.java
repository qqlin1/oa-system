package com.qqlin.oa.interceptor;

import com.qqlin.oa.exception.UnauthorizedException;
import com.qqlin.oa.security.JwtTokenService;
import com.qqlin.oa.service.UserService;
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
    private final UserService userService;

    public JwtAuthenticationInterceptor(JwtTokenService jwtTokenService, UserService userService) {
        this.jwtTokenService = jwtTokenService;
        this.userService = userService;
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

            Integer tokenVersion = claims.get("tokenVersion", Integer.class);

            userService.ensureCurrentUserActive(userId,tokenVersion);

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
