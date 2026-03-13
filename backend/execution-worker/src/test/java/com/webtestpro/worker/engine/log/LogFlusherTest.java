package com.webtestpro.worker.engine.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.entity.TcExecutionLog;
import com.webtestpro.worker.mapper.TcExecutionLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("LogFlusher – ADR C1: LRANGE-0 semantics, LTRIM on success only")
@ExtendWith(MockitoExtension.class)
class LogFlusherTest {

    @Mock private StringRedisTemplate redisTemplateQueue;
    @Mock private ListOperations<String, String> listOps;
    @Mock private TcExecutionLogMapper logMapper;
    @Mock private ObjectMapper objectMapper;

    private static final String LOG_KEY = "execution_log:42";

    private LogFlusher flusher;

    @BeforeEach
    void setUp() throws Exception {
        when(redisTemplateQueue.opsForList()).thenReturn(listOps);
        // register() calls redisTemplateQueue.expire() — stub to avoid NPE
        when(redisTemplateQueue.expire(eq(LOG_KEY), any(Duration.class))).thenReturn(true);

        flusher = new LogFlusher(redisTemplateQueue, logMapper, objectMapper);
        flusher.register(42L);
    }

    @Test
    @DisplayName("flush reads LRANGE 0 499 (always from index 0, ADR C1)")
    void flushReadsFromIndexZero() throws Exception {
        String logJson = "{\"logIndex\":0,\"content\":\"step 1\"}";
        when(listOps.range(LOG_KEY, 0, 499)).thenReturn(List.of(logJson));
        when(objectMapper.readValue(logJson, TcExecutionLog.class)).thenReturn(new TcExecutionLog());

        flusher.flush();

        verify(listOps).range(LOG_KEY, 0, 499);
    }

    @Test
    @DisplayName("LTRIM is called after successful DB write (trim count entries)")
    void ltrimCalledOnSuccess() throws Exception {
        List<String> entries = List.of(
                "{\"logIndex\":0,\"content\":\"start\"}",
                "{\"logIndex\":1,\"content\":\"click\"}"
        );
        when(listOps.range(LOG_KEY, 0, 499)).thenReturn(entries);
        TcExecutionLog log1 = new TcExecutionLog();
        TcExecutionLog log2 = new TcExecutionLog();
        when(objectMapper.readValue(entries.get(0), TcExecutionLog.class)).thenReturn(log1);
        when(objectMapper.readValue(entries.get(1), TcExecutionLog.class)).thenReturn(log2);

        flusher.flush();

        // LTRIM trims 2 entries from head: trim(key, 2, -1)
        verify(listOps).trim(LOG_KEY, 2, -1);
    }

    @Test
    @DisplayName("LTRIM is NOT called when DB write fails (ADR C1: idempotent retry)")
    void ltrimNotCalledOnFailure() throws Exception {
        String logJson = "{\"logIndex\":0,\"content\":\"step\"}";
        when(listOps.range(LOG_KEY, 0, 499)).thenReturn(List.of(logJson));
        when(objectMapper.readValue(logJson, TcExecutionLog.class)).thenReturn(new TcExecutionLog());
        doThrow(new RuntimeException("DB unavailable")).when(logMapper).insert(any(TcExecutionLog.class));

        flusher.flush();

        // LTRIM must NOT be called when DB write failed
        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("empty list is skipped (no DB call, no LTRIM)")
    void emptyListSkipped() {
        when(listOps.range(LOG_KEY, 0, 499)).thenReturn(List.of());

        flusher.flush();

        verify(logMapper, never()).insert(any(TcExecutionLog.class));
        verify(listOps, never()).trim(anyString(), anyLong(), anyLong());
    }
}
