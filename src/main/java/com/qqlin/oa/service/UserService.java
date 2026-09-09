package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.dto.UserCreateDTO;
import com.qqlin.oa.dto.UserLoginDTO;
import com.qqlin.oa.dto.UserQueryDTO;

import com.qqlin.oa.exception.ForbiddenException;
import com.qqlin.oa.exception.UnauthorizedException;
import com.qqlin.oa.exception.UserNotFoundException;
import com.qqlin.oa.exception.UsernameAlreadyExistsException;
import com.qqlin.oa.security.JwtTokenService;
import com.qqlin.oa.vo.LoginVO;
import com.qqlin.oa.vo.UserVO;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class UserService {
    private final PasswordEncoder passwordEncoder;

    private final UserMapper userMapper;

    private final JwtTokenService jwtTokenService;
    private final RateLimitService rateLimitService;

    /** 同一个账号在一个统计窗口内最多允许密码错几次 */
    private static final int MAX_LOGIN_FAILURES = 5;
    /** 登录失败次数的统计窗口 */
    private static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(1);

    public UserService(UserMapper userMapper,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService,
                       RateLimitService rateLimitService) {

        this.userMapper = userMapper;
        this.passwordEncoder=passwordEncoder;
        this.jwtTokenService=jwtTokenService;
        this.rateLimitService=rateLimitService;
    }

    public UserVO getById(Long id){

        User user=userMapper.selectById(id);
        if(user==null){
            throw new UserNotFoundException("用户不存在");
        }

        return toUserVO(user);
    }
    public PageResult<UserVO> listUsers(Long currentUserId,long current, long size, UserQueryDTO query){
        requireAdmin(currentUserId);
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
    public UserVO createUser(Long currentUserId,UserCreateDTO dto){
        requireAdmin(currentUserId);
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
        user.setRole("USER");
        userMapper.insert(user);
        return toUserVO(user);
    }
    public void updateStatus(Long currentUserId,Long id,Integer status){
        requireAdmin(currentUserId);
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
        userVO.setRole(user.getRole());
        userVO.setCreateTime(user.getCreateTime());
        userVO.setUpdateTime(user.getUpdateTime());
        return userVO;
    }
    public LoginVO login(UserLoginDTO dto){
        // 防暴力破解：这个账号在窗口内已经错够多次了，直接挡回去，连数据库都不查。
        // 放在查库之前很重要——不然攻击者照样能靠疯狂试密码把数据库压垮。
        String limitKey = "login-fail:" + dto.getUsername();
        if (rateLimitService.hasExceeded(limitKey, MAX_LOGIN_FAILURES)) {
            throw new UnauthorizedException(
                    "密码错误次数过多，请 " + LOGIN_FAIL_WINDOW.toMinutes() + " 分钟后再试");
        }

        User user=userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername,dto.getUsername())
        );
        if (user == null
                || !passwordEncoder.matches(
                dto.getPassword(),
                user.getPassword())) {

            // 记一次失败。只计数不判断——下一次请求进来时由上面那段判断是否超限
            rateLimitService.recordFailure(limitKey, LOGIN_FAIL_WINDOW);
            throw new UnauthorizedException("用户名或密码错误");
        }
        if(!Integer.valueOf(1).equals(user.getStatus())){
            throw new UnauthorizedException("用户被禁用");
        }

        // 走到这里说明密码对了。把之前攒下的失败记录清掉——
        // 正常用户偶尔输错两次不该被算进下一次的限额里
        rateLimitService.clear(limitKey);

        String token=jwtTokenService.generateToken(
                user.getId(),
                user.getUsername(),
                user.getTokenVersion()
        );
        LoginVO loginVO=new LoginVO();
        loginVO.setToken(token);
        loginVO.setUser(toUserVO(user));
        return  loginVO;
    }
    public UserVO getByIdForCurrentUser(Long  targetId,Long currentId){
        User user=getCurrentActiveUser(currentId);
        boolean isAdmin="ADMIN".equals(user.getRole());
        boolean isSelf=currentId.equals(targetId);
        if(!isSelf && !isAdmin){
            throw new ForbiddenException("无权查看其他用户信息");
        }

        return getById(targetId);
    }
    public void ensureCurrentUserActive(Long currentUserid,Integer tokenVersion){
        User currentUser=getCurrentActiveUser(currentUserid);
        if(!Objects.equals(currentUser.getTokenVersion(),tokenVersion)){
            throw new UnauthorizedException("登录状态已失效");
        }
    }
    public void logout(Long currentUserId){
        User user=getCurrentActiveUser(currentUserId);
        User updateUser=new User();
        updateUser.setId(user.getId());
        updateUser.setTokenVersion(user.getTokenVersion()+1);
        int affectedRows=userMapper.updateById(updateUser);
        if(affectedRows==0){
            throw new UnauthorizedException("登录已失效");
        }
    }

    private User getCurrentActiveUser(Long currentUserId){
        User currentUser=userMapper.selectById(currentUserId);
        if (currentUser==null||!Integer.valueOf(1).equals(currentUser.getStatus())){
            throw new UnauthorizedException("登陆状态已失效");
        }
        return currentUser;
    }
    public void requireAdmin(Long currentUserId) {
        User currentUser = getCurrentActiveUser(currentUserId);

        if (!"ADMIN".equals(currentUser.getRole())) {
            throw new ForbiddenException("需要管理员权限");
        }
    }
    public boolean hasUsersInDepartment(Long departmentId){
        Long count=userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(
                        User::getDepartmentId,
                        departmentId
                )
        );
        return count>0;

    }

}

