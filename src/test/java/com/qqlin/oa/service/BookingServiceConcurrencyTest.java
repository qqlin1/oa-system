package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.dto.BookingCreateDTO;
import com.qqlin.oa.entity.Booking;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.MeetingRoom;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.enums.BookingStatus;
import com.qqlin.oa.exception.BookingConflictException;
import com.qqlin.oa.mapper.BookingMapper;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.MeetingRoomMapper;
import com.qqlin.oa.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class BookingServiceConcurrencyTest {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private BookingMapper bookingMapper;
    @Autowired
    private MeetingRoomMapper meetingRoomMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private DepartmentMapper departmentMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long roomId;
    private Long userId;
    private Long departmentId;
    private LocalDateTime start;
    private LocalDateTime end;

    @BeforeEach
    void setUp() {
        departmentId = createDepartment("预订测试部门_" + System.nanoTime());
        userId = createUser("test_booker_" + System.nanoTime(), departmentId);
        roomId = createRoom("预订测试会议室_" + System.nanoTime());

        // 明天上午 9 点到 10 点 —— 所有线程抢的都是这一个时段
        start = LocalDate.now().plusDays(1).atTime(9, 0);
        end = LocalDate.now().plusDays(1).atTime(10, 0);
    }

    @AfterEach
    void tearDown() {
        // 关键：不能只删一条。错误版本会插进去很多条，必须按会议室全删干净
        if (roomId != null) {
            bookingMapper.delete(new LambdaQueryWrapper<Booking>()
                    .eq(Booking::getRoomId, roomId));
            meetingRoomMapper.deleteById(roomId);
        }
        if (userId != null) {
            userMapper.deleteById(userId);
        }
        if (departmentId != null) {
            departmentMapper.deleteById(departmentId);
        }
    }

    @Test
    @DisplayName("20 个人同时预订同一间会议室的同一个时段，只能有 1 个人成功")
    void shouldOnlyOneBookingSucceedUnderConcurrency() throws InterruptedException {

        int threadCount = 20;

        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);

        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherErrorCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    readyLatch.countDown();
                    startLatch.await();

                    BookingCreateDTO dto = new BookingCreateDTO();
                    dto.setRoomId(roomId);
                    dto.setStartTime(start);
                    dto.setEndTime(end);

                    bookingService.createBooking(userId, dto);

                    successCount.incrementAndGet();

                } catch (BookingConflictException e) {
                    conflictCount.incrementAndGet();

                } catch (Exception e) {
                    otherErrorCount.incrementAndGet();
                    e.printStackTrace();

                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        boolean allDone = doneLatch.await(30, TimeUnit.SECONDS);
        executorService.shutdown();

        long bookedInDb = bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .eq(Booking::getRoomId, roomId)
                        .eq(Booking::getStatus, BookingStatus.BOOKED));

        System.out.println("预订成功：" + successCount.get()
                + " 个，时段冲突：" + conflictCount.get()
                + " 个，其他异常：" + otherErrorCount.get() + " 个");
        System.out.println("数据库里 BOOKED 状态的记录：" + bookedInDb + " 条");

        assertTrue(allDone, "30 秒内所有线程应当都跑完");
        assertEquals(0, otherErrorCount.get(), "不应当出现预期之外的异常");

        assertEquals(1, successCount.get(),
                "20 个人抢同一间会议室的同一时段，应当恰好 1 个成功");
        assertEquals(threadCount - 1, conflictCount.get(),
                "其余 19 个应当拿到时段冲突异常");
        assertEquals(1, bookedInDb,
                "数据库里应当只有 1 条有效预订");
    }

    // ---------- 造数据的工具方法 ----------

    private Long createDepartment(String name) {
        Department department = new Department();
        department.setName(name);
        department.setParentId(0L);
        department.setStatus(1);
        department.setSort(0);
        departmentMapper.insert(department);
        return department.getId();
    }

    private Long createUser(String username, Long departmentId) {
        User user = new User();
        user.setUsername(username);
        user.setName(username);
        user.setPassword(passwordEncoder.encode("test123456"));
        user.setDepartmentId(departmentId);
        user.setStatus(1);
        user.setRole("USER");
        user.setTokenVersion(0);
        userMapper.insert(user);
        return user.getId();
    }

    private Long createRoom(String name) {
        MeetingRoom room = new MeetingRoom();
        room.setName(name);
        room.setCapacity(10);
        room.setLocation("测试楼层");
        room.setStatus(1);
        meetingRoomMapper.insert(room);
        return room.getId();
    }
}
