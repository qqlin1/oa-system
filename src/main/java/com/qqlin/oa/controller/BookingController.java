package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.BookingCreateDTO;
import com.qqlin.oa.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 预订会议室。
     *
     * 冲突检查与插入在同一条 SQL 里原子完成（INSERT ... WHERE NOT EXISTS），
     * 所以并发抢同一时段时只有一个能成功，其余拿 BookingConflictException。
     */
    @PostMapping
    public Result<Long> createBooking(@RequestAttribute("currentUserId") Long currentUserId,
                                      @Valid @RequestBody BookingCreateDTO dto) {
        return Result.success(bookingService.createBooking(currentUserId, dto));
    }
}
