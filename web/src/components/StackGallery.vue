<script setup lang="ts">
/**
 * StackGallery — 卡牌叠加画廊（核心交互）
 * ---------------------------------------------------------------------------
 * · 素材多时横向叠加成扇形牌；<b>点哪张</b>，哪张(Q弹)放大成主播放位并保持
 *   —— 刻意不做「悬停即展开」：鼠标从卡片上方扫过时会误触，
 *      还没滑到想看的那张、前面那张就弹出来了
 * · 点右上角 ✕ 收牌还原；支持 ← → 切换 / Esc 关闭 / 键盘 Enter、Space
 * · 选中瞬间是 squash & stretch 果冻动画，不是单纯缩放
 * · 窄屏（<620px）自动切成横向滑动 + 点击开全屏灯箱
 *
 * 同时只播一个（见 util/video.ts）：
 *   · 点开新的一张，旧的立刻停；
 *   · 整个板块滚出视口时自动收牌（收到 portfolio:video-close 事件）。
 *
 * 横竖屏兼容：
 *   · 收拢态：所有牌共用 UNIFORM_RATIO 画框 —— 竖屏和横屏混排也整齐；
 *   · 展开态：按素材真实比例 fit 进舞台，竖屏变高、横屏变宽，尺寸真的会变；
 *   · 点开即记为一次「作品播放」，进后台的监控板块。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import MediaFrame from './MediaFrame.vue'
import type { MediaItem } from '@/types/content'
import { orientationOf } from '@/util/content'
import { trackPlay } from '@/util/tracking'
import { VIDEO_CLOSE_EVENT } from '@/util/video'

const props = defineProps<{ items: MediaItem[] }>()

const root = shallowRef<HTMLElement | null>(null)
const active = ref<number | null>(null)
const carousel = ref(false)
const width = ref(0)

/** 收拢态统一画框：4:5。取这个比例是因为它对竖屏和横屏的裁切量最接近 */
const UNIFORM_RATIO = 0.8

/** 每张牌的位置/尺寸/旋转 */
type CardStyle = Record<string, string>
const styles = ref<CardStyle[]>([])

let pickTimer: ReturnType<typeof setTimeout> | null = null
const picking = ref<number | null>(null)

const N = computed(() => props.items.length)
const countText = computed(() =>
  active.value === null ? `— / ${N.value}` : `${(active.value as number) + 1} / ${N.value}`
)

/** 素材真实比例；未知时按类型猜一个，等 MediaFrame 探测到再重排 */
function ratioOf(m?: MediaItem | null): number {
  if (m?.w && m?.h && m.w > 0 && m.h > 0) return m.w / m.h
  const o = orientationOf(m?.w, m?.h)
  if (o === 'portrait') return 9 / 16
  if (o === 'square') return 1
  return m?.kind === 'video' ? 16 / 9 : 4 / 3
}

/** 把给定比例塞进 (maxW, maxH) 里，返回实际宽高 */
function fitBox(r: number, maxW: number, maxH: number) {
  const rr = Math.min(Math.max(r, 0.45), 2.4)
  let w = maxW
  let h = maxW / rr
  if (h > maxH) {
    h = maxH
    w = maxH * rr
  }
  return { w, h }
}

