package com.webtestpro.api.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.webtestpro.api.entity.TcPlan;
import com.webtestpro.api.entity.TcPlanCase;
import com.webtestpro.api.service.PlanService;
import com.webtestpro.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "计划管理")
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @Operation(summary = "分页查询计划")
    @GetMapping
    public Result<IPage<TcPlan>> page(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long projectId) {
        return Result.ok(planService.page(pageNum, pageSize, projectId));
    }

    @Operation(summary = "查询计划详情")
    @GetMapping("/{id}")
    public Result<TcPlan> get(@PathVariable Long id) {
        return Result.ok(planService.getById(id));
    }

    @Operation(summary = "查询计划下的用例列表")
    @GetMapping("/{id}/cases")
    public Result<List<TcPlanCase>> cases(@PathVariable Long id) {
        return Result.ok(planService.getPlanCases(id));
    }

    @Operation(summary = "创建计划")
    @PostMapping
    public Result<TcPlan> create(@RequestBody TcPlan plan) {
        return Result.ok(planService.create(plan));
    }

    @Operation(summary = "更新计划")
    @PutMapping("/{id}")
    public Result<TcPlan> update(@PathVariable Long id, @RequestBody TcPlan plan) {
        return Result.ok(planService.update(id, plan));
    }

    @Operation(summary = "删除计划")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        planService.delete(id);
        return Result.ok();
    }

    @Operation(summary = "添加用例到计划")
    @PostMapping("/{id}/cases/{caseId}")
    public Result<Void> addCase(@PathVariable Long id, @PathVariable Long caseId,
                                @RequestParam(required = false) Integer sortOrder) {
        planService.addCase(id, caseId, sortOrder);
        return Result.ok();
    }

    @Operation(summary = "从计划移除用例")
    @DeleteMapping("/{id}/cases/{caseId}")
    public Result<Void> removeCase(@PathVariable Long id, @PathVariable Long caseId) {
        planService.removeCase(id, caseId);
        return Result.ok();
    }
}
