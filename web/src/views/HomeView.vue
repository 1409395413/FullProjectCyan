<script setup lang="ts">
/**
 * 首页：内容全部来自 GET /api/content。
 * 模块顺序、显示与否、每个模块的文案与素材都由后台决定，改完即时生效 —— 不需要重新构建或部署。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { fetchContent } from '@/api'
import SiteNav from '@/components/SiteNav.vue'
import SiteFooter from '@/components/SiteFooter.vue'
import ModuleSection from '@/components/ModuleSection.vue'
import { initTracking, stopTracking } from '@/util/tracking'
import type { SiteContent } from '@/types/content'

/**
 * 首屏：先渲染本地缓存的上一份内容，再静默换成最新的。
 * ---------------------------------------------------------------------------
 * 站点内容来自 GET /api/content，纯 CSR。以前的做法是「拉到才渲染」，
 * 于是每次打开都会先看到一句「正在载入内容…」——哪怕内容一个字都没变。
 *
 * 这里是 stale-while-revalidate：
 *   1. setup 阶段同步读 localStorage，有就直接渲染（不等任何网络请求）；
 *   2. 网络请求回来后覆盖，差一条也不影响首帧；
 *   3. 请求失败时保留旧内容不白屏，只在角落给一句提示。
 *
 * 缓存 key 不掺版本号：内容变了是整体替换，旧结构被新结构覆盖，不会留残影。
 */
const CONTENT_CACHE_KEY = 'yc_content_cache'

function readCache(): SiteContent | null {
  try {
    const raw = localStorage.getItem(CONTENT_CACHE_KEY)
    return raw ? (JSON.parse(raw) as SiteContent) : null
  } catch {
    return null // 隐私模式、配额满、脏数据：都当作没缓存
  }
}

function writeCache(c: SiteContent) {
  try {
    localStorage.setItem(CONTENT_CACHE_KEY, JSON.stringify(c))
  } catch { /* 存不下不影响显示 */ }
}

const bootstrap = readCache()
const content = ref<SiteContent | null>(bootstrap)
const loading = ref(bootstrap === null)
const error = ref('')
/** 显示的是缓存里的旧内容，且最新一次拉取没成功 */
const stale = ref(false)
const activeId = ref('')

const visibleModules = computed(
  () => (content.value?.modules || []).filter((m) => m.visible !== false)
)

/* ---------- 滚动高亮当前板块 + 导航收展 ---------- */
// Apple 的做法：向下滚动时导航栏让位给内容（收窄），向上滚一点就立刻回来。
let lastY = 0
function onScroll() {
  const y = window.scrollY

  const mid = y + window.innerHeight / 3
  let current = visibleModules.value[0]?.id || ''
  for (const m of visibleModules.value) {
    const el = document.getElementById(m.id)
    if (el && el.offsetTop <= mid) current = m.id
  }
  activeId.value = current

  const root = document.documentElement
  if (y > 160 && y > lastY + 6) root.classList.add('nav-compact')
  else if (y < lastY - 6 || y < 80) root.classList.remove('nav-compact')
  lastY = y
}

/* ---------- 入场动画 ---------- */
let io: IntersectionObserver | null = null
function setupReveal() {
  io?.disconnect()

  // 老浏览器没有 IntersectionObserver —— 直接全部显示。
  // 入场动画只是锦上添花，绝不能因为它让内容变成一片看不见的空白。
  if (!('IntersectionObserver' in window)) {
    document.querySelectorAll('.reveal').forEach((el) => el.classList.add('is-in'))
    return
  }

  io = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) {
          e.target.classList.add('is-in')
          io?.unobserve(e.target)
        }
      }
    },
    /*
     * 这里必须是 threshold: 0，**不能用比例阈值**。
     * ------------------------------------------------------------------
     * 原来写的是 threshold: 0.12（露出 12% 才入场）。这在手机上会失效：
     * 「直播案例」板块纵向堆了 5 个案例，高度 6200px，12% 就是 747px ——
     * 比手机可视区（约 600~700px）还高，条件永远不成立，
     * 于是整个板块永远停在 opacity:0。内部的案例卡片只有 1200px 高、
     * 能正常触发，但父容器透明，用户看到的就是一整块空白。
     * 桌面端板块是横向铺开的、矮得多，所以一直没暴露 —— 只在手机上犯病。
     *
     * 改成 threshold 0 + 底部负边距：元素的顶边一进入视口下方 90% 处就入场，
     * 与元素本身多高完全无关。
     */
    { threshold: 0, rootMargin: '0px 0px -10% 0px' }
  )
  document.querySelectorAll('.reveal').forEach((el) => io?.observe(el))
}

