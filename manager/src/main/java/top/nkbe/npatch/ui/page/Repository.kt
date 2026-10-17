package org.lsposed.manager.ui.compose.repository

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
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


private suspend fun fetchReleases(pkg: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    val url = "https://api.github.com/repos/Xposed-Modules-Repo/$pkg/releases?per_page=3"
    val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
    http.newCall(req).execute().use { resp ->
        val body = resp.body?.string() ?: return@withContext emptyList()
        if (!body.trimStart().startsWith("[")) return@withContext emptyList()
        val arr = JSONArray(body)
        buildList {
            for (i in 0 until minOf(3, arr.length())) {
                val rel = arr.getJSONObject(i)
                val tag = rel.optString("tag_name")
                val assets = rel.optJSONArray("assets")
                var apk = ""
                if (assets != null) {
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        val n = a.optString("name")
                        if (n.endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
                    }
                }
                add(tag to apk)
            }
        }
    }
}

@Composable
fun RepositoryScreen(navigator: Navigator) {
    val hazeState = rememberHazeState()
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
    var selected by remember { mutableStateOf<RepoModule?>(null) }

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
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                label = "搜索模块",
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
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                                .clickable { selected = m },
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


    // === 模块详情弹窗 ===
    val sel = selected
    if (sel != null) {
        var releases by remember(sel.pkg) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var relLoading by remember(sel.pkg) { mutableStateOf(true) }
        LaunchedEffect(sel.pkg) {
            relLoading = true
            releases = runCatching { fetchReleases(sel.pkg) }.getOrDefault(emptyList())
            relLoading = false
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { selected = null }) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(sel.description.ifBlank { sel.pkg }, style = COUITheme.textStyles.title3)
                    Spacer(Modifier.height(6.dp))
                    Text(sel.pkg, style = COUITheme.textStyles.body2)
                    if (sel.authors.isNotBlank()) {
                        Text("作者: " + sel.authors, style = COUITheme.textStyles.body2)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (relLoading) {
                        Text("正在获取版本...")
                    } else if (releases.isEmpty()) {
                        Text("未找到可用版本（可到 GitHub 手动下载）")
                    } else {
                        Text("最新版本:", style = COUITheme.textStyles.body2)
                        Text(releases.first().first)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "下载地址已复制思路：点下方按钮复制链接到浏览器",
                            style = COUITheme.textStyles.body2,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = { selected = null }) {
                            Text("关闭")
                        }
                    }
                }
            }
        }
    }
}
