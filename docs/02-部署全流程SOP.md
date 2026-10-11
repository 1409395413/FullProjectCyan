# 裸机部署标准作业流程（SOP）

> 这套流程是**今天在 8.138.93.141 上真跑通、并踩完所有坑之后**整理的正确顺序。
> 换台新服务器照着这份从头做一遍即可，不需要再看别的文档。
>
> 每个阶段都给了：**做什么 / 为什么这个顺序 / 怎么算做对 / 做错了什么现象**。

---

## 零、总览：五个阶段，顺序不能乱

```
A 本地准备      →  B 服务器初始化  →  C 应用部署  →  D 验证  →  E 域名与 HTTPS
（打包）           （装依赖+调优）     （建库+配置+启动）  （冒烟）     （备案后）
```

**为什么这个顺序**（依赖关系）：

| 顺序约束 | 原因 |
|---|---|
| A 必须在 B 之前 | `setup-ubuntu.sh` 会校验 `app.jar` 和 `web/` 存在，**必须先解压再装依赖** |
| 建库（C1）必须在启动（C4）之前 | 后端 Hibernate 需要库已存在 |
| 填 `portfolio.env`（C2）必须在 `--apply-secrets`（C3）之前 | 该脚本从 env 里读 Redis 密码注入 redis.conf |
| `--apply-secrets`（C3）必须在启动（C4）之前 | 否则 Redis 无密码，后端连不上 |
| **HTTPS（E）必须在备案通过之后** | 未备案时 80/443 被阿里云拦截，certbot 的 HTTP-01 校验同样过不去 |

---

## A. 本地准备（Windows）

### A1. 停掉本地后端

```powershell
Get-Process java | Stop-Process -Force
```

> **为什么**：java 进程占用 `server/target/portfolio-server.jar`，Windows 无法删除被占用的文件，
> `mvn clean` 第一步就 BUILD FAILURE。
> 注意：`toolchain/stop-backend.ps1` 用 `$_.Path -like '*jdk-17*'` 过滤，实测匹配不上，按 PID 杀最稳。

### A2. 一键打包

```powershell
cd FullProjectCyan
.\pack-deploy.ps1
```

产出 `deploy.tar.gz`（约 55.5 MB，23 个条目）。

**验收**：
```powershell
tar.exe -tzf deploy.tar.gz     # 必须是正斜杠：./web/index.html、./deploy/nginx.conf
```
> 绝不能出现 `deploy\nginx.conf` 这种反斜杠 —— 见「坑 #4」。

---

## B. 服务器初始化

### B1. 放行端口（阿里云控制台 → 防火墙）

| 端口 | 用途 |
|---|---|
| 22 | SSH |
| 80 | HTTP |
| 443 | HTTPS |

**怎么判断放行成功了**：从本地 TCP 探测——
- `ConnectionRefused` = **已放行**（包到了主机，内核回 RST，只是没进程监听）← 正常
- 超时无响应 = 没放行，去控制台加规则

### B2. 登录

密码登录通常是禁用的，用密钥对：

```bash
ssh -i Cyan.pem root@8.138.93.141
```

### B3. 上传并解压

```bash
scp -i Cyan.pem deploy.tar.gz root@8.138.93.141:/opt
sudo mkdir -p /opt/portfolio
sudo tar -xzf /opt/deploy.tar.gz -C /opt/portfolio
ls /opt/portfolio      # app.jar  web/  deploy/  db/  config/  scripts/  setup-ubuntu.sh
```

### B4. 安装依赖 + 自动调优

```bash
cd /opt/portfolio && bash setup-ubuntu.sh --install-deps
```

这一条自动完成（含 2 GiB 机型专属调优）：
- 装 `openjdk-17-jre-headless` / `mysql-server` / `redis-server` / `nginx`
- **创建 2 GiB swap** 并写 fstab + `vm.swappiness=10`
- **MySQL 小内存配置** `/etc/mysql/conf.d/portfolio-small.cnf`
  （`innodb_buffer_pool_size=192M`、`max_connections=60`）
