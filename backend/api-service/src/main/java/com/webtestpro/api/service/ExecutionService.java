package com.webtestpro.api.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webtestpro.api.entity.TcCaseResult;
import com.webtestpro.api.entity.TcExecution;
import com.webtestpro.api.entity.TcPlan;
import com.webtestpro.api.mapper.TcCaseResultMapper;
import com.webtestpro.api.mapper.TcExecutionMapper;
import com.webtestpro.api.mapper.TcPlanMapper;
import com.webtestpro.common.enums.ExecutionStatus;
import com.webtestpro.common.exception.BizException;
import com.webtestpro.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final TcExecutionMapper executionMapper;
    private final TcCaseResultMapper caseResultMapper;
    private final TcPlanMapper planMapper;

    @Qualifier("redisTemplateQueue")
    private final StringRedisTemplate redisTemplateQueue;

    public IPage<TcExecution> page(int pageNum, int pageSize, Long planId) {
        Page<TcExecution> pageable = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<TcExecution> wrapper = new LambdaQueryWrapper<TcExecution>()
                .eq(planId != null, TcExecution::getPlanId, planId)
                .orderByDesc(TcExecution::getCreatedTime);
        return executionMapper.selectPage(pageable, wrapper);
    }

    public TcExecution getById(Long id) {
        TcExecution exec = executionMapper.selectById(id);
        if (exec == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);
        return exec;
    }

    public IPage<TcCaseResult> getCaseResults(Long executionId, int pageNum, int pageSize) {
        getById(executionId);
        Page<TcCaseResult> pageable = new Page<>(pageNum, pageSize);
        return caseResultMapper.selectPage(pageable, new LambdaQueryWrapper<TcCaseResult>()
                .eq(TcCaseResult::getExecutionId, executionId)
                .orderByAsc(TcCaseResult::getCreatedTime));
    }

    /**
     * 手动触发执行计划。
     * 创建执行记录后推入 Redis 队列，由 Worker 异步拉取并执行。
     */
    @Transactional(rollbackFor = Exception.class)
    public TcExecution trigger(Long planId, Long envId) {
        TcPlan plan = planMapper.selectById(planId);
        if (plan == null) throw new BizException(ErrorCode.RESOURCE_NOT_FOUND);

        Long triggeredBy = StpUtil.getLoginIdAsLong();
        Long resolvedEnvId = envId != null ? envId : plan.getEnvId();

        TcExecution exec = new TcExecution();
        exec.setPlanId(planId);
        exec.setProjectId(plan.getProjectId());
        exec.setEnvId(resolvedEnvId);
        exec.setStatus(ExecutionStatus.WAITING.getCode());
        exec.setTriggerType("MANUAL");
        exec.setTriggeredBy(triggeredBy);
        exec.setPriority(plan.getPriority() != null ? plan.getPriority() : "P1");
        executionMapper.insert(exec);

        // 推入优先级队列
        String queueKey = "{exec_queue}:" + exec.getPriority();
        redisTemplateQueue.opsForList().leftPush(queueKey, String.valueOf(exec.getId()));
        log.info("Execution {} triggered, pushed to queue {}", exec.getId(), queueKey);

        return exec;
    }

    /**
     * 取消执行：将状态改为 CANCELLED（Worker 拉取时会检测并丢弃）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long executionId) {
        TcExecution exec = getById(executionId);
        if (!ExecutionStatus.isTransitionAllowed(
                ExecutionStatus.fromCode(exec.getStatus()),
                ExecutionStatus.CANCELLED)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        TcExecution update = new TcExecution();
        update.setId(executionId);
        update.setStatus(ExecutionStatus.CANCELLED.getCode());
        update.setEndTime(LocalDateTime.now());
        update.setVersion(exec.getVersion());
        executionMapper.updateById(update);
    }
}
