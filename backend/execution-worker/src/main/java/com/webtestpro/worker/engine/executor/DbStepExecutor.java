package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.entity.TcDatasource;
import com.webtestpro.worker.entity.TcStep;
import com.webtestpro.worker.mapper.TcDatasourceMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.assertj.core.api.Assertions;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB 步骤执行器（数据库断言，只读）
 *
 * 安全规范（M1 + 设计文档 §6.4）：
 *   1. JSqlParser AST 校验，非 SELECT 语句直接拒绝（不依赖字符串前缀）
 *   2. PreparedStatement 参数化（环境变量只在 ? 占位处替换，禁止出现在 SQL 结构部分）
 *   3. 强制追加 LIMIT 100（防止全表扫描）
 *   4. 查询超时：使用 tc_datasource.query_timeout（默认 10s）
 *   5. 使用只读账号（tc_datasource.username 应为只读账号）
 *
 * config JSON 结构：
 * {
 *   "datasourceId": 123,
 *   "sql": "SELECT status FROM orders WHERE id = ?",
 *   "params": ["${orderId}"],
 *   "assertions": [
 *     { "row": 0, "column": "status", "expected": "PAID" }
 *   ]
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbStepExecutor implements StepExecutor {

    private final TcDatasourceMapper datasourceMapper;
    private final ObjectMapper objectMapper;

    /** 数据源连接池缓存（datasourceId → HikariDataSource，Worker 级单例） */
    private final ConcurrentHashMap<Long, HikariDataSource> datasourcePools = new ConcurrentHashMap<>();

    @Override
    public boolean supports(String stepType) {
        return "DB".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        Long datasourceId = config.path("datasourceId").asLong();
        String rawSql = config.path("sql").asText();

        // 1. AST 校验（M1）：只允许 SELECT
        validateSelectOnly(rawSql);

        // 2. 追加 LIMIT 100（防全表扫描）
        String sql = appendLimit(rawSql);

        // 3. 提取参数（变量替换只在 ? 参数位，不替换 SQL 结构）
        List<String> paramValues = new ArrayList<>();
        JsonNode paramsNode = config.path("params");
        if (paramsNode.isArray()) {
            for (JsonNode p : paramsNode) {
                paramValues.add(context.resolve(p.asText()));
            }
        }

        // 4. 获取数据源并执行查询
        TcDatasource datasource = datasourceMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new IllegalArgumentException("数据源 [id=" + datasourceId + "] 不存在");
        }

        HikariDataSource pool = getOrCreatePool(datasource);
        List<Map<String, Object>> rows = executeQuery(pool, sql, paramValues,
                datasource.getQueryTimeout() != null ? datasource.getQueryTimeout() : 10);

        // 5. 处理断言
        JsonNode assertions = config.path("assertions");
        if (!assertions.isMissingNode() && assertions.isArray()) {
            for (JsonNode assertion : assertions) {
                int rowIdx = assertion.path("row").asInt(0);
                String column = assertion.path("column").asText();
                String expected = context.resolve(assertion.path("expected").asText());

                Assertions.assertThat(rows).as("查询结果不为空").isNotEmpty();
                Assertions.assertThat(rows.size()).as("行索引越界").isGreaterThan(rowIdx);
                Object actual = rows.get(rowIdx).get(column);
                Assertions.assertThat(String.valueOf(actual))
                        .as("DB 断言 [row=" + rowIdx + ", column=" + column + "]")
                        .isEqualTo(expected);
            }
        }

        return StepResult.ok("DB 查询成功，返回 " + rows.size() + " 行");
    }

    private void validateSelectOnly(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                throw new SecurityException("DB 步骤只允许 SELECT 语句，实际: " + sql.substring(0, Math.min(50, sql.length())));
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQL 解析失败（JSqlParser）: " + e.getMessage());
        }
    }

    private String appendLimit(String sql) {
        String trimmed = sql.trim();
        // 已有 LIMIT 时不重复追加
        if (trimmed.toUpperCase().contains(" LIMIT ")) {
            return trimmed;
        }
        // 去掉末尾分号
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed + " LIMIT 100";
    }

    private List<Map<String, Object>> executeQuery(HikariDataSource pool, String sql,
                                                    List<String> params, int timeoutSeconds)
            throws Exception {
        try (Connection conn = pool.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private HikariDataSource getOrCreatePool(TcDatasource datasource) {
        return datasourcePools.computeIfAbsent(datasource.getId(), id -> {
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(datasource.getJdbcUrl());
            cfg.setUsername(datasource.getUsername());
            // password 调用方应已解密
            cfg.setPassword(datasource.getPassword());
            cfg.setMaximumPoolSize(datasource.getMaxPoolSize() != null ? datasource.getMaxPoolSize() : 5);
            cfg.setConnectionTimeout(10_000);
            cfg.setReadOnly(true);
            cfg.setPoolName("db-assert-" + id);
            return new HikariDataSource(cfg);
        });
    }
}
