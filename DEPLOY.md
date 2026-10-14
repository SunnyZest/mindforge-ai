# MindForge AI 生产部署手册

> 目标环境：**阿里云 ECS · 2核2G · Ubuntu 22.04/24.04 · 香港/海外地域（免备案）**
> 架构：单机承载全部 —— Nginx + 两个前端静态站 + Java + Python + MySQL + Redis
> 本手册配套的部署文件（均已在本仓库/兄弟仓库中备好）：
>
> | 文件 | 位置 | 用途 |
> | :--- | :--- | :--- |
> | `src/main/resources/application-prod.yml` | 本仓库 | 生产 profile（占位符，可提交） |
> | `deploy/mindforge-java.service` | 本仓库 | Java systemd 单元 |
> | `deploy/mindforge-java.env.example` | 本仓库 | 密钥环境变量模板 |
> | `deploy/mindforge-ai-service.service` | Python 仓库 | Python systemd 单元 |
> | `deploy/env.production.example` | Python 仓库 | Python .env 模板 |
> | `deploy/nginx.prod.conf` | 前端仓库 | Nginx 站点配置（已修复 internal 暴露） |

## 版本对应表

**本地构建侧**（在你 Windows 机器上跑，构建完才上传）：

| 工具 | 要求 | 核对命令 |
| :--- | :--- | :--- |
| JDK | 21（mvnw 打包用） | `java -version` |
| Node | ≥20（前端 engines 声明） | `node -v` |
| pnpm | 12.10.1（package.json packageManager 锁定） | `pnpm -v`（不符就 `corepack use pnpm@12.10.1`） |

**服务器侧**（apt/uv 安装，Ubuntu 22.04）：

| 组件 | 版本 | 说明 |
| :--- | :--- | :--- |
| OpenJDK | 21.x（apt） | 与 pom.xml 的 java.version=21 对应；找不到包用 Temurin |
| Python | 3.12.x（uv 直装） | 刻意不走 apt——22.04 系统自带 3.10 不达标 |
| Python 依赖 | uv.lock 精确锁定 | 与开发环境逐包一致 |
| MySQL | 8.0.x（apt 默认） | schema 兼容 |
| Redis | 6.0.x（apt 默认） | 客户端 Lettuce 兼容 ≥5 |
| Nginx | 1.18.x（apt 默认） | conf 全部标准指令 |

apt 装的都是发行版默认版本即可，**不要**手动升级它们；只有 JDK 21 和 Python 3.12 是硬性要求，手册的安装方式已保证。

## 架构总览

```
浏览器 ──HTTPS 443──▶ Nginx（宿主机）
                      ├─ /              → /var/www/mindforge/user   用户端静态
                      ├─ /admin/        → /var/www/mindforge/admin  管理端静态
                      ├─ /api/internal/ → 403（内部接口禁止公网访问）
                      └─ /api/          → 127.0.0.1:8080  Java（systemd）
                                            │ 127.0.0.1:8000  Python（systemd）
                                            │ （Java↔Python 走回环，令牌不出公网）
                      MySQL 127.0.0.1:3306 / Redis 127.0.0.1:6379（只监听本机）
                      文件 → 阿里云 OSS（服务器在香港则选香港 bucket 延迟最低）
```

---

## 第 0 步 · 阿里云控制台

1. **安全组**只放行三条入方向规则：
   - `22/tcp`（SSH，建议限制为你的常用 IP 段）
   - `80/tcp`、`443/tcp`（网站）
   - **不要**放行 8080 / 8000 / 3306 / 6379 —— 它们只服务本机。
2. 确认系统镜像为 Ubuntu 22.04 或 24.04，记下公网 IP（下文记作 `SERVER_IP`）。
3. 域名解析：在域名服务商加一条 A 记录指向 `SERVER_IP`（香港地域免备案，解析生效即可用）。

---

## 第 1 步 · 服务器初始化