function layout() {
  const W = root.value?.clientWidth || width.value
  width.value = W
  if (!W || !N.value) return

  carousel.value = W < 620
  if (carousel.value) {
    const cw = Math.min(W * 0.72, 280)
    styles.value = props.items.map(() => ({
      width: `${cw.toFixed(1)}px`,
      height: `${(cw / UNIFORM_RATIO).toFixed(1)}px`,
      '--x': '0px', '--y': '0px', '--rot': '0deg', '--sc': '1', '--z': '1'
    }))
    if (root.value) root.value.style.height = ''
    return
  }

  const H = Math.max(360, Math.min(560, W * 0.42))
  if (root.value) root.value.style.height = H + 'px'

  // 收拢态：统一画框
  let cardH = Math.min(H * 0.8, 460)
  let cardW = cardH * UNIFORM_RATIO
  cardW = Math.min(cardW, Math.max(180, W * 0.26))
  cardH = cardW / UNIFORM_RATIO

  const stageW = Math.min(W - 24, 880)
  const stageH = Math.min(H - 12, stageW * 0.62)

  const maxHalf = W / 2 - 12
  const n = N.value
  let step = n > 1 ? Math.min(cardW * 0.46, (maxHalf * 2 - cardW) / (n - 1)) : 0
  step = Math.max(step, 26)

  const a = active.value
  styles.value = props.items.map((m, i) => {
    let x = 0, y = 0, rot = 0, sc = 1, z = 1
    let w = cardW, h = cardH

    if (a === null) {
      const d = i - (n - 1) / 2
      x = d * step
      y = Math.abs(d) * 7
      rot = d * 2
      sc = 1 - Math.abs(d) * 0.022
      z = 50 - Math.round(Math.abs(d))
    } else if (i === a) {
      // 展开态：按这张素材的真实比例改变尺寸
      const box = fitBox(ratioOf(m), stageW, stageH)
      w = box.w
      h = box.h
      z = 200
    } else if (i < a) {
      const dl = a - i
      x = -Math.min(maxHalf - 20, stageW / 2 + 52 + (dl - 1) * 30)
      rot = -7; sc = 0.8; y = 26; z = 120 - dl
    } else {
      const dr = i - a
      x = Math.min(maxHalf - 20, stageW / 2 + 52 + (dr - 1) * 30)
      rot = 7; sc = 0.8; y = 26; z = 120 - dr
    }

    return {
      width: `${w.toFixed(1)}px`,
      height: `${h.toFixed(1)}px`,
      '--x': `${x.toFixed(1)}px`,
      '--y': `${y.toFixed(1)}px`,
      '--rot': `${rot.toFixed(2)}deg`,
      '--sc': sc.toFixed(3),
      '--z': String(z)
    }
  })
}

function pauseVideo(index: number | null) {
  if (index === null || !root.value) return
  const cards = root.value.querySelectorAll<HTMLElement>('.sitem')
  const v = cards[index]?.querySelector('video')
  if (v) {
    v.pause()
    try { v.currentTime = 0 } catch { /* ignore */ }
  }
}

function setActive(i: number | null) {
  pauseVideo(active.value)
  active.value = i
  layout()

  if (i === null || carousel.value) return

  // 点开作品 = 一次播放，记进后台监控
  trackPlay(props.items[i]?.title || props.items[i]?.meta || `作品 ${i + 1}`)

  // 果冻动效重启
  picking.value = i
  if (pickTimer) clearTimeout(pickTimer)
  pickTimer = setTimeout(() => { picking.value = null }, 700)

  nextTick(() => {
    const cards = root.value?.querySelectorAll<HTMLElement>('.sitem')
    const card = cards?.[i]
    const v = card?.querySelector('video') as HTMLVideoElement | undefined
    if (v) {
      v.controls = true
      const p = v.play()
      if (p && p.catch) p.catch(() => { v.muted = true; v.play().catch(() => {}) })
    }
  })
}

/** 素材尺寸是异步探测到的，探测完要按真实比例重排一次 */
function relayoutWhenKnown() {
  requestAnimationFrame(() => setTimeout(layout, 30))
}

/**
 * 不做悬停自动展开。
 * ---------------------------------------------------------------------------
 * 之前是「鼠标停 110ms 就展开」，横向扫过一排牌时会连着误触好几张，
 * 还没滑到目标那张，前面的就弹出来开始播了。现在只有明确的点击才展开。
 */
function onCardClick(e: MouseEvent, i: number) {
  if ((e.target as HTMLElement).closest('.sitem__close')) {
    e.stopPropagation()
    setActive(null)
    return
  }
  e.preventDefault()
  if (carousel.value) openLightbox(props.items[i])
  else setActive(i === active.value ? null : i)
}

