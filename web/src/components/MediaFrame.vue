<script setup lang="ts">
/**
 * 素材帧：图片 / 视频 / 缺失占位，三种形态用同一个盒子。
 * ---------------------------------------------------------------------------
 * 横竖屏兼容的核心在这里：
 *   · 卡牌态（uniform）—— 不管素材是 9:16 还是 16:9，都套同一个画框，
 *                          一排牌看下来视觉重量一致，不会因为一张竖图把行高撑乱；
 *   · 播放态（native）—— 点开后按素材真实比例展开，竖的变高、横的变宽；
 *   · 尺寸未知时，图片 onload / 视频 loadedmetadata 会当场探测并回填 w/h，
 *     所以"传上来什么尺寸"都能自动选对画框，不需要手工填。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { MediaItem } from '@/types/content'
import { frameOf, orientationOf, posterUrlOf } from '@/util/content'

const props = withDefaults(
  defineProps<{
    item?: MediaItem | null
    /** 兜底比例类：r-16-9 / r-4-3 / r-3-4 / r-1-1 / r-21-9 */
    ratio?: string
    /** true = 用统一画框（叠牌画廊的牌面） */
    uniform?: boolean
    /**
     * 显式画框（CSS aspect-ratio 值，例如 "0.562 / 1"）。
     * -------------------------------------------------------------------
     * 给了就固定用它，**不再跟随素材自身的宽高比**。
     * 案例模块靠这条实现「版式由该案例的第一条素材决定」：
     * 竖屏案例是竖版画框、横屏案例是横版画框，之后在缩略轨里切到别的素材
     * 版面也不会跳来跳去 —— 画框不动，只是画面换成新的。
     */
    frame?: string
    /**
     * 画面填充方式。
     *   cover   —— 裁满画框（默认，叠牌画廊用，一排牌视觉重量一致）
     *   contain —— 完整显示、绝不裁切（案例模块用：要求原样呈现第一条素材）
     */
    fit?: 'cover' | 'contain'
    /**
     * 点击才开始播。
     * -------------------------------------------------------------------
     * 未点击前不挂 controls、不预播放 —— 画面就是一张封面。
     * 点下去之后才出控件并 play()，访客不会被一堆灰底进度条劝退。
     * 播放的全局治理（同时只播一个、滚出视口自动停）由 util/video.ts 统一处理，
     * 这里只负责响应用户那一下点击。
     */
    clickToPlay?: boolean
    /** 大号播放键 */
    big?: boolean
    /** 静音循环自动播放（首屏 Showreel 用） */
    autoplay?: boolean
    controls?: boolean
  }>(),
  {
    ratio: '',
    uniform: false,
    frame: '',
    fit: 'cover',
    clickToPlay: false,
    big: false,
    autoplay: false,
    controls: false
  }
)

const item = computed(() => props.item || null)
const hasSrc = computed(() => !!item.value?.src)
/** 后台勾了「临时下架」——前台按占位块渲染，素材和配置都还在 */
const hidden = computed(() => !!item.value?.hidden)
const live = computed(() => hasSrc.value && !hidden.value)
const isVideo = computed(() => item.value?.kind === 'video')

/** 已探测到的真实宽高（优先用内容里存的值，其次用运行时探测） */
const probed = ref<{ w: number; h: number } | null>(null)

const effW = computed(() => item.value?.w || probed.value?.w || 0)
const effH = computed(() => item.value?.h || probed.value?.h || 0)
const orientation = computed(() => orientationOf(effW.value, effH.value))

/** 探测到尺寸后回填到内容对象上，下次保存就会固化进数据库 */
function remember(w: number, h: number) {
  if (!w || !h) return
  probed.value = { w, h }
  const it = item.value
  if (it && (!it.w || !it.h)) {
    it.w = w
    it.h = h
  }
}

function onImgLoad(e: Event) {
  const el = e.target as HTMLImageElement
  remember(el.naturalWidth, el.naturalHeight)
}

function onMeta(e: Event) {
  const el = e.target as HTMLVideoElement
  remember(el.videoWidth, el.videoHeight)
}

