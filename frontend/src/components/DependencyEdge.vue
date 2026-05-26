<template>
  <BaseEdge :id="id" :path="edgePath" :marker-end="markerEnd" :style="edgeStyle" />
  <EdgeLabelRenderer v-if="hasDeps">
    <div :style="labelWrapStyle" class="dep-label">
      <div
        class="dep-chip"
        @mouseenter="visible = true"
        @mouseleave="visible = false"
      >{{ deps.length }}</div>
      <div v-show="visible" class="dep-tooltip">
        <div v-for="d in deps" :key="d" class="dep-item">{{ shortLabel(d) }}</div>
      </div>
    </div>
  </EdgeLabelRenderer>
</template>

<script setup>
import {computed, ref} from 'vue'
import {BaseEdge, EdgeLabelRenderer, getSmoothStepPath} from '@vue-flow/core'

const props = defineProps({
  id: String,
  sourceX: Number,
  sourceY: Number,
  targetX: Number,
  targetY: Number,
  sourcePosition: String,
  targetPosition: String,
  data: Object,
  markerEnd: String,
})

const visible = ref(false)
const deps = computed(() => props.data?.deps ?? [])
const hasDeps = computed(() => deps.value.length > 0)

const pathData = computed(() =>
  getSmoothStepPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition,
  })
)

const edgePath = computed(() => pathData.value[0])
const labelX = computed(() => pathData.value[1])
const labelY = computed(() => pathData.value[2])

const edgeStyle = { stroke: '#90a4ae', strokeWidth: 2 }

const labelWrapStyle = computed(() => ({
  position: 'absolute',
  transform: `translate(-50%, -50%) translate(${labelX.value}px,${labelY.value}px)`,
  pointerEvents: 'all',
}))

// Show "artifactId:version" — strip the leading "groupId:" for readability
function shortLabel(gav) {
  const parts = gav.split(':')
  return parts.length === 3 ? `${parts[1]}:${parts[2]}` : gav
}
</script>

<style scoped>
.dep-label {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
}
.dep-chip {
  background: #fff;
  border: 1px solid #90a4ae;
  border-radius: 10px;
  padding: 0 6px;
  font-size: 10px;
  font-weight: 600;
  color: #607d8b;
  line-height: 16px;
  cursor: default;
  user-select: none;
}
.dep-chip:hover {
  background: #eceff1;
}
.dep-tooltip {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  background: #1a1a2e;
  color: #e0e0e0;
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 11px;
  font-family: monospace;
  white-space: nowrap;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35);
  pointer-events: none;
  z-index: 999;
}
.dep-item + .dep-item {
  margin-top: 2px;
}
</style>
