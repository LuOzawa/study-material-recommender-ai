<template>
  <div>
    <div style="display:flex;justify-content:space-between;margin-bottom:16px;">
      <h2>用户管理</h2>
    </div>

    <el-table :data="users" border v-loading="loading" style="width:100%;">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column prop="role" label="角色" width="100">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'">{{ row.role }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="targetSchool" label="目标院校" />
      <el-table-column prop="studyPhase" label="备考阶段" width="110" />
      <el-table-column prop="enabled" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'">{{ row.enabled ? '正常' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="toggleEnable(row)">
            {{ row.enabled ? '禁用' : '启用' }}
          </el-button>
          <el-button size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination :current-page="page + 1" :page-size="20" :total="total"
                   layout="prev, pager, next, total"
                   style="margin-top:16px;justify-content:center;display:flex;"
                   @current-change="p => { page = p - 1; loadUsers() }" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUsers, updateUser, deleteUser } from '@/api/admin'

const users = ref([])
const loading = ref(false)
const page = ref(0)
const total = ref(0)

async function loadUsers() {
  loading.value = true
  try {
    const res = await getUsers({ page: page.value, size: 20 })
    users.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } finally {
    loading.value = false
  }
}

async function toggleEnable(row) {
  await updateUser(row.id, { enabled: !row.enabled })
  ElMessage.success('操作成功')
  loadUsers()
}

async function handleDelete(id) {
  await ElMessageBox.confirm('确定要禁用该用户吗？', '提示', { type: 'warning' })
  await deleteUser(id)
  ElMessage.success('已禁用')
  loadUsers()
}

onMounted(loadUsers)
</script>
