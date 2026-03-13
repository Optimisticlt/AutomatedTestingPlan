package com.webtestpro.api.log;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * 敏感数据日志脱敏 PatternConverter
 *
 * 注册为 Log4j2 插件，转换键：sanitized / san。
 * 在 PatternLayout 中将 %msg 替换为 %san 即可对该 Appender 开启脱敏。
 *
 * 脱敏规则：
 *   "phone":"..."         → "phone":"***"
 *   "password":"..."      → "password":"***"
 *   "token":"..."         → "token":"***"
 *   "tokenValue":"..."    → "tokenValue":"***"
 *   Bearer <jwt>          → Bearer ***
 */
@Plugin(name = "SensitiveDataConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"sanitized", "san"})
public class SensitiveDataConverter extends LogEventPatternConverter {

    private static final String[][] RULES = {
        { "\"phone\":\"[^\"]+\"",      "\"phone\":\"***\""      },
        { "\"password\":\"[^\"]+\"",   "\"password\":\"***\""   },
        { "\"token\":\"[^\"]+\"",      "\"token\":\"***\""      },
        { "\"tokenValue\":\"[^\"]+\"", "\"tokenValue\":\"***\"" },
        { "Bearer [A-Za-z0-9._\\-]+",  "Bearer ***"             },
    };

    private SensitiveDataConverter(String[] options) {
        super("san", "san");
    }

    public static SensitiveDataConverter newInstance(String[] options) {
        return new SensitiveDataConverter(options);
    }

    @Override
    public void format(LogEvent event, StringBuilder toAppendTo) {
        String message = event.getMessage().getFormattedMessage();
        for (String[] rule : RULES) {
            message = message.replaceAll(rule[0], rule[1]);
        }
        toAppendTo.append(message);
    }
}
