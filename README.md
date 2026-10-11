# Cyan · Studio 个人作品集站点

> 📚 **完整文档在 [`docs/`](./docs/README.md)**：  
> 架构全景 · 部署 SOP · 运维排查 · 上线验收与合规。  
> 第一次接手或要重装服务器，请从 `docs/01` 顺着读。

摄影 / 剪辑 / 直播搭建 / AI 视频四大板块 + 个人简介。

**Vue 3 + TypeScript 前台 · Java 17 / Spring Boot 3 后端 · MySQL 8 主存储 · Redis 7 缓存 · Nginx 发布**。  
站点所有文案、模块、图片视频都在后台可视化维护，**改内容不需要重新部署**。

---

## 一、核心设计：代码归代码，内容归内容

这是整个项目最重要的一条原则，也是"推 GitHub 部署"能成立的前提：

| 东西                 | 放在哪                                   | 会不会被重新部署覆盖  |
| ------------------ | ------------------------------------- | ----------- |
| 代码（页面、样式、后端逻辑）     | GitHub 仓库                             | 会 —— 这是你期望的 |
| 站点内容（文案 / 模块 / 顺序） | MySQL `site_content.payload`（一行 JSON） | **不会**      |
| 图片 / 视频素材          | Docker 卷 `upload-data`                | **不会**      |
| 管理员账号              | MySQL `admin_user`（BCrypt 存储）         | **不会**      |

带来的好处：

- **加模块不用改代码**：payload 是"不透明 JSON"，后端不理解模块语义，只做存取与安全校验。以后想加"播客""客户名单"任何类型，前端加一个渲染组件即可，**不用改 Java、不用改表、不用重新建表**。
- **改内容不用重新部署**：后台保存 → 写库 → 清缓存 → 前台下次刷新就是新的。
- **换代码不丢内容**：`docker compose up -d --build` 重建的是镜像，卷里的数据原样保留。
- **仓库里没有秘密也没有大文件**：`.env`、上传目录、构建产物全部 gitignore。

---

## 二、目录结构

```
web/                     Vue 3 + Vite + TS 前台
  src/moduleSchema.ts    后台表单的"字段说明书" —— 加新模块类型就改这里
  src/components/        渲染层：HeroModule / StackModule / CasesModule ...
  src/components/StackGallery.vue   卡牌叠加画廊（悬停主播放 / ✕ 收牌 / 果冻动效）
  src/views/admin/       后台：登录 + 模块增删改排序 + 素材库 + 版本回滚
  nginx.conf             生产 Nginx（反代 /api、直读 /media、边缘限流）
server/                  Spring Boot 3.2 后端（Java 17）
  src/main/resources/application.yml     全部配置，均可环境变量覆盖
  src/main/resources/db/schema.sql       MySQL 8 建表脚本
  src/main/resources/default-content.json  首次启动的种子内容（仅空库时写入）
deploy/                  redis.conf、HTTPS 终结示例
scripts/                 backup.sh / restore.sh（内容 + 素材备份）
toolchain/               Windows 本机工具链（JDK/Maven/MySQL/Redis 便携版，免管理员）
legacy/static-prototype/ 第一阶段的纯静态原型，仅作对照，不参与部署
```

---

## 三、部署到服务器（GitHub → 服务器）

### 0. 服务器准备

一台 Linux 主机（1C2G 起），装好 Docker 与 Compose 插件：

```bash
curl -fsSL https://get.docker.com | sh
docker compose version      # 确认有输出
```

### 1. 推到 GitHub

**方式 A · 命令行（推荐）**

```bash
git init && git add . && git commit -m "portfolio: init"
git remote add origin git@github.com:<你>/<仓库>.git
git push -u origin main
```

**方式 B · 网页拖拽上传（不想用命令行时）**

仓库根目录下已经放好了 `portfolio-deploy-package.zip`（已排除 `.env`、`node_modules`、构建产物）。  
在 GitHub 新建仓库页点 **uploading an existing file**，把压缩包里的文件**整个拖进去**即可。  
注意别连 `.env` 一起传——里面是数据库密码和 JWT 密钥。

