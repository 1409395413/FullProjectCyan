<script setup lang="ts">
/**
 * 天色窗口 + 实时时钟，常驻左下角。
 * ---------------------------------------------------------------------------
 * 这块是个"天文钟"，不是开关：
 *   · 主题**自动**跟着北京的日出日落走，不需要任何人点按钮（需求原话：
 *     "不要主动调整白天和夜间按钮"）；
 *   · 小窗口里是一座山，太阳白天沿山坡从左爬到右，夜里换成月亮走同一条弧线，
 *     位置由真实时间插值，不是动画假动 —— 你盯着看它确实在动，只是很慢；
 *   · 天色也跟着进程变：靠近地平线偏晨昏橙，升到中天是正午蓝 / 午夜深蓝，
 *     所以日出日落前后是"渐进式"过渡，不是啪一下切换。
 *
 * 主题切换的渐变：切到新主题的瞬间给 <html> 挂 theme-fading 类，
 * 让全站颜色在 1.2s 里过渡完再摘掉（见 main.css）。
 * 挂类而不是常驻全局 transition，是因为常驻会把每个组件自己的 hover 过渡都顶掉。
 *
 * 刷新频率：时钟每秒走；天色每 20 秒重算一次 —— 太阳在弧线上一小时才挪十几像素，
 * 每秒重算纯属浪费。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { BEIJING_TZ_MIN, skyPalette, skyState, type SkyState } from '@/util/sun'

const state = ref<SkyState>({ theme: 'light', progress: 0.5, isDay: true, from: 0, to: 0 })
const now = ref(new Date())
let clockTimer: ReturnType<typeof setInterval> | undefined
let skyTimer: ReturnType<typeof setInterval> | undefined

const pad = (n: number) => String(n).padStart(2, '0')
const dateText = () => {
  const d = now.value
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const weekText = () => {
  const w = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return w[now.value.getDay()]
}
const timeText = () => {
  const d = now.value
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 把 UTC 时间戳格式化成北京时间钟点，给提示文案用 */
function bjClock(ms: number) {
  if (!ms) return '--:--'
  // 直接 +8h 再读 UTC 字段就是北京墙钟；见 util/sun.ts 里 beijingNow 的说明
  const d = new Date(ms + BEIJING_TZ_MIN * 60000)
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`
}

const palette = computed(() => skyPalette(state.value))
const ariaText = computed(() => {
  const s = state.value
  if (!s.from) return s.isDay ? '白天模式' : '夜间模式'
  const kind = s.isDay ? '白天' : '夜间'
  return `${kind}模式 · ${bjClock(s.from)} → ${bjClock(s.to)}`
})

/** 日/月在山坡弧线上的位置：p=0 左侧地平线，p=1 右侧地平线，0.5 顶点 */
const orb = computed(() => {
  const p = state.value.progress
  return {
    x: +(32 - 26 * Math.cos(Math.PI * p)).toFixed(2),
    y: +(36 - 22 * Math.sin(Math.PI * p)).toFixed(2)
  }
})

let fadeTimer: ReturnType<typeof setTimeout> | undefined

function applyTheme(t: 'light' | 'dark') {
  const html = document.documentElement
  if (html.dataset.theme === t) return
  // 渐进式：切的那一下全站颜色一起过渡，过渡完把类摘掉，别影响日常交互动画
  html.classList.add('theme-fading')
  html.dataset.theme = t
  const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (meta) meta.setAttribute('content', t === 'dark' ? '#0b0b0d' : '#f5f5f7')
  if (fadeTimer) clearTimeout(fadeTimer)
  fadeTimer = setTimeout(() => html.classList.remove('theme-fading'), 1500)
}

function refreshSky() {
  state.value = skyState()
  applyTheme(state.value.theme)
}

onMounted(() => {
  refreshSky()
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)
  skyTimer = setInterval(refreshSky, 20000)
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  if (skyTimer) clearInterval(skyTimer)
  if (fadeTimer) clearTimeout(fadeTimer)
})
</script>

<template>
  <div class="dock">
    <!-- 时钟在上方：年月日 + 时分秒 -->
    <div class="dock__clock">
      <div class="dock__date">{{ dateText() }} <span>{{ weekText() }}</span></div>
      <div class="dock__time">{{ timeText() }}</div>
    </div>

    <!-- 天色窗口：一座山，日月沿山坡更替 -->
    <div class="dock__sky" role="img" :aria-label="ariaText" :title="ariaText">
      <svg viewBox="0 0 64 40" width="100%" height="100%" aria-hidden="true">
        <!-- 天空：颜色由日出日落的进程插值 -->
        <rect width="64" height="40" :fill="palette.sky" />
        <!-- 星星：只有夜里、离晨昏够远才亮 -->
        <g :opacity="palette.stars" fill="#ffffff">
          <circle cx="12" cy="7" r="0.9" />
          <circle cx="27" cy="4.2" r="0.7" />
          <circle cx="46" cy="5.5" r="0.9" />
          <circle cx="57" cy="12" r="0.7" />
          <circle cx="38" cy="9" r="0.6" />
        </g>
        <!-- 日 / 月：同一道弧线，白天走太阳，夜里走月亮 -->
        <circle :cx="orb.x" :cy="orb.y" r="9" :fill="palette.orb" opacity="0.16" />
        <circle :cx="orb.x" :cy="orb.y" r="5.6" :fill="palette.orb" />
        <!-- 山：两层剪影，远山浅、近山深，天体从它们背后落下 -->
        <path
          d="M0 40 L15 21 L26 31 L41 15 L53 27 L64 19 L64 40 Z"
          :fill="palette.ridgeFar"
        />
        <path
          d="M0 40 L9 30 L21 34 L33 20 L49 32 L64 25 L64 40 Z"
          :fill="palette.ridgeNear"
        />
      </svg>
    </div>
  </div>
</template>

<style scoped>
.dock {
  position: fixed;
  left: 18px;
  bottom: calc(18px + env(safe-area-inset-bottom, 0px));
  z-index: 95;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  /* 容器本身不吃事件，否则左下角这块透明区域会挡住页面的点击 */
  pointer-events: none;
}
.dock > * { pointer-events: auto; }

.dock__clock {
  padding: 7px 12px;
  border-radius: 12px;
  background: var(--glass, rgba(255, 255, 255, .62));
  border: 1px solid var(--glass-stroke, rgba(255, 255, 255, .65));
  -webkit-backdrop-filter: blur(18px) saturate(180%);
  backdrop-filter: blur(18px) saturate(180%);
  box-shadow: var(--sh-s, 0 2px 10px rgba(0, 0, 0, .06));
  line-height: 1.25;
  user-select: none;
}
.dock__date {
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ink-2, #6e6e73);
  font-variant-numeric: tabular-nums;
}
.dock__date span { margin-left: 5px; opacity: .75; }
.dock__time {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink, #1d1d1f);
  letter-spacing: .01em;
  font-variant-numeric: tabular-nums;
}

/* 天色窗口：圆角小舷窗 */
.dock__sky {
  width: 64px;
  height: 40px;
  border-radius: 13px;
  overflow: hidden;
  border: 1px solid var(--glass-stroke, rgba(255, 255, 255, .4));
  box-shadow: var(--sh-s, 0 2px 10px rgba(0, 0, 0, .08));
  user-select: none;
}
.dock__sky svg { display: block; width: 100%; height: 100%; }
/* 天色变化本身也走过渡：进程每 20 秒才更新一次，颜色要能平滑追上 */
.dock__sky rect,
.dock__sky circle,
.dock__sky path { transition: fill 1.2s linear, opacity 1.2s linear; }

@media (max-width: 720px) {
  .dock {
    left: 12px;
    bottom: calc(12px + env(safe-area-inset-bottom, 0px));
    gap: 6px;
  }
  .dock__clock { padding: 5px 9px; border-radius: 10px; }
  .dock__date { font-size: 10.5px; }
  .dock__time { font-size: 13.5px; }
  /* 44px 是移动端可点区域的下限；虽然现在不是按钮了，体积也别缩得太小气 */
  .dock__sky { width: 58px; height: 36px; border-radius: 12px; }
}
</style>
