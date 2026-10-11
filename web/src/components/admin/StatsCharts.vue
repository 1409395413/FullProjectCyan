<script setup lang="ts">
/**
 * StatsCharts — 监控板块的图表集（手写 SVG，不引图表库）。
 * ---------------------------------------------------------------------------
 * 为什么不用 echarts：一个后台页面不值得多背 ~1MB；uPlot 也要 +45KB。
 * 手写 SVG 约 +15KB，风格还能跟后台现有的 CSS 柱状图保持一致。
 *
 * 板块（对应 docs/08 需求 2，地域按拍板走「省份条形图」，不做真实地图轮廓 ——
 * 中国地图必须用有审图号的合规底图，自己描省界有实际风险）：
 *   ① 每日趋势（折线 + 面积，hover 出当天明细）
 *   ② 时段热力（7 天 × 24 小时方块）
 *   ③ 地域分布（省份 TOP10 横向条形）
 *   ④ 设备环图 + 浏览器/来源 TOP5
 *   ⑤ 转化漏斗（访客 → 播放 → 提交需求）
 *
 * 每个板块都有独立空态：数据少的时候，一句「还没有数据」比一张空图更诚实。
 */
import { computed, ref } from 'vue'
import type { AdminStats } from '@/types/content'

const props = defineProps<{
  stats: AdminStats
  /** 停留时长的格式化方法由父组件传入，保持全后台一种写法 */
  humanDuration: (ms?: number) => string
}>()

const WEEKS = ['一', '二', '三', '四', '五', '六', '日']

/* ------------------------------ ① 趋势 ------------------------------ */

const CHART_W = 640
const CHART_H = 180
const PAD_L = 34
const PAD_B = 22
const PAD_T = 10

const series = computed(() => props.stats.series || [])

/**
 * 双 y 轴：访问（左轴，蓝）与播放（右轴，绿虚线）量纲差得远 ——
 * 曾有一天累计 447 次播放而访问只有 85，共用一个轴的话访问线会贴成一条直线。
 * 各按各的最大值缩放，hover 仍显示原始值。
 */
const visitMax = computed(() =>
  Math.max(3, ...series.value.map((d) => d.sessions))
)
const playMax = computed(() =>
  Math.max(3, ...series.value.map((d) => d.plays))
)

/** x 均分；访问按左轴、播放按右轴各自缩放（留出顶部余量防止折线贴边） */
const points = computed(() =>
  series.value.map((d, i) => ({
    d,
    x:
      series.value.length === 1
        ? PAD_L + (CHART_W - PAD_L) / 2
        : PAD_L + (i * (CHART_W - PAD_L - 8)) / (series.value.length - 1),
    y: PAD_T + (1 - d.sessions / visitMax.value) * (CHART_H - PAD_T - PAD_B),
    py: PAD_T + (1 - d.plays / playMax.value) * (CHART_H - PAD_T - PAD_B)
  }))
)

