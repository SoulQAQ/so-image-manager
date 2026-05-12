<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NavBar, Cell, CellGroup, Button, showToast } from 'vant'

const router = useRouter()
const stats = ref({
  totalImages: 0,
  pendingCount: 0,
  doneCount: 0,
  failedCount: 0
})

function getStatistics() {
  if (window.AppBridge) {
    try {
      const result = JSON.parse(window.AppBridge.getStatistics())
      if (result.code === 0) {
        stats.value = result.data
      }
    } catch (e) {
      console.error('Failed to get statistics', e)
    }
  }
}

function testPing() {
  if (window.AppBridge) {
    const resultStr = window.AppBridge.ping('Hello from H5!')
    try {
      const result = JSON.parse(resultStr)
      showToast(result.message)
    } catch (e) {
      showToast(resultStr)
    }
  } else {
    showToast('AppBridge not available')
  }
}

onMounted(() => {
  getStatistics()
})
</script>

<template>
  <div class="home-view">
    <NavBar title="Image AI" safe-area-inset-top />

    <div class="content">
      <CellGroup inset title="统计信息">
        <Cell title="图片总数" :value="stats.totalImages" />
        <Cell title="待处理" :value="stats.pendingCount" />
        <Cell title="已完成" :value="stats.doneCount" />
        <Cell title="失败" :value="stats.failedCount" />
      </CellGroup>

      <div class="button-group">
        <Button type="primary" block @click="router.push('/search')">
          搜索图片
        </Button>
        <Button type="default" block @click="router.push('/tags')">
          标签管理
        </Button>
        <Button type="default" block @click="testPing">
          测试 JSBridge
        </Button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.content {
  padding: 16px;
}

.button-group {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
