package com.qqlin.oa.service;

import com.qqlin.oa.dto.UserCreateDTO;
import com.qqlin.oa.exception.UserNotFoundException;
import com.qqlin.oa.vo.UserVO;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class UserService {
    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public UserVO getById(Long id){
        User user=userMapper.selectById(id);
        if(user==null){
            throw new UserNotFoundException("用户不存在");
        }
        UserVO userVo=new UserVO();
        userVo.setId(user.getId());
        userVo.setName(user.getName());
        userVo.setPhone(user.getPhone());
        userVo.setCreateTime(user.getCreateTime());
        userVo.setUsername(user.getUsername());
        userVo.setUpdateTime(user.getUpdateTime());
        userVo.setDepartmentId(user.getDepartmentId());
        userVo.setStatus(user.getStatus());
        return userVo;
    }
    public List<UserVO> listUsers(){
        List<User> users=userMapper.selectList(null);
        List<UserVO> userVOList=new ArrayList<>();
        for (User user:users){
            UserVO userVo=new UserVO();
            userVo.setId(user.getId());
            userVo.setName(user.getName());
            userVo.setPhone(user.getPhone());
            userVo.setCreateTime(user.getCreateTime());
            userVo.setUsername(user.getUsername());
            userVo.setUpdateTime(user.getUpdateTime());
            userVo.setDepartmentId(user.getDepartmentId());
            userVo.setStatus(user.getStatus());
            userVOList.add(userVo);
        }
        return userVOList;
    }
    public UserVO createUser(UserCreateDTO dto){
        User user=new User();
        user.setUsername(dto.getUsername());
        user.setName(dto.getName());
        user.setPassword(dto.getPassword());
        user.setPhone(dto.getPhone());
        user.setDepartmentId(dto.getDepartmentId());
        user.setStatus(1);
        userMapper.insert(user);
        UserVO userVO=new UserVO();

        userVO.setId(user.getId());
        userVO.setUsername(user.getUsername());
        userVO.setName(user.getName());
        userVO.setPhone(user.getPhone());
        userVO.setDepartmentId(user.getDepartmentId());
       userVO.setStatus(user.getStatus());

        return userVO;
    }
}
