# PostgreSQL 10 一主两从 Docker Compose 配置

本配置提供了 PostgreSQL 10 的一主两从流复制架构，使用 Docker Compose 进行部署。

## 架构说明

- **主库 (master)**: 端口 5432，负责所有写入操作和读取操作
- **从库1 (replica1)**: 端口 5433，只读副本
- **从库2 (replica2)**: 端口 5434，只读副本

## 快速开始

### 1. 配置环境变量

```bash
cd docker/postgres-replication
cp env.example .env
```

编辑 `.env` 文件，设置以下变量：
- `POSTGRES_PASSWORD`: 数据库超级用户密码
- `REPLICATOR_PASSWORD`: 复制用户密码（用于流复制）

### 2. 创建数据目录

```bash
mkdir -p data/{master,replica1,replica2}
```

### 3. 启动服务

```bash
docker-compose up -d
```

### 4. 验证安装

等待所有容器启动（首次启动可能需要 1-2 分钟，因为从库需要从主库复制数据）。

检查容器状态：
```bash
docker-compose ps
```

连接到主库验证：
```bash
docker exec -it postgres-master psql -U postgres -d postgres
```

在主库中查看复制状态：
```sql
SELECT * FROM pg_stat_replication;
```

应该能看到两条记录，分别对应 replica1 和 replica2。

## 配置说明

### 主库配置

主库启用了以下关键配置：
- `wal_level=replica`: 启用 WAL 归档，支持流复制
- `max_wal_senders=3`: 允许最多 3 个 WAL 发送进程（支持两个从库）
- `max_replication_slots=3`: 配置复制槽数量
- `hot_standby=on`: 启用热备模式

### 从库配置

从库配置：
- `hot_standby=on`: 启用只读查询
- 自动从主库同步数据
- 使用 `recovery.conf` 配置流复制（PostgreSQL 10）

### 数据持久化

数据目录挂载到宿主机：
- 主库: `./data/master`
- 从库1: `./data/replica1`
- 从库2: `./data/replica2`

**注意**: 确保数据目录有正确的权限。如果遇到权限问题，可以运行：
```bash
sudo chown -R 999:999 data/
```

## 使用方式

### 连接到数据库

**本地连接（在服务器上）:**

主库（读写）:
```bash
psql -h localhost -p 5432 -U postgres -d postgres
```

从库1（只读）:
```bash
psql -h localhost -p 5433 -U postgres -d postgres
```

从库2（只读）:
```bash
psql -h localhost -p 5434 -U postgres -d postgres
```

**远程连接（从本地机器连接到远程服务器）:**

主库（读写）:
```bash
psql -h <远程服务器IP> -p 5432 -U postgres -d postgres
```

从库1（只读）:
```bash
psql -h <远程服务器IP> -p 5433 -U postgres -d postgres
```

从库2（只读）:
```bash
psql -h <远程服务器IP> -p 5434 -U postgres -d postgres
```

**远程访问配置说明:**

配置已自动启用远程访问支持：
- `listen_addresses=*` 已配置，PostgreSQL 监听所有网络接口
- `pg_hba.conf` 已配置允许来自任何 IP 的连接（使用 md5 密码认证）
- 端口已映射到宿主机：5432（主库）、5433（从库1）、5434（从库2）

**重要安全提示:**
- 确保在远程服务器上配置防火墙，只允许可信 IP 访问这些端口
- 使用强密码保护数据库
- 生产环境建议使用 SSL 连接（需要额外配置）

### 测试复制

1. 在主库创建测试表：
```sql
CREATE TABLE test_replication (id SERIAL PRIMARY KEY, data TEXT);
INSERT INTO test_replication (data) VALUES ('test data');
```

2. 在从库查询（应该能看到数据）：
```sql
SELECT * FROM test_replication;
```

### 查看复制状态

在主库执行：
```sql
-- 查看复制连接状态
SELECT * FROM pg_stat_replication;

-- 查看复制槽
SELECT * FROM pg_replication_slots;
```

## 管理命令

### 停止服务
```bash
docker-compose down
```

### 停止服务并删除数据
```bash
docker-compose down -v
rm -rf data/*
```

### 查看日志
```bash
# 查看所有服务日志
docker-compose logs

# 查看主库日志
docker-compose logs master

# 查看从库日志
docker-compose logs replica1
docker-compose logs replica2
```

### 重启服务
```bash
docker-compose restart
```

## 故障排查

### 主库启动失败

如果主库容器启动失败（exit code 1），请按以下步骤排查：

