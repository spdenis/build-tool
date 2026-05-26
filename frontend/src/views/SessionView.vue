<template>
  <div>
    <div class="header">
      <button @click="router.back()">← Back</button>
      <h2>Build {{ sessionId.slice(0, 8) }}</h2>
      <span v-if="session" :class="['badge', session.status?.toLowerCase()]">{{ session.status }}</span>
      <button v-if="session?.status === 'RUNNING'" @click="cancel" class="cancel">Cancel</button>
    </div>

    <div v-if="session?.errorMessage" class="error-banner">{{ session.errorMessage }}</div>

    <div class="panels">
      <div class="repos-panel">
        <h3>Repositories</h3>
        <div v-if="Object.keys(graphStore.repoResults).length === 0" class="empty">
          Waiting for build to start…
        </div>
        <div v-for="(s, name) in graphStore.repoResults" :key="name" :class="['repo-card', s.status?.toLowerCase()]">
          <span class="repo-name">{{ name }}</span>
          <span class="repo-status">{{ s.status }}</span>
          <span v-if="s.durationMs" class="duration">{{ (s.durationMs / 1000).toFixed(1) }}s</span>
          <span v-if="s.error" class="repo-error">{{ s.error }}</span>
        </div>
      </div>

      <div class="log-panel">
        <h3>Log</h3>
        <div class="log-console" ref="logEl">
          <div v-for="(line, i) in graphStore.logLines" :key="i" class="log-line">{{ line }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import {computed, nextTick, onMounted, onUnmounted, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {api} from '../api'
import {useSessionStore} from '../stores/sessionStore'
import {useGraphStore} from '../stores/graphStore'

const route = useRoute()
const router = useRouter()
const sessionStore = useSessionStore()
const graphStore = useGraphStore()
const sessionId = route.params.sessionId
const session = computed(() => sessionStore.currentSession)
const logEl = ref(null)

onMounted(() => {
  sessionStore.connect(sessionId)
  graphStore.attachToSession(sessionId)
})

onUnmounted(() => sessionStore.disconnect())

watch(() => graphStore.buildPhase, (phase) => {
  if (phase === 'SUCCESS' || phase === 'FAILED') {
    sessionStore.connect(sessionId)
  }
})

watch(() => graphStore.logLines.length, async () => {
  await nextTick()
  if (logEl.value) logEl.value.scrollTop = logEl.value.scrollHeight
})

async function cancel() {
  await api.cancelBuild(sessionId)
}
</script>

<style scoped>
.header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
h2, h3 { margin-bottom: 12px; }
button { padding: 6px 12px; border: 1px solid #ccc; border-radius: 4px; cursor: pointer; background: #fff; }
button.cancel { background: #c00; color: #fff; border-color: #c00; }
.badge { font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 10px; text-transform: uppercase; }
.badge.pending { background: #fff3cd; color: #856404; }
.badge.running { background: #cce5ff; color: #004085; }
.badge.success { background: #d4edda; color: #155724; }
.badge.failed { background: #f8d7da; color: #721c24; }
.badge.cancelled { background: #e2e3e5; color: #383d41; }
.error-banner { background: #f8d7da; color: #721c24; padding: 12px; border-radius: 8px; margin-bottom: 16px; }
.panels { display: grid; grid-template-columns: 340px 1fr; gap: 16px; }
.repos-panel, .log-panel { background: #fff; border-radius: 8px; padding: 16px; }
.empty { color: #888; font-size: 14px; }
.repo-card { display: flex; align-items: center; gap: 8px; padding: 8px; border-radius: 6px; margin-bottom: 6px; font-size: 13px; }
.repo-card.waiting { background: #f8f9fa; }
.repo-card.building { background: #cce5ff; }
.repo-card.success { background: #d4edda; }
.repo-card.failed { background: #f8d7da; }
.repo-name { flex: 1; font-weight: 500; overflow: hidden; text-overflow: ellipsis; }
.repo-status { font-size: 11px; text-transform: uppercase; font-weight: 600; color: #555; }
.duration { font-size: 11px; color: #888; }
.repo-error { font-size: 11px; color: #c00; flex: 1; overflow: hidden; text-overflow: ellipsis; }
.log-console { background: #1a1a1a; color: #ddd; font-family: monospace; font-size: 12px; padding: 12px; border-radius: 4px; height: 500px; overflow-y: auto; }
.log-line { line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
</style>
