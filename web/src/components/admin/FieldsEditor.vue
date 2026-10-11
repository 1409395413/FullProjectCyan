<script setup lang="ts">
/**
 * 通用表单渲染器 —— 后台所有编辑界面都由 moduleSchema 驱动生成。
 * 递归处理 group / list，所以嵌套结构（按钮组、案例列表、页脚分栏）都不需要手写组件。
 */
import MediaItemEditor from './MediaItemEditor.vue'
import { ref } from 'vue'
import { PRESET_PAGES, presetKeyOf, type FieldDef } from '@/moduleSchema'
import type { MediaItem, MediaItemView } from '@/types/content'

const props = defineProps<{
  fields: FieldDef[]
  /** 直接就地修改传入的对象（它是 reactive 的） */
  model: Record<string, any>
}>()

// 显式命名，才能在本组件模板里递归自引用
defineOptions({ name: 'FieldsEditor' })

const flatFields = () => props.fields

/**
 * 列表行的稳定身份 id。
 * ---------------------------------------------------------------------------
 * 以前这几处 v-for 用的是数组下标 `:key="i"`，它表达的意思是
 * 「第 N 行永远是同一个东西」。于是在案例的素材组里：
 *
 *   · 删掉中间一条 —— 后面每一行的 key 整体平移一位，
 *     Vue 不会销毁任何组件实例，只是把实例按位置重排后用新数据打补丁；
 *   · MediaItemEditor 里的 <video> 只改了 src 属性，而浏览器对 video 改 src
 *     **不会自动重新加载**（img 会，video 不会），那一帧旧画面就留在了下一行
 *     —— 表现就是「明明删掉了，缩略图还在」；
 *   · 新增一条时又反过来：新插入的空条目复用了上一行那个实例，
 *     它的 <video> 里还挂着上一个素材的资源 —— 表现就是
 *     「刚添加就凭空多出一个旧素材的占位块」。
 *   组件内部状态同样会串位：uploading / error / HEVC 提示语、素材库面板的开关状态。
 *
 * 换成「对象 → 稳定 id」之后，id 跟对象本身绑死，位置怎么变都跟着走，
 * Vue 就能分清「谁真的被删了」和「谁只是挪了位」。
 *
 * 用 WeakMap 而不是往对象里写 `_id`：payload 是存在数据库里的整份 JSON，
 * 多写字段会污染数据、还得额外在后端过滤，这里让它「只在内存里有效」。
 */
const rowKeys = new WeakMap<object, string>()
let rowSeq = 0
function keyOf(item: unknown): string {
  if (item === null || typeof item !== 'object') return 'x' + String(item)
  let k = rowKeys.get(item as object)
  if (!k) {
    rowSeq += 1
    k = 'r' + rowSeq
    rowKeys.set(item as object, k)
  }
  return k
}

/* ---------- 值读写 ---------- */
function ensure(key: string, init: any) {
  if (props.model[key] === undefined || props.model[key] === null) props.model[key] = init
  return props.model[key]
}

function toList(key: string): any[] {
  return ensure(key, [])
}
function toObject(key: string): Record<string, any> {
  return ensure(key, {})
}
function linesToArray(key: string, text: string) {
  props.model[key] = text
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
}
function arrayToLines(key: string): string {
  const v = props.model[key]
  return Array.isArray(v) ? v.join('\n') : ''
}

/* ---------- 链接选择 ---------- */
/**
 * 链接字段：既能从预置页面里挑，也能自己填。
 * 选「自定义」时如果当前是某个预置页，就把输入框清空，
 * 免得用户以为改了下拉但保存的还是预置值。
 */
function onPresetPick(key: string, v: string) {
  const cur = String(props.model[key] || '').trim()
  if (v === '__custom') {
    if (PRESET_PAGES.some((p) => p.v === cur)) props.model[key] = ''
    return
  }
  props.model[key] = v
}

/* ---------- 列表操作 ---------- */
function addItem(f: FieldDef) {
  const item: Record<string, any> = {}
  for (const sub of f.fields || []) {
    if (sub.type === 'kv' || sub.type === 'stringlist') item[sub.key] = []
    else if (sub.type === 'list' || sub.type === 'medialist') item[sub.key] = []
    else if (sub.type === 'media') item[sub.key] = { kind: 'image', src: '', title: '' }
    else if (sub.type === 'select') item[sub.key] = sub.options?.[0]?.v ?? ''
    else if (sub.type === 'switch') item[sub.key] = false
    else item[sub.key] = ''
  }
  toList(f.key).push(item)
}

