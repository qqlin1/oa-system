package com.qqlin.oa.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.qqlin.oa.dto.DepartmentCreateDTO;
import com.qqlin.oa.dto.DepartmentParentUpdateDTO;
import com.qqlin.oa.entity.Department;

import com.qqlin.oa.exception.DepartmentAlreadyExistsException;
import com.qqlin.oa.exception.DepartmentInUseException;
import com.qqlin.oa.exception.DepartmentNotFoundException;
import com.qqlin.oa.exception.InvalidDepartmentHierarchyException;
import com.qqlin.oa.mapper.DepartmentMapper;

import com.qqlin.oa.vo.DepartmentTreeVO;
import com.qqlin.oa.vo.DepartmentVO;
import org.springframework.stereotype.Service;


import java.util.*;

@Service
public class DepartmentService {
    private final DepartmentMapper departmentMapper;
    private final UserService userService;
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
                        Department::getParentId,dto.getParentId()
                ).eq(Department::getName,
                        departmentName));
        if(count>0){
            throw new DepartmentAlreadyExistsException("同一上级部门下已存在同名部门");
        }


        Department department = new Department();
        department.setName(departmentName);
        department.setParentId(dto.getParentId());
        department.setLeaderId(dto.getLeaderId());
        department.setStatus(1);
        department.setSort(dto.getSort());

        departmentMapper.insert(department);

        return  toDepartmentVO(department);

    }
    public List<DepartmentTreeVO> getDepartmentTree(Long currentUserId){
        userService.requireAdmin(currentUserId);
        List<Department> departments=departmentMapper.selectList(
                new LambdaQueryWrapper<Department>().orderByAsc(
                        Department::getSort
                ).orderByAsc(
                        Department::getId
                )
        );
        Map<Long,DepartmentTreeVO> nodeMap=new HashMap<>();
        List<DepartmentTreeVO> roots=new ArrayList<>();
        for(Department department:departments){
            DepartmentTreeVO node=toDepartmentTreeVO(department);
            nodeMap.put(department.getId(), node);
            
        }
        for(Department department:departments){
            DepartmentTreeVO currentNode=nodeMap.get(department.getId());
            if(Long.valueOf(0L).equals(currentNode.getParentId())){
                roots.add(currentNode);
                continue;
            }
            DepartmentTreeVO parentNode=nodeMap.get(department.getParentId());
            parentNode.getChildren().add(currentNode);
        }
        return roots;
    }
    public void updateParent(Long currentUserId,
                             Long departmentId,
                             DepartmentParentUpdateDTO dto){
        userService.requireAdmin(currentUserId);
        Department currentDepartment=departmentMapper.selectById(departmentId);
        if(currentDepartment==null){
            throw new DepartmentNotFoundException("部门不存在");
        }
        Long newParentId=dto.getParentId();
        if(Objects.equals(currentDepartment.getParentId(),newParentId)){
            return;
        }
        validateParentChange(departmentId,newParentId);
        Long duplicateCount=departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>().eq(
                        Department::getParentId,dto.getParentId()
                ).eq(
                        Department::getName,currentDepartment.getName()
                ).ne(
                        Department::getId,departmentId
                )
        );
        if(duplicateCount>0){
            throw new DepartmentAlreadyExistsException("新上级部门下已存在同名部门");
        }
        Department updateDepartment=new Department();
        updateDepartment.setId(departmentId);
        updateDepartment.setParentId(newParentId);
        int affectRows=departmentMapper.updateById(updateDepartment);
        if(affectRows==0){
            throw new DepartmentNotFoundException("部门不存在");
        }
    }
    private void validateParentChange(Long departmentId,Long newParentId){
        List<Department> departments=departmentMapper.selectList(
                new LambdaQueryWrapper<>()
        );
        Map<Long,Department> departmentMap=new HashMap<>();
        for(Department department:departments){
            departmentMap.put(department.getId(),department);
        }
        Set<Long> visited=new HashSet<>();
        Long cursorId=newParentId;
        while(!Long.valueOf(0L).equals(cursorId)){
            if(departmentId.equals(cursorId)){
                throw new InvalidDepartmentHierarchyException("不能把部门移动到自己或者自己的下级部门");
            }
            if(!visited.add(cursorId)){
                throw new InvalidDepartmentHierarchyException("现有部门在存在层级关系");
            }
            Department cusorDepartment=departmentMap.get(cursorId);
            if(cusorDepartment==null){
                throw new DepartmentNotFoundException("上级部门不存在");
            }
            cursorId=cusorDepartment.getParentId();
        }

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
    public DepartmentTreeVO toDepartmentTreeVO(Department department){
        DepartmentTreeVO departmentTreeVO=new DepartmentTreeVO();
        departmentTreeVO.setId(department.getId());
        departmentTreeVO.setName(department.getName());
        departmentTreeVO.setSort(department.getSort());
        departmentTreeVO.setLeaderId(department.getLeaderId());
        departmentTreeVO.setParentId(department.getParentId());
        departmentTreeVO.setStatus(department.getStatus());
        return departmentTreeVO;
    }
    public boolean hasChildDepartments(Long departmentId){
        Long count=departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>().eq(
                        Department::getParentId,
                        departmentId
                )
        );
        return count>0;
    }
    public void deleteDepartment(Long currentUserId,Long departmentId){
        userService.requireAdmin(currentUserId);
        Department department=departmentMapper.selectById(departmentId);
        if(department==null){
            throw new DepartmentNotFoundException("部门不存在");
        }
        if(hasChildDepartments(departmentId)){
            throw new DepartmentInUseException("该部门下存在子部门，不能删除");
        }
        if(userService.hasUsersInDepartment(departmentId)){
            throw new DepartmentInUseException("该部门下存在员工，不能删除");
        }
        int affectRows=departmentMapper.deleteById(departmentId);
        if(affectRows==0){
            throw new DepartmentNotFoundException("部门不存在");
        }
    }

}
