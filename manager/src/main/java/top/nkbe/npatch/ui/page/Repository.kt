package org.lsposed.manager.ui.compose.repository

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.page.Navigator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.concurrent.TimeUnit

private const val SEARCH_URL = "https://modules.lsposed.org/search.json"

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
                add(
                    RepoModule(
                        pkg = o.optString("name"),
                        description = o.optString("description"),
                        summary = o.optString("summary"),
                        authors = o.optJSONArray("authors")?.joinToString(", ") ?: ""
                    )
                )
            }
        }
    }
}

@Composable
fun RepositoryScreen(navigator: Navigator) {
    val hazeState = rememberHazeState()
    val scrollBehavior = MiuixScrollBehavior()
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

    val filtered = remember(list, query) {
        if (query.isBlank()) list
        else list.filter {
            it.pkg.contains(query, true) || it.description.contains(query, true) ||
                it.summary.contains(query, true)
        }
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                placeholder = { Text("搜索模块") },
                singleLine = true,
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", style = MiuixTheme.textStyles.body2)
                        Text(error ?: "", style = MiuixTheme.textStyles.body2)
                        TextButton(onClick = {
                            scope.launch {
                                loading = true; error = null
                                runCatching { fetchModules() }
                                    .onSuccess { list = it }
                                    .onFailure { error = it.message }
                                loading = false
                            }
                        }) { Text("重试") }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.pkg }) { m ->
                        RepoModuleItem(m)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoModuleItem(m: RepoModule) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(m.description.ifBlank { m.pkg }, style = MiuixTheme.textStyles.body1)
            Text(m.pkg, style = MiuixTheme.textStyles.body2)
            if (m.authors.isNotBlank()) {
                Text(m.authors, style = MiuixTheme.textStyles.body2)
            }
        }
    }
}
