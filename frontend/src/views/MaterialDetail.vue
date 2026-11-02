<template>
  <div class="detail-view" v-loading="loading">
    <el-page-header @back="$router.back()" class="page-header">
      <template #content>资料详情</template>
    </el-page-header>

    <template v-if="material">
      <el-row :gutter="20">
        <el-col :xs="24" :lg="16">
          <el-card class="hero-card" shadow="never">
            <div class="hero-cover" :style="{ background: coverGradient }">
              <div class="cover-badge">
                <el-icon><PictureFilled /></el-icon>
                资料图
              </div>
              <div class="cover-main">
                <strong>{{ primaryTag }}</strong>
                <p>{{ secondaryTag }}</p>
              </div>
              <div class="cover-tags">
                <el-tag v-for="tag in tags.slice(0, 4)" :key="tag" size="small" effect="dark">{{ tag }}</el-tag>
              </div>
            </div>

            <div class="hero-body">
              <h2>{{ material.title }}</h2>
              <div class="meta-pills">
                <span class="pill"><el-icon><User /></el-icon>{{ material.author || '未知作者' }}</span>
                <span class="pill"><el-icon><View /></el-icon>{{ formatCount(material.viewCount) }} 浏览</span>
                <span class="pill"><el-icon><Download /></el-icon>{{ formatCount(material.downloadCount) }} 下载</span>
                <span class="pill"><el-icon><Star /></el-icon>{{ Number(material.ratingAvg || 0).toFixed(1) }} 分</span>
                <span class="pill"><el-icon><Pointer /></el-icon>{{ likes.likeCount }} 点赞</span>
              </div>

              <div class="intro-box">
                <h3>内容简介</h3>
                <p v-for="(line, idx) in descriptionLines" :key="idx">{{ line }}</p>
              </div>

              <div class="point-list">
                <div v-for="(point, idx) in introPoints" :key="idx" class="point-item">{{ point }}</div>
              </div>
            </div>
          </el-card>

          <el-card class="comment-card" shadow="never">
            <template #header>
              <div class="card-header-row">
                <div class="card-title">
                  <el-icon><ChatLineRound /></el-icon>
                  <span>评论区</span>
                </div>
                <span class="count-text">共 {{ comments.length }} 条评论</span>
              </div>
            </template>

            <div v-if="isLoggedIn" class="comment-input-wrap">
              <el-input
                v-model="commentInput"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 5 }"
                maxlength="500"
                show-word-limit
                placeholder="说说你对这份资料的看法、适用阶段或刷题建议..."
              />
              <div class="comment-action">
                <span>建议分享使用场景，帮助其他同学快速判断是否适合。</span>
                <el-button type="primary" :loading="postingComment" @click="submitComment">发布评论</el-button>
              </div>
            </div>
            <div v-else class="comment-login-tip">
              登录后可发表评论与互动。
              <el-button link type="primary" @click="router.push('/login')">去登录</el-button>
            </div>

            <el-empty v-if="comments.length === 0" description="还没有评论，来发布第一条吧" :image-size="60" />

            <div v-else class="comment-list">
              <div v-for="item in comments" :key="item.id" class="comment-item">
                <el-avatar class="comment-avatar">{{ getUserInitial(item.username) }}</el-avatar>
                <div class="comment-content">
                  <div class="comment-head">
                    <strong>{{ item.username || '匿名用户' }}</strong>
                    <span>{{ formatCommentTime(item.createdAt) }}</span>
                  </div>
                  <p>{{ item.content }}</p>
                </div>
              </div>
            </div>
          </el-card>
        </el-col>

        <el-col :xs="24" :lg="8">
          <template v-if="isLoggedIn">
            <el-card class="side-card" shadow="never">
              <template #header>
                <div class="card-title">我的评分</div>
              </template>
              <div class="rate-panel">
                <el-rate v-model="myRating" :max="5" size="large" />
                <el-button type="primary" @click="submitRating">提交评分</el-button>
              </div>
            </el-card>

            <el-card class="side-card" shadow="never">
              <template #header>
                <div class="card-title">互动操作</div>
              </template>

              <div class="action-grid">
                <el-button :type="likes.liked ? 'primary' : 'default'" plain @click="handleLike">
                  <el-icon><Pointer /></el-icon>
                  {{ likes.liked ? '已点赞' : '点赞' }} ({{ likes.likeCount }})
                </el-button>
                <el-button :type="favorited ? 'warning' : 'default'" plain @click="handleFavorite">
                  <el-icon><Star /></el-icon>
                  {{ favorited ? '已收藏' : '收藏' }}
                </el-button>
                <el-button type="primary" @click="handleDownload">
                  <el-icon><Download /></el-icon>
                  下载资料
                </el-button>
              </div>

              <!-- 推荐反馈：告诉系统这条推荐是否合适，会直接影响后续推荐排序 -->
              <div class="feedback-row">
                <span class="feedback-label">这条推荐对你有帮助吗？</span>
                <el-button
                  size="small"
                  :type="feedback === 'LIKE' ? 'success' : 'default'"
                  plain
                  @click="handleFeedback('LIKE')"
                >
                  有用
                </el-button>
                <el-button
                  size="small"
                  :type="feedback === 'DISLIKE' ? 'danger' : 'default'"
                  plain
                  @click="handleFeedback('DISLIKE')"
                >
                  没用
                </el-button>
              </div>
            </el-card>

            <el-card class="side-card" shadow="never">
              <template #header>
                <div class="card-header-row">
                  <div class="card-title">AI 内容摘要</div>
                  <el-button link type="primary" :loading="summarizing" @click="getSummary">生成</el-button>
                </div>
              </template>

              <div v-if="summary" class="summary-text">{{ summary }}</div>
              <el-empty v-else description="点击生成摘要" :image-size="56" />
            </el-card>
          </template>

          <template v-else>
            <el-card class="side-card" shadow="never">
              <template #header>
                <div class="card-title">登录后可用功能</div>
              </template>
              <p class="guest-tip">
                登录后可点赞、收藏、评分、评论、下载并使用 AI 摘要，让推荐更贴合你的学习需求。
              </p>
              <el-button type="primary" style="width: 100%" @click="router.push('/login')">去登录</el-button>
            </el-card>
          </template>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  createMaterialComment,
  downloadMaterial,
  getFavoriteStatus,
  getLikeState,
  getMaterial,
  getMaterialComments,
  rateMaterial,
  toggleFavorite,
  toggleLike
} from '@/api/materials'
import { summarize } from '@/api/ai'
import { cancelFeedback, getMyFeedback, submitFeedback } from '@/api/recommendations'
import { useAuthStore } from '@/store/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const material = ref(null)
const loading = ref(false)
const myRating = ref(0)
const favorited = ref(false)
const summary = ref('')
const summarizing = ref(false)
const likes = ref({ liked: false, likeCount: 0 })
const comments = ref([])
const commentInput = ref('')
const postingComment = ref(false)
/** 当前用户对这条资料的推荐反馈：'LIKE' / 'DISLIKE' / '' */
const feedback = ref('')

