<script setup lang="ts">
/**
 * 内容后台。
 * ---------------------------------------------------------------------------
 * 左：模块列表（增删 / 排序 / 停用）；中：按模块类型自动生成的字段表单；
 * 上：保存与导入导出。保存直接 PUT /api/content，前端刷新即可看到 —— 不需要重新构建。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi, fetchContent, tokenStore } from '@/api'
import FieldsEditor from '@/components/admin/FieldsEditor.vue'
import StatsCharts from '@/components/admin/StatsCharts.vue'
import MediaTagEditor from '@/components/admin/MediaTagEditor.vue'
import MediaTagManager from '@/components/admin/MediaTagManager.vue'
import { SITE_FIELDS, TYPE_DEFS, typeDef } from '@/moduleSchema'
import { downloadCsv, fileStamp, formatDateTime } from '@/util/exportCsv'
import type {
  AdminStats,
  AdminStatus,
  InquiryStatus,
  InquiryView,
  MediaBatchUploadItem,
  MediaItemView,
  MediaTagView,
  PortfolioModule,
  RevisionItem,
  SiteContent
} from '@/types/content'

const router = useRouter()

const content = ref<SiteContent | null>(null)
const status = ref<AdminStatus | null>(null)
const revisions = ref<RevisionItem[]>([])
const inquiries = ref<InquiryView[]>([])
const stats = ref<AdminStats | null>(null)
const statsDays = ref(7)
const inboxFilter = ref<'all' | InquiryStatus>('all')
const tab = ref<'modules' | 'site' | 'media' | 'stats' | 'inbox' | 'account'>('modules')
const selectedId = ref('')
const loading = ref(true)
const saving = ref(false)
const notice = ref<{ kind: 'ok' | 'err'; text: string } | null>(null)

let noticeTimer: ReturnType<typeof setTimeout> | null = null
function flash(kind: 'ok' | 'err', text: string) {
  notice.value = { kind, text }
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = setTimeout(() => (notice.value = null), 4000)
}

const modules = computed(() => content.value?.modules || [])
const current = computed(() => modules.value.find((m) => m.id === selectedId.value) || null)
const currentDef = computed(() => (current.value ? typeDef(current.value.type) : undefined))

/* ------------------------------ 数据加载 ------------------------------ */
onMounted(async () => {
  try {
    content.value = await adminApi.me().then(() => fetchRemote())
    selectedId.value = modules.value[0]?.id || ''
    await Promise.all([loadStatus(), loadRevisions(), loadInquiries(), loadStats()])
  } catch (e: any) {
    flash('err', e?.message || '载入失败')
  } finally {
    loading.value = false
  }
})

async function fetchRemote() {
  const c = await fetchContent()
  // 老数据里没有 showBeian 字段，而前端语义是「字段缺失 = 显示备案信息」。
  // 这里补成 true，让后台开关的勾选状态与实际显示行为保持一致
  // （否则用户填完备案号、开关看着是关的，保存后会被写成 false 反而不显示）。
  if (c?.site && typeof c.site.showBeian !== 'boolean') c.site.showBeian = true
  migrateCases(c)
  return c
}

/**
 * 老内容的案例只有 `media` 单条素材，新结构改成 `mediaList` 数组。
 *
 * 光判断「mediaList 是不是数组」不够：一旦里面已经有素材位（哪怕是空占位），
 * 老规则就不再搬运 media，于是两条并存，前台在 mediaList 没图时会兜底渲染
 * media 里那张 —— 后台表单看不见它，删不掉也改不了。
 *
 * 这里改成和后端 ContentValidator 完全相同的收敛逻辑：
 * 有真素材就用 mediaList、否则把 media 顶成第一条，两边都不留 `media`。
 * 后台载入时就地升级，保存后自然写成新结构 —— 不需要数据库迁移脚本。
 */
function migrateCases(c: SiteContent | null) {
  for (const m of c?.modules || []) {
    if (m.type !== 'cases') continue
    for (const item of (m as any).cases || []) {
      if (!item) continue
      if (!Array.isArray(item.mediaList)) {
        item.mediaList = item.media && item.media.src ? [item.media] : []
        delete item.media
        continue
      }
      const hasReal = item.mediaList.some((x: any) => x && x.src)
      if (hasReal || !item.media?.src) {
        delete item.media
      } else {
        const rest = item.mediaList.filter((x: any) => x && x.src)
        item.mediaList = [item.media, ...rest]
        delete item.media
      }
    }
  }
}

async function loadStatus() {
  try { status.value = await adminApi.status() } catch { /* ignore */ }
}
async function loadRevisions() {
  try { revisions.value = await adminApi.revisions() } catch { /* ignore */ }
}
async function loadInquiries() {
  try { inquiries.value = await adminApi.inquiries() } catch { /* ignore */ }
}
async function loadStats() {
  try { stats.value = await adminApi.stats(statsDays.value) } catch { /* ignore */ }
}

/* ------------------------------ 监控面板 ------------------------------ */
const STATUS_LABEL: Record<InquiryStatus, string> = {
  new: '未读',
  contacted: '已联系',
  archived: '已归档'
}

