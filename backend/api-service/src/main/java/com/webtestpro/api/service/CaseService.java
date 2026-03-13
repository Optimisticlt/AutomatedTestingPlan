package com.webtestpro.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webtestpro.api.entity.TcCase;
import com.webtestpro.api.entity.TcStep;
import com.webtestpro.api.mapper.TcCaseMapper;
import com.webtestpro.api.mapper.TcStepMapper;
import com.webtestpro.common.exception.BizException;
import com.webtestpro.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CaseService {

    private final TcCaseMapper caseMapper;
    private final TcStepMapper stepMapper;

    public IPage<TcCase> page(int pageNum, int pageSize, Long projectId, String name, String priority) {
        Page<TcCase> pageable = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TcCase> wrapper = new LambdaQueryWrapper<TcCase>()
                .eq(projectId != null, TcCase::getProjectId, projectId)
                .like(name != null && !name.isBlank(), TcCase::getName, name)
                .eq(priority != null && !priority.isBlank(), TcCase::getPriority, priority)
                .orderByDesc(TcCase::getCreatedTime);
        return caseMapper.selectPage(pageable, wrapper);
    }

    public TcCase getById(Long id) {
        TcCase tc = caseMapper.selectById(id);
        if (tc == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return tc;
    }

    public List<TcStep> getSteps(Long caseId) {
        return stepMapper.selectList(new LambdaQueryWrapper<TcStep>()
                .eq(TcStep::getCaseId, caseId)
                .isNull(TcStep::getParentStepId)
                .orderByAsc(TcStep::getStepOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public TcCase create(TcCase tc) {
        caseMapper.insert(tc);
        return tc;
    }

    @Transactional(rollbackFor = Exception.class)
    public TcCase update(Long id, TcCase update) {
        TcCase existing = getById(id);
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        caseMapper.updateById(update);
        return caseMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        // 删除关联步骤
        stepMapper.delete(new LambdaQueryWrapper<TcStep>().eq(TcStep::getCaseId, id));
        caseMapper.deleteById(id);
    }
}
