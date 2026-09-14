package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.FileChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileChunkMapper extends BaseMapper<FileChunk> {

    /**
     * 查某个上传会话已经传上来的分片序号。
     *
     * 这就是「断点续传」的全部实现：前端拿着这个列表，只传缺的那些。
     * 不用扫描磁盘目录 —— 一次索引查询就够了。
     */
    @Select("SELECT chunk_index FROM sys_file_chunk WHERE upload_id = #{uploadId} ORDER BY chunk_index")
    List<Integer> selectUploadedIndexes(@Param("uploadId") String uploadId);
}
