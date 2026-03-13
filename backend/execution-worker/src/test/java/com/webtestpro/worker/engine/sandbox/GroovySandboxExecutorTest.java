package com.webtestpro.worker.engine.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("GroovySandboxExecutor – Groovy whitelist enforcement")
class GroovySandboxExecutorTest {

    private GroovySandboxExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new GroovySandboxExecutor();
        // @PostConstruct is not invoked by Spring in plain unit tests.
        // Must call init() manually to initialize the ExecutorService.
        executor.init();
    }

    @Test
    @DisplayName("safe math script executes")
    void safeMathExecutes() {
        assertThatCode(() -> executor.execute("def x = 2 + 2; assert x == 4"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("allowed import (groovy.json.JsonSlurper) works")
    void allowedImportWorks() {
        assertThatCode(() -> executor.execute(
                "import groovy.json.JsonSlurper; def j = new JsonSlurper(); j.parseText('{\"a\":1}')"
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Runtime.exec is blocked by whitelist")
    void runtimeExecBlocked() {
        assertThatThrownBy(() ->
                executor.execute("Runtime.getRuntime().exec('ls')")
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("File IO is blocked")
    void fileIoBlocked() {
        assertThatThrownBy(() ->
                executor.execute("new File('/etc/passwd').text")
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Thread creation is blocked")
    void threadCreationBlocked() {
        assertThatThrownBy(() ->
                executor.execute("new Thread({ -> println 'x' }).start()")
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("CPU-spinning script is terminated within 35 seconds")
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void cpuSpinTerminated() {
        assertThatThrownBy(() ->
                executor.execute("while(true) {}")
        ).isInstanceOf(Exception.class);
    }
}
