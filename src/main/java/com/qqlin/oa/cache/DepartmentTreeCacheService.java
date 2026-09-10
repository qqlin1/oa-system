package com.qqlin.oa.cache;

import com.qqlin.oa.service.RedisLockService;
import com.qqlin.oa.vo.DepartmentTreeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * 组织架构树的缓存，用的是 Cache Aside 模式。
 *
 * Cache Aside 的两个规则：
 *
 *   读的时候：先查 Redis → 有就直接返回 → 没有就查数据库 → 写回 Redis → 返回
 *   写的时候：先更新数据库 → 再删除 Redis 里的缓存
 *
 * 这个模式也叫「旁路缓存」：缓存不是挡在数据库前面自动帮你挡请求，
 * 而是放在旁边，由你的代码显式地去读它、去删它。
 */
@Service
public class DepartmentTreeCacheService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentTreeCacheService.class);

    /** 缓存 key。用冒号分层是 Redis 的命名惯例，等价于目录结构，方便管理和批量清理。 */
    private static final String TREE_KEY = "oa:dept:tree";

    /**
     * 基础过期时间 10 分钟。
     *
     * 为什么要有过期时间：Cache Aside 在极端并发下仍然存在不一致窗口
     * （比如删缓存那一步失败了），过期时间是最后一道兜底——
     * 就算缓存脏了，最多脏 10 分钟，之后自动恢复。
     */
    private static final long BASE_TTL_MINUTES = 10;

    /**
     * 过期时间上再随机加 0 到 60 秒。
     *
     * 这是防「缓存雪崩」的常规手段：如果所有 key 都在同一秒过期，
     * 那一瞬间所有请求会同时打到数据库。加个随机值让过期时间散开，
     * 请求就不会挤在同一刻了。
     */
    private static final int TTL_JITTER_SECONDS = 60;

    /**
     * 重建缓存用的锁，用的是 Redis 分布式锁。
     *
     * 它防的是「缓存击穿」：热点 key 过期的那一瞬间，大量请求同时发现缓存没了，
     * 于是全部涌去查数据库重建。有了这把锁，同一时刻只允许一个线程去重建，
     * 其余线程等它建好之后直接读缓存。
     *
     * 为什么不用 Java 自带的 ReentrantLock：那把锁只能锁住「当前这台机器上的线程」。
     * 应用部署了 3 台的话，3 台机器各锁各的，仍然会有 3 个请求同时去重建缓存。
     * Redis 是所有机器共用的一个点，在这里加锁才锁得住全部实例。
     * （这一版就是从本地锁改过来的，改动只在这几行。）
     */
    private final RedisLockService redisLockService;

    /** 锁的名字。 */
    private static final String REBUILD_LOCK_NAME = "dept-tree-rebuild";

    /**
     * 抢不到锁时的等待策略：最多重试 10 次、每次等 50 毫秒，合计最多 500 毫秒。
     * 超过就自己查库兜底 —— 不能无限等，原因见 getOrLoad 里的说明。
     */
    private static final int RETRY_TIMES = 10;
    private static final long RETRY_SLEEP_MILLIS = 50;

    private final RedisTemplate<String, Object> redisTemplate;
    private final Random random = new Random();

    public DepartmentTreeCacheService(RedisTemplate<String, Object> redisTemplate,
                                      RedisLockService redisLockService) {
        this.redisTemplate = redisTemplate;
        this.redisLockService = redisLockService;
    }

    /**
     * 读缓存；缓存里没有就用 loader 查数据库，查到之后写回缓存。
     *
     * @param loader 真正去查库并组装树的那段逻辑。
     *               设计成 Supplier（一个「还没执行的代码块」）是为了让它延迟执行：
     *               缓存命中的时候根本不会调用它，一次数据库都不查。
     */
    public List<DepartmentTreeVO> getOrLoad(Supplier<List<DepartmentTreeVO>> loader) {

        // 第一关：先查缓存。
        // 绝大多数请求在这一步就返回了，根本碰不到下面那把锁——
        // 缓存命中的时候加锁是没有意义的开销。
        Object cached = readFromCache();
        if (cached != null) {
            return castToTree(cached);
        }

        // 缓存没了，准备重建。抢分布式锁：同一时刻只放一个线程进去重建。
        String lockToken = redisLockService.tryLock(REBUILD_LOCK_NAME);
        if (lockToken == null) {
            // 抢不到锁，说明别的实例（或别的线程）正在重建。
            //
            // 这里的关键是「等一下再读缓存」，而不是立刻自己去查库：
            // 别人可能再过几十毫秒就建好了，等一等就能直接拿到现成的。
            // 如果一抢不到就自己查库，20 个请求照样一起去查 —— 击穿根本没防住。
            //
            // 但也不能无限等：万一重建卡住了（慢查询、数据库抖动），
            // 无限等会把所有请求线程都堵死。所以设一个重试上限，
            // 超过之后自己查库兜底 —— 慢一点可以接受，卡死不行。
            for (int i = 0; i < RETRY_TIMES; i++) {
                sleepBriefly();
                cached = readFromCache();
                if (cached != null) {
                    return castToTree(cached);
                }
            }
            return loader.get();
        }

        try {
            // 第二关：拿到锁之后，再查一次缓存。
            //
            // 在当前这个「抢不到锁就等待重试」的实现里，第二关其实很少触发
            // （实测：注释掉它，20 线程依然只重建 1 次）。
            // 它真正关键的场景是「阻塞等锁」的写法 —— 见类注释里的说明。
            // 保留它成本极低，且能防住以后改实现时踩坑，所以留着。
            cached = readFromCache();
            if (cached != null) {
                return castToTree(cached);
            }

            // 确实还没人建，由我来建
            List<DepartmentTreeVO> tree = loader.get();
            writeToCache(tree);
            return tree;

        } finally {
            // 无论成功还是抛异常，锁一定要释放。
            // 传 lockToken 是为了核对：只有加锁时拿到的那个标识才能解开这把锁，
            // 防止误删别的实例后来加的锁。
            redisLockService.unlock(REBUILD_LOCK_NAME, lockToken);
        }
    }

    @SuppressWarnings("unchecked")
    private List<DepartmentTreeVO> castToTree(Object cached) {
        return (List<DepartmentTreeVO>) cached;
    }

    /** 等一小会儿再去看缓存建好没有。中断时恢复中断标记，不吞掉。 */
    private void sleepBriefly() {
        try {
            Thread.sleep(RETRY_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Object readFromCache() {
        try {
            return redisTemplate.opsForValue().get(TREE_KEY);
        } catch (Exception e) {
            // 缓存出任何问题（Redis 连不上、数据反序列化失败），都不能让接口挂掉。
            // 缓存是加速手段，不是数据来源——它挂了应该退化成直接查库，
            // 而不是让整个接口 500。
            log.warn("读取部门树缓存失败，本次直接查数据库。key={}", TREE_KEY, e);
            return null;
        }
    }

    private void writeToCache(List<DepartmentTreeVO> tree) {
        try {
            redisTemplate.opsForValue().set(TREE_KEY, tree, randomTtl());
        } catch (Exception e) {
            log.warn("写入部门树缓存失败，不影响本次返回。key={}", TREE_KEY, e);
        }
    }

    /**
     * 删除缓存。所有改动部门数据的地方都要调它。
     *
     * 注意这里是「删除」而不是「更新」缓存。为什么不直接把新树算好写进去？
     *
     *   1. 树是算出来的，不是一行数据，更新它的代价比删掉它高；
     *   2. 删掉之后下次有人读才会重算，如果这次改完之后根本没人读，那就白算了一次；
     *   3. 更重要的是，更新缓存要处理并发顺序问题（两个更新同时发生，
     *      谁先写进去谁后写进去），删除则没有这个问题——反正谁删都是删。
     */
    public void evict() {
        try {
            redisTemplate.delete(TREE_KEY);
        } catch (Exception e) {
            // 删缓存失败也要吞掉：删不掉顶多是缓存脏一会儿，
            // 有过期时间兜底；抛出去则会让整个「创建部门」操作失败，
            // 明明部门已经建好了却给用户报个错，得不偿失。
            log.warn("删除部门树缓存失败。key={}", TREE_KEY, e);
        }
    }

    /**
     * 基础过期时间 + 0 到 60 秒的随机抖动。
     *
     * 不加随机值的话，每次写缓存都设成整整 10 分钟，
     * 大量 key 就会在同一时刻集体过期——这就是缓存雪崩。
     */
    private Duration randomTtl() {
        return Duration.ofMinutes(BASE_TTL_MINUTES)
                .plusSeconds(random.nextInt(TTL_JITTER_SECONDS));
    }
}
