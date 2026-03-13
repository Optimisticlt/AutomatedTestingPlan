package com.webtestpro.api.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webtestpro.api.dto.request.LoginRequest;
import com.webtestpro.api.dto.request.RefreshTokenRequest;
import com.webtestpro.api.dto.response.TokenResponse;
import com.webtestpro.api.entity.SysRole;
import com.webtestpro.api.entity.SysUser;
import com.webtestpro.api.entity.SysUserRole;
import com.webtestpro.api.mapper.SysRoleMapper;
import com.webtestpro.api.mapper.SysUserMapper;
import com.webtestpro.api.mapper.SysUserRoleMapper;
import com.webtestpro.common.enums.AuditEventType;
import com.webtestpro.common.exception.BizException;
import com.webtestpro.common.result.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 认证服务
 *
 * 双 Token 策略：
 *   access_token  → Sa-Token 自动管理，TTL 2h，存 Redis DB0
 *   refresh_token → 自定义 UUID，TTL 7d，存 Redis DB2（key: satoken:refresh:{userId}:{deviceId}）
 *
 * 路由：SaTokenDaoImpl 将 "satoken:refresh:*" 键路由到 redisTemplateLock (DB2)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final long   REFRESH_TOKEN_TTL_SECONDS = 7L * 24 * 60 * 60;
    private static final String REFRESH_KEY_PREFIX        = "satoken:refresh:";

    private final SysUserMapper    userMapper;
    private final SysRoleMapper    roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final AuditLogService  auditLogService;

    @Qualifier("redisTemplateLock")
    private final StringRedisTemplate redisTemplateLock;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${phone.hmac.secret:wtp-phone-hmac-2024}")
    private String phoneHmacSecret;

    /**
     * 用户名密码登录。
     */
    @Transactional(rollbackFor = Exception.class)
    public TokenResponse login(LoginRequest request, String clientIp) {
        // 1. 查用户
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .eq(SysUser::getIsDeleted, 0));

        if (user == null) {
            auditLogService.log(AuditEventType.USER_LOGIN_FAIL, 0L, 0L, clientIp);
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            auditLogService.log(AuditEventType.USER_LOGIN_FAIL, user.getTenantId(), user.getId(), clientIp);
            throw new BizException(ErrorCode.USER_DISABLED);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditLogService.log(AuditEventType.USER_LOGIN_FAIL, user.getTenantId(), user.getId(), clientIp);
            throw new BizException(ErrorCode.PASSWORD_WRONG);
        }

        // 2. Sa-Token 登录（生成 access_token）
        StpUtil.login(user.getId());
        String accessToken = StpUtil.getTokenValue();

        // 3. 在 Session 中存入租户信息和角色
        List<String> roles = getUserRoles(user.getId());
        StpUtil.getTokenSession()
                .set("tenantId", user.getTenantId())
                .set("userId",   user.getId())
                .set("roles",    roles);

        // 4. 生成 refresh_token UUID 并写入 Redis DB2
        String deviceId     = UUID.randomUUID().toString().replace("-", "");
        String refreshToken = UUID.randomUUID().toString().replace("-", "");
        String refreshKey   = REFRESH_KEY_PREFIX + user.getId() + ":" + deviceId;
        redisTemplateLock.opsForValue().set(refreshKey, refreshToken,
                REFRESH_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        // 5. 更新最后登录信息
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setLastLoginIp(clientIp);
        update.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(update);

        // 6. 审计日志（异步）
        auditLogService.log(AuditEventType.USER_LOGIN, user.getTenantId(), user.getId(), clientIp);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .deviceId(deviceId)
                .expiresIn(7200L)
                .build();
    }

    /**
     * 登出：使当前 access_token 失效。
     */
    public void logout() {
        long userId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        log.info("User {} logged out", userId);
    }

    /**
     * 使用 refresh_token 换取新 access_token（Token 轮转）。
     */
    public TokenResponse refresh(RefreshTokenRequest request) {
        String refreshKey   = REFRESH_KEY_PREFIX + request.getUserId() + ":" + request.getDeviceId();
        String storedToken  = redisTemplateLock.opsForValue().get(refreshKey);

        if (storedToken == null || !storedToken.equals(request.getRefreshToken())) {
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        SysUser user = userMapper.selectById(request.getUserId());
        if (user == null || (user.getStatus() != null && user.getStatus() == 1)) {
            redisTemplateLock.delete(refreshKey);
            throw new BizException(ErrorCode.TOKEN_INVALID);
        }

        // 重新登录生成新 access_token
        StpUtil.login(user.getId());
        String accessToken = StpUtil.getTokenValue();

        // 更新 Session
        List<String> roles = getUserRoles(user.getId());
        StpUtil.getTokenSession()
                .set("tenantId", user.getTenantId())
                .set("userId",   user.getId())
                .set("roles",    roles);

        // 轮转 refresh_token（旧 token 作废，续期 7 天）
        String newRefreshToken = UUID.randomUUID().toString().replace("-", "");
        redisTemplateLock.opsForValue().set(refreshKey, newRefreshToken,
                REFRESH_TOKEN_TTL_SECONDS, TimeUnit.SECONDS);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken)
                .deviceId(request.getDeviceId())
                .expiresIn(7200L)
                .build();
    }

    private List<String> getUserRoles(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (userRoles.isEmpty()) return List.of();
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).collect(Collectors.toList());
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toList());
    }
}
