package com.car.xiaoouscreen.ui.settings.dialogs

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.car.xiaoouscreen.ui.settings.components.NeuButton
import com.car.xiaoouscreen.ui.settings.components.NeuCheckbox
import com.car.xiaoouscreen.ui.settings.model.LyricSourceItemState
import com.car.xiaoouscreen.ui.settings.theme.NeuColors
import com.car.xiaoouscreen.ui.settings.theme.neuButton
import com.car.xiaoouscreen.ui.settings.theme.neuCard
import com.car.xiaoouscreen.ui.settings.theme.neuFloatingShell
import com.car.xiaoouscreen.ui.settings.theme.neuInset
import com.carlyrics.lyrics.LyricsCollection
import com.carlyrics.lyrics.LyricsSource
import com.carlyrics.lyrics.LyricsSourceItem
import com.carlyrics.lyrics.LyricsSourceManager
import com.carlyrics.lyrics.LyricsSourceRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 导入歌词源插件弹窗 (规范重构版)
 *
 * 核心规范：
 * 1. 仅分两个页面 Tab：1.从 URL 导入；2.从文件导入 (彻底移除导入单源)
 * 2. 从 URL 导入：填写 URL 后点击解析，JS 则解析单个源，JSON 合集则解析多个源，默认全部勾选，点击立即导入并启用即可
 * 3. 从文件导入：点击浏览本地文件，支持 .js / .json / .zip，解析后在下方展示默认勾选，点击立即导入
 * 4. 如果导入源均是一样的 (同 ID/名称)，则更新为最后导入的版本
 */