/**
 * 自动播放必须**显式调用 play()**，光写 autoplay 属性不够。
 * ---------------------------------------------------------------------------
 * 线上现象：手机打开首页，Showreel 一直停在封面上一动不动，像是加载不出来。
 * 实测（移动视口）数据却一切正常 —— readyState=4、已经缓冲了 13 秒，
 * 但 paused=true、currentTime 一直是 0。也就是说**数据下完了，只是没播**。
 *
 * 原因：iOS 低电量模式、微信/部分安卓内置浏览器会直接忽略 autoplay 属性，
 * 而 Vue 只是把这个属性渲染出来，从来没有真正去调 play()。
 * 同一个页面里手动执行 play() 立刻就播起来了。
 *
 * 所以这里主动调一次，被策略拒绝就退到静音重试；还不行就挂一个一次性的
 * 「首次触摸/点击」监听 —— 用户碰一下屏幕就是合法的用户手势，那时再播。
 */
function tryAutoplay() {
  if (!props.autoplay) return
  const v = videoEl.value
  if (!v || !v.paused) return
  const p = v.play()
  if (p && p.catch) {
    p.catch(() => {
      // 被自动播放策略拦了：静音是解锁自动播放最常见的条件
      v.muted = true
      const again = v.play()
      if (again && again.catch) again.catch(() => armGestureFallback())
    })
  }
}

/** 自动播放被彻底拒绝时，等用户第一次碰屏幕再补一次 */
let gestureCleanup: (() => void) | null = null
function armGestureFallback() {
  if (gestureCleanup) return
  const once = () => {
    const v = videoEl.value
    if (v && v.paused) {
      v.muted = true
      v.play().catch(() => { /* 真播不了就保持封面 */ })
    }
    gestureCleanup?.()
  }
  document.addEventListener('touchstart', once, { passive: true })
  document.addEventListener('click', once)
  gestureCleanup = () => {
    document.removeEventListener('touchstart', once)
    document.removeEventListener('click', once)
    gestureCleanup = null
  }
}

/** 数据一到就试着播 —— 有些浏览器要等到 canplay 才允许 */
function onReady(e: Event) {
  onMeta(e)
  tryAutoplay()
}

/** 显式画框优先，没有才按素材自身比例算 */
const frameStyle = computed(() => ({
  aspectRatio: props.frame || frameOf(item.value, props.uniform)
}))

/**
 * 只有「快要滚到眼前」的视频才挂 src。
 * ---------------------------------------------------------------------------
 * 素材全是几十 MB 起步的原始视频：某个案例墙一次排了六十多条、
 * 合计 2GB 上下。以前每个 <video> 一上来就有 src，浏览器立刻对每个文件
 * 发请求去取元数据／首帧（还带 #t=0.1 让解码器定位），HTTP/1.1 又只有
 * 6 条连接，于是几十个请求排成一条长队 —— 表现就是进站先卡两分钟、
 * 真正想看的那个视频排在自己看不到的队尾，点了半天不出画面。
 *
 * 现在用 IntersectionObserver 提前 400px 预热：只有滚到附近的那几个才会真正去取
 * 数据，屏幕外的连 TCP 连接都不建。省下来的是整站几十倍的下行量。
 */
const rootEl = ref<HTMLElement | null>(null)
const near = ref(false)
let io: IntersectionObserver | null = null

onMounted(() => {
  // 首屏 Showreel：不等懒加载，DOM 一就绪就尝试起播（见 tryAutoplay 的说明）
  nextTick(tryAutoplay)

  if (!('IntersectionObserver' in window)) {
    near.value = true // 老浏览器：退回原行为，宁可慢也不能不显示
    return
  }
  io = new IntersectionObserver(
    (entries) => {
      if (!entries.some((e) => e.isIntersecting)) return
      near.value = true
      // 只需要触发一次：离屏后没必要再把手再看一遍
      if (io) {
        io.disconnect()
        io = null
      }
    },
    { rootMargin: '400px 0px' }
  )
  if (rootEl.value) io.observe(rootEl.value)
})

onBeforeUnmount(() => {
  if (io) {
    io.disconnect()
    io = null
  }
  gestureCleanup?.()
})

/**
 * 没有封面、又不是自动播放的视频，给 src 追加 `#t=0.1`。
 * 移动端（尤其 iOS）对 preload="metadata" 常常不渲染任何画面，卡片就是一块灰，
 * 用户根本看不出"到底传没传上"。带时间片段浏览器会去定位并画出那一帧。
 * 已经有封面或本身就自动播放的不需要，后者反正立刻就在动。
 */
