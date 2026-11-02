<template>
  <div class="admin-materials-page">
    <section class="hero-panel">
      <div class="hero-content">
        <p class="eyebrow">内容运营中台</p>
        <h2>资料管理驾驶舱</h2>
        <p class="subtitle">批量管理资料状态、分类与质量，让资料池持续保持高可用和高命中。</p>
        <div class="hero-actions">
          <el-button type="primary" @click="showUpload = true">上传新资料</el-button>
          <el-button @click="showCategoryDialog = true">分类管理</el-button>
          <el-button text @click="refreshAll">
            <el-icon><Refresh /></el-icon>
            刷新数据
          </el-button>
        </div>
      </div>
      <div class="hero-stats">
        <div class="stat-card card-a">
          <span>资料总量</span>
          <strong>{{ overview.total }}</strong>
        </div>
        <div class="stat-card card-b">
          <span>已发布</span>
          <strong>{{ overview.active }}</strong>
        </div>
        <div class="stat-card card-c">
          <span>待审核</span>
          <strong>{{ overview.pending }}</strong>
        </div>
        <div class="stat-card card-d">
          <span>已下架</span>
          <strong>{{ overview.deleted }}</strong>
        </div>
      </div>
    </section>

    <el-card class="filter-card" shadow="never">
      <div class="filter-top">
        <el-input
          v-model="keyword"
          class="search-input"
          placeholder="按标题、作者、标签筛选当前页"
          clearable
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>

        <el-select
          v-model="selectedCategoryId"
          class="category-select"
          placeholder="分类筛选"
          clearable
        >
          <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>

        <el-select
          v-model="statusFilter"
          class="status-select"
          placeholder="状态筛选"
          clearable
          @change="onStatusChange"
        >
          <el-option label="已发布" value="ACTIVE" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已下架" value="DELETED" />
        </el-select>
      </div>

      <div class="filter-bottom">
        <div class="quick-tags">
          <el-tag :effect="statusFilter === '' ? 'dark' : 'plain'" class="quick-tag" @click="setStatusFilter('')">
            全部 {{ overview.total }}
          </el-tag>
          <el-tag
            :effect="statusFilter === 'ACTIVE' ? 'dark' : 'plain'"
            type="success"
            class="quick-tag"
            @click="setStatusFilter('ACTIVE')"
          >
            已发布 {{ overview.active }}
          </el-tag>
          <el-tag
            :effect="statusFilter === 'PENDING' ? 'dark' : 'plain'"
            type="warning"
            class="quick-tag"
            @click="setStatusFilter('PENDING')"
          >
            待审核 {{ overview.pending }}
          </el-tag>
          <el-tag
            :effect="statusFilter === 'DELETED' ? 'dark' : 'plain'"
            type="danger"
            class="quick-tag"
            @click="setStatusFilter('DELETED')"
          >
            已下架 {{ overview.deleted }}
          </el-tag>
        </div>
        <div class="filter-tips">
          当前页 {{ materials.length }} 条，筛选后 {{ filteredMaterials.length }} 条
        </div>
      </div>
    </el-card>

    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="table-header">
          <div>
            <h3>资料列表</h3>
            <p>新增待审核流转与批量审核，运营动作更集中。</p>
          </div>
          <div class="table-tools">
            <span class="selected-info">已选 {{ selectedCount }} 条</span>
            <el-button size="small" type="success" plain :disabled="!hasSelection" @click="batchReview('ACTIVE', '批量通过')">
              批量通过
            </el-button>
            <el-button size="small" type="warning" plain :disabled="!hasSelection" @click="batchReview('PENDING', '批量待审')">
              批量待审
            </el-button>
            <el-button size="small" type="danger" plain :disabled="!hasSelection" @click="batchReview('DELETED', '批量驳回')">
              批量驳回
            </el-button>
          </div>
        </div>
      </template>

      <el-table
        ref="materialsTableRef"
        :data="filteredMaterials"
        row-key="id"
        border
        stripe
        v-loading="loading"
        class="materials-table"
        empty-text="暂无资料，先上传一份试试吧"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="48" />
        <el-table-column prop="id" label="ID" width="78" />

        <el-table-column label="标题与简介" min-width="320">
          <template #default="{ row }">
            <div class="title-cell">
              <div class="title-main">{{ row.title }}</div>
              <div class="title-sub">{{ row.description || '暂无简介' }}</div>
              <div class="title-meta">
                <el-tag size="small" effect="plain">
                  {{ categoryMap[row.categoryId] || '未分类' }}
                </el-tag>
                <span>更新于 {{ formatDate(row.updatedAt || row.createdAt) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="作者" width="160">
          <template #default="{ row }">
            <div class="author-cell">
              <el-avatar :size="26">{{ getAuthorInitial(row.author) }}</el-avatar>
              <span>{{ row.author || '未知作者' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="标签" width="230">
          <template #default="{ row }">
            <div class="tags-cell">
              <el-tag
                v-for="tag in splitTags(row.tags).slice(0, 3)"
                :key="tag"
                size="small"
                effect="plain"
                class="tag-chip"
              >
                {{ tag }}
              </el-tag>
              <span v-if="splitTags(row.tags).length > 3" class="more-tag">
                +{{ splitTags(row.tags).length - 3 }}
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="数据表现" width="170">
          <template #default="{ row }">
            <div class="metric-cell">
              <span><el-icon><View /></el-icon>{{ formatCount(row.viewCount) }}</span>
              <span><el-icon><Download /></el-icon>{{ formatCount(row.downloadCount) }}</span>
              <span><el-icon><Star /></el-icon>{{ Number(row.ratingAvg || 0).toFixed(1) }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <div class="status-cell">
              <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <div class="action-cell">
              <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>

              <el-button
                v-if="row.status === 'PENDING' || row.status === 'DELETED'"
                link
                type="success"
                @click="review(row.id, 'ACTIVE', row.status === 'PENDING' ? '通过审核' : '重新发布')"
              >
                {{ row.status === 'PENDING' ? '通过' : '发布' }}
              </el-button>

              <el-button
                v-if="row.status === 'ACTIVE'"
                link
                type="warning"
                @click="review(row.id, 'DELETED', '下架')"
              >
                下架
              </el-button>

              <el-button
                v-if="row.status === 'ACTIVE' || row.status === 'DELETED'"
                link
                type="info"
                @click="review(row.id, 'PENDING', row.status === 'ACTIVE' ? '打回待审核' : '恢复待审核')"
              >
                待审
              </el-button>

              <el-button
                v-if="row.status === 'PENDING'"
                link
                type="danger"
                @click="review(row.id, 'DELETED', '驳回')"
              >
                驳回
              </el-button>

              <el-button link type="info" @click="handleDownload(row)">下载</el-button>
              <el-button v-if="row.status !== 'DELETED'" link type="danger" @click="review(row.id, 'DELETED', '删除')">
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        :current-page="page + 1"
        :page-size="pageSize"
        :page-sizes="[20, 30, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        class="table-pagination"
        @size-change="onPageSizeChange"
        @current-change="onCurrentChange"
      />
    </el-card>

    <el-dialog v-model="showUpload" title="上传资料" width="560px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="新上传资料默认进入待审核，可在列表中执行“通过/驳回”。"
        class="upload-alert"
      />
      <el-form :model="form" label-width="90px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="内容简介">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="例如：英语, 真题, 模板" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" placeholder="选择分类" clearable style="width: 100%">
            <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="作者">
          <el-input v-model="form.author" />
        </el-form-item>
        <el-form-item label="文件">
          <el-upload ref="uploadRef" :auto-upload="false" :limit="1">
            <el-button type="primary" plain>选择文件</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUpload = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="handleUpload">确认上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="showCategoryDialog" title="分类管理" width="820px">
      <div class="category-toolbar">
        <el-input v-model="categoryForm.name" placeholder="分类名称" class="category-name" />
        <el-select v-model="categoryForm.parentId" clearable placeholder="父级分类" class="category-parent">
          <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-input v-model="categoryForm.description" placeholder="分类描述（可选）" />
        <el-button type="primary" :loading="categorySaving" @click="handleSaveCategory">
          {{ categoryForm.id ? '保存修改' : '新增分类' }}
        </el-button>
      </div>

      <el-table :data="categories" border class="category-table">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="分类名称" min-width="150" />
        <el-table-column prop="parentId" label="父级ID" width="90" />
        <el-table-column prop="description" label="描述" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button size="small" @click="editCategory(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDeleteCategory(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <template #footer>
        <el-button @click="resetCategoryForm">重置</el-button>
        <el-button @click="showCategoryDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, Search, Star, View } from '@element-plus/icons-vue'
import {
  batchReviewMaterials,
  createCategory,
  deleteCategory,
  getAdminCategories,
  getAdminMaterials,
  reviewMaterial,
  updateCategory
} from '@/api/admin'
import { createMaterial, downloadMaterial } from '@/api/materials'

const router = useRouter()
const materials = ref([])
const loading = ref(false)
const page = ref(0)
const pageSize = ref(20)
const total = ref(0)
const statusFilter = ref('')
const keyword = ref('')
const selectedCategoryId = ref(null)
const showUpload = ref(false)
const uploading = ref(false)
const uploadRef = ref()
const showCategoryDialog = ref(false)
const categories = ref([])
const categorySaving = ref(false)
const materialsTableRef = ref()
const selectedRowIds = ref([])
const form = reactive({ title: '', description: '', tags: '', author: '', categoryId: null })
const categoryForm = reactive({ id: null, name: '', parentId: null, description: '' })
const overview = reactive({ total: 0, active: 0, pending: 0, deleted: 0 })

const hasSelection = computed(() => selectedRowIds.value.length > 0)
const selectedCount = computed(() => selectedRowIds.value.length)

const categoryMap = computed(() =>
  categories.value.reduce((acc, cur) => {
    acc[cur.id] = cur.name
    return acc
  }, {})
)

const selectedCategoryIdSet = computed(() => {
  const selected = Number(selectedCategoryId.value)
  if (!selected) return null

  const childrenByParent = new Map()
  categories.value.forEach((item) => {
    const parentId = Number(item.parentId)
    const id = Number(item.id)
    if (!Number.isFinite(parentId) || !Number.isFinite(id)) return
    if (!childrenByParent.has(parentId)) {
      childrenByParent.set(parentId, [])
    }
    childrenByParent.get(parentId).push(id)
  })

  const result = new Set([selected])
  const queue = [selected]
  while (queue.length) {
    const current = queue.shift()
    const children = childrenByParent.get(current) || []
    children.forEach((childId) => {
      if (!result.has(childId)) {
        result.add(childId)
        queue.push(childId)
      }
    })
  }
  return result
})

const filteredMaterials = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  const categorySet = selectedCategoryIdSet.value
  return materials.value.filter((item) => {
    const materialCategoryId = Number(item.categoryId)
    const inCategory = !categorySet || categorySet.has(materialCategoryId)
    if (!inCategory) return false
    if (!query) return true
    const source = [item.title, item.author, item.tags].filter(Boolean).join(' ').toLowerCase()
    return source.includes(query)
  })
})

async function loadMaterials() {
  loading.value = true
  try {
    const res = await getAdminMaterials({
      status: statusFilter.value || undefined,
      page: page.value,
      size: pageSize.value
    })
    materials.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
    selectedRowIds.value = []
    await nextTick()
    materialsTableRef.value?.clearSelection()
  } finally {
    loading.value = false
  }
}

async function loadOverview() {
  const [allRes, activeRes, pendingRes, deletedRes] = await Promise.all([
    getAdminMaterials({ page: 0, size: 1 }),
    getAdminMaterials({ status: 'ACTIVE', page: 0, size: 1 }),
    getAdminMaterials({ status: 'PENDING', page: 0, size: 1 }),
    getAdminMaterials({ status: 'DELETED', page: 0, size: 1 })
  ])
  overview.total = allRes.data?.totalElements || 0
  overview.active = activeRes.data?.totalElements || 0
  overview.pending = pendingRes.data?.totalElements || 0
  overview.deleted = deletedRes.data?.totalElements || 0
}

async function loadCategories() {
  const res = await getAdminCategories()
  categories.value = res.data || []
}

async function refreshAll() {
  await Promise.all([loadMaterials(), loadCategories(), loadOverview()])
}

function onStatusChange() {
  page.value = 0
  loadMaterials()
}

function setStatusFilter(status) {
  statusFilter.value = status
  onStatusChange()
}

function onCurrentChange(currentPage) {
  page.value = currentPage - 1
  loadMaterials()
}

function onPageSizeChange(size) {
  pageSize.value = size
  page.value = 0
  loadMaterials()
}

function handleSelectionChange(rows) {
  selectedRowIds.value = rows.map((row) => row.id)
}

async function review(id, status, actionText) {
  await ElMessageBox.confirm(`确认执行“${actionText}”吗？`, '操作确认', { type: 'warning' })
  await reviewMaterial(id, status)
  ElMessage.success(`${actionText}成功`)
  await refreshAll()
}

async function batchReview(status, actionText) {
  if (!selectedRowIds.value.length) {
    ElMessage.warning('请先选择资料')
    return
  }
  await ElMessageBox.confirm(`确认${actionText} ${selectedRowIds.value.length} 条资料吗？`, '批量操作确认', { type: 'warning' })
  const res = await batchReviewMaterials(selectedRowIds.value, status)
  ElMessage.success(`${actionText}完成，已更新 ${res.data?.updated || 0} 条`)
  await refreshAll()
}

async function handleUpload() {
  if (!form.title.trim()) return ElMessage.warning('请填写标题')
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('data', new Blob([JSON.stringify(form)], { type: 'application/json' }))
    const files = uploadRef.value?.uploadFiles || []
    if (files.length > 0) fd.append('file', files[0].raw)
    await createMaterial(fd)
    ElMessage.success('上传成功，已进入待审核')
    showUpload.value = false
    form.title = ''
    form.description = ''
    form.tags = ''
    form.author = ''
    form.categoryId = null
    uploadRef.value?.clearFiles()
    await refreshAll()
  } finally {
    uploading.value = false
  }
}

function editCategory(row) {
  categoryForm.id = row.id
  categoryForm.name = row.name
  categoryForm.parentId = row.parentId
  categoryForm.description = row.description || ''
}

function resetCategoryForm() {
  categoryForm.id = null
  categoryForm.name = ''
  categoryForm.parentId = null
  categoryForm.description = ''
}

async function handleSaveCategory() {
  if (!categoryForm.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }

  categorySaving.value = true
  try {
    const payload = {
      name: categoryForm.name.trim(),
      parentId: categoryForm.parentId,
      description: categoryForm.description || null
    }
    if (categoryForm.id) {
      await updateCategory(categoryForm.id, payload)
      ElMessage.success('分类已更新')
    } else {
      await createCategory(payload)
      ElMessage.success('分类已新增')
    }
    resetCategoryForm()
    await loadCategories()
  } finally {
    categorySaving.value = false
  }
}

async function handleDeleteCategory(id) {
  await ElMessageBox.confirm('确认删除该分类吗？', '提示', { type: 'warning' })
  await deleteCategory(id)
  ElMessage.success('分类已删除')
  await loadCategories()
}

function openDetail(id) {
  router.push(`/materials/${id}`)
}

async function handleDownload(row) {
  const blobData = await downloadMaterial(row.id)
  const blob = blobData instanceof Blob ? blobData : new Blob([blobData])
  const url = URL.createObjectURL(blob)
  const filename = row.fileName || `material-${row.id}`
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  ElMessage.success('下载已开始')
}

function statusLabel(status) {
  return { ACTIVE: '已发布', PENDING: '待审核', DELETED: '已下架' }[status] || status
}

function statusType(status) {
  return { ACTIVE: 'success', PENDING: 'warning', DELETED: 'danger' }[status] || 'info'
}

function splitTags(tags) {
  return String(tags || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
}

function formatDate(value) {
  if (!value) return '--'
  return String(value).replace('T', ' ').slice(0, 16)
}

function formatCount(num) {
  const value = Number(num || 0)
  if (value >= 100000000) return `${(value / 100000000).toFixed(1)}亿`
  if (value >= 10000) return `${(value / 10000).toFixed(1)}万`
  return `${value}`
}

function getAuthorInitial(name) {
  const val = String(name || '匿').trim()
  return val.slice(0, 1).toUpperCase()
}

onMounted(async () => {
  await refreshAll()
})
</script>

<style scoped>
.admin-materials-page {
  --stroke: #dfe8f6;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.hero-panel {
  display: grid;
  grid-template-columns: 1.45fr 1fr;
  gap: 14px;
  border-radius: 16px;
  padding: 18px;
  background:
    radial-gradient(1200px 220px at -10% -30%, rgba(33, 184, 166, 0.2), transparent 70%),
    radial-gradient(900px 260px at 115% 5%, rgba(45, 107, 255, 0.2), transparent 68%),
    linear-gradient(135deg, #ffffff 0%, #f3f8ff 100%);
  border: 1px solid var(--stroke);
  animation: slide-up 0.4s ease;
}

.hero-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.eyebrow {
  font-size: 12px;
  color: #4f6f95;
  letter-spacing: 0.08em;
}

.hero-content h2 {
  margin: 0;
  font-size: 30px;
  line-height: 1.2;
  color: #16263f;
  font-family: "Source Han Sans SC", "Noto Sans SC", "Microsoft YaHei", sans-serif;
}

.subtitle {
  margin: 0;
  font-size: 14px;
  color: #5f7088;
}

.hero-actions {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.stat-card {
  border-radius: 12px;
  border: 1px solid #dfe8f8;
  background: #fff;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.stat-card span {
  font-size: 12px;
  color: #5f6f86;
}

.stat-card strong {
  font-size: 24px;
  line-height: 1.1;
  color: #1a2e4c;
}

.card-a {
  background: linear-gradient(145deg, #fbfdff 0%, #eef4ff 100%);
}

.card-b {
  background: linear-gradient(145deg, #f7fffc 0%, #e8fbf6 100%);
}

.card-c {
  background: linear-gradient(145deg, #fffdf4 0%, #fff4d8 100%);
}

.card-d {
  background: linear-gradient(145deg, #fff8f8 0%, #ffe9e9 100%);
}

.filter-card,
.table-card {
  border: 1px solid var(--stroke);
  border-radius: 14px;
  background: #fff;
  animation: slide-up 0.45s ease;
}

.filter-top {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 180px 160px;
  gap: 10px;
}

.filter-bottom {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.quick-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.quick-tag {
  cursor: pointer;
}

.filter-tips {
  font-size: 12px;
  color: #70839e;
}

.table-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.table-header h3 {
  margin: 0;
  font-size: 20px;
  color: #1b2e4d;
  font-family: "Source Han Sans SC", "Noto Sans SC", "Microsoft YaHei", sans-serif;
}

.table-header p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #6b7f9a;
}

.table-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.selected-info {
  font-size: 12px;
  color: #5f7088;
}

.materials-table {
  width: 100%;
}

.title-cell {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.title-main {
  font-size: 15px;
  font-weight: 600;
  color: #1e2e49;
}

.title-sub {
  color: #677b96;
  font-size: 12px;
  line-height: 1.6;
  max-height: 40px;
  overflow: hidden;
}

.title-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #7b8ca5;
}

.author-cell {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #2b3f60;
}

.tags-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tag-chip {
  max-width: 92px;
}

.more-tag {
  color: #8092ad;
  font-size: 12px;
}

.metric-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #4e627f;
}

.metric-cell span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.status-cell {
  display: flex;
  align-items: center;
}

.action-cell {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.table-pagination {
  margin-top: 14px;
  justify-content: center;
  display: flex;
}

.upload-alert {
  margin-bottom: 12px;
}

.category-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.category-name {
  max-width: 180px;
}

.category-parent {
  width: 160px;
}

:deep(.materials-table .el-table__header th) {
  background: linear-gradient(180deg, #f8fbff 0%, #edf4ff 100%);
  color: #2c4265;
}

:deep(.materials-table .el-table__row:hover > td) {
  background: #f5faff !important;
}

@keyframes slide-up {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 1200px) {
  .hero-panel {
    grid-template-columns: 1fr;
  }

  .filter-top {
    grid-template-columns: 1fr 1fr;
  }

  .status-select {
    grid-column: span 2;
  }
}

@media (max-width: 768px) {
  .hero-content h2 {
    font-size: 24px;
  }

  .hero-stats {
    grid-template-columns: 1fr 1fr;
  }

  .filter-top {
    grid-template-columns: 1fr;
  }

  .status-select {
    grid-column: auto;
  }

  .table-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .category-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .category-name,
  .category-parent {
    max-width: none;
    width: 100%;
  }
}
</style>
