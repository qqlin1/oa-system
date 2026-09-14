package com.qqlin.oa.service;

import com.qqlin.oa.entity.Role;
import com.qqlin.oa.mapper.PermissionMapper;
import com.qqlin.oa.mapper.RoleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * 权限查询。
 *
 * 每个受保护的接口都要问一句「这个人有没有这个权限」，所以这个查询在请求链路上，
 * 必须快。走的是和部门树一样的 Cache Aside：
 *
 *   先查 Redis → 命中直接用 → 没命中查库（三张表 join）→ 写回 Redis
 *
 * 缓存 key 按用户隔离：oa:perm:user:{userId}。
 * 用户角色变了要调 evict() 把缓存删掉，否则改权限要等 30 分钟才生效。
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private static final String PERMISSION_KEY_PREFIX = "oa:perm:user:";
    private static final String SCOPE_KEY_PREFIX = "oa:perm:scope:";

    private static final long TTL_MINUTES = 30;
    private static final int TTL_JITTER_SECONDS = 300;

    private final PermissionMapper permissionMapper;
    private final RoleMapper roleMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Random random = new Random();

    public PermissionService(PermissionMapper permissionMapper,
                             RoleMapper roleMapper,
                             RedisTemplate<String, Object> redisTemplate) {
        this.permissionMapper = permissionMapper;
        this.roleMapper = roleMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 查一个人拥有的全部权限编码。
     *
     * @return 权限编码集合，没有任何权限时返回空集合（不返回 null，
     *         调用方就不用每次都判空）
     */
    @SuppressWarnings("unchecked")
    public Set<String> getPermissions(Long userId) {
        if (userId == null) {
            return Set.of();
        }

        String key = PERMISSION_KEY_PREFIX + userId;

        Object cached = readQuietly(key);
        if (cached instanceof List<?> list) {
            return new HashSet<>((List<String>) list);
        }

        List<String> codes = permissionMapper.selectCodesByUserId(userId);
        if (codes == null) {
            codes = new ArrayList<>();
        }
        writeQuietly(key, new ArrayList<>(codes));
        return new HashSet<>(codes);
    }

    /** 判断有没有某个权限。 */
    public boolean hasPermission(Long userId, String code) {
        return getPermissions(userId).contains(code);
    }

    /**
     * 查这个人的数据权限范围。
     *
     * 一个人可能有多个角色，每个角色的 data_scope 不同，这里取「最宽的」——
     * 也就是数字最小的那个（1 全部 最宽，4 仅本人 最窄）。
     * 比如同时是「部门经理（2）」和「普通员工（4）」，应该按部门经理算。
     */
    public int getDataScope(Long userId) {
        if (userId == null) {
            return Role.SCOPE_SELF;
        }

        String key = SCOPE_KEY_PREFIX + userId;
        Object cached = readQuietly(key);
        if (cached instanceof Integer scope) {
            return scope;
        }

        List<Role> roles = roleMapper.selectByUserId(userId);
        int scope = roles.stream()
                .map(Role::getDataScope)
                .filter(java.util.Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(Role.SCOPE_SELF);

        writeQuietly(key, scope);
        return scope;
    }

    /** 这个人是不是管理员（数据权限为「全部」）。 */
    public boolean isAdmin(Long userId) {
        return getDataScope(userId) == Role.SCOPE_ALL;
    }

    /**
     * 清掉某个用户的权限缓存。
     *
     * 什么时候必须调：给用户加/减角色、停用角色、改了角色的权限配置。
     * 不调的话，改动要等 TTL 到期才生效 —— 表现就是「我明明改了权限，怎么还是不行」。
     */
    public void evict(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redisTemplate.delete(PERMISSION_KEY_PREFIX + userId);
            redisTemplate.delete(SCOPE_KEY_PREFIX + userId);
        } catch (Exception e) {
            // 删缓存失败不影响主流程：最坏情况是权限晚 30 分钟生效。
            // 抛出去反而会让「改权限」这个操作失败，得不偿失。
            log.warn("清除用户权限缓存失败。userId={}", userId, e);
        }
    }

    private Object readQuietly(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // 缓存挂了就退化成直接查库，不能让接口 500
            log.warn("读取权限缓存失败，本次直接查数据库。key={}", key, e);
            return null;
        }
    }

    private void writeQuietly(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value, randomTtl());
        } catch (Exception e) {
            log.warn("写入权限缓存失败，不影响本次鉴权。key={}", key, e);
        }
    }

    /** 基础 30 分钟 + 0~300 秒随机抖动，防止大量用户缓存同时失效。 */
    private Duration randomTtl() {
        return Duration.ofMinutes(TTL_MINUTES)
                .plusSeconds(random.nextInt(TTL_JITTER_SECONDS));
    }
}
