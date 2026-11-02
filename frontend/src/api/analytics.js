import http from './http'

export const getHotness = () => http.get('/analytics/hotness')
export const getUserProfile = () => http.get('/analytics/user-profile')
export const getStats = () => http.get('/analytics/stats')
export const getDashboard = () => http.get('/analytics/dashboard', { silentError: true })
export const cleanBehaviorLogs = () => http.post('/analytics/clean')