function humanDuration(ms?: number): string {
  const s = Math.round((ms || 0) / 1000)
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分 ${s % 60} 秒`
  return `${Math.floor(m / 60)} 小时 ${m % 60} 分`
}

/** 「较昨日 ↑↓ x%」的小箭头；null = 昨日基数为 0，显示「新增」 */
function deltaArrow(v?: number | null): string {
  if (v === null || v === undefined) return '↑ 新增'
  if (v === 0) return '持平'
  return v > 0 ? `↑ ${v}%` : `↓ ${Math.abs(v)}%`
}
function deltaClass(v?: number | null): string {
  if (v === null || v === undefined || v > 0) return 'is-up'
  return v === 0 ? 'is-flat' : 'is-down'
}

const filteredInquiries = computed(() => {
  const all = inquiries.value
  return inboxFilter.value === 'all' ? all : all.filter((q) => q.status === inboxFilter.value)
})

/* ---------------------- 工单勾选与批量导出 ---------------------- */
/** 勾选的工单 id。切换筛选/刷新不会丢，方便跨状态挑几条一起导出。 */
const picked = ref<number[]>([])

function isPicked(id: number) {
  return picked.value.includes(id)
}

function togglePick(id: number, on: boolean) {
  picked.value = on
    ? Array.from(new Set([...picked.value, id]))
    : picked.value.filter((x) => x !== id)
}

/** 当前筛选下已勾选的条数（导出以「当前列表 ∩ 已勾选」为准） */
const pickedCount = computed(
  () => filteredInquiries.value.filter((q) => picked.value.includes(q.id)).length
)

const allPicked = computed(
  () =>
    filteredInquiries.value.length > 0 &&
    filteredInquiries.value.every((q) => picked.value.includes(q.id))
)

function toggleAll(on: boolean) {
  const ids = filteredInquiries.value.map((q) => q.id)
  picked.value = on
    ? Array.from(new Set([...picked.value, ...ids]))
    : picked.value.filter((x) => !ids.includes(x))
}

/**
 * 批量删除：两段式确认，不弹 window.confirm。
 * ---------------------------------------------------------------------------
 * 删除是不可逆的 —— 一次误点就能清掉一批真实客户留言，而且没有回收站。
 * 所以第一次点击只进入「待确认」，按钮变成"再点一次确认删除 N 条"，
 * 4 秒内没有第二次点击就自动撤回。
 *
 * 不用 confirm() 的理由：它冻结整个页面、样式不受控、还会被浏览器折叠成
 * "此页面显示"之类的无关提示，实际防误触效果很差。
 */
const confirmDelete = ref(false)
let confirmTimer: ReturnType<typeof setTimeout> | undefined

function clearConfirm() {
  if (confirmTimer) clearTimeout(confirmTimer)
  confirmTimer = undefined
  confirmDelete.value = false
}

function askDeletePicked() {
  const list = filteredInquiries.value.filter((q) => picked.value.includes(q.id))
  if (!list.length) {
    flash('err', '还没有勾选工单')
    return
  }
  if (!confirmDelete.value) {
    confirmDelete.value = true
    confirmTimer = setTimeout(clearConfirm, 4000)
    return
  }
  doDeletePicked(list)
}

async function doDeletePicked(list: InquiryView[]) {
  clearConfirm()
  let ok = 0
  let fail = 0
  // 一条一条删：后端只有单条删除接口。数量本来就不大（几十条量级），
  // 逐条还能拿到每条的成败，好过一口气发个批量接口然后只知道"整体失败"。
  for (const q of list) {
    try {
      await adminApi.deleteInquiry(q.id)
      ok++
    } catch {
      fail++
    }
  }
  const gone = new Set(list.map((q) => q.id))
  picked.value = picked.value.filter((id) => !gone.has(id))
  await loadInquiries()
  if (fail) flash('err', `删除完成：成功 ${ok} 条，失败 ${fail} 条（失败的可重试）`)
  else flash('ok', `已删除 ${ok} 条工单`)
}

/** 勾选状态变了就撤销待确认，避免"勾完 A 又改成 B，结果删了旧的那一批" */
watch(picked, clearConfirm)

const EXPORT_HEADERS = [
  '提交时间',
  '姓名',
  '邮箱 / 微信',
  '项目类型',
  '预算区间',
  '项目简介',
  '状态',
  '内部备注',
  '来源 IP',
  '工单 ID'
]

function inquiryRow(q: InquiryView): (string | number)[] {
  return [
    formatDateTime(q.createdAt),
    q.name || '',
    q.contact || '',
    q.type || '',
    q.budget || '',
    q.message || '',
    STATUS_LABEL[q.status] || q.status,
    q.note || '',
    q.ip || '',
    q.id
  ]
}

function exportPicked() {
  const list = filteredInquiries.value.filter((q) => picked.value.includes(q.id))
  if (!list.length) {
    flash('err', '还没有勾选工单')
    return
  }
  downloadCsv(`工作对接-${fileStamp()}.csv`, [
    EXPORT_HEADERS,
    ...list.map(inquiryRow)
  ])
  flash('ok', `已导出 ${list.length} 条为表格文件`)
}

function exportAll() {
  const list = filteredInquiries.value
  if (!list.length) {
    flash('err', '当前列表没有内容')
    return
  }
  downloadCsv(`工作对接-${fileStamp()}.csv`, [
    EXPORT_HEADERS,
    ...list.map(inquiryRow)
  ])
  flash('ok', `已导出当前列表 ${list.length} 条`)
}

const inboxCounts = computed(() => ({
  all: inquiries.value.length,
  new: inquiries.value.filter((q) => q.status === 'new').length,
  contacted: inquiries.value.filter((q) => q.status === 'contacted').length,
  archived: inquiries.value.filter((q) => q.status === 'archived').length
}))

async function setStatus(q: InquiryView, s: InquiryStatus) {
  try {
    const updated = await adminApi.patchInquiry(q.id, { status: s })
    const i = inquiries.value.findIndex((x) => x.id === q.id)
    if (i >= 0) inquiries.value[i] = updated
    flash('ok', `已标记为「${STATUS_LABEL[s]}」`)
  } catch (e: any) {
    flash('err', e?.message || '更新失败')
  }
}

async function saveNote(q: InquiryView) {
  try {
    const updated = await adminApi.patchInquiry(q.id, { note: q.note || '' })
    const i = inquiries.value.findIndex((x) => x.id === q.id)
    if (i >= 0) inquiries.value[i] = updated
    flash('ok', '备注已保存')
  } catch (e: any) {
    flash('err', e?.message || '保存失败')
  }
}

async function removeInquiry(q: InquiryView) {
  if (!confirm(`删除来自「${q.name || '匿名'}」的这条需求？`)) return
  try {
    await adminApi.deleteInquiry(q.id)
    inquiries.value = inquiries.value.filter((x) => x.id !== q.id)
    flash('ok', '已删除')
  } catch (e: any) {
    flash('err', e?.message || '删除失败')
  }
}

/* ------------------------------ 模块操作 ------------------------------ */
function newId(type: string) {
  let n = 1
  let id = `${type}${n}`
  const exists = (x: string) => modules.value.some((m) => m.id === x)
  while (exists(id)) id = `${type}${++n}`
  return id
}

function addModule(type: string) {
  if (!content.value) return
  const def = typeDef(type)
  const mod: PortfolioModule = {
    id: newId(type),
    type: type as any,
    visible: true,
    flat: false,
    size: 'auto',
    showNav: true,
    navLabel: def?.label || type,
    eyebrow: '',
    title: def?.label || type,
    lead: ''
  } as PortfolioModule
  if (type === 'stack' || type === 'custom') (mod as any).media = []
  if (type === 'cases') (mod as any).cases = []
  if (type === 'contact') (mod as any).rows = []
  if (type === 'custom') (mod as any).columns = '2'
  content.value.modules.push(mod)
  selectedId.value = mod.id
  flash('ok', `已添加「${def?.label || type}」模块，完善内容后记得保存`)
}

function moveModule(i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= modules.value.length) return
  const arr = modules.value
  const t = arr[i]
  arr[i] = arr[j]
  arr[j] = t
}

function removeModule(id: string) {
  if (!content.value) return
  if (!confirm(`确定删除模块「${id}」？删除后需要点保存才会生效。`)) return
  content.value.modules = content.value.modules.filter((m) => m.id !== id)
  if (selectedId.value === id) selectedId.value = content.value.modules[0]?.id || ''
}

function changeId(oldId: string, newId: string) {
  if (!content.value) return
  const trimmed = newId.trim()
  if (!trimmed || modules.value.some((m) => m.id === trimmed && m.id !== oldId)) return
  modules.value.forEach((m) => { if (m.id === oldId) m.id = trimmed })
  selectedId.value = trimmed
}

/* ------------------------------ 保存 / 导入导出 ------------------------------ */
async function save() {
  if (!content.value || saving.value) return
  saving.value = true
  try {
    const res = await adminApi.saveContent(content.value)
    flash('ok', `已保存，内容版本 v${res.version}`)
    await Promise.all([loadStatus(), loadRevisions()])
  } catch (e: any) {
    flash('err', e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function exportJson() {
  const blob = new Blob([JSON.stringify(content.value, null, 2)], {
    type: 'application/json'
  })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `content-${Date.now()}.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

function importJson(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => {
    try {
      const parsed = JSON.parse(String(reader.result))
      if (!parsed.site || !Array.isArray(parsed.modules)) throw new Error('结构不正确')
      content.value = parsed
      selectedId.value = parsed.modules[0]?.id || ''
      flash('ok', '已载入到本地，检查无误后点「保存」')
    } catch (err: any) {
      flash('err', '导入失败：' + (err?.message || '不是合法的内容 JSON'))
    }
  }
  reader.readAsText(f)
  input.value = ''
}

async function rollback(id: number) {
  if (!confirm('回滚到这份快照？当前未保存的改动会被覆盖。')) return
  try {
    const res = await adminApi.rollback(id)
    content.value = await fetchRemote()
    flash('ok', `已回滚，当前版本 v${res.version}`)
    await Promise.all([loadStatus(), loadRevisions()])
  } catch (e: any) {
    flash('err', e?.message || '回滚失败')
  }
}

/* ------------------------------ 素材库 / 账号 ------------------------------ */
const mediaList = ref<MediaItemView[]>([])

/* ------------------------------ 素材库（需求 3） ------------------------------ */
/**
 * 素材库按「照片 / 视频」分开看，并标出每条被引用了几次。
 *
 * 引用计数是纯前端算的：直接扫一遍当前内容里所有 src / poster，
 * 不新增接口。引用为 0 的就是「没人用的孤儿素材」，可以放心清理。
 */
const mediaFilter = ref<'all' | 'image' | 'video' | 'unused'>('all')
const mediaSort = ref<'new' | 'size'>('new')
const mediaQuery = ref('')

/* ------------------------------ 素材标签 ------------------------------ */
/**
 * 标签筛选支持多选，取的是「同时具备」：
 * 勾「直播」+「封面」得到的是既直播又是封面的那一小撮。
 * 选就是 Filtering by narrowing 的直觉 —— 每多勾一个，结果只会更少而不会更多。
 * 想要「直播或者封面」，分两次看就行。
 */
const mediaTags = ref<MediaTagView[]>([])
const tagFilter = ref<string[]>([])

function toggleTagFilter(name: string) {
  const s = tagFilter.value.slice()
  const i = s.indexOf(name)
  if (i >= 0) s.splice(i, 1)
  else s.push(name)
  tagFilter.value = s
}

const assetsOf = (m: MediaItemView) => m.tags || []
const hasTagFilter = computed(() => tagFilter.value.length > 0)

/** 素材 →「标签」按钮。单条走「以此为准」，批量走「按需追加」，差别在 MediaTagEditor 里说明 */
const tagEditorOpen = ref(false)
const tagEditorItems = ref<MediaItemView[]>([])
function openTagEditor(items: MediaItemView[]) {
  if (!items.length) return
  tagEditorItems.value = items
  tagEditorOpen.value = true
}
const tagManagerOpen = ref(false)

async function loadTags() {
  try { mediaTags.value = await adminApi.listTags(true) } catch { /* ignore */ }
}

function collectSrcs(node: any, out: Map<string, number>) {
  if (!node || typeof node !== 'object') return
  if (Array.isArray(node)) {
    node.forEach((n) => collectSrcs(n, out))
    return
  }
  for (const [k, v] of Object.entries(node)) {
    if ((k === 'src' || k === 'poster') && typeof v === 'string' && v) {
      out.set(v, (out.get(v) || 0) + 1)
    } else if (v && typeof v === 'object') {
      collectSrcs(v, out)
    }
  }
}

const mediaUsage = computed(() => {
  const m = new Map<string, number>()
  collectSrcs(content.value, m)
  return m
})

const isVideoAsset = (m: MediaItemView) => (m.mime || '').startsWith('video/')

const mediaSummary = computed(() => {
  let img = 0
  let vid = 0
  let bytes = 0
  for (const m of mediaList.value) {
    bytes += Number(m.size || 0)
    if (isVideoAsset(m)) vid++
    else img++
  }
  return { img, vid, bytes }
})

const mediaUnusedCount = computed(
  () => mediaList.value.filter((m) => !mediaUsage.value.has(m.url)).length
)

const mediaFiltered = computed(() => {
  let list = mediaList.value.slice()
  if (mediaFilter.value === 'image') list = list.filter((m) => !isVideoAsset(m))
  else if (mediaFilter.value === 'video') list = list.filter((m) => isVideoAsset(m))
  else if (mediaFilter.value === 'unused') {
    list = list.filter((m) => !mediaUsage.value.has(m.url))
  }

  // 标签是「与」：每多勾一个标签，结果只会更少
  if (tagFilter.value.length) {
    list = list.filter((m) => tagFilter.value.every((t) => assetsOf(m).includes(t)))
  }

  const q = mediaQuery.value.trim().toLowerCase()
  if (q) list = list.filter((m) => (m.url || '').toLowerCase().includes(q))

  list.sort((a, b) =>
    mediaSort.value === 'size'
      ? Number(b.size || 0) - Number(a.size || 0)
      : String(b.createdAt || '').localeCompare(String(a.createdAt || ''))
  )
  return list
})

function humanSize(n?: number) {
  const b = Number(n || 0)
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(0) + ' KB'
  return (b / 1024 / 1024).toFixed(1) + ' MB'
}

async function loadMedia() {
  try { mediaList.value = await adminApi.listMedia(true) } catch { /* ignore */ }
  await loadTags()
}

/**
 * 素材库被改动后，本地那份 `content` 也要跟着刷新。
 * ---------------------------------------------------------------------------
 * 删素材时后端会把模块里的引用摘掉并存成新版本，但后台内存里的 content 还是旧的，
 * 不重新拉一次的话「被 N 处引用」那个角标会继续显示 —— 看着就像删除没生效。
 */
async function reloadContent() {
  try { content.value = await fetchRemote() } catch { /* 拉不到就保留现状 */ }
}

/* --------------------- 批量选择与批量操作 --------------------- */
const mediaSelected = ref<Set<string>>(new Set())
const mediaBusy = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const selectedPaths = computed(() => Array.from(mediaSelected.value))
const selectedCount = computed(() => mediaSelected.value.size)
/** 批量操作要拿到素材对象本身（打标签时需要它们现有的 tags），不只是路径 */
const selectedItems = computed(() =>
  mediaList.value.filter((m) => mediaSelected.value.has(m.path))
)

function toggleSelect(path: string) {
  const s = new Set(mediaSelected.value)
  if (s.has(path)) s.delete(path)
  else s.add(path)
  mediaSelected.value = s
}

/** 全选勾的是「当前筛选出来的」而不是全库 —— 过滤到第 3 页时不会误伤看不见的素材 */
const allVisibleSelected = computed(
  () => mediaFiltered.value.length > 0 && mediaFiltered.value.every((m) => mediaSelected.value.has(m.path))
)
function toggleSelectAllVisible() {
  const s = new Set(mediaSelected.value)
  if (allVisibleSelected.value) mediaFiltered.value.forEach((m) => s.delete(m.path))
  else mediaFiltered.value.forEach((m) => s.add(m.path))
  mediaSelected.value = s
}
function clearSelection() {
  mediaSelected.value = new Set()
}

async function removeSelected() {
  const paths = selectedPaths.value
  if (!paths.length) return
  if (!confirm(`删除选中的 ${paths.length} 个素材？它们在页面里被引用的地方会一并摘掉。`)) return
  mediaBusy.value = true
  try {
    const r = await adminApi.deleteMediaBatch(paths)
    clearSelection()
    await Promise.all([loadMedia(), reloadContent()])
    let msg = `已删除 ${r.deleted} 个素材`
    if (r.referencesRemoved) msg += `，同步移除 ${r.referencesRemoved} 处引用`
    if (r.failed.length) msg += `，${r.failed.length} 个已不存在`
    flash('ok', msg)
  } catch (e: any) {
    flash('err', e?.message || '批量删除失败')
  } finally {
    mediaBusy.value = false
  }
}

/** 批量导入进度，例如「第 2/7 批（共 34 个文件 · 1.2 GB）」 */
const uploadHint = ref('')

/** 批量导入：自动分批，一批失败不连累其余，最后统一给结论 */
async function importFiles(files: FileList | null) {
  const list = files ? Array.from(files) : []
  // 先清空 input —— 否则连续两次选同一批文件不会再触发 change
  if (fileInput.value) fileInput.value.value = ''
  if (!list.length) return

  const totalBytes = list.reduce((n, f) => n + f.size, 0)
  const label = `${list.length} 个文件 · ${humanSize(totalBytes)}`
  mediaBusy.value = true
  uploadHint.value = `准备上传 ${label}…`
  try {
    const items = await adminApi.uploadMany(list, [], (done, total) => {
      uploadHint.value = `第 ${done}/${total} 批 · ${label}`
    })
    await finishImport(items)
  } catch (e: any) {
    // 分批之后仍有可能是「一半成功一半失败」：把成功的那半个照样入库、照样勾上，
    // 再把失败的那几个批次点名告诉用户 —— 总比整批作废、让他从头再传一遍强
    const partial: MediaBatchUploadItem[] = e?.partial || []
    if (partial.length) {
      await finishImport(partial)
      flash('err', e?.message || '部分批次上传失败')
    } else {
      flash('err', e?.message || '上传失败')
    }
  } finally {
    mediaBusy.value = false
    uploadHint.value = ''
  }
}

/** 上传收尾：刷新列表 → 汇总成败 → 自动勾上新素材（下一步多半就是打标签）→ 转述后处理的提醒 */
async function finishImport(items: MediaBatchUploadItem[]) {
  const bad = items.filter((i) => i.error)
  const okN = items.length - bad.length
  await loadMedia()

  let msg = `上传完成：${okN} 个成功`
  if (bad.length) {
    msg += `，${bad.length} 个失败：`
      + bad.slice(0, 3).map((i) => `${i.name}（${i.error}）`).join('；')
      + (bad.length > 3 ? ` 等 ${bad.length} 个` : '')
  }
  flash(bad.length && !okN ? 'err' : 'ok', msg)

  const urls = new Set(items.filter((i) => i.url).map((i) => i.url as string))
  if (urls.size) {
    mediaSelected.value = new Set(
      mediaList.value.filter((m) => urls.has(m.url)).map((m) => m.path)
    )
  }

  const warned = items.find((i) => i.warning)
  if (warned) flash('err', `${warned.name}：${warned.warning}`)
}

async function removeMedia(path: string) {
  if (!confirm('删除素材？页面里引用它的位置会被一并摘掉。')) return
  try {
    const r = await adminApi.deleteMedia(path)
    await Promise.all([loadMedia(), reloadContent()])
    flash('ok', r.referencesRemoved
      ? `已删除，并同步移除 ${r.referencesRemoved} 处引用`
      : '已删除（没有任何模块引用它）')
  } catch (e: any) {
    flash('err', e?.message || '删除失败')
  }
}

/** 标签被改动后：素材列表里嵌着每条的 tags，标签清单本身的计数也变了，两个都得重拉 */
async function onTagsSaved() {
  clearSelection()
  await Promise.all([loadMedia(), loadTags()])
  flash('ok', '标签已更新')
}

/** 一键清掉没人引用的素材 —— 删除不可恢复，所以二次确认 + 列出条数 */
async function cleanUnused() {
  const unused = mediaList.value.filter((m) => !mediaUsage.value.has(m.url))
  if (!unused.length) return flash('ok', '没有未使用的素材')
  if (!confirm(`将删除 ${unused.length} 个未被任何模块引用的素材，且不可恢复。确定继续？`)) return
  mediaBusy.value = true
  try {
    const r = await adminApi.deleteMediaBatch(unused.map((m) => m.path))
    clearSelection()
    await Promise.all([loadMedia(), reloadContent()])
    flash('ok', `已清理 ${r.deleted} 个未使用素材`)
  } catch (e: any) {
    flash('err', e?.message || '清理失败')
  } finally {
    mediaBusy.value = false
  }
}

const pw = ref({ old: '', next: '' })
async function changePassword() {
  try {
    await adminApi.changePassword(pw.value.old, pw.value.next)
    pw.value = { old: '', next: '' }
    flash('ok', '密码已更新，下次登录请使用新密码')
  } catch (e: any) {
    flash('err', e?.message || '修改失败')
  }
}

function logout() {
  adminApi.logout().catch(() => {})
  tokenStore.clear()
  router.replace('/login')
}

onMounted(() => { loadMedia() })
</script>

<template>
  <div class="admin">
    <!-- 顶栏 -->
    <header class="admin-bar glass">
      <RouterLink to="/" class="admin-brand">
        <span class="nav__mark">{{ content?.site.brandMark || 'C' }}</span>
        内容后台
      </RouterLink>

      <nav class="admin-tabs">
        <button :class="{ on: tab === 'modules' }" @click="tab = 'modules'">模块</button>
        <button :class="{ on: tab === 'site' }" @click="tab = 'site'">站点信息</button>
        <button :class="{ on: tab === 'media' }" @click="tab = 'media'; loadMedia()">素材库</button>
        <button :class="{ on: tab === 'stats' }" @click="tab = 'stats'; loadStats()">监控</button>
        <button :class="{ on: tab === 'inbox' }" @click="tab = 'inbox'; loadInquiries()">
          工作对接<template v-if="inboxCounts.new"> · {{ inboxCounts.new }} 新</template>
        </button>
        <button :class="{ on: tab === 'account' }" @click="tab = 'account'">账号</button>
      </nav>

      <div class="admin-bar__right">
        <span v-if="status" class="badge" :class="status.redis">
          {{ status.redis === 'up' ? 'Redis 正常' : status.redis === 'down' ? 'Redis 已降级' : 'Redis 关闭' }}
        </span>
        <span v-if="status" class="badge">v{{ status.contentVersion }}</span>
        <button class="mini" @click="exportJson">导出 JSON</button>
        <label class="mini file-btn">
          导入
          <input type="file" accept="application/json" hidden @change="importJson" />
        </label>
        <a class="mini" href="/" target="_blank" rel="noreferrer">查看前台 ↗</a>
        <button class="btn btn-sm" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button class="mini danger" @click="logout">登出</button>
      </div>
    </header>

    <p v-if="notice" class="notice" :class="notice.kind">{{ notice.text }}</p>
    <div v-if="loading" class="admin-empty">载入中…</div>

    <div v-else-if="content" class="admin-body">
      <!-- 模块 tab -->
      <template v-if="tab === 'modules'">
        <aside class="mod-list glass">
          <div class="mod-list__head">
            <b>模块 ({{ modules.length }})</b>
            <select @change="addModule(($event.target as HTMLSelectElement).value); ($event.target as HTMLSelectElement).value = ''">
              <option value="">+ 新增模块</option>
              <option v-for="t in TYPE_DEFS" :key="t.type" :value="t.type">{{ t.label }}</option>
            </select>
          </div>

          <div class="mod-list__items">
            <div
              v-for="(m, i) in modules"
              :key="m.id"
              class="mod-item"
              :class="{ on: selectedId === m.id, off: m.visible === false }"
              @click="selectedId = m.id"
            >
              <span class="dot" :class="{ hidden: m.visible === false }"></span>
              <span class="mod-item__text">
                <b>{{ m.navLabel || m.title || m.id }}</b>
                <i>{{ typeDef(m.type)?.label || m.type }} · {{ m.id }}</i>
              </span>
              <span class="row-tools">
                <button class="mini icon" @click.stop="moveModule(i, -1)" :disabled="i === 0">↑</button>
                <button class="mini icon" @click.stop="moveModule(i, 1)" :disabled="i === modules.length - 1">↓</button>
                <button class="mini danger" @click.stop="removeModule(m.id)">✕</button>
              </span>
            </div>
          </div>

          <p class="small mod-list__tip">
            用 ↑↓ 调整顺序；左侧小圆点变空心表示已从前台隐藏。
          </p>
        </aside>

        <section class="editor glass">
          <div v-if="current" class="editor-inner">
            <div class="editor-head">
              <label class="f inline">
                <span>模块 ID（导航锚点）</span>
                <input :value="current.id" @change="changeId(current.id, ($event.target as HTMLInputElement).value)" />
              </label>
              <label class="f inline">
                <span>类型</span>
                <b class="readonly">{{ currentDef?.label || current.type }}</b>
              </label>
            </div>
            <p v-if="currentDef?.hint" class="small">{{ currentDef.hint }}</p>

            <FieldsEditor :fields="currentDef?.fields || []" :model="current as any" />

            <div v-if="!currentDef" class="raw-json">
              <p class="small">
                这个类型还没写表单描述，下面直接编辑原始数据（前台会用通用方式渲染）。
              </p>
              <textarea
                :value="JSON.stringify(current, null, 2)"
                rows="14"
                @change="
                  Object.assign(
                    current as any,
                    JSON.parse(($event.target as HTMLTextAreaElement).value)
                  )
                "
              ></textarea>
            </div>
          </div>
          <div v-else class="admin-empty">左侧选择一个模块，或新增一个。</div>
        </section>
      </template>

      <!-- 站点 tab -->
      <section v-else-if="tab === 'site'" class="editor glass wide">
        <h2 class="t-md">站点信息</h2>
        <FieldsEditor :fields="SITE_FIELDS" :model="content.site as any" />

        <h2 class="t-md" style="margin-top: 32px">
          历史快照<i class="f-note">共 {{ revisions.length }} 条 · 自动保留最近 10 条</i>
        </h2>
        <p class="small">
          每次保存都会留一份快照，改错了可以回滚。内容没变化不会重复存；
          超过 10 条会自动删掉最老的，所以这里最多回退 10 步。
        </p>
        <div v-for="r in revisions" :key="r.id" class="rev-row">
          <span>{{ new Date(r.createdAt).toLocaleString('zh-CN') }}</span>
          <i>{{ r.moduleCount }} 个模块 · {{ Math.round(r.bytes / 1024) }} KB</i>
          <button class="mini" @click="rollback(r.id)">回滚</button>
        </div>
      </section>

      <!-- 素材 tab -->
      <section v-else-if="tab === 'media'" class="editor glass wide">
        <h2 class="t-md">素材库</h2>
        <p class="small">
          素材存在服务器的持久卷里，<b>不在 Git 仓库中</b>，重新部署不会丢。
          共 {{ mediaSummary.img }} 张照片 · {{ mediaSummary.vid }} 个视频 ·
          合计 {{ humanSize(mediaSummary.bytes) }}。
        </p>

        <div class="row-tools" style="margin: 12px 0">
          <button class="mini" :class="{ on: mediaBusy }" :disabled="mediaBusy" @click="fileInput?.click()">
            {{ mediaBusy ? '处理中…' : '批量导入（可多选文件）' }}
          </button>
          <input
            ref="fileInput"
            type="file"
            multiple
            accept="image/jpeg,image/png,image/gif,image/webp,video/mp4,video/webm"
            class="visually-hidden"
            @change="importFiles(($event.target as HTMLInputElement).files)"
          />
          <span v-if="uploadHint" class="small upload-hint">{{ uploadHint }}</span>
          <span v-else class="small">图片 / 视频均可，一次可以选多个；重名的会自动秒传去重。</span>
        </div>

        <!-- 标签筛选：多选 = 同时具备，每多勾一个结果只会更少 -->
        <div class="tag-row">
          <span class="tag-row__lab">标签</span>
          <button class="tag-chip" :class="{ 'tag-chip--on': !hasTagFilter }" @click="tagFilter = []">
            全部
          </button>
          <button
            v-for="t in mediaTags"
            :key="t.name"
            class="tag-chip"
            :class="{ 'tag-chip--on': tagFilter.includes(t.name) }"
            @click="toggleTagFilter(t.name)"
          >
            {{ t.name }}<i>{{ t.count }}</i>
          </button>
          <span v-if="!mediaTags.length" class="small">
            还没建过标签 —— 用素材卡片上的「标签」按钮新建一个。
          </span>
          <span v-if="hasTagFilter" class="small">
            只看同时带 {{ tagFilter.map((t) => `「${t}」`).join(' + ') }} 的素材
          </span>
          <button class="mini push" @click="tagManagerOpen = true">管理标签</button>
        </div>

        <div class="filter-row">
          <button class="mini" :class="{ on: mediaFilter === 'all' }" @click="mediaFilter = 'all'">
            全部 ({{ mediaList.length }})
          </button>
          <button class="mini" :class="{ on: mediaFilter === 'image' }" @click="mediaFilter = 'image'">
            照片 ({{ mediaSummary.img }})
          </button>
          <button class="mini" :class="{ on: mediaFilter === 'video' }" @click="mediaFilter = 'video'">
            视频 ({{ mediaSummary.vid }})
          </button>
          <button class="mini" :class="{ on: mediaFilter === 'unused' }" @click="mediaFilter = 'unused'">
            未使用 ({{ mediaUnusedCount }})
          </button>
          <button class="mini" @click="toggleSelectAllVisible">
            {{ allVisibleSelected ? '取消全选' : `全选当前 (${mediaFiltered.length})` }}
          </button>
          <select v-model="mediaSort" class="push">
            <option value="new">最新在前</option>
            <option value="size">体积从大到小</option>
          </select>
          <input v-model="mediaQuery" class="mini-input" placeholder="搜索文件名…" />
        </div>

        <div class="media-grid-lib">
          <div
            v-for="m in mediaFiltered"
            :key="m.path"
            class="lib-item"
            :class="{ 'lib-item--on': mediaSelected.has(m.path) }"
            @click="toggleSelect(m.path)"
          >
            <label class="lib-check" @click.stop>
              <input type="checkbox" :checked="mediaSelected.has(m.path)" @change="toggleSelect(m.path)" />
            </label>
            <video v-if="isVideoAsset(m)" :src="m.url" muted playsinline preload="metadata"></video>
            <img v-else :src="m.url" alt="" loading="lazy" />
            <span class="lib-item__kind">{{ isVideoAsset(m) ? '▶ 视频' : '🖼 照片' }}</span>
            <span class="lib-item__size">{{ humanSize(m.size) }}</span>
            <div v-if="m.tags?.length" class="lib-item__tags">
              <span v-for="t in m.tags" :key="t">{{ t }}</span>
            </div>
            <div class="lib-item__bar">
              <i>{{ m.url }}</i>
              <span class="row-tools">
                <b v-if="mediaUsage.get(m.url)" class="lib-use">被 {{ mediaUsage.get(m.url) }} 处引用</b>
                <b v-else class="lib-use lib-use--none">未使用</b>
                <button class="mini" @click.stop="openTagEditor([m])">
                  标签<template v-if="m.tags?.length">({{ m.tags.length }})</template>
                </button>
                <a class="mini" :href="m.url" target="_blank" rel="noreferrer" @click.stop>打开</a>
                <button class="mini danger" @click.stop="removeMedia(m.path)">删除</button>
              </span>
            </div>
          </div>
        </div>

        <p v-if="!mediaList.length" class="small">还没有素材，用上面的「批量导入」或去「模块」里上传。</p>
        <p v-else-if="!mediaFiltered.length" class="small">没有符合条件的文件。</p>

        <div class="row-tools" style="margin-top: 14px">
          <button class="mini" @click="loadMedia">刷新</button>
          <button class="mini danger" :disabled="mediaBusy" @click="cleanUnused">清理未使用素材</button>
        </div>

        <!-- 选中若干条后浮出操作条 —— 没选中时不占版面 -->
        <Transition name="pop">
          <div v-if="selectedCount" class="bulk-bar glass">
            <span>已选中 <b>{{ selectedCount }}</b> 个素材</span>
            <span class="row-tools">
              <button class="mini" @click="clearSelection">取消选择</button>
              <button class="mini" :disabled="mediaBusy" @click="openTagEditor(selectedItems)">
                打标签
              </button>
              <button class="mini danger" :disabled="mediaBusy" @click="removeSelected">
                {{ mediaBusy ? '删除中…' : `删除所选 (${selectedCount})` }}
              </button>
            </span>
          </div>
        </Transition>

        <!-- 打标签 / 管理标签。放 .glass 外面会被 backdrop-filter 拽偏，两个组件内部都 Teleport 到 body 了 -->
        <MediaTagEditor
          :open="tagEditorOpen"
          :items="tagEditorItems"
          :all-tags="mediaTags"
          @close="tagEditorOpen = false"
          @saved="onTagsSaved"
        />
        <MediaTagManager
          :open="tagManagerOpen"
          :tags="mediaTags"
          @close="tagManagerOpen = false"
          @changed="onTagsSaved"
        />
      </section>

      <!-- 监控 tab -->
      <section v-else-if="tab === 'stats'" class="editor glass wide">
        <div class="sec-head">
          <h2 class="t-md">监控</h2>
          <div class="row-tools">
            <select v-model.number="statsDays" @change="loadStats">
              <option :value="7">近 7 天</option>
              <option :value="14">近 14 天</option>
              <option :value="30">近 30 天</option>
            </select>
            <button class="mini" @click="loadStats">刷新</button>
          </div>
        </div>
        <p class="small">
          只统计匿名会话：IP 只存哈希，不采集身份信息。停留时长由前端心跳累加，关页面时会补最后一拍。
        </p>

        <template v-if="stats">
          <div class="stat-cards">
            <div class="stat-card">
              <b>{{ stats.today.visitors }}</b>
              <span>今日访客</span>
              <i>{{ stats.today.sessions }} 次访问</i>
              <em :class="deltaClass(stats.todayDelta?.visitors)">{{ deltaArrow(stats.todayDelta?.visitors) }}</em>
            </div>
            <div class="stat-card">
              <b>{{ humanDuration(stats.today.avgDurationMs) }}</b>
              <span>平均停留</span>
              <i>单次访问</i>
              <em :class="deltaClass(stats.todayDelta?.avgDurationMs)">{{ deltaArrow(stats.todayDelta?.avgDurationMs) }}</em>
            </div>
            <div class="stat-card">
              <b>{{ stats.today.plays }}</b>
              <span>作品播放</span>
              <i>{{ stats.today.playedSessions }} 人点开过</i>
              <em :class="deltaClass(stats.todayDelta?.plays)">{{ deltaArrow(stats.todayDelta?.plays) }}</em>
            </div>
            <div class="stat-card">
              <b>{{ stats.today.playRate }}%</b>
              <span>播放率</span>
              <i>点开作品 / 总访问</i>
              <em :class="deltaClass(stats.todayDelta?.playRate)">{{ deltaArrow(stats.todayDelta?.playRate) }}</em>
            </div>
            <div class="stat-card">
              <b>{{ stats.inquiry.new }}</b>
              <span>待处理需求</span>
              <i>共 {{ stats.inquiry.total }} 条</i>
            </div>
          </div>

          <!-- 图表板块：趋势 / 热力 / 地域 / 设备来源 / 漏斗（手写 SVG，见 StatsCharts） -->
          <StatsCharts :stats="stats" :human-duration="humanDuration" />

          <div class="two-col">
            <div>
              <h3 class="sub">播放最多的作品</h3>
              <div v-for="p in stats.topPlays" :key="p.target" class="play-row">
                <span class="play-row__k">{{ p.target }}</span>
                <b>{{ p.count }}</b>
              </div>
              <p v-if="!stats.topPlays.length" class="small">还没有播放记录。</p>
            </div>

            <div>
              <h3 class="sub">最近访问</h3>
              <div v-for="v in stats.recent" :key="v.id" class="visit-row">
                <span class="mono small">{{ v.startedAt?.slice(11, 19) || '—' }}</span>
                <b>{{ humanDuration(v.durationMs) }}</b>
                <i>{{ v.playCount }} 次播放</i>
                <i>{{ v.region || '未知' }} · {{ v.device || v.ua }}</i>
              </div>
              <p v-if="!stats.recent.length" class="small">暂无记录。</p>
            </div>
          </div>
        </template>
        <p v-else class="small">载入中…</p>
      </section>

      <!-- 工作对接 tab -->
      <section v-else-if="tab === 'inbox'" class="editor glass wide">
        <div class="sec-head">
          <h2 class="t-md">工作对接</h2>
          <div class="row-tools">
            <button class="mini" @click="loadInquiries">刷新</button>
            <button class="mini" @click="exportPicked">
              导出所选<template v-if="pickedCount"> ({{ pickedCount }})</template>
            </button>
            <button class="mini" @click="exportAll">导出当前列表</button>
            <button
              class="mini"
              :class="{ danger: !confirmDelete, 'danger is-armed': confirmDelete }"
              @click="askDeletePicked"
            >
              <template v-if="confirmDelete">再点一次确认删除 {{ pickedCount }} 条</template>
              <template v-else>删除所选<template v-if="pickedCount"> ({{ pickedCount }})</template></template>
            </button>
          </div>
        </div>
        <p class="small">
          网站底部提交的项目需求都进这里，标记状态、写内部备注、处理完归档。
          勾选任意几条后可批量导出成表格文件（CSV，Excel / WPS 可直接打开），
          也可以批量删除 —— 删除<b>不可恢复</b>，所以删除按钮要连点两次才会执行。
        </p>

        <div class="filter-row">
          <button
            v-for="f in (['all', 'new', 'contacted', 'archived'] as const)"
            :key="f"
            class="mini"
            :class="{ on: inboxFilter === f }"
            @click="inboxFilter = f"
          >
            {{ f === 'all' ? '全部' : STATUS_LABEL[f] }} ({{ inboxCounts[f] }})
          </button>

          <span class="inbox-pickall">
            <label class="inbox-pickall__all">
              <input
                type="checkbox"
                :checked="allPicked"
                @change="toggleAll(($event.target as HTMLInputElement).checked)"
              />
              全选当前列表
            </label>
            <button v-if="pickedCount" class="mini" @click="toggleAll(false)">清空选择</button>
          </span>
        </div>

        <div v-for="q in filteredInquiries" :key="q.id" class="inbox-item" :class="`is-${q.status}`">
          <div class="inbox-item__head">
            <label class="inbox-check">
              <input
                type="checkbox"
                :checked="isPicked(q.id)"
                :aria-label="`选择工单 ${q.id}`"
                @change="togglePick(q.id, ($event.target as HTMLInputElement).checked)"
              />
              <span class="small mono">#{{ q.id }}</span>
            </label>
            <span class="tag tag--status">{{ STATUS_LABEL[q.status] }}</span>
            <span v-if="q.type" class="tag">{{ q.type }}</span>
            <span v-if="q.budget" class="tag tag--ghost">{{ q.budget }}</span>
            <span class="small push mono">{{ formatDateTime(q.createdAt) }}</span>
          </div>

          <!-- 与前台表单一一对应的字段：姓名 / 邮箱·微信 / 项目类型 / 预算区间 / 项目简介 -->
          <dl class="inbox-fields">
            <div class="inbox-f">
              <dt>姓名</dt>
              <dd>{{ q.name || '—' }}</dd>
            </div>
            <div class="inbox-f">
              <dt>邮箱 / 微信</dt>
              <dd>{{ q.contact || '—' }}</dd>
            </div>
            <div class="inbox-f">
              <dt>项目类型</dt>
              <dd>{{ q.type || '—' }}</dd>
            </div>
            <div class="inbox-f">
              <dt>预算区间</dt>
              <dd>{{ q.budget || '—' }}</dd>
            </div>
            <div class="inbox-f">
              <dt>提交时间</dt>
              <dd class="mono">{{ formatDateTime(q.createdAt) }}</dd>
            </div>
            <div class="inbox-f">
              <dt>来源 IP</dt>
              <dd class="mono">{{ q.ip || '—' }}</dd>
            </div>
            <div class="inbox-f inbox-f--full">
              <dt>项目简介</dt>
              <dd class="inbox-f__msg">{{ q.message || '—' }}</dd>
            </div>
          </dl>

          <div class="inbox-item__foot">
            <input
              v-model="q.note"
              class="note-input"
              placeholder="内部备注（只有你能看到），写完点「存备注」"
              @keyup.enter="saveNote(q)"
            />
            <span class="row-tools">
              <button class="mini" @click="saveNote(q)">存备注</button>
              <button v-if="q.status !== 'contacted'" class="mini" @click="setStatus(q, 'contacted')">标为已联系</button>
              <button v-if="q.status !== 'archived'" class="mini" @click="setStatus(q, 'archived')">归档</button>
              <button v-if="q.status !== 'new'" class="mini" @click="setStatus(q, 'new')">退回未读</button>
              <button class="mini danger" @click="removeInquiry(q)">删除</button>
            </span>
          </div>
        </div>

        <p v-if="!filteredInquiries.length" class="small">
          {{ inboxFilter === 'all' ? '还没有收到需求。' : '这个状态下没有内容。' }}
        </p>
      </section>

      <!-- 账号 tab -->
      <section v-else class="editor glass narrow">
        <h2 class="t-md">修改密码</h2>
        <label class="f">
          <span>当前密码</span>
          <input v-model="pw.old" type="password" />
        </label>
        <label class="f">
          <span>新密码（至少 8 位）</span>
          <input v-model="pw.next" type="password" />
        </label>
        <button class="btn" @click="changePassword" :disabled="!pw.old || !pw.next">更新密码</button>

        <div class="status-box">
          <div v-if="status">
            <span>内容版本</span><b>v{{ status.contentVersion }}</b>
          </div>
          <div v-if="status">
            <span>素材数量</span><b>{{ status.mediaCount }}</b>
          </div>
          <div v-if="status">
            <span>单文件上限</span><b>{{ status.maxUploadMB }} MB</b>
          </div>
          <div v-if="status">
            <span>上传目录</span><b class="mono small">{{ status.uploadDir }}</b>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* ---------------- 素材库：勾选 + 批量操作条 ---------------- */
.media-grid-lib { position: relative; }

.lib-item { position: relative; cursor: pointer; }
.lib-item--on {
  outline: 2px solid var(--accent, #2997ff);
  outline-offset: 1px;
}

.lib-check {
  position: absolute;
  left: 8px;
  top: 8px;
  z-index: 2;
  display: flex;
}
.lib-check input {
  width: 18px;
  height: 18px;
  cursor: pointer;
  accent-color: var(--accent, #2997ff);
}

.bulk-bar {
  position: sticky;
  bottom: 16px;
  z-index: 20;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 14px;
  padding: 12px 16px;
  border-radius: 14px;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
  border: 0;
}

.pop-enter-active,
.pop-leave-active { transition: opacity .18s ease, transform .18s ease; }
.pop-enter-from,
.pop-leave-to { opacity: 0; transform: translateY(6px); }

/* 批量导入进行中的批次进度：大文件传几十秒，没有它就以为卡死了 */
.upload-hint {
  color: var(--ink-1);
  font-variant-numeric: tabular-nums;
}
</style>
