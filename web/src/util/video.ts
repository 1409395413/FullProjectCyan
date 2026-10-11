/**
 * 全局视频播放治理。
 * ---------------------------------------------------------------------------
 * 规则只有两条：
 *   1. 同一时刻只允许一个「主动点开」的视频在播 —— 点开新的，旧的立刻停；
 *   2. 滚出视口（露出不足 1/3）就自动停掉并通知画廊收牌。
 *
 * 唯一例外：首屏 Showreel（MediaFrame 在 autoplay 模式下打上 data-hero-loop）。
 * 它是静音循环的氛围视频，要求一直播，上面两条对它都不生效。
 *
 * 为什么用 document 级捕获监听：
 *   play / pause 事件不冒泡，只能在捕获阶段收；这样新增任何播放入口
 *   （叠牌画廊、灯箱、自定义模块）都不用各自注册，统一在这里治理。
 */

/** 露出低于这个比例就算「已经不在视口里」 */
const VISIBLE_RATIO = 0.34

/** 首屏 Showreel 的豁免标记（MediaFrame 在 autoplay 时写入） */
const HERO_ATTR = 'data-hero-loop'

/** 视频被自动关闭时发出，画廊收到后收牌 */
export const VIDEO_CLOSE_EVENT = 'portfolio:video-close'

let installed = false
const observers = new WeakMap<HTMLVideoElement, IntersectionObserver>()

function isVideo(el: EventTarget | null): el is HTMLVideoElement {
  return !!el && (el as Element).tagName === 'VIDEO'
}

function isHero(v: HTMLVideoElement): boolean {
  return v.getAttribute(HERO_ATTR) === '1'
}

function notifyClosed(v: HTMLVideoElement) {
  window.dispatchEvent(new CustomEvent(VIDEO_CLOSE_EVENT, { detail: { video: v } }))
}

/** 停掉一个视频：暂停 + 回到开头，并广播「已关闭」 */
function stop(v: HTMLVideoElement) {
  try {
    v.pause()
    v.currentTime = 0
  } catch { /* 部分浏览器在没 metadata 时设 currentTime 会抛，忽略 */ }
  notifyClosed(v)
}

/** 除了 except（以及首屏视频）之外，停掉页面上所有正在播放的视频 */
function stopOthers(except?: HTMLVideoElement) {
  document.querySelectorAll('video').forEach((v) => {
    if (v === except || isHero(v)) return
    if (!v.paused || v.currentTime > 0) stop(v)
  })
}

/**
 * 「还看得见吗」的判定。
 * ---------------------------------------------------------------------------
 * 不能直接拿 intersectionRatio（可见面积 / 元素自身面积）当标准：
 * 手机视口小，一个竖屏素材的画框常常比视口还高，此时它就算完全铺满屏幕，
 * intersectionRatio 也只有 0.5 上下 —— 一旦低于阈值就会被判定成「滚出去了」，
 * 于是用户刚点开、画面刚出来就被立刻 pause()，看着就是"点了播不了"。
 * 所以基准取 min(元素高, 视口高)：元素比视口大时按视口算，铺满就算可见。
 */
function visibleEnough(e: IntersectionObserverEntry, v: HTMLVideoElement): boolean {
  if (!e.isIntersecting) return false
  const ref = Math.min(v.clientHeight || 0, window.innerHeight) || window.innerHeight
  return e.intersectionRect.height >= ref * VISIBLE_RATIO
}

/** iOS 全屏播放时页面上的元素依然是"可见"的，别在这时候去暂停它 */
function inFullscreen(v: HTMLVideoElement): boolean {
  return (v as HTMLVideoElement & { webkitDisplayingFullscreen?: boolean })
    .webkitDisplayingFullscreen === true
}

/** 监听可见性：滚出视口就停 */
function watchVisibility(v: HTMLVideoElement) {
  if (!('IntersectionObserver' in window)) return
  if (observers.has(v)) return
  /**
   * 注册观察器后浏览器会**立刻**回调一次当前状态。
   * 如果这一下就是"可见度不够"（视频刚点开、只露出一截），就会把刚 play 的
   * 视频当场 pause() 掉 —— 用户在 iPhone 上的感受正是"点了没反应"。
   * 所以先记下"有没有真正露出来过"，没露出来过的那次回调只更新状态、不停车。
   */
  let everVisible = false
  const io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (inFullscreen(v)) return
        const ok = visibleEnough(e, v)
        if (ok) {
          everVisible = true
          continue
        }
        if (!everVisible) continue
        if (!v.paused) stop(v)
      }
    },
    { threshold: [0, VISIBLE_RATIO, 0.7] }
  )
  io.observe(v)
  observers.set(v, io)
}

function onPlay(e: Event) {
  if (!isVideo(e.target)) return
  const v = e.target
  if (isHero(v)) return          // 首屏视频：一直循环，不参与治理
  stopOthers(v)
  watchVisibility(v)
}

/**
 * 页面切到后台：把非首屏的视频全停（省电，也避免切回来时好几个一起响）。
 * 切回来：把首屏 Showreel 重新拉起来 —— 之前只停不恢复，切回标签页时
 * 首屏是一张静止的封面，看着像卡住了。
 */
function onVisibility() {
  if (document.hidden) {
    stopOthers()
    return
  }
  document.querySelectorAll<HTMLVideoElement>('video[' + HERO_ATTR + '="1"]').forEach((v) => {
    const p = v.play()
    if (p && p.catch) p.catch(() => { /* 用户还没交互过可能被拒，忽略 */ })
  })
}

export function installVideoGovernor() {
  if (installed) return
  installed = true
  document.addEventListener('play', onPlay, true)
  document.addEventListener('visibilitychange', onVisibility)
}
