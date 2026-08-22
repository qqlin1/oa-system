package com.qqlin.oa.controller;

import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.dto.UserCreateDTO;
import com.qqlin.oa.dto.UserStatusUpdateDTO;
import com.qqlin.oa.vo.UserVO;
import com.qqlin.oa.service.UserService;
import jakarta.validation.Valid;
import org.apache.ibatis.annotations.Param;
import org.springframework.web.bind.annotation.*;
import com.qqlin.oa.common.Result;
import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }
    @PostMapping()
    public Result<UserVO> createUser(@Valid @RequestBody UserCreateDTO dto){

        return Result.success(userService.createUser(dto));
    }


    @GetMapping("/{id}")
    public Result<UserVO> getById(@PathVariable Long id) {
        return Result.success(userService.getById(id));
    }
    @PatchMapping("{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @Valid @RequestBody UserStatusUpdateDTO dto){
        userService.updateStatus(id,dto.getStatus());
        return Result.success();
    }
    @GetMapping()
    public Result<PageResult<UserVO>> getUserList(@RequestParam(defaultValue = "1") long current, @RequestParam (defaultValue = "10")long size){
        return Result.success(userService.listUsers(current,size));
    }
}