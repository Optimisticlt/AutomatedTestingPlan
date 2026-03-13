package com.webtestpro.api.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.webtestpro.api.entity.TcCaseResult;
import com.webtestpro.api.entity.TcExecution;
import com.webtestpro.api.service.ExecutionService;
import com.webtestpro.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "执行管理")
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final ExecutionService executionService;

    @Operation(summary = "分页查询执行记录")
    @GetMapping
    public Result<IPage<TcExecution>> page(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long planId) {
        return Result.ok(executionService.page(pageNum, pageSize, planId));
    }

    @Operation(summary = "查询执行详情")
    @GetMapping("/{id}")
    public Result<TcExecution> get(@PathVariable Long id) {
        return Result.ok(executionService.getById(id));
    }

    @Operation(summary = "查询用例执行结果")
    @GetMapping("/{id}/case-results")
    public Result<IPage<TcCaseResult>> caseResults(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(executionService.getCaseResults(id, pageNum, pageSize));
    }

    @Operation(summary = "手动触发执行计划")
    @PostMapping("/trigger")
    public Result<TcExecution> trigger(
            @RequestParam Long planId,
            @RequestParam(required = false) Long envId) {
        return Result.ok(executionService.trigger(planId, envId));
    }

    @Operation(summary = "取消执行")
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        executionService.cancel(id);
        return Result.ok();
    }
}