1. **查看主库日志**：
```bash
docker-compose logs master
```

2. **检查环境变量**：
确保 `.env` 文件存在且包含所有必需的变量：
```bash
cat .env
```
必须包含：
- `POSTGRES_PASSWORD`
- `REPLICATOR_PASSWORD`

3. **检查数据目录权限**：
```bash
ls -la data/master
sudo chown -R 999:999 data/
```

4. **清理并重新启动**：
```bash
docker-compose down
rm -rf data/master/*
docker-compose up -d master
docker-compose logs -f master
```

5. **常见错误**：
   - **环境变量未设置**：确保 `.env` 文件存在且所有密码都已设置
   - **权限问题**：确保数据目录权限正确（postgres 用户，uid 999）
   - **端口占用**：检查端口 5432 是否被占用：`sudo lsof -i :5432`

### 从库无法连接主库

1. 检查主库是否正常运行：
```bash
docker-compose ps master
docker-compose logs master
```

2. 检查网络连接：
```bash
docker exec -it postgres-replica1 ping master
```

3. 检查主库的 pg_hba.conf 配置：
```bash
docker exec -it postgres-master cat /var/lib/postgresql/data/pgdata/pg_hba.conf | grep replication
```

4. 检查复制用户是否存在：
```bash
docker exec -it postgres-master psql -U postgres -c "\du replicator"
```

### 从库数据不同步

1. 检查复制状态：
```sql
-- 在主库执行
SELECT * FROM pg_stat_replication;
```

2. 查看从库日志：
```bash
docker-compose logs replica1
```

3. 检查从库是否处于standby模式：
```sql
-- 在从库执行
SHOW transaction_read_only;
-- 应该返回 'on'

-- 检查recovery.conf是否存在
SELECT pg_is_in_recovery();
-- 应该返回 't' (true)
```

4. 如果复制中断或从库没有正确初始化，可以重新初始化从库：
```bash
# 停止从库
docker-compose stop replica1

# 删除从库数据目录
rm -rf data/replica1/*

# 重新启动从库（会自动从主库复制数据）
docker-compose up -d replica1

# 查看日志确认初始化过程
docker-compose logs -f replica1
```

5. 如果从库显示 `transaction_read_only = off`，说明从库没有正确进入standby模式，需要重新初始化。

### 权限问题

如果遇到数据目录权限问题：
```bash
sudo chown -R 999:999 data/
```

### 端口冲突

如果端口 5432、5433、5434 已被占用，可以修改 `docker-compose.yml` 中的端口映射。

## 生产环境建议

1. **密码安全**: 使用强密码，不要使用默认密码
2. **SSL 连接**: 在生产环境中启用 SSL 连接
3. **备份策略**: 定期备份主库数据
4. **监控**: 设置监控告警，监控复制延迟
5. **资源限制**: 根据实际需求设置 CPU 和内存限制
6. **日志管理**: 配置日志轮转和集中日志管理

## 远程服务器部署注意事项

### 防火墙配置

在 Ubuntu 服务器上，需要开放相应端口：

```bash
# 使用 ufw（Ubuntu 防火墙）
sudo ufw allow 5432/tcp  # 主库
sudo ufw allow 5433/tcp  # 从库1
sudo ufw allow 5434/tcp  # 从库2

# 或者只允许特定 IP 访问（更安全）
sudo ufw allow from <你的IP地址> to any port 5432
sudo ufw allow from <你的IP地址> to any port 5433
sudo ufw allow from <你的IP地址> to any port 5434
```

### 云服务器安全组配置

如果使用云服务器（如阿里云、腾讯云、AWS 等），还需要在云平台的安全组中开放相应端口。

### 验证远程连接

在本地机器上测试连接：

```bash
# 测试主库连接
psql -h <服务器IP> -p 5432 -U postgres -d postgres -c "SELECT version();"

# 测试从库连接
psql -h <服务器IP> -p 5433 -U postgres -d postgres -c "SELECT version();"
```

## 注意事项

- 首次启动从库需要等待主库完全初始化（约 30-60 秒）
- 从库是只读的，不能执行写操作
- 如果主库崩溃，需要手动提升一个从库为主库
- 数据目录包含敏感信息，请妥善保管
- **远程访问已启用，请确保配置防火墙和强密码以保护数据库安全**

## 技术支持

如有问题，请查看：
- PostgreSQL 官方文档: https://www.postgresql.org/docs/10/high-availability.html
- Docker Compose 文档: https://docs.docker.com/compose/