> 推送前自查一遍，下面这些**不应该**出现在仓库里：  
> `.env`、`data/`、`.toolchain/`、`web/dist/`、`server/target/`、`node_modules/`、`*.log`  
> 它们都已在 `.gitignore` 中。`.env` 尤其重要 —— 里面有数据库密码和 JWT 密钥。

### 2. 服务器上克隆

```bash
sudo mkdir -p /opt/portfolio && cd /opt/portfolio
git clone git@github.com:<你>/<仓库>.git .
```

### 3. 生成 `.env`

```bash
cp .env.example .env
```

然后**必须**改这几项：

```bash
# 强随机密钥（三条都用这个命令生成，各生成一次）
openssl rand -base64 64
```

| 变量                                       | 说明                                                         |
| ---------------------------------------- | ---------------------------------------------------------- |
| `JWT_SECRET`                             | ≥32 字节随机串。**改它会使所有已登录会话失效**，所以只在上線前设一次                     |
| `MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` | 数据库密码，两个都要换                                                |
| `REDIS_PASSWORD`                         | Redis 密码                                                   |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD`      | **仅首次启动**用于播种管理员                                           |
| `APP_CORS_ORIGINS`                       | 填正式域名，如 `https://www.yourdomain.com`。走 Nginx 同源反代时不跨域，可留默认 |
| `HTTP_PORT`                              | 对外端口，默认 80                                                 |

> `.env` 不支持行尾注释（`# 注释` 会被当成值的一部分），改的时候注意。

### 4. 一键启动

```bash
docker compose up -d --build
docker compose ps          # 四个服务都应 Running / healthy
docker compose logs -f api # 首次启动会建表 + 播种，约 30~60 秒
```

看到 `Started PortfolioApplication` 即就绪。访问 `http://<服务器IP>` 看前台，`/login` 进后台。

### 5. 首次登录

用 `.env` 里的 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 登录。

**登录后第一件事：把 `.env` 里的 `ADMIN_PASSWORD` 删掉**（后端只在账号表为空时播种，留着也没用，删掉更干净）。之后改密码用后台的「修改密码」。

### 6. 日常改内容 —— 不需要重新部署 ✅

后台 `/admin` 里改文案、加模块、传图传视频 → 保存 → 刷新前台即可。

### 7. 只有改代码/改样式才需要重新部署

```bash
cd /opt/portfolio
git pull
docker compose up -d --build     # 内容与素材不受影响
```

### 8. HTTPS（建议）

最省事：前面挂 Caddy 自动签发证书。

```
# Caddyfile
www.yourdomain.com {
    reverse_proxy 127.0.0.1:8081
    encode gzip
}
```

把 `.env` 里 `HTTP_PORT=8081`（只对内网监听），然后 `caddy run`。  
想用 Nginx 终结 TLS 的话看 `deploy/nginx-tls.conf.example`。

### 9. 换服务器也不会踩坑的路径清单

代码里**没有任何**绑死本机环境的路径（没有盘符、没有用户目录、没有仓库绝对地址）。  
只有下面这几处需要你按目标机器确认一遍：

| 位置                        | 值                                           | 说明                                                                             |
| ------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------ |
| `.env` → `APP_UPLOAD_DIR` | `/var/lib/portfolio/upload`                 | **必须是绝对路径**。相对路径会跟着 java 进程的工作目录解析，换启动方式素材就"消失"。配成相对路径时后端启动会打 WARN 并打印解析后的真实位置 |
| `docker-compose.yml`（api） | `APP_UPLOAD_DIR: /var/lib/portfolio/upload` | 容器内路径，与 `Dockerfile` 里 `mkdir`/`chown` 的一致                                     |
| `docker-compose.yml`（web） | `upload-data:/var/www/media:ro`             | 同一个卷的只读副本，Nginx 从这里直出图片/视频                                                     |
| `docker-compose.yml`      | `name: portfolio`                           | 固定项目名 → 卷名恒为 `portfolio_xxx`，备份脚本不用猜目录名                                        |
| `deploy/redis.conf`       | 无 `requirepass`                             | 密码由 compose 用 `--requirepass ${REDIS_PASSWORD}` 注入，只认 `.env` 一份                |
| 前端 `VITE_BASE`            | 默认 `/`                                      | 部署在子目录（如 `/portfolio/`）时设成 `/portfolio/`，前端路由已用 `BASE_URL`，改这一处就够              |
| `HTTP_PORT`               | 默认 `80`                                     | 对外只暴露这一个端口                                                                     |

