<template>
  <div>
    <el-card style="margin-bottom:20px;">
      <el-row :gutter="16" align="middle">
        <el-col :span="14">
          <el-input v-model="keyword" placeholder="搜索资料标题、标签、简介..." clearable
                    prefix-icon="Search" @keyup.enter="search" @clear="search" />
        </el-col>
        <el-col :span="6">
          <el-select v-model="categoryId" placeholder="分类筛选" clearable style="width:100%;" @change="search">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-col>
        <el-col :span="4">
          <el-button type="primary" @click="search">搜索</el-button>
        </el-col>
      </el-row>
    </el-card>

    <div v-loading="loading" style="min-height:300px;">
      <el-empty v-if="!loading && materials.length === 0" description="没有找到相关资料" />
      <el-row v-else :gutter="20">
        <el-col v-for="m in materials" :key="m.id" :xs="24" :sm="12" :md="8" :lg="6" style="margin-bottom:20px;">
          <material-card :material="m" />
        </el-col>
      </el-row>
    </div>

    <el-pagination
      v-if="total > 0"
      :current-page="page + 1"
      :page-size="12"
      :total="total"
      layout="prev, pager, next, total"
      style="margin-top:20px;justify-content:center;display:flex;"
      @current-change="handlePageChange"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { getMaterials } from '@/api/materials'
import MaterialCard from '@/components/MaterialCard.vue'
import http from '@/api/http'

const keyword = ref('')
const categoryId = ref(null)
const materials = ref([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const categories = ref([])

async function loadCategories() {
  try {
    const res = await http.get('/categories')
    categories.value = res.data || []
  } catch {}
}

async function loadMaterials() {
  loading.value = true
  try {
    const res = await getMaterials({
      keyword: keyword.value || undefined,
      categoryId: categoryId.value || undefined,
      page: page.value,
      size: 12
    })
    materials.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0
  loadMaterials()
}

function handlePageChange(p) {
  page.value = p - 1
  loadMaterials()
}

onMounted(() => {
  loadCategories()
  loadMaterials()
})
</script>