const videoSrc = computed(() => {
  const s = item.value?.src || ''
  if (!s || posterSrc.value || props.autoplay) return s
  return s.includes('#') ? s : `${s}#t=0.1`
})

/** 还没滚到眼前就先不挂 src —— 自动播放的首屏视频是例外，它必须立刻能动 */
const displayedSrc = computed(() =>
  isVideo.value && !props.autoplay && !near.value ? undefined : videoSrc.value
)

/**
 * 预加载策略。
 * ---------------------------------------------------------------------------
 * metadata 不是免费的：它会让浏览器去把这个视频的头部（有时是整个 moov box，
 * 对长视频可能有几 MB）拉下来。六十多个视频就是六十多份。
 * 所以只在「确实需要」时才给 metadata：
 *   · 自动播放 —— 必须立刻有数据，给 auto
 *   · 尺寸还没测出来 —— 得靠 loadedmetadata 才知道该画多大的框，只能给 metadata
 *   · 其余一律 none —— 封面已经把画面交代清楚了，点播放时浏览器再去取，一行不多下
 */
const needsProbe = computed(() => effW.value <= 0 || effH.value <= 0)
const preloadMode = computed<'none' | 'metadata' | 'auto'>(() => {
  if (props.autoplay) return 'auto'
  // 用户已经点开了：他要的就是这个文件，别再省流量，尽快把数据拉下来
  if (activated.value || props.controls) return 'auto'
  return needsProbe.value ? 'metadata' : 'none'
})

/**
 * 声音开关。
 * 浏览器一律禁止「带声音的自动播放」：页面没被用户交互过时，
 * 带音轨的 play() 会被直接拒绝。所以做法是——静音先播起来保证画面在动，
 * 右下角放一个喇叭，访客点这一下既是用户手势，也就顺理成章地解除静音。
 * 后台没勾「允许播放声音」时连按钮都不出现，纯静音循环。
 */
const soundAllowed = computed(() => !!item.value?.sound)
const muted = ref(true)
const showSoundBtn = computed(
  () => props.autoplay && isVideo.value && hasSrc.value && soundAllowed.value
)
function toggleSound(e: Event) {
  e.preventDefault()
  e.stopPropagation()
  muted.value = !muted.value
}

/** 没探测到尺寸、也没手填、也没显式画框时，用调用方给的比例类兜底 */
const fallbackCls = computed(() =>
  props.ratio || (isVideo.value ? 'r-16-9' : 'r-4-3')
)

/** 只要有其一就用画框：显式指定 / 探测到尺寸 / 后台手填过画框策略 */
const useKnownFrame = computed(() =>
  !!props.frame ||
  (effW.value > 0 && effH.value > 0) ||
  (!!item.value?.fit && item.value.fit !== 'auto')
)

/**
 * 封面优先顺序：后台手填 → 后端抽的视频首帧。
 * 有了首帧之后，手机端未播放的视频不再是「一块灰」。
 */
const posterSrc = computed(
  () => item.value?.poster || (isVideo.value ? posterUrlOf(item.value?.src) : undefined)
)

/** contain = 完整显示不裁切（案例模块要求原样呈现素材，不能砍掉构图） */
const containCls = computed(() => (props.fit === 'contain' ? 'is-contain' : ''))

/**
 * 点击才播。
 * ---------------------------------------------------------------------------
 * activated 一旦置 true 就一直保持 —— 用户播完想重看、拖进度条、暂停再放，
 * 都不该再把控件收回去（收回去等于把暂停键藏了，很烦）。
 */
const activated = ref(false)
const videoEl = ref<HTMLVideoElement | null>(null)
const showControls = computed(() => props.controls || activated.value)

/**
 * 现在这一下点击真的会做事。
 * ---------------------------------------------------------------------------
 * 除了配 cursor:pointer，这里还要给 role/tabindex：iOS Safari 判断"这个元素
 * 能不能点"看的是这些语义（纯 div 上只挂 addEventListener 有时收不到 click），
 * role=button 是最稳的那一条，顺带把键盘操作也补上。
 */
const clickable = computed(
  () => props.clickToPlay && isVideo.value && live.value && !activated.value
)

/**
 * play() 被拒后退到的「强制静音」状态。
 * ---------------------------------------------------------------------------
 * 不能只写 v.muted = true —— Vue 下一次重渲染会拿 :muted 的绑定值把它冲掉。
 * 所以这里用一个 ref 参与绑定，保证静音这件事是响应式的、不会被 patch 回去。
 */
