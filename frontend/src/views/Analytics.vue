<template>
  <div class="analytics-page" v-loading="loading">
    <section class="hero-panel">
      <div class="hero-copy">
        <p class="hero-eyebrow">运营洞察中心</p>
        <h2>数据分析驾驶舱</h2>
        <p>
          基于系统现有的用户行为、评分、收藏、评论和推荐反馈数据，实时观察内容质量与用户互动趋势。
        </p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" :loading="refreshing" @click="handleRefresh">刷新数据</el-button>
        <el-button type="warning" plain :loading="cleaning" @click="handleCleanLogs">
          清洗并去重行为日志
        </el-button>
      </div>
    </section>

    <section class="kpi-grid">
      <el-card shadow="never" class="kpi-card kpi-a">
        <span>总用户数</span>
        <strong>{{ stats.totalUsers }}</strong>
      </el-card>
      <el-card shadow="never" class="kpi-card kpi-b">
        <span>总资料数</span>
        <strong>{{ stats.totalMaterials }}</strong>
      </el-card>
      <el-card shadow="never" class="kpi-card kpi-c">
        <span>行为日志</span>
        <strong>{{ stats.totalBehaviors }}</strong>
      </el-card>
      <el-card shadow="never" class="kpi-card kpi-d">
        <span>收藏总量</span>
        <strong>{{ stats.totalFavorites }}</strong>
      </el-card>
      <el-card shadow="never" class="kpi-card kpi-e">
        <span>评论总量</span>
        <strong>{{ stats.totalComments }}</strong>
      </el-card>
      <el-card shadow="never" class="kpi-card kpi-f">
        <span>平均评分</span>
        <strong>{{ Number(stats.avgRating || 0).toFixed(2) }}</strong>
      </el-card>
    </section>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>资料热度 TOP 20</span>
              <small>基于行为互动总量</small>
            </div>
          </template>
          <div ref="hotnessChartRef" class="chart-box" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>行为趋势（近14天）</span>
              <small>浏览 / 下载 / 收藏 / 点赞</small>
            </div>
          </template>
          <div ref="trendChartRef" class="chart-box" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>备考阶段分布</span>
              <small>用户画像</small>
            </div>
          </template>
          <div ref="phaseChartRef" class="chart-box" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>行为类型占比</span>
              <small>所有行为动作</small>
            </div>
          </template>
          <div ref="actionChartRef" class="chart-box" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>资料状态分布</span>
              <small>已发布 / 待审核 / 已下架</small>
            </div>
          </template>
          <div ref="statusChartRef" class="chart-box" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>分类热度对比</span>
              <small>浏览 / 下载 / 收藏 / 点赞 / 评论综合</small>
            </div>
          </template>
          <div ref="categoryHotnessChartRef" class="chart-box" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>分类质量象限</span>
              <small>X=平均评分，Y=资料数量</small>
            </div>
          </template>
          <div ref="qualityChartRef" class="chart-box" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>评分分布</span>
              <small>1~5 星评分人数</small>
            </div>
          </template>
          <div ref="ratingChartRef" class="chart-box" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="chart-card">
          <template #header>
            <div class="card-title-row">
              <span>互动漏斗与推荐反馈</span>
              <small>从浏览到下载 + 推荐满意度</small>
            </div>
          </template>
          <div class="dual-chart">
            <div ref="funnelChartRef" class="dual-chart-box" />
            <div ref="feedbackChartRef" class="dual-chart-box" />
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row ops-row">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="ops-card">
          <template #header>
            <div class="card-title-row">
              <span>系统日志查看</span>
              <small>快速定位运行异常</small>
            </div>
          </template>
          <div class="ops-toolbar">
            <el-button type="primary" plain :loading="logsLoading" @click="loadLogs">加载最新 200 行日志</el-button>
          </div>
          <el-scrollbar max-height="240px" class="log-scroll">
            <pre>{{ logsText || '点击按钮查看日志内容' }}</pre>
          </el-scrollbar>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="ops-card">
          <template #header>
            <div class="card-title-row">
              <span>数据库备份快照</span>
              <small>导出当前关键表 JSON 快照</small>
            </div>
          </template>
          <p class="backup-tip">
            包含 users / categories / materials / user_behaviors / user_ratings / user_favorites / recommendations 等数据。
          </p>
          <div class="ops-toolbar ops-toolbar-wrap">
            <el-button type="primary" :loading="backupLoading" @click="downloadBackup">下载备份快照</el-button>
            <el-button type="success" plain :loading="hadoopExporting" @click="handleHadoopExport">
              导出到 Hadoop
            </el-button>
            <el-button plain :loading="hadoopStatusLoading" @click="loadHadoopStatus(true)">
              刷新导出状态
            </el-button>
          </div>
          <el-scrollbar max-height="160px" class="log-scroll">
            <pre>{{ hadoopStatusText }}</pre>
          </el-scrollbar>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { cleanBehaviorLogs, getDashboard, getHotness, getStats, getUserProfile } from '@/api/analytics'
