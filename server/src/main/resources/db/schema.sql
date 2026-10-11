-- =============================================================================
--  Cyan · Studio · MySQL 8 schema
--  说明：ddl-auto=update 时能自动生成，但生产建议以本文件为准（validate 模式）
--  执行：  mysql -h127.0.0.1 -P3307 -uroot -p CyanWorks < server/src/main/resources/db/schema.sql
-- =============================================================================
SET NAMES utf8mb4;

-- ---------------------------- 站点内容 ----------------------------
-- 整份页面 JSON 存一行：模块增删不用改表，前端渲染层自动排版
CREATE TABLE IF NOT EXISTS site_content (
  id          BIGINT      NOT NULL PRIMARY KEY,
  payload     LONGTEXT    NOT NULL COMMENT '整站内容：site + modules[]',
  version     INT         NOT NULL DEFAULT 1,
  updated_by  BIGINT      NULL,
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX idx_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 每次保存的快照，误删/改错可回滚
CREATE TABLE IF NOT EXISTS content_revision (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  payload     LONGTEXT    NOT NULL,
  created_by  BIGINT      NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------- 后台账号 ----------------------------
CREATE TABLE IF NOT EXISTS admin_user (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  username       VARCHAR(32)  NOT NULL,
  password_hash  VARCHAR(255) NOT NULL COMMENT 'bcrypt / argon2id',
  totp_secret    VARCHAR(64)  NULL COMMENT '二次验证，建议开启',
  role           ENUM('owner','editor') NOT NULL DEFAULT 'editor',
  status         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=启用 0=停用',
  last_login_at  DATETIME(3)  NULL,
  last_login_ip  VARCHAR(45)  NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_username (username),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 会话：TTL 由应用侧清理，Redis 里另存黑名单用于强制下线
CREATE TABLE IF NOT EXISTS admin_session (
  id          CHAR(36)     NOT NULL PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  ip          VARCHAR(45)  NULL,
  user_agent  VARCHAR(255) NULL,
  expires_at  DATETIME(3)  NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_expires (expires_at),
  INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------- 素材 ----------------------------
CREATE TABLE IF NOT EXISTS media_asset (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  path        VARCHAR(255) NOT NULL COMMENT '相对路径，不暴露真实磁盘目录',
  mime        VARCHAR(64)  NOT NULL,
  size        BIGINT       NOT NULL,
  checksum    CHAR(64)     NULL COMMENT 'sha256，用于秒传与去重',
  width       INT          NULL,
  height      INT          NULL,
  created_by  BIGINT       NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_checksum (checksum),
  INDEX idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 素材标签。名字唯一，改名 = 改这一行，所有素材跟着变
CREATE TABLE IF NOT EXISTS media_tag (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(64)  NOT NULL COMMENT '标签名，唯一',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 素材 × 标签。PRIMARY KEY(asset_id, tag_id) 本身就是「一条素材重复打同一标签」的兜底
CREATE TABLE IF NOT EXISTS media_asset_tag (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  asset_id    BIGINT       NOT NULL,
  tag_id      BIGINT       NOT NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_asset_tag (asset_id, tag_id),
  INDEX idx_tag (tag_id),
  CONSTRAINT fk_mat_asset FOREIGN KEY (asset_id) REFERENCES media_asset (id) ON DELETE CASCADE,
  CONSTRAINT fk_mat_tag   FOREIGN KEY (tag_id)   REFERENCES media_tag (id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------- 工作对接（访客需求）----------------------------
CREATE TABLE IF NOT EXISTS inquiry (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(64)   NULL,
  contact     VARCHAR(128)  NULL,
  type        VARCHAR(64)   NULL COMMENT '项目类型',
  budget      VARCHAR(64)   NULL COMMENT '预算区间',
  message     LONGTEXT      NULL,
  status      VARCHAR(16)   NOT NULL DEFAULT 'new' COMMENT 'new / contacted / archived',
  note        LONGTEXT      NULL COMMENT '内部备注，仅后台可见',
  ip          VARCHAR(45)   NULL,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_created (created_at),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------- 访问统计（匿名）----------------------------
-- 只存哈希后的 IP，不采集身份信息；用来回答"今天几个人 / 待多久 / 有没有播作品"
CREATE TABLE IF NOT EXISTS visit_session (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  visitor_key  VARCHAR(64)  NULL COMMENT 'hash(ip+ua+日期)，用于按人去重',
  ip_hash      VARCHAR(64)  NULL,
  ua           VARCHAR(255) NULL,
  referrer     VARCHAR(255) NULL,
  entry_path   VARCHAR(255) NULL,
  started_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  duration_ms  BIGINT       NOT NULL DEFAULT 0,
  play_count   INT          NOT NULL DEFAULT 0,
  day          CHAR(10)     NULL COMMENT 'yyyy-MM-dd，按 Asia/Shanghai 计算',
  country      VARCHAR(32)  NULL COMMENT '国家；改造前的历史数据为 NULL',
  region       VARCHAR(32)  NULL COMMENT '省/直辖市（离线库 ip2region，只到省级）',
  INDEX idx_day (day),
  INDEX idx_started (started_at),
  INDEX idx_region (region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS visit_event (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id  BIGINT       NOT NULL,
  type        VARCHAR(24)  NOT NULL COMMENT 'play / inquiry / contact_click',
  target      VARCHAR(255) NULL,
  day         CHAR(10)     NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_type_day (type, day),
  INDEX idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------- 审计 ----------------------------
CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     BIGINT       NULL,
  action      VARCHAR(64)  NOT NULL COMMENT 'login / save_content / upload / delete_media ...',
  ip          VARCHAR(45)  NULL,
  detail      LONGTEXT    NULL,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX idx_created (created_at),
  INDEX idx_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------- 初始内容行 ----------------------------
-- 故意不预置内容：后端首次启动时若 site_content 为空，
-- 会自动把随包的 default-content.json 写进来（见 DataInitializer）。
-- 这样"默认站点长什么样"由代码仓库里的 JSON 决定，而不是由 SQL 决定。
