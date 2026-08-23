package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.Query;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.dto.UserCreateDTO;
import com.qqlin.oa.dto.UserLoginDTO;
import com.qqlin.oa.dto.UserQueryDTO;
import com.qqlin.oa.exception.UnauthorizedException;
import com.qqlin.oa.exception.UserNotFoundException;
import com.qqlin.oa.exception.UsernameAlreadyExistsException;
import com.qqlin.oa.vo.UserVO;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {
    private final PasswordEncoder passwordEncoder;

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper,PasswordEncoder passwordEncoder) {

        this.userMapper = userMapper;
        this.passwordEncoder=passwordEncoder;
    }

    public UserVO getById(Long id){
        User user=userMapper.selectById(id);
        if(user==null){
            throw new UserNotFoundException("用户不存在");
        }

        return toUserVO(user);
    }
    public PageResult<UserVO> listUsers(long current, long size, UserQueryDTO query){
        Page<User> page= new Page<>(current,size);
        LambdaQueryWrapper<User> wrapper=new LambdaQueryWrapper<>();
        boolean hasUsername=query.getUsername()!=null&&!query.getUsername().isBlank();
        wrapper.like(hasUsername,User::getUsername,query.getUsername());
        wrapper.eq(query.getStatus()!=null,User::getStatus, query.getStatus());
        wrapper.orderByDesc(User::getCreateTime);
        wrapper.orderByDesc(User::getId);
        Page<User> userPage = userMapper.selectPage(page, wrapper);
        List<UserVO> userVOList=new ArrayList<>();
        for (User user:userPage.getRecords()){
            userVOList.add(toUserVO(user));
        }
        return new PageResult<>(userVOList,userPage.getTotal(),userPage.getCurrent(),userPage.getSize());
    }
    public UserVO createUser(UserCreateDTO dto){
        Long count=userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername,dto.getUsername())
        );
        if(count>0)
        {
            throw new UsernameAlreadyExistsException("用户已存在");
        }
        User user=new User();
        user.setUsername(dto.getUsername());
        user.setName(dto.getName());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setPhone(dto.getPhone());
        user.setDepartmentId(dto.getDepartmentId());
        user.setStatus(1);
        userMapper.insert(user);
        return toUserVO(user);
    }
    public void updateStatus(Long id,Integer status){
        User user =new User();
        user.setId(id);
        user.setStatus(status);
        int affectRows=userMapper.updateById(user);
        if(affectRows==0){
            throw new UserNotFoundException("用户不存在");
        }
    }
        private UserVO toUserVO(User user){
        UserVO userVO=new UserVO();
        userVO.setId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setName(user.getName());
        userVO.setPhone(user.getPhone());
        userVO.setDepartmentId(user.getDepartmentId());
        userVO.setStatus(user.getStatus());
        userVO.setCreateTime(user.getCreateTime());
        userVO.setUpdateTime(user.getUpdateTime());
        return userVO;
    }
    public UserVO login(UserLoginDTO dto){
        User user=userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername,dto.getUsername())
        );
        if (user == null
                || !passwordEncoder.matches(
                dto.getPassword(),
                user.getPassword())) {

            throw new UnauthorizedException("用户名或密码错误");
        }
        if(!Integer.valueOf(1).equals(user.getStatus())){
            throw new UnauthorizedException("用户未认证");
        }
        return  toUserVO(user);
    }

}
