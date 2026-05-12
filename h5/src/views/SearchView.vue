<script setup lang="ts">
import { ref } from 'vue'
import { NavBar, Search, Image, Empty, showToast } from 'vant'
import type { ImageSearchResult } from '../types'

const keyword = ref('')
const results = ref<ImageSearchResult[]>([])
const loading = ref(false)

function doSearch() {
  if (!keyword.value.trim()) {
    showToast('请输入搜索关键词')
    return
  }

  if (!window.AppBridge) {
    showToast('AppBridge 不可用')
    return
  }

  loading.value = true
  try {
    const result = JSON.parse(window.AppBridge.searchImages(keyword.value, 100))
    if (result.code === 0) {
      results.value = result.data.items || []
      if (results.value.length === 0) {
        showToast('未找到相关图片')
      }
    } else {
      showToast(result.message)
    }
  } catch (e) {
    showToast('搜索失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="search-view">
    <NavBar title="搜索图片" left-arrow @click-left="$router.back()" safe-area-inset-top />

    <div class="content">
      <Search v-model="keyword" placeholder="输入关键词搜索" show-action @search="doSearch">
        <template #action>
          <div @click="doSearch">搜索</div>
        </template>
      </Search>

      <div class="results" v-if="results.length > 0">
        <div class="image-grid">
          <div
            v-for="item in results"
            :key="item.imageId"
            class="image-item"
            @click="$router.push(`/image/${item.imageId}`)"
          >
            <Image :src="item.uri" fit="cover" lazy-load />
            <div class="relevance">{{ (item.relevance || 0).toFixed(2) }}</div>
          </div>
        </div>
      </div>

      <Empty v-else-if="!loading" description="搜索图片" />
    </div>
  </div>
</template>

<style scoped>
.content {
  padding-bottom: 16px;
}

.results {
  padding: 12px;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.image-item {
  aspect-ratio: 1;
  position: relative;
  overflow: hidden;
  border-radius: 8px;
  background: #f5f5f5;
}

.image-item .van-image {
  width: 100%;
  height: 100%;
}

.relevance {
  position: absolute;
  bottom: 4px;
  right: 4px;
  background: rgba(0, 0, 0, 0.6);
  color: white;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
