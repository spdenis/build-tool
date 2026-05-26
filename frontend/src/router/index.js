import {createRouter, createWebHistory} from 'vue-router'
import GraphView from '../views/GraphView.vue'
import BuildListView from '../views/BuildListView.vue'
import SessionView from '../views/SessionView.vue'
import SettingsView from '../views/SettingsView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: GraphView },
    { path: '/builds', component: BuildListView },
    { path: '/builds/:sessionId', component: SessionView },
    { path: '/settings', component: SettingsView }
  ]
})
