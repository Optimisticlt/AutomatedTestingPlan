#!/bin/sh
# Vault dev 模式密钥初始化脚本
# 在 Vault 容器启动后执行，写入平台所需的初始 secret
# 使用方式：docker exec wtp-vault sh /docker-entrypoint-initdb.d/init-secrets.sh

export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=${VAULT_DEV_TOKEN:-wtp-dev-root-token}

# 等待 Vault 就绪
sleep 3

# 启用 KV v2 secrets engine（已存在时忽略错误）
vault secrets enable -path=secret kv-v2 2>/dev/null || true

# ---- 生成随机密钥 ----
AES_KEY="$(openssl rand -base64 32)"
WEBHOOK_SECRET="$(openssl rand -base64 32)"

# ---- 主密钥路径（Spring Cloud Vault default-context 直接映射为 Spring 属性）----
# Spring 属性 encryption.key → Vault key "encryption.key" at secret/webtestpro
# Spring 属性 webhook.secret → Vault key "webhook.secret" at secret/webtestpro
vault kv put secret/webtestpro \
  "encryption.key=${AES_KEY}" \
  "webhook.secret=${WEBHOOK_SECRET}"

# ---- 分路径保留（供 Vault UI 查阅，不被 Spring 直接加载）----
vault kv put secret/webtestpro/aes-key \
  key="${AES_KEY}" \
  key_version="1"

vault kv put secret/webtestpro/webhook-secret \
  secret="${WEBHOOK_SECRET}" \
  version="1"

vault kv put secret/webtestpro/xxl-job \
  access_token="${XXL_JOB_TOKEN:-wtp-xxl-job-token-2024}"

echo "Vault secrets initialized successfully"
vault kv list secret/webtestpro
