<script setup lang="ts">
/**
 * 单个素材条目：上传 / 直接填外链 / 补全标题等展示信息。
 *
 * 上传或粘贴外链后会立刻探测真实像素并回填 w/h —— 前台据此判断横竖屏，
 * 决定是走统一画框还是按原始比例展开，所以"传什么尺寸都能自己兼容"。
 */
import { ref } from 'vue'
import MediaPicker from './MediaPicker.vue'
import { adminApi } from '@/api'
import type { MediaItem, MediaItemView } from '@/types/content'
import { describeMedia, orientationOf, posterUrlOf } from '@/util/content'

const props = defineProps<{
  item: MediaItem
  /** 同一组素材里已用到的路径，选取器据此标「已在此模块」 */
  used?: string[]
}>()
const emit = defineEmits<{ 'pick-more': [MediaItemView[]] }>()

/**
 * 从素材库选取（需求 4）。
 * 第一条填进当前这个条目，其余的交给父级追加成新条目 ——
 * 这样一次勾选多条就能批量插入，不用一条条点。
 */
const pickerOpen = ref(false)
function onPickFromLibrary(list: MediaItemView[]) {
  if (!list.length) return
  const first = list[0]
  const isVideo = (first.mime || '').startsWith('video/')
  props.item.src = first.url
  props.item.kind = isVideo ? 'video' : 'image'
  if (first.width) props.item.w = first.width
  if (first.height) props.item.h = first.height
  // 标题留空才补文件名，别覆盖用户已经写好的展示信息
  if (!props.item.title) props.item.title = (first.url || '').split('/').pop() || ''
  if (list.length > 1) emit('pick-more', list.slice(1))
}

const file = ref<HTMLInputElement | null>(null)
const uploading = ref(false)
const error = ref('')
const warn = ref('')

/**
 * 用「本地文件」探测尺寸，并顺手给视频抓一张封面帧。
 * ---------------------------------------------------------------------------
 * 为什么必须用本地文件：以前是上传完再去探测远端 URL，但 mp4 如果没做
 * faststart（moov 索引在 mdat 数据之后），浏览器要把整个文件下完才有元数据。
 * 一个 40MB 的视频在手机上基本必超时 → w/h 探测不到 → 画框退回兜底的 16:9
 * → 竖屏素材被裁成横屏；同时首帧渲染不出来，卡片就是一块灰。
 * 走 objectURL 直接读本地文件，秒出结果，与文件多大、有没有 faststart 无关。
 */
function probeLocalFile(f: File, kind: string) {
  return new Promise<{ w: number; h: number; poster?: Blob } | null>((resolve) => {
    const url = URL.createObjectURL(f)
    let settled = false
    const finish = (r: { w: number; h: number; poster?: Blob } | null) => {
      if (settled) return
      settled = true
      URL.revokeObjectURL(url)
      resolve(r)
    }

    if (kind !== 'video') {
      const img = new Image()
      img.onload = () => finish({ w: img.naturalWidth, h: img.naturalHeight })
      img.onerror = () => finish(null)
      img.src = url
      setTimeout(() => finish(null), 10000)
      return
    }

    const v = document.createElement('video')
    v.preload = 'auto'
    v.muted = true
    v.playsInline = true
    v.setAttribute('playsinline', '')
    v.onerror = () => finish(null)
    v.onloadedmetadata = () => {
      const w = v.videoWidth
      const h = v.videoHeight
      if (!w || !h) return finish(null)

      // 抓封面：跳到 10% 处（避开片头黑场），画进 canvas 再导出 jpg
      let grabbed = false
      const grab = () => {
        if (grabbed) return
        grabbed = true
        try {
          const scale = Math.min(1, 720 / Math.max(w, h))
          const cw = Math.max(1, Math.round(w * scale))
          const ch = Math.max(1, Math.round(h * scale))
          const c = document.createElement('canvas')
          c.width = cw
          c.height = ch
          const ctx = c.getContext('2d')
          if (!ctx) return finish({ w, h })
          ctx.drawImage(v, 0, 0, cw, ch)
          c.toBlob((b) => finish({ w, h, poster: b || undefined }), 'image/jpeg', 0.72)
        } catch {
          finish({ w, h })
        }
      }
      v.onseeked = grab
      const t = Math.min(0.2, (v.duration || 1) * 0.1)
      try {
        v.currentTime = t
      } catch {
        grab()
      }
      setTimeout(grab, 5000) // seek 卡住的兜底
    }
    v.src = url
    setTimeout(() => finish(null), 15000)
  })
}