const coverGradients = [
  'linear-gradient(135deg, #1e63ff 0%, #4b88ff 48%, #8db8ff 100%)',
  'linear-gradient(135deg, #0f9d8c 0%, #22c3a3 50%, #8be7d7 100%)',
  'linear-gradient(135deg, #ff8f1f 0%, #ffb546 50%, #ffd79b 100%)',
  'linear-gradient(135deg, #2f4f9c 0%, #5e80d8 52%, #9fb8f5 100%)'
]

const tags = computed(() =>
  (material.value?.tags || '')
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)
)

const primaryTag = computed(() => tags.value[0] || '综合')
const secondaryTag = computed(() => tags.value[1] || '精选资料')
const isLoggedIn = computed(() => authStore.isLoggedIn)

const coverGradient = computed(() => {
  const id = Number(material.value?.id || 0)
  return coverGradients[id % coverGradients.length]
})

const descriptionLines = computed(() => {
  const raw = material.value?.description?.trim()
  if (!raw) return ['暂无简介']

  const byLine = raw
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean)

  if (byLine.length > 1) {
    return byLine.slice(0, 4)
  }

  const bySentence = raw
    .split(/(?<=[。！？])/)
    .map((item) => item.trim())
    .filter(Boolean)

  return bySentence.length ? bySentence.slice(0, 4) : [raw]
})

