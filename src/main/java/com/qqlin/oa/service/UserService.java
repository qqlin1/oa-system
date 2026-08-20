package com.qqlin.oa.service;

import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.UserMapper;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }
    public User getById(Long id){
        return userMapper.selectById(id);
    }
}