@Composable
fun ImportSourceDialog(
    onDismiss: () -> Unit,
    onSourcesImported: (List<LyricsSource>) -> Unit,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 当前选中的 Tab: "url" 或 "file"
    var currentTab by remember { mutableStateOf("url") }

    // Tab 1: 从 URL 导入状态
    var urlInput by remember { mutableStateOf("") }
    var isUrlParsing by remember { mutableStateOf(false) }
    val parsedUrlSources = remember { mutableStateListOf<LyricsSourceItem>() }
    val selectedUrlSourceIds = remember { mutableStateListOf<String>() }
    var isParsedReady by remember { mutableStateOf(false) }

    // Tab 2: 从文件导入状态
    var selectedFileDisplay by remember { mutableStateOf("") }
    var isFileParsing by remember { mutableStateOf(false) }
    val parsedFileSources = remember { mutableStateListOf<LyricsSourceItem>() }
    val selectedFileSourceIds = remember { mutableStateListOf<String>() }

    // 系统文件选择器 (支持 .js, .json, .zip)
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            isFileParsing = true
            parsedFileSources.clear()
            selectedFileSourceIds.clear()

            try {
                val fileName = uri.lastPathSegment ?: "未知文件"
                selectedFileDisplay = fileName

                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    onShowToast("无法读取选中的本地文件")
                    isFileParsing = false
                    return@launch
                }

                val lowerName = fileName.lowercase()
                withContext(Dispatchers.IO) {
                    if (lowerName.endsWith(".zip")) {
                        // 解析 ZIP 中的所有 .js 源
                        val zipResult = LyricsSourceManager.parseCollectionFromZip(inputStream, fileName)
                        zipResult.onSuccess { col ->
                            val items = col.sources ?: emptyList()
                            withContext(Dispatchers.Main) {
                                parsedFileSources.addAll(items)
                                selectedFileSourceIds.addAll(items.mapNotNull { it.id })
                                onShowToast("成功从 ZIP 中解析出 ${items.size} 个歌词源")
                            }
                        }.onFailure { err ->
                            withContext(Dispatchers.Main) {
                                onShowToast("解析 ZIP 失败: ${err.message}")
                            }
                        }
                    } else if (lowerName.endsWith(".json")) {
                        // 解析 JSON 合集
                        val jsonStr = inputStream.bufferedReader().use { it.readText() }
                        val colResult = LyricsSourceManager.parseCollectionJson(jsonStr)
                        colResult.onSuccess { col ->
                            val items = col.sources ?: emptyList()
                            withContext(Dispatchers.Main) {
                                parsedFileSources.addAll(items)
                                selectedFileSourceIds.addAll(items.mapNotNull { it.id })
                                onShowToast("成功从 JSON 合集中解析出 ${items.size} 个歌词源")
                            }
                        }.onFailure { err ->
                            withContext(Dispatchers.Main) {
                                onShowToast("解析 JSON 失败: ${err.message}")
                            }
                        }
                    } else {
                        // 默认为 .js 单源
                        val scriptStr = inputStream.bufferedReader().use { it.readText() }
                        val defaultId = fileName.substringBeforeLast(".")
                        val meta = LyricsSourceRunner.parseMetadata(scriptStr, defaultId)
                        val item = LyricsSourceItem(
                            id = meta.id,
                            name = meta.name,
                            version = meta.version,
                            author = meta.author,
                            description = meta.description,
                            url = null,
                            script = scriptStr
                        )
                        withContext(Dispatchers.Main) {
                            parsedFileSources.add(item)
                            selectedFileSourceIds.add(meta.id)
                            onShowToast("已成功识别 JS 歌词源：「${meta.name}」")
                        }
                    }
                }
            } catch (e: Exception) {
                onShowToast("文件读取或解析失败: ${e.message}")
            } finally {
                isFileParsing = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 520.dp, max = 680.dp)
                    .fillMaxHeight(0.85f)
                    .neuFloatingShell(cornerRadius = 24.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { }
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 头部标题与关闭
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .neuButton(cornerRadius = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = "导入源",
                                    tint = NeuColors.AccentBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "导入歌词源插件",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextPrimary
                                )
                                Text(
                                    text = "支持从网络 URL 远程加载或从本地文件 (.js/.json/.zip) 批量导入",
                                    fontSize = 11.sp,
                                    color = NeuColors.TextSecondary
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .neuButton(cornerRadius = 8.dp)
                                .clickable { onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.TextSecondary
                            )
                        }
                    }

                    // 两个页面的 Tab 切换栏：1.从 URL 导入 / 2.从文件导入
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neuInset(cornerRadius = 12.dp)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TabItem(
                            title = "1. 从 URL 导入",
                            icon = Icons.Default.Language,
                            isSelected = currentTab == "url",
                            onClick = { currentTab = "url" },
                            modifier = Modifier.weight(1f)
                        )
                        TabItem(
                            title = "2. 从文件导入",
                            icon = Icons.Default.FolderOpen,
                            isSelected = currentTab == "file",
                            onClick = { currentTab = "file" },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // =========================================================
                    // 页面 1：从 URL 导入
                    // =========================================================
                    if (currentTab == "url") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // URL 输入行与解析按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .neuInset(cornerRadius = 12.dp)
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    if (urlInput.isEmpty()) {
                                        Text(
                                            text = "输入插件 URL (支持 .js 单源或 .json 合集链接)",
                                            fontSize = 12.sp,
                                            color = NeuColors.TextMuted
                                        )
                                    }
                                    BasicTextField(
                                        value = urlInput,
                                        onValueChange = {
                                            urlInput = it
                                            isParsedReady = false
                                        },
                                        textStyle = TextStyle(
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = NeuColors.TextPrimary,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        cursorBrush = SolidColor(NeuColors.AccentBlue),
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true
                                    )
                                }

                                NeuButton(
                                    text = if (isUrlParsing) "解析中..." else if (isParsedReady) "重新解析" else "解析",
                                    onClick = {
                                        val trimmedUrl = urlInput.trim()
                                        if (trimmedUrl.isBlank()) {
                                            onShowToast("请先输入有效的歌词源 URL 链接")
                                            return@NeuButton
                                        }

                                        coroutineScope.launch {
                                            isUrlParsing = true
                                            parsedUrlSources.clear()
                                            selectedUrlSourceIds.clear()

                                            try {
                                                withContext(Dispatchers.IO) {
                                                    val req = Request.Builder().url(trimmedUrl).build()
                                                    val bodyStr = com.carlyrics.lyrics.LyricsSourceRunner.client.newCall(req).execute().use { resp ->
                                                        if (!resp.isSuccessful) {
                                                            throw IllegalStateException("HTTP 状态码: ${resp.code}")
                                                        }
                                                        resp.body?.string() ?: ""
                                                    }
                                                    val trimmedBody = bodyStr.trim()

                                                    if (trimmedBody.startsWith("{") || trimmedBody.startsWith("[")) {
                                                        // JSON 合集
                                                        val colResult = LyricsSourceManager.parseCollectionJson(trimmedBody)
                                                        colResult.onSuccess { col ->
                                                            val items = col.sources ?: emptyList()
                                                            withContext(Dispatchers.Main) {
                                                                parsedUrlSources.addAll(items)
                                                                selectedUrlSourceIds.addAll(items.mapNotNull { it.id })
                                                                isParsedReady = true
                                                                onShowToast("已解析出合集中的 ${items.size} 个歌词源")
                                                            }
                                                        }.onFailure { e ->
                                                            withContext(Dispatchers.Main) {
                                                                onShowToast("解析合集失败: ${e.message}")
                                                            }
                                                        }
                                                    } else {
                                                        // 单个 JS 源
                                                        val defaultId = trimmedUrl.substringAfterLast("/").substringBefore("?")
                                                        val meta = LyricsSourceRunner.parseMetadata(trimmedBody, defaultId)
                                                        val item = LyricsSourceItem(
                                                            id = meta.id,
                                                            name = meta.name,
                                                            version = meta.version,
                                                            author = meta.author,
                                                            description = meta.description,
                                                            url = trimmedUrl,
                                                            script = trimmedBody
                                                        )
                                                        withContext(Dispatchers.Main) {
                                                            parsedUrlSources.add(item)
                                                            selectedUrlSourceIds.add(meta.id)
                                                            isParsedReady = true
                                                            onShowToast("已解析出单源：「${meta.name}」")
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                onShowToast("网络请求或解析失败: ${e.message}")
                                            } finally {
                                                isUrlParsing = false
                                            }
                                        }
                                    },
                                    textColor = NeuColors.AccentBlue
                                )
                            }

                            // 展开解析出的源列表
                            if (parsedUrlSources.isNotEmpty()) {
                                Text(
                                    text = "已识别到以下源插件（已默认勾选，可按需点选）：",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextSecondary
                                )

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(parsedUrlSources, key = { it.id ?: it.name ?: "" }) { srcItem ->
                                        val srcId = srcItem.id ?: ""
                                        val isChecked = selectedUrlSourceIds.contains(srcId)
                                        SourceCheckItemRow(
                                            title = srcItem.name ?: srcId,
                                            subtitle = "作者: ${srcItem.author ?: "未知"} · 版本: v${srcItem.version ?: "1.0.0"}",
                                            desc = srcItem.description ?: "通过 URL 远程解析",
                                            isChecked = isChecked,
                                            onToggle = {
                                                if (isChecked) selectedUrlSourceIds.remove(srcId)
                                                else selectedUrlSourceIds.add(srcId)
                                            }
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .neuInset(cornerRadius = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isUrlParsing) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = NeuColors.AccentBlue)
                                    } else {
                                        Text(
                                            text = "填写 URL 链接后点击上方「解析」，将在此处展开源信息并导入",
                                            fontSize = 12.sp,
                                            color = NeuColors.TextMuted
                                        )
                                    }
                                }
                            }
                        }

                        // URL 页面底部确认导入操作
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NeuButton(
                                text = "取消",
                                onClick = onDismiss
                            )

                            NeuButton(
                                text = if (selectedUrlSourceIds.isEmpty()) "立即导入并启用" else "立即导入并启用 (${selectedUrlSourceIds.size})",
                                onClick = {
                                    if (parsedUrlSources.isEmpty()) {
                                        onShowToast("请先填写 URL 并成功解析歌词源")
                                        return@NeuButton
                                    }
                                    val toImport = parsedUrlSources.filter { selectedUrlSourceIds.contains(it.id) }
                                    if (toImport.isEmpty()) {
                                        onShowToast("请至少勾选一个需要导入的歌词源")
                                        return@NeuButton
                                    }

                                    coroutineScope.launch {
                                        val importedList = mutableListOf<LyricsSource>()
                                        withContext(Dispatchers.IO) {
                                            for (item in toImport) {
                                                val id = item.id ?: "src_${System.currentTimeMillis()}"
                                                val script = item.script ?: ""
                                                val meta = if (script.isNotBlank()) {
                                                    LyricsSourceRunner.parseMetadata(script, id)
                                                } else {
                                                    LyricsSource(
                                                        id = id,
                                                        name = item.name ?: id,
                                                        version = item.version ?: "1.0.0",
                                                        author = item.author ?: "",
                                                        description = item.description ?: "",
                                                        script = script,
                                                        url = item.url ?: "",
                                                        isEnabled = true
                                                    )
                                                }
                                                LyricsSourceManager.saveSource(meta.copy(isEnabled = true))
                                                importedList.add(meta)
                                            }
                                        }
                                        onSourcesImported(importedList)
                                        onShowToast("已成功导入并更新 ${importedList.size} 个歌词源")
                                        onDismiss()
                                    }
                                },
                                isPrimary = true
                            )
                        }
                    }

                    // =========================================================
                    // 页面 2：从文件导入
                    // =========================================================
                    if (currentTab == "file") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 文件选择控制栏
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .neuCard(cornerRadius = 14.dp)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = "选择车机/U盘存储中的插件文件",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuColors.TextPrimary
                                    )
                                    Text(
                                        text = if (selectedFileDisplay.isNotBlank()) "当前已选：$selectedFileDisplay"
                                        else "支持 .js 单源、.json 规则集及 .zip 压缩包批量解析",
                                        fontSize = 11.sp,
                                        color = if (selectedFileDisplay.isNotBlank()) NeuColors.AccentBlue else NeuColors.TextSecondary
                                    )
                                }

                                NeuButton(
                                    text = "浏览本地文件...",
                                    onClick = {
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    },
                                    isPrimary = true
                                )
                            }

                            // 展开解析出的文件源列表
                            if (parsedFileSources.isNotEmpty()) {
                                Text(
                                    text = "已从文件中解析出以下源插件（已默认勾选，同名源将自动更新至最新）：",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.TextSecondary
                                )

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(parsedFileSources, key = { it.id ?: it.name ?: "" }) { srcItem ->
                                        val srcId = srcItem.id ?: ""
                                        val isChecked = selectedFileSourceIds.contains(srcId)
                                        SourceCheckItemRow(
                                            title = srcItem.name ?: srcId,
                                            subtitle = "作者: ${srcItem.author ?: "未知"} · 版本: v${srcItem.version ?: "1.0.0"}",
                                            desc = srcItem.description ?: "来自本地文件导入",
                                            isChecked = isChecked,
                                            onToggle = {
                                                if (isChecked) selectedFileSourceIds.remove(srcId)
                                                else selectedFileSourceIds.add(srcId)
                                            }
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .neuInset(cornerRadius = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isFileParsing) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp), color = NeuColors.AccentBlue)
                                    } else {
                                        Text(
                                            text = "点击上方「浏览本地文件...」选择 .js、.json 或 .zip 歌词源文件",
                                            fontSize = 12.sp,
                                            color = NeuColors.TextMuted
                                        )
                                    }
                                }
                            }
                        }

                        // 文件页面底部确认导入操作
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NeuButton(
                                text = "取消",
                                onClick = onDismiss
                            )

                            NeuButton(
                                text = if (selectedFileSourceIds.isEmpty()) "立即导入并启用" else "立即导入并启用 (${selectedFileSourceIds.size})",
                                onClick = {
                                    if (parsedFileSources.isEmpty()) {
                                        onShowToast("请先选择本地文件并完成解析")
                                        return@NeuButton
                                    }
                                    val toImport = parsedFileSources.filter { selectedFileSourceIds.contains(it.id) }
                                    if (toImport.isEmpty()) {
                                        onShowToast("请至少勾选一个需要导入的歌词源")
                                        return@NeuButton
                                    }

                                    coroutineScope.launch {
                                        val importedList = mutableListOf<LyricsSource>()
                                        withContext(Dispatchers.IO) {
                                            for (item in toImport) {
                                                val id = item.id ?: "file_${System.currentTimeMillis()}"
                                                val script = item.script ?: ""
                                                val meta = if (script.isNotBlank()) {
                                                    LyricsSourceRunner.parseMetadata(script, id)
                                                } else {
                                                    LyricsSource(
                                                        id = id,
                                                        name = item.name ?: id,
                                                        version = item.version ?: "1.0.0",
                                                        author = item.author ?: "",
                                                        description = item.description ?: "",
                                                        script = script,
                                                        url = item.url ?: "",
                                                        isEnabled = true
                                                    )
                                                }
                                                // 同名/同ID源自动更新为最新导入版本
                                                LyricsSourceManager.saveSource(meta.copy(isEnabled = true))
                                                importedList.add(meta)
                                            }
                                        }
                                        onSourcesImported(importedList)
                                        onShowToast("已成功导入并更新 ${importedList.size} 个歌词源")
                                        onDismiss()
                                    }
                                },
                                isPrimary = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabItem(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .background(
                color = if (isSelected) NeuColors.AccentBlue else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) Color.White else NeuColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else NeuColors.TextSecondary
            )
        }
    }
}

@Composable
private fun SourceCheckItemRow(
    title: String,
    subtitle: String,
    desc: String,
    isChecked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neuCard(
                cornerRadius = 12.dp,
                borderColor = if (isChecked) Color(0xFF93C5FD) else NeuColors.BorderCard
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NeuCheckbox(
                checked = isChecked,
                onCheckedChange = { onToggle() }
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.TextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = NeuColors.TextSecondary
                )
                Text(
                    text = desc,
                    fontSize = 10.sp,
                    color = NeuColors.TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}
