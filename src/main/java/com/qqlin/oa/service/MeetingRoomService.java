package com.qqlin.oa.service;

import com.qqlin.oa.dto.MeetingRoomCreateDTO;
import com.qqlin.oa.entity.MeetingRoom;
import com.qqlin.oa.exception.MeetingRoomNotFoundException;
import com.qqlin.oa.mapper.MeetingRoomMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MeetingRoomService {

    private final MeetingRoomMapper meetingRoomMapper;
    private final UserService userService;

    public MeetingRoomService(MeetingRoomMapper meetingRoomMapper, UserService userService) {
        this.meetingRoomMapper = meetingRoomMapper;
        this.userService = userService;
    }

    /**
     * 创建会议室（仅管理员）。
     */
    public Long createMeetingRoom(Long currentUserId, MeetingRoomCreateDTO dto) {
        userService.requireAdmin(currentUserId);
        MeetingRoom room = new MeetingRoom();
        room.setName(dto.getName().trim());
        room.setCapacity(dto.getCapacity() == null ? 0 : dto.getCapacity());
        room.setLocation(dto.getLocation());
        room.setStatus(1);
        meetingRoomMapper.insert(room);
        return room.getId();
    }

    /**
     * 只返回启用中的会议室，供预订时选择。
     */
    public List<MeetingRoom> listAvailableRooms() {
        return meetingRoomMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MeetingRoom>()
                        .eq(MeetingRoom::getStatus, 1)
                        .orderByAsc(MeetingRoom::getId));
    }

    public MeetingRoom getById(Long id) {
        MeetingRoom room = meetingRoomMapper.selectById(id);
        if (room == null) {
            throw new MeetingRoomNotFoundException("会议室不存在");
        }
        return room;
    }
}
