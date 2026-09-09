package com.qqlin.oa.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 序列化配置。
 *
 * 为什么要配这个：Spring Boot 默认给的 RedisTemplate 用的是 JDK 原生序列化
 * （JdkSerializationRedisSerializer），它有三个问题：
 *
 *   1. 存进去是二进制，用 redis-cli 看是一堆乱码，出问题没法直接查；
 *   2. 序列化结果里带着 Java 类的完整包路径，类一改路径，老数据就反序列化失败了；
 *   3. 其他语言（比如以后写个脚本做运维）根本读不懂。
 *
 * 这里改成：key 用字符串，value 用 JSON。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 注意这里的类名：Spring Boot 4 带的是 Jackson 3（groupId 是 tools.jackson.core），
        // 对应的序列化器是 GenericJacksonJsonRedisSerializer——不带 2 的那个。
        // 网上大量教程写的 GenericJackson2JsonRedisSerializer（带 2）是给 Jackson 2 用的，
        // 在 Boot 4 上会报类找不到。
        GenericJacksonJsonRedisSerializer jsonSerializer =
                GenericJacksonJsonRedisSerializer.builder()
                        // 序列化时把对象的真实类型一起写进 JSON（一个 "@class" 字段），
                        // 反序列化时才知道该还原成 DepartmentTreeVO 而不是 LinkedHashMap。
                        // 没有它，取出来强转就会 ClassCastException。
                        // 方法名带 Unsafe 是因为 Jackson 提醒：不加白名单地反序列化任意类型
                        // 有安全风险。我们存的是自己程序写进去的数据，风险可控。
                        .enableUnsafeDefaultTyping()
                        .build();

        // key 必须用字符串序列化。不配的话 key 会被当成对象序列化，
        // 存进去的 key 前面会多一段二进制前缀，导致你 set 进去却 get 不出来。
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
