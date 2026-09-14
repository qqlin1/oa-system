package com.qqlin.oa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.oa.dto.LeaveResultMessage;
import com.qqlin.oa.entity.Notification;
import com.qqlin.oa.mapper.NotificationMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 消费「请假审批结果」消息，给申请人生成一条站内通知。
 *
 * 这里最重要的是「消费幂等」。
 *
 * 为什么必须做：消息队列保证的是「至少一次投递」，不是「恰好一次」。
 * 网络重传、消费者重启、位点回退，都会让同一条消息被投递第二次。
 * 不做幂等的话，用户会收到两条一模一样的通知。
 *
 * 怎么做的：通知表的 msg_id 上有唯一索引。
 * 第一次插入成功；第二次插入撞唯一键失败 —— 我们把它当成「已经处理过」，
 * 直接返回消费成功，而不是当成错误去重试。
 */
@Service
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    /** 同 LeaveResultProducer：ObjectMapper 线程安全，静态复用，不依赖 Spring 注入 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NotificationMapper notificationMapper;

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Value("${rocketmq.topic.leave-result}")
    private String topic;

    @Value("${rocketmq.consumer.group}")
    private String consumerGroup;

    private DefaultMQPushConsumer consumer;

    /**
     * 消费者是否已完成启动。
     *
     * 注意：start() 返回只代表客户端起来了，背后还有一步「队列分配」（rebalance）是异步的。
     * 在那之前消息不会流进来。测试如果一上来就发消息、然后干等，可能等不到 —— 表现就是偶发失败。
     * 所以对外暴露这个标志，让测试能先等就绪再发消息。
     */
    private volatile boolean ready = false;

    public NotificationConsumer(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    /** 应用启动后开始监听消息 */
    @PostConstruct
    public void start() {
        try {
            consumer = new DefaultMQPushConsumer(consumerGroup);
            consumer.setNamesrvAddr(nameServer);
            consumer.subscribe(topic, "*");
            consumer.registerMessageListener((MessageListenerConcurrently) this::consume);
            consumer.start();
            ready = true;
            log.info("RocketMQ 消费者已启动。topic={}, group={}", topic, consumerGroup);
        } catch (Exception e) {
            // 消费者起不来不应该让整个应用启动失败 —— 通知功能挂了，
            // 审批、请假这些主流程还能用。记下日志，后面可以排查。
            log.error("RocketMQ 消费者启动失败，通知功能不可用。", e);
        }
    }

    /** 应用关闭时摘掉消费者 */
    @PreDestroy
    public void stop() {
        ready = false;
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    /** 消费者是否已就绪（测试等它，避免偶发的时序失败） */
    public boolean isReady() {
        return ready;
    }

    private ConsumeConcurrentlyStatus consume(List<MessageExt> msgs,
                                              ConsumeConcurrentlyContext context) {
        for (MessageExt msgExt : msgs) {
            String msgId = msgExt.getKeys();
            try {
                String body = new String(msgExt.getBody(), StandardCharsets.UTF_8);
                LeaveResultMessage message = OBJECT_MAPPER.readValue(body, LeaveResultMessage.class);

                saveNotificationOnce(message);
                log.info("已生成通知。msgId={}, leaveId={}", msgId, message.getLeaveId());

            } catch (DuplicateNotificationException e) {
                // 同一条消息被重复投递 —— 属于正常情况，不是错误。
                // 已经处理过了就直接确认消费成功，不要返回「稍后重试」，
                // 否则它会一直重试这条其实已经处理好的消息。
                log.warn("消息重复投递，跳过。msgId={}", msgId);

            } catch (Exception e) {
                // 真正的处理失败（比如数据库抖动）才需要重试。
                log.error("处理审批结果消息失败，稍后重试。msgId={}", msgId, e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    /**
     * 写入通知。msg_id 唯一，重复插入会撞键 —— 那时抛 DuplicateNotificationException，
     * 让上层知道「这是重复消息」，而不是「处理出错」。
     */
    private void saveNotificationOnce(LeaveResultMessage message) {
        try {
            Notification notification = new Notification();
            notification.setUserId(message.getApplicantId());
            notification.setBizType("LEAVE");
            notification.setBizId(message.getLeaveId());
            notification.setContent(buildContent(message));
            notification.setMsgId(message.getMsgId());
            notificationMapper.insert(notification);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DuplicateNotificationException(e);
        }
    }

    private String buildContent(LeaveResultMessage message) {
        String result = "APPROVED".equals(message.getStatus()) ? "已通过" : "被驳回";
        String comment = (message.getComment() == null || message.getComment().isBlank())
                ? "无"
                : message.getComment();
        return "您的请假申请（单号 " + message.getLeaveId() + "）" + result + "，审批意见：" + comment;
    }

    /** 标记「这条消息已经处理过了」，用来和真正的处理失败区分开 */
    static class DuplicateNotificationException extends RuntimeException {
        DuplicateNotificationException(Throwable cause) {
            super(cause);
        }
    }
}
