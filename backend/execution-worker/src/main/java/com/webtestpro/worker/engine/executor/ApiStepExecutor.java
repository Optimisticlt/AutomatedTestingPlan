package com.webtestpro.worker.engine.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webtestpro.worker.engine.context.ExecutionContext;
import com.webtestpro.worker.entity.TcStep;
import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.Assertions;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Iterator;
import java.util.Map;

/**
 * API 步骤执行器（HTTP 接口测试）
 *
 * config JSON 结构：
 * {
 *   "method": "POST",
 *   "url": "http://test.app.com/api/login",
 *   "headers": { "Content-Type": "application/json" },
 *   "params": { "key": "value" },          // query 参数（安全：URIComponentsBuilder）
 *   "body": "{ \"username\": \"${username}\" }",
 *   "assertions": [
 *     { "type": "STATUS_CODE", "expected": "200" },
 *     { "type": "JSON_PATH", "path": "$.data.token", "expected": "${token}", "extract": "token" }
 *   ]
 * }
 *
 * 安全规范（NH1）：URL 参数通过 URIComponentsBuilder 参数化构建，禁止字符串拼接。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiStepExecutor implements StepExecutor {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String stepType) {
        return "API".equalsIgnoreCase(stepType);
    }

    @Override
    public StepResult execute(TcStep step, ExecutionContext context) throws Exception {
        JsonNode config = objectMapper.readTree(step.getConfig());
        String method = config.path("method").asText("GET").toUpperCase();
        String rawUrl = context.resolve(config.path("url").asText());

        // 安全构建 URL（NH1：禁止字符串拼接 URL）
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(rawUrl);
        JsonNode params = config.path("params");
        if (!params.isMissingNode()) {
            Iterator<Map.Entry<String, JsonNode>> fields = params.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                uriBuilder.queryParam(entry.getKey(), context.resolve(entry.getValue().asText()));
            }
        }
        String url = uriBuilder.build().toUriString();

        RequestSpecification spec = RestAssured.given().relaxedHTTPSValidation();

        // 设置请求头
        JsonNode headers = config.path("headers");
        if (!headers.isMissingNode()) {
            Iterator<Map.Entry<String, JsonNode>> hFields = headers.fields();
            while (hFields.hasNext()) {
                Map.Entry<String, JsonNode> h = hFields.next();
                spec.header(h.getKey(), context.resolve(h.getValue().asText()));
            }
        }

        // 设置请求体
        JsonNode bodyNode = config.path("body");
        if (!bodyNode.isMissingNode()) {
            spec.body(context.resolve(bodyNode.asText()));
        }

        log.debug("[exec={}] API {} {}", context.getExecutionId(), method, url);

        Response response = switch (method) {
            case "GET" -> spec.get(url);
            case "POST" -> spec.post(url);
            case "PUT" -> spec.put(url);
            case "DELETE" -> spec.delete(url);
            case "PATCH" -> spec.patch(url);
            default -> throw new IllegalArgumentException("不支持的 HTTP 方法: " + method);
        };

        // 处理断言和变量提取
        JsonNode assertions = config.path("assertions");
        String extractedKey = null;
        String extractedValue = null;

        if (!assertions.isMissingNode() && assertions.isArray()) {
            for (JsonNode assertion : assertions) {
                String type = assertion.path("type").asText();
                String expected = context.resolve(assertion.path("expected").asText(""));
                String extractVar = assertion.path("extract").asText(null);

                switch (type) {
                    case "STATUS_CODE" -> {
                        Allure.step("断言状态码: " + expected);
                        Assertions.assertThat(String.valueOf(response.getStatusCode()))
                                .as("HTTP 状态码").isEqualTo(expected);
                    }
                    case "JSON_PATH" -> {
                        String jsonPath = assertion.path("path").asText();
                        String actual = response.jsonPath().getString(jsonPath);
                        if (!expected.isBlank()) {
                            Allure.step("断言 JSONPath " + jsonPath + " = " + expected);
                            Assertions.assertThat(actual).as("JSONPath [" + jsonPath + "]").isEqualTo(expected);
                        }
                        if (extractVar != null && !extractVar.isBlank()) {
                            context.setVariable(extractVar, actual);
                            extractedKey = extractVar;
                            extractedValue = actual;
                        }
                    }
                    case "HEADER" -> {
                        String headerName = assertion.path("header").asText();
                        String actual = response.getHeader(headerName);
                        Allure.step("断言响应头 " + headerName + " = " + expected);
                        Assertions.assertThat(actual).as("响应头 [" + headerName + "]").contains(expected);
                    }
                    case "BODY_CONTAINS" -> {
                        Allure.step("断言响应体包含: " + expected);
                        Assertions.assertThat(response.getBody().asString())
                                .as("响应体").contains(expected);
                    }
                }
            }
        }

        return StepResult.builder()
                .success(true)
                .message("API " + method + " " + url + " → " + response.getStatusCode())
                .extractedKey(extractedKey)
                .extractedValue(extractedValue)
                .build();
    }
}
