<template>
  <div class="profile-page">
    <section class="hero-panel">
      <div class="hero-user">
        <el-avatar :size="68" class="hero-avatar">{{ avatarText }}</el-avatar>
        <div>
          <p class="hero-eyebrow">学习画像</p>
          <h2>{{ profile?.username || '考研同学' }}</h2>
          <p class="hero-subtitle">
            {{ phaseLabel }} · {{ profileForm.targetSchool || '目标院校待设置' }}
          </p>
        </div>
      </div>

      <div class="hero-kpis">
        <div class="kpi-chip">
          <span>收藏</span>
          <strong>{{ favorites.length }}</strong>
        </div>
        <div class="kpi-chip">
          <span>评分</span>
          <strong>{{ ratings.length }}</strong>
        </div>
        <div class="kpi-chip">
          <span>浏览记录</span>
          <strong>{{ history.length }}</strong>
        </div>
        <div class="kpi-chip">
          <span>推荐数量</span>
          <strong>{{ prefForm.topN }}</strong>
        </div>
      </div>

      <div class="hero-progress">
        <div class="progress-head">
          <span>资料完善度</span>
          <strong>{{ profileCompletion }}%</strong>
        </div>
        <el-progress :percentage="profileCompletion" :show-text="false" :stroke-width="10" />
        <p>{{ completionHint }}</p>
      </div>
    </section>

    <el-row :gutter="16" class="content-row">
      <el-col :lg="10" :md="24">
        <el-card class="panel-card form-card" shadow="never" v-loading="profileLoading">
          <template #header>
            <div class="card-head">
              <div class="card-title">
                <el-icon><User /></el-icon>
                <span>个人信息</span>
              </div>
              <small>完善后可获得更精准推荐</small>
            </div>
          </template>

          <el-form :model="profileForm" label-width="90px">
            <el-form-item label="用户名">
              <el-input :model-value="profile?.username || '-'" disabled />
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="profileForm.email" placeholder="请输入邮箱" />
            </el-form-item>
            <el-form-item label="手机号">
              <el-input v-model="profileForm.phone" placeholder="请输入手机号" />
            </el-form-item>
            <el-form-item label="目标院校">
              <el-input v-model="profileForm.targetSchool" placeholder="例如：清华大学" />
            </el-form-item>
            <el-form-item label="目标专业">
              <el-input v-model="profileForm.major" placeholder="例如：计算机科学与技术" />
            </el-form-item>
            <el-form-item label="备考阶段">
              <el-select v-model="profileForm.studyPhase" style="width: 100%">
                <el-option label="备考初期" value="PREPARATION" />
                <el-option label="基础阶段" value="INITIAL" />
                <el-option label="强化阶段" value="INTENSIVE" />
                <el-option label="冲刺阶段" value="REVIEW" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="profileSaving" @click="saveProfile">保存信息</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card class="panel-card form-card" shadow="never" v-loading="prefLoading">
          <template #header>
            <div class="card-head">
              <div class="card-title">
                <el-icon><Setting /></el-icon>
                <span>推荐偏好设置</span>
              </div>
              <small>{{ prefForm.autoRefresh ? '自动刷新已开启' : '自动刷新已关闭' }}</small>
            </div>
          </template>

          <el-form :model="prefForm" label-width="95px">
            <el-form-item label="偏好分类">
              <el-select
                v-model="prefForm.preferredCategories"
                multiple
                collapse-tags
                collapse-tags-tooltip
                style="width: 100%"
                placeholder="请选择偏好分类"
              >
                <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="偏好标签">
              <el-input
                v-model="prefForm.preferredTagsText"
                placeholder="例如：数学,真题,阅读（逗号分隔）"
              />
            </el-form-item>
            <el-form-item label="排除标签">
              <el-input
                v-model="prefForm.excludedTagsText"
                placeholder="例如：政治,写作（逗号分隔）"
              />
            </el-form-item>
            <el-form-item label="推荐数量">
              <el-input-number v-model="prefForm.topN" :min="5" :max="50" style="width: 100%" />
            </el-form-item>
            <el-form-item label="自动刷新">
              <el-switch v-model="prefForm.autoRefresh" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="prefSaving" @click="savePreferences">保存偏好</el-button>
            </el-form-item>
          </el-form>

          <div class="pref-preview">
            <span class="preview-label">当前偏好分类：</span>
            <div v-if="preferredCategoryNames.length" class="tag-group">
              <el-tag
                v-for="name in preferredCategoryNames"
                :key="name"
                size="small"
                type="primary"
                effect="plain"
              >
                {{ name }}
              </el-tag>
            </div>
            <span v-else class="placeholder">尚未选择偏好分类</span>
          </div>
        </el-card>
      </el-col>

      <el-col :lg="14" :md="24">
        <el-card class="panel-card activity-card" shadow="never">
          <template #header>
            <div class="card-head">
              <div class="card-title">
                <el-icon><DataAnalysis /></el-icon>
                <span>学习轨迹</span>
              </div>
              <small>最近活跃：{{ latestHistoryTime }}</small>
            </div>
          </template>

          <el-tabs v-model="activeTab" class="profile-tabs">
            <el-tab-pane label="我的收藏" name="favorites">
              <div class="tab-toolbar">
                <span>共 {{ favorites.length }} 条收藏</span>
              </div>
              <el-empty v-if="favorites.length === 0" description="暂无收藏" />
              <el-table v-else :data="favorites" stripe class="data-table">
                <el-table-column prop="title" label="资料标题" min-width="240" />
                <el-table-column prop="tags" label="标签" min-width="200">
                  <template #default="{ row }">
                    <div class="table-tag-wrap">
                      <el-tag
                        v-for="tag in splitTags(row.tags).slice(0, 3)"
                        :key="`${row.id}-${tag}`"
                        size="small"
                        effect="plain"
                      >
                        {{ tag }}
                      </el-tag>
                      <span v-if="splitTags(row.tags).length > 3" class="more-tag">+{{ splitTags(row.tags).length - 3 }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="160">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="goMaterial(row.id)">查看</el-button>
                    <el-button link type="danger" @click="handleToggleFavorite(row.id)">取消收藏</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="我的评分" name="ratings">
              <div class="tab-toolbar">
                <span>共 {{ ratings.length }} 条评分</span>
              </div>
              <el-empty v-if="ratings.length === 0" description="暂无评分记录" />
              <el-table v-else :data="ratings" stripe class="data-table">
                <el-table-column prop="materialTitle" label="资料标题" min-width="240" />
                <el-table-column label="评分" width="130">
                  <template #default="{ row }">
                    <el-rate :model-value="Number(row.rating)" disabled />
                  </template>
                </el-table-column>
                <el-table-column prop="updatedAt" label="更新时间" width="180" />
                <el-table-column label="操作" width="140">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="goMaterial(row.materialId)">查看</el-button>
                    <el-button link type="danger" @click="handleDeleteRating(row.materialId)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="浏览历史" name="history">
              <div class="tab-toolbar between">
                <span>共 {{ history.length }} 条历史</span>
                <el-button type="danger" plain :disabled="history.length === 0" @click="handleClearHistory">清空历史</el-button>
              </div>
              <el-empty v-if="history.length === 0" description="暂无浏览历史" />
              <el-table v-else :data="history" stripe class="data-table">
                <el-table-column prop="materialTitle" label="资料标题" min-width="220" />
                <el-table-column prop="action" label="行为" width="110">
                  <template #default="{ row }">
                    <el-tag size="small" :type="actionTagType(row.action)" effect="plain">{{ actionLabel(row.action) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="createdAt" label="时间" width="180" />
                <el-table-column label="操作" width="140">
                  <template #default="{ row }">
                    <el-button link type="primary" @click="goMaterial(row.materialId)">查看</el-button>
                    <el-button link type="danger" @click="handleDeleteHistory(row.id)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>

            <el-tab-pane label="AI 学习建议" name="suggestions">
              <div class="suggestion-panel">
                <div class="suggestion-head">
                  <div class="title-wrap">
                    <el-icon><MagicStick /></el-icon>
                    <span>个性化建议</span>
                  </div>
                  <el-button type="primary" plain :loading="suggestionsLoading" @click="loadSuggestions">
                    {{ suggestions ? '刷新建议' : '生成建议' }}
                  </el-button>
                </div>

                <div v-if="suggestions" class="suggestion-text">{{ suggestions }}</div>
                <el-empty v-else description="暂未生成建议，点击上方按钮开始" :image-size="72" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, MagicStick, Setting, User } from '@element-plus/icons-vue'
import http from '@/api/http'
import { getSuggestions } from '@/api/ai'
import {
  getProfile,
  updateProfile,
  getFavorites,
  toggleFavorite,
  getHistory,
  clearHistory,
  deleteHistoryItem,
  getRatings,
  deleteRating,
  getPreferences,
  updatePreferences
} from '@/api/user'

const router = useRouter()

const activeTab = ref('favorites')
const profile = ref(null)
const favorites = ref([])
const ratings = ref([])
const history = ref([])
const categories = ref([])
const suggestions = ref('')

const profileLoading = ref(false)
const profileSaving = ref(false)
const prefLoading = ref(false)
const prefSaving = ref(false)
const suggestionsLoading = ref(false)

const profileForm = reactive({
  email: '',
  phone: '',
  targetSchool: '',
  major: '',
  studyPhase: 'PREPARATION'
})

const prefForm = reactive({
  preferredCategories: [],
  preferredTagsText: '',
  excludedTagsText: '',
  topN: 10,
  autoRefresh: false
})

const phaseLabelMap = {
  PREPARATION: '备考初期',
  INITIAL: '基础阶段',
  INTENSIVE: '强化阶段',
  REVIEW: '冲刺阶段'
}

const phaseLabel = computed(() => phaseLabelMap[profileForm.studyPhase] || '备考初期')

const avatarText = computed(() => {
  const username = String(profile.value?.username || '研')
  return username.slice(0, 1).toUpperCase()
})

const profileCompletion = computed(() => {
  const checks = [
    profileForm.email,
    profileForm.phone,
    profileForm.targetSchool,
    profileForm.major,
    profileForm.studyPhase
  ]
  const filled = checks.filter(Boolean).length
  return Math.round((filled / checks.length) * 100)
})

const completionHint = computed(() => {
  if (profileCompletion.value >= 85) {
    return '信息很完整，推荐模型可以更准确识别你的学习偏好。'
  }
  if (profileCompletion.value >= 60) {
    return '再补充手机号或目标信息，推荐结果会继续提升。'
  }
  return '建议先完善院校、专业和联系方式，方便构建更完整学习画像。'
})

const latestHistoryTime = computed(() => history.value[0]?.createdAt || '-')

const preferredCategoryNames = computed(() => {
  const selected = new Set((prefForm.preferredCategories || []).map((id) => Number(id)))
  return (categories.value || [])
    .filter((item) => selected.has(Number(item.id)))
    .map((item) => item.name)
})

function parseTags(text) {
  if (!text) return []
  return text
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function splitTags(raw) {
  if (!raw) return []
  return raw
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function actionLabel(action) {
  const map = {
    VIEW: '浏览',
    LIKE: '点赞',
    DISLIKE: '点踩',
    COLLECT: '收藏',
    RATE: '评分',
    DOWNLOAD: '下载'
  }
  return map[action] || action
}

function actionTagType(action) {
  const map = {
    VIEW: 'info',
    LIKE: 'success',
    DISLIKE: 'danger',
    COLLECT: 'warning',
    RATE: 'primary',
    DOWNLOAD: ''
  }
  return map[action] || ''
}

function goMaterial(id) {
  router.push(`/materials/${id}`)
}

async function loadProfile() {
  profileLoading.value = true
  try {
    const res = await getProfile()
    profile.value = res.data
    Object.assign(profileForm, {
      email: res.data?.email || '',
      phone: res.data?.phone || '',
      targetSchool: res.data?.targetSchool || '',
      major: res.data?.major || '',
      studyPhase: res.data?.studyPhase || 'PREPARATION'
    })
  } finally {
    profileLoading.value = false
  }
}

async function saveProfile() {
  profileSaving.value = true
  try {
    await updateProfile(profileForm)
    ElMessage.success('个人信息已保存')
    await loadProfile()
  } finally {
    profileSaving.value = false
  }
}

async function loadCategories() {
  const res = await http.get('/categories')
  categories.value = res.data || []
}

async function loadPreferences() {
  prefLoading.value = true
  try {
    const res = await getPreferences()
    const data = res.data || {}
    prefForm.preferredCategories = (data.preferredCategories || []).map(Number)
    prefForm.preferredTagsText = (data.preferredTags || []).join(',')
    prefForm.excludedTagsText = (data.excludedTags || []).join(',')
    prefForm.topN = Number(data.topN || 10)
    prefForm.autoRefresh = !!data.autoRefresh
  } finally {
    prefLoading.value = false
  }
}

async function savePreferences() {
  prefSaving.value = true
  try {
    await updatePreferences({
      preferredCategories: prefForm.preferredCategories,
      preferredTags: parseTags(prefForm.preferredTagsText),
      excludedTags: parseTags(prefForm.excludedTagsText),
      topN: prefForm.topN,
      autoRefresh: prefForm.autoRefresh
    })
    ElMessage.success('推荐偏好已保存')
  } finally {
    prefSaving.value = false
  }
}

async function loadFavorites() {
  const res = await getFavorites()
  favorites.value = res.data || []
}

async function handleToggleFavorite(materialId) {
  await toggleFavorite(materialId)
  ElMessage.success('已取消收藏')
  await loadFavorites()
}

async function loadRatings() {
  const res = await getRatings()
  ratings.value = res.data || []
}

async function loadHistory() {
  const res = await getHistory()
  history.value = res.data || []
}

async function handleDeleteRating(materialId) {
  await ElMessageBox.confirm('确认删除这条评分吗？', '提示', { type: 'warning' })
  await deleteRating(materialId)
  ElMessage.success('评分已删除')
  await loadRatings()
}

async function handleDeleteHistory(historyId) {
  await deleteHistoryItem(historyId)
  ElMessage.success('历史记录已删除')
  await loadHistory()
}

async function handleClearHistory() {
  await ElMessageBox.confirm('确认清空全部浏览历史吗？', '提示', { type: 'warning' })
  await clearHistory()
  ElMessage.success('浏览历史已清空')
  await loadHistory()
}

async function loadSuggestions() {
  suggestionsLoading.value = true
  try {
    const res = await getSuggestions()
    suggestions.value = res.data?.suggestions || ''
  } finally {
    suggestionsLoading.value = false
  }
}

onMounted(async () => {
  await Promise.all([
    loadProfile(),
    loadCategories(),
    loadPreferences(),
    loadFavorites(),
    loadRatings(),
    loadHistory()
  ])
})
</script>

<style scoped>
.profile-page {
  --panel-border: #dfe9fb;
  --panel-shadow: 0 10px 22px rgba(33, 76, 153, 0.08);
  --hero-bg: linear-gradient(125deg, #e8f2ff 0%, #edf8f5 45%, #fff7eb 100%);
  --brand: #2f78ff;
  --text-main: #173157;
  --text-sub: #5f7697;
  --table-head: #f4f8ff;

  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hero-panel {
  border: 1px solid var(--panel-border);
  border-radius: 16px;
  background: var(--hero-bg);
  box-shadow: var(--panel-shadow);
  padding: 18px 20px;
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr;
  gap: 14px;
}

.hero-user {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-avatar {
  background: linear-gradient(145deg, #2f78ff 0%, #67a2ff 100%);
  color: #fff;
  font-size: 24px;
  font-weight: 700;
  border: 3px solid #fff;
  box-shadow: 0 8px 18px rgba(47, 120, 255, 0.25);
}

.hero-eyebrow {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: #4e719e;
}

.hero-user h2 {
  margin: 6px 0 4px;
  font-size: 24px;
  line-height: 1.2;
  color: var(--text-main);
}

.hero-subtitle {
  margin: 0;
  color: var(--text-sub);
  font-size: 13px;
}

.hero-kpis {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.kpi-chip {
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid #dce7fb;
  padding: 10px 12px;
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.kpi-chip span {
  font-size: 12px;
  color: #617b9f;
}

.kpi-chip strong {
  font-size: 22px;
  line-height: 1;
  color: #204a86;
}

.hero-progress {
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid #dce7fb;
  padding: 12px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 10px;
}

.progress-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.progress-head span {
  font-size: 13px;
  color: #4e719e;
}

.progress-head strong {
  color: var(--brand);
  font-size: 18px;
}

.hero-progress p {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #5d7796;
}

.content-row {
  margin: 0 !important;
}

.panel-card {
  border: 1px solid var(--panel-border);
  border-radius: 14px;
  box-shadow: var(--panel-shadow);
}

.panel-card + .panel-card {
  margin-top: 16px;
}

.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}

.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  color: var(--text-main);
  font-weight: 700;
}

.card-head small {
  color: #7990ad;
  font-size: 12px;
}

.form-card :deep(.el-form-item__label) {
  color: #516b8d;
  font-weight: 600;
}

.form-card :deep(.el-input__wrapper),
.form-card :deep(.el-select__wrapper),
.form-card :deep(.el-textarea__inner) {
  border-radius: 10px;
  background: #f8fbff;
}

.pref-preview {
  margin-top: 8px;
  padding-top: 10px;
  border-top: 1px dashed #d8e5fb;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.preview-label {
  font-size: 13px;
  color: #516b8d;
}

.tag-group {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.placeholder {
  color: #97a9c2;
  font-size: 12px;
}

.activity-card {
  min-height: 680px;
}

.profile-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.profile-tabs :deep(.el-tabs__item) {
  font-weight: 600;
  color: #617a9c;
}

.profile-tabs :deep(.el-tabs__item.is-active) {
  color: var(--brand);
}

.tab-toolbar {
  margin-bottom: 10px;
  color: #728aad;
  font-size: 13px;
}

.tab-toolbar.between {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.data-table {
  width: 100%;
}

.data-table :deep(.el-table__header-wrapper th) {
  background: var(--table-head);
  color: #4f688a;
}

.table-tag-wrap {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.more-tag {
  color: #6d86a7;
  font-size: 12px;
}

.suggestion-panel {
  border: 1px solid #dce8ff;
  border-radius: 12px;
  background: linear-gradient(160deg, #f7fbff 0%, #fefcf7 100%);
  padding: 14px;
}

.suggestion-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #365b8c;
  font-weight: 700;
}

.suggestion-text {
  white-space: pre-wrap;
  line-height: 1.9;
  color: #4d6586;
  font-size: 14px;
}

@media (max-width: 1280px) {
  .hero-panel {
    grid-template-columns: 1fr 1fr;
  }

  .hero-progress {
    grid-column: span 2;
  }
}

@media (max-width: 900px) {
  .hero-panel {
    grid-template-columns: 1fr;
    padding: 14px;
  }

  .hero-progress {
    grid-column: span 1;
  }

  .hero-user h2 {
    font-size: 22px;
  }

  .activity-card {
    min-height: auto;
  }

  .tab-toolbar.between {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
}
</style>
