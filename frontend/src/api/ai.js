import http from './http'

export const chat = (question) => http.post('/ai/chat', { question })
export const summarize = (materialId) => http.post(`/ai/summarize/${materialId}`)
export const getSuggestions = () => http.post('/ai/suggestions')
export const semanticSearch = (q) => http.get('/ai/search', { params: { q } })
export const getChatHistory = () => http.get('/ai/history')
