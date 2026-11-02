import http from './http'

export const getRecommendations = () => http.get('/recommendations')
export const getPublicRecommendations = () => http.get('/recommendations/public')
export const refreshRecommendations = () => http.post('/recommendations/refresh')

// 推荐反馈（有用 / 没用）
export const submitFeedback = (materialId, feedback) =>
  http.post(`/recommendations/${materialId}/feedback`, { feedback })
export const cancelFeedback = (materialId) =>
  http.delete(`/recommendations/${materialId}/feedback`)
export const getMyFeedback = () => http.get('/recommendations/feedback')