import {
  exportHadoopDatasets,
  getAdminLogs,
  getBackupSnapshot,
  getLatestHadoopExport
} from '@/api/admin'

const loading = ref(false)
const refreshing = ref(false)
const cleaning = ref(false)

const stats = ref({
  totalUsers: 0,
  totalMaterials: 0,
  totalBehaviors: 0,
  totalFavorites: 0,
  totalComments: 0,
  avgRating: 0
})

const hotnessData = ref([])
const trendData = ref([])
const phaseData = ref([])
const actionData = ref([])
const statusData = ref([])
const categoryHotnessData = ref([])
const qualityData = ref([])
const ratingData = ref([])
const funnelData = ref([])
const feedbackData = ref([])

const logsText = ref('')
const logsLoading = ref(false)
const backupLoading = ref(false)
const hadoopExporting = ref(false)
const hadoopStatusLoading = ref(false)
const hadoopStatusText = ref('点击按钮查看 Hadoop 导出状态')
const hasShownCompatNotice = ref(false)

const hotnessChartRef = ref()
const trendChartRef = ref()
const phaseChartRef = ref()
const actionChartRef = ref()
const statusChartRef = ref()
const categoryHotnessChartRef = ref()
const qualityChartRef = ref()
const ratingChartRef = ref()
const funnelChartRef = ref()
const feedbackChartRef = ref()

const chartInstances = new Map()

const phaseLabelMap = {
  PREPARATION: '备考初期',
  INITIAL: '基础阶段',
  INTENSIVE: '强化阶段',
  REVIEW: '冲刺阶段'
}

const actionLabelMap = {
  VIEW: '浏览',
  LIKE: '点赞',
  DISLIKE: '点踩',
  COLLECT: '收藏',
  RATE: '评分',
  DOWNLOAD: '下载'
}

const statusLabelMap = {
  ACTIVE: '已发布',
  PENDING: '待审核',
  DELETED: '已下架'
}

const stageLabelMap = {
  VIEW: '浏览',
  COLLECT: '收藏',
  LIKE: '点赞',
  COMMENT: '评论',
  DOWNLOAD: '下载'
}

const feedbackLabelMap = {
  LIKE: '满意',
  DISLIKE: '不满意'
}

onMounted(async () => {
  await loadDashboard(false)
  await loadHadoopStatus(false)
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  disposeCharts()
})

async function loadDashboard(showMessage = false) {
  loading.value = true
  try {
    const data = await fetchDashboardData()
    applyDashboardData(data)

    await nextTick()
    renderCharts()

    if (showMessage) {
      ElMessage.success('数据分析已刷新')
    }
  } finally {
    loading.value = false
    refreshing.value = false
  }
}

async function fetchDashboardData() {
  try {
    const res = await getDashboard()
    return res.data || {}
  } catch (err) {
    if (err?.response?.status === 404) {
      return loadLegacyDashboardData()
    }
    throw err
  }
}

async function loadLegacyDashboardData() {
  const [statsRes, hotnessRes, phaseRes] = await Promise.all([
    getStats().catch(() => ({ data: {} })),
    getHotness().catch(() => ({ data: [] })),
    getUserProfile().catch(() => ({ data: [] }))
  ])

  if (!hasShownCompatNotice.value) {
    hasShownCompatNotice.value = true
    ElMessage.warning('后端未升级 dashboard 接口，已自动切换兼容模式')
  }

  return {
    stats: statsRes.data || {},
    hotness: hotnessRes.data || [],
    userPhase: phaseRes.data || [],
    behaviorTrend: [],
    actionDistribution: [],
    materialStatus: [],
    categoryHotness: [],
    categoryQuality: [],
    ratingDistribution: [],
    interactionFunnel: [],
    feedbackDistribution: []
  }
}

