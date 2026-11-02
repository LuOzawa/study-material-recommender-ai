<template>
  <el-container class="layout-shell">
    <el-aside width="232px" class="layout-sidebar">
      <div class="brand-block" @click="router.push('/')">
        <div class="brand-icon">
          <el-icon size="20"><Reading /></el-icon>
        </div>
        <div class="brand-text">
          <strong>考研推荐系统</strong>
          <span>Study Mate</span>
        </div>
      </div>

      <el-menu
        class="side-menu"
        :default-active="$route.path"
        router
        background-color="transparent"
        text-color="#c2d0e6"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/">
          <el-icon><House /></el-icon>
          <span>推荐首页</span>
        </el-menu-item>
        <el-menu-item index="/materials">
          <el-icon><Files /></el-icon>
          <span>资料浏览</span>
        </el-menu-item>
        <el-menu-item v-if="authStore.isLoggedIn" index="/ai">
          <el-icon><ChatDotRound /></el-icon>
          <span>AI 助手</span>
        </el-menu-item>
        <el-menu-item v-if="authStore.isLoggedIn" index="/profile">
          <el-icon><User /></el-icon>
          <span>个人中心</span>
        </el-menu-item>
        <template v-if="authStore.isAdmin">
          <el-divider class="menu-divider" />
          <el-menu-item index="/admin/materials">
            <el-icon><Management /></el-icon>
            <span>资料管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/users">
            <el-icon><UserFilled /></el-icon>
            <span>用户管理</span>
          </el-menu-item>
          <el-menu-item index="/admin/analytics">
            <el-icon><DataAnalysis /></el-icon>
            <span>数据分析</span>
          </el-menu-item>
        </template>
      </el-menu>

      <div class="sidebar-foot">
        <div class="status-chip">
          <span class="status-dot" />
          <span>{{ authStore.isLoggedIn ? '个性化推荐已开启' : '游客模式浏览中' }}</span>
        </div>
        <p class="status-note">
          {{ authStore.isLoggedIn ? '多浏览、多反馈，系统会持续优化推荐结果。' : '登录后可使用 AI 助手、收藏与反馈功能。' }}
        </p>
      </div>
    </el-aside>

    <el-container class="layout-main-wrap">
      <el-header class="layout-header">
        <div class="header-left">
          <p class="header-kicker">{{ todayText }}</p>
          <h1 class="header-title">{{ currentRouteInfo.title }}</h1>
          <p class="header-subtitle">{{ currentRouteInfo.subtitle }}</p>
        </div>

        <div class="header-right">
          <div class="quick-actions">
            <el-button
              v-if="$route.path !== '/materials'"
              round
              @click="router.push('/materials')"
            >
              去资料库
            </el-button>
            <el-button
              v-if="authStore.isLoggedIn && $route.path !== '/ai'"
              type="primary"
              plain
              round
              @click="router.push('/ai')"
            >
              AI 助手
            </el-button>
          </div>

          <template v-if="authStore.isLoggedIn">
            <el-dropdown @command="handleCommand">
              <div class="user-chip">
                <el-avatar :size="34" icon="UserFilled" />
                <div class="user-info">
                  <strong>{{ authStore.username }}</strong>
                  <span>{{ authStore.isAdmin ? '管理员账号' : '学习用户' }}</span>
                </div>
                <el-tag v-if="authStore.isAdmin" type="danger" size="small">管理员</el-tag>
                <el-icon class="user-arrow"><ArrowDown /></el-icon>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                  <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>

          <template v-else>
            <div class="guest-actions">
              <el-tag type="info" effect="plain">游客模式</el-tag>
              <el-button text type="primary" @click="router.push('/login')">登录</el-button>
              <el-button type="primary" plain round @click="router.push('/register')">注册</el-button>
            </div>
          </template>
        </div>
      </el-header>

      <el-main class="layout-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useAuthStore } from '@/store/auth'
import { useRoute, useRouter } from 'vue-router'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const routeInfoMap = {
  Home: { title: '推荐首页', subtitle: '精选热门与个性化资料，帮助你更快找到重点内容。' },
  Materials: { title: '资料浏览', subtitle: '按关键词与分类检索，快速定位目标资料。' },
  MaterialDetail: { title: '资料详情', subtitle: '查看资料简介、评分、下载与个性化操作。' },
  Profile: { title: '个人中心', subtitle: '管理偏好标签、学习画像与账号信息。' },
  AiChat: { title: 'AI 助手', subtitle: '围绕资料做问答与摘要，提升理解效率。' },
  AdminMaterials: { title: '资料管理', subtitle: '审核、维护平台资料库，保证内容质量。' },
  AdminUsers: { title: '用户管理', subtitle: '查看用户信息与账号状态，维护平台秩序。' },
  Analytics: { title: '数据分析', subtitle: '观察平台行为趋势，为运营决策提供依据。' }
}