**已经替你踩掉的三个坑**（不改的话在服务器上是真的起不来）：

1. Redis 密码写死在 `redis.conf` 里 → 与 `.env` 不一致，后端认证失败；
2. Redis 容器没有 `REDIS_PASSWORD` 环境变量 → 健康检查永远 unhealthy，`api` 因 `depends_on` 永远不启动；
3. 备份脚本用目录名推卷名 → 换个目录部署就找不到卷。

### 10. 备份（务必设置）

```bash
./scripts/backup.sh                       # 输出到 ./backup/
# 定时：每天 03:00
0 3 * * * cd /opt/portfolio && ./scripts/backup.sh >> /var/log/pf-backup.log 2>&1
```

产出两份：`db-时间戳.sql.gz`（整站内容/账号/审计）+ `media-时间戳.tar.gz`（图片视频），默认保留最近 14 份。  
恢复：`./scripts/restore.sh backup/db-xxx.sql.gz backup/media-xxx.tar.gz`。

---

## 四、本地开发（Windows，免管理员权限）

```powershell
.\toolchain\setup.ps1          # JDK 17 + Maven 3.9 + Redis（约 200MB）
.\toolchain\setup-mysql.ps1    # MySQL 8.0 便携版（约 243MB）
.\toolchain\mysql-start.ps1    # 初始化数据目录
.\toolchain\mysql-init-db.ps1  # 建库 Cyan + 应用账号 + 灌表
.\toolchain\check.ps1          # 体检
```

日常：

```powershell
.\toolchain\start-dev.ps1      # MySQL(3307) + Redis(6379) + 后端(8080) + 前端(5173)
```

或手动：

```bash
cd server && mvn spring-boot:run         # http://127.0.0.1:8080/api/content
cd web    && npm install && npm run dev  # http://localhost:5173
```

本地同样可以用 Docker：`docker compose up -d --build`（需把 `.env` 里的 `MYSQL_PORT` 改成 3306、`MYSQL_HOST` 改成 mysql 之外的地址，或直接改 compose）。

---

## 五、内容模型

整份站点就是一个 JSON（后台「导出 JSON」可拿到，也可导入）：

```jsonc
{
  "site": { "brandName": "", "brandMark": "", "footerCols": [...] },
  "modules": [
    {
      "id": "photo",                 // 同时是锚点 #photo
      "type": "hero|about|stack|cases|quote|contact|text",
      "size": "auto|sm|md|lg|xl|full",   // auto = 按内容量自动决定占几列
      "visible": true, "showNav": true, "navLabel": "摄影",
      "eyebrow": "", "title": "", "lead": "",
      "media": [ { "kind": "image|video", "src": "", "title": "", "meta": "", "dur": "", "badge": "" } ]
    }
  ]
}
```

`src` 留空时前台渲染占位块并显示 `hint`，所以**先搭结构、后补素材**完全没问题。

### 加一种新模块类型要动几处

1. `web/src/moduleSchema.ts` 里加一条 `TYPE_DEFS`（描述后台表单长什么样）；
2. `web/src/components/modules/XxxModule.vue` 写一个渲染组件；
3. `ModuleSection.vue` 里登记映射。

**后端零改动，数据库零改动。**

---

## 六、版面自动排布

- 12 栅格 + `grid-auto-flow: dense`，模块由 `size` 或 `auto` 规则换算占位宽度；
- `auto` 规则：作品 ≥5 条自动通栏，案例 >1 条自动通栏，长文本占 2/3，两个小模块并排；
- 新增模块不用管位置，浏览器自动补白 —— 这就是"后期随便加模块"的底气。

