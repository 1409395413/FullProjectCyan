<script setup lang="ts">
/**
 * 标签管理：改名 / 删除 / 清理空标签。
 * ---------------------------------------------------------------------------
 * 为什么需要独立入口，而不是在打标签的框里顺手改：标签和素材是<b>多对多</b>的关系，
 * 「直播」这个名字出现在几十条素材上。改它是一次全局操作，跟编辑某一条素材的标签
 * 完全不是一回事 —— 混在一个弹窗里，很容易让人以为自己只改了眼前这一条。
 *
 * 删标签同样只解关联，<b>素材一个都不删</b>。这是最能吓人的一步，所以每个删都要确认。
 */
import { ref, watch } from 'vue'
import { adminApi } from '@/api'
import type { MediaTagView } from '@/types/content'

const props = defineProps<{ open: boolean; tags: MediaTagView[] }>()
const emit = defineEmits<{ close: []; changed: [] }>()

const busy = ref(false)
const error = ref('')
const note = ref('')

/** 正在重命名的标签。null = 没在编辑 */
const editing = ref<string | null>(null)
const renameTo = ref('')
const confirmDelete = ref<string | null>(null)

watch(
  () => props.open,
  (v) => {
    if (!v) return
    error.value = ''
    note.value = ''
    editing.value = null
    renameTo.value = ''
    confirmDelete.value = null
  }
)

function startRename(name: string) {
  editing.value = name
  renameTo.value = name
  confirmDelete.value = null
}

async function submitRename(from: string) {
  const to = renameTo.value.trim().replace(/^#+/, '').trim()
  if (!to || to === from) {
    editing.value = null
    return
  }
  busy.value = true
  error.value = ''
  try {
    const r = await adminApi.renameTag(from, to)
    editing.value = null
    emit('changed')
    note.value = r.applied
      ? `「${from}」已改为「${to}」，${r.applied} 条素材的标签同步更新`
      : `「${from}」已改为「${to}」`
  } catch (e: any) {
    error.value = e?.message || '改名失败'
  } finally {
    busy.value = false
  }
}

async function doDelete(name: string) {
  busy.value = true
  error.value = ''
  try {
    const r = await adminApi.deleteTag(name)
    confirmDelete.value = null
    emit('changed')
    note.value = `已删除标签「${name}」，解除 ${r.removed} 处关联（素材都没动）`
  } catch (e: any) {
    error.value = e?.message || '删除失败'
  } finally {
    busy.value = false
  }
}

async function pruneEmpty() {
  busy.value = true
  error.value = ''
  try {
    const r = await adminApi.pruneEmptyTags()
    emit('changed')
    note.value = r.removed ? `已清理 ${r.removed} 个没人用的标签` : '没有空标签'
  } catch (e: any) {
    error.value = e?.message || '清理失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="tm-mask" @click.self="emit('close')">
      <div class="tm glass" role="dialog" aria-modal="true" aria-label="管理标签">
        <div class="tm__head">
          <h3 class="t-md">管理标签</h3>
          <button class="mini" @click="emit('close')">关闭</button>
        </div>

        <p class="small">
          改名会把所有素材上的这个标签一起改掉；删除只是解除关联，
          <b>素材本身不会被删除</b>。
        </p>

        <div v-if="!tags.length" class="small">还没有标签。给素材打一个，这里就会列出来。</div>

        <ul v-else class="tm__list">
          <li v-for="t in tags" :key="t.name">
            <template v-if="editing === t.name">
              <input
                v-model="renameTo"
                class="mini-input"
                autofocus
                @keydown.enter.prevent="submitRename(t.name)"
                @keydown.esc="editing = null"
              />
              <span class="row-tools">
                <button class="mini primary" :disabled="busy" @click="submitRename(t.name)">保存</button>
                <button class="mini" @click="editing = null">取消</button>
              </span>
            </template>
            <template v-else>
              <span class="tm__name">{{ t.name }}</span>
              <i class="tm__count">{{ t.count }} 个素材</i>
              <span class="row-tools tm__tools">
                <button class="mini" @click="startRename(t.name)">改名</button>
                <button
                  v-if="confirmDelete === t.name"
                  class="mini danger is-armed"
                  :disabled="busy"
                  @click="doDelete(t.name)"
                >
                  确认删除？
                </button>
                <button
                  v-else
                  class="mini danger"
                  @click="confirmDelete = t.name"
                >
                  删除
                </button>
              </span>
            </template>
          </li>
        </ul>

        <p v-if="error" class="media-error">{{ error }}</p>
        <p v-if="note" class="small">{{ note }}</p>

        <div class="tm__foot">
          <span class="small">共 {{ tags.length }} 个标签</span>
          <span class="row-tools">
            <button class="mini" :disabled="busy" @click="pruneEmpty">
              {{ busy ? '处理中…' : '清理空标签' }}
            </button>
          </span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.tm-mask {
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
.tm {
  width: min(560px, 100%);
  max-height: min(86vh, 720px);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  overflow: auto;
}
.tm__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.tm__list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.tm__list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: var(--r-s, 10px);
  background: rgba(0, 0, 0, .04);
}
.tm__name {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tm__count {
  font-style: normal;
  font-size: 12px;
  color: var(--ink-3);
  white-space: nowrap;
}
.tm__tools { margin-left: auto; }
.tm__list .mini-input { flex: 1; }
.tm__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line, rgba(255, 255, 255, .12));
}
</style>
