package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.Booking;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 查询「与指定时段重叠」的预订有多少条。
     *
     * 两个时间段重叠的判断条件只有一行，但要理解它：
     *
     *     已有.start < 新订.end   并且   已有.end > 新订.start
     *
     * 用两个例子验证一遍就明白了（已有预订是 9:00-10:00）：
     *
     *   新订 8:30-9:30  →  9:00 < 9:30 成立，10:00 > 8:30 成立  → 重叠，要拦住
     *   新订 9:30-10:30 →  9:00 < 10:30 成立，10:00 > 9:30 成立 → 重叠，要拦住
     *   新订 10:00-11:00 → 9:00 < 11:00 成立，10:00 > 10:00 不成立 → 不重叠，可以订
     *
     * 注意最后一条：一个的结束时间正好等于另一个的开始时间，不算重叠。
     * 所以两边用的都是严格小于/大于，不能写成小于等于/大于等于。
     *
     * @return 重叠的预订条数。0 表示这个时段是空的
     */
    @Select("""
            SELECT COUNT(*) FROM sys_booking
            WHERE room_id = #{roomId}
              AND status = 'BOOKED'
              AND start_time < #{endTime}
              AND end_time > #{startTime}
            """)
    long countOverlap(@Param("roomId") Long roomId,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime);

    /**
     * 把「检查冲突」和「插入」合并成一条 SQL，中间不留时间窗。
     *
     * 写法是 INSERT INTO ... SELECT ... FROM DUAL WHERE NOT EXISTS (...)：
     *   - 后面的 SELECT 不产生真实数据，只负责提供一个插入源
     *   - WHERE NOT EXISTS 为真才插，为假就什么都不做
     *
     * 包一层派生表（AS b）的原因和 U8 里那个 DELETE 一样：
     * MySQL 不允许在同一条语句里既改这张表又直接查这张表。
     *
     * @return 影响行数。1 表示订上了，0 表示撞上了冲突、没订上
     */
    @Insert("""
            INSERT INTO sys_booking
                (room_id, user_id, start_time, end_time, status, create_time, update_time)
            SELECT #{roomId}, #{userId}, #{startTime}, #{endTime}, 'BOOKED', NOW(), NOW()
            FROM DUAL
            WHERE NOT EXISTS (
                SELECT 1 FROM (
                    SELECT start_time, end_time FROM sys_booking
                    WHERE room_id = #{roomId} AND status = 'BOOKED'
                ) AS b
                WHERE b.start_time < #{endTime} AND b.end_time > #{startTime}
            )
            """)
    int insertIfNoOverlap(@Param("roomId") Long roomId,
                          @Param("userId") Long userId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);
}