function applyDashboardData(data) {
  stats.value = {
    ...stats.value,
    ...(data.stats || {})
  }

  hotnessData.value = data.hotness || []
  trendData.value = data.behaviorTrend || []
  phaseData.value = data.userPhase || []
  actionData.value = data.actionDistribution || []
  statusData.value = data.materialStatus || []
  categoryHotnessData.value = data.categoryHotness || []
  qualityData.value = data.categoryQuality || []
  ratingData.value = data.ratingDistribution || []
  funnelData.value = data.interactionFunnel || []
  feedbackData.value = data.feedbackDistribution || []
}

async function handleRefresh() {
  refreshing.value = true
  await loadDashboard(true)
}

async function handleCleanLogs() {
  cleaning.value = true
  try {
    const res = await cleanBehaviorLogs()
    const data = res.data || {}
    ElMessage.success(`去重完成：移除 ${data.removed || 0} 条重复行为日志`)
    await loadDashboard(false)
  } finally {
    cleaning.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await getAdminLogs({ lines: 200 })
    const lines = res.data || []
    logsText.value = lines.join('\n')
  } finally {
    logsLoading.value = false
  }
}

async function loadHadoopStatus(showSuccess = false) {
  hadoopStatusLoading.value = true
  try {
    const res = await getLatestHadoopExport()
    hadoopStatusText.value = formatHadoopStatus(res.data || {})
    if (showSuccess) {
      ElMessage.success('Hadoop 导出状态已刷新')
    }
  } catch (err) {
    const backendMsg = err?.response?.data?.message
    hadoopStatusText.value = [
      'Hadoop 导出状态暂不可用',
      backendMsg ? `error: ${backendMsg}` : 'error: 无法连接 Hadoop 或导出目录尚未初始化'
    ].join('\n')
  } finally {
    hadoopStatusLoading.value = false
  }
}

async function handleHadoopExport() {
  hadoopExporting.value = true
  try {
    const res = await exportHadoopDatasets()
    hadoopStatusText.value = formatHadoopStatusFromExport(res.data || {})
    ElMessage.success('已触发 Hadoop 导出')
  } finally {
    hadoopExporting.value = false
  }
}

function formatHadoopStatus(data) {
  const lines = []
  lines.push(`storage: ${data.storageScheme || '-'} | ${data.storageUri || '-'}`)
  lines.push(`basePath: ${data.basePath || '-'}`)
  lines.push(`generatedAt: ${data.generatedAt || '-'}`)
  lines.push(`totalRows: ${Number(data.totalRows || 0)}`)
  lines.push('')

  const datasets = Array.isArray(data.datasets) ? data.datasets : []
  if (!datasets.length) {
    lines.push('No dataset status found.')
    return lines.join('\n')
  }

  datasets.forEach((item) => {
    lines.push(`[${item.dataset || '-'}]`)
    lines.push(`exists: ${item.exists === false ? 'false' : 'true'}`)
    if (item.latestFile) {
      lines.push(`latestFile: ${item.latestFile}`)
      lines.push(`rows: ${Number(item.rows || 0)} | size: ${Number(item.latestFileSizeBytes || 0)} bytes`)
      lines.push(`modifiedAt: ${item.latestModifiedAt || '-'}`)
    } else {
      lines.push('latestFile: (none)')
    }
    lines.push('')
  })

  return lines.join('\n').trim()
}

function formatHadoopStatusFromExport(data) {
  const lines = []
  lines.push(`trigger: ${data.trigger || '-'}`)
  lines.push(`generatedAt: ${data.generatedAt || '-'}`)
  lines.push(`storage: ${data.storageScheme || '-'} | ${data.storageUri || '-'}`)
  lines.push('')

  const exports = Array.isArray(data.exports) ? data.exports : []
  if (!exports.length) {
    lines.push('No exported datasets.')
    return lines.join('\n')
  }

  exports.forEach((item) => {
    lines.push(`[${item.dataset || '-'}] rows=${Number(item.rows || 0)}`)
    lines.push(String(item.path || '-'))
    lines.push('')
  })
  return lines.join('\n').trim()
}

