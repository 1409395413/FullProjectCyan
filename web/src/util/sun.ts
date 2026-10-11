/**
 * 日出日落 + 天色计算（北京）。
 * ---------------------------------------------------------------------------
 * 为什么自己算而不是写死一张"每月日出日落表"：
 *   表只能给近似值（同一月份里日出能差十几分钟），而且每年还不完全一样。
 *   这里用的是标准太阳位置算法（NOAA / SunCalc 同款，精度 ±1 分钟量级），
 *   给定经纬度直接算出当天的日出日落 —— 它本质上是跟着太阳赤纬走的，
 *   而赤纬正是二十四节气的天文依据，所以"按中国日历同步"这件事是自动成立的。
 *
 * 为什么坐标写死北京：访客可能在任何城市，但需求点名"按北京时间和中国日历"，
 * 用一个全国折中的坐标比"猜访客在哪"更可预期。要改成按访客定位，把 BEIJING
 * 换成定位结果即可，其余逻辑不动。
 *
 * ⚠️ web/index.html 里有一段内联脚本负责首帧防闪烁，它用的是"粗略小时数"
 *    而不是这里的精确计算 —— 因为内联脚本必须同步执行、不能背一套天文数学。
 *    页面加载后 ThemeDock 会用这里的精确值纠正，纠正过程带渐变过渡，不会跳。
 */

const rad = Math.PI / 180
const DAY_MS = 86400000
const J1970 = 2440588
const J2000 = 2451545

/** 北京坐标（天安门）。 */
const BEIJING = { lat: 39.9042, lng: 116.4074 }
/** UTC+8。 */
export const BEIJING_TZ_MIN = 480

/**
 * 把"现在"平移成北京时间的墙上时钟，之后一律用 getUTC* 读数。
 *
 * ⚠️ 这里**不能**再叠加本地时区偏移（getTimezoneOffset）——
 * getUTC* 读出来的本来就是 UTC 墙钟，要让它等于北京墙钟只需要把时刻 +8h。
 * 叠加本地偏移会在非 +8 时区的机器上算错两次。
 */
export function beijingNow(nowMs = Date.now()): Date {
  return new Date(nowMs + BEIJING_TZ_MIN * 60000)
}

export function beijingParts(nowMs = Date.now()) {
  const d = beijingNow(nowMs)
  return {
    y: d.getUTCFullYear(),
    m: d.getUTCMonth(),
    d: d.getUTCDate(),
    hh: d.getUTCHours(),
    mm: d.getUTCMinutes(),
    ss: d.getUTCSeconds()
  }
}

/* ---------------- 太阳位置 ---------------- */

function toDays(date: Date) {
  return date.valueOf() / DAY_MS - 0.5 + J1970 - J2000
}
function solarMeanAnomaly(d: number) {
  return rad * (357.5291 + 0.98560028 * d)
}
function eclipticLongitude(M: number) {
  const C = rad * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M))
  const P = rad * 102.9372
  return M + C + P + Math.PI
}
/** 太阳赤纬（b=0）。 */
function declination(l: number) {
  const e = rad * 23.4397
  return Math.asin(Math.sin(e) * Math.sin(l))
}
const J0 = 0.0009
function julianCycle(d: number, lw: number) {
  return Math.round(d - J0 - lw / (2 * Math.PI))
}
function approxTransit(Ht: number, lw: number, n: number) {
  return J0 + (Ht + lw) / (2 * Math.PI) + n
}
function solarTransitJ(ds: number, M: number, L: number) {
  return J2000 + ds + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * L)
}
function hourAngle(h: number, phi: number, d: number) {
  return Math.acos((Math.sin(h) - Math.sin(phi) * Math.sin(d)) / (Math.cos(phi) * Math.cos(d)))
}
function fromJulian(j: number) {
  return new Date((j + 0.5 - J1970) * DAY_MS)
}

/**
 * 某个北京日历日的日出/日落（UTC 毫秒时间戳）。
 * 返回 null = 极昼或极夜（北京纬度不会发生，纯防御）。
 */
