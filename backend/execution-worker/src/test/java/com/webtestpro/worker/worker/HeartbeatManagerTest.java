package com.webtestpro.worker.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("HeartbeatManager – heartbeat key TTL writes")
@ExtendWith(MockitoExtension.class)
class HeartbeatManagerTest {

    @Mock private StringRedisTemplate redisTemplateLock;
    @Mock private ValueOperations<String, String> valueOps;

    private HeartbeatManager manager;

    @BeforeEach
    void setUp() {
        when(redisTemplateLock.opsForValue()).thenReturn(valueOps);
        manager = new HeartbeatManager(redisTemplateLock);
    }

    @Test
    @DisplayName("register adds execution to active set and writes initial heartbeat")
    void registerAddsToActive() {
        manager.register(123L);
        // register() calls writeHeartbeat() immediately
        verify(valueOps).set(eq("heartbeat:123"), anyString(), eq(Duration.ofSeconds(15)));
    }

    @Test
    @DisplayName("heartbeat key format is 'heartbeat:{execId}'")
    void heartbeatKeyFormat() {
        manager.register(456L);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), anyString(), any(Duration.class));
        assertThat(keyCaptor.getValue()).isEqualTo("heartbeat:456");
    }

    @Test
    @DisplayName("heartbeat TTL is exactly Duration.ofSeconds(15)")
    void heartbeatTtlDuration15Seconds() {
        manager.register(789L);

        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOps).set(anyString(), anyString(), durationCaptor.capture());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("refreshHeartbeats writes key for each registered execution")
    void refreshHeartbeatsWritesAllActive() {
        manager.register(1L);
        manager.register(2L);
        clearInvocations(valueOps); // clear calls from register()'s initial write

        manager.refreshHeartbeats(); // invoke the @Scheduled method manually

        verify(valueOps, times(2)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("unregister removes execution from active set (no more heartbeats)")
    void unregisterRemovesFromActive() {
        manager.register(111L);
        manager.unregister(111L);
        clearInvocations(valueOps);

        manager.refreshHeartbeats();

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("multiple executions all get heartbeat written in refreshHeartbeats")
    void multipleExecutionsAllWritten() {
        manager.register(1L);
        manager.register(2L);
        manager.register(3L);
        clearInvocations(valueOps);

        manager.refreshHeartbeats();

        verify(valueOps, times(3)).set(anyString(), anyString(), any(Duration.class));
    }
}