const introPoints = computed(() => {
  const result = []
  if (tags.value.length) {
    result.push(`标签方向：${tags.value.slice(0, 4).join(' / ')}`)
  }
  result.push(`当前评分：${Number(material.value?.ratingAvg || 0).toFixed(1)}（${material.value?.ratingCount || 0} 人）`)
  result.push(`浏览/下载：${formatCount(material.value?.viewCount || 0)} / ${formatCount(material.value?.downloadCount || 0)}`)
  return result
})

async function loadMaterial() {
  loading.value = true
  try {
    const res = await getMaterial(route.params.id)
    material.value = res.data
    await Promise.all([loadInteractionState(), loadComments(), loadFeedback()])
  } finally {
    loading.value = false
  }
}

async function loadInteractionState() {
  const likeRes = await getLikeState(route.params.id)
  likes.value = likeRes.data || { liked: false, likeCount: 0 }

  if (isLoggedIn.value) {
    const favRes = await getFavoriteStatus(route.params.id)
    favorited.value = !!favRes.data?.favorited
  } else {
    favorited.value = false
  }
}

async function loadComments() {
  const res = await getMaterialComments(route.params.id)
  comments.value = res.data || []
}

async function submitRating() {
  if (!myRating.value) return ElMessage.warning('请选择评分')
  await rateMaterial(route.params.id, myRating.value)
  ElMessage.success('评分成功')
  loadMaterial()
}

async function handleLike() {
  if (!isLoggedIn.value) {
    ElMessage.warning('请先登录后点赞')
    router.push('/login')
    return
  }

  const res = await toggleLike(route.params.id)
  likes.value = res.data || likes.value
  ElMessage.success(likes.value.liked ? '点赞成功' : '已取消点赞')
}

async function handleFavorite() {
  if (!isLoggedIn.value) {
    ElMessage.warning('请先登录后收藏')
    router.push('/login')
    return
  }

  const res = await toggleFavorite(route.params.id)
  favorited.value = !!res.data?.favorited
  ElMessage.success(favorited.value ? '已收藏' : '已取消收藏')
}

async function handleFeedback(type) {
  if (!isLoggedIn.value) {
    ElMessage.warning('请先登录后反馈')
    router.push('/login')
    return
  }
  try {
    if (feedback.value === type) {
      // 再点一次 = 撤销
      await cancelFeedback(route.params.id)
      feedback.value = ''
      ElMessage.success('已撤销反馈')
    } else {
      const res = await submitFeedback(route.params.id, type)
      feedback.value = res.data?.feedback || type
      ElMessage.success(
        feedback.value === 'LIKE' ? '已记录：这条推荐有用' : '已记录：这条推荐没用，之后会减少推荐'
      )
    }
  } catch (e) {
    // 错误提示由 axios 拦截器统一处理
  }
}

async function loadFeedback() {
  if (!isLoggedIn.value) {
    feedback.value = ''
    return
  }
  try {
    const res = await getMyFeedback()
    feedback.value = res.data?.mine?.[String(route.params.id)] || ''
  } catch (e) {
    feedback.value = ''
  }
}

async function handleDownload() {
  if (!isLoggedIn.value) {
    ElMessage.warning('请先登录后下载')
    router.push('/login')
    return
  }

  const fileBlob = await downloadMaterial(route.params.id)
  const blob = fileBlob instanceof Blob ? fileBlob : new Blob([fileBlob])
  const url = URL.createObjectURL(blob)
  const filename = material.value?.fileName || `material-${route.params.id}`

  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)

  ElMessage.success('下载已开始')
  await loadMaterial()
}

async function submitComment() {
  if (!isLoggedIn.value) {
    ElMessage.warning('请先登录后评论')
    router.push('/login')
    return
  }

  const content = commentInput.value.trim()
  if (!content) {
    ElMessage.warning('请输入评论内容')
    return
  }

  postingComment.value = true
  try {
    await createMaterialComment(route.params.id, content)
    commentInput.value = ''
    ElMessage.success('评论已发布')
    await loadComments()
  } finally {
    postingComment.value = false
  }
}