```bash
ssh root@SERVER_IP

# 时区（JDBC URL 里 serverTimezone=Asia/Shanghai，系统时区保持一致避免时间错乱）
timedatectl set-timezone Asia/Shanghai

# 基础工具 + JDK 21（Ubuntu 24.04 自带；22.04 若提示找不到包，
# 先 add-apt-repository universe && apt update 再试；仍不行用 Temurin：
# https://adoptium.net/installation.html 的 apt 源）
apt update && apt install -y curl wget tar
apt install -y openjdk-21-jre-headless
java -version   # 应显示 21.x

# ★ 2核2G 必配 swap：MySQL/Java/Python 三者内存峰值合计超过物理内存，
#   没 swap 的话 OOM killer 会随机杀进程（通常是先杀最大的 Java）
fallocate -l 2G /swapfile && chmod 600 /swapfile
mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
echo 'vm.swappiness=10' > /etc/sysctl.d/99-mindforge.conf && sysctl -p /etc/sysctl.d/99-mindforge.conf

# 专用运行用户 + 目录规划
useradd -r -m -d /opt/mindforge -s /usr/sbin/nologin mindforge
mkdir -p /opt/mindforge /var/www/mindforge
```

> 说明：`mindforge` 用户没有 shell，服务进程以它运行；部署操作用 root 完成后 `chown` 交付。

---

## 第 2 步 · MySQL 与 Redis

### 2.1 MySQL

```bash
apt install -y mysql-server

# ★ 2G 内存机器的 MySQL 调优（默认配置会吃掉 400M+）
cat > /etc/mysql/mysql.conf.d/99-mindforge.cnf <<'EOF'
[mysqld]
innodb_buffer_pool_size = 128M
performance_schema = OFF
max_connections = 60
EOF
systemctl restart mysql && systemctl enable mysql
```

建库、建用户（密码记下来，第 3 步要填进 env 文件）：

```bash
mysql -e "CREATE DATABASE mindforge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -e "CREATE USER 'mindforge'@'localhost' IDENTIFIED BY '这里换成一个强密码';"
mysql -e "GRANT ALL PRIVILEGES ON mindforge.* TO 'mindforge'@'localhost'; FLUSH PRIVILEGES;"
```

导入表结构（本地机器执行，schema 含全部 31 张业务表，一份就够）：

```bash
scp D:/java_IDEA/maven_code/mindforge-ai/mindforge-schema.sql root@SERVER_IP:/root/
# 然后回到服务器：
mysql mindforge < /root/mindforge-schema.sql
mysql -e "USE mindforge; SHOW TABLES;" | wc -l   # 应为 31+1
```

### 2.2 Redis

```bash
apt install -y redis-server
# 密码记下来，第 3 步要填
sed -i 's/^# requirepass foobared/requirepass 这里换成一个强密码/' /etc/redis/redis.conf
cat >> /etc/redis/redis.conf <<'EOF'
maxmemory 128mb
maxmemory-policy allkeys-lru
EOF
systemctl restart redis-server && systemctl enable redis-server
redis-cli ping   # 应报 NOAUTH（说明密码生效了），带密码 ping：redis-cli -a '密码' ping → PONG
```

> 数据只有限流计数和登录失败计数，LRU 淘汰可接受；128MB 上限保护 2G 内存。

---

## 第 3 步 · 部署 Java 后端

**本地打包**（Windows，项目根目录）：

```bash
cd D:/java_IDEA/maven_code/mindforge-ai
./mvnw.cmd clean package -DskipTests
# 产物：target/mindforge-ai-0.0.1-SNAPSHOT.jar
```

**上传并安装**（本地 Git Bash 执行）：

```bash
scp target/mindforge-ai-0.0.1-SNAPSHOT.jar root@SERVER_IP:/opt/mindforge/mindforge-ai.jar
scp deploy/mindforge-java.service root@SERVER_IP:/etc/systemd/system/
scp deploy/mindforge-java.env.example root@SERVER_IP:/opt/mindforge/mindforge.env
```

**服务器上配置密钥**（参照文件内注释逐项填写）：

