<template>
  <Handle type="target" :position="Position.Top" />
  <div class="node" :style="bodyStyle">
    <div class="lbl">{{ data.label }}</div>
    <div class="branch">{{ data.branch }}</div>
    <div v-if="data.version" class="version">{{ data.version }}</div>
    <div v-if="data.status !== 'idle'" class="pill" :style="pillStyle">{{ statusText }}</div>
  </div>
  <Handle type="source" :position="Position.Bottom" />
</template>

<script setup>
import {computed} from 'vue'
import {Handle, Position} from '@vue-flow/core'

const props = defineProps({ data: { type: Object, required: true } })

const palette = {
  idle:     { bg: '#f0f7ff', border: '#90caf9', text: '#1565c0', pill: '#90caf9',  pillTxt: '#fff' },
  building: { bg: '#fffde7', border: '#ffb300', text: '#e65100', pill: '#ffb300',  pillTxt: '#fff' },
  success:  { bg: '#f1f8e9', border: '#66bb6a', text: '#1b5e20', pill: '#66bb6a',  pillTxt: '#fff' },
  failed:   { bg: '#ffebee', border: '#ef5350', text: '#b71c1c', pill: '#ef5350',  pillTxt: '#fff' },
}

const c = computed(() => palette[props.data?.status] ?? palette.idle)

const borderVariant = computed(() => {
  switch (props.data?.buildService) {
    case 'LIGHTSPEED': return { borderStyle: 'double', borderWidth: '5px' }
    case 'LOCAL':      return { borderStyle: 'dashed' }
    default:           return {}
  }
})

const bodyStyle = computed(() => ({
  background: c.value.bg,
  borderColor: c.value.border,
  color: c.value.text,
  ...borderVariant.value,
}))

const pillStyle = computed(() => ({
  background: c.value.pill,
  color: c.value.pillTxt,
}))

const statusText = computed(() => ({
  building: 'Building…',
  success: '✓ Built',
  failed: '✗ Failed',
}[props.data?.status] ?? ''))
</script>

<style scoped>
.node {
  min-width: 150px;
  max-width: 220px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 2px solid;
  font-family: system-ui, sans-serif;
  transition: background 0.3s, border-color 0.3s;
  box-shadow: 0 2px 8px rgba(0,0,0,.1);
  cursor: default;
}
.lbl {
  font-size: 13px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.branch {
  font-size: 10px;
  opacity: 0.6;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.version {
  font-size: 10px;
  font-family: monospace;
  opacity: 0.75;
  margin-top: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pill {
  display: inline-block;
  font-size: 10px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 8px;
  margin-top: 6px;
}
</style>
