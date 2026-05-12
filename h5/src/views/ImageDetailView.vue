<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NavBar, Image, Tag, Loading, showToast, showConfirmDialog } from 'vant'
import type { ImageTagInfo } from '../types'

const route = useRoute()
const router = useRouter()
const imageId = computed(() => Number(route.params.id))

const imageInfo = ref({
  uri: '',
  sha256: '',
  width: null as number | null,
  height: null as number | null
})
const aiTags = ref<ImageTagInfo[]>([])
const userTags = ref<ImageTagInfo[]>([])
const loading = ref(true)

function loadImageData() {
  if (!window.AppBridge) return

  loading.value = true
  try {
    const result = JSON.parse(window.AppBridge.getImageTags(imageId.value))
    if (result.code === 0) {
      imageInfo.value = {
        uri: result.data.uri || '',
        sha256: result.data.sha256 || '',
        width: result.data.width,
        height: result.data.height
      }
      aiTags.value = result.data.aiTags || []
      userTags.value = result.data.userTags || []
    } else {
      showToast(result.message)
    }
  } catch (e) {
    showToast('加载失败')
  } finally {
    loading.value = false
  }
}

async function deleteUserTag(tag: ImageTagInfo) {
  try {
    await showConfirmDialog({
      title: '删除标签',
      message: `确定删除标签"${tag.name}"？`
    })
    const newTags = userTags.value.filter(t => t.name !== tag.name)
    saveUserTags(newTags.map(t => t.name))
  } catch {
    // 用户取消
  }
}

function saveUserTags(tags: string[]) {
  if (!window.AppBridge) return

  const result = JSON.parse(window.AppBridge.saveUserTags(imageId.value, JSON.stringify(tags)))
  if (result.code === 0) {
    showToast('保存成功')
    loadImageData()
  } else {
    showToast(result.message)
  }
}

onMounted(() => {
  loadImageData()
})
</script>

<template>
  <div class="image-detail">
    <NavBar title="图片详情" left-arrow @click-left="router.back()" safe-area-inset-top />

    <Loading v-if="loading" class="loading-center" />

    <div v-else class="content">
      <div class="image-container">
        <Image :src="imageInfo.uri" fit="contain" />
      </div>

      <div class="info-section">
        <div class="info-item" v-if="imageInfo.sha256">
          <span class="label">SHA256:</span>
          <span class="value sha256">{{ imageInfo.sha256 }}</span>
        </div>
        <div class="info-item" v-if="imageInfo.width && imageInfo.height">
          <span class="label">尺寸:</span>
          <span class="value">{{ imageInfo.width }} × {{ imageInfo.height }}</span>
        </div>
      </div>

      <div class="tags-section" v-if="aiTags.length > 0">
        <div class="section-title">AI 标签</div>
        <div class="tags">
          <Tag v-for="tag in aiTags" :key="tag.name" plain>
            {{ tag.name }} ({{ (tag.confidence * 100).toFixed(0) }}%)
          </Tag>
        </div>
      </div>

      <div class="tags-section" v-if="userTags.length > 0">
        <div class="section-title">用户标签</div>
        <div class="tags">
          <Tag
            v-for="tag in userTags"
            :key="tag.name"
            closeable
            @close="deleteUserTag(tag)"
          >
            {{ tag.name }}
          </Tag>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.loading-center {
  display: flex;
  justify-content: center;
  padding-top: 100px;
}

.content {
  padding: 12px;
}

.image-container {
  background: #f5f5f5;
  border-radius: 8px;
  overflow: hidden;
  margin-bottom: 16px;
}

.image-container .van-image {
  width: 100%;
  max-height: 300px;
}

.info-section {
  background: white;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 16px;
}

.info-item {
  display: flex;
  margin-bottom: 8px;
}

.info-item:last-child {
  margin-bottom: 0;
}

.label {
  color: #666;
  min-width: 60px;
}

.value {
  flex: 1;
  word-break: break-all;
}

.sha256 {
  font-size: 12px;
  color: #999;
}

.tags-section {
  background: white;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 16px;
}

.section-title {
  font-weight: 500;
  margin-bottom: 8px;
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
