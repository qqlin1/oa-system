package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.UserLoginDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.exception.UnauthorizedException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.vo.LoginVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证登录限流（防暴力破解）。
 *
 * 落点是 UserService.login：同一个账号 1 分钟内密码错满 5 次，
 * 之后的请求直接挡回去，连数据库都不查。
 */
@SpringBootTest
class LoginRateLimitTest {

    @Autowired
    private UserService userService;
    @Autowired
    private RateLimitService rateLimitService;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private DepartmentMapper departmentMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private String username;
    private String correctPassword;
    private Long departmentId;
    private Long userId;

    @BeforeEach
    void setUp() {
        departmentId = createDepartment();
        username = "test_ratelimit_" + System.nanoTime();
        correctPassword = "correct-pwd-123";
        userId = createUser(username, correctPassword);
        clearLimitKey();
    }

    @AfterEach
    void tearDown() {
        clearLimitKey();
        userMapper.delete(new LambdaQueryWrapper<User>()
                .like(User::getUsername, "test_ratelimit_"));
        departmentMapper.delete(new LambdaQueryWrapper<Department>()
                .like(Department::getName, "ratelimit_dept_"));
    }

    @Test
    @DisplayName("密码连续错 5 次后，第 6 次即使密码正确也会被拦")
    void shouldBlockAfterTooManyFailures() {

        for (int i = 1; i <= 5; i++) {
            UnauthorizedException e = assertThrows(
                    UnauthorizedException.class,
                    () -> login("wrong-password"),
                    "第 " + i + " 次用错密码登录应当失败");
            assertEquals("用户名或密码错误", e.getMessage(),
                    "前 5 次被拒的原因应当是密码错，不是触发限流");
        }

        // 第 6 次：用的是正确密码，但已经超限了，仍然要被拦
        UnauthorizedException e = assertThrows(
                UnauthorizedException.class,
                () -> login(correctPassword),
                "超过次数限制后，即使用正确密码也应当被拦");

        assertTrue(e.getMessage().contains("次数过多"),
                "第 6 次被拦的原因应当是「次数过多」而不是「密码错误」。"
                        + "实际返回的是：" + e.getMessage());
    }

    @Test
    @DisplayName("错误次数没到上限时，正常登录不受影响")
    void shouldAllowLoginWhenUnderLimit() {

        // 先错 3 次（没到 5 次的上限）
        for (int i = 0; i < 3; i++) {
            assertThrows(UnauthorizedException.class, () -> login("wrong-password"));
        }
        assertEquals(3, rateLimitService.currentCount(limitKey()),
                "错了 3 次，计数应当是 3");

        // 第 4 次用正确密码，应当能正常登录
        LoginVO vo = login(correctPassword);
        assertNotNull(vo.getToken(), "没到上限时用正确密码应当能登录成功");
    }

    @Test
    @DisplayName("登录成功后，之前的失败计数会被清零")
    void shouldClearFailureCountAfterSuccessfulLogin() {

        for (int i = 0; i < 3; i++) {
            assertThrows(UnauthorizedException.class, () -> login("wrong-password"));
        }
        assertEquals(3, rateLimitService.currentCount(limitKey()), "前置条件：已经错了 3 次");

        // 登录成功
        assertNotNull(login(correctPassword).getToken());

        assertEquals(0, rateLimitService.currentCount(limitKey()),
                "登录成功之后，之前攒下的失败记录应当被清零");
    }

    @Test
    @DisplayName("固定窗口：限额 3 次，第 4 次起被拒")
    void fixedWindowShouldRejectAfterLimit() {

        String key = "fixed-" + System.nanoTime();
        Duration window = Duration.ofMinutes(1);

        try {
            assertTrue(rateLimitService.tryAcquireFixedWindow(key, 3, window), "第 1 次放行");
            assertTrue(rateLimitService.tryAcquireFixedWindow(key, 3, window), "第 2 次放行");
            assertTrue(rateLimitService.tryAcquireFixedWindow(key, 3, window), "第 3 次放行");
            assertFalse(rateLimitService.tryAcquireFixedWindow(key, 3, window), "第 4 次应当被拒");
            assertFalse(rateLimitService.tryAcquireFixedWindow(key, 3, window), "第 5 次应当被拒");
        } finally {
            rateLimitService.clear(key);
        }
    }

