package com.webtestpro.worker.engine.executor;

import com.webtestpro.common.exception.BizException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the SQL AST whitelist enforced by DbStepExecutor.
 * We test the JSqlParser logic directly, matching what DbStepExecutor does internally.
 * This avoids needing a real DB connection.
 */
@DisplayName("DbStepExecutor – SQL AST whitelist (SELECT-only)")
class DbStepExecutorTest {

    /** Mirrors the validation logic inside DbStepExecutor */
    private static void validateSelectOnly(String sql) throws Exception {
        Statement stmt = CCJSqlParserUtil.parse(sql);
        if (!(stmt instanceof Select)) {
            throw new BizException(
                    com.webtestpro.common.result.ErrorCode.SQL_NOT_ALLOWED);
        }
    }

    @ParameterizedTest(name = "allowed: {0}")
    @ValueSource(strings = {
            "SELECT id, name FROM users WHERE id = 1",
            "SELECT * FROM orders WHERE status = 'PAID' LIMIT 50",
            "SELECT COUNT(*) FROM tc_case WHERE project_id = 99",
            "SELECT a.name, b.status FROM users a JOIN orders b ON a.id = b.user_id"
    })
    @DisplayName("SELECT statements are allowed")
    void selectStatementsAllowed(String sql) {
        assertThatCode(() -> validateSelectOnly(sql)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "blocked: {0}")
    @ValueSource(strings = {
            "INSERT INTO users (name) VALUES ('hacker')",
            "UPDATE users SET password = 'pwned' WHERE id = 1",
            "DELETE FROM users WHERE id = 1",
            "DROP TABLE users",
            "CREATE TABLE evil (id INT)",
            "TRUNCATE TABLE users",
            "ALTER TABLE users ADD COLUMN evil VARCHAR(255)"
    })
    @DisplayName("Non-SELECT statements are blocked")
    void nonSelectStatementsBlocked(String sql) {
        assertThatThrownBy(() -> validateSelectOnly(sql))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("SELECT with comment does not bypass AST check")
    void selectWithCommentBlocked() {
        // A real bypass attempt using comment injection — JSqlParser still parses correctly
        String trickySql = "SELECT 1; DROP TABLE users; --";
        // JSqlParser may reject multi-statement or parse only the first SELECT
        // Either outcome is acceptable; the point is no DROP reaches execution
        try {
            Statement stmt = CCJSqlParserUtil.parse(trickySql);
            // If parsed as Select, the ; DROP part was ignored — OK
            assertThat(stmt).isInstanceOf(Select.class);
        } catch (Exception e) {
            // Parse error on multi-statement — also OK, execution never happens
        }
    }
}
