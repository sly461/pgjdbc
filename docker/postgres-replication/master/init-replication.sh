#!/usr/bin/env bash
set -eo pipefail

# 检查必需的环境变量（使用默认值避免 unbound variable 错误）
REPLICATOR_PASSWORD="${REPLICATOR_PASSWORD:-}"

if [ -z "${REPLICATOR_PASSWORD}" ]; then
    echo "ERROR: REPLICATOR_PASSWORD environment variable is not set!"
    echo "Please set REPLICATOR_PASSWORD in your .env file."
    exit 1
fi

# 等待 PostgreSQL 完全启动
echo "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if pg_isready -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-postgres}" >/dev/null 2>&1; then
        break
    fi
    if [ $i -eq 30 ]; then
        echo "ERROR: PostgreSQL did not become ready in time"
        exit 1
    fi
    sleep 2
done

echo "PostgreSQL is ready. Creating replication user..."

# 创建复制用户
# 转义密码中的单引号，避免 SQL 注入
REPLICATOR_PASSWORD_ESCAPED=$(echo "${REPLICATOR_PASSWORD}" | sed "s/'/''/g")

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname "${POSTGRES_DB:-postgres}" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_user WHERE usename = 'replicator') THEN
            CREATE USER replicator WITH REPLICATION ENCRYPTED PASSWORD '${REPLICATOR_PASSWORD_ESCAPED}';
            RAISE NOTICE 'Replication user created successfully';
        ELSE
            RAISE NOTICE 'Replication user already exists';
        END IF;
    END
    \$\$;
EOSQL

# 配置 pg_hba.conf 允许从库连接
echo "Configuring pg_hba.conf for replication..."

# 获取 PostgreSQL 数据目录
# 如果 PGDATA 环境变量未设置，使用默认路径
PGDATA_PATH="${PGDATA:-/var/lib/postgresql/data}"
# 如果 PGDATA 指向的是包含 pgdata 子目录的路径，使用子目录
if [ -d "${PGDATA_PATH}/pgdata" ]; then
    PGDATA_PATH="${PGDATA_PATH}/pgdata"
fi

PG_HBA_FILE="${PGDATA_PATH}/pg_hba.conf"

# 等待 pg_hba.conf 文件创建（PostgreSQL 初始化可能需要一些时间）
if [ ! -f "${PG_HBA_FILE}" ]; then
    echo "Waiting for pg_hba.conf to be created..."
    for i in {1..10}; do
        if [ -f "${PG_HBA_FILE}" ]; then
            break
        fi
        sleep 1
    done
fi

# 检查并添加远程访问配置
if [ -f "${PG_HBA_FILE}" ]; then
    # 添加远程数据库连接配置（如果不存在）
    if ! grep -q "^host.*all.*all.*0.0.0.0/0.*md5" "${PG_HBA_FILE}" 2>/dev/null; then
        cat >> "${PG_HBA_FILE}" <<EOF

# Allow remote connections for database access
host    all             all             0.0.0.0/0               md5
EOF
        echo "Added remote database access configuration."
    fi
    
    # 添加复制连接配置（如果不存在）
    if ! grep -q "replication.*replicator" "${PG_HBA_FILE}" 2>/dev/null; then
        cat >> "${PG_HBA_FILE}" <<EOF

# Replication connections
host    replication     replicator      0.0.0.0/0               md5
EOF
        echo "Added replication access configuration."
    fi
    
    # 重新加载配置（如果 PostgreSQL 已经运行）
    if pg_isready -U "${POSTGRES_USER:-postgres}" >/dev/null 2>&1; then
        psql -v ON_ERROR_STOP=0 --username "${POSTGRES_USER:-postgres}" --dbname "${POSTGRES_DB:-postgres}" -c "SELECT pg_reload_conf();" 2>/dev/null || true
        echo "pg_hba.conf updated and configuration reloaded."
    else
        echo "pg_hba.conf updated (will be loaded on next restart)."
    fi
else
    echo "Warning: pg_hba.conf file not found at ${PG_HBA_FILE}, but this may be normal during initialization."
fi

echo "Replication user created and pg_hba.conf configured successfully."
