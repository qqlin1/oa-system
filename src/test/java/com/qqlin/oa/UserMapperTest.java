package com.qqlin.oa;

import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    // 测试自己造的数据的主键，跑完要自己删掉
    private Long createdUserId;
    private String createdUsername;

    /**
     * 自己造数据，不依赖数据库里已有的任何一行。
     * 旧版本断言「id=1 一定叫 zhangsan」——这个假设换台机器就不成立，测试会挂。
     */
    @BeforeEach
    void setUp() {
        createdUsername = "test_usermapper_" + System.nanoTime();

        User user = new User();
        user.setUsername(createdUsername);
        user.setName("测试用户");
        user.setPassword("placeholder-not-used-in-this-test");
        user.setDepartmentId(0L);
        user.setStatus(1);
        user.setRole("USER");
        user.setTokenVersion(0);

        userMapper.insert(user);
        createdUserId = user.getId();
    }

    @AfterEach
    void tearDown() {
        if (createdUserId != null) {
            userMapper.deleteById(createdUserId);
        }
    }

    @Test
    @DisplayName("按主键查询：能查到自己刚插入的那条，且字段一致")
    void testSelectById() {
        User user = userMapper.selectById(createdUserId);

        assertNotNull(user);
        assertEquals(createdUserId, user.getId());
        assertEquals(createdUsername, user.getUsername());
        assertEquals("USER", user.getRole());
    }
}
