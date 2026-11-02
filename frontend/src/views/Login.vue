<template>
  <div class="login-page">
    <div class="login-shell">
      <section class="brand-panel" aria-label="平台介绍">
        <div class="brand-badge">
          <span class="badge-dot"></span>
          智能资料推荐
        </div>

        <div class="brand-copy">
          <p class="eyebrow">POSTGRADUATE STUDY HUB</p>
          <h1>让备考资料<br />更懂你的阶段</h1>
          <p class="brand-desc">
            聚合课程、真题、笔记与学习行为数据，为每位考研用户匹配更合适的复习资料。
          </p>
        </div>

        <div class="study-illustration" aria-hidden="true">
          <div class="book-stack">
            <div class="book book-blue"></div>
            <div class="book book-teal"></div>
            <div class="book book-orange"></div>
          </div>
          <div class="monitor">
            <div class="monitor-screen">
              <div class="screen-line long"></div>
              <div class="screen-line"></div>
              <div class="progress-row">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
            <div class="monitor-base"></div>
          </div>
          <div class="float-card card-score">
            <span>92%</span>
            <small>匹配度</small>
          </div>
          <div class="float-card card-data">
            <el-icon><DataAnalysis /></el-icon>
            <small>Hadoop</small>
          </div>
        </div>

        <div class="feature-row">
          <span>个性推荐</span>
          <span>行为分析</span>
          <span>资料管理</span>
        </div>
      </section>

      <section class="login-panel" aria-label="账号登录">
        <button class="back-link" type="button" @click="$router.push('/')">
          <el-icon><ArrowLeft /></el-icon>
          返回首页
        </button>

        <div class="login-header">
          <div class="logo-mark">
            <el-icon><Reading /></el-icon>
          </div>
          <p class="login-kicker">ACCOUNT ACCESS</p>
          <h2>系统登录</h2>
          <p>输入账号信息，进入个人学习工作台。</p>
        </div>

        <el-form ref="formRef" class="login-form" :model="form" :rules="rules" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" size="large" prefix-icon="User" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large"
                      prefix-icon="Lock" show-password />
          </el-form-item>
          <el-form-item>
            <el-button class="login-button" size="large" :loading="loading" @click="handleLogin">
              立即登录
            </el-button>
          </el-form-item>
        </el-form>

        <div class="login-actions">
          <span>没有账号？</span>
          <el-link type="primary" @click="$router.push('/register')">去注册</el-link>
          <el-link type="info" @click="resetVisible = true">忘记密码</el-link>
        </div>
      </section>
    </div>

    <el-dialog v-model="resetVisible" title="找回密码" width="420px" destroy-on-close class="reset-dialog">
      <el-form :model="resetForm" label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="resetForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="resetForm.email" placeholder="请输入注册邮箱" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="至少6位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetLoading" @click="handleResetPassword">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, resetPassword } from '@/api/auth'
import { useAuthStore } from '@/store/auth'

const router = useRouter()
const authStore = useAuthStore()
const formRef = ref()
const loading = ref(false)
const resetVisible = ref(false)
const resetLoading = ref(false)

const form = reactive({ username: '', password: '' })
const resetForm = reactive({ username: '', email: '', newPassword: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login(form)
    authStore.login(res.data)
    ElMessage.success('登录成功')
    router.push('/')
  } finally {
    loading.value = false
  }
}

