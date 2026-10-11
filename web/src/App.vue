<template>
  <!-- 液态毛玻璃底噪：没有这层，backdrop-filter 就没有可透的东西 -->
  <div class="mesh" aria-hidden="true"></div>
  <RouterView />
  <!-- 昼夜切换 + 实时时钟：常驻左下角，滚动不跟随 -->
  <ThemeDock />
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { RouterView } from 'vue-router'
import ThemeDock from './components/ThemeDock.vue'

/**
 * Liquid Glass 的"活"：让玻璃表面的镜面高光跟着指针跑。
 *
 * 对每个玻璃元素写入本地百分比坐标 --mx / --my，CSS 里用 radial-gradient 消费。
 * 只在有精确指针（鼠标/触控笔）时启用，触屏不参与 —— 省电也避免误触抖动。
 * 用 rAF 节流，一帧最多算一次。
 */
const GLASS_SELECTOR = '.glass, .nav__pill, .chip, .btn--ghost, .sitem'

let raf = 0
let pending: PointerEvent | null = null

function apply() {
  raf = 0
  const e = pending
  if (!e) return
  pending = null
  const els = document.querySelectorAll<HTMLElement>(GLASS_SELECTOR)
  for (const el of els) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    // 元素在视口外的不计算，滚动长页面时省掉大部分开销
    if (r.bottom < -200 || r.top > window.innerHeight + 200) continue
    el.style.setProperty('--mx', (((e.clientX - r.left) / r.width) * 100).toFixed(1) + '%')
    el.style.setProperty('--my', (((e.clientY - r.top) / r.height) * 100).toFixed(1) + '%')
  }
}

function onMove(e: PointerEvent) {
  if (e.pointerType === 'touch') return
  pending = e
  if (!raf) raf = requestAnimationFrame(apply)
}

onMounted(() => {
  window.addEventListener('pointermove', onMove, { passive: true })
})
onBeforeUnmount(() => {
  window.removeEventListener('pointermove', onMove)
  if (raf) cancelAnimationFrame(raf)
})
</script>
