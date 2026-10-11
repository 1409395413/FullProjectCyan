# 本地工具链（Windows）

安装位置统一在 `%USERPROFILE%\.toolchain`，**不写 Program Files、不需要管理员权限**，不污染系统环境。

## 一次装好

```powershell
cd C:\Users\Administrator\WorkBuddy\2026-10-07-13-35-31
powershell -ExecutionPolicy Bypass -File .\toolchain\setup.ps1
```

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | Temurin 17.0.20 (LTS) | Java 后端运行时 / 编译 |
| Maven | 3.9.11 | Java 构建，已配国内镜像 |
| MySQL | 8.0.40（便携版） | **主存储**：内容、管理员、素材元数据、审计日志 |
| Redis | 5.0.14（Windows 移植版） | 可选：缓存、登录失败锁定、接口限流、会话黑名单 |
| Node | 22 LTS | Vue 前端（若本机已有则跳过） |

MySQL 单独安装（约 243MB，若已装官方 MySQL 可跳过）：

```powershell
.\toolchain\setup-mysql.ps1    # 下载 + 解压
.\toolchain\mysql-start.ps1    # 首次自动初始化数据目录；之后纯启动
.\toolchain\mysql-init-db.ps1  # 建库 Cyan + 应用账号 Cyan + 灌 schema.sql
```

本机用 **3307 端口**，和已装的 MySQL 不冲突；root 初始空密码，第一次进去记得改（`ALTER USER 'root'@'localhost' IDENTIFIED BY '...'`）。

装完**重开一个终端**，`java -version` / `mvn -v` / `redis-cli` 即可用。旧终端请先执行：

```powershell
. .\toolchain\env.ps1        # PowerShell，注意前面的点
call toolchain\env.bat       # CMD
```

## 日常使用

```powershell
.\toolchain\mysql-start.ps1    # 起 MySQL（3307）
.\toolchain\mysql-stop.ps1     # 停（mysqladmin shutdown）
.\toolchain\redis-start.ps1    # 起 Redis（6379，带密码 + AOF）
.\toolchain\redis-stop.ps1     # 停，停之前先 SAVE
.\toolchain\check.ps1          # 体检：JDK/Maven/Node + MySQL/Redis 状态
.\toolchain\start-dev.ps1      # 一键起：MySQL + Redis + Java 后端 + Vue 前端
```

## 为什么是 MySQL 主 + Redis 辅

| 数据 | 放哪 | 理由 |
|---|---|---|
| 站点内容 JSON、版本快照 | MySQL | 要能备份、回滚、按时间查 |
| 管理员账号、会话、素材元数据 | MySQL | 关系清晰，密码哈希/TOTP 都要持久 |
| 审计日志 | MySQL | 关键日志不能丢，还得能按人/按时间筛 |
| 内容缓存 | Redis | 丢了自动从 MySQL 重建 |
| 登录失败计数、接口限流、token 黑名单 | Redis | 天然带 TTL，最适合临时计数 |

Redis 挂了不影响站点可写，MySQL 挂了才会真出问题——所以 MySQL 才是兜底那一层。

## 关于 Redis 里的配置

模板 `redis.local.conf` 里两个占位符 `__DATA_DIR__` / `__PASSWORD__` 由脚本替换成实际值后生成 `redis.local.generated.conf`（在 `.toolchain` 根目录，别手改）。已实测生效的策略：

- **必须密码**：无密码访问返回 `NOAUTH`
- **只监听 127.0.0.1**：外网摸不到本机 6379
- **AOF 每秒落盘**：崩溃最多丢 1 秒写入
- **maxmemory-policy noeviction**：内存满也不淘汰内容 key
- **FLUSHALL / FLUSHDB / SHUTDOWN / DEBUG 全部禁用**
- **CONFIG / KEYS 改名**成随机串，防扫站的人一键改配置或遍历 key

密码优先级：`$env:REDIS_PASSWORD` → 仓库根目录 `.env` 的 `REDIS_PASSWORD` → **无默认值**。

> 脚本不再内置任何默认口令：未配置时 `redis-start.ps1` 直接报错退出（绝不启动无口令实例），
> `check.ps1` / `redis-stop.ps1` 提示后跳过。仓库是公开的，口令只能来自环境变量或 `.env`。
> 先 `copy .env.example .env` 再填自己的密码。

> 生产请用 `deploy/redis.conf`（Linux 版），密码从环境变量注入并 ≥32 位随机串。
