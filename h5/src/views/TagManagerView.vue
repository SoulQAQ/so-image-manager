<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { NavBar, Cell, CellGroup, Field, Button, Tag, showToast, showNotify } from 'vant'

interface TagAlias {
  alias: string
  canonical: string
}

const aliases = ref<TagAlias[]>([])
const newAlias = ref('')
const newCanonical = ref('')
const allTags = ref<string[]>([])
const loading = ref(false)

function loadAliases() {
  if (!window.AppBridge) return

  loading.value = true
  try {
    const result = JSON.parse(window.AppBridge.getTagAliases())
    if (result.code === 0) {
      aliases.value = result.data.items || []
    }
  } finally {
    loading.value = false
  }
}

function loadAllTags() {
  if (!window.AppBridge) return

  try {
    const result = JSON.parse(window.AppBridge.getAllTags())
    if (result.code === 0) {
      allTags.value = (result.data.items || []).map((t: any) => t.name)
    }
  } catch (e) {
    console.error('Failed to load tags', e)
  }
}

function addAlias() {
  if (!newAlias.value.trim() || !newCanonical.value.trim()) {
    showToast('请填写完整信息')
    return
  }

  if (!window.AppBridge) return

  const result = JSON.parse(
    window.AppBridge.addTagAlias(newAlias.value.trim(), newCanonical.value.trim())
  )

  if (result.code === 0) {
    showNotify({ type: 'success', message: '添加成功' })
    aliases.value.push({
      alias: newAlias.value.trim(),
      canonical: newCanonical.value.trim()
    })
    newAlias.value = ''
    newCanonical.value = ''
  } else {
    showToast(result.message)
  }
}

function deleteAlias(alias: string) {
  if (!window.AppBridge) return

  const result = JSON.parse(window.AppBridge.deleteTagAlias(alias))
  if (result.code === 0) {
    aliases.value = aliases.value.filter(a => a.alias !== alias)
    showToast('删除成功')
  } else {
    showToast(result.message)
  }
}

onMounted(() => {
  loadAliases()
  loadAllTags()
})
</script>

<template>
  <div class="tag-manager">
    <NavBar title="标签管理" left-arrow @click-left="$router.back()" safe-area-inset-top />

    <div class="content">
      <CellGroup inset title="添加别名">
        <Field
          v-model="newAlias"
          label="别名"
          placeholder="输入标签别名"
        />
        <Field
          v-model="newCanonical"
          label="标准名"
          placeholder="输入标准标签名"
        />
        <div class="add-button">
          <Button type="primary" size="small" @click="addAlias">添加</Button>
        </div>
      </CellGroup>

      <CellGroup inset title="已有别名">
        <Cell
          v-for="item in aliases"
          :key="item.alias"
          :title="item.alias"
          :value="item.canonical"
          value-class="canonical-value"
        >
          <template #right-icon>
            <Tag plain @click="deleteAlias(item.alias)">删除</Tag>
          </template>
        </Cell>
        <Cell v-if="aliases.length === 0" title="暂无别名" />
      </CellGroup>

      <CellGroup inset title="所有标签" v-if="allTags.length > 0">
        <div class="tags-cloud">
          <Tag v-for="tag in allTags.slice(0, 50)" :key="tag" plain>{{ tag }}</Tag>
        </div>
        <div v-if="allTags.length > 50" class="more-tags">
          还有 {{ allTags.length - 50 }} 个标签...
        </div>
      </CellGroup>
    </div>
  </div>
</template>

<style scoped>
.content {
  padding: 12px;
}

.add-button {
  padding: 12px;
  text-align: center;
}

.canonical-value {
  color: #1989fa;
}

.tags-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px;
}

.more-tags {
  text-align: center;
  padding: 8px;
  color: #999;
  font-size: 12px;
}
</style>
