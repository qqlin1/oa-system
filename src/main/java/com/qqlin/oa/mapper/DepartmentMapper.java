package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.Department;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DepartmentMapper extends BaseMapper<Department> {

    /**
     * 原子地删除一个「没有被使用」的部门：
     * 检查有没有子部门、检查有没有员工、执行删除，这三件事在一条 SQL 里一次性完成。
     *
     * 为什么不能「先查再删」：两个操作之间有时间窗。别的线程可以在这个窗口里
     * 往该部门下面建一个子部门，等这边删完，那个新建的子部门就变成了孤儿节点
     * （parent_id 指向一个已经不存在的部门）。
     *
     * 为什么子查询外面要多包一层 (SELECT parent_id FROM sys_department)：
     * MySQL 不允许在 DELETE 语句里直接 SELECT 同一张表，
     * 包一层派生表（derived table）是绕过这个限制的常规写法。
     *
     * @return 影响行数。0 表示没删成——可能是部门不存在，也可能是它下面还有子部门或员工
     */
    @Delete("""
            DELETE FROM sys_department
            WHERE id = #{id}
              AND NOT EXISTS (
                  SELECT 1 FROM (SELECT parent_id FROM sys_department) AS child
                  WHERE child.parent_id = #{id}
              )
              AND NOT EXISTS (
                  SELECT 1 FROM sys_user u WHERE u.department_id = #{id}
              )
            """)
    int deleteIfUnused(@Param("id") Long id);

    /**
     * 对父部门这一行加排他锁，做「当前读」。
     *
     * 为什么不能用普通的 SELECT：MySQL 默认的普通读是 MVCC 快照读，不加任何锁，
     * 可能读到「别人已经删除但还没提交」的旧版本。结果是——
     * 我这边检查父部门「还在」，等我插完子部门，父部门其实早就被删了，孤儿节点又出现了。
     *
     * FOR UPDATE 是加锁的当前读，会读到最新已提交的数据，并且把这一行锁住，
     * 让并发的删除操作必须排队等我这个事务结束。
     *
     * 注意：锁只在事务内有效，所以调用方的方法必须带 @Transactional。
     *
     * @return 部门 ID；为 null 表示该部门已经不存在了
     */
    @Select("SELECT id FROM sys_department WHERE id = #{id} FOR UPDATE")
    Long selectIdForUpdate(@Param("id") Long id);
}
