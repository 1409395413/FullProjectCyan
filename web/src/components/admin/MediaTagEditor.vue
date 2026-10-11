<script setup lang="ts">
/**
 * 给素材打标签。
 * ---------------------------------------------------------------------------
 * 一个组件同时管「单条素材」和「勾选了二十条」两种情况，差别只在两个默认值上：
 *
 *   · 标签初值：取所有选中素材标签的<b>并集</b>。这样批量编辑时，已有的标签先摆出来，
 *     你是在这份基础上加减，而不是面对一个空的输入框去猜它们原本有什么。
 *   · 写入方式：默认是「追加」（replace=false），各素材原有的标签不丢。
 *     勾二十张图只想加一个「直播截图」，如果按覆盖写，其余素材互相交换标签就乱了 ——
 *     追加是这种场景下唯一说得通的默认行为。想统一成一模一样，取消下面那个勾选即可。
 *
 * 单条素材时那格勾选没有副作用（它本来只有一个），默认覆盖也符合直觉，
 * 所以按素材数量切换默认值，而不是让用户每次都记着改。
 */
import { computed, ref, watch } from 'vue'
import { adminApi } from '@/api'
import type { MediaItemView, MediaTagView } from '@/types/content'

const props = defineProps<{
  open: boolean
  /** 要打标签的素材。长度 1 是单条，>1 是批量 */
  items: MediaItemView[]
  /** 全站已有标签，用于输入时的下拉建议 */
  allTags: MediaTagView[]
}>()
const emit = defineEmits<{ close: []; saved: [] }>()

const draft = ref<string[]>([])
const input = ref('')
const keepExisting = ref(true)
const busy = ref(false)
const error = ref('')

/** 选中素材标签的并集 */
const union = computed(() => {
  const out: string[] = []
  for (const m of props.items) {
    for (const t of m.tags || []) {
      if (!out.includes(t)) out.push(t)
    }
  }
  return out
})

/** 并集之外的、素材尚未用到但系统里已有的标签 —— 摆在下面方便一键加 */
const suggestions = computed(() =>
  props.allTags
    .map((t) => t.name)
    .filter((n) => !draft.value.includes(n))
)

watch(
  () => props.open,
  (v) => {
    if (!v) return
    error.value = ''
    input.value = ''
    draft.value = union.value.slice()
    // 批量默认「保留原有」（追加），单条默认「以此为准」（覆盖）
    keepExisting.value = props.items.length > 1
  }
)

function add(name: string) {
  const n = name.trim().replace(/^#+/, '').trim()
  if (!n) return
  if (n.length > 32) {
    error.value = '标签名最长 32 个字'
    return
  }
  if (!draft.value.includes(n)) draft.value.push(n)
  input.value = ''
  error.value = ''
}

function remove(name: string) {
  draft.value = draft.value.filter((x) => x !== name)
}

async function save() {
  if (!props.items.length) return
  busy.value = true
  error.value = ''
  try {
    await adminApi.applyTags({
      paths: props.items.map((m) => m.path),
      tags: draft.value,
      replace: !keepExisting.value
    })
    emit('saved')
    emit('close')
  } catch (e: any) {
    error.value = e?.message || '保存标签失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!-- Teleport 到 body：祖先里有 .glass（backdrop-filter 会成为 fixed 子元素的包含块），
       不挪出来的话弹窗会被甩出视口。同 MediaPicker。 -->
  <Teleport to="body">
    <div v-if="open" class="tag-mask" @click.self="emit('close')">
      <div class="tag-dlg glass" role="dialog" aria-modal="true" aria-label="编辑标签">
        <div class="tag-dlg__head">
          <h3 class="t-md">
            标签
            <span class="small">{{ items.length > 1 ? `${items.length} 个素材` : '单个素材' }}</span>
          </h3>
          <button class="mini" @click="emit('close')">关闭</button>
        </div>

        <p class="small">
          标签只用来在后台筛选归类，<b>不会改变素材本身</b>，也不影响前台显示的内容。
        </p>

        <div class="tag-edit-row">
          <input
            v-model="input"
            class="mini-input"
            :list="'tag-suggest-' + items.length"
            placeholder="输入新标签名，回车添加"
            @keydown.enter.prevent="add(input)"
          />
          <datalist :id="'tag-suggest-' + items.length">
            <option v-for="s in suggestions" :key="s" :value="s"></option>
          </datalist>
          <button class="mini" :disabled="!input.trim()" @click="add(input)">添加</button>
        </div>

        <div v-if="draft.length" class="tag-chips">
          <span v-for="t in draft" :key="t" class="tag-chip tag-chip--on">
            {{ t }}
            <b @click="remove(t)">✕</b>
          </span>
        </div>
        <p v-else class="small">还没有标签。在输入框里写一个名字，回车即可新建。</p>

        <div v-if="suggestions.length" class="tag-lib">
          <span class="small">已有标签：</span>
          <button
            v-for="s in suggestions.slice(0, 20)"
            :key="s"
            class="tag-chip"
            @click="add(s)"
          >
            + {{ s }}
          </button>
        </div>

        <label v-if="items.length > 1" class="tag-lv">
          <input v-model="keepExisting" type="checkbox" />
          <span>
            保留各素材原有的标签（只追加）
            <small>
              勾选 = 添加下面的标签，谁原本有什么不会被拿走；
              取消 = 这些素材的标签统一变成下面这一份。
            </small>
          </span>
        </label>

        <p v-if="error" class="media-error">{{ error }}</p>

        <div class="tag-dlg__foot">
          <span class="small">共 {{ draft.length }} 个标签</span>
          <span class="row-tools">
            <button class="mini" @click="emit('close')">取消</button>
            <button class="mini primary" :disabled="busy" @click="save">
              {{ busy ? '保存中…' : `保存（应用到 ${items.length} 个素材）` }}
            </button>
          </span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.tag-mask {
  position: fixed;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(8, 10, 14, .62);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
  overscroll-behavior: contain;
}
.tag-dlg {
  width: min(560px, 100%);
  max-height: min(86vh, 760px);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  overflow: auto;
}
.tag-dlg__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tag-dlg__head .small { font-weight: 400; margin-left: 8px; }
.tag-dlg__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line, rgba(255, 255, 255, .12));
}
.tag-edit-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.tag-edit-row .mini-input { flex: 1; }
.tag-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag-lib {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.tag-lv {
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: start;
  gap: 4px 8px;
  padding: 8px 10px;
  border-radius: var(--r-s, 10px);
  background: rgba(41, 151, 255, .08);
  font-size: 13px;
  cursor: pointer;
}
.tag-lv input { width: 15px; height: 15px; margin-top: 2px; }
.tag-lv small {
  display: block;
  grid-column: 2;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.5;
}
</style>
