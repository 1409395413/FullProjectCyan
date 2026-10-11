// 站点内容模型 —— 与后端 Java DTO / 数据库中的 JSON 结构严格一致
// 后端不解析模块语义（只做安全校验），因此这里新增字段不会破坏后端接口。

export type ModuleType =
  | 'hero' | 'about' | 'stack' | 'cases' | 'quote' | 'contact' | 'text' | 'custom'
export type MediaKind = 'image' | 'video'
export type SizeToken = 'auto' | 'sm' | 'md' | 'lg' | 'xl' | 'full'

/** 上传素材的真实像素；留空时前端会在加载后自动探测，用于判断横竖屏 */
export interface MediaItem {
  kind: MediaKind
  src?: string
  poster?: string
  title?: string
  /** 卡片副标题 */
  meta?: string
  /** 左上角小标签 */
  badge?: string
  /** 右下角时长 */
  dur?: string
  /** 占位态的尺寸提示 */
  hint?: string
  label?: string
  cap?: string
  cap2?: string
  /** 原始宽高（上传时自动写入，也可手工填） */
  w?: number
  h?: number
  /**
   * 画框策略：
   *  auto    —— 按素材真实方向：竖屏 4:5、横屏 16:9、方图 1:1
   *  uniform —— 强制统一画框（叠牌画廊里的默认表现）
   *  native  —— 完全按原始比例，不裁切
   */
  fit?: 'auto' | 'uniform' | 'native'
  /**
   * 是否允许播放声音（仅对视频有效）。
   *
   * 浏览器一律禁止「带声音的自动播放」——没被用户交互过的页面，
   * 带音轨的 video.play() 会被直接拒绝。所以即便这里打开，
   * 首屏仍然是静音自动播放，只是多出一个喇叭按钮，
   * 访客点一下才真正出声（这一下点击就是浏览器要的用户手势）。
   * 关掉则连喇叭按钮都不显示，纯静音循环。
   */
  sound?: boolean
  /**
   * 临时下架（隐藏）。
   *
   * 勾选后前台不再渲染这条素材，但文件、标题、画框配置全都留着，
   * 想重新上架只要取消勾选 —— 不用重新上传、不用重填字段。
   * 后台素材库里仍然能看到并编辑它。
   */
  hidden?: boolean
}

export interface Action {
  label: string
  href: string
  kind?: 'primary' | 'ghost'
}

export interface KV {
  k: string
  v: string
}

export interface Stat {
  value: string
  label: string
}

export interface HeroReel {
  bar?: string
  media?: MediaItem
}

export interface FooterCol {
  title: string
  links: { label: string; href: string }[]
}

export interface SiteConfig {
  brandName: string
  brandMark: string
  footerNote?: string
  copyright?: string
  footerCols: FooterCol[]
  /**
   * 备案信息总开关。关掉后页脚不再显示备案号（号码可以留着不用删）。
   * 不填这个字段等同于开启。
   */
  showBeian?: boolean
  /**
   * ICP 备案号，例如「粤ICP备2026xxxxxx号」。
   * 填了就会在页脚底部渲染一条链到 https://beian.miit.gov.cn/ 的链接。
   * 国内节点上线必须挂，管局会抽查。
   */
  icpBeian?: string
  /**
   * 公安备案号，例如「粤公网安备 44010602000123号」。
   * 链接会自动从号码里抽数字拼到 beian.mps.gov.cn 的查询地址上。
   */
  policeBeian?: string
}

export interface CaseItem {
  title: string
  desc?: string
  /** 老数据：一个案例只有一条素材 */
  media?: MediaItem
  /**
   * 新数据：一个案例可以挂任意多条素材（视频 + 图片混排）。
   * 前台主展示位播第一条，其余在悬浮缩略轨里点选切换。
   */
  mediaList?: MediaItem[]
  specs?: KV[]
  tags?: string[]
}

/** 字段缺失时的兜底字段，未知类型模块也能渲染 */
export interface GenericFields {
  body?: string
  paragraphs?: string[]
  facts?: KV[]
  skills?: string[]
  rows?: KV[]
  projectTypes?: string[]
  [key: string]: unknown
}

export interface MediaSlots {
  /** 部分模块把 media 写成对象而不是数组 */
  media?: MediaItem | MediaItem[]
}

export interface ModuleBase {
  id: string
  type: ModuleType
  visible?: boolean
  flat?: boolean
  size?: SizeToken
  showNav?: boolean
  navLabel?: string
  eyebrow?: string
  title?: string
  lead?: string
}

export interface HeroModule extends ModuleBase {
  type: 'hero'
  actions?: Action[]
  chips?: string[]
  stats?: Stat[]
  reel?: HeroReel
}

export interface AboutModule extends ModuleBase {
  type: 'about'
  paragraphs?: string[]
  media?: MediaItem | MediaItem[]
  facts?: KV[]
  skills?: string[]
}

export interface StackModule extends ModuleBase {
  type: 'stack'
  actions?: Action[]
  media?: MediaItem[]
}

export interface CasesModule extends ModuleBase {
  type: 'cases'
  actions?: Action[]
  cases?: CaseItem[]
}

export interface QuoteModule extends ModuleBase {
  type: 'quote'
}

export interface ContactModule extends ModuleBase {
  type: 'contact'
  rows?: KV[]
  formNote?: string
  submitLabel?: string
  projectTypes?: string[]
}

