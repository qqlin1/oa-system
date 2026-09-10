package com.qqlin.oa.config;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 生产者的装配。
 *
 * 为什么不用官方的 rocketmq-spring-boot-starter：
 * 项目用的是 Spring Boot 4，官方 starter 目前还跟不上，硬引会有兼容问题。
 * 原生客户端自己封装虽然多写几十行，但完全可控，也能看清每一步在做什么。
 */
@Configuration
public class RocketMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RocketMQConfig.class);

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.producer.group}")
    private String producerGroup;

    /**
     * destroyMethod = "shutdown"：Spring 容器关闭时会自动关掉生产者，
     * 避免应用退出后还留着网络连接。
     */
    /**
     * 注意：Bean 名不能叫 leaveResultProducer —— 那是我们自己写的业务类
     * （@Service LeaveResultProducer）的默认 Bean 名，重名会导致启动失败。
     */
    @Bean(name = "rocketMQProducer", destroyMethod = "shutdown")
    public DefaultMQProducer rocketMQProducer() throws MQClientException {
        DefaultMQProducer producer = new DefaultMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        // 发送失败时重试 2 次（默认就是 2，写出来是为了让人看见这个配置的存在）
        producer.setRetryTimesWhenSendFailed(2);
        producer.start();
        log.info("RocketMQ 生产者已启动。nameServer={}, group={}", nameServer, producerGroup);
        return producer;
    }
}
