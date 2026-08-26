package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.DepartmentCreateDTO;
import com.qqlin.oa.dto.DepartmentParentUpdateDTO;
import com.qqlin.oa.service.DepartmentService;
import com.qqlin.oa.vo.DepartmentTreeVO;
import com.qqlin.oa.vo.DepartmentVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/departments")
public class DepartmentController {
    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    @PostMapping
    public Result<DepartmentVO> createDepartment(@RequestAttribute("currentUserId") Long currentUserid,
                                                 @Valid @RequestBody DepartmentCreateDTO dto){
        return Result.success(departmentService.createDepartment(currentUserid,dto));
    }
    @GetMapping("/tree")
    public Result<List<DepartmentTreeVO>> getDepartmentTree(@RequestAttribute("currentUserId" )Long currentUserid){
        return Result.success(departmentService.getDepartmentTree(currentUserid));

    }
    @PatchMapping("/{id}/parent")
    public Result<Void> updateParent(@RequestAttribute("currentUserId") Long currentUserid,
                                     @PathVariable("id") Long id,
                                     @RequestBody @Valid DepartmentParentUpdateDTO dto){
        departmentService.updateParent(currentUserid,id,dto);
        return Result.success();
    }
    @DeleteMapping("/{id}")
    public Result<Void> deleteDepartment(@RequestAttribute("currentUserId") Long currentUserId,
                                         @PathVariable("id") Long id){
        departmentService.deleteDepartment(currentUserId,id);
        return Result.success();
    }

}