```bash
cd /opt/mindforge
# 三个密钥的生成命令（生成后填入对应占位）：
openssl rand -base64 32   # → MINDFORGE_JWT_SECRET
openssl rand -base64 24   # → MINDFORGE_SERVICE_TOKEN（记下来，第 4 步 Python 侧要用同一个）
openssl rand -base64 32   # → MINDFORGE_AI_CONFIG_SECRET_KEY
vim mindforge.env         # 填完所有 <...> 占位：数据库/Redis 密码、OSS 四项、三个密钥
chown mindforge:mindforge mindforge.env && chmod 600 mindforge.env
chown mindforge:mindforge mindforge-ai.jar

systemctl daemon-reload
systemctl enable --now mindforge-java
sleep 25 && systemctl status mindforge-java --no-pager
curl -s http://127.0.0.1:8080/api/v3/api-docs | head -c 200   # 返回 JSON 即正常
```

> ⚠️ 生产环境的 AI 模型配置（管理端 → AI 配置）要用**生产**的 `MINDFORGE_AI_CONFIG_SECRET_KEY` 重新录入一遍模型 API Key —— dev 加密的密文在生产解不开。

---

## 第 4 步 · 部署 Python AI 服务

**本地打包上传**（Git Bash，排除 .venv / data / 缓存——data 里的 chroma 是本地测试数据，生产重新建库）：

```bash
cd /d/pythoncode/mindforge-ai-service
tar --exclude=.venv --exclude=__pycache__ --exclude='*/__pycache__' \
    --exclude=data --exclude=tests -czf /tmp/ai-service.tgz .
scp /tmp/ai-service.tgz root@SERVER_IP:/root/
```

**服务器上安装**（uv 会自动装 Python 3.12，不依赖系统版本）：

```bash
mkdir -p /opt/mindforge/ai-service
tar -xzf /root/ai-service.tgz -C /opt/mindforge/ai-service
curl -LsSf https://astral.sh/uv/install.sh | sh && export PATH="$HOME/.local/bin:$PATH"
cd /opt/mindforge/ai-service
uv python install 3.12
uv sync                       # 按锁文件装依赖，生成 .venv

# 配置 .env
cp deploy/env.production.example .env
vim .env
#   JAVA_SERVICE_TOKEN ← 第 3 步生成的 MINDFORGE_SERVICE_TOKEN（必须完全一致）
#   DASHSCOPE_API_KEY  ← 你的 DashScope 密钥
chown -R mindforge:mindforge /opt/mindforge/ai-service
chmod 600 .env

cp deploy/mindforge-ai-service.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now mindforge-ai-service
sleep 8 && systemctl status mindforge-ai-service --no-pager
curl -s http://127.0.0.1:8000/health        # 应返回健康 JSON
```

**联调验证**（Java ↔ Python 互认）：

```bash
# Java 侧日志应出现轮询正常 / Python 侧 health/java 连通测试
journalctl -u mindforge-ai-service -n 30 --no-pager | grep -i "java"
```

---

## 第 5 步 · 前端与 Nginx + HTTPS

**本地构建**（前端仓库根目录）：

```bash
cd D:/Vue/mindforge
pnpm install
pnpm --filter @mindforge/user build     # → apps/user/dist
pnpm --filter @mindforge/admin build    # → apps/admin/dist
```

**上传**（Git Bash）：

```bash
scp -r apps/user/dist  root@SERVER_IP:/var/www/mindforge/user
scp -r apps/admin/dist root@SERVER_IP:/var/www/mindforge/admin
scp deploy/nginx.prod.conf root@SERVER_IP:/etc/nginx/conf.d/mindforge.conf
```

**服务器上启用**：

```bash
apt install -y nginx
rm -f /etc/nginx/sites-enabled/default      # 移除默认站点（同样占 80 端口）
sed -i 's/mindforge.example.com/你的真实域名/' /etc/nginx/conf.d/mindforge.conf
nginx -t && systemctl reload nginx

# HTTPS（香港地域免备案，域名解析生效即可签发；certbot 会自动改写配置加 443）
apt install -y certbot python3-certbot-nginx
certbot --nginx -d 你的真实域名
```

---

## 第 6 步 · 验收清单

按顺序过一遍，任何一步不过就先解决再继续：

