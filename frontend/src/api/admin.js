import http from './http'

export const getUsers = (params) => http.get('/admin/users', { params })
export const updateUser = (id, data) => http.put(`/admin/users/${id}`, data)
export const deleteUser = (id) => http.delete(`/admin/users/${id}`)

export const getAdminMaterials = (params) => http.get('/admin/materials', { params })
export const reviewMaterial = (id, status) => http.put(`/admin/materials/${id}/review`, { status })
export const batchReviewMaterials = (ids, status) => http.put('/admin/materials/review/batch', { ids, status })
export const batchImportMaterials = (materials) => http.post('/admin/materials/batch', materials)
export const getAdminCategories = () => http.get('/admin/categories')
export const createCategory = (data) => http.post('/admin/categories', data)
export const updateCategory = (id, data) => http.put(`/admin/categories/${id}`, data)
export const deleteCategory = (id) => http.delete(`/admin/categories/${id}`)
export const getAdminLogs = (params) => http.get('/admin/logs', { params })
export const getBackupSnapshot = () => http.get('/admin/backup')
export const exportHadoopDatasets = () => http.post('/admin/hadoop/export')
export const getLatestHadoopExport = () => http.get('/admin/hadoop/export/latest', { silentError: true })
