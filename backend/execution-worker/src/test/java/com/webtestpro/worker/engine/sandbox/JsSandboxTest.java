package com.webtestpro.worker.engine.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsSandbox – GraalVM Polyglot sandbox enforcement")
class JsSandboxTest {

    private JsSandbox sandbox;

    @BeforeEach
    void setUp() throws Exception {
        sandbox = new JsSandbox();
        // @PostConstruct is not invoked by Spring in plain unit tests.
        // Must call init() manually to initialize the ScheduledExecutorService.
        sandbox.init();
    }

    @AfterEach
    void tearDown() {
        sandbox.destroy();
    }

    @Test
    @DisplayName("safe arithmetic script executes and returns result")
    void safeScriptExecutes() {
        assertThatCode(() -> sandbox.execute("var x = 1 + 2; x;"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("script can set a context variable")
    void scriptCanUseLocalVar() {
        assertThatCode(() -> sandbox.execute("var greeting = 'hello'; greeting;"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Java class access is blocked (allowAllAccess=false)")
    void javaClassAccessBlocked() {
        // Attempting to access Java.type should fail in restricted context
        assertThatThrownBy(() ->
                sandbox.execute("Java.type('java.lang.Runtime').getRuntime().exec('ls');")
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("file IO via Java is blocked")
    void fileIoBlocked() {
        assertThatThrownBy(() ->
                sandbox.execute("var f = new java.io.File('/etc/passwd'); f.exists();")
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("infinite loop is killed within 35 seconds")
    @Timeout(value = 35, unit = TimeUnit.SECONDS)
    void infiniteLoopIsTerminated() {
        // Pure CPU spin — should throw ScriptTimeoutException (or similar) not hang forever.
        assertThatThrownBy(() ->
                sandbox.execute("while(true) {}")
        ).isInstanceOf(Exception.class);
    }
}
