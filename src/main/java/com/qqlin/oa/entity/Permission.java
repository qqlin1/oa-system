package com.qqlin.oa.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 权限点。
 *
 * 权限点是「最小粒度的操作」，比如 leave:approve 表示「可以审批请假」。
 *
 * 为什么不用角色字符串判断（if ("ADMIN".equals(role))）：
 *   1. 加一个角色就要改代码里所有判断的地方；
 *   2. 「谁能审批」这个规则变了，也要改代码；
 *   3. 代码里看不出某个方法到底需要什么权限。
 * 换成权限点之后，上面三件事都变成改数据，不动代码。
 *
 * 编码格式用「模块:动作」，方便按模块分组展示和做通配匹配。
 */
@TableName("sys_permission")
public class Permission {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String module;
    private Integer sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
