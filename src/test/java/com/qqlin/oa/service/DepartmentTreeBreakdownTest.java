package com.qqlin.oa.service;

import com.qqlin.oa.cache.DepartmentTreeCacheService;
import com.qqlin.oa.vo.DepartmentTreeVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证「缓存击穿」被防住了。
 *
 * 击穿是什么：热点 key 过期的那一瞬间，大量请求同时发现缓存没了，
 * 于是全部涌去查数据库重建。明明只需要重建一次，却重建了几十次。
 *
 * 怎么测：给 getOrLoad 传一个「带计数器的 loader」，数一数它被调用了几次。
 * loader 被调用 1 次 = 只重建了一次，防住了；
 * loader 被调用 20 次 = 20 个请求全都去查库了，没防住。
 *
 * 为什么 loader 里要 sleep 一下：不加锁的情况下，20 个线程可能碰巧
 * 挤在同一个瞬间执行，也可能碰巧错开。sleep 50 毫秒相当于把「查库要花时间」
 * 这件事放大，让不加锁时的错误结果稳定出现，而不是时灵时不灵。
 */
@SpringBootTest
class DepartmentTreeBreakdownTest {

    private static final String TREE_KEY = "oa:dept:tree";

    @Autowired
    private DepartmentTreeCacheService departmentTreeCacheService;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.delete(TREE_KEY);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(TREE_KEY);
    }

    @Test
    @DisplayName("缓存失效瞬间 20 个线程并发请求，只应当重建 1 次")
    void shouldRebuildOnlyOnceUnderConcurrency() throws InterruptedException {

        int threadCount = 20;
        AtomicInteger rebuildCount = new AtomicInteger(0);

        // 这个 loader 模拟「查库并组装树」：计数 +1，然后睡 50 毫秒
        Supplier<List<DepartmentTreeVO>> slowLoader = () -> {
            rebuildCount.incrementAndGet();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fakeTree("重建出来的树");
        };

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger nullResult = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();

                    List<DepartmentTreeVO> tree =
                            departmentTreeCacheService.getOrLoad(slowLoader);
                    if (tree == null) {
                        nullResult.incrementAndGet();
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

        System.out.println("20 个线程并发请求，重建次数 = " + rebuildCount.get());

        assertEquals(1, rebuildCount.get(),
                "缓存失效的瞬间，20 个请求同时进来，应当只有 1 个真正去重建。"
                        + "如果这里大于 1，说明互斥锁没生效");
        assertEquals(0, nullResult.get(), "每个线程都应当拿到非空的树");
    }

    @Test
    @DisplayName("缓存命中时，重建逻辑一次都不应当执行")
    void shouldNotRebuildWhenCacheHit() throws InterruptedException {

        // 先往 Redis 里放一份现成的树
        redisTemplate.opsForValue().set(TREE_KEY, fakeTree("现成的树"), Duration.ofMinutes(1));

        int threadCount = 20;
        AtomicInteger rebuildCount = new AtomicInteger(0);

        Supplier<List<DepartmentTreeVO>> loader = () -> {
            rebuildCount.incrementAndGet();
            return fakeTree("不该被用到");
        };

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    departmentTreeCacheService.getOrLoad(loader);
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

        assertEquals(0, rebuildCount.get(),
                "缓存里有数据的时候，20 个请求一个都不该触发重建");
    }

    @Test
    @DisplayName("重建完成后，后续请求应当直接读缓存，不再重建")
    void shouldReuseCacheAfterFirstRebuild() {

        AtomicInteger rebuildCount = new AtomicInteger(0);

        Supplier<List<DepartmentTreeVO>> loader = () -> {
            rebuildCount.incrementAndGet();
            return fakeTree("第一次建出来的树");
        };

        // 第一次：缓存空，会重建
        List<DepartmentTreeVO> first =
                departmentTreeCacheService.getOrLoad(loader);
        assertNotNull(first);
        assertEquals(1, rebuildCount.get(), "第一次应当重建");

        // 第二、第三次：缓存已经有了，不该再重建
        departmentTreeCacheService.getOrLoad(loader);
        departmentTreeCacheService.getOrLoad(loader);

        assertEquals(1, rebuildCount.get(),
                "第一次重建之后，后续请求应当直接读缓存，重建次数不应当再增加");
    }

    /**
     * 造一棵只有一个节点的假树。
     *
     * 这里必须用 ArrayList，不能用 List.of()。
     *
     * 原因：List.of() 返回的是不可变列表，它的实际类型是
     * java.util.ImmutableCollections$ListN，那是一个 final 类。
     * 而我们配的序列化规则是 DefaultTyping.NON_FINAL——final 类不写类型信息。
     * 结果就是存进 Redis 的 JSON 里少了类型标记，取出来的时候
     * Jackson 不知道该还原成什么，反序列化直接失败。
     *
     * （这个坑说明：往缓存里放的对象，尽量用普通的 ArrayList / HashMap，
     *  别用不可变集合。）
     */
    private List<DepartmentTreeVO> fakeTree(String name) {
        DepartmentTreeVO root = new DepartmentTreeVO();
        root.setId(1L);
        root.setName(name);
        root.setParentId(0L);
        root.setSort(0);
        root.setStatus(1);

        List<DepartmentTreeVO> tree = new ArrayList<>();
        tree.add(root);
        return tree;
    }
}
