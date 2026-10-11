import type {
  AdminStats,
  AdminStatus,
  ApiError,
  InquiryStatus,
  InquiryView,
  LoginPayload,
  LoginResult,
  MediaBatchDeleteResult,
  MediaBatchUploadItem,
  MediaDeleteResult,
  MediaItemView,
  MediaTagView,
  MediaTagsResult,
  RevisionItem,
  SiteContent
} from '@/types/content'

const BASE = import.meta.env.VITE_API_BASE || '/api'

/** sendBeacon 需要拼绝对路径，所以把前缀也导出去 */
export const API_BASE = BASE

export class HttpError extends Error {
  status: number
  code: string
  constructor(status: number, body: Partial<ApiError>) {
    super(body.message || `HTTP ${status}`)
    this.status = status
    this.code = body.code || String(status)
  }
}

const TOKEN_KEY = 'yc_admin_token'

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY)
}

/**
 * 素材库列表的进程内缓存。
 * ---------------------------------------------------------------------------
 * 以前这份缓存藏在 MediaPicker 组件里，结果是：在素材库删掉一张图之后，
 * 再打开模块里的「从素材库选取」，那张图还在列表里 —— 因为它压根不知道外面删过东西。
 * 缓存挪到这里，上传 / 删除 / 清理都走同一个出口作废它，就不会再看到已删素材了。
 */
const mediaCache: { data: MediaItemView[] | null } = { data: null }

/**
 * 标签清单的缓存。和素材缓存是两份 —— 但凡是动到标签的写操作，两个都得作废：
 * 素材列表里嵌着每条的 tags，不清就会出现「打了标签、卡片上还看不见」。
 */
const tagCache: { data: MediaTagView[] | null } = { data: null }

function clearMediaCaches() {
  mediaCache.data = null
  tagCache.data = null
}

/**
 * 单批体积上限 90MB。
 *
 * <p>必须明显小于 nginx 的 {@code client_max_body_size}（线上已放宽到 1200m），
 * 留出余量给 multipart 边界、多个文件的开销，以及 HTTP 本身的一点膨胀。
 */
const UPLOAD_BATCH_MAX_BYTES = 90 * 1024 * 1024

/** 单批文件数上限 —— 一堆小文件也不该攒出一个难以 accept 的超大请求 */
const UPLOAD_BATCH_MAX_FILES = 20

/**
 * 按累计大小把文件切成若干批：单批不超过 {@link UPLOAD_BATCH_MAX_BYTES}，
 * 也不超过 {@link UPLOAD_BATCH_MAX_FILES}；单个就超限的文件自己独占一批。
 */
function chunkFiles(files: File[]): File[][] {
  const batches: File[][] = []
  let cur: File[] = []
  let bytes = 0

  for (const f of files) {
    const oversized = f.size > UPLOAD_BATCH_MAX_BYTES
    // 装不下、到数量了、或下一个本身就超大 —— 先把攒着的这批交出去
    if (cur.length && (oversized || bytes + f.size > UPLOAD_BATCH_MAX_BYTES
        || cur.length >= UPLOAD_BATCH_MAX_FILES)) {
      batches.push(cur)
      cur = []
      bytes = 0
    }
    cur.push(f)
    bytes += f.size
    // 单个文件超过上限：让它单独走一批，不然它排谁后面都必然超限
    if (oversized) {
      batches.push(cur)
      cur = []
      bytes = 0
    }
  }
  if (cur.length) batches.push(cur)
  return batches
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = tokenStore.get()
  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init.headers as Record<string, string> | undefined)
  }
  if (token) headers.Authorization = `Bearer ${token}`
  if (init.body && typeof init.body === 'string') headers['Content-Type'] = 'application/json'

  let res: Response
  try {
    res = await fetch(BASE + path, { ...init, headers, credentials: 'same-origin' })
  } catch (e) {
    // fetch 只在「压根没拿到响应」时抛 TypeError（网络断开、DNS 失败、
    // 连接被重置……）—— nginx 请求体超限时的 413 就属于后者：它一面回错误页
    // 一面掐断连接，浏览器还在往外写数据就被 RST 掉，报出来就是那句
    // 没有信息量的 "Failed to fetch"。这里换成人类能看懂的话。
    throw new HttpError(0, {
      code: 'network_error',
      message: '网络中断或被网关拒绝（文件总大小超过服务器上传上限时也会这样），请分批重试'
    })
  }
  if (res.status === 401) tokenStore.clear()
  if (!res.ok) {
    let body: Partial<ApiError> = {}
    try { body = await res.json() } catch { /* ignore */ }
    throw new HttpError(res.status, body)
  }
  if (res.status === 204) return undefined as unknown as T
  return res.json() as Promise<T>
}