## 七、卡牌叠加画廊（作品区核心交互）

- 素材多时自动横向叠成一副扇形牌，卡片数量不限，间距自适应；
- 鼠标移到哪张 → 那张弹性放大成主播放位（视频自动开播），**移开后保持**；
- 点右上角 **✕** 收牌还原；支持 ← → 切换、Esc 关闭、键盘 Enter/Space；
- 选中瞬间有 squash & stretch 的果冻动效（`jellyPop` + 回弹缓动）；
- 窄屏（<620px）自动切成横向滑动 + 全屏灯箱。

视觉为 Apple 风格：液态毛玻璃（`backdrop-filter: blur(26px) saturate(180%)`）、大圆角、克制的动效时长曲线。

---

## 八、后台三块新能力

### 监控（今日访客 / 停留时长 / 是否播放作品）

前台 `HomeView` 启动时开一次匿名会话，之后每 20 秒报一次心跳累加停留时长，  
关标签页时用 `navigator.sendBeacon` 补最后一拍；点开任意作品记一次 `play`。

- 后端只存 **IP 的哈希**，不采集姓名、账号、精确定位，也不做跨天追踪；
- 三张表：`visit_session`（会话 / 停留 / 播放数）、`visit_event`（动作明细）；
- 后台「监控」页给出：今日访客数、平均停留、播放次数与播放率、近 7/14/30 天柱状趋势、  
  被点播最多的作品 TOP10、最近 20 次访问明细。

### 工作对接（网站底部提交的需求）

以前留言塞在 `audit_log` 里只能看不能处理，现在独立成 `inquiry` 表：

- 状态流转 **未读 → 已联系 → 已归档**，可写**内部备注**（只有你能看到，不会回到前台）；
- 表单字段：称呼 / 联系方式 / 项目类型（后台可配）/ 预算区间 / 项目简介；
- 匿名可写，但按 IP 限 5 条/分钟挡灌水。

### 自定义模块类型

后台「新增模块」下拉里有一项 **自定义内容**，选中后可以自由组合：

- 富文本正文（段落 / 加粗 / 列表 / 链接，经 jsoup 白名单净化）；
- 素材网格 1 / 2 / 3 列，横竖屏自动适配；
- 可选参数表 + 按钮。

**后端零改动**——payload 对后端是不透明 JSON，加类型不用改 Java、不用改表。

## 九、素材的横竖屏兼容

摄影、剪辑、直播这行必然横屏 16:9 与竖屏 9:16 混着来，规则只有一条：

| 状态        | 行为                                    |
| --------- | ------------------------------------- |
| **卡牌收拢态** | 所有牌共用 4:5 统一画框，一排牌视觉重量一致，不会因一张竖图把行高撑乱 |
| **点开播放态** | 按素材**真实比例**展开：竖屏变高、横屏变宽，尺寸真的会变（弹簧缓动）  |
| **其他模块**  | 按真实方向选画框，竖屏 4:5 / 横屏 16:9 / 方图 1:1    |

尺寸从哪来：上传或粘贴外链后**前端当场探测**真实像素并回填 `w/h`  
（图片用 `naturalWidth`，视频用 `loadedmetadata`），下次保存就固化进数据库。  
后台每张素材都会显示「1080×1920 · 竖屏」，并可手工指定画框策略  
（自动 / 统一画框 / 原始比例）。

## 十、安全清单

**后台与接口**

- 无状态 JWT；BCrypt(cost=12) 存密码，永不明文落库；
- 登录失败 5 次锁 15 分钟（Redis 计数，Redis 挂了自动降级为本地计数）；
- 用户名不存在时也走一次等价哈希运算，杜绝"用户名枚举"的时序差；
- 登出把 `jti` 加入黑名单，旧 token 立即失效；
- Spring Security 默认 `anyRequest().denyAll()`，只显式放行必要端点；
- actuator 只暴露 `/actuator/health`，且不显示详情。

**内容安全**

