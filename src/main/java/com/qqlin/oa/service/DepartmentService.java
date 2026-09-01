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
import org.springframework.transaction.annotation.Transactional;


import java.util.*;

@Service
public class DepartmentService {
    private final DepartmentMapper departmentMapper;
    private final UserService userService;
    public DepartmentService(DepartmentMapper departmentMapper, UserService userService) {
        this.departmentMapper=departmentMapper;
        this.userService=userService;
    }
    /**
     * 加事务是为了让 validateParentWithLock 拿到的行锁能一直持有到方法结束。
     * 锁只在事务内有效，方法上没有 @Transactional 的话，
     * SELECT ... FOR UPDATE 执行完锁就释放了，等于没锁。
     */
    @Transactional
    public DepartmentVO createDepartment( Long currentUserId,DepartmentCreateDTO dto){
        userService.requireAdmin(currentUserId);
        validateLeader(dto.getLeaderId());
        validateParentWithLock(dto.getParentId());
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
            if(parentNode==null){
                // 父部门不存在：可能是历史遗留的脏数据（孤儿节点），
                // 也可能是组装过程中父部门刚好被别的线程删掉了。
                // 这里当作根节点处理，而不是让它空指针——
                // 写操作修好了也只能阻止新增脏数据，库里已有的脏数据读接口仍要能容忍。
                roots.add(currentNode);
                continue;
            }
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

    /**
     * 校验上级部门存在，并且对父部门那一行加排他锁，锁一直持有到本事务结束。
     *
     * 光修删除侧是不够的：创建侧同样存在「先查父部门在不在、再插入子部门」的时间窗。
     * 两侧都修才算闭环——
     *   删除侧：检查子部门、检查员工、删除，合并成一条 SQL；
     *   创建侧：锁住父部门这一行，让并发的删除必须排队等我插完。
     *
     * 这样两个方向都被堵死：
     *   创建先拿到锁 → 子部门插进去了，删除侧检测到有子部门，删不掉；
     *   删除先执行完 → 创建侧加锁读发现父部门已经没了，直接报错。
     */
    private void validateParentWithLock(Long parentId) {
        if (Long.valueOf(0L).equals(parentId)) {
            return;   // 根部门没有父部门，不需要锁
        }
        if (departmentMapper.selectIdForUpdate(parentId) == null) {
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
    /**
     * 判断部门下有没有子部门。
     *
     * 注意：这个方法只适合做「预检查提示」（比如前端要先弹个确认框），
     * 不能拿它当并发安全的依据——它返回 false 之后、真正执行删除之前，
     * 别的线程仍然可以往这个部门下面插子部门。
     * 并发安全的删除走 {@link com.qqlin.oa.mapper.DepartmentMapper#deleteIfUnused}。
     */
    public boolean hasChildDepartments(Long departmentId){
        Long count=departmentMapper.selectCount(
                new LambdaQueryWrapper<Department>().eq(
                        Department::getParentId,
                        departmentId
                )
        );
        return count>0;
    }
    public void deleteDepartment(Long currentUserId, Long departmentId){
        userService.requireAdmin(currentUserId);

        // 「查子部门 → 查员工 → 删除」这三件事合并成一条 SQL 原子完成。
        // 旧写法是三个独立的数据库操作，中间有时间窗：
        // 别的线程可以在窗口里往这个部门下面建一个子部门，等这边删完，
        // 那个新建的子部门就成了孤儿节点（parent_id 指向已不存在的部门）。
        int affectedRows=departmentMapper.deleteIfUnused(departmentId);
        if(affectedRows==0){
            // 影响行数为 0 有两种可能，回查一次区分开，好让调用方拿到准确的错误码：
            // 部门不存在 → 404；部门还在但被占用 → 409
            Department stillExists=departmentMapper.selectById(departmentId);
            if(stillExists==null){
                throw new DepartmentNotFoundException("部门不存在");
            }
            throw new DepartmentInUseException("该部门下存在子部门或员工，不能删除");
        }
    }

}
