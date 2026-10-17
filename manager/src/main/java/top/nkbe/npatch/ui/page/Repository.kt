package org.lsposed.manager.ui.compose.repository

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.page.Navigator
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.concurrent.TimeUnit

private const val SEARCH_URL = "https://modules.lsposed.org/search.json"

/** 免root框架无法修改系统，过滤掉明显作用于系统/框架的模块 */
private val SYSTEM_KEYWORDS = listOf(
    "systemui", "system ui", "状态栏", "导航栏", "status bar", "navigation bar",
    "framework", "系统框架", "系统界面", "开机动画", "boot animation",
    "android system", "系统设置", "settings provider", "launcher", "桌面启动器",
    "system_server", "core patch", "系统级"
)

private fun isSystemLevel(m: RepoModule): Boolean {
    val hay = (m.description + " " + m.summary).lowercase()
    return SYSTEM_KEYWORDS.any { hay.contains(it.lowercase()) }
}

data class RepoModule(
    val pkg: String,
    val description: String,
    val summary: String,
    val authors: String,
)

private val http by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
}

private suspend fun fetchModules(): List<RepoModule> = withContext(Dispatchers.IO) {
    val req = Request.Builder().url(SEARCH_URL).header("User-Agent", "Mozilla/5.0").build()
    http.newCall(req).execute().use { resp ->
        val body = resp.body?.string() ?: return@withContext emptyList()
        val arr = JSONArray(body)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val authorsArr = o.optJSONArray("authors")
                val authorStr = if (authorsArr != null) {
                    (0 until authorsArr.length()).joinToString(", ") { authorsArr.optString(it) }
                } else ""
                add(
                    RepoModule(
                        pkg = o.optString("name"),
                        description = o.optString("description"),
                        summary = o.optString("summary"),
                        authors = authorStr
                    )
                )
            }
        }
    }
}

@Composable
fun RepositoryScreen(navigator: Navigator) {
    val hazeState = rememberHazeState()
    val scrollBehavior = ScrollBehavior()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<RepoModule>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        runCatching { fetchModules() }
            .onSuccess { list = it }
            .onFailure { error = it.message ?: "加载失败" }
        loading = false
    }

    var hideSystem by remember { mutableStateOf(true) }

    val filtered = remember(list, query, hideSystem) {
        list.asSequence()
            .filter { !hideSystem || !isSystemLevel(it) }
            .filter {
                query.isBlank() || it.pkg.contains(query, true) ||
                    it.description.contains(query, true) || it.summary.contains(query, true)
            }
            .toList()
    }

    NPatchScaffold(
        topBar = {
            NPatchTopAppBar(
                title = stringResource(R.string.screen_repo),
                hazeState = hazeState,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            InputField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "屏蔽系统级模块（免Root无法作用于系统）",
                    style = COUITheme.textStyles.body2,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = hideSystem, onCheckedChange = { hideSystem = it })
            }
            Text(
                text = "共 ${filtered.size} 个模块",
                style = COUITheme.textStyles.body2,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text("加载中…")
                }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败: ${error}")
                        Text("点击重试")
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { m ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = m.description.ifBlank { m.pkg },
                                    style = COUITheme.textStyles.body1,
                                )
                                Text(
                                    text = m.pkg,
                                    style = COUITheme.textStyles.body2,
                                )
                                if (m.authors.isNotBlank()) {
                                    Text(
                                        text = m.authors,
                                        style = COUITheme.textStyles.body2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
