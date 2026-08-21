package com.qqlin.oa.controller;

import com.qqlin.oa.dto.UserCreateDTO;
import com.qqlin.oa.vo.UserVO;
import com.qqlin.oa.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }
    @PostMapping()
    public UserVO creatUser(@RequestBody UserCreateDTO dto){
        return userService.createUser(dto);
    }
    @GetMapping()
    public List<UserVO> listUsers() {
        return userService.listUsers();
    }

    @GetMapping("/{id}")
    public UserVO getById(@PathVariable Long id) {
        return userService.getById(id);
    }

}