const forceMuted = ref(false)

function onMediaClick() {
  if (!props.clickToPlay || !isVideo.value || !live.value) return
  // 极端情况：程序化滚动 / IO 还没回调就点了 —— 直接当成已预热
  near.value = true
  activated.value = true

  /**
   * ⚠️ 必须**同步**调 play()，一次 nextTick 都不能等。
   * -------------------------------------------------------------------------
   * 现象：iPhone 上除了首屏视频，其它视频点了完全没反应。
   * 原因：iOS/WebKit 的「用户手势（user activation）」只在**当前同步调用栈**内
   * 有效。play() 一旦被放进 microtask（nextTick / Promise.then / await 之后），
   * WebKit 就认为这次播放不是用户发起的 —— 直接抛 NotAllowedError。
   * 更糟的是回退逻辑（静音重试）也在同一个 microtask 里，一样会被拒，
   * 于是「点了没反应」。首屏那个是静音自动播放，本来就不要求手势，
   * 所以只有它能播 —— 这正好解释了"只有首页视频正常"。
   *
   * 修复：在事件处理函数里立刻 play()，需要重试也在同一个栈里完成。
   */
  const v = videoEl.value
  if (!v) return
  // 元素此刻可能刚挂上 src（懒加载同一帧才生效），手动补一次保证同步可用
  const want = videoSrc.value
  if (want && !v.getAttribute('src')) v.src = want
  if (!v.paused) return
  const p = v.play()
  if (p && p.catch) {
    p.catch(() => {
      // 静音重试：静音是解锁播放最常见的条件。
      // 注意这里已经进了 microtask，iOS 未必认它是手势 —— 所以再挂一层兜底：
      // 用户下一次碰屏幕（比如去点画面上的原生播放键）时补一次。
      forceMuted.value = true
      v.muted = true
      const again = v.play()
      if (again && again.catch) again.catch(() => armGestureFallback())
    })
  }
}
</script>

<template>
  <div
    ref="rootEl"
    class="ph media-frame"
    :class="[
      live ? 'is-real' : '',
      useKnownFrame ? '' : fallbackCls,
      `is-${orientation}`,
      containCls,
      clickable ? 'is-clickable' : ''
    ]"
    :style="useKnownFrame ? frameStyle : undefined"
    :role="clickable ? 'button' : undefined"
    :tabindex="clickable ? 0 : undefined"
    :aria-label="clickable ? `播放 ${item?.title || '视频'}` : undefined"
    @contextmenu.prevent
    @click="onMediaClick"
    @keydown.enter.prevent="onMediaClick"
    @keydown.space.prevent="onMediaClick"
  >
    <span v-if="item?.badge" class="tag-res">{{ item.badge }}</span>
    <span v-if="item?.dur" class="tag-dur">{{ item.dur }}</span>

    <!-- data-hero-loop：首屏 Showreel 的标记，全局「只播一个」规则对它不生效 -->
    <video
      v-if="isVideo && live"
      ref="videoEl"
      :src="displayedSrc"
      :poster="posterSrc"
      playsinline
      webkit-playsinline
      x5-playsinline
      :preload="preloadMode"
      :controls="showControls"
      :autoplay="props.autoplay"
      :loop="props.autoplay"
      :muted="props.autoplay ? muted : forceMuted"
      :data-hero-loop="props.autoplay ? '1' : undefined"
      controlsList="nodownload"
      draggable="false"
      @loadedmetadata="onReady"
      @loadeddata="onReady"
      @canplay="onReady"
    ></video>

    <img
      v-else-if="live"
      :src="item!.src"
      :alt="item!.title || ''"
      loading="lazy"
      decoding="async"
      draggable="false"
      @load="onImgLoad"
    />

    <!-- 上面两个分支都不命中 = 占位态（没传素材 / 已下架） -->
    <div v-else class="ph__label">
      <span v-if="isVideo" class="play" :class="{ 'play--sm': !props.big }">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" /></svg>
      </span>
      <svg v-else class="ph__ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4">
        <rect x="3" y="5" width="18" height="14" rx="2" />
        <circle cx="8.5" cy="9.5" r="2" />
        <path d="M21 16l-4.5-4.5L8 20" />
      </svg>
      {{ item?.title || item?.label || '素材占位' }}
      <small v-if="hidden">已下架 —— 后台取消勾选即可重新上架</small>
      <small v-else-if="item?.hint">{{ item.hint }}</small>
      <small v-else>后台上传后自动替换</small>
    </div>

    <button
      v-if="showSoundBtn"
      class="sound-btn"
      type="button"
      :title="muted ? '开启声音' : '关闭声音'"
      :aria-label="muted ? '开启声音' : '关闭声音'"
      @click="toggleSound"
    >
      <svg v-if="muted" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M11 5 6 9H3v6h3l5 4V5z" />
        <path
          d="M16 9.5l5 5M21 9.5l-5 5"
          fill="none"
          stroke="currentColor"
          stroke-width="1.9"
          stroke-linecap="round"
        />
      </svg>
      <svg v-else viewBox="0 0 24 24" aria-hidden="true">
        <path d="M11 5 6 9H3v6h3l5 4V5z" />
        <path
          d="M15.5 8.5a5 5 0 0 1 0 7M18.5 6a8 8 0 0 1 0 12"
          fill="none"
          stroke="currentColor"
          stroke-width="1.9"
          stroke-linecap="round"
        />
      </svg>
    </button>
  </div>