- 富文本经 jsoup 白名单消毒（`<script>`、`onerror` 等被剥离，已在测试中验证）；
- URL 走协议白名单：只允许 http/https/mailto/tel/data:image/相对路径/锚点；
- 内容体积上限（4MB / 300 个模块 / 30000 节点），防超大 payload 打爆内存；
- 模块 id 正则校验 + 重复 id 拒绝。

**上传**

- 按**魔数**嗅探真实 MIME，不信客户端 `Content-Type`；
- 文件名 UUID + 日期分目录，路径拼装防目录穿越；
- sha256 去重；库里只存相对路径，不暴露服务器真实目录。

**传输与边缘**

- Nginx 层限流：登录 12 次/分钟、其余 API 30 次/秒、单 IP 并发 60；
- 安全响应头（CSP / nosniff / X-Frame-Options / Referrer-Policy / Permissions-Policy）；
- MySQL、Redis **不映射宿主端口**，公网只能经 `web` 进；
- 访客留言按 IP 限 5 条/分钟。

---

## 十一、接口一览

| 方法     | 路径                          | 鉴权  | 说明                               |
| ------ | --------------------------- | --- | -------------------------------- |
| GET    | `/api/content`              | 公开  | 整份站点 JSON                        |
| PUT    | `/api/content`              | 需登录 | 保存（自动校验 + 存快照 + 版本号 +1）          |
| GET    | `/api/content/revisions`    | 需登录 | 最近 20 份快照                        |
| POST   | `/api/content/rollback?id=` | 需登录 | 回滚                               |
| POST   | `/api/media`                | 需登录 | 上传，返回 `{url, mime, size}`        |
| GET    | `/api/media`                | 需登录 | 素材库                              |
| DELETE | `/api/media?path=`          | 需登录 | 删除                               |
| POST   | `/api/auth/login`           | 公开  | 登录                               |
| POST   | `/api/auth/logout`          | 需登录 | 登出（拉黑 token）                     |
| GET    | `/api/auth/me`              | 需登录 | 当前用户                             |
| POST   | `/api/auth/password`        | 需登录 | 改密码                              |
| GET    | `/api/admin/status`         | 需登录 | Redis 状态 / 内容版本 / 素材数            |
| GET    | `/api/admin/inbox`          | 需登录 | 访客留言                             |
| POST   | `/api/inquiry`              | 公开  | 提交项目需求                           |
| GET    | `/api/admin/inquiries`      | 需登录 | 需求列表                             |
| PATCH  | `/api/admin/inquiries/{id}` | 需登录 | 改状态（new/contacted/archived）与内部备注 |
| DELETE | `/api/admin/inquiries/{id}` | 需登录 | 删除需求                             |
| GET    | `/api/admin/stats?days=7`   | 需登录 | 访客 / 停留 / 播放 / 趋势 / 播放榜          |
| POST   | `/api/track/session`        | 公开  | 开匿名会话，返回 sessionId               |
| POST   | `/api/track/beat`           | 公开  | 心跳，累加停留时长                        |
| POST   | `/api/track/event`          | 公开  | 记动作（play / inquiry）              |


| GET | `/media/<相对路径>` | 公开 | 素材（生产由 Nginx 直出） |

---

## 十二、出问题先看这里

| 现象 | 原因 / 处理 |
|---|---|
| `api` 反复重启 | 大概率连不上 MySQL。看 `docker compose logs api`，确认 mysql 已 healthy |
| 前台空白 / 404 | `docker compose logs web`；确认 `web/dist` 是在**镜像内**构建的（Dockerfile 已改成 `COPY --from=build`） |
| 上传 413 | Nginx 或上游还有一层反代没放开 `client_max_body_size 220m` |
| 图片传上去打不开 | 检查 `upload-data` 卷是否同时挂给了 api（读写）和 web（只读） |
| 改了内容前台没变 | 后台保存后硬刷新一次；若开了 Redis 缓存仍异常，`docker compose exec redis redis-cli -a "$REDIS_PASSWORD" FLUSHDB` |
| 忘了管理员密码 | 没有找回入口（刻意如此）。用 `docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" Cyan` 手动更新 `admin_user.password_hash`，或删掉该行让下次启动重新播种 |
