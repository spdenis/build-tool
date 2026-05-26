<template>
  <div id="app">
    <nav>
      <router-link to="/">Graph</router-link>
      <router-link to="/builds">Builds</router-link>
      <router-link to="/settings">Settings</router-link>
    </nav>
    <main>
      <router-view v-slot="{ Component }">
        <keep-alive include="GraphView">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>
  </div>
</template>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: system-ui, sans-serif; background: #f5f5f5; color: #222; }
#app { min-height: 100vh; display: flex; flex-direction: column; }
nav { background: #1a1a2e; padding: 12px 24px; display: flex; gap: 24px; }
nav a { color: #ccc; text-decoration: none; font-size: 14px; }
nav a.router-link-active { color: #fff; font-weight: 600; }
main { flex: 1; padding: 24px; max-width: 1200px; margin: 0 auto; width: 100%; }
</style>
