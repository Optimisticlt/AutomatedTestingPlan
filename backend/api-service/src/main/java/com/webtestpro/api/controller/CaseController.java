package com.webtestpro.api.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.webtestpro.api.entity.TcCase;
import com.webtestpro.api.entity.TcStep;
import com.webtestpro.api.service.CaseService;
import com.webtestpro.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用例管理")
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/cases")
@RequiredArgsConstructor
public class CaseController {

    private final CaseService caseService;

    @Operation(summary = "分页查询用例")
    @GetMapping
    public Result<IPage<TcCase>> page(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String priority) {
        return Result.ok(caseService.page(pageNum, pageSize, projectId, name, priority));
    }

    @Operation(summary = "查询用例详情")
    @GetMapping("/{id}")
    public Result<TcCase> get(@PathVariable Long id) {
        return Result.ok(caseService.getById(id));
    }

    @Operation(summary = "查询用例步骤")
    @GetMapping("/{id}/steps")
    public Result<List<TcStep>> steps(@PathVariable Long id) {
        return Result.ok(caseService.getSteps(id));
    }

    @Operation(summary = "创建用例")
    @PostMapping
    public Result<TcCase> create(@RequestBody TcCase tc) {
        return Result.ok(caseService.create(tc));
    }

    @Operation(summary = "更新用例")
    @PutMapping("/{id}")
    public Result<TcCase> update(@PathVariable Long id, @RequestBody TcCase tc) {
        return Result.ok(caseService.update(id, tc));
    }

    @Operation(summary = "删除用例")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        caseService.delete(id);
        return Result.ok();
    }
}