async function downloadBackup() {
  backupLoading.value = true
  try {
    const res = await getBackupSnapshot()
    const text = JSON.stringify(res.data || {}, null, 2)
    const blob = new Blob([text], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `backup-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.json`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('备份快照已下载')
  } finally {
    backupLoading.value = false
  }
}

function renderCharts() {
  renderHotnessChart()
  renderTrendChart()
  renderPhaseChart()
  renderActionChart()
  renderStatusChart()
  renderCategoryHotnessChart()
  renderQualityChart()
  renderRatingChart()
  renderFunnelChart()
  renderFeedbackChart()
}

function renderHotnessChart() {
  const el = hotnessChartRef.value
  if (!el) return

  setChart('hotness', el, {
    color: ['#2f80ed'],
    tooltip: { trigger: 'axis' },
    grid: { left: 24, right: 16, top: 20, bottom: 66, containLabel: true },
    xAxis: {
      type: 'category',
      data: hotnessData.value.map((item) => shrinkText(item.title || `资料${item.materialId}`, 10)),
      axisLabel: { interval: 0, rotate: 28, fontSize: 11 }
    },
    yAxis: { type: 'value', name: '热度' },
    series: [
      {
        type: 'bar',
        data: hotnessData.value.map((item) => Number(item.count || 0)),
        barMaxWidth: 24,
        itemStyle: {
          borderRadius: [8, 8, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#4f9dff' },
            { offset: 1, color: '#2f80ed' }
          ])
        }
      }
    ]
  })
}

function renderTrendChart() {
  const el = trendChartRef.value
  if (!el) return

  const dates = trendData.value.map((item) => shortDate(item.date))

  setChart('trend', el, {
    color: ['#1f8a70', '#ff9f1c', '#2d6cdf', '#00a6a6'],
    tooltip: { trigger: 'axis' },
    legend: { top: 0 },
    grid: { left: 16, right: 16, top: 38, bottom: 20, containLabel: true },
    xAxis: { type: 'category', data: dates },
    yAxis: { type: 'value', name: '次数' },
    series: [
      {
        name: '浏览',
        type: 'line',
        smooth: true,
        data: trendData.value.map((item) => Number(item.view || 0))
      },
      {
        name: '下载',
        type: 'line',
        smooth: true,
        data: trendData.value.map((item) => Number(item.download || 0))
      },
      {
        name: '收藏',
        type: 'line',
        smooth: true,
        data: trendData.value.map((item) => Number(item.collect || 0))
      },
      {
        name: '点赞',
        type: 'line',
        smooth: true,
        data: trendData.value.map((item) => Number(item.like || 0))
      }
    ]
  })
}

function renderPhaseChart() {
  const el = phaseChartRef.value
  if (!el) return

  setChart('phase', el, {
    color: ['#2d6cdf', '#43aa8b', '#f9c74f', '#f9844a'],
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 4 },
    series: [
      {
        type: 'pie',
        radius: ['38%', '66%'],
        center: ['50%', '44%'],
        label: { formatter: '{b}\n{d}%' },
        data: phaseData.value.map((item) => ({
          name: phaseLabelMap[item.phase] || item.phase,
          value: Number(item.count || 0)
        }))
      }
    ]
  })
}

function renderActionChart() {
  const el = actionChartRef.value
  if (!el) return

  const sorted = [...actionData.value].sort((a, b) => Number(b.count || 0) - Number(a.count || 0))

  setChart('action', el, {
    tooltip: { trigger: 'axis' },
    grid: { left: 78, right: 18, top: 20, bottom: 20 },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      data: sorted.map((item) => actionLabelMap[item.action] || item.action)
    },
    series: [
      {
        type: 'bar',
        barWidth: 14,
        data: sorted.map((item) => Number(item.count || 0)),
        itemStyle: {
          borderRadius: [0, 7, 7, 0],
          color: new echarts.graphic.LinearGradient(1, 0, 0, 0, [
            { offset: 0, color: '#00a6a6' },
            { offset: 1, color: '#2d6cdf' }
          ])
        }
      }
    ]
  })
}

function renderStatusChart() {
  const el = statusChartRef.value
  if (!el) return

  setChart('status', el, {
    color: ['#43aa8b', '#ff9f1c', '#f25f5c'],
    tooltip: { trigger: 'item', formatter: '{b}: {c}' },
    legend: { bottom: 4 },
    series: [
      {
        type: 'pie',
        radius: ['34%', '62%'],
        center: ['50%', '44%'],
        data: statusData.value.map((item) => ({
          name: statusLabelMap[item.status] || item.status,
          value: Number(item.count || 0)
        }))
      }
    ]
  })
}

