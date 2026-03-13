package com.webtestpro.worker.engine.context;

import com.webtestpro.worker.entity.TcEnvVariable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 执行上下文：单次用例执行期间的变量沙箱。
 *
 * 变量来源（优先级从高到低）：
 *   1. EXTRACT 步骤运行时提取的变量（最高优先级，可覆盖环境变量）
 *   2. 环境变量（env_snapshot 中的明文值，执行开始时快照，不受后续修改影响）
 *
 * 变量替换语法：${变量名}
 *
 * 注意（NH1 安全规范）：
 *   - XPath/CSS 步骤：替换后的值必须经 XPath 字符串转义
 *   - DB 步骤：变量只允许出现在 PreparedStatement 的 ? 参数位，不可出现在 SQL 结构部分
 *   - API 步骤 URL：使用 URIComponentsBuilder 参数化构建
 */
@Slf4j
public class ExecutionContext {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    /** Faker 内置变量前缀 */
    private static final String FAKER_PREFIX = "faker.";

    /** 执行 ID（日志追踪用） */
    @Getter
    private final Long executionId;

    /** 用例 ID（当前正在执行的用例） */
    @Getter
    private Long currentCaseId;

    /** 环境变量快照（明文，从 env_snapshot 解析） */
    private final Map<String, String> envVariables;

    /** 运行时提取变量（EXTRACT 步骤写入，最高优先级） */
    private final ConcurrentHashMap<String, String> runtimeVariables = new ConcurrentHashMap<>();

    /** 日志索引计数器（LogFlusher 使用，原子自增） */
    private volatile int logIndex = 0;

    public ExecutionContext(Long executionId, List<TcEnvVariable> envVariableList) {
        this.executionId = executionId;
        this.envVariables = new HashMap<>();
        if (envVariableList != null) {
            for (TcEnvVariable v : envVariableList) {
                // 此处存入的是明文值（调用方负责解密）
                envVariables.put(v.getVarKey(), v.getVarValue() != null ? v.getVarValue() : "");
            }
        }
    }

    public void setCurrentCaseId(Long caseId) {
        this.currentCaseId = caseId;
    }

    /** EXTRACT 步骤写入运行时变量 */
    public void setVariable(String key, String value) {
        runtimeVariables.put(key, value);
        log.debug("[exec={}][case={}] 提取变量 {}={}", executionId, currentCaseId, key, value);
    }

    /** 获取变量值（运行时变量优先于环境变量） */
    public String getVariable(String key) {
        if (runtimeVariables.containsKey(key)) {
            return runtimeVariables.get(key);
        }
        return envVariables.getOrDefault(key, "");
    }

    /**
     * 对字符串进行变量替换（${varName} → 实际值）。
     * 未找到的变量保留原始占位符（不静默替换为空，方便排查）。
     */
    public String resolve(String template) {
        if (template == null || !template.contains("${")) {
            return template;
        }
        Matcher m = VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1);
            String value = resolveVar(varName);
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析单个变量名，支持内置 Faker 数据工厂。
     */
    private String resolveVar(String varName) {
        if (varName.startsWith(FAKER_PREFIX)) {
            return resolveFaker(varName.substring(FAKER_PREFIX.length()));
        }
        if (runtimeVariables.containsKey(varName)) {
            return runtimeVariables.get(varName);
        }
        if (envVariables.containsKey(varName)) {
            return envVariables.get(varName);
        }
        log.warn("[exec={}] 未找到变量 ${{{}}}, 保留原始占位符", executionId, varName);
        return "${" + varName + "}";
    }

    /**
     * Faker 内置数据工厂（动态生成测试数据）。
     * 支持：phone / email / name / uuid
     */
    private String resolveFaker(String type) {
        return switch (type) {
            case "phone" -> generatePhone();
            case "email" -> generateEmail();
            case "name" -> generateName();
            case "uuid" -> java.util.UUID.randomUUID().toString();
            default -> "${faker." + type + "}";
        };
    }

    private String generatePhone() {
        // 生成合法格式手机号（测试用）
        String[] prefixes = {"130", "131", "132", "133", "134", "135", "136", "137",
                "138", "139", "150", "151", "152", "153", "155", "156",
                "157", "158", "159", "170", "176", "177", "178", "180",
                "181", "182", "183", "184", "185", "186", "187", "188", "189"};
        String prefix = prefixes[(int) (Math.random() * prefixes.length)];
        long suffix = (long) (Math.random() * 100_000_000L);
        return prefix + String.format("%08d", suffix);
    }

    private String generateEmail() {
        String[] domains = {"test.com", "example.com", "qa.org"};
        String user = "user_" + System.nanoTime() % 100_000;
        String domain = domains[(int) (Math.random() * domains.length)];
        return user + "@" + domain;
    }

    private String generateName() {
        String[] surnames = {"张", "李", "王", "刘", "陈", "杨", "赵", "黄"};
        String[] names = {"伟", "芳", "娜", "秀英", "敏", "静", "丽", "强", "磊", "军"};
        return surnames[(int) (Math.random() * surnames.length)]
                + names[(int) (Math.random() * names.length)];
    }

    /** 获取并自增日志索引（线程安全） */
    public synchronized int nextLogIndex() {
        return logIndex++;
    }

    /** 返回所有变量的合并视图（调试用，不含运行时变量覆盖前的值） */
    public Map<String, String> getAllVariables() {
        Map<String, String> all = new HashMap<>(envVariables);
        all.putAll(runtimeVariables);
        return all;
    }

    /**
     * XPath 字符串安全转义（NH1 修复：防止变量值中含单引号破坏 XPath 结构）。
     * 使用 concat() 技术将含单引号的字符串拆分。
     */
    public static String escapeXPathString(String value) {
        if (!value.contains("'")) {
            return "'" + value + "'";
        }
        // 拆分含单引号的字符串为 concat(...)
        String[] parts = value.split("'", -1);
        StringBuilder sb = new StringBuilder("concat(");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", \"'\", ");
            sb.append("'").append(parts[i]).append("'");
        }
        sb.append(")");
        return sb.toString();
    }
}
