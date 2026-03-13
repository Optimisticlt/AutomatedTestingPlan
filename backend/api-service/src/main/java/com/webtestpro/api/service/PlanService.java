package com.webtestpro.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webtestpro.api.entity.TcPlan;
import com.webtestpro.api.entity.TcPlanCase;
import com.webtestpro.api.mapper.TcPlanCaseMapper;
import com.webtestpro.api.mapper.TcPlanMapper;
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
public class PlanService {

    private final TcPlanMapper planMapper;
    private final TcPlanCaseMapper planCaseMapper;

    public IPage<TcPlan> page(int pageNum, int pageSize, Long projectId) {
        Page<TcPlan> pageable = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TcPlan> wrapper = new LambdaQueryWrapper<TcPlan>()
                .eq(projectId != null, TcPlan::getProjectId, projectId)
                .orderByDesc(TcPlan::getCreatedTime);
        return planMapper.selectPage(pageable, wrapper);
    }

    public TcPlan getById(Long id) {
        TcPlan plan = planMapper.selectById(id);
        if (plan == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return plan;
    }

    public List<TcPlanCase> getPlanCases(Long planId) {
        return planCaseMapper.selectList(new LambdaQueryWrapper<TcPlanCase>()
                .eq(TcPlanCase::getPlanId, planId)
                .orderByAsc(TcPlanCase::getSortOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public TcPlan create(TcPlan plan) {
        planMapper.insert(plan);
        return plan;
    }

    @Transactional(rollbackFor = Exception.class)
    public TcPlan update(Long id, TcPlan update) {
        TcPlan existing = getById(id);
        update.setId(existing.getId());
        update.setVersion(existing.getVersion());
        planMapper.updateById(update);
        return planMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getById(id);
        planCaseMapper.delete(new LambdaQueryWrapper<TcPlanCase>().eq(TcPlanCase::getPlanId, id));
        planMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void addCase(Long planId, Long caseId, Integer sortOrder) {
        getById(planId);
        TcPlanCase pc = new TcPlanCase();
        pc.setPlanId(planId);
        pc.setCaseId(caseId);
        pc.setSortOrder(sortOrder != null ? sortOrder : 0);
        planCaseMapper.insert(pc);
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeCase(Long planId, Long caseId) {
        planCaseMapper.delete(new LambdaQueryWrapper<TcPlanCase>()
                .eq(TcPlanCase::getPlanId, planId)
                .eq(TcPlanCase::getCaseId, caseId));
    }
}
