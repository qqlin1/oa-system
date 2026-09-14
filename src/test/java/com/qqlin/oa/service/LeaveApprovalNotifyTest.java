package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.dto.LeaveCreateDTO;
import com.qqlin.oa.dto.LeaveResultMessage;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.LeaveRequest;
import com.qqlin.oa.entity.Notification;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.enums.LeaveStatus;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.LeaveRequestMapper;
import com.qqlin.oa.mapper.NotificationMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.support.TestRoleAssigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证「审批结果 → 消息 → 站内通知」这条异步链路。
 *
 * 两个关注点：
 *   1. 审批成功之后，申请人能收到一条通知（消息真的发出去了、也被消费了）
 *   2. 同一条消息被重复投递时，只产生一条通知（消费幂等）
 *
 * 消费是异步的，所以断言前要「等一会儿 + 轮询」，不能发完就立刻查。
 */
@SpringBootTest
class LeaveApprovalNotifyTest {

    @Autowired private LeaveService leaveService;
    @Autowired private LeaveResultProducer leaveResultProducer;
    @Autowired private NotificationConsumer notificationConsumer;
    @Autowired private NotificationMapper notificationMapper;
    @Autowired private LeaveRequestMapper leaveRequestMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private DepartmentMapper departmentMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TestRoleAssigner roleAssigner;

    private Long departmentId;
    private Long adminId;
    private Long employeeId;
    private Long leaveId;

    @BeforeEach
    void setUp() {
        // 先等消费者就绪，再发消息。
        // 消费者 start() 之后还有一步异步的队列分配，在那之前发的消息要等很久才被拉到，
        // 不先等的话这个用例会偶发失败（全量跑时更容易撞上）。
        waitForConsumerReady(20);

        // 先清历史残留，再创建本次的数据。
        // 顺序反过来的话，会把本次刚建好的测试用户也一起删掉。
        cleanUpTestNotifications();

        departmentId = createDepartment("通知测试部门_" + System.nanoTime());
        adminId = createUser("notify_admin_" + System.nanoTime(), "ADMIN");
        employeeId = createUser("notify_emp_" + System.nanoTime(), "USER");

        LeaveCreateDTO dto = new LeaveCreateDTO();
        dto.setLeaveType("ANNUAL");
        dto.setStartTime(LocalDateTime.now().plusDays(1));
        dto.setEndTime(LocalDateTime.now().plusDays(2));
        dto.setReason("通知测试：等待审批");
        leaveId = leaveService.createLeave(employeeId, dto);
    }

