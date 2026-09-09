package com.qqlin.oa.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Redis 分布式锁的四个关键点。
 *
 * 其中「正常解锁能删掉」和「别人的标识解不掉」这两个用例
 * 顺带验证了解锁用的 Lua 脚本确实在执行——
 * 如果 Lua 静默失败（比如参数被序列化带上了引号），
 * 解锁会走 catch 分支什么都不做，这两个用例就会红。
 */
@SpringBootTest
class RedisLockTest {

    private static final String LOCK_KEY_PREFIX = "oa:lock:";

    @Autowired
    private RedisLockService redisLockService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void tearDown() {
        stringRedisTemplate.delete(LOCK_KEY_PREFIX + "test-lock");
    }

    @Test
    @DisplayName("同一把锁，第二个人抢不到")
    void secondTryShouldFail() {

        String tokenA = redisLockService.tryLock("test-lock");
        assertNotNull(tokenA, "第一个人应当加锁成功");

        String tokenB = redisLockService.tryLock("test-lock");
        assertNull(tokenB, "锁还被占着，第二个人应当抢不到");

        redisLockService.unlock("test-lock", tokenA);
    }

    @Test
    @DisplayName("解锁之后，锁能被再次获取")
    void shouldBeReacquirableAfterUnlock() {

        String tokenA = redisLockService.tryLock("test-lock");
        assertNotNull(tokenA);

        redisLockService.unlock("test-lock", tokenA);

        // 解锁之后 Redis 里这个 key 应当已经没了
        assertNull(redisLockService.currentToken("test-lock"),
                "解锁之后锁应当已经释放。如果这里还查得到，说明解锁的 Lua 脚本没生效");

        String tokenB = redisLockService.tryLock("test-lock");
        assertNotNull(tokenB, "解锁之后应当能重新加锁");

        redisLockService.unlock("test-lock", tokenB);
    }

    @Test
    @DisplayName("用别人的标识解锁，解不掉（防止误删）")
    void shouldNotUnlockWithWrongToken() {

        String tokenA = redisLockService.tryLock("test-lock");
        assertNotNull(tokenA);

        // 模拟：A 的锁过期了，B 抢到了锁；这时 A 拿着旧标识来解锁
        String wrongToken = "this-is-not-my-token";
        redisLockService.unlock("test-lock", wrongToken);

        assertNotNull(redisLockService.currentToken("test-lock"),
                "用错误的标识解锁，锁应当还在（否则就是把别人的锁删了）");
        assertEquals(tokenA, redisLockService.currentToken("test-lock"),
                "锁的值应当还是原来那个人设的");

        redisLockService.unlock("test-lock", tokenA);
    }

    @Test
    @DisplayName("锁到期之后会自动释放，不需要手动解锁")
    void shouldExpireAutomatically() throws InterruptedException {

        String tokenA = redisLockService.tryLock("test-lock", Duration.ofSeconds(1));
        assertNotNull(tokenA);

        // 等过期时间过去（1 秒的锁，等 1.3 秒留余量）
        Thread.sleep(1300);

        assertNull(redisLockService.currentToken("test-lock"),
                "锁到了过期时间应当自动消失——这条保证了机器崩溃时锁不会永久占着");

        String tokenB = redisLockService.tryLock("test-lock");
        assertNotNull(tokenB, "自动过期之后应当能重新加锁");

        redisLockService.unlock("test-lock", tokenB);
    }

    @Test
    @DisplayName("20 个线程同时抢锁，只有 1 个能拿到")
    void onlyOneThreadShouldAcquire() throws InterruptedException {

        int threadCount = 20;
        AtomicInteger acquired = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();

                    String token = redisLockService.tryLock("test-lock");
                    if (token != null) {
                        acquired.incrementAndGet();
                        redisLockService.unlock("test-lock", token);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("20 个线程抢锁，成功 " + acquired.get() + " 个");

        assertEquals(1, acquired.get(),
                "20 个线程同时抢同一把锁，应当恰好 1 个成功");
        assertTrue(acquired.get() >= 1, "至少要有一个成功，否则说明锁本身有问题");
    }
}
