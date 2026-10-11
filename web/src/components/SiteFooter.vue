<script setup lang="ts">
import { computed } from 'vue'
import type { SiteConfig } from '@/types/content'

const props = defineProps<{ site: SiteConfig }>()

function toTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

/** 备案信息总开关：字段不存在时默认开启，只有显式写成 false 才隐藏 */
const showBeian = computed(() => props.site.showBeian !== false)

const icp = computed(() =>
  showBeian.value ? (props.site.icpBeian || '').trim() : ''
)

const police = computed(() =>
  showBeian.value ? (props.site.policeBeian || '').trim() : ''
)

/**
 * 公安备案查询地址要的是号码里的纯数字部分：
 * 「粤公网安备 44010602000123号」→ 44010602000123
 * 填错或只填了文字时，退化到站点首页，避免出现死链。
 */
const policeHref = computed(() => {
  const digits = police.value.replace(/\D/g, '')
  return digits
    ? `https://beian.mps.gov.cn/#/query/webSearch?code=${digits}`
    : 'https://beian.mps.gov.cn/'
})
</script>

<template>
  <footer class="page footer">
    <div class="footer__cols">
      <div>
        <span class="nav__brand" style="padding: 0">
          <span class="nav__mark">{{ props.site.brandMark }}</span>
          {{ props.site.brandName }}
        </span>
        <p class="body" style="margin-top: 16px; max-width: 30ch" v-html="props.site.footerNote"></p>
      </div>

      <div v-for="(c, i) in props.site.footerCols || []" :key="i">
        <h5>{{ c.title }}</h5>
        <ul>
          <li v-for="(l, j) in c.links || []" :key="j">
            <a :href="l.href || '#'">{{ l.label }}</a>
          </li>
        </ul>
      </div>
    </div>

    <div class="footer__bottom">
      <span class="footer__legal">
        <span v-if="props.site.copyright">{{ props.site.copyright }}</span>

        <template v-if="icp">
          <span class="footer__sep" aria-hidden="true">·</span>
          <a class="beian" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">
            {{ icp }}
          </a>
        </template>

        <template v-if="police">
          <span class="footer__sep" aria-hidden="true">·</span>
          <a class="beian beian--police" :href="policeHref" target="_blank" rel="noopener noreferrer">
            <svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true" focusable="false">
              <path
                fill="currentColor"
                d="M8 1.25 13 3v4.6c0 3.05-2.1 5.72-5 7.15-2.9-1.43-5-4.1-5-7.15V3l5-1.75Zm0 1.6L4.2 4.35v3.25c0 2.25 1.47 4.3 3.8 5.5 2.33-1.2 3.8-3.25 3.8-5.5V4.35L8 2.85Z" />
            </svg>
            {{ police }}
          </a>
        </template>
      </span>

      <a class="to-top" href="#" @click.prevent="toTop">回到顶部 ↑</a>
    </div>
  </footer>
</template>
