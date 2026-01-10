#!/usr/bin/env bash
set -eo pipefail

MASTER_HOST="${POSTGRES_MASTER_SERVICE:-master}"
MASTER_PORT=5432
REPLICATOR_USER="replicator"
REPLICATOR_PASSWORD="${REPLICATOR_PASSWORD:-}"
PGDATA="${PGDATA:-/var/lib/postgresql/data}"

# 检查必需的环境变量
if [ -z "${REPLICATOR_PASSWORD}" ]; then
    echo "ERROR: REPLICATOR_PASSWORD environment variable is not set!"
    echo "Please set REPLICATOR_PASSWORD in your .env file."
    exit 1
fi

# 如果 PGDATA 包含 pgdata 子目录，使用完整路径
if [ -d "${PGDATA}/pgdata" ]; then
    PGDATA_FULL="${PGDATA}/pgdata"
else
    PGDATA_FULL="${PGDATA}"
fi

# 检查数据目录是否为空（需要初始化）
if [ ! -d "${PGDATA_FULL}" ] || [ -z "$(ls -A ${PGDATA_FULL} 2>/dev/null)" ]; then
    echo "Data directory is empty. Starting replica initialization from master..."
    
    # 等待主库就绪
    echo "Waiting for master to be ready..."
    for i in {1..60}; do
        if pg_isready -h "${MASTER_HOST}" -p "${MASTER_PORT}" -U "${POSTGRES_USER:-postgres}" >/dev/null 2>&1; then
            echo "Master is ready."
            break
        fi
        if [ $i -eq 60 ]; then
            echo "ERROR: Master did not become ready in time"
            exit 1
        fi
        echo "Master is not ready yet. Waiting... ($i/60)"
        sleep 2
    done
    
    # 确保数据目录存在
    mkdir -p "${PGDATA_FULL}"
    chown -R postgres:postgres "${PGDATA}"
    chmod 700 "${PGDATA_FULL}"
    
    # 使用 pg_basebackup 从主库复制数据
    # 注意：PostgreSQL容器通常以postgres用户运行，但entrypoint可能以root启动
    # 我们需要确保以postgres用户运行pg_basebackup
    echo "Performing base backup from master..."
    
    export PGPASSWORD="${REPLICATOR_PASSWORD}"
    
    # 尝试不同的方式切换到postgres用户
    if command -v su-exec >/dev/null 2>&1; then
        su-exec postgres pg_basebackup \
            -h "${MASTER_HOST}" \
            -p "${MASTER_PORT}" \
            -U "${REPLICATOR_USER}" \
            -D "${PGDATA_FULL}" \
            -Fp \
            -Xs \
            -P \
            -R
    elif command -v gosu >/dev/null 2>&1; then
        gosu postgres pg_basebackup \
            -h "${MASTER_HOST}" \
            -p "${MASTER_PORT}" \
            -U "${REPLICATOR_USER}" \
            -D "${PGDATA_FULL}" \
            -Fp \
            -Xs \
            -P \
            -R
    elif [ "$(id -u)" = "0" ]; then
        # 如果是root用户，切换到postgres
        su - postgres -c "export PGPASSWORD='${REPLICATOR_PASSWORD}'; pg_basebackup -h ${MASTER_HOST} -p ${MASTER_PORT} -U ${REPLICATOR_USER} -D ${PGDATA_FULL} -Fp -Xs -P -R"
    else
        # 直接运行（可能已经是postgres用户）
        pg_basebackup \
            -h "${MASTER_HOST}" \
            -p "${MASTER_PORT}" \
            -U "${REPLICATOR_USER}" \
            -D "${PGDATA_FULL}" \
            -Fp \
            -Xs \
            -P \
            -R
    fi
    
    # 检查 pg_basebackup 是否成功
    if [ $? -ne 0 ]; then
        echo "ERROR: pg_basebackup failed!"
        exit 1
    fi
    
    # PostgreSQL 10 使用 recovery.conf 文件
    # pg_basebackup -R 选项应该已经创建了 recovery.conf，但我们确保它存在并正确配置
    if [ ! -f "${PGDATA_FULL}/recovery.conf" ]; then
        echo "Creating recovery.conf..."
        cat > "${PGDATA_FULL}/recovery.conf" <<EOF
standby_mode = 'on'
primary_conninfo = 'host=${MASTER_HOST} port=${MASTER_PORT} user=${REPLICATOR_USER} password=${REPLICATOR_PASSWORD}'
trigger_file = '/tmp/postgresql.trigger'
EOF
    else
        echo "recovery.conf already exists, updating primary_conninfo..."
        # 更新 primary_conninfo 确保密码正确
        sed -i "s|primary_conninfo = .*|primary_conninfo = 'host=${MASTER_HOST} port=${MASTER_PORT} user=${REPLICATOR_USER} password=${REPLICATOR_PASSWORD}'|" "${PGDATA_FULL}/recovery.conf"
    fi
    
    # 配置 pg_hba.conf 允许远程访问（从库也需要允许远程只读连接）
    PG_HBA_FILE="${PGDATA_FULL}/pg_hba.conf"
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
    
    # 设置正确的权限
    chown -R postgres:postgres "${PGDATA}"
    chmod 700 "${PGDATA_FULL}"
    
    echo "Replica initialization completed successfully!"
else
    echo "Data directory is not empty. Skipping initialization."
    
    # 确保 recovery.conf 存在且配置正确（用于重启后的情况）
    if [ -f "${PGDATA_FULL}/recovery.conf" ]; then
        # 更新 primary_conninfo 确保密码正确
        sed -i "s|primary_conninfo = .*|primary_conninfo = 'host=${MASTER_HOST} port=${MASTER_PORT} user=${REPLICATOR_USER} password=${REPLICATOR_PASSWORD}'|" "${PGDATA_FULL}/recovery.conf"
    fi
fi

# 调用 PostgreSQL 的原始 entrypoint
if [ -x /usr/local/bin/docker-entrypoint.sh ]; then
    exec /usr/local/bin/docker-entrypoint.sh "$@"
elif [ -x /docker-entrypoint.sh ]; then
    exec /docker-entrypoint.sh "$@"
else
    echo "ERROR: Could not find PostgreSQL entrypoint script"
    exit 1
fi
