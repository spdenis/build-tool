import {defineStore} from 'pinia'
import {ref} from 'vue'

let _evtSrc = null  // module-level — survives component navigation

export const useGraphStore = defineStore('graph', () => {
  const repos = ref([])
  const graph = ref(null)
  const savedPositions = ref({})
  const reposFileName = ref('')
  const reposFileContent = ref('')

  const nodeStatuses = ref({})   // nodeId → 'idle'|'building'|'success'|'failed'
  const repoResults = ref({})    // nodeId → { status, durationMs, error, layerIndex }
  const buildPhase = ref(null)   // null | 'RUNNING' | 'SUCCESS' | 'FAILED'
  const sessionId = ref(null)
  const buildRunning = ref(false)
  const logLines = ref([])

  function setGraph(repoList, graphData) {
    repos.value = repoList
    graph.value = graphData
  }

  function savePosition(nodeId, pos) {
    savedPositions.value[nodeId] = { x: pos.x, y: pos.y }
  }

  function clearPositions() {
    savedPositions.value = {}
  }

  function startBuildSession(sid) {
    sessionId.value = sid
    buildPhase.value = 'RUNNING'
    buildRunning.value = true
    logLines.value = []
    nodeStatuses.value = {}
    repoResults.value = {}
    connectSse(sid)
  }

  function finishBuildSession(success) {
    buildPhase.value = success ? 'SUCCESS' : 'FAILED'
    buildRunning.value = false
  }

  function connectSse(sid) {
    _evtSrc?.close()
    _evtSrc = new EventSource(`/api/sessions/${sid}/events`)
    _evtSrc.onmessage = e => handleSseEvent(JSON.parse(e.data))
    _evtSrc.onerror = () => {
      _evtSrc?.close()
      if (buildRunning.value) finishBuildSession(false)
    }
  }

  function attachToSession(sid) {
    if (sessionId.value === sid) return
    if (buildRunning.value) return
    sessionId.value = sid
    connectSse(sid)
  }

  function handleSseEvent(evt) {
    if (evt.type === 'LOG') {
      logLines.value.push(evt.message)
    } else if (evt.type === 'REPO_STARTED') {
      nodeStatuses.value[evt.repo] = 'building'
      repoResults.value[evt.repo] = { status: 'BUILDING', layerIndex: evt.layerIndex }
    } else if (evt.type === 'REPO_FINISHED') {
      const status = evt.success ? 'success' : 'failed'
      nodeStatuses.value[evt.repo] = status
      repoResults.value[evt.repo] = {
        ...repoResults.value[evt.repo],
        status: evt.success ? 'SUCCESS' : 'FAILED',
        durationMs: evt.durationMs,
        error: evt.errorMessage,
      }
    } else if (evt.type === 'BUILD_FINISHED') {
      finishBuildSession(evt.success)
      _evtSrc?.close()
    }
  }

  return {
    repos, graph,
    savedPositions, reposFileName, reposFileContent,
    nodeStatuses, repoResults, buildPhase, sessionId, buildRunning, logLines,
    setGraph, savePosition, clearPositions,
    startBuildSession, finishBuildSession, attachToSession,
  }
})
