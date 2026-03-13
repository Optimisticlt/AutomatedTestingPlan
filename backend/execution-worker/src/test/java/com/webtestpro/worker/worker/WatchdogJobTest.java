package com.webtestpro.worker.worker;

import com.webtestpro.common.enums.ExecutionStatus;
import com.webtestpro.worker.entity.TcExecution;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import com.webtestpro.worker.mapper.TcPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("WatchdogJob – CAS recovery for heartbeat-expired executions (ADR H5)")
@ExtendWith(MockitoExtension.class)
class WatchdogJobTest {

    @Mock private TcExecutionMapper executionMapper;
    @Mock private TcPlanMapper planMapper;
    @Mock private StringRedisTemplate redisTemplateLock;
    @Mock private StringRedisTemplate redisTemplateQueue;
    @Mock private ListOperations<String, String> queueListOps;

    private WatchdogJob watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new WatchdogJob(executionMapper, planMapper, redisTemplateLock, redisTemplateQueue);
        // @Value("${selenoid.max-sessions:5}") is not injected in plain unit tests.
        ReflectionTestUtils.setField(watchdog, "maxSessions", 5);
    }

    private TcExecution runningExecution(Long id, int version, Long planId) {
        TcExecution exec = new TcExecution();
        exec.setId(id);
        exec.setStatus(ExecutionStatus.RUNNING);
        exec.setVersion(version);
        exec.setPlanId(planId);
        return exec;
    }

    @Test
    @DisplayName("execution with expired heartbeat triggers RUNNING→INTERRUPTED CAS")
    void expiredHeartbeatTriggersInterruption() {
        TcExecution exec = runningExecution(10L, 3, null);

        when(executionMapper.selectRunningExecutions()).thenReturn(List.of(exec));
        when(redisTemplateLock.hasKey("heartbeat:10")).thenReturn(false);
        // Lua Capped-INCR for semaphore
        when(redisTemplateLock.execute(any(), anyList(), any())).thenReturn(1L);
        // Step 1: RUNNING → INTERRUPTED succeeds
        when(executionMapper.casUpdateStatus(
                10L, ExecutionStatus.RUNNING.getCode(), ExecutionStatus.INTERRUPTED.getCode(), 3))
                .thenReturn(1);
        // Reload for step 3
        TcExecution reloaded = runningExecution(10L, 4, null);
        reloaded.setStatus(ExecutionStatus.INTERRUPTED);
        when(executionMapper.selectById(10L)).thenReturn(reloaded);
        // Step 3: INTERRUPTED → WAITING succeeds
        when(executionMapper.casUpdateStatus(
                10L, ExecutionStatus.INTERRUPTED.getCode(), ExecutionStatus.WAITING.getCode(), 4))
                .thenReturn(1);
        when(redisTemplateQueue.opsForList()).thenReturn(queueListOps);

        watchdog.watchdogJobHandler();

        verify(executionMapper).casUpdateStatus(
                10L, ExecutionStatus.RUNNING.getCode(), ExecutionStatus.INTERRUPTED.getCode(), 3);
    }

    @Test
    @DisplayName("execution with alive heartbeat is NOT interrupted")
    void aliveHeartbeatSkipped() {
        TcExecution exec = runningExecution(20L, 1, null);

        when(executionMapper.selectRunningExecutions()).thenReturn(List.of(exec));
        when(redisTemplateLock.hasKey("heartbeat:20")).thenReturn(true);

        watchdog.watchdogJobHandler();

        // MySQL CAS must NOT be called for live executions
        verify(executionMapper, never()).casUpdateStatus(anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("MySQL optimistic lock conflict (affected=0) on step 1 skips recovery")
    void mysqlConflictOnStep1SkipsRecovery() {
        TcExecution exec = runningExecution(30L, 5, null);

        when(executionMapper.selectRunningExecutions()).thenReturn(List.of(exec));
        when(redisTemplateLock.hasKey("heartbeat:30")).thenReturn(false);
        // MySQL CAS step 1: affected = 0 → another Watchdog already handled it
        when(executionMapper.casUpdateStatus(
                30L, ExecutionStatus.RUNNING.getCode(), ExecutionStatus.INTERRUPTED.getCode(), 5))
                .thenReturn(0);

        watchdog.watchdogJobHandler();

        // Recovery was skipped after CAS conflict — no re-enqueue
        verify(redisTemplateQueue, never()).opsForList();
    }

    @Test
    @DisplayName("empty RUNNING list: watchdog does nothing")
    void emptyRunningListDoesNothing() {
        when(executionMapper.selectRunningExecutions()).thenReturn(List.of());

        watchdog.watchdogJobHandler();

        verify(redisTemplateLock, never()).hasKey(anyString());
        verify(executionMapper, never()).casUpdateStatus(anyLong(), anyString(), anyString(), anyInt());
    }
}
