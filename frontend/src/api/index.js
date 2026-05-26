import axios from 'axios'

const http = axios.create({ baseURL: '' })

export const api = {
  // Graph
  previewGraph: (repos) => http.post('/api/graph', { repos }),

  // Builds
  startBuild: (payload) => http.post('/api/builds', payload),
  listBuilds: () => http.get('/api/builds'),
  getBuild: (id) => http.get(`/api/builds/${id}`),
  cancelBuild: (id) => http.delete(`/api/builds/${id}`),

  // Settings
  getSettings: () => http.get('/api/settings'),
  saveSettings: (data) => http.put('/api/settings', data),
}
