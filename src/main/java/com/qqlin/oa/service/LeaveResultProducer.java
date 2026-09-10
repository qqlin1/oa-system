package com.qqlin.oa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qqlin.oa.dto.LeaveResultMessage;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 发送「请假审批结果」消息。
 *
 * 用同步发送（send 会一直等到 Broker 返回确认），而不是单向发送或异步发送：
 *   - 单向发送（sendOneway）：发出去就不管了，消息丢了都不知道 —— 不能用在业务结果上
 *   - 异步发送：性能好，但要处理回调，这里用不上
 *   - 同步发送：Broker 确认收到才返回，拿不到确认就抛异常，由调用方决定怎么办
 *
 * 消息可靠性是三段的（生产端确认 + Broker 持久化 + 消费端 ack），
 * 这一段管的是「生产端确认」。
 */
@Service
public class LeaveResultProducer {

    private static final Logger log = LoggerFactory.getLogger(LeaveResultProducer.class);

    /** 消息标签。同一个 topic 下可以用 tag 再细分，这里只有一类消息，给个固定值。 */
    private static final String TAG = "RESULT";

    /**
     * ObjectMapper 是线程安全的，做成静态常量复用即可。
     * 不从 Spring 注入，是因为当前项目里并没有装配 ObjectMapper 这个 Bean
     * （Boot 4 的 webmvc 默认不带），硬要注入会启动失败。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final DefaultMQProducer producer;

    @Value("${rocketmq.topic.leave-result}")
    private String topic;

    public LeaveResultProducer(DefaultMQProducer producer) {
        this.producer = producer;
    }

    /**
     * 同步发送审批结果。
     *
     * @return 发送成功返回 true；失败返回 false（调用方决定是忽略还是补偿）
     */
    public boolean send(LeaveResultMessage message) {
        try {
            String body = OBJECT_MAPPER.writeValueAsString(message);

            Message msg = new Message(
                    topic,
                    TAG,
                    message.getMsgId(),          // 消息的业务 key，控制台排查时能按它搜
                    body.getBytes(StandardCharsets.UTF_8)
            );

            SendResult result = producer.send(msg);

            if (result.getSendStatus() == SendStatus.SEND_OK) {
                log.info("审批结果消息已发送。msgId={}, leaveId={}", message.getMsgId(), message.getLeaveId());
                return true;
            }

            log.warn("审批结果消息发送状态异常。msgId={}, status={}",
                    message.getMsgId(), result.getSendStatus());
            return false;

        } catch (Exception e) {
            // 发送失败不往上抛：通知是「锦上添花」，不能因为它失败就让审批操作回滚。
            // 审批本身已经成功了，只是通知没发出去 —— 记下日志，后续可以补偿。
            log.error("审批结果消息发送失败。msgId={}, leaveId={}",
                    message.getMsgId(), message.getLeaveId(), e);
            return false;
        }
    }
}
