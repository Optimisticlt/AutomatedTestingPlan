package com.webtestpro.worker.worker;

import com.webtestpro.worker.engine.session.SelenoidSessionManager;
import com.webtestpro.worker.mapper.TcExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ExecutionQueueConsumer – priority queue with P2 aging")
@ExtendWith(MockitoExtension.class)
class ExecutionQueueConsumerTest {

    @Mock private StringRedisTemplate redisTemplateQueue;
    @Mock private ListOperations<String, String> listOps;
    @Mock private TcExecutionMapper executionMapper;
    @Mock private SelenoidSessionManager sessionManager;
    @Mock private ExecutionOrchestrator orchestrator;

    private ExecutionQueueConsumer consumer;

    @BeforeEach
    void setUp() {
        when(redisTemplateQueue.opsForList()).thenReturn(listOps);
        consumer = new ExecutionQueueConsumer(
                redisTemplateQueue, executionMapper, sessionManager, orchestrator);
    }

    @Test
    @DisplayName("P0 is consumed before P1 when P0 has tasks")
    void p0BeforeP1() {
        // P0 has a task — rightPop uses Duration overload
        when(listOps.rightPop(eq("{exec_queue}:P0"), any(Duration.class))).thenReturn("101");

        String popped = consumer.pollNextTask();

        assertThat(popped).isEqualTo("101");
        verify(listOps).rightPop(eq("{exec_queue}:P0"), any(Duration.class));
        verify(listOps, never()).rightPop(eq("{exec_queue}:P1"), any(Duration.class));
    }

    @Test
    @DisplayName("falls through to P1 when P0 is empty")
    void fallsThroughToP1WhenP0Empty() {
        when(listOps.rightPop(eq("{exec_queue}:P0"), any(Duration.class))).thenReturn(null);
        when(listOps.rightPop(eq("{exec_queue}:P1"), any(Duration.class))).thenReturn("201");

        String popped = consumer.pollNextTask();

        assertThat(popped).isEqualTo("201");
    }

    @Test
    @DisplayName("P2 aging: after P2_AGING_THRESHOLD P0/P1 consumes, P2 is force-consumed")
    void p2AgingAfterThreshold() {
        // P0 always empty, P1 always has tasks, P2 has tasks
        when(listOps.rightPop(eq("{exec_queue}:P0"), any(Duration.class))).thenReturn(null);
        when(listOps.rightPop(eq("{exec_queue}:P1"), any(Duration.class)))
                .thenReturn("201", "202", "203", "204", "205");
        // Aging peek: rightPopAndLeftPush(P2, P2, Duration.ZERO)
        when(listOps.rightPopAndLeftPush(eq("{exec_queue}:P2"), eq("{exec_queue}:P2"), any(Duration.class)))
                .thenReturn("301");
        // Aging direct-consume: rightPop(QUEUE_P2) — no Duration (non-blocking overload)
        when(listOps.rightPop(eq("{exec_queue}:P2"))).thenReturn("301");

        // Consume P2_AGING_THRESHOLD (5) P1 tasks to build up skip count
        for (int i = 0; i < 5; i++) {
            consumer.pollNextTask();
        }
        // 6th poll should force-consume P2
        String sixth = consumer.pollNextTask();

        assertThat(sixth).isEqualTo("301");
    }
}
