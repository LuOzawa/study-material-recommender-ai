<template>
  <div class="home-view">
    <section class="hero-panel">
      <div class="hero-main">
        <p class="hero-kicker">{{ authStore.isLoggedIn ? `欢迎回来，${authStore.username}` : '欢迎来到考研推荐系统' }}</p>
        <h2 class="hero-title">{{ authStore.isLoggedIn ? '个性化推荐' : '热门推荐' }}</h2>
        <p class="hero-subtitle">
          {{ authStore.isLoggedIn
            ? '基于你的近期行为和偏好，为你优先推送更相关的学习资料。'
            : '游客可先浏览热门资料，登录后可解锁个性化推荐。' }}
        </p>
        <p v-if="authStore.isLoggedIn && algoSummary" class="hero-algo">
          推荐引擎：{{ algoSummary }}
        </p>

        <div class="hero-stats">
          <div class="stat-pill">
            <span class="stat-label">推荐数量</span>
            <strong>{{ materials.length }}</strong>
          </div>
          <div class="stat-pill">
            <span class="stat-label">平均评分</span>
            <strong>{{ avgRatingText }}</strong>
          </div>
          <div class="stat-pill">
            <span class="stat-label">总浏览量</span>
            <strong>{{ totalViewText }}</strong>
          </div>
        </div>
      </div>

      <div class="hero-actions">
        <template v-if="authStore.isLoggedIn">
          <el-button type="primary" :loading="refreshing" @click="handleRefresh">
            <el-icon><Refresh /></el-icon>
            刷新推荐
          </el-button>
        </template>
        <template v-else>
          <el-button type="primary" plain @click="router.push('/login')">登录解锁个性化</el-button>
        </template>
        <el-button @click="router.push('/materials')">浏览全部资料</el-button>
      </div>
    </section>

    <el-card v-if="materials.length > 0" class="filter-card" shadow="never">
      <div class="filter-row">
        <div class="category-row">
          <span class="filter-label">分类</span>
          <div class="category-tabs">
            <button
              v-for="category in categoryOptions"
              :key="category"
              class="tab-btn"
              :class="{ active: activeCategory === category }"
              type="button"
              @click="activeCategory = category"
            >
              {{ category }}
            </button>
          </div>
        </div>

        <div class="sort-row">
          <span class="filter-label">排序</span>
          <el-select v-model="sortBy" class="sort-select" size="large">
            <el-option label="最热优先" value="hot" />
            <el-option label="高分优先" value="rating" />
            <el-option label="最近更新" value="latest" />
          </el-select>
        </div>
      </div>
    </el-card>

    <div v-if="loading" v-loading="loading" style="min-height: 260px" />

    <el-empty
      v-else-if="displayedMaterials.length === 0"
      :description="authStore.isLoggedIn ? '暂无推荐，先去浏览资料积累偏好数据吧。' : '暂无热门推荐，请稍后重试。'"
    />

    <template v-else>
      <el-row :gutter="20" class="material-grid">
        <el-col
          v-for="m in visibleMaterials"
          :key="m.id"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
          class="grid-item"
        >
          <material-card :material="m" />
        </el-col>
      </el-row>

      <div v-if="hasMore" class="load-more-wrap">
        <el-button round size="large" @click="handleLoadMore">加载更多资料</el-button>
      </div>

      <section class="insight-section">
        <el-card class="insight-card" shadow="never">
          <template #header>
            <div class="insight-title">热门标签</div>
          </template>
          <div v-if="hotTags.length" class="tag-cloud">
            <el-tag
              v-for="tag in hotTags"
              :key="tag.name"
              class="hot-tag"
              effect="light"
              :type="tag.type"
            >
              {{ tag.name }} · {{ tag.count }}
            </el-tag>
          </div>
          <el-empty v-else description="暂无标签数据" :image-size="56" />
        </el-card>

        <el-card class="insight-card" shadow="never">
          <template #header>
            <div class="insight-title">学习建议</div>
          </template>
          <p class="tip-item">1. 每天至少浏览 3 篇资料，系统会更快学习你的偏好。</p>
          <p class="tip-item">2. 结合资料浏览页的分类筛选，先定方向再深入学习。</p>
        </el-card>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  getPublicRecommendations,
  getRecommendations,
  refreshRecommendations
} from '@/api/recommendations'
import MaterialCard from '@/components/MaterialCard.vue'
import { useAuthStore } from '@/store/auth'