function renderCategoryHotnessChart() {
  const el = categoryHotnessChartRef.value
  if (!el) return

  const categories = categoryHotnessData.value.map((item) => item.category)
  const hotness = categoryHotnessData.value.map((item) => Number(item.hotness || 0))

  setChart('categoryHotness', el, {
    tooltip: {
      trigger: 'axis',
      formatter: (params) => {
        const idx = params?.[0]?.dataIndex ?? -1
        const row = categoryHotnessData.value[idx]
        if (!row) return ''
        return [
          `<strong>${row.category}</strong>`,
          `热度指数：${row.hotness || 0}`,
          `浏览：${formatCount(row.views)}`,
          `下载：${formatCount(row.downloads)}`,
          `收藏：${formatCount(row.favorites)}`,
          `点赞：${formatCount(row.likes)}`,
          `评论：${formatCount(row.comments)}`
        ].join('<br/>')
      }
    },
    grid: { left: 26, right: 18, top: 20, bottom: 26, containLabel: true },
    xAxis: { type: 'value', name: '热度' },
    yAxis: {
      type: 'category',
      data: categories,
      axisLabel: { width: 92, overflow: 'truncate' }
    },
    series: [
      {
        type: 'bar',
        data: hotness,
        barWidth: 16,
        itemStyle: {
          borderRadius: [0, 8, 8, 0],
          color: new echarts.graphic.LinearGradient(1, 0, 0, 0, [
            { offset: 0, color: '#ff9f1c' },
            { offset: 1, color: '#f9844a' }
          ])
        }
      }
    ]
  })
}

function renderQualityChart() {
  const el = qualityChartRef.value
  if (!el) return

  const points = qualityData.value.map((item) => [
    Number(item.avgRating || 0),
    Number(item.materialCount || 0),
    Number(item.ratingUsers || 0),
    item.category
  ])

  setChart('quality', el, {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        const data = params.data || []
        return [
          `<strong>${data[3] || ''}</strong>`,
          `平均评分：${Number(data[0] || 0).toFixed(2)}`,
          `资料数量：${data[1] || 0}`,
          `评分人数：${data[2] || 0}`
        ].join('<br/>')
      }
    },
    xAxis: { type: 'value', name: '平均评分', min: 0, max: 5.2 },
    yAxis: { type: 'value', name: '资料数量' },
    grid: { left: 18, right: 18, top: 26, bottom: 24, containLabel: true },
    series: [
      {
        type: 'scatter',
        data: points,
        symbolSize: (val) => 10 + Math.min(24, Number(val?.[2] || 0) / 40),
        itemStyle: {
          color: '#2d6cdf',
          opacity: 0.78
        },
        label: {
          show: true,
          formatter: (params) => shrinkText(params?.data?.[3] || '', 6),
          color: '#475569',
          fontSize: 11,
          position: 'top'
        }
      }
    ]
  })
}

function renderRatingChart() {
  const el = ratingChartRef.value
  if (!el) return

  setChart('rating', el, {
    color: ['#f9c74f'],
    tooltip: { trigger: 'axis' },
    grid: { left: 24, right: 16, top: 20, bottom: 24, containLabel: true },
    xAxis: {
      type: 'category',
      data: ratingData.value.map((item) => `${item.rating} 星`)
    },
    yAxis: { type: 'value', name: '人数' },
    series: [
      {
        type: 'bar',
        data: ratingData.value.map((item) => Number(item.count || 0)),
        barMaxWidth: 30,
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#ffd166' },
            { offset: 1, color: '#f9c74f' }
          ])
        }
      }
    ]
  })
}

function renderFunnelChart() {
  const el = funnelChartRef.value
  if (!el) return

  setChart('funnel', el, {
    tooltip: { trigger: 'item', formatter: '{b}: {c}' },
    series: [
      {
        type: 'funnel',
        left: '2%',
        top: 16,
        bottom: 12,
        width: '96%',
        min: 0,
        maxSize: '100%',
        sort: 'descending',
        gap: 2,
        label: { show: true, position: 'inside' },
        itemStyle: { borderColor: '#fff', borderWidth: 1 },
        color: ['#2d6cdf', '#43aa8b', '#00a6a6', '#ff9f1c', '#f9844a'],
        data: funnelData.value.map((item) => ({
          name: stageLabelMap[item.stage] || item.stage,
          value: Number(item.count || 0)
        }))
      }
    ]
  })
}

