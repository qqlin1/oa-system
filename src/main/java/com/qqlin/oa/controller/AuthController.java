package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.UserLoginDTO;
import com.qqlin.oa.service.UserService;
import com.qqlin.oa.vo.LoginVO;
import com.qqlin.oa.vo.UserVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody UserLoginDTO dto){
        return Result.success(userService.login(dto));
    }
    @PostMapping("/logout")
    public Result<Void> logout(@RequestAttribute("currentUserId") Long currentUserId){
        userService.logout(currentUserId);
        return Result.success();
    }
}
