package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.DepartmentCreateDTO;
import com.qqlin.oa.entity.Department;
import com.qqlin.oa.entity.User;
import com.qqlin.oa.exception.DepartmentNotFoundException;
import com.qqlin.oa.mapper.DepartmentMapper;
import com.qqlin.oa.mapper.UserMapper;
import com.qqlin.oa.vo.DepartmentVO;

import java.util.Map;

public class DepartmentService {
    private DepartmentMapper departmentMapper;
    private UserService userService;
    public DepartmentService(DepartmentMapper departmentMapper, UserService userService) {
        this.departmentMapper=departmentMapper;
        this.userService=userService;
    }
    public DepartmentVO createDepartment( Long currentUserId,DepartmentCreateDTO dto){
        userService.requireAdmin(currentUserId);
        validateLeader(dto.getLeaderId());
        validateParent(dto.getParentId());
        String departmentName= dto.getName().trim();
        Long count=departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>().eq(
                        Department::getName,dto.getName()
                ).eq(Department::getLeaderId,
                        dto.getLeaderId())
        );
        if(count>0){
            throw new DepartmentNotFoundException("同一上级部门下已存在同名部门");
        }
        DepartmentVO departmentVO = new DepartmentVO();

        Department department = new Department();
        department.setName(departmentName);
        department.setParentId(dto.getParentId());
        department.setLeaderId(dto.getLeaderId());
        department.setStatus(1);
        department.setSort(dto.getSort());

        departmentMapper.insert(department);

        return  toDepartmentVO(department);

    }
    private void validateParent(Long parentId) {
        if (Long.valueOf(0L).equals(parentId)) {
            return;
        }
        Department parent = departmentMapper.selectById(parentId);
        if (parent == null) {
            throw new DepartmentNotFoundException("上级部门不存在");
        }


    }
    private  void validateLeader(Long leaderId){
        if (leaderId == null) {
            return;
        }
        userService.getById(leaderId);
    }
    public DepartmentVO toDepartmentVO(Department department){
        DepartmentVO departmentVO=new DepartmentVO();
        departmentVO.setId(department.getId());
        departmentVO.setName(department.getName());
        departmentVO.setSort(department.getSort());
        departmentVO.setLeaderId(department.getLeaderId());
        departmentVO.setParentId(department.getParentId());
        departmentVO.setStatus(department.getStatus());
        departmentVO.setCreateTime(department.getCreateTime());
        departmentVO.setUpdateTime(department.getUpdateTime());
        return departmentVO;
    }
}
