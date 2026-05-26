<template>
  <div>
    <h2>Settings</h2>
    <div v-if="saved" class="success-banner">Settings saved.</div>

    <div class="card">
      <h3>General</h3>
      <div class="form-row"><label>Clone Directory</label><input v-model="form.cloneDir" /></div>
      <div class="form-row"><label>Integration Branch</label><input v-model="form.integrationBranch" /></div>
      <div class="form-row"><label>Default Source Branch</label><input v-model="form.defaultSourceBranch" /></div>
      <div class="form-row">
        <label>Build Mode</label>
        <select v-model="form.buildMode">
          <option value="SNAPSHOT">SNAPSHOT</option>
          <option value="RELEASE">RELEASE</option>
        </select>
      </div>
      <div class="form-row">
        <label>Build Service</label>
        <select v-model="form.buildService">
          <option value="LOCAL">LOCAL</option>
          <option value="TEAMCITY">TEAMCITY</option>
          <option value="LIGHTSPEED">LIGHTSPEED</option>
          <option value="DUMMY">DUMMY</option>
        </select>
      </div>
      <div class="form-row"><label><input type="checkbox" v-model="form.buildEnabled" /> Build enabled</label></div>
      <div class="form-row"><label><input type="checkbox" v-model="form.dryMode" /> Dry mode</label></div>
      <div class="form-row"><label><input type="checkbox" v-model="form.skipGit" /> Skip git operations</label></div>
    </div>

    <div class="card">
      <h3>Git / GitHub</h3>
      <div class="form-row"><label>GitHub Token</label><input v-model="form.githubToken" type="password" placeholder="leave blank to keep existing" /></div>
      <div class="form-row"><label><input type="checkbox" v-model="form.gitAuthTokenInUrl" /> Embed token in clone URL</label></div>
    </div>

    <div class="card">
      <h3>TeamCity</h3>
      <div class="form-row"><label>URL</label><input v-model="form.teamcityUrl" /></div>
      <div class="form-row"><label>Token</label><input v-model="form.teamcityToken" type="password" placeholder="leave blank to keep existing" /></div>
      <div class="form-row"><label>Poll interval (ms)</label><input v-model.number="form.teamcityPollIntervalMs" type="number" /></div>
    </div>

    <div class="card">
      <h3>Lightspeed</h3>
      <div class="form-row"><label>Snapshots URL</label><input v-model="form.lightspeedSnapshotsUrl" /></div>
      <div class="form-row"><label>Releases URL</label><input v-model="form.lightspeedReleasesUrl" /></div>
      <div class="form-row"><label>Username</label><input v-model="form.lightspeedUsername" /></div>
      <div class="form-row"><label>Password</label><input v-model="form.lightspeedPassword" type="password" placeholder="leave blank to keep existing" /></div>
    </div>

    <div class="form-actions">
      <button class="primary" @click="save" :disabled="saving">{{ saving ? 'Saving...' : 'Save Settings' }}</button>
    </div>
    <p v-if="saveError" class="error">{{ saveError }}</p>
  </div>
</template>

<script setup>
import {onMounted, ref} from 'vue'
import {api} from '../api'

const form = ref({})
const saving = ref(false)
const saved = ref(false)
const saveError = ref(null)

onMounted(async () => {
  const res = await api.getSettings()
  form.value = { ...res.data }
})

async function save() {
  saving.value = true
  saved.value = false
  saveError.value = null
  try {
    await api.saveSettings(form.value)
    saved.value = true
    setTimeout(() => { saved.value = false }, 3000)
  } catch (e) {
    saveError.value = e.response?.data?.message ?? e.message
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
h2, h3 { margin-bottom: 12px; }
.card { background: #fff; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.form-row { margin-bottom: 10px; display: flex; flex-direction: column; gap: 4px; }
label { font-size: 13px; font-weight: 500; }
input:not([type=checkbox]), select { padding: 6px 10px; border: 1px solid #ddd; border-radius: 4px; width: 400px; }
.form-actions { margin-top: 8px; }
button.primary { background: #1a1a2e; color: #fff; border: none; padding: 8px 20px; border-radius: 4px; cursor: pointer; }
button:disabled { opacity: 0.5; }
.error { color: #c00; font-size: 13px; margin-top: 8px; }
.success-banner { background: #d4edda; color: #155724; padding: 10px 16px; border-radius: 6px; margin-bottom: 12px; }
</style>
