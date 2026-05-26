<template>
  <div class="gv">

    <!-- ── Toolbar ─────────────────────────────────────────────── -->
    <div class="toolbar">
      <div class="tb-left">
        <label class="file-btn">
          <input type="file" accept=".json" @change="onFileSelected" class="file-input-hidden" />
          {{ graphStore.reposFileName ? graphStore.reposFileName : 'Upload repos.json' }}
        </label>
        <button @click="parse" :disabled="loading || !graphStore.reposFileContent" class="btn">
          {{ loading ? 'Parsing…' : 'Parse' }}
        </button>
        <span v-if="parseError" class="err">{{ parseError }}</span>
      </div>

      <div v-if="graphStore.graph" class="tb-right">
        <div class="build-opts">
          <select v-model="buildForm.buildMode">
            <option>SNAPSHOT</option>
            <option>RELEASE</option>
          </select>
          <input
            v-model="buildForm.integrationBranch"
            placeholder="integration branch (optional)"
            class="branch-input"
          />
          <label class="chk"><input type="checkbox" v-model="buildForm.dryMode" /> Dry</label>
          <label class="chk"><input type="checkbox" v-model="buildForm.buildEnabled" /> Build</label>
        </div>
        <button
          @click="startBuild"
          :disabled="graphStore.buildRunning"
          :class="['btn', 'btn-primary', { running: graphStore.buildRunning }]"
        >
          {{ graphStore.buildRunning ? '◼ Running' : '▶ Start Build' }}
        </button>
        <span v-if="graphStore.buildPhase" :class="['phase-badge', graphStore.buildPhase.toLowerCase()]">
          {{ graphStore.buildPhase }}
        </span>
        <router-link v-if="graphStore.sessionId" :to="`/builds/${graphStore.sessionId}`" class="details-link">
          Details →
        </router-link>
      </div>
    </div>

    <!-- ── Graph canvas ─────────────────────────────────────────── -->
    <div class="canvas-wrap">
      <VueFlow
        v-if="graphStore.graph"
        :id="FLOW_ID"
        :node-types="nodeTypes"
        :edge-types="edgeTypes"
        :nodes-connectable="false"
        :elements-selectable="false"
        @pane-ready="onPaneReady"
        @node-drag-stop="onNodeDragStop"
        class="flow"
      />
      <div v-else class="empty-canvas">
        <div class="empty-inner">
          <div class="empty-icon">⬡</div>
          <p>Upload a <strong>repos.json</strong> file and click <strong>Parse</strong><br>to analyse dependencies and render the graph.</p>
        </div>
      </div>
    </div>

    <!-- ── Log tail ─────────────────────────────────────────────── -->
    <div v-if="graphStore.logLines.length" class="log-panel">
      <div class="log-header" @click="logOpen = !logOpen">
        Build log ({{ graphStore.logLines.length }}) <span>{{ logOpen ? '▾' : '▴' }}</span>
      </div>
      <div v-if="logOpen" class="log-body" ref="logEl">
        <div v-for="(l, i) in graphStore.logLines" :key="i" class="ll">{{ l }}</div>
      </div>
    </div>

  </div>
</template>

<script>
export default { name: 'GraphView' }
</script>

<script setup>
import { ref, reactive, markRaw, nextTick, watch, watchEffect } from 'vue'
import { VueFlow, useVueFlow, MarkerType } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { useGraphStore } from '../stores/graphStore'
import { api } from '../api'
import RepoNode from '../components/RepoNode.vue'
import DependencyEdge from '../components/DependencyEdge.vue'

const FLOW_ID = 'dep-graph'
const flow = useVueFlow({ id: FLOW_ID })

const graphStore = useGraphStore()
const loading = ref(false)
const parseError = ref(null)

const buildForm = reactive({
  buildMode: 'SNAPSHOT',
  integrationBranch: '',
  dryMode: false,
  buildEnabled: true,
})
const logOpen = ref(true)
const logEl = ref(null)

const nodeTypes = { repo: markRaw(RepoNode) }
const edgeTypes = { dependency: markRaw(DependencyEdge) }
const flowReady = ref(false)  // set once when VueFlow fires pane-ready; persists while kept alive

// ── Reactive graph sync ─────────────────────────────────────────────
// watchEffect re-runs whenever graph, nodeStatuses, or savedPositions change.
// GraphView is kept alive by <KeepAlive> so this effect never tears down.
watchEffect(() => {
  if (!flowReady.value || !graphStore.graph) return

  const graph = graphStore.graph
  const NODE_W = 220, NODE_H = 110, LAYER_GAP = 100, NODE_GAP = 40

  const nodeLayer = {}
  graph.layers.forEach((layer, li) => layer.forEach(id => (nodeLayer[id] = li)))
  const maxLayerSize = Math.max(...graph.layers.map(l => l.length), 1)
  const maxLayerW = maxLayerSize * (NODE_W + NODE_GAP) - NODE_GAP

  const nodes = graph.nodes.map(n => {
    const li = nodeLayer[n.id] ?? 0
    const layerNodes = graph.layers[li] ?? [n.id]
    const posI = layerNodes.indexOf(n.id)
    const layerW = layerNodes.length * (NODE_W + NODE_GAP) - NODE_GAP
    const defaultPos = {
      x: (maxLayerW - layerW) / 2 + posI * (NODE_W + NODE_GAP),
      y: li * (NODE_H + LAYER_GAP),
    }
    return {
      id: n.id,
      type: 'repo',
      data: { label: n.id, branch: n.name, version: n.version ?? null, buildService: n.buildService ?? null, status: graphStore.nodeStatuses[n.id] ?? 'idle' },
      position: graphStore.savedPositions[n.id] ?? defaultPos,
    }
  })

  const edges = graph.edges.map(e => ({
    id: `e-${e.from}-${e.to}`,
    source: e.to,
    target: e.from,
    type: 'dependency',
    markerEnd: { type: MarkerType.ArrowClosed, color: '#90a4ae' },
    data: { deps: e.dependencyVersions ?? [] },
  }))

  flow.setNodes(nodes)
  flow.setEdges(edges)
})