async function handleResetPassword() {
  if (!resetForm.username || !resetForm.email || !resetForm.newPassword) {
    ElMessage.warning('请完整填写信息')
    return
  }
  if (resetForm.newPassword.length < 6) {
    ElMessage.warning('新密码至少6位')
    return
  }

  resetLoading.value = true
  try {
    await resetPassword(resetForm)
    ElMessage.success('密码已重置，请使用新密码登录')
    resetVisible.value = false
    resetForm.username = ''
    resetForm.email = ''
    resetForm.newPassword = ''
  } finally {
    resetLoading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  padding: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background:
    linear-gradient(115deg, rgba(255, 116, 72, 0.18) 0 18%, transparent 18% 100%),
    linear-gradient(292deg, rgba(33, 214, 197, 0.16) 0 16%, transparent 16% 100%),
    linear-gradient(135deg, #141216 0%, #1b1723 48%, #12221f 100%);
}

.login-shell {
  width: min(1040px, 100%);
  min-height: 560px;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(340px, 0.85fr);
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background: rgba(13, 18, 39, 0.78);
  box-shadow: 0 28px 80px rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(18px);
}

.brand-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-height: 560px;
  padding: 46px;
  color: #fff;
  overflow: hidden;
  background:
    linear-gradient(120deg, rgba(255, 115, 59, 0.16), transparent 34%),
    linear-gradient(135deg, rgba(48, 37, 52, 0.94), rgba(10, 30, 30, 0.86));
}

.brand-panel::before {
  content: "";
  position: absolute;
  inset: 0;
  opacity: 0.14;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.08) 1px, transparent 1px);
  background-size: 42px 42px;
}

.brand-badge,
.brand-copy,
.study-illustration,
.feature-row {
  position: relative;
  z-index: 1;
}

.brand-badge {
  width: fit-content;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 999px;
  color: rgba(255, 255, 255, 0.78);
  font-size: 13px;
  background: rgba(255, 255, 255, 0.07);
}

.badge-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff794d;
  box-shadow: 0 0 18px rgba(255, 121, 77, 0.85);
}

.eyebrow,
.login-kicker {
  margin: 0 0 12px;
  color: #26d8cb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 4px;
}

.brand-copy h1 {
  margin: 0;
  color: #fff;
  font-size: 42px;
  line-height: 1.18;
  font-weight: 800;
  letter-spacing: 0;
}

.brand-desc {
  max-width: 440px;
  margin: 18px 0 0;
  color: rgba(255, 255, 255, 0.72);
  font-size: 15px;
  line-height: 1.8;
}

.study-illustration {
  height: 238px;
  margin-top: 30px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  background:
    linear-gradient(145deg, rgba(255, 255, 255, 0.11), rgba(255, 255, 255, 0.03)),
    rgba(3, 12, 28, 0.46);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.08), 0 18px 40px rgba(0, 0, 0, 0.24);
  overflow: hidden;
}

.book-stack {
  position: absolute;
  left: 74px;
  bottom: 56px;
  width: 128px;
  height: 82px;
}

.book {
  position: absolute;
  width: 116px;
  height: 24px;
  border-radius: 6px;
  box-shadow: 0 12px 22px rgba(0, 0, 0, 0.2);
}