function step(dir: number) {
  if (dir === 0) { setActive(null); return }
  const cur = active.value === null ? 0 : active.value
  const next = ((cur + dir) % N.value + N.value) % N.value
  setActive(next)
}

function onKey(e: KeyboardEvent) {
  if (lightboxOpen.value) {
    if (e.key === 'Escape') closeLightbox()
    return
  }
  if (active.value === null) return
  if (e.key === 'Escape') setActive(null)
  else if (e.key === 'ArrowRight') step(1)
  else if (e.key === 'ArrowLeft') step(-1)
}

/* ---------------- 窄屏灯箱 ---------------- */
const lightboxOpen = ref(false)
const lightboxItem = ref<MediaItem | null>(null)

function openLightbox(m: MediaItem) {
  lightboxItem.value = m
  lightboxOpen.value = true
  document.body.style.overflow = 'hidden'
  trackPlay(m?.title || m?.meta || '作品')
}
function closeLightbox() {
  lightboxOpen.value = false
  lightboxItem.value = null
  document.body.style.overflow = ''
}

/* ---------------- 自动收牌：滚出视口 / 别的视频抢占 ---------------- */
/**
 * 板块滚出视口就收牌。
 * ---------------------------------------------------------------------------
 * 单靠视频元素自己的可见性判断不够：展开后的卡片会超出画廊容器，
 * 用户往下滑时卡片可能还露着一半、但整个作品区已经离场了，
 * 所以这里对整个板块再观察一次。
 */
let outIo: IntersectionObserver | null = null
function watchOutOfView() {
  if (!('IntersectionObserver' in window) || !root.value) return
  outIo?.disconnect()
  outIo = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (!e.isIntersecting || e.intersectionRatio < 0.25) {
          if (active.value !== null) setActive(null)
        }
      }
    },
    { threshold: [0, 0.25, 0.6] }
  )
  outIo.observe(root.value)
}

/** 全局治理把本画廊里的视频关掉了 → 跟着收牌，保持视觉一致 */
function onVideoClose(e: Event) {
  const v = (e as CustomEvent<{ video: HTMLVideoElement }>).detail?.video
  if (!v || active.value === null) return
  if (root.value?.contains(v)) setActive(null)
}

/* ---------------- 生命周期 ---------------- */
let ro: ResizeObserver | null = null
let resizeTimer: ReturnType<typeof setTimeout> | null = null

onMounted(() => {
  layout()
  watchOutOfView()
  window.addEventListener(VIDEO_CLOSE_EVENT, onVideoClose)
  if ('ResizeObserver' in window && root.value) {
    ro = new ResizeObserver(() => {
      if (resizeTimer) clearTimeout(resizeTimer)
      resizeTimer = setTimeout(layout, 120)
    })
    ro.observe(root.value)
  }
  window.addEventListener('resize', onResize)
  window.addEventListener('keydown', onKey)
  // 图片/视频陆续加载完才知道真实尺寸，分几次重排把版面收敛到最终形态
  relayoutWhenKnown()
  setTimeout(layout, 600)
  setTimeout(layout, 1800)
})

function onResize() {
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(layout, 120)
}

onBeforeUnmount(() => {
  ro?.disconnect()
  outIo?.disconnect()
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', onKey)
  window.removeEventListener(VIDEO_CLOSE_EVENT, onVideoClose)
  if (pickTimer) clearTimeout(pickTimer)
  document.body.style.overflow = ''
})
</script>

