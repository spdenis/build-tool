<template>
  <div>
    <h2>Builds</h2>

    <div class="card" v-if="graphStore.repos.length">
      <h3>Start New Build</h3>
      <div class="form-row">
        <label>Build Mode</label>
        <select v-model="form.buildMode">
          <option value="SNAPSHOT">SNAPSHOT</option>
          <option value="RELEASE">RELEASE</option>
        </select>
      </div>
      <div class="form-row">
        <label>Integration Branch</label>
        <input v-model="form.integrationBranch" placeholder="leave blank to use settings default" />
      </div>
      <div class="form-row">
        <label><input type="checkbox" v-model="form.dryMode" /> Dry mode (skip push/remote)</label>
      </div>
      <div class="form-row">
        <label><input type="checkbox" v-model="form.buildEnabled" /> Enable build (uncheck to parse only)</label>
      </div>
      <button class="primary" @click="start" :disabled="starting">
        {{ starting ? 'Starting...' : 'Start Build' }}
      </button>
      <p v-if="startError" class="error">{{ startError }}</p>
    </div>

    <div class="card" v-else>
      <p>No repos loaded. Go to <router-link to="/">Graph</router-link> to load a repos.json first.</p>
    </div>

    <h3 style="margin: 16px 0 8px">Recent Builds</h3>
    <div v-if="builds.length === 0" class="empty">No builds yet.</div>
    <div v-for="b in builds" :key="b.sessionId" class="build-row" @click="router.push(`/builds/${b.sessionId}`)">
      <span class="session-id">{{ b.sessionId.slice(0, 8) }}</span>
      <span :class="['badge', b.status.toLowerCase()]">{{ b.status }}</span>
      <span class="started">{{ formatTime(b.startedAt) }}</span>
    </div>
  </div>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {api} from '../api'
import {useGraphStore} from '../stores/graphStore'

const router = useRouter()
const graphStore = useGraphStore()
const builds = ref([])
const starting = ref(false)
const startError = ref(null)
const form = ref({ buildMode: 'SNAPSHOT', integrationBranch: '', dryMode: false, buildEnabled: true })

onMounted(async () => {
  const res = await api.listBuilds()
  builds.value = res.data
})

async function start() {
  starting.value = true
  startError.value = null
  try {
    const payload = {
      repos: graphStore.repos,
      buildMode: form.value.buildMode,
      integrationBranch: form.value.integrationBranch || null,
      dryMode: form.value.dryMode,
      buildEnabled: form.value.buildEnabled
    }
    const res = await api.startBuild(payload)
    router.push(`/builds/${res.data.sessionId}`)
  } catch (e) {
    startError.value = e.response?.data?.message ?? e.message
  } finally {
    starting.value = false
  }
}

function formatTime(iso) {
  if (!iso) return ''
  return new Date(iso).toLocaleString()
}
</script>

<style scoped>
h2, h3 { margin-bottom: 12px; }
.card { background: #fff; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.form-row { margin-bottom: 12px; display: flex; flex-direction: column; gap: 4px; }
label { font-size: 13px; font-weight: 500; }
input[type=text], input:not([type=checkbox]), select { padding: 6px 10px; border: 1px solid #ddd; border-radius: 4px; width: 320px; }
button.primary { background: #1a1a2e; color: #fff; border: none; padding: 8px 20px; border-radius: 4px; cursor: pointer; }
button:disabled { opacity: 0.5; }
.error { color: #c00; font-size: 13px; margin-top: 8px; }
.empty { color: #888; font-size: 14px; }
.build-row { background: #fff; border-radius: 8px; padding: 12px 16px; margin-bottom: 8px; display: flex; align-items: center; gap: 16px; cursor: pointer; }
.build-row:hover { background: #f0f0f0; }
.session-id { font-family: monospace; font-size: 12px; color: #666; }
.started { font-size: 13px; color: #888; margin-left: auto; }
.badge { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 10px; text-transform: uppercase; }
.badge.pending { background: #fff3cd; color: #856404; }
.badge.running { background: #cce5ff; color: #004085; }
.badge.success { background: #d4edda; color: #155724; }
.badge.failed { background: #f8d7da; color: #721c24; }
.badge.cancelled { background: #e2e3e5; color: #383d41; }
</style>