</template>

<style scoped>
/* 注意：这里绝不写死浅色背景 —— 之前写死过 linear-gradient(#e7e7ec,#d3d4dc)，
   会把深色主题下 .ph 的深色底盖掉，导致图片/视频框在深色模式下变成一块块白卡片。 */
.is-real {
  border-color: transparent;
}
.ph video,
.ph img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: inherit;
}
/* 竖屏素材在统一画框里靠上裁切，避免把人脸/主体切掉 */
.is-portrait img,
.is-portrait video {
  object-position: center 35%;
}

/*
 * contain：完整显示，绝不裁切。
 * 案例模块要求「原样呈现第一条素材」。cover 会把竖屏的上下、横屏的左右砍掉，
 * 而构图恰恰是摄影 / 剪辑的命根子 —— 宁可留边，也不能替作者改取景。
 */
.is-contain video,
.is-contain img {
  object-fit: contain;
}

/*
 * contain 模式下素材比例和画框不可能 100% 对齐（比例值只保留三位小数），
 * 边角可能渗出几丝 .ph 的浅灰底 —— 那一小截在圆角处会被看成"白色小角"。
 * 这里已经是真素材了，不该再有占位底：直接透明，
 * 万一还有缝隙露出来的是页面/卡片本身的背景，不会是一块突兀的灰。
 */
.ph.is-real.is-contain {
  background: transparent;
}

/*
 * 点击才播的视频给个手型光标，暗示「这里可以点」。
 * 只加光标，不放任何图标 —— 一排素材满屏都是 ▶ 会很吵，
 * 而且 hover 抬一下 + 首帧封面已经足够说明它是个可播的东西。
 */
.is-clickable { cursor: pointer; }

/*
 * 素材保护：禁掉拖拽另存、iOS 长按保存、右键菜单。
 * ---------------------------------------------------------------------------
 * 得说清楚这是「抬门槛」而不是「防下载」：视频能播，浏览器就一定拿到了完整数据，
 * 开了 F12 的人几秒钟就能把原文件存走 —— 这跟前端写多少行代码都没关系。
 * 真正想要版权保护，靠的是画面水印 + 法律，以及「只放低清预览、原片不外露」。
 * 这里做的只是把顺手右键/长按/拖拽这三条最省力的路径堵上。
 */
.ph video,
.ph img {
  -webkit-user-drag: none;
  user-select: none;
  -webkit-touch-callout: none;
}
.ph.is-real:deep(.tag-res),
.ph.is-real:deep(.tag-dur) {
  z-index: 3;
}
/* 右上角：左上角是 tag-res、右下角是 tag-dur，只有这里不会打架 */
.sound-btn {
  position: absolute;
  right: 12px;
  top: 12px;
  z-index: 4;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  -webkit-backdrop-filter: blur(10px);
  backdrop-filter: blur(10px);
  color: #fff;
  cursor: pointer;
  transition: transform 0.18s var(--ease), background 0.18s;
}
.sound-btn:hover {
  transform: scale(1.08);
  background: rgba(0, 0, 0, 0.68);
}
.sound-btn:active {
  transform: scale(0.95);
}
.sound-btn svg {
  width: 19px;
  height: 19px;
  fill: currentColor;
}
</style>
