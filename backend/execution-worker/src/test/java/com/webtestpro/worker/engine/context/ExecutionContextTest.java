package com.webtestpro.worker.engine.context;

import com.webtestpro.worker.entity.TcEnvVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ExecutionContext – variable sandbox and faker")
class ExecutionContextTest {

    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        TcEnvVariable baseUrl = new TcEnvVariable();
        baseUrl.setVarKey("BASE_URL");
        baseUrl.setVarValue("https://example.com");

        TcEnvVariable username = new TcEnvVariable();
        username.setVarKey("USERNAME");
        username.setVarValue("admin");

        ctx = new ExecutionContext(1L, List.of(baseUrl, username));
    }

    @Test
    @DisplayName("resolve replaces env variable placeholder")
    void resolveEnvVar() {
        assertThat(ctx.resolve("${BASE_URL}/login")).isEqualTo("https://example.com/login");
    }

    @Test
    @DisplayName("resolve replaces runtime variable set during execution")
    void resolveRuntimeVar() {
        ctx.setVariable("TOKEN", "abc123");
        assertThat(ctx.resolve("Bearer ${TOKEN}")).isEqualTo("Bearer abc123");
    }

    @Test
    @DisplayName("runtime variable overrides env variable with same name")
    void runtimeOverridesEnv() {
        ctx.setVariable("USERNAME", "runtime-user");
        assertThat(ctx.resolve("${USERNAME}")).isEqualTo("runtime-user");
    }

    @Test
    @DisplayName("unknown placeholder is left unchanged")
    void unknownPlaceholderUnchanged() {
        assertThat(ctx.resolve("${NONEXISTENT}")).isEqualTo("${NONEXISTENT}");
    }

    @Test
    @DisplayName("faker.phone returns non-empty string")
    void fakerPhoneNotEmpty() {
        String phone = ctx.resolve("${faker.phone}");
        assertThat(phone).isNotBlank();
        assertThat(phone).doesNotContain("${faker");
    }

    @Test
    @DisplayName("faker.email returns string containing @")
    void fakerEmailContainsAt() {
        String email = ctx.resolve("${faker.email}");
        assertThat(email).contains("@");
    }

    @Test
    @DisplayName("faker.uuid returns 36-char UUID format")
    void fakerUuidFormat() {
        String uuid = ctx.resolve("${faker.uuid}");
        assertThat(uuid).hasSize(36);
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("nextLogIndex returns monotonically increasing int values")
    void logIndexMonotonic() {
        int i1 = ctx.nextLogIndex();
        int i2 = ctx.nextLogIndex();
        int i3 = ctx.nextLogIndex();
        assertThat(i1).isLessThan(i2);
        assertThat(i2).isLessThan(i3);
    }

    @Test
    @DisplayName("resolve string with no placeholders returns unchanged")
    void noPlaceholderUnchanged() {
        assertThat(ctx.resolve("plain text")).isEqualTo("plain text");
    }

    @Test
    @DisplayName("resolve null returns null")
    void resolveNullReturnsNull() {
        assertThat(ctx.resolve(null)).isNull();
    }
}
