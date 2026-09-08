package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.MeetingRoomCreateDTO;
import com.qqlin.oa.entity.MeetingRoom;
import com.qqlin.oa.service.MeetingRoomService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("meeting-rooms")
public class MeetingRoomController {

    private final MeetingRoomService meetingRoomService;

    public MeetingRoomController(MeetingRoomService meetingRoomService) {
        this.meetingRoomService = meetingRoomService;
    }

    /** 管理员创建会议室 */
    @PostMapping
    public Result<Long> createMeetingRoom(@RequestAttribute("currentUserId") Long currentUserId,
                                          @Valid @RequestBody MeetingRoomCreateDTO dto) {
        return Result.success(meetingRoomService.createMeetingRoom(currentUserId, dto));
    }

    /** 查询启用中的会议室，供预订时选择 */
    @GetMapping
    public Result<List<MeetingRoom>> listRooms(@RequestAttribute("currentUserId") Long currentUserId) {
        return Result.success(meetingRoomService.listAvailableRooms());
    }

    /** 按 id 查单个会议室 */
    @GetMapping("/{id}")
    public Result<MeetingRoom> getRoom(@RequestAttribute("currentUserId") Long currentUserId,
                                       @PathVariable("id") Long id) {
        return Result.success(meetingRoomService.getById(id));
    }
}