    @Test
    @DisplayName("滑动窗口：限额 2 次，第 3 次被拒，窗口过去后恢复")
    void slidingWindowShouldRejectThenRecover() throws InterruptedException {

        String key = "sliding-" + System.nanoTime();
        Duration window = Duration.ofSeconds(1);

        try {
            assertTrue(rateLimitService.tryAcquireSlidingWindow(key, 2, window), "第 1 次放行");
            assertTrue(rateLimitService.tryAcquireSlidingWindow(key, 2, window), "第 2 次放行");
            assertFalse(rateLimitService.tryAcquireSlidingWindow(key, 2, window), "第 3 次应当被拒");

            // 等窗口过去（1 秒窗口，等 1.2 秒留点余量）
            Thread.sleep(1200);

            assertTrue(rateLimitService.tryAcquireSlidingWindow(key, 2, window),
                    "窗口过去之后，旧记录应当已被清掉，重新放行");
        } finally {
            rateLimitService.clear("sliding:" + key);
        }
    }

    /**
     * 这个用例专门防「删窗口外记录」那一步被漏掉。
     *
     * 为什么前面那个「窗口过去后恢复」的用例抓不到它：
     * 如果 key 的过期时间正好等于窗口，key 会自己过期、把整个 ZSet 清掉、
     * 计数归零 —— 那次通过是 Redis 过期救的，不是滑动窗口起作用。
     *
     * 所以这里改成「持续请求」：让 key 一直被刷新、不会过期，
     * 这样旧记录到底有没有被清理，就藏不住了。
     */
    @Test
    @DisplayName("滑动窗口：窗口滑走后应当持续释放配额，而不是一次性耗尽")
    void slidingWindowShouldReleaseQuotaAsWindowSlides() throws InterruptedException {

        String key = "sliding-" + System.nanoTime();
        Duration window = Duration.ofSeconds(1);

        try {
            int allowed = 0;
            // 3.2 秒里每 400 毫秒请求一次，共 8 次
            for (int i = 0; i < 8; i++) {
                if (rateLimitService.tryAcquireSlidingWindow(key, 2, window)) {
                    allowed++;
                }
                Thread.sleep(400);
            }

            System.out.println("滑动窗口 3.2 秒内共放行 " + allowed + " 次");

            // 有清理旧记录：窗口会滑走，配额持续释放 —— 放行次数应当明显多于限额 2
            // 没清理：前 2 次用完就永远不放行 —— 只会有 2 次
            assertTrue(allowed > 2,
                    "滑动窗口应当随时间释放配额。只放行 " + allowed
                            + " 次，说明旧记录没被清掉，已经退化成「总量限制」");
        } finally {
            rateLimitService.clear("sliding:" + key);
        }
    }

    // ---------- 工具方法 ----------

    private String limitKey() {
        return "login-fail:" + username;
    }

    private void clearLimitKey() {
        redisTemplate.delete("oa:ratelimit:" + limitKey());
    }

    private LoginVO login(String password) {
        UserLoginDTO dto = new UserLoginDTO();
        dto.setUsername(username);
        dto.setPassword(password);
        return userService.login(dto);
    }

    private Long createDepartment() {
        Department department = new Department();
        department.setName("ratelimit_dept_" + System.nanoTime());
        department.setParentId(0L);
        department.setStatus(1);
        department.setSort(0);
        departmentMapper.insert(department);
        return department.getId();
    }

    private Long createUser(String name, String rawPassword) {
        User user = new User();
        user.setUsername(name);
        user.setName(name);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        user.setRole("USER");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }
}
