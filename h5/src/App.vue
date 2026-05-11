<script setup lang="ts">
import { ref } from 'vue'
import { NavBar, Button, Cell, CellGroup, showToast } from 'vant'

const bridgeResult = ref<string>('点击按钮测试 JSBridge')

function testPing() {
  if (window.AppBridge) {
    const result = window.AppBridge.ping('Hello from H5!')
    bridgeResult.value = `收到响应: ${JSON.stringify(result)}`
    showToast(result.message)
  } else {
    bridgeResult.value = 'AppBridge 未定义，请在 App 内运行'
    showToast('AppBridge 未定义')
  }
}
</script>

<template>
  <div class="app">
    <NavBar title="Image AI" safe-area-inset-top />

    <div class="content">
      <CellGroup inset title="JSBridge 测试">
        <Cell title="状态" :value="bridgeResult" />
      </CellGroup>

      <div class="button-group">
        <Button type="primary" block @click="testPing">
          测试 Ping
        </Button>
      </div>

      <CellGroup inset title="功能列表">
        <Cell title="标签管理" is-link />
        <Cell title="筛选设置" is-link />
        <Cell title="帮助说明" is-link />
      </CellGroup>
    </div>
  </div>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  background-color: #f7f8fa;
}

.app {
  min-height: 100vh;
}

.content {
  padding: 16px;
}

.button-group {
  margin: 16px 0;
}
</style>
