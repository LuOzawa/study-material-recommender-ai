import http from './http'

export const getMaterials = (params) => http.get('/materials', { params })
export const getMaterial = (id) => http.get(`/materials/${id}`)
export const createMaterial = (formData) => http.post('/materials', formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
})
export const updateMaterial = (id, data) => http.put(`/materials/${id}`, data)
export const deleteMaterial = (id) => http.delete(`/materials/${id}`)
export const rateMaterial = (id, rating) => http.post(`/materials/${id}/rate`, { rating })
export const toggleFavorite = (id) => http.post(`/materials/${id}/favorite`)
export const getFavoriteStatus = (id) => http.get(`/materials/${id}/favorite-status`)
export const getLikeState = (id) => http.get(`/materials/${id}/like`)
export const toggleLike = (id) => http.post(`/materials/${id}/like`)
export const getMaterialComments = (id) => http.get(`/materials/${id}/comments`)
export const createMaterialComment = (id, content) => http.post(`/materials/${id}/comments`, { content })
export const downloadMaterial = (id) => http.get(`/materials/${id}/download`, { responseType: 'blob' })