function addMedia(key: string) {
  toList(key).push({ kind: 'image', src: '', title: '' } as MediaItem)
}

/**
 * 素材库里一次勾了多条：第一条由 MediaItemEditor 自己消化，
 * 剩下的在这里追加成新条目，省得一条条点开选。
 */
function appendMedia(key: string, extras: MediaItemView[]) {
  const arr = toList(key)
  for (const m of extras) {
    arr.push({
      kind: (m.mime || '').startsWith('video/') ? 'video' : 'image',
      src: m.url,
      w: m.width,
      h: m.height,
      title: (m.url || '').split('/').pop() || ''
    } as MediaItem)
  }
}

/** 当前列表里已经用到的素材路径，传给选取器打「已在此模块」标 */
function usedSrcs(key: string): string[] {
  return toList(key).map((it) => String(it?.src || '')).filter(Boolean)
}

function move(arr: any[], i: number, dir: -1 | 1) {
  const j = i + dir
  if (j < 0 || j >= arr.length) return
  const t = arr[i]
  arr[i] = arr[j]
  arr[j] = t
}

function remove(arr: any[], i: number) {
  arr.splice(i, 1)
}

/**
 * 整条素材要删就删，但必须点两下。
 * ---------------------------------------------------------------------------
 * ✕ 一直是「一次点击直接 splice」，而它就贴在素材卡片旁边，四周全是输入框 ——
 * 填标题、调画框的时候手很容易蹭过去，整行的配置一并没了，还得重新添加、重新填。
 * 真正想做的「换个文件」现在已经交给素材卡片里的「清空内容」，不该再走删除这条路。
 *
 * 所以这里第一下只进入待确认态（沿用素材库那边「确认删除？」的反馈样式），
 * 并且同一时刻只允许一个条目待确认：点到别的行，上一行自动解除。
 */
const armedRow = ref('')
function requestRemove(arr: any[], i: number, id: string) {
  if (armedRow.value !== id) {
    armedRow.value = id
    return
  }
  armedRow.value = ''
  arr.splice(i, 1)
}
</script>

