#!/bin/sh
# Vault dev 模式密钥初始化脚本
# 在 Vault 容器启动后执行，写入平台所需的初始 secret
# 使用方式：docker exec wtp-vault sh /docker-entrypoint-initdb.d/init-secrets.sh

export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=${VAULT_DEV_TOKEN:-wtp-dev-root-token}

# 等待 Vault 就绪
sleep 3

# 启用 KV v2 secrets engine
vault secrets enable -path=secret kv-v2 2>/dev/null || true

# ---- AES-256-GCM 主密钥（应用层加密用）----
vault kv put secret/webtestpro/aes-key \
  key="$(openssl rand -base64 32)" \
  key_version="1"

# ---- Webhook HMAC-SHA256 签名密钥 ----
vault kv put secret/webtestpro/webhook-secret \
  secret="$(openssl rand -base64 32)" \
  version="1"

# ---- XXL-JOB accessToken ----
vault kv put secret/webtestpro/xxl-job \
  access_token="${XXL_JOB_TOKEN:-wtp-xxl-job-token-2024}"

echo "✅ Vault secrets initialized successfully"
vault kv list secret/webtestpro