async function getSummary() {
  summarizing.value = true
  try {
    const res = await summarize(route.params.id)
    summary.value = res.data.summary
  } finally {
    summarizing.value = false
  }
}

function getUserInitial(name) {
  const value = String(name || '匿').trim()
  return value.slice(0, 1).toUpperCase()
}

function formatCommentTime(value) {
  if (!value) return '刚刚'
  return value.length >= 16 ? value.slice(0, 16) : value
}

function formatCount(num) {
  const value = Number(num || 0)
  if (value >= 100000000) return `${(value / 100000000).toFixed(1)}亿`
  if (value >= 10000) return `${(value / 10000).toFixed(1)}万`
  return `${value}`
}

onMounted(loadMaterial)
watch(() => route.params.id, loadMaterial)
</script>

<style scoped>
.detail-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.page-header {
  margin-bottom: 8px;
}

.hero-card,
.comment-card,
.side-card {
  border-radius: 14px;
  border: 1px solid #e6ecf5;
}

.hero-card {
  overflow: hidden;
}

:deep(.hero-card .el-card__body) {
  padding: 0;
}

.hero-cover {
  min-height: 152px;
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  color: #fff;
}

.cover-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.2);
  font-size: 12px;
}

.cover-main strong {
  display: block;
  font-size: 24px;
  line-height: 1.2;
}

.cover-main p {
  margin-top: 6px;
  font-size: 13px;
  opacity: 0.9;
}

.cover-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.hero-body {
  padding: 16px;
}

.hero-body h2 {
  color: #1a2535;
  font-size: 28px;
  line-height: 1.25;
  margin-bottom: 12px;
}

.meta-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid #e4ebf8;
  border-radius: 999px;
  padding: 6px 10px;
  font-size: 12px;
  color: #556177;
  background: #fbfcff;
}

.intro-box {
  border: 1px solid #e6ecf8;
  border-radius: 12px;
  padding: 12px;
  background: #f9fbff;
}

.intro-box h3 {
  font-size: 15px;
  color: #1d293b;
  margin-bottom: 8px;
}

.intro-box p {
  color: #4f5c72;
  line-height: 1.75;
  font-size: 14px;
}

.intro-box p + p {
  margin-top: 4px;
}

.point-list {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.point-item {
  border-radius: 10px;
  background: #f2f6ff;
  color: #41506a;
  padding: 9px 10px;
  font-size: 12px;
  line-height: 1.5;
}

.card-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.card-title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #1e293b;
  font-weight: 600;
}

.count-text {
  font-size: 12px;
  color: #7d8aa0;
}

.comment-card {
  margin-top: 16px;
}

.comment-input-wrap {
  margin-bottom: 14px;
}

.comment-action {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.comment-action span {
  font-size: 12px;
  color: #7c879c;
}

.comment-login-tip {
  padding: 12px;
  border-radius: 10px;
  background: #f5f8ff;
  color: #60708b;
  margin-bottom: 10px;
}

.comment-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comment-item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  border: 1px solid #e8edf8;
  border-radius: 12px;
  padding: 10px 11px;
  background: #fff;
}

.comment-avatar {
  flex-shrink: 0;
  background: linear-gradient(135deg, #1e63ff 0%, #4b88ff 100%);
}

.comment-content {
  min-width: 0;
  flex: 1;
}

.comment-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.comment-head strong {
  color: #1f2b3e;
  font-size: 13px;
}

.comment-head span {
  color: #8a96aa;
  font-size: 12px;
}

.comment-content p {
  color: #445167;
  font-size: 14px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.side-card {
  margin-bottom: 14px;
}

.rate-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.action-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.feedback-row {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #e3e8f0;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.feedback-label {
  font-size: 12px;
  color: #7b8aa0;
}

.summary-text {
  font-size: 13px;
  color: #4a576d;
  line-height: 1.8;
  white-space: pre-wrap;
}

.guest-tip {
  color: #58667e;
  line-height: 1.75;
  margin: 0 0 12px;
}

@media (max-width: 1200px) {
  .point-list {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 768px) {
  .hero-body h2 {
    font-size: 22px;
  }

  .point-list {
    grid-template-columns: 1fr;
  }

  .comment-action {
    flex-direction: column;
    align-items: flex-end;
  }
}
</style>
