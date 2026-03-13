package com.webtestpro.api.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenResponse {
    private String accessToken;
    private String refreshToken;
    private String deviceId;
    private Long   expiresIn;
}