onMounted(async () => {
  window.addEventListener('scroll', onScroll, { passive: true })
  initTracking()
  try {
    content.value = await fetchContent()
    writeCache(content.value)
    stale.value = false
    await nextTick()
    setupReveal()
    onScroll()
  } catch (e: any) {
    error.value = e?.message || '内容加载失败'
    // 有旧内容就继续显示旧的 —— 比起换成报错页，访客更愿意看到上一版内容
    stale.value = content.value !== null
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  io?.disconnect()
  document.documentElement.classList.remove('nav-compact')
  stopTracking()
})
</script>

<template>
  <template v-if="content">
    <SiteNav :site="content.site" :modules="visibleModules" :active-id="activeId" />
  </template>

  <main v-if="content" class="page">
    <div class="grid">
      <ModuleSection v-for="m in visibleModules" :key="m.id" :module="m" />
    </div>
    <SiteFooter v-if="content" :site="content.site" />
  </main>

  <div v-else-if="loading" class="boot-grid" aria-hidden="true">
    <div class="boot-hero"></div>
    <div class="boot-cards">
      <span></span><span></span><span></span><span></span><span></span><span></span>
    </div>
  </div>
  <div v-else class="state-box error">
    <p>{{ error }}</p>
    <p class="hint">确认后端已启动，并且 <code>VITE_API_BASE</code> 指向正确。</p>
  </div>

  <!-- 网络没成功，但手上还有上一份内容：不打断浏览，只在角落说一句 -->
  <div v-if="stale" class="stale-tip" role="status">
    内容更新失败，当前显示的是上次打开的版本
  </div>
</template>

<style scoped>
.state-box {
  padding: 25vh 24px;
  text-align: center;
  color: var(--ink-2);
  font-size: 17px;
}
.state-box.error { color: #d70015; }
.state-box .hint { margin-top: 10px; font-size: 13px; color: var(--ink-3); }
.state-box code {
  padding: 2px 6px;
  border-radius: 6px;
  background: rgba(0, 0, 0, .06);
  font-size: 12px;
}

/* 首次进站（还没有本地缓存）时的骨架屏 —— 比一句"正在载入"更像网站 */
.boot-grid {
  max-width: 1200px;
  margin: 0 auto;
  padding: 96px 24px 64px;
}
.boot-hero {
  height: 46vh;
  min-height: 260px;
  border-radius: 22px;
  background: var(--surface, rgba(120, 120, 128, .1));
  animation: boot-pulse 1.6s ease-in-out infinite;
}
.boot-cards {
  margin-top: 22px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 16px;
}
.boot-cards span {
  aspect-ratio: 4 / 3;
  border-radius: 16px;
  background: var(--surface, rgba(120, 120, 128, .1));
  animation: boot-pulse 1.6s ease-in-out infinite;
}
.boot-cards span:nth-child(2) { animation-delay: .08s; }
.boot-cards span:nth-child(3) { animation-delay: .16s; }
.boot-cards span:nth-child(4) { animation-delay: .24s; }
.boot-cards span:nth-child(5) { animation-delay: .32s; }
.boot-cards span:nth-child(6) { animation-delay: .4s; }

@keyframes boot-pulse {
  0%, 100% { opacity: .55; }
  50% { opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .boot-hero, .boot-cards span { animation: none; }
}

.stale-tip {
  position: fixed;
  left: 50%;
  bottom: 18px;
  transform: translateX(-50%);
  z-index: 40;
  padding: 8px 14px;
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-2);
  background: var(--glass-bg, rgba(250, 250, 250, .82));
  border: 1px solid var(--line, rgba(0, 0, 0, .1));
  backdrop-filter: blur(12px);
}
</style>
