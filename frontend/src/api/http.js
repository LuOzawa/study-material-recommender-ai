import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/store/auth'
import router from '@/router'

const http = axios.create({
  baseURL: '/api',
  timeout: 60000
})

const ERROR_TOAST_DEDUP_MS = 1200
let lastToastMessage = ''
let lastToastTime = 0

function showErrorMessage(msg) {
  const now = Date.now()
  if (msg === lastToastMessage && now - lastToastTime < ERROR_TOAST_DEDUP_MS) {
    return
  }
  lastToastMessage = msg
  lastToastTime = now
  ElMessage.error(msg)
}

// Attach JWT for authenticated requests.
http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

// Unified error handling.
http.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const status = err.response?.status
    const backendMessage = err.response?.data?.message
    const reqUrl = err.config?.url || ''
    const isLoginRequest = reqUrl.includes('/auth/login')
    const silentError = err.config?.silentError === true

    // Spring Security may return 403 with empty body when token is missing/invalid.
    const isSessionInvalid403 = status === 403 && !backendMessage
    const shouldForceLogin = !isLoginRequest && (status === 401 || isSessionInvalid403)

    if (shouldForceLogin) {
      useAuthStore().logout()
      router.push('/login')
      showErrorMessage('登录已过期，请重新登录')
    } else {
      const msg =
        backendMessage ||
        (status === 401
          ? '用户名或密码错误'
          : status === 403
            ? '权限不足'
            : err.message || '请求失败')

      if (!silentError) {
        showErrorMessage(msg)
      }
    }

    return Promise.reject(err)
  }
)

export default http