const materials = ref([])
const loading = ref(false)
const refreshing = ref(false)
const sortBy = ref('hot')
const activeCategory = ref('全部')
const visibleCount = ref(8)
const router = useRouter()
const authStore = useAuthStore()

const TAG_TYPE_MAP = ['primary', 'success', 'warning', 'danger', 'info']

const categoryOptions = computed(() => {
  const tags = new Set()
  materials.value.forEach((item) => {
    const firstTag = getPrimaryTag(item)
    if (firstTag) {
      tags.add(firstTag)
    }
  })
  return ['全部', ...Array.from(tags)]
})

const displayedMaterials = computed(() => {
  let list = [...materials.value]

  if (activeCategory.value !== '全部') {
    list = list.filter((item) => getPrimaryTag(item) === activeCategory.value)
  }

  list.sort((a, b) => {
    if (sortBy.value === 'rating') {
      return Number(b?.ratingAvg || 0) - Number(a?.ratingAvg || 0)
    }
    if (sortBy.value === 'latest') {
      return getTimestamp(b?.updatedAt || b?.createdAt) - getTimestamp(a?.updatedAt || a?.createdAt)
    }
    return Number(b?.viewCount || 0) - Number(a?.viewCount || 0)
  })

  return list
})

const visibleMaterials = computed(() => displayedMaterials.value.slice(0, visibleCount.value))
const hasMore = computed(() => displayedMaterials.value.length > visibleCount.value)

const totalViewText = computed(() =>
  formatCount(materials.value.reduce((sum, item) => sum + Number(item?.viewCount || 0), 0))
)

const avgRatingText = computed(() => {
  if (!materials.value.length) {
    return '0.0'
  }
  const ratingSum = materials.value.reduce((sum, item) => sum + Number(item?.ratingAvg || 0), 0)
  return (ratingSum / materials.value.length).toFixed(1)
})

const algoSummary = computed(() => {
  const counter = new Map()
  materials.value.forEach((item) => {
    const key = String(item?.recommendationAlgorithm || '').toUpperCase()
    if (!key) return
    counter.set(key, (counter.get(key) || 0) + 1)
  })
  if (!counter.size) return ''

  const labelMap = {
    HYBRID: '混合算法',
    USERCF: 'UserCF',
    ITEMCF: 'ItemCF',
    TFIDF: 'TF-IDF',
    SVD: 'SVD',
    HOT: '热门回退'
  }

  return Array.from(counter.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([name, count]) => `${labelMap[name] || name} ${count}`)
    .join(' / ')
})

const hotTags = computed(() => {
  const counter = new Map()
  materials.value.forEach((item) => {
    ;(item?.tags || '')
      .split(',')
      .map((tag) => tag.trim())
      .filter(Boolean)
      .forEach((tag) => {
        counter.set(tag, (counter.get(tag) || 0) + 1)
      })
  })

  return Array.from(counter.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([name, count], index) => ({
      name,
      count,
      type: TAG_TYPE_MAP[index % TAG_TYPE_MAP.length]
    }))
})

watch([sortBy, activeCategory], () => {
  visibleCount.value = 8
})

watch(materials, () => {
  visibleCount.value = 8
  if (!categoryOptions.value.includes(activeCategory.value)) {
    activeCategory.value = '全部'
  }
})

