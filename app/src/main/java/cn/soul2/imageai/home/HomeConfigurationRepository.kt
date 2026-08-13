package cn.soul2.imageai.home

import cn.soul2.imageai.data.db.dao.AppSettingDao
import cn.soul2.imageai.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class HomeModuleType { RECENT, ALBUM, TAG, CATEGORY, SAVED_SEARCH, THEME }
enum class HomeModuleSort { NEWEST, NAME, SIZE }
enum class HomeModuleLayout { STRIP, GRID }

data class HomeModule(
    val id: String,
    val title: String,
    val type: HomeModuleType,
    val sourceKey: String = "",
    val enabled: Boolean = true,
    val sort: HomeModuleSort = HomeModuleSort.NEWEST,
    val layout: HomeModuleLayout = HomeModuleLayout.STRIP,
    val previewCount: Int = 12,
)

data class SavedSearch(val id: String, val name: String, val query: String)
data class UserTheme(val id: String, val name: String, val query: String, val aiRecommended: Boolean)

class HomeConfigurationRepository(private val dao: AppSettingDao) {
    val modules: Flow<List<HomeModule>> = dao.observeByKey(KEY_MODULES).map {
        parseModules(it?.valueJson).ifEmpty { listOf(defaultModule()) }
    }
    val savedSearches: Flow<List<SavedSearch>> = dao.observeByKey(KEY_SEARCHES).map { parseSearches(it?.valueJson) }
    val themes: Flow<List<UserTheme>> = dao.observeByKey(KEY_THEMES).map { parseThemes(it?.valueJson) }

    suspend fun saveSearch(name: String, query: String): SavedSearch {
        require(name.isNotBlank() && query.isNotBlank())
        val values = parseSearches(dao.getByKey(KEY_SEARCHES)?.valueJson).toMutableList()
        val item = SavedSearch(UUID.randomUUID().toString(), name.trim().take(80), query.trim().take(512))
        values += item
        save(KEY_SEARCHES, JSONArray(values.map(::searchJson)).toString())
        return item
    }

    suspend fun saveTheme(name: String, query: String, aiRecommended: Boolean = false): UserTheme {
        require(name.isNotBlank() && query.isNotBlank())
        val values = parseThemes(dao.getByKey(KEY_THEMES)?.valueJson).toMutableList()
        val item = UserTheme(UUID.randomUUID().toString(), name.trim().take(80), query.trim().take(512), aiRecommended)
        values += item
        save(KEY_THEMES, JSONArray(values.map(::themeJson)).toString())
        return item
    }

    suspend fun replaceRecommendedThemes(values: List<Pair<String, String>>) {
        val retained = parseThemes(dao.getByKey(KEY_THEMES)?.valueJson)
            .filterNot(UserTheme::aiRecommended)
        val recommendations = values
            .filter { (name, query) -> name.isNotBlank() && query.isNotBlank() }
            .distinctBy { it.second }
            .take(12)
            .map { (name, query) ->
                UserTheme(
                    id = "ai.${UUID.nameUUIDFromBytes(query.toByteArray(Charsets.UTF_8))}",
                    name = name.trim().take(80),
                    query = query.trim().take(512),
                    aiRecommended = true,
                )
            }
        save(KEY_THEMES, JSONArray((retained + recommendations).map(::themeJson)).toString())
    }

    suspend fun deleteTheme(id: String) {
        val values = parseThemes(dao.getByKey(KEY_THEMES)?.valueJson).filterNot { it.id == id }
        save(KEY_THEMES, JSONArray(values.map(::themeJson)).toString())
    }

    suspend fun saveModules(modules: List<HomeModule>) {
        val normalized = modules.distinctBy(HomeModule::id).take(12).map {
            it.copy(title = it.title.trim().take(80), previewCount = it.previewCount.coerceIn(4, 50))
        }
        save(KEY_MODULES, JSONArray(normalized.map(::moduleJson)).toString())
    }

    private suspend fun save(key: String, json: String) = dao.upsert(
        AppSettingEntity(key, json, System.currentTimeMillis()),
    )

    companion object {
        private const val KEY_MODULES = "home.modules.v1"
        private const val KEY_SEARCHES = "search.saved.v1"
        private const val KEY_THEMES = "theme.rules.v1"
        fun defaultModule() = HomeModule("recent", "最近图片", HomeModuleType.RECENT)
    }
}

private fun moduleJson(v: HomeModule) = JSONObject().put("id", v.id).put("title", v.title)
    .put("type", v.type.name).put("sourceKey", v.sourceKey).put("enabled", v.enabled)
    .put("sort", v.sort.name).put("layout", v.layout.name).put("previewCount", v.previewCount)
private fun searchJson(v: SavedSearch) = JSONObject().put("id", v.id).put("name", v.name).put("query", v.query)
private fun themeJson(v: UserTheme) = JSONObject().put("id", v.id).put("name", v.name).put("query", v.query)
    .put("aiRecommended", v.aiRecommended)

private fun parseModules(raw: String?): List<HomeModule> = parseArray(raw) { j ->
    HomeModule(
        j.getString("id"), j.getString("title"), HomeModuleType.valueOf(j.getString("type")),
        j.optString("sourceKey"), j.optBoolean("enabled", true),
        runCatching { HomeModuleSort.valueOf(j.optString("sort", HomeModuleSort.NEWEST.name)) }
            .getOrDefault(HomeModuleSort.NEWEST),
        runCatching { HomeModuleLayout.valueOf(j.optString("layout", HomeModuleLayout.STRIP.name)) }
            .getOrDefault(HomeModuleLayout.STRIP),
        j.optInt("previewCount", 12).coerceIn(4, 50),
    )
}
private fun parseSearches(raw: String?): List<SavedSearch> = parseArray(raw) { j ->
    SavedSearch(j.getString("id"), j.getString("name"), j.getString("query"))
}
private fun parseThemes(raw: String?): List<UserTheme> = parseArray(raw) { j ->
    UserTheme(j.getString("id"), j.getString("name"), j.getString("query"), j.optBoolean("aiRecommended"))
}
private fun <T> parseArray(raw: String?, parser: (JSONObject) -> T): List<T> = runCatching {
    val array = JSONArray(raw ?: "[]")
    List(array.length()) { parser(array.getJSONObject(it)) }
}.getOrDefault(emptyList())