/** 读真实像素：图片用 Image，视频用 <video> 的 loadedmetadata */
function probe(src: string, kind: string) {
  return new Promise<{ w: number; h: number } | null>((resolve) => {
    if (!src) return resolve(null)
    const done = (w: number, h: number) => resolve(w && h ? { w, h } : null)
    const fail = () => resolve(null)

    if (kind === 'video') {
      const v = document.createElement('video')
      v.preload = 'metadata'
      v.muted = true
      v.onloadedmetadata = () => done(v.videoWidth, v.videoHeight)
      v.onerror = fail
      v.src = src
      setTimeout(fail, 8000)
      return
    }
    const img = new Image()
    img.onload = () => done(img.naturalWidth, img.naturalHeight)
    img.onerror = fail
    img.src = src
    setTimeout(fail, 8000)
  })
}

async function applyMeta(src: string, kind: string) {
  const dim = await probe(src, kind)
  if (dim) {
    props.item.w = dim.w
    props.item.h = dim.h
  }
}

async function pick(e: Event) {
  const input = e.target as HTMLInputElement
  const f = input.files?.[0]
  if (!f) return
  uploading.value = true
  error.value = ''
  warn.value = ''
  try {
    const kind = f.type.startsWith('video/') ? 'video' : 'image'
    // 先本地探测尺寸并抓封面（不依赖网络，也不受 mp4 是否 faststart 影响）
    const meta = await probeLocalFile(f, kind)

    const res = await adminApi.upload(f)
    // HEVC 之类的编码能上传成功、但访客端放不出来，服务端会带一句话回来
    if (res.warning) warn.value = res.warning
    props.item.src = res.url
    props.item.kind = kind
    if (meta?.w && meta?.h) {
      props.item.w = meta.w
      props.item.h = meta.h
    }
    // 视频抓到封面就一并上传 —— 卡片直接显示画面，而不是一块灰
    if (meta?.poster) {
      try {
        const p = await adminApi.upload(
          new File([meta.poster], 'poster.jpg', { type: 'image/jpeg' })
        )
        props.item.poster = p.url
      } catch {
        /* 封面失败不阻断主流程 */
      }
    }
    // 本地探测没拿到尺寸（格式不支持等），再退回去探远端
    if (!meta?.w || !meta?.h) await applyMeta(res.url, kind)
  } catch (err: any) {
    error.value = err?.message || '上传失败'
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function onSrcChange(v: string) {
  if (v) await applyMeta(v, props.item.kind)
}

/**
 * 「清空内容」= 只拿掉放进来的那个文件，这条条目本身留着。
 * ---------------------------------------------------------------------------
 * 以前这里挂的是 emit('remove')，父级直接 splice 掉整行 —— 于是：
 *   · 在素材列表里，点一次「删除」整张编辑卡片连同这一行一起消失；
 *   · 更糟的是后面的行会整体前移一位，正在编辑的位置突然变了，
 *     手一抖就删错行，而且删完还得重新「+ 添加素材」才能再来一次。
 * 绝大多数时候人的意图只是「换一个文件」，而不是「这一项不要了」。
 *
 * 所以现在按下只清掉素材本体（src / poster / w / h），
 * 标题、副标题、角标、画框策略、下架状态这些**手打过的东西全部留着**，
 * 面板原地保留，直接点「上传本地文件」或「从素材库选取」就能重新放一个进去。
 * 真想连这一条一起删掉，用行尾那个 ✕（要再点一次确认）。
 *
 * w/h 用 delete 而不是赋 undefined：payload 是要存进数据库的整份 JSON，
 * 留一个 `w: undefined` 字段序列化后会变成脏数据，删干净更保险。
 */
function clearContent() {
  delete props.item.src
  delete props.item.poster
  delete props.item.w
  delete props.item.h
  // 换素材等于换称重那些从文件里探测出来的展示信息，时长也一并失效
  delete props.item.dur
  error.value = ''
  warn.value = ''
  // 输入框里可能还挂着上次选的同一个文件，不清掉就不会再触发 change
  if (file.value) file.value.value = ''
}

// 只有确实放进了东西，清空按钮才可用 —— 否则它点下去没有任何视觉反馈，像坏了
const hasContent = () => !!props.item.src || !!props.item.poster

const orientText = () => {
  const o = orientationOf(props.item.w, props.item.h)
  return o === 'portrait' ? '竖屏' : o === 'landscape' ? '横屏' : o === 'square' ? '方图' : ''
}
</script>

<template>
  <div class="media-card" :class="{ 'is-hidden': !!item.hidden }">
    <div class="media-thumb">
      <!--
        这里必须按 src 再加一次 key。
        <img> 改 src 浏览器会自己去取新图，但 <video> **不会** ——
        它要求显式调用 load() 才重新加载，否则画面停在上一帧。
        素材列表是「同一行反复换素材」的高频场景：不重建元素，
        删掉一个视频后下一行的位置就会残留上一个视频的画面，
        看着就像删了没生效、或者凭空多出一块占位。
        用 :key 让 Vue 在 src 变化时直接换一个新元素，最省事也最可靠。

        另外这里刻意写成 preload="none" + poster：后台一个模块能挂几十个素材，
        preload 默认值会让浏览器给每个视频都去握手、读 moov、解首帧，
        等于后台一打开就悄悄下载几十 MB —— 这正是「后台操作卡顿」的来源。
        这里先用服务端抽好的封面顶着，只有真点了播放才去取视频本体。
      -->
      <video
        v-if="item.kind === 'video' && item.src"
        :key="'v-' + item.src"
        :src="item.src"
        :poster="item.poster || posterUrlOf(item.src)"
        muted
        playsinline
        preload="none"
      ></video>
      <img
        v-else-if="item.src"
        :key="'i-' + item.src"
        :src="item.src"
        alt=""
        loading="lazy"
        decoding="async"
      />
      <span v-else class="media-ph">{{ item.kind === 'video' ? '视频' : '图片' }}</span>
    </div>

    <div class="media-main">
      <div class="media-actions">
        <button class="mini" :disabled="uploading" @click="file?.click()">
          {{ uploading ? '上传中…' : '上传本地文件' }}
        </button>
        <input ref="file" type="file" accept="image/*,video/*" hidden @change="pick" />
        <button class="mini" @click="pickerOpen = true">从素材库选取</button>
        <input
          class="mini-input"
          v-model="item.src"
          placeholder="或粘贴外链 URL"
          @change="onSrcChange(item.src || '')"
        />
        <button
          class="mini"
          :disabled="!hasContent()"
          title="只清掉放进来的文件，标题 / 角标 / 画框等配置保留，面板不动"
          @click="clearContent"
        >
          清空内容
        </button>
      </div>
      <p class="media-clear-tip">
        清空只是解除引用，<b>素材文件本身还在素材库里</b>，随时可以再选回来。
        标题、角标、画框这些设置不受影响，重新上传或选取即可替换这一格的内容。
      </p>

      <div class="media-meta-row">
        <span class="media-dim">
          {{ describeMedia(item) }}
          <i v-if="orientText()">{{ orientText() }}</i>
        </span>
        <span class="small">画框策略决定它和横屏素材并排时怎么被裁切</span>
        <button class="mini" @click="applyMeta(item.src || '', item.kind)">重新探测尺寸</button>
      </div>

      <div class="media-grid">
        <label>
          <span>类型</span>
          <select v-model="item.kind">
            <option value="image">图片</option>
            <option value="video">视频</option>
          </select>
        </label>
        <label>
          <span>标题</span>
          <input v-model="item.title" />
        </label>
        <label>
          <span>副标题</span>
          <input v-model="item.meta" />
        </label>
        <label>
          <span>角标</span>
          <input v-model="item.badge" />
        </label>
        <label>
          <span>时长</span>
          <input v-model="item.dur" />
        </label>
        <label>
          <span>占位提示</span>
          <input v-model="item.hint" />
        </label>
        <label>
          <span>画框</span>
          <select v-model="item.fit">
            <option value="auto">自动（按真实方向）</option>
            <option value="uniform">统一画框</option>
            <option value="native">原始比例</option>
          </select>
        </label>
        <label>
          <span>宽 px</span>
          <input v-model.number="item.w" type="number" min="0" />
        </label>
        <label>
          <span>高 px</span>
          <input v-model.number="item.h" type="number" min="0" />
        </label>
      </div>

      <label class="media-toggle media-toggle--hide">
        <input v-model="item.hidden" type="checkbox" />
        <span>
          临时下架（前台隐藏）
          <b v-if="item.hidden" class="media-hidden-tag">已下架</b>
        </span>
        <small>
          勾选后前台不再显示这条素材，但文件、标题、画框等配置全部保留，
          取消勾选即可原样重新上架 —— 不用重新上传。
        </small>
      </label>

      <label v-if="item.kind === 'video'" class="media-toggle">
        <input v-model="item.sound" type="checkbox" />
        <span>允许播放声音</span>
        <small>
          浏览器禁止「带声音自动播放」，所以打开后首屏仍是静音播放，
          只是多出一个喇叭按钮，访客点一下才出声。关闭则不显示按钮。
        </small>
      </label>

      <p v-if="error" class="media-error">{{ error }}</p>
      <p v-if="warn" class="media-warn">{{ warn }}</p>
    </div>

    <MediaPicker
      :open="pickerOpen"
      :used="props.used || (item.src ? [item.src] : [])"
      @close="pickerOpen = false"
      @pick="onPickFromLibrary"
    />
  </div>
</template>

<style scoped>
.media-clear-tip {
  margin: -2px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}
.media-clear-tip b { color: var(--ink-2); font-weight: 600; }
.media-meta-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.media-dim {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px;
  border-radius: var(--r-full);
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.media-dim i { font-style: normal; opacity: .75; }
.media-toggle {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 4px 8px;
  margin-top: 8px;
  font-size: 13px;
  cursor: pointer;
}
.media-toggle input { width: 15px; height: 15px; }
.media-toggle small {
  grid-column: 2;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.5;
}
/* 下架开关：单独强调，避免和别的开关混在一起看漏 */
.media-toggle--hide {
  padding: 8px 10px;
  border-radius: var(--r-s, 10px);
  background: rgba(255, 159, 10, .1);
}
.media-hidden-tag {
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: var(--r-full, 999px);
  background: rgba(255, 159, 10, .22);
  color: #a86400;
  font-size: 11px;
  font-weight: 700;
}
/* 缩略图跟着变灰，一眼能看出哪条是下架状态 */
.media-card.is-hidden .media-thumb { position: relative; opacity: .48; filter: grayscale(.7); }
.media-card.is-hidden .media-thumb::after {
  content: '已下架';
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  padding: 4px 10px;
  border-radius: var(--r-full, 999px);
  background: rgba(0, 0, 0, .68);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
}
@media (prefers-color-scheme: dark) {
  .media-hidden-tag { color: #ffcf70; }
}
</style>
