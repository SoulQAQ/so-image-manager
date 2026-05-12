package cn.soul2.imageai.webview

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import cn.soul2.imageai.data.db.AppDatabase
import cn.soul2.imageai.domain.service.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class JsBridge(private val context: Context) {

    private val database: AppDatabase = AppDatabase.getInstance(context)
    private val imageDao = database.imageDao()
    private val tagDao = database.tagDao()
    private val searchService = SearchService(context)

    @JavascriptInterface
    fun ping(message: String): String {
        return successResult("Pong: $message", JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("received", message)
        })
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        return successResult("ok", JSONObject().apply {
            put("appName", "ImageAI")
            put("versionName", "1.0.0")
            put("versionCode", 1)
            put("deviceId", android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ))
        })
    }

    /**
     * 文本搜索图片
     */
    @JavascriptInterface
    fun searchImages(query: String, limit: Int): String {
        return runBlocking {
            try {
                val results = searchService.searchByText(query, limit)
                val dataArray = JSONArray().apply {
                    results.forEach { result ->
                        put(JSONObject().apply {
                            put("imageId", result.imageId)
                            put("uri", result.uri)
                            put("sha256", result.sha256)
                            put("width", result.width)
                            put("height", result.height)
                            put("mimeType", result.mimeType)
                            put("relevance", result.relevance)
                        })
                    }
                }
                successResult("ok", JSONObject().apply {
                    put("total", results.size)
                    put("items", dataArray)
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Search failed")
            }
        }
    }

    /**
     * 获取图片标签
     */
    @JavascriptInterface
    fun getImageTags(imageId: Long): String {
        return runBlocking {
            try {
                val result = searchService.getImageTags(imageId)
                successResult("ok", JSONObject().apply {
                    put("imageId", result.imageId)
                    put("uri", result.uri ?: "")
                    put("sha256", result.sha256 ?: "")
                    put("aiTags", JSONArray().apply {
                        result.aiTags.forEach { tag ->
                            put(JSONObject().apply {
                                put("name", tag.name)
                                put("confidence", tag.confidence)
                            })
                        }
                    })
                    put("userTags", JSONArray().apply {
                        result.userTags.forEach { tag ->
                            put(JSONObject().apply {
                                put("name", tag.name)
                                put("confidence", tag.confidence)
                            })
                        }
                    })
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to get tags")
            }
        }
    }

    /**
     * 保存用户修正的标签
     */
    @JavascriptInterface
    fun saveUserTags(imageId: Long, tagsJson: String): String {
        return runBlocking {
            try {
                val tagsArray = JSONArray(tagsJson)
                val tags = mutableListOf<String>()
                for (i in 0 until tagsArray.length()) {
                    tags.add(tagsArray.getString(i))
                }
                searchService.saveUserTags(imageId, tags)
                successResult("ok", JSONObject().apply {
                    put("imageId", imageId)
                    put("savedCount", tags.size)
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to save tags")
            }
        }
    }

    /**
     * 获取所有标签
     */
    @JavascriptInterface
    fun getAllTags(): String {
        return runBlocking {
            try {
                val tags = tagDao.getAllFlow()
                // 获取当前值
                val tagList = database.openHelper.readableDatabase.query(
                    "SELECT id, name FROM tag ORDER BY name"
                ).use { cursor ->
                    val list = mutableListOf<JSONObject>()
                    while (cursor.moveToNext()) {
                        list.add(JSONObject().apply {
                            put("id", cursor.getLong(0))
                            put("name", cursor.getString(1))
                        })
                    }
                    list
                }
                successResult("ok", JSONObject().apply {
                    put("total", tagList.size)
                    put("items", JSONArray().apply {
                        tagList.forEach { put(it) }
                    })
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to get tags")
            }
        }
    }

    /**
     * 获取标签别名列表
     */
    @JavascriptInterface
    fun getTagAliases(): String {
        return runBlocking {
            try {
                val aliases = database.openHelper.readableDatabase.query(
                    "SELECT alias, canonical FROM tag_alias ORDER BY canonical, alias"
                ).use { cursor ->
                    val list = mutableListOf<JSONObject>()
                    while (cursor.moveToNext()) {
                        list.add(JSONObject().apply {
                            put("alias", cursor.getString(0))
                            put("canonical", cursor.getString(1))
                        })
                    }
                    list
                }
                successResult("ok", JSONObject().apply {
                    put("total", aliases.size)
                    put("items", JSONArray().apply {
                        aliases.forEach { put(it) }
                    })
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to get aliases")
            }
        }
    }

    /**
     * 添加标签别名
     */
    @JavascriptInterface
    fun addTagAlias(alias: String, canonical: String): String {
        return runBlocking {
            try {
                tagDao.insertAlias(cn.soul2.imageai.data.db.entity.TagAliasEntity(alias, canonical))
                successResult("ok", JSONObject().apply {
                    put("alias", alias)
                    put("canonical", canonical)
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to add alias")
            }
        }
    }

    /**
     * 删除标签别名
     */
    @JavascriptInterface
    fun deleteTagAlias(alias: String): String {
        return runBlocking {
            try {
                tagDao.deleteAlias(alias)
                successResult("ok", JSONObject().apply {
                    put("alias", alias)
                    put("deleted", true)
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to delete alias")
            }
        }
    }

    /**
     * 获取图片统计信息
     */
    @JavascriptInterface
    fun getStatistics(): String {
        return runBlocking {
            try {
                val totalImages = imageDao.getCount()
                val pendingCount = imageDao.getCountByAiStatus(cn.soul2.imageai.data.db.entity.ImageEntity.AI_STATUS_PENDING)
                val doneCount = imageDao.getCountByAiStatus(cn.soul2.imageai.data.db.entity.ImageEntity.AI_STATUS_DONE)
                val failedCount = imageDao.getCountByAiStatus(cn.soul2.imageai.data.db.entity.ImageEntity.AI_STATUS_FAILED)

                successResult("ok", JSONObject().apply {
                    put("totalImages", totalImages)
                    put("pendingCount", pendingCount)
                    put("doneCount", doneCount)
                    put("failedCount", failedCount)
                })
            } catch (e: Exception) {
                errorResult(e.message ?: "Failed to get statistics")
            }
        }
    }

    private fun successResult(message: String, data: JSONObject): String {
        return JSONObject().apply {
            put("code", 0)
            put("message", message)
            put("data", data)
        }.toString()
    }

    private fun errorResult(message: String): String {
        return JSONObject().apply {
            put("code", -1)
            put("message", message)
            put("data", null)
        }.toString()
    }
}
