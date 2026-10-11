<script setup lang="ts">
/**
 * 案例：图文左右分栏 + 参数表 + 标签。
 * ---------------------------------------------------------------------------
 * 素材组：一个案例可以挂任意多条素材，视频和图片混排。
 * 主展示位显示当前选中的那条，下方一条悬浮缩略轨用来点选切换。
 *
 * 四条硬规矩：
 *   1. **版式由第一条素材定** —— 第一条是竖屏就竖版画框、横屏就横版画框。
 *      之后在缩略轨里切到别的素材，画框不动、只是内容换掉，
 *      版面不会跟着跳来跳去；
 *   2. 画面一律 contain 完整显示 —— cover 会砍掉构图，摄影 / 剪辑的素材不能这么裁；
 *   3. 缩略轨常驻 —— 不常驻的话访客根本不知道这个案例还能翻；
 *   4. 视频点一下才播、图片双击开大图 —— 见下面 activate / zoom 两段。
 */
import { ref } from 'vue'
import MediaFrame from '../MediaFrame.vue'
import type { CasesModule, MediaItem } from '@/types/content'
import { caseMedia, nativeFrame, posterUrlOf } from '@/util/content'

const props = defineProps<{ module: CasesModule }>()

/** 每个案例各自记一个「当前看第几条」，key 用案例下标 */
const active = ref<Record<number, number>>({})

function listOf(c: any): MediaItem[] {
  return caseMedia(c)
}

function cur(i: number, c: any): number {
  const n = listOf(c).length
  if (!n) return 0
  const v = active.value[i] ?? 0
  return v >= n ? 0 : v
}

/** 当前主展示位那一条 */
function curItem(i: number, c: any): MediaItem | undefined {
  return listOf(c)[cur(i, c)]
}

function pick(i: number, k: number) {
  active.value[i] = k
}

const showRail = (c: any) => listOf(c).length > 1

const isVideo = (m?: MediaItem) => m?.kind === 'video'

/**
 * 版式：跟随**当前正在看的那条**素材的真实比例。
 * ---------------------------------------------------------------------------
 * 最初版式由第一条素材决定（active 默认是 0，首屏看到的就是它），
 * 但一旦在缩略轨里切到别的素材，画框要跟着变 ——
 * 之前「锁死在第一条」的做法会让横屏图塞在竖版画框里，
 * 上下留出两大条底色（contain 不裁切，空白处露出 .ph 那层浅灰），
 * 四角尤其明显，看着像白边。
 *
 * nativeFrame 内部已经做了两件事，正好是这里要的：
 *   · 已知宽高就按真实比例（并夹到 0.5~2.2，免得极端比例把版面撑爆）；
 *   · 尺寸还没探测到就按方向猜 —— 竖 9:16、方 1:1、横 16:9。
 * 所以无论素材有没有尺寸元数据，版式都是稳定的，不会先横后竖闪一下。
 */
function frameOf(m?: MediaItem | null): string {
  return nativeFrame(m)
}

/** 缩略轨角标：视频优先显示时长标记，没有就用一个 ▶ */
function railBadge(m: MediaItem) {
  return isVideo(m) ? m.dur || '▶' : '🖼'
}

/** 缩略图：图片用自己，视频优先手填封面、没有就用后端抽的首帧 */
function thumbOf(m: MediaItem) {
  if (!m.src) return ''
  if (!isVideo(m)) return m.src
  return m.poster || posterUrlOf(m.src) || ''
}

/** 主展示位是不是视频 —— 决定要不要给「点击可播放」的手型光标 */
function isCurVideo(i: number, c: any): boolean {
  return isVideo(curItem(i, c))
}
</script>