// FitView when graph first loads or is re-parsed (not on every status change)
watch(() => graphStore.graph, (newGraph) => {
  if (newGraph && flowReady.value) {
    nextTick(() => flow.fitView({ padding: 0.2 }))
  }
})

watch(() => graphStore.logLines.length, async () => {
  await nextTick()
  if (logEl.value) logEl.value.scrollTop = logEl.value.scrollHeight
})

// ── File upload ────────────────────────────────────────────────────

function onFileSelected(event) {
  const file = event.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = e => {
    graphStore.reposFileContent = e.target.result
    graphStore.reposFileName = file.name
    parseError.value = null
  }
  reader.readAsText(file)
}

// ── Parse ──────────────────────────────────────────────────────────

async function parse() {
  parseError.value = null
  let repos
  try {
    repos = JSON.parse(graphStore.reposFileContent)
  } catch {
    parseError.value = 'Invalid JSON'
    return
  }
  loading.value = true
  graphStore.clearPositions()
  try {
    const { data } = await api.previewGraph(repos)
    graphStore.setGraph(repos, data)
    // watchEffect handles the VueFlow update automatically
  } catch (e) {
    parseError.value = e.response?.data?.message ?? e.message
  } finally {
    loading.value = false
  }
}

// ── Vue Flow ───────────────────────────────────────────────────────

function onPaneReady() {
  flowReady.value = true  // fires watchEffect; stays true for the lifetime of the kept-alive component
  nextTick(() => flow.fitView({ padding: 0.2 }))
}

function onNodeDragStop({ nodes }) {
  nodes.forEach(n => graphStore.savePosition(n.id, n.position))
}

// ── Build ──────────────────────────────────────────────────────────

async function startBuild() {
  parseError.value = null
  try {
    const { data } = await api.startBuild({
      repos: graphStore.repos,
      buildMode: buildForm.buildMode,
      integrationBranch: buildForm.integrationBranch || null,
      dryMode: buildForm.dryMode,
      buildEnabled: buildForm.buildEnabled,
    })
    graphStore.startBuildSession(data.sessionId)  // clears nodeStatuses → watchEffect resets nodes to idle
  } catch (e) {
    graphStore.finishBuildSession(false)
    parseError.value = e.response?.data?.message ?? e.message
  }
}
</script>

<style scoped>
.gv {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* Toolbar */
.toolbar {
  background: #fff;
  border-radius: 8px;
  padding: 12px 16px;
  display: flex;
  gap: 20px;
  align-items: center;
  flex-wrap: wrap;
}
.tb-left {
  display: flex;
  gap: 8px;
  align-items: center;
}
.file-input-hidden {
  display: none;
}
.file-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px dashed #aaa;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  background: #fafafa;
  white-space: nowrap;
  color: #444;
  transition: border-color 0.2s, background 0.2s;
}
.file-btn:hover { border-color: #666; background: #f0f0f0; }
.tb-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.build-opts {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.branch-input {
  padding: 6px 10px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
  width: 200px;
}
select {
  padding: 6px 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
}
.chk {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
}
.btn {
  padding: 7px 14px;
  border: 1px solid #ccc;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  background: #fff;
  white-space: nowrap;
}
.btn-primary { background: #1a1a2e; color: #fff; border-color: #1a1a2e; }
.btn-primary.running { background: #555; border-color: #555; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.err { color: #c00; font-size: 12px; }
.phase-badge {
  font-size: 11px;
  font-weight: 700;
  padding: 3px 9px;
  border-radius: 10px;
  text-transform: uppercase;
  white-space: nowrap;
}
.phase-badge.running { background: #cce5ff; color: #004085; }
.phase-badge.success { background: #d4edda; color: #155724; }
.phase-badge.failed  { background: #f8d7da; color: #721c24; }
.details-link { font-size: 12px; color: #1a1a2e; text-decoration: none; }
.details-link:hover { text-decoration: underline; }

/* Raise edge-labels portal above nodes so tooltips render on top */
:deep(.vue-flow__edge-labels) {
  z-index: 5;
}

/* Graph canvas */
.canvas-wrap {
  position: relative;
  height: calc(100vh - 230px);
  min-height: 400px;
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.flow { width: 100%; height: 100%; }
.empty-canvas {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.empty-inner { text-align: center; color: #999; }
.empty-icon { font-size: 48px; margin-bottom: 12px; opacity: 0.3; }
.empty-inner p { font-size: 14px; line-height: 1.7; }
.empty-inner strong { color: #555; }

/* Log */
.log-panel { background: #1a1a1a; border-radius: 8px; overflow: hidden; }
.log-header {
  padding: 8px 14px;
  color: #bbb;
  font-size: 12px;
  cursor: pointer;
  user-select: none;
  display: flex;
  justify-content: space-between;
}
.log-header:hover { background: #252525; }
.log-body { max-height: 200px; overflow-y: auto; padding: 6px 14px 10px; }
.ll {
  font-family: monospace;
  font-size: 11px;
  color: #ccc;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