- 建系统用户 `portfolio`、`/var/lib/portfolio/upload`、`/var/log/portfolio`
- 注册 systemd 单元、安装 nginx 配置（**故意不启动后端**，因为密钥还没填）

**另外务必装 ffmpeg**（可选但强烈建议）：

```bash
apt-get install -y ffmpeg
```

后端上传视频时会用它做 faststart（把 moov 索引挪到文件头）并用 ffprobe 读取宽高。
**不装也能跑**（代码会检测并跳过），但那样上传的 mp4 若是 `mdat -> moov` 顺序，
浏览器要下完整个文件才有尺寸和首帧，表现为：竖屏视频被裁成横屏、卡片位置一片灰。

**验收**：`swapon --show` 应看到 2 GiB。

> ⚠️ **建完目录必须补一条 `chmod`，否则素材全部 404**：
>
> ```bash
> chmod 755 /var/lib/portfolio          # 父目录！建目录默认 750，nginx(www-data) 进不去
> usermod -aG portfolio www-data        # 纵深防御：将来目录被改回 750 也仍能读
> ```
>
> 少了这步，上传能成功落盘、数据库也有记录，但 nginx `stat()` 报
> `Permission denied`，而 `try_files =404` 会把它显示成 **404 Not Found**，极具迷惑性。
> 详见《03-运维与故障排查手册》"素材 404"一节。

---

## C. 应用部署

### C1. 建库导表

```bash
sudo mysql -e "CREATE DATABASE IF NOT EXISTS Cyan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sudo mysql Cyan < /opt/portfolio/db/schema.sql
```

> Ubuntu 上 MySQL root 走 `auth_socket`，直接 `sudo mysql` 即可，不用密码。
> 库名大小写敏感，`Cyan` 不要写成 `cyan`。

### C2. 生成密钥并填 env

```bash
cd /opt/portfolio/deploy
cp env.production.example portfolio.env && chmod 600 portfolio.env

MYSQL_PW=$(openssl rand -hex 16); REDIS_PW=$(openssl rand -hex 16)
JWT=$(openssl rand -hex 32);      ADMIN_PW=$(openssl rand -hex 8)

sudo mysql -e "CREATE USER IF NOT EXISTS 'Cyan'@'%' IDENTIFIED BY '$MYSQL_PW';"
sudo mysql -e "GRANT ALL PRIVILEGES ON CyanWorks.* TO 'Cyan'@'%'; FLUSH PRIVILEGES;"

sed -i "s|^MYSQL_HOST=.*|MYSQL_HOST=127.0.0.1|"            portfolio.env
sed -i "s|^MYSQL_PORT=.*|MYSQL_PORT=3306|"                 portfolio.env
sed -i "s|^MYSQL_DB=.*|MYSQL_DB=CyanWorks|"                     portfolio.env
sed -i "s|^MYSQL_USER=.*|MYSQL_USER=Cyan|"               portfolio.env
sed -i "s|^MYSQL_PASSWORD=.*|MYSQL_PASSWORD=$MYSQL_PW|"    portfolio.env
sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$REDIS_PW|"    portfolio.env
sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$JWT|"                 portfolio.env
sed -i "s|^ADMIN_USERNAME=.*|ADMIN_USERNAME=Cyan|"         portfolio.env
sed -i "s|^ADMIN_PASSWORD=.*|ADMIN_PASSWORD=$ADMIN_PW|"    portfolio.env
sed -i "s|^APP_UPLOAD_DIR=.*|APP_UPLOAD_DIR=/var/lib/portfolio/upload|" portfolio.env
sed -i "s|^APP_CORS_ORIGINS=.*|APP_CORS_ORIGINS=http://8.138.93.141|"   portfolio.env

echo "后台管理员：Cyan / $ADMIN_PW"     # ★ 记下来
```

