package com.qqlin.oa.controller;

import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.DepartmentCreateDTO;
import com.qqlin.oa.service.DepartmentService;
import com.qqlin.oa.vo.DepartmentVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

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
}
