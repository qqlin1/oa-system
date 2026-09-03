package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.BookingCreateDTO;
import com.qqlin.oa.entity.Booking;
import com.qqlin.oa.entity.MeetingRoom;
import com.qqlin.oa.enums.BookingStatus;
import com.qqlin.oa.exception.BookingConflictException;
import com.qqlin.oa.exception.InvalidBookingRequestException;
import com.qqlin.oa.exception.MeetingRoomNotFoundException;
import com.qqlin.oa.mapper.BookingMapper;
import com.qqlin.oa.mapper.MeetingRoomMapper;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class BookingService {

    /**
     * 死锁重试上限。死锁是偶发的，一般重试一次就成了。
     */
    private static final int MAX_ATTEMPTS = 3;

    private final BookingMapper bookingMapper;
    private final MeetingRoomMapper meetingRoomMapper;

    public BookingService(BookingMapper bookingMapper, MeetingRoomMapper meetingRoomMapper) {
        this.bookingMapper = bookingMapper;
        this.meetingRoomMapper = meetingRoomMapper;
    }

    /**
     * 预订会议室：一条 SQL 原子完成「冲突检查 + 插入」。
     *
     * 【演进过程，面试时按这个顺序讲】
     *
     * 第一版是「先 SELECT 查冲突，没冲突再 INSERT」—— 典型的 check-then-act。
     * 单线程下完全正常，手动点几下试不出任何问题。但并发测试一跑就露馅：
     *
     *     20 个线程抢同一间会议室的同一时段 → 成功 13 个，库里留下 13 条预订记录
     *     报错：expected: <1> but was: <13>
     *
     * 原因：查到「没冲突」和真正执行 INSERT 之间隔着好几个 Java 语句的执行时间。
     * 这段窗口里，别的线程同样能查到「没冲突」，于是都插进去了 —— 这就是超卖。
     *
     * 修法：两步合并成一条 INSERT ... WHERE NOT EXISTS，
     * 由数据库一次性完成判断和写入，中间不再有窗口。
     * 修完之后同样 20 个线程，成功 1 个，库里只留 1 条。
     */
    public Long createBooking(Long userId, BookingCreateDTO dto) {
        LocalDateTime now = LocalDateTime.now();
        if (dto.getStartTime() == null || dto.getEndTime() == null) {
            throw new InvalidBookingRequestException("预订起止时间不能为空");
        }
        if (dto.getStartTime().isBefore(now)) {
            throw new InvalidBookingRequestException("预订开始时间不能早于当前时间");
        }
        if (!dto.getEndTime().isAfter(dto.getStartTime())) {
            throw new InvalidBookingRequestException("预订结束时间必须晚于开始时间");
        }

        MeetingRoom room = meetingRoomMapper.selectById(dto.getRoomId());
        if (room == null || !Integer.valueOf(1).equals(room.getStatus())) {
            throw new MeetingRoomNotFoundException("会议室不存在或已停用");
        }

        // 冲突检查与插入合并成一条 SQL。死锁是偶发的，失败就重试。
        for (int attempt = 1; ; attempt++) {
            try {
                int affectedRows = bookingMapper.insertIfNoOverlap(
                        dto.getRoomId(), userId, dto.getStartTime(), dto.getEndTime());
                if (affectedRows == 0) {
                    // 这是正常的业务冲突，不是故障，不需要重试
                    throw new BookingConflictException("该会议室在这个时段已被预订");
                }

                Booking booking = bookingMapper.selectOne(
                        new LambdaQueryWrapper<Booking>()
                                .eq(Booking::getRoomId, dto.getRoomId())
                                .eq(Booking::getUserId, userId)
                                .eq(Booking::getStartTime, dto.getStartTime())
                                .eq(Booking::getEndTime, dto.getEndTime())
                                .eq(Booking::getStatus, BookingStatus.BOOKED)
                                .orderByDesc(Booking::getId)
                                .last("LIMIT 1"));
                return booking.getId();

            } catch (DeadlockLoserDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                sleepBeforeRetry(attempt);
            }
        }
    }

    /**
     * 重试前等一小会儿，退避时间随重试次数递增，避免大家同时重试又撞在一起。
     */
    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
