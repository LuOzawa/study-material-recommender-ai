<template>
  <div class="register-page">
    <el-card class="register-card">
      <div class="header">
        <el-icon size="36" color="#409eff"><Reading /></el-icon>
        <h2>注册账号</h2>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3-50个字符" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="至少6位" show-password />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" placeholder="可选" />
        </el-form-item>
        <el-form-item label="目标院校">
          <el-input v-model="form.targetSchool" placeholder="如：清华大学" />
        </el-form-item>
        <el-form-item label="目标专业">
          <el-input v-model="form.major" placeholder="如：计算机科学与技术" />
        </el-form-item>
        <el-form-item label="备考阶段">
          <el-select v-model="form.studyPhase" style="width: 100%">
            <el-option label="备考初期" value="PREPARATION" />
            <el-option label="基础阶段" value="INITIAL" />
            <el-option label="强化阶段" value="INTENSIVE" />
            <el-option label="冲刺阶段" value="REVIEW" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" style="width: 100%" :loading="loading" :disabled="loading" @click="handleRegister">
            注册
          </el-button>
        </el-form-item>
      </el-form>

      <div style="text-align: center; color: #909399">
        已有账号？
        <el-link type="primary" @click="$router.push('/login')">立即登录</el-link>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '@/api/auth'

const router = useRouter()
const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
  email: '',
  targetSchool: '',
  major: '',
  studyPhase: 'PREPARATION'
})

const rules = {
  username: [{ required: true, min: 3, max: 50, message: '用户名为3-50字符', trigger: 'blur' }],
  password: [{ required: true, min: 6, message: '密码至少6位', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function handleRegister() {
  if (loading.value || !formRef.value) return

  loading.value = true
  try {
    await formRef.value.validate()
    await register(form)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (err) {
    // API errors are handled by axios interceptor.
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}

.register-card {
  width: 480px;
  border-radius: 12px;
}

.header {
  text-align: center;
  margin-bottom: 24px;
}

.header h2 {
  margin: 10px 0 0;
  color: #303133;
}
</style>