async function loadRecommendations() {
  loading.value = true
  try {
    const res = authStore.isLoggedIn ? await getRecommendations() : await getPublicRecommendations()
    materials.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function handleRefresh() {
  if (!authStore.isLoggedIn) {
    ElMessage.warning('请先登录后刷新个性化推荐')
    router.push('/login')
    return
  }

  refreshing.value = true
  try {
    await refreshRecommendations()
    ElMessage.success('推荐已刷新')
    await loadRecommendations()
  } finally {
    refreshing.value = false
  }
}

function handleLoadMore() {
  visibleCount.value += 4
}

function getPrimaryTag(material) {
  return (material?.tags || '')
    .split(',')
    .map((tag) => tag.trim())
    .find(Boolean)
}

function getTimestamp(value) {
  const time = new Date(value || 0).getTime()
  return Number.isNaN(time) ? 0 : time
}

function formatCount(num) {
  const value = Number(num || 0)
  if (value >= 100000000) return `${(value / 100000000).toFixed(1)}亿`
  if (value >= 10000) return `${(value / 10000).toFixed(1)}万`
  return `${value}`
}

onMounted(loadRecommendations)
watch(() => authStore.isLoggedIn, loadRecommendations)
</script>

<style scoped>
.home-view {
  --surface: #ffffff;
  --border-soft: #e8edf5;
  --text-main: #182230;
  --text-sub: #556074;
  --brand: #1e63ff;
  --brand-soft: #edf3ff;

  display: flex;
  flex-direction: column;
  gap: 18px;
}

.hero-panel {
  position: relative;
  overflow: hidden;
  border-radius: 16px;
  padding: 26px;
  background:
    radial-gradient(circle at 86% 12%, rgba(0, 184, 169, 0.18), transparent 42%),
    linear-gradient(135deg, #ffffff 0%, #f4f8ff 100%);
  border: 1px solid var(--border-soft);
  box-shadow: 0 12px 26px rgba(16, 24, 40, 0.06);
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.hero-main {
  max-width: 760px;
}

.hero-kicker {
  font-size: 13px;
  color: #3f5372;
  font-weight: 600;
  margin-bottom: 8px;
}

.hero-title {
  color: var(--text-main);
  font-size: 30px;
  line-height: 1.2;
  margin-bottom: 10px;
}

.hero-subtitle {
  color: var(--text-sub);
  font-size: 14px;
  line-height: 1.6;
  margin-bottom: 16px;
}

.hero-algo {
  margin: -6px 0 16px;
  color: #304a6e;
  font-size: 13px;
}

.hero-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.stat-pill {
  min-width: 118px;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
}

.stat-pill strong {
  display: block;
  color: var(--text-main);
  font-size: 20px;
  margin-top: 4px;
  font-weight: 600;
}

.stat-label {
  color: #6b7588;
  font-size: 12px;
}

.hero-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: flex-start;
  gap: 10px;
}

.filter-card {
  border-radius: 14px;
  border: 1px solid var(--border-soft);
  background: var(--surface);
}

:deep(.filter-card .el-card__body) {
  padding: 14px 16px;
}

.filter-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.category-row,
.sort-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.filter-label {
  font-size: 13px;
  color: #667085;
  flex-shrink: 0;
}

.category-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tab-btn {
  border: 1px solid var(--border-soft);
  background: #fff;
  color: #4f5b6e;
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  transition: all 0.22s ease;
}

.tab-btn:hover {
  border-color: #bfd2ff;
  color: var(--brand);
}

.tab-btn.active {
  background: var(--brand-soft);
  border-color: #b8cbff;
  color: var(--brand);
  font-weight: 600;
}

.sort-select {
  width: 140px;
}

.material-grid {
  margin-top: 2px;
}

.grid-item {
  margin-bottom: 20px;
}

.load-more-wrap {
  display: flex;
  justify-content: center;
  margin: 4px 0 8px;
}

.insight-section {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 6px;
}

.insight-card {
  border-radius: 14px;
  border: 1px solid var(--border-soft);
}

:deep(.insight-card .el-card__header) {
  padding: 14px 16px;
  border-bottom: 1px solid #edf1f7;
}

:deep(.insight-card .el-card__body) {
  padding: 14px 16px 16px;
}

.insight-title {
  font-size: 14px;
  color: var(--text-main);
  font-weight: 600;
}

.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hot-tag {
  border-radius: 999px;
}

.tip-item {
  font-size: 13px;
  color: #4b5565;
  line-height: 1.75;
}

@media (max-width: 1100px) {
  .hero-panel {
    flex-direction: column;
  }

  .hero-actions {
    align-items: flex-start;
    flex-direction: row;
    flex-wrap: wrap;
  }
}

@media (max-width: 900px) {
  .insight-section {
    grid-template-columns: 1fr;
  }

  .hero-title {
    font-size: 24px;
  }
}

@media (max-width: 680px) {
  .hero-panel {
    padding: 18px;
  }

  .filter-row {
    flex-direction: column;
    align-items: flex-start;
  }

  .sort-select {
    width: 100%;
  }

  .sort-row {
    width: 100%;
  }
}
</style>
