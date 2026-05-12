---
name: project-overview-update
description: 项目文件变更后必须更新项目总览文档的规则
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 6edfbae0-1454-443f-b340-5c54524c783c
---

# 项目总览文档更新规则

## 规则

每次改动了项目文件后，必须同步更新 `docs\项目总览.md` 文件。

**Why:**
用户希望后续的 agent 能够通过该文档快速了解项目当前情况，避免重复扫描和重复解释。

**How to apply:**

1. 每次修改代码文件、添加新功能、重构结构后，立即更新 `docs\项目总览.md`
2. 更新内容应包括：
   - 新增/修改的文件列表
   - 功能变更说明
   - 架构调整要点
   - 依赖变化
3. 保持文档简洁，重点突出"发生了什么变化"
4. 不要重复显而易见的信息，聚焦于需要了解的变更

## 相关文档路径

- `D:\ai-create\ai-project\so-image-manager\docs\项目总览.md`

## 关联记忆

- [[version-release-process]] - 版本发布流程