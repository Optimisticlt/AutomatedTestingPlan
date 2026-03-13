package com.webtestpro.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webtestpro.api.entity.TcProject;
import com.webtestpro.api.mapper.TcProjectMapper;
import com.webtestpro.common.exception.BizException;
import com.webtestpro.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final TcProjectMapper projectMapper;

    public IPage<TcProject> page(int pageNum, int pageSize, String name) {
        Page<TcProject> pageable = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TcProject> wrapper = new LambdaQueryWrapper<TcProject>()
                .like(name != null && !name.isBlank(), TcProject::getName, name)
                .orderByDesc(TcProject::getCreatedTime);
        return projectMapper.selectPage(pageable, wrapper);
    }

    public TcProject getById(Long id) {
        TcProject project = projectMapper.selectById(id);
        if (project == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return project;
    }

    @Transactional(rollbackFor = Exception.class)
    public TcProject create(TcProject project) {
        projectMapper.insert(project);
        return project;
    }

    @Transactional(rollbackFor = Exception.class)
    public TcProject update(Long id, TcProject update) {
        TcProject existing = getById(id);
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        projectMapper.updateById(update);
        return projectMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        projectMapper.deleteById(id);
    }
}
