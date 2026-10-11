<script setup lang="ts">
/** 联系方式：信息列表 + 项目需求表单（提交到后端 /api/inquiry，进后台「工作对接」）。 */
import { reactive, ref } from 'vue'
import { submitInquiry } from '@/api'
import { trackEvent } from '@/util/tracking'
import type { ContactModule } from '@/types/content'

const props = defineProps<{ module: ContactModule }>()

const form = reactive({
  name: '',
  contact: '',
  type: (props.module.projectTypes || [])[0] || '',
  budget: '',
  message: ''
})

const state = ref<'idle' | 'sending' | 'ok' | 'error'>('idle')
const error = ref('')

/**
 * 限流弹窗。
 * ---------------------------------------------------------------------------
 * 后端对「同一个 IP 一分钟内超过 5 次提交」返回 429，并锁定 10 分钟。
 * 这种情况只在表单底部用一行小字提示太容易被忽略，所以单独弹一条居中提示。
 */
const toast = ref('')
let toastTimer: ReturnType<typeof setTimeout> | null = null
function showToast(text: string, ms = 4000) {
  toast.value = text
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), ms)
}

async function submit() {
  if (state.value === 'sending') return
  state.value = 'sending'
  error.value = ''
  try {
    await submitInquiry({ ...form })
    trackEvent('inquiry', form.type || '')
    state.value = 'ok'
    form.name = form.contact = form.message = form.budget = ''
  } catch (e: any) {
    state.value = 'error'
    const msg = e?.message || '发送失败'
    error.value = msg
    // 429 = 同一 IP 提交过于频繁（一分钟内超过 5 次），弹出明确提示
    if (e?.status === 429) showToast(msg)
  }
}
</script>

<template>
  <div class="contact reveal">
    <div>
      <dl v-if="props.module.rows?.length" class="rows">
        <div v-for="r in props.module.rows" :key="r.k" class="row-i">
          <dt>{{ r.k }}</dt>
          <dd>{{ r.v }}</dd>
        </div>
      </dl>
    </div>

    <form class="form glass" @submit.prevent="submit">
      <div class="form__row">
        <div class="field">
          <label>姓名</label>
          <input v-model="form.name" type="text" placeholder="你的名字" required />
        </div>
        <div class="field">
          <label>邮箱 / 微信</label>
          <input v-model="form.contact" type="text" placeholder="方便我回复你" required />
        </div>
      </div>

      <div class="form__row">
        <div class="field">
          <label>项目类型</label>
          <select v-model="form.type">
            <option v-for="t in props.module.projectTypes || []" :key="t" :value="t">{{ t }}</option>
          </select>
        </div>
        <div class="field">
          <label>预算区间（选填）</label>
          <input v-model="form.budget" type="text" placeholder="如 5k–1w" />
        </div>
      </div>

      <div class="field">
        <label>项目简介</label>
        <textarea v-model="form.message" placeholder="简单描述项目内容、时间与交付形式" required></textarea>
      </div>

      <div class="form__foot">
        <p class="small">
          <span v-if="state === 'ok'" style="color: #0a7d33">已收到，我会尽快回复。</span>
          <span v-else-if="state === 'error'" style="color: #d70015">{{ error }}</span>
          <span v-else>{{ props.module.formNote }}</span>
        </p>
        <button class="btn" type="submit" :disabled="state === 'sending'">
          {{ state === 'sending' ? '发送中…' : props.module.submitLabel || '发送' }}
        </button>
      </div>
    </form>
  </div>

  <!-- 限流弹窗：同一 IP 一分钟内提交超过 5 次时弹出 -->
  <Teleport to="body">
    <div v-if="toast" class="inquiry-toast" role="alert">
      <span class="inquiry-toast__ico" aria-hidden="true">
        <svg viewBox="0 0 24 24"><path d="M12 3 1.5 21h21L12 3zm1 13h-2v2h2v-2zm0-7h-2v5h2V9z" /></svg>
      </span>
      <span>{{ toast }}</span>
    </div>
  </Teleport>
</template>

<style scoped>
.btn[disabled] { opacity: .6; cursor: default; transform: none; }
</style>

<style>
.inquiry-toast {
  position: fixed;
  left: 50%;
  top: 96px;
  z-index: 9999;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 10px;
  max-width: min(92vw, 460px);
  padding: 13px 20px;
  border-radius: var(--r-full, 999px);
  border: 1px solid rgba(255, 255, 255, .18);
  background: rgba(20, 20, 24, .92);
  -webkit-backdrop-filter: blur(18px) saturate(180%);
  backdrop-filter: blur(18px) saturate(180%);
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -.01em;
  box-shadow: 0 18px 44px rgba(0, 0, 0, .32);
  animation: inquiry-toast-in .32s cubic-bezier(.2, .9, .3, 1.2);
}
.inquiry-toast__ico { display: grid; place-items: center; flex: none; }
.inquiry-toast__ico svg { width: 18px; height: 18px; fill: #ffd60a; }
@keyframes inquiry-toast-in {
  from { opacity: 0; transform: translateX(-50%) translateY(-14px) scale(.96); }
  to   { opacity: 1; transform: translateX(-50%) translateY(0) scale(1); }
}
</style>
