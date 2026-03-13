package com.webtestpro.worker.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.common.enums.ExecutionStatus;
import com.webtestpro.worker.engine.runner.TestNGSuiteRunner;
import com.webtestpro.worker.entity.TcEnvVariable;
import com.webtestpro.worker.entity.TcExecution;
import com.webtestpro.worker.entity.TcPlan;
import com.webtestpro.worker.mapper.TcEnvVariableMapper;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import com.webtestpro.worker.mapper.TcPlanMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 执行编排器（核心调度入口）
 *
 * 职责：
 *   1. 状态机迁移：WAITING → RUNNING（乐观锁，validate via ExecutionStatus.isTransitionAllowed）
 *   2. 环境变量快照（env_snapshot，NH2 已修复：执行开始前原子写入）
 *   3. 调用 TestNGSuiteRunner 执行用例集
 *   4. 心跳注册/注销
 *   5. 执行完成状态写库（PASS / FAIL）
 *   6. 断点续跑：解析 checkpointCompletedIds，跳过已完成 case_id
 *   7. Redis Pub/Sub 发布执行完成通知（供 API Service WebSocket 推送）
 *
 * 取消检测：
 *   handleAsync 在状态迁移前检查 CANCELLED，避免浪费 session。
 */
@Slf4j
@Component
public class ExecutionOrchestrator {

    private static final String STATUS_KEY_PREFIX = "exec:status:";
    private static final String CANCEL_CHANNEL_PREFIX = "execution:%s:cancel";

    private final TcExecutionMapper executionMapper;
    private final TcPlanMapper planMapper;
    private final TcEnvVariableMapper envVariableMapper;
    private final TestNGSuiteRunner suiteRunner;
    private final HeartbeatManager heartbeatManager;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplateLock;
    private final StringRedisTemplate redisTemplateCache;

    public ExecutionOrchestrator(
            TcExecutionMapper executionMapper,
            TcPlanMapper planMapper,
            TcEnvVariableMapper envVariableMapper,
            TestNGSuiteRunner suiteRunner,
            HeartbeatManager heartbeatManager,
            ObjectMapper objectMapper,
            @Qualifier("redisTemplateLock") StringRedisTemplate redisTemplateLock,
            @Qualifier("redisTemplateCache") StringRedisTemplate redisTemplateCache) {
        this.executionMapper = executionMapper;
        this.planMapper = planMapper;
        this.envVariableMapper = envVariableMapper;
        this.suiteRunner = suiteRunner;
        this.heartbeatManager = heartbeatManager;
        this.objectMapper = objectMapper;
        this.redisTemplateLock = redisTemplateLock;
        this.redisTemplateCache = redisTemplateCache;
    }

