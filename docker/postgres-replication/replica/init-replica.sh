#!/usr/bin/env bash
set -eo pipefail

MASTER_HOST="${POSTGRES_MASTER_SERVICE:-master}"
MASTER_PORT=5432
REPLICATOR_USER="replicator"
REPLICATOR_PASSWORD="${REPLICATOR_PASSWORD:-}"

# 检查必需的环境变量
if [ -z "${REPLICATOR_PASSWORD}" ]; then
    echo "ERROR: REPLICATOR_PASSWORD environment variable is not set!"
    echo "Please set REPLICATOR_PASSWORD in your .env file."
    exit 1
fi

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
# 如果 PGDATA 包含 pgdata 子目录，使用完整路径
if [ -d "${PGDATA}/pgdata" ]; then
    PGDATA="${PGDATA}/pgdata"
fi

echo "Waiting for master to be ready..."
until pg_isready -h "${MASTER_HOST}" -p "${MASTER_PORT}" -U "${POSTGRES_USER:-postgres}"; do
  echo "Master is not ready yet. Waiting..."
  sleep 2
done

echo "Master is ready. Starting replica initialization..."

# 如果数据目录不为空，跳过初始化（已存在的副本）
if [ "$(ls -A ${PGDATA} 2>/dev/null)" ]; then
    echo "Data directory is not empty. Skipping initialization."
    exit 0
fi

# 使用 pg_basebackup 从主库复制数据
echo "Performing base backup from master..."

export PGPASSWORD="${REPLICATOR_PASSWORD}"
pg_basebackup \
    -h "${MASTER_HOST}" \
    -p "${MASTER_PORT}" \
    -U "${REPLICATOR_USER}" \
    -D "${PGDATA}" \
    -Fp \
    -Xs \
    -P \
    -R

# 检查 pg_basebackup 是否成功
if [ $? -ne 0 ]; then
    echo "ERROR: pg_basebackup failed!"
    exit 1
fi

# PostgreSQL 10 使用 recovery.conf 文件
# pg_basebackup -R 选项应该已经创建了 recovery.conf，但我们确保它存在
if [ ! -f "${PGDATA}/recovery.conf" ]; then
    echo "Creating recovery.conf..."
    cat > "${PGDATA}/recovery.conf" <<EOF
standby_mode = 'on'
primary_conninfo = 'host=${MASTER_HOST} port=${MASTER_PORT} user=${REPLICATOR_USER} password=${REPLICATOR_PASSWORD}'
trigger_file = '/tmp/postgresql.trigger'
EOF
else
    echo "recovery.conf already exists, updating primary_conninfo..."
    # 更新 primary_conninfo 确保密码正确
    sed -i "s|primary_conninfo = .*|primary_conninfo = 'host=${MASTER_HOST} port=${MASTER_PORT} user=${REPLICATOR_USER} password=${REPLICATOR_PASSWORD}'|" "${PGDATA}/recovery.conf"
fi

# 设置正确的权限
chown -R postgres:postgres "${PGDATA}"
chmod 700 "${PGDATA}"

# 配置 pg_hba.conf 允许远程访问（从库也需要允许远程只读连接）
PG_HBA_FILE="${PGDATA}/pg_hba.conf"
if [ -f "${PG_HBA_FILE}" ]; then
    # 添加远程数据库连接配置（如果不存在）
    if ! grep -q "^host.*all.*all.*0.0.0.0/0.*md5" "${PG_HBA_FILE}" 2>/dev/null; then
        cat >> "${PG_HBA_FILE}" <<EOF

# Allow remote connections for database access (read-only on replica)
host    all             all             0.0.0.0/0               md5
EOF
        echo "Added remote database access configuration to replica."
    fi
fi

echo "Replica initialization completed successfully!"