> **`MYSQL_PORT=3306` 必须显式写**：`application.yml` 的默认兜底是 3307，漏了就连不上库。
> **`ADMIN_PASSWORD` 只在管理员表为空时播种一次**，之后改它不生效——改密走后台或 `/api/auth/password`。

### C3. 应用密钥（注入 Redis 密码）

```bash
cd /opt/portfolio && bash setup-ubuntu.sh --apply-secrets
```

备份 `redis.conf` → 用裸机版覆盖 → 注入密码 → 重启 → 校验 `PONG`。

### C4. 启动

```bash
systemctl enable --now portfolio nginx mysql redis-server
systemctl is-active portfolio nginx mysql redis-server
```

---

## D. 验证

```bash
curl -s http://127.0.0.1:8080/actuator/health   # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/      # 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/login  # 200
curl -s http://127.0.0.1/api/content | head -c 120               # 返回 JSON
free -h                                          # Swap 应有 2.0G
ss -lntp | grep -E ':80|:3306|:6379|:8080'       # 3306/6379 只绑 127.0.0.1
```

**从本地**（不是服务器上）再测一次公网入口：
```
http://服务器IP/        → 200
http://服务器IP/login   → 200
```

### D+. 权限收紧（建议做）

解压出来的 `/opt/portfolio` 默认是 777、`app.jar` 是 666，任何本地用户都能改你的 jar：

```bash
chown -R portfolio:portfolio /opt/portfolio
find /opt/portfolio -type d -exec chmod 755 {} +
find /opt/portfolio -type f -exec chmod 644 {} +
chmod 600 /opt/portfolio/deploy/portfolio.env
systemctl restart portfolio && curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/
```

---

## E. 域名与 HTTPS（备案通过后）

1. **补根域名解析**：`cyaniam.online` 主机记录 `@` → A → `8.138.93.141`
2. **验证**：`nslookup cyaniam.online`、`curl -I http://cyaniam.online` 应为 200 而不是备案拦截页
3. **开 HTTPS**：`certbot --nginx -d cyaniam.online -d www.cyaniam.online --redirect`
4. **改 CORS**：`APP_CORS_ORIGINS=https://cyaniam.online,https://www.cyaniam.online` + 重启后端
5. **挂备案号到页脚**（管局硬性要求）

---

## ⚠️ 今天真机踩到的 6 个坑（每个都是致命的）

### 坑 #1：Redis 起不来 —— `bind 127.0.0.1 -::1`

**现象**：`--apply-secrets` 卡住，Redis 启动失败。
**根因**：`Could not create server TCP listening socket -::1:6379`。
`-::1` 语义是"可选 IPv6"，但**Redis 6.0 在没有 IPv6 的机器上不会降级跳过，直接退出**。
**修复**：`deploy/redis-ubuntu.conf` 改成 `bind 127.0.0.1`。

### 坑 #2：Docker 版 redis.conf 不能用于裸机

**现象**：Redis 起不来（`dir /data` 不存在）。
**根因**：Docker 版配置里 `dir /data` 是容器路径，且故意不含 `requirepass`（靠命令行注入）。
**修复**：必须走 `--apply-secrets`，用裸机版配置（含 `daemonize no` / `supervised systemd`，
因为 Ubuntu 的 `redis.service` 是 `Type=notify`，缺 `supervised` 会启动超时）。

### 坑 #3：后端无限重启 + YAML 解析错误

**现象**：`expected <block end>, but found '<block sequence start>'`，systemd 反复重启。
**根因**：两个叠加——
1. **Spring Boot 会加载 `file:./config/`**，而 `portfolio.service` 的 `WorkingDirectory=/opt/portfolio`，
   所以 `/opt/portfolio/config/application.yml` 会被加载，**且优先级高于 jar 内配置**；
