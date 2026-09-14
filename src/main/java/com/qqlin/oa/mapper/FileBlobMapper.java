package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.FileBlob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileBlobMapper extends BaseMapper<FileBlob> {

    /**
     * 引用数 +1。
     *
     * 写成 `ref_count = ref_count + 1` 而不是「查出来、加一、写回去」：
     * 后者在并发下会丢更新（两个人同时秒传同一个文件，都读到 1，都写成 2，实际应该是 3）。
     * 让数据库自己算，天然是原子的。
     */
    @Update("UPDATE sys_file_blob SET ref_count = ref_count + 1 WHERE id = #{id}")
    int increaseRefCount(@Param("id") Long id);

    /**
     * 引用数 -1，并且不允许减到负数。
     *
     * 加 `ref_count > 0` 这个条件是为了防止重复删除把计数减成负数 ——
     * 负数会让「引用数为 0 才删物理文件」这个判断彻底失效。
     */
    @Update("UPDATE sys_file_blob SET ref_count = ref_count - 1 WHERE id = #{id} AND ref_count > 0")
    int decreaseRefCount(@Param("id") Long id);
}