export const http = request

/* ------------------------------ 公开接口 ------------------------------ */
export const fetchContent = () => request<SiteContent>('/content')

/** 访客在底部提交需求 */
export function submitInquiry(payload: {
  name: string
  contact: string
  type?: string
  budget?: string
  message: string
}) {
  return request<{ status: string }>('/inquiry', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
}

/** 匿名访问埋点：开会话 / 心跳 / 关键动作 */
export const trackApi = {
  open(path: string, referrer: string) {
    return request<{ sessionId: number; beatMs: number }>('/track/session', {
      method: 'POST',
      body: JSON.stringify({ path, referrer })
    })
  },
  beat(sessionId: number, ms: number) {
    return request<{ status: string }>('/track/beat', {
      method: 'POST',
      body: JSON.stringify({ sessionId, ms })
    })
  },
  event(sessionId: number, type: string, target: string) {
    return request<{ status: string }>('/track/event', {
      method: 'POST',
      body: JSON.stringify({ sessionId, type, target })
    })
  }
}

/* ------------------------------ 后台接口 ------------------------------ */
export const adminApi = {
  login(payload: LoginPayload) {
    return request<LoginResult>('/auth/login', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },
  logout() {
    return request<void>('/auth/logout', { method: 'POST' })
  },
  me() {
    return request<{ username: string; role: string; checkedAt: string }>('/auth/me')
  },
  changePassword(oldPassword: string, newPassword: string) {
    return request<{ status: string }>('/auth/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword })
    })
  },
  saveContent(content: SiteContent) {
    return request<{ version: number }>('/content', {
      method: 'PUT',
      body: JSON.stringify(content)
    })
  },
  status() {
    return request<AdminStatus>('/admin/status')
  },
  revisions() {
    return request<RevisionItem[]>('/admin/revisions')
  },
  rollback(id: number) {
    return request<{ version: number }>(`/admin/revisions/${id}/rollback`, { method: 'POST' })
  },
  upload(file: File) {
    const form = new FormData()
    form.append('file', file)
    return request<{ url: string; mime: string; size: number; width?: number; height?: number; warning?: string }>(
      '/media',
      {
        method: 'POST',
        body: form
      }
    )
  },

  /**
   * 批量上传，自动分批。
   *
   * <p>为什么不能一股脑塞进一个请求：一次选二十个视频，请求体动辄 1GB 以上，
   * nginx 的 {@code client_max_body_size} 会直接回 413 并掐断连接 —— 而此刻浏览器
   * 还在往外写数据，收到重置后整个请求被标记为网络错误，前端只得到一句
   * "Failed to fetch"，已经传上去的那部分也全部白费，用户只能对着一个
   * 看不懂的报错反复重试（线上日志里同一个 1.1GB 的请求被重发了 5 次）。
   *
   * <p>分批之后带来三个好处：单个请求永远够小不会触发网关限制、
   * 某一批失败不影响其余批次（已成功的素材已经入库）、进度可以被看见。
   *
   * @param tags       可选的标签名，上传成功后自动打在这批新素材上
   * @param onProgress 每完成一批回调一次 {@code (已完成批数, 总批数)}
   */
  async uploadMany(
    files: File[],
    tags: string[] = [],
    onProgress?: (done: number, total: number) => void
  ): Promise<MediaBatchUploadItem[]> {
    const list = (files || []).filter(Boolean)
    if (!list.length) return []

    const batches = chunkFiles(list)
    const out: MediaBatchUploadItem[] = []
    const batchErrors: string[] = []

    for (let i = 0; i < batches.length; i++) {
      const batch = batches[i]
      const form = new FormData()
      for (const f of batch) form.append('files', f)
      if (tags.length) form.append('tags', tags.join(','))

      try {
        const got = await request<MediaBatchUploadItem[]>('/media/batch', {
          method: 'POST',
          body: form
        })
        out.push(...got)
      } catch (e: any) {
        // 一批失败不中止其余 —— 已经落到服务器上的素材不该跟着回滚
        const names = batch.map((f) => f.name).slice(0, 2).join('、')
        const more = batch.length > 2 ? ` 等 ${batch.length} 个` : ''
        batchErrors.push(
          `第 ${i + 1}/${batches.length} 批（${names}${more}）：${e?.message || '网络中断'}`
        )
      }
      onProgress?.(i + 1, batches.length)
    }

    if (batchErrors.length) {
      const err: any = new Error(
        `${batchErrors.length}/${batches.length} 批上传失败：` + batchErrors.join('；')
      )
      // 已成功的那部分挂在错误上 —— 调用方仍能把它渲染出来，不至于整批白传
      err.partial = out
      throw err
    }
    return out
  },

  listMedia(force = false) {
    if (mediaCache.data && !force) return Promise.resolve(mediaCache.data)
    return request<MediaItemView[]>('/media').then((list) => {
      mediaCache.data = list
      return list
    })
  },

  /** 素材库刚被改过（上传 / 删除 / 清理 / 打标签）时调一下，下次列出就会重新拉 */
  invalidateMediaCache() {
    clearMediaCaches()
  },

  /** 全部标签 + 各自名下素材数，按素材数从多到少排 */
  listTags(force = false) {
    if (tagCache.data && !force) return Promise.resolve(tagCache.data)
    return request<MediaTagView[]>('/media/tags').then((list) => {
      tagCache.data = list
      return list
    })
  },

  /**
   * 给素材打标签。
   *
   * replace=false（默认）= 追加，素材原有的标签保留 —— 批量勾选时用这个；
   * replace=true = 覆盖成传入的这一份 —— 单条素材编辑框里取消勾选某个标签时用这个，
   *   否则「取消勾选」会被当成「这次没传它」，永远删不掉。
   */
  applyTags(payload: { paths: string[]; tags: string[]; replace?: boolean }) {
    const r = request<MediaTagsResult>('/media/tags', {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    return r.then((res) => {
      clearMediaCaches()
      return res
    })
  },

  /** 标签改名；新名字已存在就是合并：素材挪过去，旧名字消失 */
  renameTag(from: string, to: string) {
    const r = request<MediaTagsResult>('/media/tags/rename', {
      method: 'POST',
      body: JSON.stringify({ from, to })
    })
    return r.then((res) => {
      clearMediaCaches()
      return res
    })
  },

  /** 删标签 —— 只解除关联，素材一个都不删 */
  deleteTag(name: string) {
    const r = request<{ removed: number }>(`/media/tags?name=${encodeURIComponent(name)}`, {
      method: 'DELETE'
    })
    return r.then((res) => {
      clearMediaCaches()
      return res
    })
  },

  /** 清理没人用的空标签 */
  pruneEmptyTags() {
    const r = request<{ removed: number }>('/media/tags/empty', { method: 'DELETE' })
    return r.then((res) => {
      clearMediaCaches()
      return res
    })
  },

  deleteMedia(path: string) {
    const r = request<MediaDeleteResult>(`/media?path=${encodeURIComponent(path)}`, { method: 'DELETE' })
    return r.then((res) => {
      mediaCache.data = null
      return res
    })
  },

  /** 批量删除：一次 JSON body，避免几十个 query 参数把 URL 撑爆 */
  deleteMediaBatch(paths: string[]) {
    const r = request<MediaBatchDeleteResult>('/media/batch', {
      method: 'DELETE',
      body: JSON.stringify({ paths })
    })
    return r.then((res) => {
      mediaCache.data = null
      return res
    })
  },
  /* ---- 监控 ---- */
  stats(days = 7) {
    return request<AdminStats>(`/admin/stats?days=${days}`)
  },
  /* ---- 工作对接 ---- */
  inquiries() {
    return request<InquiryView[]>('/admin/inquiries')
  },
  patchInquiry(id: number, patch: { status?: InquiryStatus; note?: string }) {
    return request<InquiryView>(`/admin/inquiries/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(patch)
    })
  },
  deleteInquiry(id: number) {
    return request<void>(`/admin/inquiries/${id}`, { method: 'DELETE' })
  }
}
