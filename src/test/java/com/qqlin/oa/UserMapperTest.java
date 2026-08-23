package com.qqlin.oa;

import com.qqlin.oa.entity.User;
import com.qqlin.oa.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void testSelectById() {

        User user = userMapper.selectById(1L);

        assertNotNull(user);
        assertEquals(1L, user.getId());
        assertEquals("zhangsan", user.getUsername());
    }
}