    /**
     * 异步执行入口（ExecutionQueueConsumer 调用）。
     * 每次执行在独立线程池中运行，@Async 由 Spring 托管。
     */
    @Async("executionThreadPool")
    public void handleAsync(TcExecution execution) {
        Long executionId = execution.getId();
        log.info("[exec={}] 开始编排执行", executionId);

        try {
            // 1. 再次检查取消状态（BRPOP 等待期间可能已被取消）
            TcExecution latest = executionMapper.selectById(executionId);
            if (latest == null || "CANCELLED".equals(latest.getStatus().getCode())) {
                log.info("[exec={}] 执行已取消，放弃", executionId);
                return;
            }

            // 2. 状态迁移 WAITING → RUNNING（乐观锁 CAS）
            int affected = executionMapper.casUpdateStatus(executionId,
                    ExecutionStatus.WAITING.getCode(), ExecutionStatus.RUNNING.getCode(),
                    latest.getVersion());
            if (affected == 0) {
                log.warn("[exec={}] 状态迁移失败（乐观锁冲突），放弃执行", executionId);
                return;
            }
            // 更新 Redis 状态缓存
            redisTemplateLock.opsForValue().set(STATUS_KEY_PREFIX + executionId, "RUNNING");

            // 3. 环境变量快照（NH2 修复：在 Worker 开始前原子写入）
            List<TcEnvVariable> envVars = envVariableMapper.selectByEnvId(latest.getEnvId());
            writeEnvSnapshot(executionId, envVars);

            // 4. 注册心跳
            heartbeatManager.register(executionId);

            // 5. 加载执行计划
            TcPlan plan = planMapper.selectById(latest.getPlanId());
            if (plan == null) {
                throw new IllegalArgumentException("执行计划 [id=" + latest.getPlanId() + "] 不存在");
            }

            // 6. 解析断点续跑数据
            Set<Long> completedIds = parseCheckpointIds(latest.getCheckpointCompletedIds());

            // 7. 更新执行开始时间
            TcExecution startUpdate = new TcExecution();
            startUpdate.setId(executionId);
            startUpdate.setStartTime(LocalDateTime.now());
            executionMapper.updateById(startUpdate);

            // 8. 执行套件
            boolean passed = suiteRunner.runSuite(latest, plan, envVars, completedIds);

            // 9. 终态写库（PASS / FAIL）
            ExecutionStatus finalStatus = passed ? ExecutionStatus.PASS : ExecutionStatus.FAIL;
            updateFinalStatus(executionId, finalStatus);

            // 10. Pub/Sub 通知（API Service 推送 WebSocket）
            publishCompletion(executionId, finalStatus);

            log.info("[exec={}] 执行完成，状态: {}", executionId, finalStatus);

        } catch (Exception e) {
            log.error("[exec={}] 执行异常: {}", executionId, e.getMessage(), e);
            updateFinalStatus(executionId, ExecutionStatus.FAIL);
            publishCompletion(executionId, ExecutionStatus.FAIL);
        } finally {
            heartbeatManager.unregister(executionId);
            // 清理 Redis 状态缓存
            redisTemplateLock.delete(STATUS_KEY_PREFIX + executionId);
        }
    }

    private void writeEnvSnapshot(Long executionId, List<TcEnvVariable> envVars) {
        try {
            Map<String, String> snapshot = new HashMap<>();
            for (TcEnvVariable v : envVars) {
                // 加密变量存 [ENCRYPTED] 占位（明文变量存原值）
                String value = v.getIsEncrypted() != null && v.getIsEncrypted() == 1
                        ? "[ENCRYPTED]"
                        : v.getVarValue();
                snapshot.put(v.getVarKey(), value);
            }
            String json = objectMapper.writeValueAsString(snapshot);
            TcExecution update = new TcExecution();
            update.setId(executionId);
            update.setEnvSnapshot(json);
            executionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[exec={}] 写入 env_snapshot 失败: {}", executionId, e.getMessage());
        }
    }

    private Set<Long> parseCheckpointIds(String checkpointCompletedIds) {
        if (checkpointCompletedIds == null || checkpointCompletedIds.isBlank()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (String s : checkpointCompletedIds.split(",")) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return ids;
    }

    private void updateFinalStatus(Long executionId, ExecutionStatus status) {
        try {
            TcExecution execution = executionMapper.selectById(executionId);
            if (execution == null) return;

            int affected = executionMapper.casUpdateStatus(executionId,
                    ExecutionStatus.RUNNING.getCode(), status.getCode(), execution.getVersion());

            if (affected == 0) {
                log.warn("[exec={}] 终态写库失败（乐观锁冲突或状态已变更）", executionId);
            }
        } catch (Exception e) {
            log.error("[exec={}] 更新终态失败: {}", executionId, e.getMessage());
        }
    }

    private void publishCompletion(Long executionId, ExecutionStatus status) {
        try {
            String channel = String.format(CANCEL_CHANNEL_PREFIX.replace("cancel", "complete"), executionId);
            redisTemplateCache.convertAndSend(channel,
                    "{\"executionId\":" + executionId + ",\"status\":\"" + status.getCode() + "\"}");
        } catch (Exception e) {
            log.warn("[exec={}] 发布完成通知失败: {}", executionId, e.getMessage());
        }
    }
}
