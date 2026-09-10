package com.qqlin.oa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * 限流服务，提供两种实现：固定窗口和滑动窗口。
 *
 * 为什么限流要放在 Redis 里做，而不是在 Java 里用个计数器：
 * 应用是多实例部署的时候，每台机器各自计数，
 * 你有 3 台机器、限 100 次，实际会放过 300 次。
 * Redis 是大家共用的一个计数点，数出来的才是总数。
 */
@Service
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private static final String KEY_PREFIX = "oa:ratelimit:";

    /**
     * 这里用 StringRedisTemplate，而不是配了 JSON 序列化的那个 RedisTemplate。
     *
     * 原因是踩过的坑：Lua 脚本的参数会按这个模板的序列化器来处理。
     * 用 JSON 序列化器时，数字 1757424000000 会被写成带引号的 "1757424000000"，
     * Lua 里 tonumber('"1757424000000"') 解析不出来，返回 nil，
     * 后面的算术运算直接让脚本报错。
     * 而脚本报错会被下面的 try-catch 吞掉走「放行」分支——
     * 表现出来就是限流完全没生效，而且从日志上看不出是脚本错了。
     *
     * 限流存的是纯数字字符串，用 StringRedisTemplate 最合适。
     */
    private final StringRedisTemplate stringRedisTemplate;

    public RateLimitService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 固定窗口限流：window 这段时间里最多放行 limit 次。
     *
     * 计数那条命令的原子性处理见 {@link #increase} 的注释——
     * 最直觉的「先 INCR 再 EXPIRE」会在中间断掉时导致永久限流。
     *
     * @return true 放行，false 被限流
     */
    public boolean tryAcquireFixedWindow(String businessKey, int limit, Duration window) {
        try {
            long count = increase(KEY_PREFIX + businessKey, window);
            return count <= limit;
        } catch (Exception e) {
            // Redis 挂了的时候要放行，不能拒绝所有请求。
            // 限流是保护手段，不能反过来变成故障源。
            log.warn("限流检查失败，本次放行。key={}", businessKey, e);
            return true;
        }
    }

    /**
     * 滑动窗口限流：任意长度 window 的时间区间内，最多放行 limit 次。
     *
     * 它解决的是固定窗口的临界问题——固定窗口在两个窗口的交界处
     * 能放过 2 倍的请求（上一个窗口末尾打满，下一个窗口开头再打满）。
     *
     * 做法是用 ZSet（有序集合）把每一次请求的时间戳都记下来：
     *   分数 score = 请求发生的毫秒时间戳
     *   成员 member = 一个唯一 ID（同一毫秒来了多个请求也不会互相覆盖）
     * 每次判断前先把窗口外的旧记录删掉，剩下的就是「最近这段时间里来了几次」。
     *
     * @return true 放行，false 被限流
     */
    public boolean tryAcquireSlidingWindow(String businessKey, int limit, Duration window) {
        String key = KEY_PREFIX + "sliding:" + businessKey;
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - window.toMillis();

            // ① 先把窗口外的旧记录删掉
            stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

            // ② 数一下窗口里还剩几次
            Long count = stringRedisTemplate.opsForZSet().zCard(key);

            // ③ 没超就记下这一次（member 用随机 ID，保证同一毫秒来的多个请求不会互相覆盖）
            if (count == null || count < limit) {
                stringRedisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now);

                // 过期时间必须比窗口长。
                // 如果正好等于窗口，key 会自己过期把整个 ZSet 清掉、计数归零 ——
                // 那实际效果就退化成固定窗口了，上面「删窗口外记录」那步等于白写。
                // 留成 2 倍，让「清理旧记录」这件事真正由 removeRangeByScore 来做。
                stringRedisTemplate.expire(key, window.multipliedBy(2));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("滑动窗口限流检查失败，本次放行。key={}", businessKey, e);
            return true;
        }
    }

    /**
     * 记一次失败。返回记完之后累计失败了几次。
     * 只计数，不做判断——判断用 {@link #hasExceeded}。
     */
    public long recordFailure(String businessKey, Duration window) {
        try {
            return increase(KEY_PREFIX + businessKey, window);
        } catch (Exception e) {
            log.warn("记录失败次数出错，本次按 0 处理。key={}", businessKey, e);
            return 0;
        }
    }

    /**
     * 计数 +1，返回加完之后的值。
     *
     * 没有用 Lua 脚本，而是用「SET NX + EX」配「INCR」两条命令：
     *   1. SET key 1 EX 秒数 NX —— 只有 key 不存在时才设，顺便把过期时间一起设上。
     *      这一条命令本身是原子的（NX 和 EX 由 Redis 一次性完成）。
     *   2. 上一步没设成功（说明 key 已经存在），就 INCR 加 1。
     *
     * 为什么不用最直觉的「INCR 之后判断是不是 1，是 1 就 EXPIRE」：
     * 那是两条命令，中间不是原子的。万一 INCR 成功、EXPIRE 没执行
     * （比如正好这一瞬间网络断了），这个 key 就永远不会过期，
     * 这个账号会被永久限流——比限流本身严重得多。
     *
     * 这个版本也还有一个极小的缝隙：key 恰好在第 1 步和第 2 步之间过期时，
     * 第 2 步的 INCR 会建出一个不带过期时间的 key。
     * 所以下面加了补偿：INCR 的结果正好是 1 说明它是新建的，补设过期时间。
     *
     * 要做到完全没有缝隙，就得把这几步包进 Lua 脚本让 Redis 一次性执行。
     */
    private long increase(String key, Duration window) {
        Boolean created = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", window);
        if (Boolean.TRUE.equals(created)) {
            return 1;
        }

        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 补偿：说明 key 是在上面两步之间过期的，这里补上过期时间
            stringRedisTemplate.expire(key, window);
        }
        return count == null ? 1L : count;
    }

    /** 当前已经累计了几次（没记过就是 0） */
    public long currentCount(String businessKey) {
        try {
            String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + businessKey);
            if (value == null) {
                return 0;
            }
            return Long.parseLong(value);
        } catch (Exception e) {
            log.warn("读取限流计数失败。key={}", businessKey, e);
            return 0;
        }
    }

    /** 是否已超限（只看不计数） */
    public boolean hasExceeded(String businessKey, int limit) {
        return currentCount(businessKey) >= limit;
    }

    /** 清零（比如登录成功了，之前的失败记录就不该继续算） */
    public void clear(String businessKey) {
        try {
            stringRedisTemplate.delete(KEY_PREFIX + businessKey);
        } catch (Exception e) {
            log.warn("清除限流计数失败。key={}", businessKey, e);
        }
    }
}
