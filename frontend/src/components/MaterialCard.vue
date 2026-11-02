<template>
  <el-card class="material-card" shadow="hover" @click="goDetail">
    <div class="card-cover" :style="{ background: coverGradient }">
      <el-tag class="cover-tag" effect="dark" type="info">{{ primaryTag }}</el-tag>
      <p class="cover-sub">{{ secondaryTag }}</p>
    </div>

    <div class="card-content">
      <h4 class="title">{{ material.title }}</h4>
      <p class="desc">{{ material.description || '暂无简介' }}</p>

      <div class="meta-grid">
        <div class="meta-item">
          <span class="meta-label">评分</span>
          <strong>{{ ratingText }}</strong>
        </div>
        <div class="meta-item">
          <span class="meta-label">浏览</span>
          <strong>{{ viewText }}</strong>
        </div>
        <div class="meta-item">
          <span class="meta-label">更新</span>
          <strong>{{ updateText }}</strong>
        </div>
      </div>

      <div class="rate-row">
        <el-rate v-model="ratingValue" disabled :max="5" size="small" />
        <span class="rate-count">{{ material.ratingCount || 0 }} 人评分</span>
      </div>
    </div>

    <div class="card-footer" @click.stop>
      <el-tag
        v-if="algoText"
        size="small"
        effect="light"
        type="info"
        class="algo-tag"
      >
        {{ algoText }}
      </el-tag>
      <el-button link type="primary" @click="goDetail">查看详情</el-button>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const props = defineProps({
  material: {
    type: Object,
    required: true
  }
})

const router = useRouter()
const coverGradients = [
  'linear-gradient(135deg, #1e63ff 0%, #4b88ff 55%, #86b7ff 100%)',
  'linear-gradient(135deg, #0f9d8c 0%, #1ec3a2 60%, #8ae8d6 100%)',
  'linear-gradient(135deg, #ff8f1f 0%, #ffb347 55%, #ffd699 100%)',
  'linear-gradient(135deg, #2f4f9c 0%, #5979d1 50%, #95b0f5 100%)',
  'linear-gradient(135deg, #1f7a8c 0%, #3ea5b2 50%, #9edce3 100%)'
]

const tags = computed(() =>
  (props.material?.tags || '')
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)
)

const primaryTag = computed(() => tags.value[0] || '综合')
const secondaryTag = computed(() => tags.value[1] || '优选资料')
const ratingValue = computed(() => Number(props.material?.ratingAvg || 0))
const ratingText = computed(() => Number(props.material?.ratingAvg || 0).toFixed(1))
const viewText = computed(() => formatCount(props.material?.viewCount || 0))
const updateText = computed(() => formatDate(props.material?.updatedAt || props.material?.createdAt))
const algoText = computed(() => {
  const value = String(props.material?.recommendationAlgorithm || '').toUpperCase()
  if (!value) return ''
  const map = {
    HYBRID: '混合算法',
    USERCF: 'UserCF',
    ITEMCF: 'ItemCF',
    TFIDF: 'TF-IDF',
    SVD: 'SVD',
    HOT: '热门回退'
  }
  return map[value] || value
})

const coverGradient = computed(() => {
  const id = Number(props.material?.id || 0)
  return coverGradients[id % coverGradients.length]
})

function goDetail() {
  router.push(`/materials/${props.material.id}`)
}

function formatCount(num) {
  const value = Number(num || 0)
  if (value >= 100000000) return `${(value / 100000000).toFixed(1)}亿`
  if (value >= 10000) return `${(value / 10000).toFixed(1)}万`
  return `${value}`
}

function formatDate(value) {
  if (!value) return '暂无'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '暂无'
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${month}-${day}`
}
</script>

<style scoped>
.material-card {
  height: 100%;
  cursor: pointer;
  border: 1px solid #e8edf5;
  border-radius: 16px;
  overflow: hidden;
  transition: transform 0.24s ease, box-shadow 0.24s ease, border-color 0.24s ease;
}

.material-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 16px 30px rgba(16, 24, 40, 0.12);
  border-color: #bfd3ff;
}

:deep(.material-card .el-card__body) {
  height: 100%;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.card-cover {
  min-height: 92px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.cover-tag {
  width: fit-content;
  border: none;
  background: rgba(12, 22, 39, 0.24);
  color: #fff;
}

.cover-sub {
  color: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  margin: 0;
}

.card-content {
  flex: 1;
  padding: 14px 14px 10px;
  display: flex;
  flex-direction: column;
}

.title {
  font-size: 16px;
  font-weight: 600;
  color: #182230;
  line-height: 1.45;
  margin: 0 0 8px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.desc {
  color: #5f6b7e;
  font-size: 13px;
  line-height: 1.55;
  margin: 0 0 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  margin-bottom: 10px;
}

.meta-item {
  border-radius: 10px;
  background: #f7f9fd;
  padding: 7px 8px;
  text-align: center;
}

.meta-label {
  display: block;
  color: #7f8ba0;
  font-size: 11px;
  margin-bottom: 3px;
}

.meta-item strong {
  color: #2a3342;
  font-size: 12px;
  font-weight: 600;
}

.rate-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-top: auto;
}

.rate-count {
  color: #97a1b3;
  font-size: 12px;
  flex-shrink: 0;
}

.card-footer {
  border-top: 1px solid #edf1f7;
  padding: 10px 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.algo-tag {
  border-color: #d2def5;
  color: #3e567d;
}

@media (max-width: 768px) {
  .meta-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