| # | 检查项 | 命令 / 操作 | 预期 |
| :- | :--- | :--- | :--- |
| 1 | 两个服务活着 | `systemctl status mindforge-java mindforge-ai-service` | active (running) |
| 2 | 内存没爆 | `free -h` | swap 用量 < 500M |
| 3 | Python 健康 | `curl -s 127.0.0.1:8000/health` | JSON |
| 4 | Java 健康 | `curl -s 127.0.0.1:8080/api/v3/api-docs` | JSON |
| 5 | 用户端打开 | 浏览器 `https://域名` | 登录页，HTTPS 锁标 |
| 6 | 注册/登录 | 注册新用户并登录 | 成功拿到 token |
| 7 | 全链路 AI | 上传简历 → 解析 → 优化 | 解析成功（打通 Java↔Python↔DashScope↔OSS） |
| 8 | RAG 对话 | 建知识库 → 传文档 → 对话 | 流式输出、来源引用正常 |
| 9 | 模拟面试 | 创建 → 出题 → 作答 → 报告 | 全流程成功 |
| 10 | 管理端 | `https://域名/admin/` | 管理员可登录 |
| 11 | **内部接口封死** | `curl -i https://域名/api/internal/documents/claim` | **403** |
| 12 | 数据库直连封死 | 外部 `mysql -h SERVER_IP` | 连不上 |

---

## 2核2G 内存账本

| 进程 | 峰值 | 控制手段 |
| :--- | ---: | :--- |
| OS + Nginx + 杂项 | ~250M | — |
| MySQL | ~350M | buffer_pool 128M、performance_schema OFF |
| Redis | ~60M | maxmemory 128mb |
| Java | ~1000M | -Xmx640m + Metaspace 192m + MemoryMax 1100M |
| Python | ~600M | 单 worker + MemoryMax 800M |
| **合计** | **~2.2G** | 超出物理内存的部分落 swap（2G） |

- 三个进程都有 systemd `MemoryMax` 硬顶，最坏情况是「某服务被掐掉自动重启」，而不是整机 OOM 死锁。
- 日常观察 `free -h`：**swap 持续 > 1G 说明该升配了**（升到 2核4G 后：去掉本手册的 MySQL 调优、把 Java 的 -Xmx 提到 1g、MemoryMax 相应放开）。
- 并发上量（日活几百以上）或简历解析频繁超时时，优先把 MySQL 迁到 RDS，内存立刻松 350M。

## 常见问题速查

| 症状 | 原因 | 处理 |
| :--- | :--- | :--- |
| Python 日志疯狂报 401 | 两边令牌不一致 | 对齐 `.env` 的 `JAVA_SERVICE_TOKEN` 与 `mindforge.env` 的 `MINDFORGE_SERVICE_TOKEN`，重启两者 |
| 登录失败一次就把所有人锁死 | 限流按 IP 计数时全成了 127.0.0.1 | prod 配置已设 `trust-forwarded-headers: true`；确认 Nginx 转发头三行还在 |
| 上传 30~50MB 文件报 413 | Nginx 拦的 | `client_max_body_size` 已改 50m；确认用的是 nginx.prod.conf |
| 对话不出字 / 一次性全出 | SSE 被缓冲 | `proxy_buffering off` 已配；别自己加 `gzip` 于 `text/event-stream` |
| 简历解析报「AI 服务超时」 | 超时链路被压缩 | 保持 Java read-timeout 660s、Nginx proxy_read_timeout 660s，别改小 |
| Java 启动即退，日志有 `Could not resolve placeholder` | env 文件缺占位 | 对照 `mindforge.env.example` 逐项检查（缺密钥时 fail-fast 是刻意的） |
| 解析文档后知识库检索为空 | chroma data 目录权限 | `chown -R mindforge:mindforge /opt/mindforge/ai-service/data` |

## 后续更新

- **Java**：本地重新 package → scp 覆盖 jar → `systemctl restart mindforge-java`（约 30 秒中断）
- **Python**：重新打 tar 上传 → `uv sync` → `systemctl restart mindforge-ai-service`
- **前端**：重新 build → scp 覆盖 dist → 无需重启 Nginx（入口 HTML 已禁缓存，立即生效）
- **数据库变更**：有新增 SQL 时按增量脚本执行（参考仓库根目录的 `mindforge-增量变更.sql` 模式）