.book-blue {
  left: 0;
  bottom: 0;
  background: linear-gradient(90deg, #4c72ff, #7b8cff);
}

.book-teal {
  left: 16px;
  bottom: 26px;
  background: linear-gradient(90deg, #1fd5c1, #8be8df);
}

.book-orange {
  left: 5px;
  bottom: 52px;
  background: linear-gradient(90deg, #ff7a45, #ffd36e);
}

.monitor {
  position: absolute;
  right: 80px;
  bottom: 46px;
  width: 186px;
}

.monitor-screen {
  height: 116px;
  padding: 20px;
  border: 7px solid rgba(237, 242, 255, 0.92);
  border-radius: 18px;
  background: linear-gradient(145deg, #dfe9ff, #f7fbff);
  box-shadow: 0 18px 30px rgba(0, 0, 0, 0.26);
}

.screen-line {
  height: 9px;
  width: 58%;
  margin-bottom: 12px;
  border-radius: 999px;
  background: #6e789b;
}

.screen-line.long {
  width: 82%;
}

.progress-row {
  display: grid;
  grid-template-columns: 1fr 0.7fr 0.45fr;
  gap: 8px;
  margin-top: 20px;
}

.progress-row span {
  height: 28px;
  border-radius: 6px;
  background: linear-gradient(180deg, #26d8cb, #1b8fff);
}

.monitor-base {
  width: 72px;
  height: 12px;
  margin: 14px auto 0;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
}

.float-card {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 8px;
  color: #fff;
  background: rgba(9, 16, 34, 0.82);
  box-shadow: 0 16px 28px rgba(0, 0, 0, 0.24);
}

.float-card small {
  color: rgba(255, 255, 255, 0.66);
}

.card-score {
  left: 222px;
  bottom: 34px;
}

.card-score span {
  color: #ffd36e;
  font-size: 18px;
  font-weight: 800;
}

.card-data {
  right: 34px;
  top: 34px;
  color: #26d8cb;
}

.feature-row {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.feature-row span {
  padding: 9px 12px;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.76);
  font-size: 13px;
  background: rgba(255, 255, 255, 0.08);
}

.login-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 50px 46px;
  color: #dce6ff;
  background: rgba(7, 10, 28, 0.9);
}

.back-link {
  position: absolute;
  top: 32px;
  left: 42px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  color: rgba(220, 230, 255, 0.66);
  background: transparent;
  cursor: pointer;
  transition: color 0.2s ease;
}

.back-link:hover {
  color: #fff;
}

.login-header {
  margin-bottom: 28px;
}

.logo-mark {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  margin-bottom: 28px;
  border-radius: 8px;
  color: #101426;
  font-size: 25px;
  background: linear-gradient(135deg, #ff794d, #ffd36e 45%, #26d8cb);
  box-shadow: 0 14px 34px rgba(38, 216, 203, 0.22);
}

.login-header h2 {
  margin: 0 0 10px;
  color: #fff;
  font-size: 28px;
  letter-spacing: 0;
}

.login-header p {
  margin: 0;
  color: rgba(220, 230, 255, 0.58);
  font-size: 14px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.el-input__wrapper) {
  min-height: 48px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.08) inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px rgba(38, 216, 203, 0.66) inset, 0 0 0 4px rgba(38, 216, 203, 0.08);
}

.login-form :deep(.el-input__inner),
.login-form :deep(.el-input__prefix),
.login-form :deep(.el-input__suffix) {
  color: #fff;
}

.login-form :deep(.el-input__inner::placeholder) {
  color: rgba(220, 230, 255, 0.38);
}

.login-button {
  width: 100%;
  border: 0;
  border-radius: 8px;
  color: #101426;
  font-weight: 800;
  background: linear-gradient(90deg, #ff7448 0%, #ffd45e 48%, #21d6c5 100%);
  box-shadow: 0 16px 34px rgba(33, 214, 197, 0.22);
}

.login-button:hover,
.login-button:focus {
  color: #101426;
  filter: brightness(1.04);
}

.login-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  color: rgba(220, 230, 255, 0.48);
  font-size: 13px;
}

.login-actions :deep(.el-link) {
  font-size: 13px;
}

@media (max-width: 860px) {
  .login-page {
    padding: 18px;
  }

  .login-shell {
    grid-template-columns: 1fr;
  }

  .brand-panel {
    min-height: 420px;
    padding: 32px;
  }

  .brand-copy h1 {
    font-size: 34px;
  }

  .study-illustration {
    height: 190px;
  }

  .monitor {
    right: 36px;
    transform: scale(0.82);
    transform-origin: right bottom;
  }

  .book-stack {
    left: 38px;
    transform: scale(0.82);
    transform-origin: left bottom;
  }

  .card-score {
    left: 150px;
  }

  .login-panel {
    padding: 74px 28px 34px;
  }

  .back-link {
    top: 28px;
    left: 28px;
  }
}

@media (max-width: 520px) {
  .brand-panel {
    display: none;
  }

  .login-shell {
    min-height: auto;
  }

  .login-panel {
    min-height: calc(100vh - 36px);
  }
}
</style>
