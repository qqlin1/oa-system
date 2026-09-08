package com.qqlin.oa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qqlin.oa.entity.Idempotent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdempotentMapper extends BaseMapper<Idempotent> {
}