<template>
  <div class="stack-wrap">
    <div
      ref="root"
      class="stack stack-ready"
      :class="{ 'stack--carousel': carousel, 'has-active': active !== null }"
    >
      <template v-if="N">
        <div
          v-for="(m, i) in props.items"
          :key="m.src || String(i)"
          class="sitem"
          :class="{ 'is-active': active === i, 'is-dim': active !== null && active !== i, 'is-picking': picking === i }"
          :style="styles[i]"
          role="button"
          tabindex="0"
          :aria-label="m.title || `作品 ${i + 1}`"
          :title="active === i ? '' : '点击展开播放'"
          @click="onCardClick($event, i)"
          @keydown.enter.prevent="carousel ? openLightbox(m) : setActive(i)"
          @keydown.space.prevent="carousel ? openLightbox(m) : setActive(i)"
        >
          <div class="sitem__body">
            <MediaFrame
              :item="m"
              :uniform="active !== i"
              :big="active === i"
              :controls="active === i"
            />
            <!--
              收拢态不再叠播放键。
              之前这里盖了一个 46px 的圆形 ▶，一片素材铺下来满屏幕都是同一个图标，
              素材本身的画面反倒被压在下面。现在是干净的画面 + 右下角 `点任意一张展开播放`
              的文案提示，含义一样，但眼睛先看到的是作品。
            -->
            <div class="sitem__meta">
              <h4>{{ m.title || `作品 ${i + 1}` }}</h4>
              <span v-if="m.meta">{{ m.meta }}</span>
            </div>
            <button class="sitem__close" aria-label="收牌" @click.stop="setActive(null)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
            </button>
          </div>
        </div>

        <div class="stack__nav">
          <button aria-label="上一个" @click.stop="step(-1)">‹</button>
          <button class="stack__count" @click.stop="setActive(null)">{{ countText }}</button>
          <button aria-label="下一个" @click.stop="step(1)">›</button>
        </div>
        <div class="stack__hint">点任意一张展开播放 · 点 ✕ 收牌</div>
      </template>

      <div v-else class="ph r-16-9">
        <div class="ph__label">
          <svg class="ph__ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4">
            <rect x="3" y="5" width="18" height="14" rx="2" />
            <circle cx="8.5" cy="9.5" r="2" />
            <path d="M21 16l-4.5-4.5L8 20" />
          </svg>
          暂无素材
          <small>去后台给这个模块添加图片或视频</small>
        </div>
      </div>
    </div>

    <!-- 窄屏灯箱 -->
    <div class="lightbox" :class="{ 'is-open': lightboxOpen }" @click.self="closeLightbox">
      <div v-if="lightboxOpen" class="lightbox__box">
        <div class="lightbox__media">
          <MediaFrame :item="lightboxItem" big controls />
        </div>
        <div class="lightbox__bar">
          <div>
            <h4>{{ lightboxItem?.title }}</h4>
            <span class="small">{{ lightboxItem?.meta }}</span>
          </div>
          <button class="lightbox__close" aria-label="关闭" @click="closeLightbox">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stack-wrap { width: 100%; }
.stack { width: 100%; }
.stack-ready .sitem {
  position: absolute;
  top: 50%;
  left: 50%;
}
.stack--carousel .sitem { position: relative; }

/* 悬停反馈：轻轻「弹一下下」
 * ---------------------------------------------------------------------------
 * 只做很小的放大 + 抬高阴影，绝不展开 —— 展开必须靠点击。
 * 缩放放在 .sitem__body 上：卡牌自身的 transform 由内联的 --x/--y/--rot/--sc 驱动，
 * 内联样式优先级高于样式表，直接改 .sitem 的 transform 是改不动的。
 * 缓动用带一点回弹的曲线（末段 >1），按下去有"Q 一下"的手感，但幅度很小。
 */
.sitem__body {
  transition: transform .42s cubic-bezier(.34, 1.46, .64, 1);
}
@media (hover: hover) {
  .stack:not(.has-active) .sitem:hover .sitem__body {
    transform: scale(1.035);
  }
  /* 悬停的牌抬到最上层，免得被相邻的牌压住一点点边 */
  .stack:not(.has-active) .sitem:hover {
    z-index: 90 !important;
    box-shadow: 0 26px 58px rgba(0, 0, 0, .26), inset 0 1px 0 rgba(255, 255, 255, .7);
  }
}
/* 触屏没有 hover，用按下的瞬间给同样的反馈 */
.stack:not(.has-active) .sitem:active .sitem__body {
  transform: scale(1.015);
}

</style>