const currentRouteInfo = computed(() => {
  const name = String(route.name || '')
  return routeInfoMap[name] || { title: '考研推荐系统', subtitle: '让资料获取更高效，学习路径更清晰。' }
})

const todayText = computed(() =>
  new Date().toLocaleDateString('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'short'
  })
)

function handleCommand(cmd) {
  if (cmd === 'logout') {
    authStore.logout()
    router.push('/')
  } else if (cmd === 'profile') {
    router.push('/profile')
  }
}
</script>

<style scoped>
.layout-shell {
  min-height: 100vh;
  background:
    radial-gradient(circle at 78% 8%, rgba(30, 99, 255, 0.08), transparent 30%),
    radial-gradient(circle at 18% 20%, rgba(0, 184, 169, 0.08), transparent 34%),
    #f3f6fb;
}

.layout-sidebar {
  background: linear-gradient(180deg, #041a37 0%, #062850 56%, #082b4b 100%);
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.brand-block {
  margin: 14px 14px 8px;
  border-radius: 14px;
  padding: 14px 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
  cursor: pointer;
}

.brand-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, #1e63ff 0%, #2d8cff 100%);
  box-shadow: 0 8px 16px rgba(30, 99, 255, 0.35);
  flex-shrink: 0;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-text strong {
  color: #fff;
  font-size: 15px;
}

.brand-text span {
  color: #a9bbd6;
  font-size: 11px;
  margin-top: 4px;
  letter-spacing: 0.4px;
}

.side-menu {
  border-right: none;
  padding: 8px 10px 0;
  flex: 1;
}

:deep(.side-menu .el-menu-item) {
  height: 42px;
  border-radius: 10px;
  margin-bottom: 6px;
  font-size: 14px;
}

:deep(.side-menu .el-menu-item .el-icon) {
  font-size: 16px;
}

:deep(.side-menu .el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(30, 99, 255, 0.34), rgba(0, 184, 169, 0.14));
  box-shadow: inset 0 0 0 1px rgba(138, 179, 255, 0.32);
}

:deep(.side-menu .el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.08);
}

.menu-divider {
  border-color: rgba(255, 255, 255, 0.15);
  margin: 8px 6px;
}

.sidebar-foot {
  margin: 10px 14px 14px;
  border-radius: 12px;
  padding: 10px 12px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(8, 19, 37, 0.34);
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #d6e4f6;
  font-size: 12px;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #2ed39c;
  box-shadow: 0 0 0 4px rgba(46, 211, 156, 0.18);
}

.status-note {
  margin-top: 8px;
  font-size: 12px;
  color: #a9bbd6;
  line-height: 1.5;
}

.layout-main-wrap {
  min-width: 0;
}

.layout-header {
  height: auto;
  min-height: 82px;
  padding: 16px 24px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(7px);
  border-bottom: 1px solid #e7ecf5;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.header-left {
  min-width: 0;
}

.header-kicker {
  font-size: 12px;
  color: #6e7892;
  margin-bottom: 4px;
}

.header-title {
  font-size: 24px;
  color: #1a2433;
  line-height: 1.2;
  margin-bottom: 4px;
}

.header-subtitle {
  font-size: 13px;
  color: #616c80;
}

.header-right {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.quick-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-chip {
  min-width: 220px;
  border-radius: 999px;
  padding: 6px 10px 6px 8px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border: 1px solid #e4eaf4;
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.08);
  cursor: pointer;
}

.user-info {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  min-width: 0;
}

.user-info strong {
  color: #1f2b3d;
  font-size: 13px;
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-info span {
  color: #7d8798;
  font-size: 11px;
  margin-top: 3px;
}

.user-arrow {
  color: #8490a3;
}

.guest-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.layout-main {
  padding: 24px;
  background: transparent;
}

@media (max-width: 1100px) {
  .layout-header {
    padding: 14px 16px;
    align-items: flex-start;
    flex-direction: column;
  }

  .header-right {
    width: 100%;
    justify-content: space-between;
  }
}

@media (max-width: 900px) {
  .layout-sidebar {
    width: 86px !important;
  }

  .brand-block {
    justify-content: center;
    padding: 10px 8px;
  }

  .brand-text {
    display: none;
  }

  :deep(.side-menu .el-menu-item) {
    padding: 0 !important;
    justify-content: center;
  }

  :deep(.side-menu .el-menu-item span) {
    display: none;
  }

  .sidebar-foot {
    display: none;
  }
}

@media (max-width: 680px) {
  .layout-main {
    padding: 14px;
  }

  .header-title {
    font-size: 20px;
  }

  .header-subtitle {
    display: none;
  }

  .quick-actions {
    display: none;
  }

  .user-chip {
    min-width: 182px;
  }
}
</style>
