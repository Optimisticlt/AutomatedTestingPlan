package com.webtestpro.api.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket / STOMP 配置
 *
 * 端点：  /ws/execution/{execId}（支持 SockJS 回退）
 * Broker：/topic（Simple Broker）
 * 应用前缀：/app
 *
 * 安全策略：
 *   1. HTTP Handshake 阶段：验证 token query param，提取 loginId、execId 存入 Session。
 *   2. STOMP SUBSCRIBE 帧：验证订阅的 execId 与握手时存入的 execId 一致。
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Pattern EXEC_ID_PATTERN =
            Pattern.compile("/ws/execution/([^/?]+)");

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        HandshakeInterceptor authInterceptor = new TokenHandshakeInterceptor();

        registry.addEndpoint("/ws/execution/{execId}")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authInterceptor);

        registry.addEndpoint("/ws/execution/{execId}")
                .setAllowedOriginPatterns("*")
                .addInterceptors(authInterceptor)
                .withSockJS();
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration
                .setMessageSizeLimit(64 * 1024)
                .setSendTimeLimit(20 * 1000)
                .setSendBufferSizeLimit(512 * 1024);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor == null) {
                    return message;
                }

                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    String destination = accessor.getDestination();
                    if (destination == null) {
                        return message;
                    }

                    if (destination.startsWith("/topic/execution/") && destination.endsWith("/log")) {
                        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                        if (sessionAttributes == null) {
                            log.warn("WS SUBSCRIBE 被拒绝：无 session attributes, destination={}", destination);
                            return null;
                        }

                        Object loginId = sessionAttributes.get("loginId");
                        if (loginId == null) {
                            log.warn("WS SUBSCRIBE 被拒绝：loginId 为空, destination={}", destination);
                            return null;
                        }

                        String sessionExecId = (String) sessionAttributes.get("execId");
                        String destExecId = extractExecIdFromTopic(destination);
                        if (destExecId == null || !destExecId.equals(sessionExecId)) {
                            log.warn("WS SUBSCRIBE 被拒绝：execId 不匹配, sessionExecId={}, destExecId={}, loginId={}",
                                    sessionExecId, destExecId, loginId);
                            return null;
                        }
                    }
                }
                return message;
            }
        });
    }

    private static class TokenHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(
                org.springframework.http.server.ServerHttpRequest request,
                org.springframework.http.server.ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Map<String, Object> attributes) {

            URI uri = request.getURI();
            String query = uri.getQuery();
            String token = extractQueryParam(query, "token");

            if (token == null || token.isBlank()) {
                log.warn("WS 握手被拒绝：缺少 token, uri={}", uri);
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            Object loginId;
            try {
                loginId = StpUtil.getLoginIdByToken(token);
                if (loginId == null) {
                    log.warn("WS 握手被拒绝：token 无效, uri={}", uri);
                    response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                    return false;
                }
            } catch (Exception e) {
                log.warn("WS 握手被拒绝：token 校验异常, uri={}, error={}", uri, e.getMessage());
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }

            String path = uri.getPath();
            String execId = extractExecIdFromPath(path);
            if (execId == null) {
                log.warn("WS 握手被拒绝：无法提取 execId, path={}", path);
                response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
                return false;
            }

            attributes.put("loginId", loginId);
            attributes.put("execId", execId);
            log.debug("WS 握手成功: loginId={}, execId={}", loginId, execId);
            return true;
        }

        @Override
        public void afterHandshake(
                org.springframework.http.server.ServerHttpRequest request,
                org.springframework.http.server.ServerHttpResponse response,
                WebSocketHandler wsHandler,
                Exception exception) {
        }

        private String extractQueryParam(String query, String paramName) {
            if (query == null || query.isBlank()) {
                return null;
            }
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && paramName.equals(kv[0])) {
                    return kv[1];
                }
            }
            return null;
        }

        private String extractExecIdFromPath(String path) {
            if (path == null) return null;
            Matcher m = EXEC_ID_PATTERN.matcher(path);
            return m.find() ? m.group(1) : null;
        }
    }

    private static String extractExecIdFromTopic(String destination) {
        if (destination == null) return null;
        String[] parts = destination.split("/");
        if (parts.length >= 4) {
            return parts[3];
        }
        return null;
    }
}
