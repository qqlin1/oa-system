package com.qqlin.oa.controller;

import com.qqlin.oa.common.PageResult;
import com.qqlin.oa.common.Result;
import com.qqlin.oa.dto.LeaveApprovalDTO;
import com.qqlin.oa.dto.LeaveCreateDTO;
import com.qqlin.oa.service.LeaveService;
import com.qqlin.oa.vo.LeaveVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("leaves")
public class LeaveController {
    private final LeaveService leaveService;
    public LeaveController(LeaveService leaveService) {
        this.leaveService = leaveService;
    }
    @PostMapping
    public Result<Long> createLeave(@RequestAttribute("currentUserId")Long currentUserId, @Valid @RequestBody
                                LeaveCreateDTO dto){
        return Result.success(leaveService.createLeave(currentUserId,dto));
    }
    @GetMapping("/me")
    public Result<PageResult<LeaveVO>> getLeaveList(@RequestAttribute("currentUserId") Long currentUserId,
                                                    @RequestParam(defaultValue = "1")@Min(value = 1,message = "页数必须大于1") long current,
                                                    @RequestParam(defaultValue = "10") @Min(
                                                            value = 1,
                                                            message = "每页条数必须大于等于1"
                                                    )@Max(value = 100,message = "每页条数不能超过100") long size){
        return Result.success(leaveService.getLeaveList(currentUserId,current,size));
    }
    @PatchMapping("/{id}/approval")
    public Result<Void> approvalLeave(@RequestAttribute("currentUserId") Long currentUserId,
                                      @PathVariable("id") Long id,
                                      @Valid @RequestBody LeaveApprovalDTO dto){
        leaveService.approveLeave(currentUserId,id,dto);
        return  Result.success();
    }
    @PatchMapping("/{id}/cancel")
    public Result<Void> cancelLeave(@RequestAttribute("currentUserId") Long currentUserId,
                                    @PathVariable("id")long id){
        leaveService.cancelLeave(currentUserId,id);
        return Result.success();
    }
    @GetMapping("/pending")
    public Result<PageResult<LeaveVO>> getPendingLeaveList(@RequestAttribute("currentUserId") Long currentUserId,
                                                           @RequestParam(defaultValue = "1")
                                                           @Min(value = 1,message = "当前页数不能小于1") long current,
                                                           @RequestParam(defaultValue = "10")
                                                               @Min(
                                                                       value = 1,
                                                                       message = "每页条数必须大于等于1"
                                                               )
                                                           @Max(value = 100,message = "每页条数不能超过100")long size){
        return Result.success(leaveService.getPendingLeaveList(currentUserId, current, size));
    }
}
