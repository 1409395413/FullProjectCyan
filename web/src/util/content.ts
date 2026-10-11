import type { MediaItem } from '@/types/content'

/**
 * 防御式取值：某些模块的 media 是对象、某些是数组，这里统一成数组。
 *
 * 顺手把「已下架」的素材过滤掉 —— 后台勾选了 hidden 的条目前台不再渲染，
 * 素材本身和它的配置都还在，取消勾选就能原样上架，不需要重新上传。
 */
export function mediaList(v: unknown): MediaItem[] {
  if (!v) return []
  if (Array.isArray(v)) return v.filter(Boolean).filter((m) => !m.hidden)
  if (typeof v === 'object') {
    const one = v as MediaItem
    return one.hidden ? [] : [one]
  }
  return []
}

/**
 * 案例的素材组 —— 兼容「一个案例一条素材」的老数据。
 *
 * 老内容里案例只有 `media`（单条对象），新内容是 `mediaList`（数组）。
 * 这里统一成数组返回，所以不需要迁移脚本：老案例照旧显示，
 * 你在后台一加素材就自动升级成多素材版本。
 */
export function caseMedia(c: { media?: unknown; mediaList?: unknown } | null | undefined): MediaItem[] {
  if (!c) return []
  const fromList = mediaList(c.mediaList)
  if (fromList.length) return fromList
  return mediaList(c.media)
}

/** 后台保存过的富文本字段已由后端 jsoup 白名单净化，此处可直接渲染。 */
export function rich(v?: string): string {
  return v ?? ''
}

/* ---------------------------------------------------------------------------
 * 横竖屏方向
 * -------------------------------------------------------------------------
 * 摄影、剪辑、直播这些活儿必然是横屏 16:9 与竖屏 9:16 混着来。
 * 这里的规则只回答一个问题：这个素材"更像"竖的还是横的。
 * ------------------------------------------------------------------------- */

/**
 * 视频首帧封面地址。
 * ---------------------------------------------------------------------------
 * 后台不会自动给素材生成封面（只有手工填过 poster 的才有），
 * 手机浏览器又常常不解出视频的第一帧 —— 结果就是案例 / 卡牌位置的视频是一块灰。
 *
 * 这里改成统一向后端要首帧：`/media/a/b/x.mp4` → `/api/media/poster?path=a/b/x.mp4`。
 * 后端用 ffmpeg 抽第 0.2 秒那一帧，缓存成一张几十 KB 的 jpg，
 * 之后 nginx/浏览器都当普通图片加载 —— 任何手机都显示得出来。
 *
 * 路径里可能带 `#t=0.1` 之类的片段或有 query，取之前先切掉。
 *
 * ⚠️ 斜杠必须保留成 `/`，不能用 encodeURIComponent 整体编码。
 * 曾经这里写的是 encodeURIComponent(rel)，斜杠变成 %2F；而 nginx 的
 * `$arg_path` 不会解码 %2F，于是服务端正则校验一律判非法 → 整站封面 400
 * → 视频和案例缩略图全变成灰块，只有点播放浏览器自己去取首帧才看得见画面。
 * 更隐蔽的是：用 Python `urllib.parse.quote` 造测试数据默认不编码斜杠，
 * 所以"服务端自测全 200"，线上却全挂 —— 测试数据必须跟前端真实请求逐字节一致。
 * 这里逐段编码再拼回斜杠：既挡掉 `&`/空格之类的注入，又保住目录分隔符。
 */
export function posterUrlOf(src?: string): string | undefined {
  if (!src) return undefined
  const i = src.indexOf('/media/')
  if (i < 0) return undefined
  const rel = src.slice(i + '/media/'.length).split('#')[0].split('?')[0]
  if (!rel) return undefined
  return `/api/media/poster?path=${rel.split('/').map(encodeURIComponent).join('/')}`
}

/** 素材真实比例；未知时按类型猜一个，等 MediaFrame 探测到再重排 */
export type Orientation = 'portrait' | 'landscape' | 'square' | 'unknown'

/** 0.75~1.33 之间都算方图，避免 4:3 被判成横屏、3:4 被判成竖屏 */
export function orientationOf(w?: number, h?: number): Orientation {
  if (!w || !h || w <= 0 || h <= 0) return 'unknown'
  const r = w / h
  if (r > 1.33) return 'landscape'
  if (r < 0.75) return 'portrait'
  return 'square'
}

/**
 * 叠牌画廊里所有卡牌共用的画框（这样一张竖屏和一张横屏并排时视觉重量一致）。
 * 返回 CSS aspect-ratio 用的 "w / h"。
 */
export function uniformFrame(item?: MediaItem | null): string {
  const o = orientationOf(item?.w, item?.h)
  return o === 'portrait' ? '4 / 5' : o === 'landscape' ? '4 / 3' : '1 / 1'
}

/**
 * 展开成主播放位时用的比例 —— 按素材真实方向给，竖屏就给高的，横屏就给宽的。
 * 这就是"点击播放时改变尺寸"的那一次形变。
 */
export function nativeFrame(item?: MediaItem | null): string {
  const w = item?.w
  const h = item?.h
  if (w && h && w > 0 && h > 0) {
    // 限制极端比例，免得一张长图把版面撑爆
    const r = Math.min(Math.max(w / h, 0.5), 2.2)
    return `${r.toFixed(3)} / 1`
  }
  const o = orientationOf(w, h)
  return o === 'portrait' ? '9 / 16' : o === 'square' ? '1 / 1' : '16 / 9'
}

/** 画框策略 → 具体的 aspect-ratio 字符串 */
export function frameOf(item?: MediaItem | null, uniform = false): string {
  const fit = item?.fit || 'auto'
  if (fit === 'native') return nativeFrame(item)
  if (fit === 'uniform') return uniformFrame(item)
  return uniform ? uniformFrame(item) : nativeFrame(item)
}

/** 给后台看的中文描述，例如 "1080×1920 · 竖屏" */
export function describeMedia(item?: MediaItem | null): string {
  if (!item?.w || !item?.h) return '尺寸未知'
  const o = orientationOf(item.w, item.h)
  const label = o === 'portrait' ? '竖屏' : o === 'landscape' ? '横屏' : '方图'
  return `${item.w}×${item.h} · ${label}`
}
