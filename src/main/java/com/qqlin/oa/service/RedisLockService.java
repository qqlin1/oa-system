package com.qqlin.oa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * 基于 Redis 的分布式锁。
 *
 * 为什么需要它：你之前防缓存击穿用的是 Java 的 ReentrantLock。
 * 那把锁只能锁住「当前这台机器上的线程」——应用部署 3 台，
 * 3 台机器会各锁各的，3 个请求仍然会同时去重建缓存。
 * Redis 是大家共用的一个点，在这里加锁才能锁住所有机器。
 *
 * 三件事必须做对，少一件都会出事：
 *   1. 加锁要设过期时间 —— 不然拿到锁的那台机器崩了，锁永远不释放；
 *   2. 解锁要核对标识   —— 不然会把别人后来加的锁给删了；
 *   3. 解锁要原子       —— 判断和删除是两步，中间锁过期了就会误删。
 */
@Service
public class RedisLockService {

    private static final Logger log = LoggerFactory.getLogger(RedisLockService.class);

    private static final String LOCK_PREFIX = "oa:lock:";

    /** 默认持锁时间。业务没执行完锁就过期是个真问题，见 tryLock 的注释。 */
    private static final Duration DEFAULT_EXPIRE = Duration.ofSeconds(10);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 解锁脚本：先看值是不是自己当初设的那个，是才删。
     *
     * 为什么不分成 GET 和 DEL 两步做：那两步之间不是原子的。
     * 假设线程 A 的锁刚好在这一瞬间过期、线程 B 抢到了锁，
     * 这时 A 再执行 DEL，删掉的就是 B 刚加的锁。
     * Lua 脚本在 Redis 里一次性执行完，中间不会有别的命令插进来。
     */
    private final DefaultRedisScript<Long> unlockScript;

    public RedisLockService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                        "    return redis.call('DEL', KEYS[1]) " +
                        "else " +
                        "    return 0 " +
                        "end"
        );
        script.setResultType(Long.class);
        this.unlockScript = script;
    }

    /**
     * 尝试加锁。
     *
     * 用的是 SET key 值 NX EX 秒数 这一条命令：
     *   NX = 只有 key 不存在时才设置（保证同一时刻只有一个人能设成功）
     *   EX = 顺便设上过期时间
     * 关键在于 NX 和 EX 是同一条命令一起完成的——
     * 如果分成 SETNX 和 EXPIRE 两条命令，中间崩了就会留下一个永不过期的锁。
     *
     * @return 加锁成功返回锁标识（解锁时要原样传回来），失败返回 null
     */
    public String tryLock(String lockKey) {
        return tryLock(lockKey, DEFAULT_EXPIRE);
    }

    public String tryLock(String lockKey, Duration expire) {
        // 锁标识：谁加的锁，只有他自己能解
        String token = UUID.randomUUID().toString();
        try {
            Boolean success = stringRedisTemplate.opsForValue()
                    .setIfAbsent(LOCK_PREFIX + lockKey, token, expire);
            return Boolean.TRUE.equals(success) ? token : null;
        } catch (Exception e) {
            log.warn("加锁失败，按未加锁处理。key={}", lockKey, e);
            return null;
        }
    }

    /**
     * 释放锁。
     *
     * @param token 加锁时返回的标识。传错了解不掉——这是故意的，
     *              就是为了防止误删别人加的锁。
     */
    public void unlock(String lockKey, String token) {
        if (token == null) {
            return;
        }
        try {
            stringRedisTemplate.execute(
                    unlockScript,
                    Collections.singletonList(LOCK_PREFIX + lockKey),
                    token
            );
        } catch (Exception e) {
            log.warn("解锁失败，锁会在过期时间到后自动释放。key={}", lockKey, e);
        }
    }

    /** 查一下锁当前的值（排查用） */
    public String currentToken(String lockKey) {
        try {
            return stringRedisTemplate.opsForValue().get(LOCK_PREFIX + lockKey);
        } catch (Exception e) {
            return null;
        }
    }
}