function renderFeedbackChart() {
  const el = feedbackChartRef.value
  if (!el) return

  setChart('feedback', el, {
    color: ['#43aa8b', '#f25f5c'],
    tooltip: { trigger: 'item', formatter: '{b}: {c}' },
    series: [
      {
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '50%'],
        label: { formatter: '{b}\n{d}%' },
        data: feedbackData.value.map((item) => ({
          name: feedbackLabelMap[item.feedback] || item.feedback,
          value: Number(item.count || 0)
        }))
      }
    ]
  })
}

function setChart(key, el, option) {
  if (!el) return

  if (chartInstances.has(key)) {
    chartInstances.get(key).dispose()
    chartInstances.delete(key)
  }

  const instance = echarts.init(el)
  instance.setOption(option)
  chartInstances.set(key, instance)
}

function resizeCharts() {
  chartInstances.forEach((instance) => instance.resize())
}

function disposeCharts() {
  chartInstances.forEach((instance) => instance.dispose())
  chartInstances.clear()
}

function shortDate(value) {
  if (!value || typeof value !== 'string') return value
  return value.length >= 10 ? value.slice(5, 10) : value
}

function shrinkText(value, maxLen) {
  if (!value) return ''
  return value.length > maxLen ? `${value.slice(0, maxLen)}...` : value
}

function formatCount(value) {
  const num = Number(value || 0)
  if (num >= 10000) return `${(num / 10000).toFixed(1)}万`
  return `${num}`
}
</script>

<style scoped>
.analytics-page {
  --ink-900: #0f172a;
  --ink-700: #334155;
  --ink-500: #64748b;
  --line: #dbe4ef;
  --paper: #f8fbff;
  --card-shadow: 0 12px 24px rgba(15, 23, 42, 0.05);
  --radius: 14px;

  font-family: 'Source Han Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

.hero-panel {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background:
    radial-gradient(circle at 15% 20%, rgba(45, 108, 223, 0.08), transparent 45%),
    radial-gradient(circle at 88% 12%, rgba(67, 170, 139, 0.1), transparent 40%),
    linear-gradient(120deg, #f7fbff 0%, #f9fcff 100%);
}

.hero-eyebrow {
  margin: 0 0 6px;
  font-size: 12px;
  color: #2d6cdf;
  letter-spacing: 0.06em;
}

.hero-copy h2 {
  margin: 0;
  font-size: 30px;
  color: var(--ink-900);
}

.hero-copy p {
  margin: 8px 0 0;
  color: var(--ink-500);
  line-height: 1.6;
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.kpi-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  box-shadow: var(--card-shadow);
}

.kpi-card :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px;
}

.kpi-card span {
  font-size: 12px;
  color: var(--ink-500);
}

.kpi-card strong {
  font-size: 30px;
  line-height: 1;
  color: var(--ink-900);
  font-weight: 700;
}

.kpi-a { background: linear-gradient(135deg, #eff6ff, #f8fbff); }
.kpi-b { background: linear-gradient(135deg, #ecfeff, #f7feff); }
.kpi-c { background: linear-gradient(135deg, #fff7ed, #fffdf7); }
.kpi-d { background: linear-gradient(135deg, #f0fdf4, #f8fff9); }
.kpi-e { background: linear-gradient(135deg, #fefce8, #fffef5); }
.kpi-f { background: linear-gradient(135deg, #fff1f2, #fff8f8); }

.chart-row {
  margin-bottom: 14px;
}

.chart-card,
.ops-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--card-shadow);
  background: var(--paper);
}

.card-title-row {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  color: var(--ink-700);
  font-weight: 600;
}

.card-title-row small {
  font-size: 12px;
  color: #7a8aa0;
  font-weight: 400;
}

.chart-box {
  height: 320px;
}

.dual-chart {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.dual-chart-box {
  height: 320px;
}

.ops-row {
  margin-top: 2px;
}

.ops-toolbar {
  margin-bottom: 10px;
}

.ops-toolbar-wrap {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.log-scroll pre {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: #233045;
  white-space: pre-wrap;
}

.backup-tip {
  margin: 0 0 14px;
  color: var(--ink-500);
  line-height: 1.7;
}

@media (max-width: 1500px) {
  .kpi-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 1100px) {
  .hero-panel {
    flex-direction: column;
  }

  .dual-chart {
    grid-template-columns: 1fr;
  }

  .dual-chart-box {
    height: 280px;
  }
}

@media (max-width: 800px) {
  .kpi-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .hero-copy h2 {
    font-size: 24px;
  }

  .chart-box {
    height: 280px;
  }
}
</style>


