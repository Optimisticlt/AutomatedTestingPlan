package com.webtestpro.api.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.webtestpro.api.entity.TcProject;
import com.webtestpro.api.service.ProjectService;
import com.webtestpro.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "项目管理")
@SaCheckLogin
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @Operation(summary = "分页查询项目")
    @GetMapping
    public Result<IPage<TcProject>> page(
            @RequestParam(defaultValue = "1")  int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String name) {
        return Result.ok(projectService.page(pageNum, pageSize, name));
    }

    @Operation(summary = "查询项目详情")
    @GetMapping("/{id}")
    public Result<TcProject> get(@PathVariable Long id) {
        return Result.ok(projectService.getById(id));
    }

    @Operation(summary = "创建项目")
    @PostMapping
    public Result<TcProject> create(@RequestBody TcProject project) {
        return Result.ok(projectService.create(project));
    }

    @Operation(summary = "更新项目")
    @PutMapping("/{id}")
    public Result<TcProject> update(@PathVariable Long id, @RequestBody TcProject project) {
        return Result.ok(projectService.update(id, project));
    }

    @Operation(summary = "删除项目")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(id);
        return Result.ok();
    }
}