<template>
  <div class="fields">
    <div v-for="f in flatFields()" :key="f.key" class="field-block" :class="'fb-' + f.type">
      <!-- 文本 / 富文本 / 下拉 -->
      <label v-if="f.type === 'text'" class="f">
        <span>{{ f.label }}</span>
        <input v-model="model[f.key]" :placeholder="f.hint" />
      </label>

      <label v-else-if="f.type === 'textarea'" class="f">
        <span>{{ f.label }}</span>
        <textarea v-model="model[f.key]" :placeholder="f.hint" rows="3"></textarea>
      </label>

      <label v-else-if="f.type === 'richtext'" class="f">
        <span>{{ f.label }}<i class="f-note">支持少量 HTML</i></span>
        <textarea v-model="model[f.key]" :placeholder="f.hint" rows="2"></textarea>
      </label>

      <label v-else-if="f.type === 'select'" class="f">
        <span>{{ f.label }}</span>
        <select v-model="model[f.key]">
          <option v-for="o in f.options || []" :key="o.v" :value="o.v">{{ o.label }}</option>
        </select>
      </label>

      <!-- 链接：预置页面 / 自定义 -->
      <div v-else-if="f.type === 'link'" class="f">
        <span>{{ f.label }}<i class="f-note">选预置页或自己填</i></span>
        <select
          :value="presetKeyOf(model[f.key])"
          @change="onPresetPick(f.key, ($event.target as HTMLSelectElement).value)"
        >
          <option value="__custom">自定义链接…</option>
          <option v-for="p in f.options || PRESET_PAGES" :key="p.v" :value="p.v">{{ p.label }}</option>
        </select>
        <input v-model="model[f.key]" :placeholder="f.hint || 'https:// 或 /pages/xxx.html 或 #锚点'" />
      </div>

      <label v-else-if="f.type === 'switch'" class="f f-switch">
        <span>{{ f.label }}</span>
        <input v-model="model[f.key]" type="checkbox" />
      </label>

      <!-- 字符串数组：一行一项 -->
      <label v-else-if="f.type === 'stringlist'" class="f">
        <span>{{ f.label }}<i class="f-note">一行一项</i></span>
        <textarea
          :value="arrayToLines(f.key)"
          rows="4"
          :placeholder="f.hint"
          @input="linesToArray(f.key, ($event.target as HTMLTextAreaElement).value)"
        ></textarea>
      </label>

      <!-- 键值对 -->
      <div v-else-if="f.type === 'kv'" class="f">
        <div class="f-head">
          <span>{{ f.label }}</span>
          <button class="mini" @click="toList(f.key).push({ k: '', v: '' })">+ 添加</button>
        </div>
        <div v-for="(row, i) in toList(f.key)" :key="keyOf(row)" class="kv-row">
          <input v-model="row.k" placeholder="名称" />
          <input v-model="row.v" placeholder="内容" />
          <button class="mini icon" @click="move(toList(f.key), i, -1)" :disabled="i === 0">↑</button>
          <button class="mini icon" @click="move(toList(f.key), i, 1)" :disabled="i === toList(f.key).length - 1">↓</button>
          <button class="mini danger" @click="remove(toList(f.key), i)">✕</button>
        </div>
      </div>

      <!-- 单个素材 -->
      <div v-else-if="f.type === 'media'" class="f">
        <div class="f-head"><span>{{ f.label }}</span></div>
        <MediaItemEditor :item="toObject(f.key) as MediaItem" />
      </div>

      <!-- 素材列表 -->
      <div v-else-if="f.type === 'medialist'" class="f">
        <div class="f-head">
          <span>{{ f.label }}<i class="f-note">{{ toList(f.key).length }} 项</i></span>
          <button class="mini" @click="addMedia(f.key)">+ 添加素材</button>
        </div>
        <div v-for="(item, i) in toList(f.key)" :key="keyOf(item)" class="media-row">
          <MediaItemEditor
            :item="item"
            :used="usedSrcs(f.key)"
            @pick-more="appendMedia(f.key, $event)"
          />
          <div class="row-tools">
            <button class="mini icon" @click="move(toList(f.key), i, -1)" :disabled="i === 0">↑</button>
            <button class="mini icon" @click="move(toList(f.key), i, 1)" :disabled="i === toList(f.key).length - 1">↓</button>
            <button
              class="mini danger"
              :class="{ 'is-armed': armedRow === keyOf(item) }"
              :title="armedRow === keyOf(item) ? '再点一次真的删除这一整条' : '删除这一整条素材（含它的文案配置）'"
              @click="requestRemove(toList(f.key), i, keyOf(item))"
            >
              {{ armedRow === keyOf(item) ? '确认删除？' : '✕' }}
            </button>
          </div>
        </div>
      </div>

      <!-- 嵌套对象 -->
      <div v-else-if="f.type === 'group'" class="f f-group">
        <div class="f-head"><span>{{ f.label }}</span></div>
        <FieldsEditor :fields="f.fields || []" :model="toObject(f.key)" compact />
      </div>

      <!-- 对象列表 -->
      <div v-else-if="f.type === 'list'" class="f">
        <div class="f-head">
          <span>{{ f.label }}<i class="f-note">{{ toList(f.key).length }} 项</i></span>
          <button class="mini" @click="addItem(f)">+ 添加</button>
        </div>
        <div v-for="(item, i) in toList(f.key)" :key="keyOf(item)" class="list-item">
          <div class="list-item__bar">
            <b>{{ item[f.titleKey || 'title'] || `第 ${i + 1} 项` }}</b>
            <span class="row-tools">
              <button class="mini icon" @click="move(toList(f.key), i, -1)" :disabled="i === 0">↑</button>
              <button class="mini icon" @click="move(toList(f.key), i, 1)" :disabled="i === toList(f.key).length - 1">↓</button>
              <button class="mini danger" @click="remove(toList(f.key), i)">✕</button>
            </span>
          </div>
          <FieldsEditor :fields="f.fields || []" :model="item" compact />
        </div>
      </div>
    </div>
  </div>
</template>