2. 打包脚本的"脱敏"用 `Get-Content`（按 GBK 解 UTF-8）+ `Set-Content`，中文注释变乱码并吞掉换行，
   把 `upload-dir:` / `upload-allow-mime:` 合并进了注释行。

**修复**：
- 打包时参考配置改名为 **`config/application.yml.example`**（不被 Spring 扫描）
- 脱敏改用 `[System.IO.File]::ReadAllText/WriteAllText` 显式 UTF-8 无 BOM

### 坑 #4：zip 在 Linux 上解压丢掉整个目录结构 ★ 最隐蔽

**现象**：服务器上解压后得到一堆扁平文件，名字叫 `deploy\nginx.conf`、`web\index.html`。
**根因**：Windows 的 `Compress-Archive` 在 zip 里写的是**反斜杠**路径；
Linux 上反斜杠是合法文件名字符，于是 `\` 不建目录，只当普通字符。
**为什么本地测不出来**：Windows 版 Python 的 `zipfile` 读的时候会把 `\` 规范化成 `/`，
所以在 Windows 上验证"永远通过"。
**修复**：改用 `tar.exe -czf` 打 `deploy.tar.gz`（tar 写正斜杠）。
**教训**：验证压缩包内容要看原始字节，或**到 Linux 上看**。

### 坑 #5：2 GiB 内存会被 OOM Killer 随机杀进程

**现象**：后端突然连不上库（MySQL 被杀了）。
**根因**：MySQL 8 + JVM + Redis + Nginx 挤在 2 GiB 里，没有 swap 时内核 OOM 随机挑进程杀。
**修复**（已写进 `--install-deps`）：
- 建 2 GiB swap + `vm.swappiness=10`
- MySQL `innodb_buffer_pool_size=192M` / `max_connections=60`
- systemd `MemoryMax=1024M`（JVM 失控只杀后端，不连累 MySQL）
- systemd 依赖写 **Ubuntu 服务名**：`mysql.service` / `redis-server.service`
  （写成 `mysqld.service` / `redis.service` 时 systemd 会忽略依赖，后端在库就绪前就启动）

### 坑 #6：素材 404 —— 换设备/换目录就丢

**现象**：`http://8.138.93.141` 前台图片视频全裂。
**根因**：`APP_UPLOAD_DIR` 写成相对路径 `./data/upload`，相对 java 进程工作目录解析；
重新 clone 到新目录后，素材还在旧目录里。
**修复**：`APP_UPLOAD_DIR` 用**绝对路径**且**在项目目录之外**（`/var/lib/portfolio/upload`），
并与 nginx `location ^~ /media/ { alias ... }` 指向同一目录。
配套：`toolchain/export-for-migration.ps1` / `import-for-migration.ps1` 做整机迁移。

---

## 一键复现：干净重装的最短命令序列

```bash
# 服务器上，全新机器
tar -xzf /opt/deploy.tar.gz -C /opt/portfolio
cd /opt/portfolio && bash setup-ubuntu.sh --install-deps
sudo mysql -e "CREATE DATABASE IF NOT EXISTS Cyan CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
sudo mysql Cyan < /opt/portfolio/db/schema.sql
# → 做 C2 填 env（见上）
cd /opt/portfolio && bash setup-ubuntu.sh --apply-secrets
systemctl enable --now portfolio nginx mysql redis-server
# → 做 D 验证 + D+ 权限收紧
```

---

## 时间参考（今天实测）

| 环节 | 耗时 |
|---|---|
| `pack-deploy.ps1`（含前端 build + 后端 package） | 约 3–5 分钟 |
| 上传 55 MB 到服务器 | < 1 分钟 |
| `--install-deps` | 约 3–5 分钟 |
| 建库 + 填 env + `--apply-secrets` | 约 2 分钟 |
| 验证 | 1 分钟 |

**总计约 15 分钟**（不含备案等待）。
