<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi, tokenStore } from '@/api'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const remember = ref(true)
const busy = ref(false)
const error = ref('')

async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const res = await adminApi.login({
      username: username.value,
      password: password.value,
      remember: remember.value
    })
    tokenStore.set(res.token)
    const redirect = (route.query.r as string) || '/admin'
    router.replace(redirect)
  } catch (e: any) {
    error.value = e?.message || '登录失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <form class="login-card glass" @submit.prevent="submit">
      <div class="login-mark">Cc</div>
      <h1 class="t-md">内容后台</h1>
      <p class="small">登录后可修改站点内容、上传素材</p>

      <label class="f">
        <span>账号</span>
        <input v-model="username" autocomplete="username" />
      </label>
      <label class="f">
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" />
      </label>
      <label class="f f-switch">
        <span>保持登录 14 天</span>
        <input v-model="remember" type="checkbox" />
      </label>

      <p v-if="error" class="login-error">{{ error }}</p>

      <button class="btn" type="submit" :disabled="busy">
        {{ busy ? '登录中…' : '登录' }}
      </button>

      <RouterLink class="small" to="/">← 返回前台</RouterLink>
    </form>
  </div>
</template>