export function sunTimesUtc(y: number, m: number, d: number): { rise: number; set: number } | null {
  // 北京时间正午 = UTC 04:00，用它锚定"算的是哪一天"
  const noonUtc = Date.UTC(y, m, d, 4, 0, 0)
  const lw = rad * -BEIJING.lng
  const phi = rad * BEIJING.lat
  const ds = toDays(new Date(noonUtc))
  const n = julianCycle(ds, lw)
  const ds0 = approxTransit(0, lw, n)
  const M = solarMeanAnomaly(ds0)
  const L = eclipticLongitude(M)
  const dec = declination(L)
  const Jnoon = solarTransitJ(ds0, M, L)
  const h = rad * -0.833 // 太阳中心落到地平线下 0.833° 才算日落（含大气折射）
  const w = hourAngle(h, phi, dec)
  if (Number.isNaN(w)) return null
  const Jset = solarTransitJ(approxTransit(w, lw, n), M, L)
  const Jrise = Jnoon - (Jset - Jnoon)
  return { rise: fromJulian(Jrise).getTime(), set: fromJulian(Jset).getTime() }
}

/* ---------------- 天色状态 ---------------- */

export type SkyState = {
  /** 当前该用哪个主题 */
  theme: 'light' | 'dark'
  /** 日出/日落进程 0~1：0 = 刚在地平线，1 = 对面地平线。驱动图标在山坡上的位置 */
  progress: number
  isDay: boolean
  /** 本次白天/夜间的起止（UTC 毫秒），给提示文案用 */
  from: number
  to: number
}

/**
 * 当前天色。
 * 夜间的"一天"按日历跨天算：昨天日落后到今天日出前，也算今天的夜里。
 */
export function skyState(nowMs = Date.now()): SkyState {
  const p = beijingParts(nowMs)
  const around = (delta: number) => {
    const dt = new Date(Date.UTC(p.y, p.m, p.d + delta, 4, 0, 0))
    return sunTimesUtc(dt.getUTCFullYear(), dt.getUTCMonth(), dt.getUTCDate())
  }
  const today = around(0)
  // 算不出来就用一个安全的默认值：白天、太阳挂在半山腰
  if (!today) return { theme: 'light', progress: 0.5, isDay: true, from: 0, to: 0 }

  if (nowMs >= today.rise && nowMs < today.set) {
    const span = Math.max(1, today.set - today.rise)
    return {
      theme: 'light',
      progress: Math.min(1, Math.max(0, (nowMs - today.rise) / span)),
      isDay: true,
      from: today.rise,
      to: today.set
    }
  }

  const before = nowMs < today.rise
  const night = before ? around(-1) : around(1)
  if (!night) return { theme: 'dark', progress: 0.5, isDay: false, from: 0, to: 0 }
  const from = before ? night.set : today.set
  const to = before ? today.rise : night.rise
  const span = Math.max(1, to - from)
  return {
    theme: 'dark',
    progress: Math.min(1, Math.max(0, (nowMs - from) / span)),
    isDay: false,
    from,
    to
  }
}

/* ---------------- 颜色插值（驱动小窗口里的天色渐变） ---------------- */

function hexRgb(h: string): [number, number, number] {
  return [
    parseInt(h.slice(1, 3), 16),
    parseInt(h.slice(3, 5), 16),
    parseInt(h.slice(5, 7), 16)
  ]
}
function mix(a: string, b: string, t: number): string {
  const A = hexRgb(a)
  const B = hexRgb(b)
  const c = A.map((v, i) => Math.round(v + (B[i] - v) * t))
  return `rgb(${c[0]},${c[1]},${c[2]})`
}

export type SkyPalette = {
  sky: string
  /** 远山 / 近山两层剪影 */
  ridgeFar: string
  ridgeNear: string
  /** 太阳或月亮的颜色 */
  orb: string
  /** 星星透明度 0~1，只有夜里接近午夜才亮 */
  stars: number
}

/**
 * 天色配色。
 * 关键是「离地平线越近越偏晨昏橙、升到中天越正常」——
 * 用 progress 距 0/1 的距离做插值，日出日落前后天色会自己变化，这就是"渐进式"。
 */
export function skyPalette(s: SkyState): SkyPalette {
  const edge = Math.min(s.progress, 1 - s.progress)
  const t = Math.min(1, edge / 0.2) // 0.2 之前算晨昏时段
  if (s.isDay) {
    return {
      sky: mix('#e8935a', '#4f9fe8', t),
      ridgeFar: mix('#8a6248', '#3c627a', t),
      ridgeNear: mix('#54382a', '#22404f', t),
      orb: '#ffd76b',
      stars: 0
    }
  }
  return {
    sky: mix('#2c3a6e', '#0b1128', t),
    ridgeFar: mix('#1d2748', '#0d1330', t),
    ridgeNear: mix('#121a34', '#05080f', t),
    orb: '#e9eeff',
    stars: 1 - t
  }
}