export interface TextModule extends ModuleBase {
  type: 'text'
  body?: string
}

/** 自定义模块：正文 + 可选素材网格 + 可选参数表，排版交给作者 */
export interface CustomModule extends ModuleBase {
  type: 'custom'
  body?: string
  media?: MediaItem[]
  /** 素材网格列数 */
  columns?: 1 | 2 | 3
  facts?: KV[]
  actions?: Action[]
}

export type PortfolioModule =
  | HeroModule | AboutModule | StackModule | CasesModule
  | QuoteModule | ContactModule | TextModule | CustomModule

export interface SiteContent {
  version: number
  site: SiteConfig
  modules: PortfolioModule[]
}

/* ---------------- 后台鉴权相关 ---------------- */
export interface LoginPayload {
  username: string
  password: string
  remember?: boolean
}

export interface LoginResult {
  token: string
  expiresIn: number
  username: string
  role: string
}

export interface ApiError {
  code: string
  message: string
}

/* ---------------- 后台运行状态 ---------------- */
export interface AdminStatus {
  contentVersion: number
  mediaCount: number
  redis: 'up' | 'down' | 'disabled'
  uploadDir: string
  maxUploadMB: number
}

export interface RevisionItem {
  id: number
  createdAt: string
  bytes: number
  moduleCount: number
}

export interface MediaItemView {
  path: string
  url: string
  mime: string
  size: number
  width?: number
  height?: number
  createdAt?: string
  /** 这条素材身上的标签名。后端一次性算好，前端不用二次请求 */
  tags?: string[]
}

/** 一个标签 + 它名下的素材数（用来在筛选按钮上显示「直播截图 12」） */
export interface MediaTagView {
  id: number
  name: string
  count: number
}

/**
 * 打标签的回执。
 * tags 是**规范化之后**的名字 —— 输入 " #直播 " 存进去是 "直播"，
 * 回显这一份，界面就能立刻和系统里实际存的保持一致。
 */
export interface MediaTagsResult {
  applied: number
  missing: string[]
  tags: string[]
}

/** 单个素材删除的结果：连同引用一起摘掉后，还剩几处引用被清理了 */
export interface MediaDeleteResult {
  deleted: number
  referencesRemoved: number
}

/** 批量删除结果：failed 是没删成的路径（通常是已不存在） */
export interface MediaBatchDeleteResult {
  deleted: number
  referencesRemoved: number
  failed: string[]
}

/** 批量上传里每个文件的结局：给 url 就是成功，给 error 就是失败 */
export interface MediaBatchUploadItem {
  name: string
  url?: string
  mime?: string
  size?: number
  width?: number
  height?: number
  warning?: string
  error?: string
}

/* ---------------- 后台监控 ---------------- */
export interface StatsToday {
  day: string
  /** 今天来访的去重人数 */
  visitors: number
  /** 今天共开了几次会话 */
  sessions: number
  /** 作品播放总次数 */
  plays: number
  /** 点开过作品的人数 */
  playedSessions: number
  /** 平均停留毫秒 */
  avgDurationMs: number
  /** 播放率（百分比，0–100，保留一位小数） */
  playRate: number
}

export interface StatsSeriesItem {
  day: string
  sessions: number
  plays: number
  avgDurationMs: number
}

export interface StatsRecent {
  id: number
  startedAt: string
  durationMs: number
  playCount: number
  entryPath?: string
  referrer?: string | null
  ua?: string
  /** 省/直辖市（2026-10-09 起采集，历史数据为「未知」） */
  region?: string
  /** 手机 / 平板 / 桌面 */
  device?: string
}

export interface AdminStats {
  today: StatsToday
  series: StatsSeriesItem[]
  topPlays: { target: string; count: number }[]
  recent: StatsRecent[]
  inquiry: { total: number; new: number; contacted: number; archived: number }
  /** 较昨日变化百分比；null = 昨日基数为 0，无法算百分比 */
  todayDelta?: {
    visitors?: number | null
    sessions?: number | null
    plays?: number | null
    avgDurationMs?: number | null
    playRate?: number | null
  }
  /** 地域排行（省/直辖市，海外为国家名），最多 10 条 */
  region?: { name: string; count: number }[]
  /** 设备占比：手机 / 平板 / 桌面 */
  devices?: { name: string; count: number }[]
  /** 浏览器 TOP5 */
  browsers?: { name: string; count: number }[]
  /** 来源 TOP5（referrer 域名，无来源为「直接访问」） */
  sources?: { name: string; count: number }[]
  /** 时段热力：168 格铺平（周一 0 点起，7 天 × 24 小时） */
  heatmap?: number[]
  /** 转化漏斗：窗口内 访问会话 → 播放过作品的会话 → 提交需求（件数） */
  funnel?: { visits: number; players: number; inquirers: number }
  /** 统计窗口天数 */
  windowDays?: number
  /** 参与聚合的会话数（地域/设备等板块的数据基础） */
  windowSessions?: number
}

/* ---------------- 工作对接 ---------------- */
export type InquiryStatus = 'new' | 'contacted' | 'archived'

export interface InquiryView {
  id: number
  name?: string
  contact?: string
  type?: string
  budget?: string
  message?: string
  status: InquiryStatus
  note?: string
  ip?: string
  createdAt?: string
}