const trendLine = computed(() =>
  points.value.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
)
const trendArea = computed(() =>
  points.value.length
    ? `${trendLine.value} L${points.value[points.value.length - 1].x.toFixed(1)},${CHART_H - PAD_B} L${points.value[0].x.toFixed(1)},${CHART_H - PAD_B} Z`
    : ''
)
const playLine = computed(() =>
  points.value.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.py.toFixed(1)}`).join(' ')
)

/** 网格线：左轴（访问）最大值分成 3 份 */
const gridLines = computed(() => [1, 2 / 3, 1 / 3, 0].map((r) => ({
  y: PAD_T + (1 - r) * (CHART_H - PAD_T - PAD_B),
  label: Math.round(visitMax.value * r),
  r
})))

const hoverIdx = ref<number | null>(null)

/* ------------------------------ ② 热力 ------------------------------ */

/** 168 格铺平（周一 0 点起），这里还原成 7×24 的矩阵 */
const heatGrid = computed(() => {
  const flat = props.stats.heatmap || []
  const rows: number[][] = []
  for (let d = 0; d < 7; d++) rows.push(flat.slice(d * 24, d * 24 + 24))
  return rows
})
const heatMax = computed(() => Math.max(1, ...(props.stats.heatmap || [])))
/** 颜色深浅：0 格给底色，其余按占比走透明度，深浅一眼可读 */
function heatCell(v: number) {
  if (!v) return { opacity: 0, label: '无访问' }
  const r = v / heatMax.value
  return { opacity: 0.2 + r * 0.8, label: `${v} 次` }
}
const heatHasData = computed(() => (props.stats.heatmap || []).some((v) => v > 0))

/* ------------------------------ ③ 地域 ------------------------------ */

const regions = computed(() => props.stats.region || [])
const regionMax = computed(() => Math.max(1, ...regions.value.map((r) => r.count)))
const regionTotal = computed(() => regions.value.reduce((s, r) => s + r.count, 0))
/** 有多少会话还查不出省份（历史数据 + 内网 + 库里没有的） */
const regionUnknown = computed(() => {
  const s = props.stats.windowSessions || 0
  return Math.max(0, s - regionTotal.value)
})

/* ------------------------------ ④ 设备 / 来源 ------------------------------ */

const devices = computed(() => props.stats.devices || [])
const deviceTotal = computed(() => devices.value.reduce((s, d) => s + d.count, 0))

/** 环图：把计数换算成 SVG 圆弧。用 stroke-dasharray 画，比 path 简单可靠 */
const RING_R = 46
const RING_C = 2 * Math.PI * RING_R
function ringSeg(idx: number) {
  const total = Math.max(1, deviceTotal.value)
  const before = devices.value.slice(0, idx).reduce((s, d) => s + d.count, 0)
  const frac = devices.value[idx].count / total
  return {
    dash: `${(frac * RING_C).toFixed(2)} ${(RING_C - frac * RING_C).toFixed(2)}`,
    offset: `${(-before * RING_C / total).toFixed(2)}`
  }
}
/** 三种设备给固定颜色，浏览器/来源用同一套色板循环取 */
const PALETTE = ['#6c8cff', '#38c6a4', '#f2a35c', '#c583e8', '#5fb8ef', '#ef6f8e']
function palette(i: number) {
  return PALETTE[i % PALETTE.length]
}
const DEVICE_COLOR: Record<string, string> = { 手机: '#6c8cff', 平板: '#38c6a4', 桌面: '#f2a35c', 未知: '#9aa0b0' }
function deviceColor(name: string) {
  return DEVICE_COLOR[name] || palette(3)
}

const browsers = computed(() => props.stats.browsers || [])
const sources = computed(() => props.stats.sources || [])
const browserMax = computed(() => Math.max(1, ...browsers.value.map((b) => b.count)))
const sourceMax = computed(() => Math.max(1, ...sources.value.map((s) => s.count)))

/* ------------------------------ ⑤ 漏斗 ------------------------------ */

const funnel = computed(() => props.stats.funnel)
const funnelSteps = computed(() => {
  const f = funnel.value
  if (!f) return []
  const top = Math.max(1, f.visits, f.players, f.inquirers)
  return [
    { label: '访问', value: f.visits, pct: 100 },
    { label: '点开作品', value: f.players, pct: (f.players / top) * 100 },
    { label: '提交需求', value: f.inquirers, pct: (f.inquirers / top) * 100 }
  ].map((s) => ({ ...s, w: Math.max(3, (s.pct / 100) * 100) }))
})
</script>

<template>
  <div class="charts">
    <!-- ① 每日趋势 -->
    <div class="panel">
      <h3 class="sub">每日访问趋势</h3>
      <template v-if="series.length">
        <svg
          class="trend"
          :viewBox="`0 0 ${CHART_W} ${CHART_H}`"
          role="img"
          aria-label="每日访问与播放趋势"
          @mouseleave="hoverIdx = null"
        >
          <g v-for="(g, i) in gridLines" :key="i">
            <line :x1="PAD_L" :x2="CHART_W - 4" :y1="g.y" :y2="g.y" class="grid" />
            <text :x="PAD_L - 6" :y="g.y + 3" class="tick" text-anchor="end">{{ g.label }}</text>
            <!-- 右轴：播放次数（绿色，与虚线对应） -->
            <text :x="CHART_W - 2" :y="g.y + 3" class="tick tick--play" text-anchor="start">
              {{ Math.round(playMax * g.r) }}
            </text>
          </g>
          <path :d="trendArea" class="area" />
          <path :d="trendLine" class="line" />
          <path v-if="points.length > 1" :d="playLine" class="line line--play" />
          <g
            v-for="(p, i) in points"
            :key="p.d.day"
            @mouseenter="hoverIdx = i"
          >
            <circle :cx="p.x" :cy="p.y" r="3.5" class="dot" :class="{ on: hoverIdx === i }" />
            <text :x="p.x" :y="CHART_H - 6" class="tick" text-anchor="middle">{{ p.d.day.slice(5) }}</text>
          </g>
        </svg>
        <p v-if="hoverIdx !== null && points[hoverIdx]" class="small tip">
          {{ points[hoverIdx]!.d.day }} · {{ points[hoverIdx]!.d.sessions }} 次访问 ·
          {{ points[hoverIdx]!.d.plays }} 次播放 · 平均停留
          {{ humanDuration(points[hoverIdx]!.d.avgDurationMs) }}
        </p>
        <p v-else class="small tip">
          <span class="lg lg--visit"></span> 访问（左轴）
          <span class="lg lg--play"></span> 播放（右轴）· 鼠标移到圆点看当天明细
        </p>
      </template>
      <p v-else class="small empty">还没有数据，去前台刷几下页面就有了。</p>
    </div>

    <!-- ② 时段热力 -->
    <div class="panel">
      <h3 class="sub">时段热力（访客几点来）</h3>
      <template v-if="heatHasData">
        <div class="heat">
          <div class="heat__corner"></div>
          <div class="heat__hours">
            <i v-for="h in 24" :key="h">{{ (h - 1) % 3 === 0 ? h - 1 : '' }}</i>
          </div>
          <template v-for="(row, d) in heatGrid" :key="d">
            <span class="heat__day">周{{ WEEKS[d] }}</span>
            <div class="heat__cells">
              <span
                v-for="(v, h) in row"
                :key="h"
                class="heat__cell"
                :style="{ opacity: heatCell(v).opacity }"
                :title="`周${WEEKS[d]} ${h} 点 · ${heatCell(v).label}`"
              ></span>
            </div>
          </template>
        </div>
      </template>
      <p v-else class="small empty">这个窗口里还没有访问记录。</p>
    </div>

    <!-- ③ 地域分布 -->
    <div class="panel">
      <h3 class="sub">
        访客来自哪里
        <small class="sub__note">只到省一级 · 2026-10-09 起采集，之前的会话显示「未知」</small>
      </h3>
      <template v-if="regions.length">
        <div v-for="r in regions" :key="r.name" class="hbar">
          <span class="hbar__k" :title="r.name">{{ r.name }}</span>
          <span class="hbar__track">
            <span class="hbar__bar" :style="{ width: Math.max(3, (r.count / regionMax) * 100) + '%' }"></span>
          </span>
          <b class="hbar__v">{{ r.count }}</b>
          <i class="hbar__p">{{ Math.round((r.count / Math.max(1, regionTotal)) * 100) }}%</i>
        </div>
      </template>
      <p v-else class="small empty">
        还没有可统计的地域数据 —— 新访客进来后这里就会出现省份排行。
      </p>
      <p v-if="regionUnknown > 0" class="small dim">
        另有 {{ regionUnknown }} 次访问未记录地域（历史数据 / 内网 / 识别不出）。
      </p>
    </div>

    <!-- ④ 设备 / 浏览器 / 来源 -->
    <div class="panel">
      <h3 class="sub">设备与来源</h3>
      <div class="dev">
        <template v-if="devices.length && deviceTotal">
          <svg viewBox="0 0 120 120" class="ring" role="img" aria-label="设备占比">
            <circle cx="60" cy="60" :r="RING_R" class="ring__bg" />
            <circle
              v-for="(d, i) in devices"
              :key="d.name"
              cx="60"
              cy="60"
              :r="RING_R"
              class="ring__seg"
              :stroke="deviceColor(d.name)"
              :stroke-dasharray="ringSeg(i).dash"
              :stroke-dashoffset="ringSeg(i).offset"
            />
            <text x="60" y="57" class="ring__num" text-anchor="middle">{{ deviceTotal }}</text>
            <text x="60" y="72" class="ring__cap" text-anchor="middle">次访问</text>
          </svg>
          <div class="dev__list">
            <div v-for="d in devices" :key="d.name" class="lg-row">
              <span class="lg-dot" :style="{ background: deviceColor(d.name) }"></span>
              {{ d.name }}
              <b>{{ d.count }}</b>
              <i>{{ Math.round((d.count / Math.max(1, deviceTotal)) * 100) }}%</i>
            </div>
          </div>
        </template>
        <p v-else class="small empty">还没有访问记录。</p>
      </div>

      <div class="two-col two-col--tight">
        <div>
          <h4 class="mini-sub">浏览器 TOP5</h4>
          <template v-if="browsers.length">
            <div v-for="b in browsers" :key="b.name" class="hbar hbar--sm">
              <span class="hbar__k" :title="b.name">{{ b.name }}</span>
              <span class="hbar__track">
                <span class="hbar__bar" :style="{ width: Math.max(4, (b.count / browserMax) * 100) + '%' }"></span>
              </span>
              <b class="hbar__v">{{ b.count }}</b>
            </div>
          </template>
          <p v-else class="small empty">暂无数据。</p>
        </div>
        <div>
          <h4 class="mini-sub">来源 TOP5</h4>
          <template v-if="sources.length">
            <div v-for="s in sources" :key="s.name" class="hbar hbar--sm">
              <span class="hbar__k" :title="s.name">{{ s.name }}</span>
              <span class="hbar__track">
                <span class="hbar__bar" :style="{ width: Math.max(4, (s.count / sourceMax) * 100) + '%' }"></span>
              </span>
              <b class="hbar__v">{{ s.count }}</b>
            </div>
          </template>
          <p v-else class="small empty">暂无数据。</p>
        </div>
      </div>
    </div>

    <!-- ⑤ 转化漏斗 -->
    <div class="panel">
      <h3 class="sub">转化漏斗</h3>
      <template v-if="funnelSteps.length && (funnel?.visits || 0) > 0">
        <div class="funnel">
          <div v-for="(s, i) in funnelSteps" :key="s.label" class="funnel__step">
            <span class="funnel__bar" :style="{ width: s.w + '%' }">
              <span class="funnel__label">{{ s.label }}</span>
            </span>
            <b>{{ s.value }}</b>
            <i v-if="i > 0" class="small dim">
              {{ funnelSteps[i]!.value }}/{{ funnelSteps[i - 1]!.value || 1 }}
            </i>
            <i v-else class="small dim">100%</i>
          </div>
        </div>
        <p class="small dim">
          窗口 {{ stats.windowDays || 7 }} 天：多少次访问 → 多少次访问里点开了作品 → 多少人提交了需求。
        </p>
      </template>
      <p v-else class="small empty">这个窗口里还没有访问记录。</p>
    </div>
  </div>
</template>

<style scoped>
.charts { display: grid; gap: 22px; }
.panel { display: grid; gap: 8px; }

.sub { margin: 4px 0 0; }
.sub__note { font-weight: 400; font-size: 12px; color: var(--muted, #8a8f9c); margin-left: 8px; }
.mini-sub { margin: 6px 0; font-size: 13px; opacity: .85; }
.empty { opacity: .7; }
.dim { opacity: .62; }

/* ---- 趋势图 ---- */
.trend { width: 100%; height: auto; display: block; }
.trend .grid { stroke: currentColor; opacity: .12; stroke-width: 1; }
.trend .tick { font-size: 10px; fill: currentColor; opacity: .55; }
.trend .tick--play { fill: #38c6a4; opacity: .8; }
.trend .area { fill: #6c8cff; opacity: .16; stroke: none; }
.trend .line { fill: none; stroke: #6c8cff; stroke-width: 2; stroke-linecap: round; }
.trend .line--play { stroke: #38c6a4; stroke-dasharray: 4 4; }
.trend .dot { fill: #6c8cff; stroke: var(--panel, #fff); stroke-width: 1.5; cursor: pointer; }
.trend .dot.on { r: 5; }
.tip { min-height: 1.4em; }

.lg { display: inline-block; width: 14px; height: 3px; border-radius: 2px; vertical-align: middle; margin: 0 4px 0 10px; }
.lg--visit { background: #6c8cff; }
.lg--play { background: #38c6a4; }

/* ---- 热力 ---- */
.heat { display: grid; grid-template-columns: 34px 1fr; gap: 3px 6px; align-items: center; max-width: 560px; }
.heat__corner { grid-column: 1; }
.heat__hours { display: grid; grid-template-columns: repeat(24, 1fr); gap: 2px; }
.heat__hours i { font-size: 9px; font-style: normal; opacity: .5; text-align: left; }
.heat__day { font-size: 11px; opacity: .7; }
.heat__cells { display: grid; grid-template-columns: repeat(24, 1fr); gap: 2px; }
.heat__cell { aspect-ratio: 1; border-radius: 3px; background: #6c8cff; }

/* ---- 横向条形 ---- */
.hbar { display: grid; grid-template-columns: 76px 1fr 44px 40px; gap: 8px; align-items: center; margin: 4px 0; }
.hbar--sm { grid-template-columns: 76px 1fr 32px; }
.hbar__k { font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hbar__track { height: 10px; border-radius: 5px; background: color-mix(in srgb, currentColor 8%, transparent); overflow: hidden; }
.hbar__bar { display: block; height: 100%; border-radius: 5px; background: linear-gradient(90deg, #6c8cff, #8aa4ff); }
.hbar--sm .hbar__bar { background: linear-gradient(90deg, #38c6a4, #5fd9bc); }
.hbar__v { font-size: 12px; text-align: right; }
.hbar__p { font-size: 11px; font-style: normal; opacity: .55; }

/* ---- 设备环图 ---- */
.dev { display: flex; gap: 22px; align-items: center; flex-wrap: wrap; }
.ring { width: 128px; height: 128px; flex: none; }
.ring__bg { fill: none; stroke: color-mix(in srgb, currentColor 10%, transparent); stroke-width: 16; }
.ring__seg { fill: none; stroke-width: 16; transform: rotate(-90deg); transform-origin: 60px 60px; }
.ring__num { font-size: 22px; font-weight: 700; fill: currentColor; }
.ring__cap { font-size: 9px; fill: currentColor; opacity: .55; }
.dev__list { display: grid; gap: 6px; }
.lg-row { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.lg-dot { width: 10px; height: 10px; border-radius: 3px; flex: none; }
.lg-row b { margin-left: 4px; }
.lg-row i { font-style: normal; opacity: .55; font-size: 12px; }

.two-col--tight { margin-top: 12px; display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
@media (max-width: 720px) { .two-col--tight { grid-template-columns: 1fr; } }

/* ---- 漏斗 ---- */
.funnel { display: grid; gap: 8px; }
.funnel__step { display: grid; grid-template-columns: minmax(120px, 340px) 48px 52px; gap: 10px; align-items: center; }
.funnel__bar { height: 30px; border-radius: 8px; background: linear-gradient(90deg, #6c8cff, #8aa4ff);
  display: flex; align-items: center; padding: 0 12px; overflow: hidden; }
.funnel__step:nth-child(2) .funnel__bar { background: linear-gradient(90deg, #38c6a4, #5fd9bc); }
.funnel__step:nth-child(3) .funnel__bar { background: linear-gradient(90deg, #f2a35c, #f6bd86); }
.funnel__label { color: #fff; font-size: 12px; white-space: nowrap; text-shadow: 0 1px 2px rgba(0,0,0,.25); }
.funnel__step b { font-size: 14px; }
</style>
