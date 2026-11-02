import http from './http'

export const getProfile = () => http.get('/user/profile')
export const updateProfile = (data) => http.put('/user/profile', data)
export const getFavorites = () => http.get('/user/favorites')
export const toggleFavorite = (materialId) => http.post(`/user/favorites/${materialId}`)
export const getHistory = () => http.get('/user/history')
export const clearHistory = () => http.delete('/user/history')
export const deleteHistoryItem = (id) => http.delete(`/user/history/${id}`)
export const getRatings = () => http.get('/user/ratings')
export const deleteRating = (materialId) => http.delete(`/user/ratings/${materialId}`)
export const getPreferences = () => http.get('/user/preferences')
export const updatePreferences = (data) => http.put('/user/preferences', data)