<template>
  <div>
    <div v-for="(c, i) in props.module.cases || []" :key="i" class="case reveal">
      <div>
        <div class="case-media">
          <!--
            主展示位。
            · frame = 画框比例，跟着当前这条素材的真实比例走（切素材会平滑变过去）；
            · fit = contain，完整显示不裁切 —— 构图不能被替作者改；
            · clickToPlay，视频点一下才出控件并开始播，之前只显示首帧封面。
          -->
          <div class="case-frame" :class="{ 'is-playable': isCurVideo(i, c) }">
            <MediaFrame
              :item="curItem(i, c)"
              :frame="frameOf(curItem(i, c))"
              fit="contain"
              click-to-play
            />
          </div>

          <!-- 悬浮缩略轨：常驻，点哪条主位就切到哪条 -->
          <div
            v-if="showRail(c)"
            class="rail"
            role="tablist"
            :aria-label="`${c.title} 素材切换`"
          >
            <button
              v-for="(m, k) in listOf(c)"
              :key="k"
              class="rail__item"
              :class="{ on: cur(i, c) === k }"
              role="tab"
              :aria-selected="cur(i, c) === k"
              :title="m.title || `素材 ${k + 1}`"
              @click="pick(i, k)"
            >
              <!--
                缩略轨只加载一张封面图，不碰视频本体：
                · 图片素材直接用自己；
                · 视频素材用后台填的 poster，没有就用后端抽的首帧；
                · 以前兜底挂的是 <video preload="none">，浏览器什么都不渲染 —— 缩略图是一块空，
                  现在有了首帧接口，这条兜底基本不会再走到。
              -->
              <img
                v-if="thumbOf(m)"
                :src="thumbOf(m)"
                alt=""
                loading="lazy"
                draggable="false"
                @contextmenu.prevent
              />
              <video
                v-else-if="m.src"
                :src="m.src"
                muted
                playsinline
                preload="none"
                draggable="false"
              ></video>
              <span class="rail__badge">{{ railBadge(m) }}</span>
            </button>
          </div>
        </div>

        <p v-if="showRail(c)" class="rail__hint small">
          {{ listOf(c).length }} 个素材 · 点缩略图切换观看
        </p>
      </div>

      <div>
        <h3 class="t-md">{{ c.title }}</h3>
        <p v-if="c.desc" class="body" style="margin-top: 12px">{{ c.desc }}</p>

        <dl v-if="c.specs?.length" class="specs">
          <div v-for="s in c.specs" :key="s.k" class="spec">
            <dt>{{ s.k }}</dt>
            <dd>{{ s.v }}</dd>
          </div>
        </dl>

        <div v-if="c.tags?.length" class="chip-row" style="margin-top: 18px">
          <span v-for="t in c.tags" :key="t" class="chip chip--flat">{{ t }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.case-media { position: relative; }

/*
 * 主展示位的容器：只包 MediaFrame、不包缩略轨。
 * 这样鼠标移到缩略轨上时不会误触发「抬起」—— 缩略轨是按钮，
 * 它自己有 hover 反馈，两个动画叠在一起会显得很乱。
 */
.case-frame {
  position: relative;
  border-radius: inherit;
  /* --jelly 末段 >1，抬起来会有一点回弹，就是那种「弹一下」的手感 */
  transition: transform .42s var(--jelly, cubic-bezier(.34, 1.56, .64, 1)),
              box-shadow .3s var(--ease, ease);
  will-change: transform;
}

/*
 * 切素材时画框要跟着当前素材的比例变 —— 让它平滑地"长"过去，而不是啪一下跳。
 * aspect-ratio 是可动画属性（Chrome/Edge/Safari 支持）；不支持的浏览器
 * 会退化成瞬间切换，功能不受影响，只是少了过渡。
 */
.case-frame :deep(.media-frame) {
  transition: aspect-ratio .4s var(--ease, ease);
}

@media (hover: hover) and (pointer: fine) {
  .case-frame:hover {
    transform: translateY(-7px);
    box-shadow: 0 24px 50px rgba(0, 0, 0, .2);
  }
}
/* 按下去稍微收回一点，给出「按到实处」的触感 */
.case-frame:active { transform: translateY(-3px); }

/* 视频可点播 —— 给个手型提示，图片保持默认光标（不再做双击放大） */
.case-frame.is-playable { cursor: pointer; }

/* 悬浮缩略轨：叠在主画面底部，半透明玻璃，不挡主体也不喧宾夺主 */
.rail {
  position: absolute;
  left: 50%;
  bottom: 10px;
  transform: translateX(-50%);
  display: flex;
  gap: 6px;
  max-width: calc(100% - 20px);
  padding: 6px;
  border-radius: 12px;
  background: rgba(10, 12, 18, .5);
  backdrop-filter: blur(8px);
  overflow-x: auto;
  scrollbar-width: thin;
}
.rail__item {
  position: relative;
  flex: 0 0 auto;
  width: 62px;
  height: 40px;
  padding: 0;
  border: 1.5px solid transparent;
  border-radius: 7px;
  overflow: hidden;
  background: rgba(255, 255, 255, .1);
  cursor: pointer;
  transition: border-color .16s, transform .16s;
}
.rail__item:hover { transform: translateY(-2px); }
.rail__item.on {
  border-color: var(--accent, #2997ff);
  box-shadow: 0 0 0 2px rgba(41, 151, 255, .28);
}
.rail__item img,
.rail__item video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.rail__badge {
  position: absolute;
  right: 3px;
  bottom: 2px;
  padding: 0 4px;
  border-radius: 4px;
  background: rgba(0, 0, 0, .62);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 14px;
}
.rail__hint { margin-top: 10px; opacity: .8; }

/* 窄屏：不再悬浮，改为整行横向滑动，避免挡住画面 */
@media (max-width: 720px) {
  .rail {
    position: static;
    transform: none;
    max-width: 100%;
    margin-top: 8px;
    background: rgba(127, 127, 127, .14);
    backdrop-filter: none;
  }
  /* 触屏没有 hover，抬起效果自然不触发；点按时给一点回弹就够 */
  .case-frame { transition: transform .3s var(--jelly, cubic-bezier(.34, 1.56, .64, 1)); }
  .zoom { padding: 24px 14px; }
  .zoom__x { top: 6px; right: 6px; }
}
</style>