    /**
     * 清掉历次测试残留的通知。
     *
     * 必须按「测试用户」清，不能只按 bizId 清：
     * 消费是异步的，上一次测试跑完后消息可能才落到库里，
     * 那时候 tearDown 早执行完了 —— 只按 bizId 删是删不掉这些「迟到」的数据的。
     */
    private void cleanUpTestNotifications() {
        List<User> testUsers = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .like(User::getUsername, "notify_"));
        for (User u : testUsers) {
            notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getUserId, u.getId()));
        }
        // 顺手把残留的测试用户也清掉，避免越堆越多
        for (User u : testUsers) {
            userMapper.delete(new LambdaQueryWrapper<User>()
                    .like(User::getUsername, "notify_"));
        }
    }

    @AfterEach
    void tearDown() {
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, employeeId));
        leaveRequestMapper.deleteById(leaveId);
        userMapper.deleteById(adminId);
        userMapper.deleteById(employeeId);
        departmentMapper.deleteById(departmentId);
    }

    @Test
    @DisplayName("审批通过后，申请人应当收到一条站内通知")
    void shouldNotifyApplicantAfterApproval() {

        LeaveApprovalDTO approval = new LeaveApprovalDTO();
        approval.setDecision("APPROVED");
        approval.setApprovalComment("同意，注意休息");

        leaveService.approveLeave(adminId, leaveId, approval);

        // 消费是异步的：等到通知出现，最多等 15 秒。
        // 查询带上 userId 而不是只按 bizId —— 消费可能滞后到 tearDown 之后才落库，
        // 只按 bizId 查会把上一次测试残留的通知也数进来。
        List<Notification> notifications = waitForNotifications(leaveId, employeeId, 1, 15);

        assertEquals(1, notifications.size(),
                "审批通过后应当产生 1 条通知。如果这里是 0，说明消息没发出去或没被消费");

        Notification n = notifications.get(0);
        assertEquals(employeeId, n.getUserId(), "通知应当发给申请人本人");
        assertEquals(leaveId, n.getBizId(), "通知应当关联到这张请假单");
        assertNotNull(n.getMsgId(), "通知必须带消息ID，幂等靠它");
    }

    @Test
    @DisplayName("同一条消息重复投递，只应当产生一条通知（消费幂等）")
    void shouldNotCreateDuplicateNotificationWhenMessageRedelivered() {

        String msgId = UUID.randomUUID().toString();

        LeaveResultMessage message = new LeaveResultMessage();
        message.setMsgId(msgId);
        message.setLeaveId(leaveId);
        message.setApplicantId(employeeId);
        message.setStatus("APPROVED");
        message.setComment("幂等测试");

        // 同一条消息连发三次，模拟重复投递
        leaveResultProducer.send(message);
        leaveResultProducer.send(message);
        leaveResultProducer.send(message);

        waitForNotificationByMsgId(msgId, message, 15);

        // 按 msgId 精确统计：就是这个「唯一索引 + 重复投递」的验证点，
        // 不受其他测试残留数据的影响。
        long count = notificationMapper.selectCount(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getMsgId, msgId));

        assertEquals(1, count,
                "同一条消息投递 3 次，最终只应当产生 1 条通知 —— 这就是消费幂等");
    }

    /** 轮询等待通知出现，返回查到的通知列表（限定用户，避免残留干扰） */
    private List<Notification> waitForNotifications(Long bizId, Long userId, int expected, int maxSeconds) {
        for (int i = 0; i < maxSeconds * 2; i++) {
            long count = notificationMapper.selectCount(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getBizId, bizId)
                            .eq(Notification::getUserId, userId));
            if (count >= expected) {
                // 再稍微等一下，确保没有「第二条」正在来的路上
                sleep(500);
                return notificationMapper.selectList(
                        new LambdaQueryWrapper<Notification>()
                                .eq(Notification::getBizId, bizId)
                                .eq(Notification::getUserId, userId));
            }
            sleep(500);
        }
        return notificationMapper.selectList(
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getBizId, bizId)
                        .eq(Notification::getUserId, userId));
    }

    /** 等消费者完成启动（最多等 maxSeconds 秒） */
    private void waitForConsumerReady(int maxSeconds) {
        for (int i = 0; i < maxSeconds * 2; i++) {
            if (notificationConsumer.isReady()) {
                return;
            }
            sleep(500);
        }
    }

    /**
     * 等到指定 msgId 的通知出现（最多等 maxSeconds 秒）。
     *
     * 等的过程中每 1 秒补发一次：万一这一条恰好在消费者分配队列之前发出去被漏掉了，
     * 补发能保证它最终被消费到。补发进来的重复消息本就该被幂等挡掉，不影响「只产生 1 条」这个断言。
     */
    private void waitForNotificationByMsgId(String msgId, LeaveResultMessage message, int maxSeconds) {
        // 每 500 毫秒补发一次，maxSeconds 秒内共有 maxSeconds×2 次机会。
        // 消费链路是异步的（发 → Broker → 消费者拉取 → 写库），等待要给足余量，否则用例会偶发变红。
        for (int i = 0; i < maxSeconds * 2; i++) {
            long count = notificationMapper.selectCount(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getMsgId, msgId));
            if (count >= 1) {
                sleep(800);   // 给后面的重复投递一点时间露出马脚
                return;
            }
            leaveResultProducer.send(message);
            sleep(500);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Long createDepartment(String name) {
        Department d = new Department();
        d.setName(name);
        d.setParentId(0L);
        d.setStatus(1);
        d.setSort(0);
        departmentMapper.insert(d);
        return d.getId();
    }

    private Long createUser(String username, String role) {
        User u = new User();
        u.setUsername(username);
        u.setName(username);
        u.setPassword(passwordEncoder.encode("test123456"));
        u.setDepartmentId(departmentId);
        u.setStatus(1);
        u.setRole(role);
        u.setTokenVersion(0);
        userMapper.insert(u);
        roleAssigner.assign(u.getId(), role);
        return u.getId();
    }
}
