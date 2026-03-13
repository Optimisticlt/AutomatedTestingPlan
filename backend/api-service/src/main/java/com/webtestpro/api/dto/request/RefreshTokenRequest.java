package com.webtestpro.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RefreshTokenRequest {

    @NotNull(message = "userId 不能为空")
    private Long userId;

    @NotBlank(message = "deviceId 不能为空")
    private String deviceId;

    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
