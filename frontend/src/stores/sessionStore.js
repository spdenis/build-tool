import {defineStore} from 'pinia'
import {ref} from 'vue'
import {api} from '../api'

export const useSessionStore = defineStore('session', () => {
  const currentSession = ref(null)

  function connect(sessionId) {
    api.getBuild(sessionId).then(res => {
      currentSession.value = res.data
    })
  }

  function disconnect() {}

  return { currentSession, connect, disconnect }
})
