package com.example.filemanager.ui.viewers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.filemanager.apk.ApkInstaller
import com.example.filemanager.archive.*
import com.example.filemanager.model.FileItem
import com.example.filemanager.ui.archive.FolderPickerDialog
import com.example.filemanager.ui.explorer.*
import com.example.filemanager.utils.FileThumbnailLoader
import com.example.filemanager.utils.FileUtils
import com.example.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

data class ArchiveStackItem(
    val file: File,
    val title: String,
    val isTemporary: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    archiveFile: File,
    onBack: () -> Unit,
    onOpenApkInspector: ((File) -> Unit)? = null,
    onOpenSqliteViewer: ((File) -> Unit)? = null,
    onOpenHexViewer: ((File) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Nested archive stack
    val archiveStack = remember { mutableStateListOf<ArchiveStackItem>() }
    val currentArchiveItem = archiveStack.lastOrNull() ?: ArchiveStackItem(archiveFile, archiveFile.name, false)
    val activeArchiveFile = currentArchiveItem.file

    // Tree navigation state
    var rootNode by remember { mutableStateOf<ZipNode?>(null) }
    var currentNode by remember { mutableStateOf<ZipNode?>(null) }
    var rawEntries by remember { mutableStateOf<List<ArchiveEntryInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Search and Sort
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(ZipSortOption.NAME) }
    var sortAscending by remember { mutableStateOf(true) }
    var isGridView by remember { mutableStateOf(false) }

    // Multi-selection
    var isSelectMode by remember { mutableStateOf(false) }
    val selectedNodes = remember { mutableStateListOf<ZipNode>() }
    val isSelectionActive = isSelectMode || selectedNodes.isNotEmpty()
    val isWritableZip = remember(activeArchiveFile, currentArchiveItem.isTemporary) {
        !currentArchiveItem.isTemporary &&
            activeArchiveFile.extension.equals("zip", ignoreCase = true) &&
            activeArchiveFile.canWrite()
    }

    // Extraction & deletion progress
    var isExtracting by remember { mutableStateOf(false) }
    var extractProgress by remember { mutableFloatStateOf(0f) }
    var currentExtractName by remember { mutableStateOf("") }
    var extractJob by remember { mutableStateOf<Job?>(null) }
    var isDeletingEntries by remember { mutableStateOf(false) }

    // Dialogs, Menus & Password state
    var archivePassword by remember { mutableStateOf<String?>(null) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var pendingPasswordAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var showNormalMoreMenu by remember { mutableStateOf(false) }
    var showSelectionMoreMenu by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showArchiveInfoDialog by remember { mutableStateOf(false) }
    var testIntegrityResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showDeleteSelectedConfirm by remember { mutableStateOf(false) }
    var nodeToDeleteConfirm by remember { mutableStateOf<ZipNode?>(null) }

    var showExtractOptionsDialog by remember { mutableStateOf(false) }
    var extractFolderPickerMode by remember { mutableStateOf<Pair<ExtractMode, ZipNode?>?>(null) }
    var previewTextDialog by remember { mutableStateOf<Pair<ZipNode, String>?>(null) }
    var previewImageDialog by remember { mutableStateOf<Pair<ZipNode, ImageBitmap>?>(null) }
    var showDetailsDialog by remember { mutableStateOf<ZipNode?>(null) }
    var isPreviewLoading by remember { mutableStateOf(false) }

    val apksInZip = remember(rawEntries) {
        rawEntries.filter { !it.isDirectory && it.path.endsWith(".apk", ignoreCase = true) }
    }
    var showApksInZipDialog by remember { mutableStateOf(false) }
    var apkNodeToInstall by remember { mutableStateOf<ZipNode?>(null) }

    fun toggleNodeSelection(node: ZipNode) {
        if (selectedNodes.contains(node)) {
            selectedNodes.remove(node)
            if (selectedNodes.isEmpty()) isSelectMode = false
        } else {
            selectedNodes.add(node)
            isSelectMode = true
        }
    }

    fun clearZipSelection() {
        selectedNodes.clear()
        isSelectMode = false
    }

    fun selectAllDisplayed(displayed: List<ZipNode>) {
        selectedNodes.clear()
        selectedNodes.addAll(displayed)
        if (displayed.isNotEmpty()) isSelectMode = true
    }

    fun loadArchive(file: File, pwd: String? = archivePassword, restorePath: String? = null) {
        isLoading = true
        errorMessage = null
        clearZipSelection()
        coroutineScope.launch {
            val result = ArchiveManager.listArchiveEntries(file, pwd)
            if (result.isSuccess) {
                rawEntries = result.getOrNull() ?: emptyList()
                val tree = ZipTreeBuilder.buildTree(rawEntries)
                rootNode = tree
                currentNode = if (!restorePath.isNullOrEmpty()) {
                    findZipNodeByPath(tree, restorePath) ?: tree
                } else {
                    tree
                }
            } else {
                val err = result.exceptionOrNull()
                if (err is ArchivePasswordRequiredException) {
                    pendingPasswordAction = { loadArchive(file, archivePassword, restorePath) }
                    showPasswordPrompt = true
                } else {
                    errorMessage = err?.message ?: "Lỗi khi mở tệp nén"
                }
            }
            isLoading = false
        }
    }

    LaunchedEffect(activeArchiveFile) {
        loadArchive(activeArchiveFile, archivePassword)
    }

    DisposableEffect(Unit) {
        onDispose {
            ArchiveManager.cleanTempFiles(context.cacheDir)
        }
    }

    // Back handling: navigates up tree, then pops nested zip stack, then exits screen
    fun handleBack() {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
            return
        }
        if (isSelectionActive) {
            clearZipSelection()
            return
        }
        val cur = currentNode
        val parent = cur?.parent
        if (parent != null) {
            currentNode = parent
        } else if (archiveStack.size > 1) {
            val popped = archiveStack.removeAt(archiveStack.lastIndex)
            if (popped.isTemporary) {
                popped.file.delete()
            }
        } else {
            onBack()
        }
    }

    BackHandler(onBack = { handleBack() })

    // Open item preview
    fun openNode(node: ZipNode) {
        if (node.isDirectory) {
            currentNode = node
            return
        }

        val ext = node.extension
        when (ext) {
            "txt", "json", "xml", "ts", "tsx", "css", "bat", "dat", "kt", "java", "py", "c", "cpp", "h",
            "html", "md", "ini", "cfg", "properties", "yaml", "yml", "gradle", "sh" -> {
                isPreviewLoading = true
                coroutineScope.launch {
                    val res = ArchiveManager.readEntryText(activeArchiveFile, node.fullPath, password = archivePassword)
                    if (res.isSuccess) {
                        previewTextDialog = Pair(node, res.getOrNull() ?: "")
                    } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                        pendingPasswordAction = { openNode(node) }
                        showPasswordPrompt = true
                    } else {
                        snackbarHostState.showSnackbar("Không thể đọc tệp văn bản: ${res.exceptionOrNull()?.message}")
                    }
                    isPreviewLoading = false
                }
            }

            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> {
                isPreviewLoading = true
                coroutineScope.launch {
                    val res = ArchiveManager.readEntryBytes(activeArchiveFile, node.fullPath, password = archivePassword)
                    if (res.isSuccess) {
                        val bytes = res.getOrNull()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                previewImageDialog = Pair(node, bmp.asImageBitmap())
                            } else {
                                snackbarHostState.showSnackbar("Không thể giải mã hình ảnh")
                            }
                        }
                    } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                        pendingPasswordAction = { openNode(node) }
                        showPasswordPrompt = true
                    } else {
                        snackbarHostState.showSnackbar("Lỗi đọc hình ảnh: ${res.exceptionOrNull()?.message}")
                    }
                    isPreviewLoading = false
                }
            }

            "apk" -> {
                apkNodeToInstall = node
            }

            "zip", "jar", "obb", "7z", "tar" -> {
                // Nested archive explorer!
                isPreviewLoading = true
                coroutineScope.launch {
                    val res = ArchiveManager.extractNestedZip(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
                    if (res.isSuccess) {
                        val nestedFile = res.getOrThrow()
                        if (archiveStack.isEmpty()) {
                            archiveStack.add(ArchiveStackItem(archiveFile, archiveFile.name, false))
                        }
                        archiveStack.add(ArchiveStackItem(nestedFile, node.name, true))
                    } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                        pendingPasswordAction = { openNode(node) }
                        showPasswordPrompt = true
                    } else {
                        snackbarHostState.showSnackbar("Lỗi khi mở tệp nén con: ${res.exceptionOrNull()?.message}")
                    }
                    isPreviewLoading = false
                }
            }

            else -> {
                showDetailsDialog = node
            }
        }
    }

    // Extraction executor
    fun startExtraction(
        targetDir: File,
        mode: ExtractMode,
        targetNode: ZipNode? = null
    ) {
        showExtractOptionsDialog = false
        isExtracting = true
        extractProgress = 0f
        currentExtractName = ""

        extractJob = coroutineScope.launch {
            val res = when (mode) {
                ExtractMode.ALL -> {
                    ArchiveManager.extractAll(activeArchiveFile, targetDir, archivePassword) { p, name ->
                        extractProgress = p
                        currentExtractName = name
                    }
                }
                ExtractMode.CURRENT_DIR -> {
                    val prefix = targetNode?.fullPath ?: currentNode?.fullPath ?: ""
                    ArchiveManager.extractDirectory(activeArchiveFile, prefix, targetDir, archivePassword) { p, name ->
                        extractProgress = p
                        currentExtractName = name
                    }
                }
                ExtractMode.SELECTED -> {
                    val paths = selectedNodes.flatMap { collectAllArchiveEntryPaths(it) }.distinct()
                    ArchiveManager.extractEntries(activeArchiveFile, paths, targetDir, archivePassword) { p, name ->
                        extractProgress = p
                        currentExtractName = name
                    }
                }
                ExtractMode.SINGLE -> {
                    val paths = targetNode?.let { collectAllArchiveEntryPaths(it) } ?: emptyList()
                    ArchiveManager.extractEntries(activeArchiveFile, paths, targetDir, archivePassword) { p, name ->
                        extractProgress = p
                        currentExtractName = name
                    }
                }
            }

            if (res.isSuccess) {
                val count = res.getOrNull() ?: 0
                snackbarHostState.showSnackbar("Đã trích xuất thành công $count tệp vào: ${targetDir.name}")
                clearZipSelection()
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { startExtraction(targetDir, mode, targetNode) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi giải nén: ${res.exceptionOrNull()?.message}")
            }
            isExtracting = false
        }
    }

    fun deleteEntriesFromArchive(targets: List<ZipNode>) {
        if (targets.isEmpty()) return
        val currentPathBeforeDelete = currentNode?.fullPath
        isDeletingEntries = true
        coroutineScope.launch {
            val res = ArchiveManager.deleteEntriesFromZip(
                zipFile = activeArchiveFile,
                entryPaths = targets.map { it.fullPath },
                password = archivePassword
            )
            isDeletingEntries = false
            if (res.isSuccess) {
                val removed = res.getOrNull() ?: 0
                clearZipSelection()
                snackbarHostState.showSnackbar("Đã xóa $removed mục khỏi ${activeArchiveFile.name}")
                loadArchive(activeArchiveFile, archivePassword, restorePath = currentPathBeforeDelete)
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { deleteEntriesFromArchive(targets) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Không thể xóa khỏi ZIP: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun shareNodeViaTemp(node: ZipNode) {
        if (node.isDirectory) return
        coroutineScope.launch {
            isPreviewLoading = true
            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
            isPreviewLoading = false
            if (res.isSuccess) {
                FileUtils.shareFile(context, res.getOrThrow())
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { shareNodeViaTemp(node) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi chia sẻ tệp: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun openNodeWithExternalApp(node: ZipNode) {
        if (node.isDirectory) return
        coroutineScope.launch {
            isPreviewLoading = true
            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
            isPreviewLoading = false
            if (res.isSuccess) {
                val opened = FileUtils.openWithExternalApp(context, res.getOrThrow())
                if (!opened) {
                    snackbarHostState.showSnackbar("Không tìm thấy ứng dụng phù hợp để mở ${node.name}")
                }
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { openNodeWithExternalApp(node) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi mở tệp: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun installApkNode(node: ZipNode) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar("Đang giải nén và chuẩn bị cài đặt ${node.name}...")
            val res = ApkInstaller.extractAndInstallFromZip(context, activeArchiveFile, node.fullPath)
            if (res.isFailure) {
                snackbarHostState.showSnackbar("Lỗi cài APK: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun inspectApkNode(node: ZipNode) {
        coroutineScope.launch {
            isPreviewLoading = true
            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
            isPreviewLoading = false
            if (res.isSuccess) {
                onOpenApkInspector?.invoke(res.getOrThrow())
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { inspectApkNode(node) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi phân tích APK: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun openSqliteNode(node: ZipNode) {
        coroutineScope.launch {
            isPreviewLoading = true
            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
            isPreviewLoading = false
            if (res.isSuccess) {
                onOpenSqliteViewer?.invoke(res.getOrThrow())
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { openSqliteNode(node) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi mở SQLite: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun openHexNode(node: ZipNode) {
        coroutineScope.launch {
            isPreviewLoading = true
            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
            isPreviewLoading = false
            if (res.isSuccess) {
                onOpenHexViewer?.invoke(res.getOrThrow())
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { openHexNode(node) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi mở HEX: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun openNestedArchiveNode(node: ZipNode) {
        coroutineScope.launch {
            isPreviewLoading = true
            val res = ArchiveManager.extractNestedZip(context.cacheDir, activeArchiveFile, node.fullPath, archivePassword)
            isPreviewLoading = false
            if (res.isSuccess) {
                val nestedFile = res.getOrThrow()
                if (archiveStack.isEmpty()) {
                    archiveStack.add(ArchiveStackItem(archiveFile, archiveFile.name, false))
                }
                archiveStack.add(ArchiveStackItem(nestedFile, node.name, true))
            } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                pendingPasswordAction = { openNestedArchiveNode(node) }
                showPasswordPrompt = true
            } else {
                snackbarHostState.showSnackbar("Lỗi mở tệp nén con: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    val displayedNodes = remember(currentNode, searchQuery, isSearchActive, sortOption, sortAscending) {
        if (isSearchActive && searchQuery.isNotEmpty() && rootNode != null) {
            ZipTreeBuilder.searchNodes(rootNode!!, searchQuery)
        } else {
            val children = currentNode?.children?.values ?: emptyList()
            ZipTreeBuilder.sortChildren(children, sortOption, sortAscending)
        }
    }
    val allSelectedInDir = displayedNodes.isNotEmpty() && selectedNodes.size >= displayedNodes.size &&
        displayedNodes.all { selectedNodes.contains(it) }
    val defaultNewExtractFolder = remember(activeArchiveFile) {
        val parentDir = activeArchiveFile.parentFile ?: Environment.getExternalStorageDirectory()
        File(parentDir, activeArchiveFile.nameWithoutExtension)
    }
    val downloadsDir = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(color = CharcoalSurface) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    if (isSelectionActive) {
                        TopAppBar(
                            navigationIcon = {
                                IconButton(
                                    onClick = { clearZipSelection() },
                                    modifier = Modifier.testTag("zip_selection_close_button")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Đóng chọn nhiều", tint = TextPrimary)
                                }
                            },
                            title = {
                                Text(
                                    text = "Đã chọn ${selectedNodes.size}",
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 18.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            actions = {
                                IconButton(
                                    onClick = { showExtractOptionsDialog = true },
                                    enabled = selectedNodes.isNotEmpty(),
                                    modifier = Modifier.testTag("zip_selection_extract_button")
                                ) {
                                    Icon(
                                        Icons.Default.FolderZip,
                                        contentDescription = "Trích xuất đã chọn",
                                        tint = if (selectedNodes.isNotEmpty()) ElectricBlue else TextMuted
                                    )
                                }
                                if (isWritableZip) {
                                    IconButton(
                                        onClick = {
                                            if (selectedNodes.isNotEmpty()) {
                                                showDeleteSelectedConfirm = true
                                            }
                                        },
                                        enabled = selectedNodes.isNotEmpty(),
                                        modifier = Modifier.testTag("zip_selection_delete_button")
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Xóa mục đã chọn",
                                            tint = if (selectedNodes.isNotEmpty()) TextPrimary else TextMuted
                                        )
                                    }
                                }
                                Box {
                                    IconButton(
                                        onClick = { showSelectionMoreMenu = true },
                                        modifier = Modifier.testTag("zip_selection_more_menu")
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Tùy chọn chọn nhiều", tint = TextPrimary)
                                    }
                                    DropdownMenu(
                                        expanded = showSelectionMoreMenu,
                                        onDismissRequest = { showSelectionMoreMenu = false },
                                        modifier = Modifier.background(CharcoalSurfaceVariant)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Sắp xếp theo...", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = TextSecondary) },
                                            onClick = {
                                                showSelectionMoreMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Chọn tất cả", color = ElectricBlue, fontWeight = FontWeight.Medium) },
                                            leadingIcon = { Icon(Icons.Default.SelectAll, null, tint = ElectricBlue) },
                                            onClick = {
                                                showSelectionMoreMenu = false
                                                selectAllDisplayed(displayedNodes)
                                            }
                                        )
                                        if (allSelectedInDir) {
                                            DropdownMenuItem(
                                                text = { Text("Bỏ chọn tất cả", color = TextSecondary) },
                                                leadingIcon = { Icon(Icons.Default.ClearAll, null, tint = TextSecondary) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    clearZipSelection()
                                                }
                                            )
                                        }
                                        HorizontalDivider(color = DocumentsUIDivider)
                                        DropdownMenuItem(
                                            text = { Text("Trích xuất vào thư mục mới", color = ElectricBlue, fontWeight = FontWeight.Medium) },
                                            leadingIcon = { Icon(Icons.Default.CreateNewFolder, null, tint = ElectricBlue) },
                                            enabled = selectedNodes.isNotEmpty(),
                                            onClick = {
                                                showSelectionMoreMenu = false
                                                startExtraction(defaultNewExtractFolder, ExtractMode.SELECTED)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Giải nén đến thư mục...", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, tint = CyanAccent) },
                                            enabled = selectedNodes.isNotEmpty(),
                                            onClick = {
                                                showSelectionMoreMenu = false
                                                extractFolderPickerMode = Pair(ExtractMode.SELECTED, null)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Trích xuất ngay (Downloads)", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Download, null, tint = ColorSuccessGreen) },
                                            enabled = selectedNodes.isNotEmpty(),
                                            onClick = {
                                                showSelectionMoreMenu = false
                                                startExtraction(downloadsDir, ExtractMode.SELECTED)
                                            }
                                        )
                                        if (selectedNodes.size == 1) {
                                            val single = selectedNodes.first()
                                            HorizontalDivider(color = DocumentsUIDivider)
                                            DropdownMenuItem(
                                                text = { Text(if (single.isDirectory) "Mở thư mục" else "Mở / Xem trước", color = TextPrimary) },
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = ElectricBlue) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    clearZipSelection()
                                                    openNode(single)
                                                }
                                            )
                                            if (!single.isDirectory) {
                                                DropdownMenuItem(
                                                    text = { Text("Chia sẻ tệp này", color = CyanAccent) },
                                                    leadingIcon = { Icon(Icons.Default.Share, null, tint = CyanAccent) },
                                                    onClick = {
                                                        showSelectionMoreMenu = false
                                                        shareNodeViaTemp(single)
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("Xem thông tin", color = TextPrimary) },
                                                leadingIcon = { Icon(Icons.Default.Info, null, tint = TextSecondary) },
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    showDetailsDialog = single
                                                }
                                            )
                                        }
                                        if (isWritableZip) {
                                            HorizontalDivider(color = DocumentsUIDivider)
                                            DropdownMenuItem(
                                                text = { Text("Xóa khỏi ZIP", color = Color(0xFFEF5350)) },
                                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350)) },
                                                enabled = selectedNodes.isNotEmpty(),
                                                onClick = {
                                                    showSelectionMoreMenu = false
                                                    showDeleteSelectedConfirm = true
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = CharcoalSurfaceVariant)
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ZipDocumentBadgeIcon(modifier = Modifier.size(width = 24.dp, height = 30.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = currentArchiveItem.title,
                                            color = TextPrimary,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (archiveStack.size > 1) "Cấp độ ZIP lồng nhau #${archiveStack.size}" else "${FileItem.formatBytes(activeArchiveFile.length())} • ${rawEntries.size} mục",
                                            color = CyanAccent,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { handleBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = TextPrimary)
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearchActive = !isSearchActive }) {
                                    Icon(Icons.Default.Search, contentDescription = "Tìm kiếm", tint = if (isSearchActive) ElectricBlue else TextPrimary)
                                }
                                IconButton(onClick = { isGridView = !isGridView }) {
                                    Icon(if (isGridView) Icons.Default.ViewList else Icons.Default.GridView, contentDescription = "Kiểu xem", tint = TextPrimary)
                                }
                                if (apksInZip.isNotEmpty()) {
                                    IconButton(onClick = {
                                        if (apksInZip.size == 1) {
                                            val singleApk = apksInZip.first()
                                            coroutineScope.launch {
                                                snackbarHostState.showSnackbar("Đang giải nén và chuẩn bị cài đặt ${singleApk.name}...")
                                                val res = ApkInstaller.extractAndInstallFromZip(context, activeArchiveFile, singleApk.path)
                                                if (res.isFailure) {
                                                    snackbarHostState.showSnackbar("Lỗi: ${res.exceptionOrNull()?.message}")
                                                }
                                            }
                                        } else {
                                            showApksInZipDialog = true
                                        }
                                    }) {
                                        BadgedBox(
                                            badge = {
                                                Badge(containerColor = ColorSuccessGreen) {
                                                    Text("${apksInZip.size}", fontSize = 10.sp, color = Color.White)
                                                }
                                            }
                                        ) {
                                            Icon(Icons.Default.Android, contentDescription = "Cài đặt APK trong ZIP", tint = ColorSuccessGreen)
                                        }
                                    }
                                }
                                IconButton(onClick = { showExtractOptionsDialog = true }) {
                                    Icon(Icons.Default.FolderZip, contentDescription = "Trích xuất", tint = ElectricBlue)
                                }
                                Box {
                                    IconButton(
                                        onClick = { showNormalMoreMenu = true },
                                        modifier = Modifier.testTag("zip_topbar_more_menu")
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Menu tùy chọn tệp nén", tint = TextPrimary)
                                    }
                                    DropdownMenu(
                                        expanded = showNormalMoreMenu,
                                        onDismissRequest = { showNormalMoreMenu = false },
                                        modifier = Modifier.background(CharcoalSurfaceVariant)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Chọn tất cả (Chọn nhiều)", color = ElectricBlue, fontWeight = FontWeight.Medium) },
                                            leadingIcon = { Icon(Icons.Default.SelectAll, null, tint = ElectricBlue) },
                                            onClick = {
                                                showNormalMoreMenu = false
                                                if (displayedNodes.isNotEmpty()) {
                                                    selectAllDisplayed(displayedNodes)
                                                } else {
                                                    isSelectMode = true
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Sắp xếp theo...", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null, tint = TextSecondary) },
                                            onClick = {
                                                showNormalMoreMenu = false
                                                showSortDialog = true
                                            }
                                        )
                                        HorizontalDivider(color = DocumentsUIDivider)
                                        DropdownMenuItem(
                                            text = { Text("Giải nén tệp nén...", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.FolderZip, null, tint = ElectricBlue) },
                                            onClick = {
                                                showNormalMoreMenu = false
                                                showExtractOptionsDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Kiểm tra toàn vẹn (Test)", color = ColorSuccessGreen) },
                                            leadingIcon = { Icon(Icons.Default.Verified, null, tint = ColorSuccessGreen) },
                                            onClick = {
                                                showNormalMoreMenu = false
                                                coroutineScope.launch {
                                                    val res = ArchiveManager.testArchiveIntegrity(activeArchiveFile, archivePassword)
                                                    if (res.isSuccess) {
                                                        testIntegrityResult = true to (res.getOrNull() ?: "Toàn vẹn tốt")
                                                    } else if (res.exceptionOrNull() is ArchivePasswordRequiredException) {
                                                        pendingPasswordAction = {
                                                            coroutineScope.launch {
                                                                val retry = ArchiveManager.testArchiveIntegrity(activeArchiveFile, archivePassword)
                                                                testIntegrityResult = if (retry.isSuccess) {
                                                                    true to (retry.getOrNull() ?: "Toàn vẹn tốt")
                                                                } else {
                                                                    false to (retry.exceptionOrNull()?.message ?: "Lỗi kiểm tra")
                                                                }
                                                            }
                                                        }
                                                        showPasswordPrompt = true
                                                    } else {
                                                        testIntegrityResult = false to (res.exceptionOrNull()?.message ?: "Lỗi kiểm tra tệp nén")
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Chia sẻ tệp nén", color = CyanAccent) },
                                            leadingIcon = { Icon(Icons.Default.Share, null, tint = CyanAccent) },
                                            onClick = {
                                                showNormalMoreMenu = false
                                                val ok = FileUtils.shareFile(context, activeArchiveFile)
                                                if (!ok) {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar("Không thể chia sẻ tệp nén này")
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Thông tin tệp nén", color = TextPrimary) },
                                            leadingIcon = { Icon(Icons.Default.Info, null, tint = TextSecondary) },
                                            onClick = {
                                                showNormalMoreMenu = false
                                                showArchiveInfoDialog = true
                                            }
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = CharcoalSurface)
                        )
                    }

                    // Search row if active
                    if (isSearchActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Tìm kiếm tệp trong file nén...", fontSize = 13.sp, color = TextMuted) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                singleLine = true,
                                shape = RoundedCornerShape(20.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ElectricBlue,
                                    unfocusedBorderColor = DocumentsUIDivider,
                                    focusedContainerColor = CharcoalCard,
                                    unfocusedContainerColor = CharcoalCard,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Xóa", tint = TextMuted)
                                }
                            }
                        }
                    }

                    // Breadcrumbs bar inside ZIP
                    val cur = currentNode
                    if (cur != null && !isSearchActive) {
                        val crumbs = cur.getBreadcrumbs()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CharcoalBackground)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            crumbs.forEachIndexed { index, crumb ->
                                val isCurrent = index == crumbs.lastIndex
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        currentNode = crumb
                                        clearZipSelection()
                                    }
                                ) {
                                    Text(
                                        text = if (crumb.name == "/") "Gốc ZIP" else crumb.name,
                                        color = if (isCurrent) ElectricBlue else TextMuted,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.5.sp
                                    )
                                    if (!isCurrent) {
                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Sort & Filter bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isSearchActive) "Kết quả tìm kiếm" else "${currentNode?.directChildrenCount ?: 0} mục trong thư mục này",
                            fontSize = 11.5.sp,
                            color = TextMuted
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when (sortOption) {
                                    ZipSortOption.NAME -> "Tên"
                                    ZipSortOption.SIZE -> "Kích thước"
                                    ZipSortOption.TYPE -> "Loại"
                                    ZipSortOption.DATE -> "Ngày"
                                },
                                fontSize = 11.5.sp,
                                color = ElectricBlue,
                                modifier = Modifier.clickable { showSortDialog = true }
                            )
                            IconButton(
                                onClick = { sortAscending = !sortAscending },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (sortAscending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = "Thứ tự sắp xếp",
                                    tint = ElectricBlue,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = DocumentsUIDivider, thickness = 0.8.dp)
                }
            }
        },
        containerColor = CharcoalBackground
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = ElectricBlue)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Đang phân tích cấu trúc tệp nén...", color = TextMuted, fontSize = 13.sp)
                    }
                }

                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF5350), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(errorMessage ?: "", color = TextPrimary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { loadArchive(activeArchiveFile) }) {
                            Text("Thử lại")
                        }
                    }
                }

                else -> {
                    if (displayedNodes.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.FolderOpen, null, tint = TextMuted, modifier = Modifier.size(44.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Thư mục rỗng hoặc không có kết quả phù hợp", color = TextMuted, fontSize = 13.sp)
                        }
                    } else if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(displayedNodes, key = { it.fullPath }) { node ->
                                val isSelected = selectedNodes.contains(node)
                                ZipGridNodeItem(
                                    node = node,
                                    archiveFile = activeArchiveFile,
                                    password = archivePassword,
                                    isSelected = isSelected,
                                    isWritableZip = isWritableZip,
                                    onClick = {
                                        if (isSelectionActive) {
                                            toggleNodeSelection(node)
                                        } else {
                                            openNode(node)
                                        }
                                    },
                                    onLongClick = { toggleNodeSelection(node) },
                                    onToggleSelect = { toggleNodeSelection(node) },
                                    onOpen = { openNode(node) },
                                    onInstallApk = { installApkNode(node) },
                                    onInspectApk = { inspectApkNode(node) },
                                    onBrowseNestedArchive = { openNestedArchiveNode(node) },
                                    onOpenSqlite = { openSqliteNode(node) },
                                    onOpenHex = { openHexNode(node) },
                                    onOpenWithExternal = { openNodeWithExternalApp(node) },
                                    onExtractNow = {
                                        startExtraction(
                                            downloadsDir,
                                            if (node.isDirectory) ExtractMode.CURRENT_DIR else ExtractMode.SINGLE,
                                            node
                                        )
                                    },
                                    onExtractToFolder = {
                                        extractFolderPickerMode = Pair(
                                            if (node.isDirectory) ExtractMode.CURRENT_DIR else ExtractMode.SINGLE,
                                            node
                                        )
                                    },
                                    onShare = { shareNodeViaTemp(node) },
                                    onCopyPath = {
                                        clipboardManager.setText(AnnotatedString(node.fullPath))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Đã sao chép đường dẫn trong ZIP")
                                        }
                                    },
                                    onShowDetails = { showDetailsDialog = node },
                                    onDeleteFromZip = { nodeToDeleteConfirm = node }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(displayedNodes, key = { it.fullPath }) { node ->
                                val isSelected = selectedNodes.contains(node)
                                ZipListNodeItem(
                                    node = node,
                                    archiveFile = activeArchiveFile,
                                    password = archivePassword,
                                    isSelected = isSelected,
                                    isWritableZip = isWritableZip,
                                    onClick = {
                                        if (isSelectionActive) {
                                            toggleNodeSelection(node)
                                        } else {
                                            openNode(node)
                                        }
                                    },
                                    onLongClick = { toggleNodeSelection(node) },
                                    onToggleSelect = { toggleNodeSelection(node) },
                                    onOpen = { openNode(node) },
                                    onInstallApk = { installApkNode(node) },
                                    onInspectApk = { inspectApkNode(node) },
                                    onBrowseNestedArchive = { openNestedArchiveNode(node) },
                                    onOpenSqlite = { openSqliteNode(node) },
                                    onOpenHex = { openHexNode(node) },
                                    onOpenWithExternal = { openNodeWithExternalApp(node) },
                                    onExtractNow = {
                                        startExtraction(
                                            downloadsDir,
                                            if (node.isDirectory) ExtractMode.CURRENT_DIR else ExtractMode.SINGLE,
                                            node
                                        )
                                    },
                                    onExtractToFolder = {
                                        extractFolderPickerMode = Pair(
                                            if (node.isDirectory) ExtractMode.CURRENT_DIR else ExtractMode.SINGLE,
                                            node
                                        )
                                    },
                                    onShare = { shareNodeViaTemp(node) },
                                    onCopyPath = {
                                        clipboardManager.setText(AnnotatedString(node.fullPath))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Đã sao chép đường dẫn trong ZIP")
                                        }
                                    },
                                    onShowDetails = { showDetailsDialog = node },
                                    onDeleteFromZip = { nodeToDeleteConfirm = node }
                                )
                            }
                        }
                    }
                }
            }

            // Preview or Deletion Loading Overlay
            if (isPreviewLoading || isDeletingEntries) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black.copy(alpha = 0.5f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CharcoalCard),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = if (isDeletingEntries) "Đang xóa mục khỏi tệp ZIP..." else "Đang đọc luồng tệp trong ZIP...",
                                    color = TextPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Text File In-Archive Preview Dialog
    previewTextDialog?.let { (node, content) ->
        AlertDialog(
            onDismissRequest = { previewTextDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Article, null, tint = ElectricBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(node.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(FileItem.formatBytes(node.size), color = CyanAccent, fontSize = 11.sp)
                    }
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .background(CharcoalBackground, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = content,
                        color = TextPrimary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        startExtraction(downloads, ExtractMode.SINGLE, node)
                        previewTextDialog = null
                    }
                ) {
                    Text("Trích xuất tệp này", color = ElectricBlue)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(content))
                        coroutineScope.launch { snackbarHostState.showSnackbar("Đã sao chép nội dung vào khay nhớ tạm") }
                    }) {
                        Text("Sao chép", color = TextMuted)
                    }
                    TextButton(onClick = { previewTextDialog = null }) {
                        Text("Đóng", color = TextMuted)
                    }
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Image In-Archive Preview Dialog
    previewImageDialog?.let { (node, bmp) ->
        AlertDialog(
            onDismissRequest = { previewImageDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, null, tint = Color(0xFFF48FB1))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(node.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .background(CharcoalBackground, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp,
                            contentDescription = node.name,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${bmp.width} × ${bmp.height} • ${FileItem.formatBytes(node.size)}",
                        color = CyanAccent,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                        startExtraction(downloads, ExtractMode.SINGLE, node)
                        previewImageDialog = null
                    }
                ) {
                    Text("Trích xuất ảnh", color = ElectricBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { previewImageDialog = null }) {
                    Text("Đóng", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Node Details / Action Dialog
    showDetailsDialog?.let { node ->
        AlertDialog(
            onDismissRequest = { showDetailsDialog = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (node.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (node.isDirectory) Color(0xFFFFB300) else ElectricBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(node.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Đường dẫn trong ZIP:", node.fullPath)
                    if (node.isDirectory) {
                        DetailRow("Số file con:", "${node.fileCount} tệp")
                        DetailRow("Số thư mục con:", "${node.dirCount} thư mục")
                        DetailRow("Tổng dung lượng:", FileItem.formatBytes(node.totalSize))
                    } else {
                        DetailRow("Kích thước gốc:", FileItem.formatBytes(node.size))
                        DetailRow("Kích thước nén:", FileItem.formatBytes(node.compressedSize))
                        DetailRow("Tỷ lệ nén:", "${String.format("%.1f", node.compressionRatio)}%")
                        DetailRow("Phần mở rộng:", node.extension.ifEmpty { "Không có" })
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = DocumentsUIDivider)
                    Spacer(modifier = Modifier.height(8.dp))

                    // External viewers if applicable
                    val ext = node.extension
                    if (!node.isDirectory) {
                        when (ext) {
                            "apk" -> {
                                Button(
                                    onClick = {
                                        showDetailsDialog = null
                                        coroutineScope.launch {
                                            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath)
                                            if (res.isSuccess) {
                                                onOpenApkInspector?.invoke(res.getOrThrow())
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = ColorSuccessGreen)
                                ) {
                                    Icon(Icons.Default.Android, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Mở bằng APK Inspector")
                                }
                            }
                            "db", "sqlite" -> {
                                Button(
                                    onClick = {
                                        showDetailsDialog = null
                                        coroutineScope.launch {
                                            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath)
                                            if (res.isSuccess) {
                                                onOpenSqliteViewer?.invoke(res.getOrThrow())
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                                ) {
                                    Icon(Icons.Default.Storage, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Mở bằng SQLite Viewer")
                                }
                            }
                            "bin", "dat", "hex" -> {
                                Button(
                                    onClick = {
                                        showDetailsDialog = null
                                        coroutineScope.launch {
                                            val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, node.fullPath)
                                            if (res.isSuccess) {
                                                onOpenHexViewer?.invoke(res.getOrThrow())
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                                ) {
                                    Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Mở bằng HEX Viewer")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            extractFolderPickerMode = Pair(
                                if (node.isDirectory) ExtractMode.CURRENT_DIR else ExtractMode.SINGLE,
                                node
                            )
                            showDetailsDialog = null
                        }
                    ) {
                        Text("Chọn thư mục...", color = CyanAccent)
                    }
                    TextButton(
                        onClick = {
                            val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                            startExtraction(downloads, if (node.isDirectory) ExtractMode.CURRENT_DIR else ExtractMode.SINGLE, node)
                            showDetailsDialog = null
                        }
                    ) {
                        Text("Trích xuất ngay", color = ElectricBlue)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetailsDialog = null }) {
                    Text("Đóng", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Extraction Options Dialog
    if (showExtractOptionsDialog) {
        val parentDir = activeArchiveFile.parentFile ?: android.os.Environment.getExternalStorageDirectory()
        val defaultNewFolder = File(parentDir, activeArchiveFile.nameWithoutExtension)

        AlertDialog(
            onDismissRequest = { showExtractOptionsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderZip, null, tint = ElectricBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tùy chọn trích xuất", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Chọn phương thức hoặc thư mục đích để giải nén:", color = TextMuted, fontSize = 12.5.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    ExtractOptionCard(
                        title = "Trích xuất vào thư mục mới",
                        subtitle = "${defaultNewFolder.name}/ (Khuyên dùng)",
                        icon = Icons.Default.CreateNewFolder,
                        onClick = { startExtraction(defaultNewFolder, ExtractMode.ALL) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ExtractOptionCard(
                        title = "Trích xuất tại đây",
                        subtitle = parentDir.name,
                        icon = Icons.Default.Folder,
                        onClick = { startExtraction(parentDir, ExtractMode.ALL) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    ExtractOptionCard(
                        title = "Giải nén đến thư mục khác...",
                        subtitle = "Chọn bất kỳ thư mục nào trên thiết bị",
                        icon = Icons.AutoMirrored.Filled.DriveFileMove,
                        onClick = {
                            showExtractOptionsDialog = false
                            val mode = if (selectedNodes.isNotEmpty()) ExtractMode.SELECTED else ExtractMode.ALL
                            extractFolderPickerMode = Pair(mode, null)
                        }
                    )

                    if (currentNode != null && currentNode != rootNode) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ExtractOptionCard(
                            title = "Trích xuất thư mục hiện tại trong ZIP",
                            subtitle = currentNode?.name ?: "",
                            icon = Icons.Default.FolderOpen,
                            onClick = {
                                val out = File(parentDir, currentNode?.name ?: "extracted")
                                startExtraction(out, ExtractMode.CURRENT_DIR)
                            }
                        )
                    }

                    if (selectedNodes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        ExtractOptionCard(
                            title = "Trích xuất ${selectedNodes.size} mục đã chọn",
                            subtitle = "Giải nén vào thư mục mới",
                            icon = Icons.Default.CheckCircle,
                            onClick = { startExtraction(defaultNewFolder, ExtractMode.SELECTED) }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExtractOptionsDialog = false }) {
                    Text("Hủy", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Folder Picker for extracting to any custom folder
    extractFolderPickerMode?.let { (mode, targetNode) ->
        FolderPickerDialog(
            title = "Chọn thư mục giải nén",
            initialDir = activeArchiveFile.parentFile ?: android.os.Environment.getExternalStorageDirectory(),
            onDismiss = { extractFolderPickerMode = null },
            onFolderSelected = { chosenDir ->
                extractFolderPickerMode = null
                startExtraction(chosenDir, mode, targetNode)
            }
        )
    }

    // Password Prompt Dialog for encrypted archives
    if (showPasswordPrompt) {
        AlertDialog(
            onDismissRequest = {
                showPasswordPrompt = false
                pendingPasswordAction = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, tint = ColorWarningAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tệp nén được bảo vệ", color = TextPrimary, fontSize = 15.5.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("Nhập mật khẩu cho ${activeArchiveFile.name}:", color = TextSecondary, fontSize = 12.5.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text("Mật khẩu") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricBlue,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        archivePassword = passwordInput
                        showPasswordPrompt = false
                        val action = pendingPasswordAction
                        pendingPasswordAction = null
                        action?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("Xác nhận")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordPrompt = false
                    pendingPasswordAction = null
                }) {
                    Text("Hủy", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Extraction In-Progress Dialog
    if (isExtracting) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = ElectricBlue, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Đang trích xuất tệp nén...", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { extractProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = ElectricBlue,
                        trackColor = DocumentsUIDivider,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = currentExtractName.substringAfterLast('/'),
                            color = TextMuted,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(extractProgress * 100).toInt()}%",
                            color = ElectricBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    extractJob?.cancel()
                    isExtracting = false
                }) {
                    Text("Hủy bỏ", color = Color(0xFFEF5350))
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Direct APK Install / Inspect Dialog from inside ZIP
    apkNodeToInstall?.let { node ->
        AlertDialog(
            onDismissRequest = { apkNodeToInstall = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Android, contentDescription = null, tint = ColorSuccessGreen, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = node.name,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${FileItem.formatBytes(node.size)} (Nén: ${FileItem.formatBytes(node.compressedSize)})",
                            color = CyanAccent,
                            fontSize = 11.5.sp
                        )
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Tệp này là ứng dụng Android APK nằm trong file nén. Bạn có thể cài đặt trực tiếp, kiểm tra phân tích mã nguồn hoặc duyệt các tệp bên trong.",
                        color = TextSecondary,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val apkPath = node.fullPath
                            apkNodeToInstall = null
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Đang giải nén và khởi động trình cài đặt APK...")
                                val res = ApkInstaller.extractAndInstallFromZip(context, activeArchiveFile, apkPath)
                                if (res.isFailure) {
                                    snackbarHostState.showSnackbar("Không thể cài APK: ${res.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ColorSuccessGreen),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cài đặt APK này ngay", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val apkPath = node.fullPath
                            apkNodeToInstall = null
                            coroutineScope.launch {
                                isPreviewLoading = true
                                val res = ArchiveManager.extractEntryToTemp(context.cacheDir, activeArchiveFile, apkPath, archivePassword)
                                isPreviewLoading = false
                                if (res.isSuccess) {
                                    onOpenApkInspector?.invoke(res.getOrThrow())
                                } else {
                                    snackbarHostState.showSnackbar("Lỗi phân tích: ${res.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = ElectricBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Phân tích APK (Inspector)", color = ElectricBlue)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            val apkPath = node.fullPath
                            apkNodeToInstall = null
                            coroutineScope.launch {
                                isPreviewLoading = true
                                val res = ArchiveManager.extractNestedZip(context.cacheDir, activeArchiveFile, apkPath)
                                isPreviewLoading = false
                                if (res.isSuccess) {
                                    val nestedFile = res.getOrThrow()
                                    if (archiveStack.isEmpty()) {
                                        archiveStack.add(ArchiveStackItem(archiveFile, archiveFile.name, false))
                                    }
                                    archiveStack.add(ArchiveStackItem(nestedFile, node.name, true))
                                } else {
                                    snackbarHostState.showSnackbar("Lỗi duyệt APK: ${res.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp), tint = TextSecondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Duyệt cấu trúc bên trong APK", color = TextSecondary)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { apkNodeToInstall = null }) {
                    Text("Đóng", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // List of All APKs inside current ZIP Dialog
    if (showApksInZipDialog) {
        AlertDialog(
            onDismissRequest = { showApksInZipDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Android, contentDescription = null, tint = ColorSuccessGreen)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Tệp APK trong file nén (${apksInZip.size})", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(apksInZip) { apkInfo ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CharcoalSurface),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val apkItemBmp by produceState<Bitmap?>(initialValue = null, activeArchiveFile.path, apkInfo.path) {
                                    value = FileThumbnailLoader.loadArchiveEntryApkThumbnail(context, activeArchiveFile, apkInfo.path, archivePassword)
                                }
                                val itemBmp = apkItemBmp
                                if (itemBmp != null) {
                                    Image(
                                        bitmap = itemBmp.asImageBitmap(),
                                        contentDescription = apkInfo.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                } else {
                                    Icon(Icons.Default.Android, null, tint = ColorSuccessGreen, modifier = Modifier.size(28.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = apkInfo.name,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${FileItem.formatBytes(apkInfo.size)} • ${apkInfo.path}",
                                        color = TextMuted,
                                        fontSize = 10.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        showApksInZipDialog = false
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Đang trích xuất và khởi động cài đặt ${apkInfo.name}...")
                                            val res = ApkInstaller.extractAndInstallFromZip(context, activeArchiveFile, apkInfo.path)
                                            if (res.isFailure) {
                                                snackbarHostState.showSnackbar("Lỗi: ${res.exceptionOrNull()?.message}")
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ColorSuccessGreen),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Cài đặt", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showApksInZipDialog = false }) {
                    Text("Đóng", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Sort Options Dialog
    if (showSortDialog) {
        AlertDialog(
            onDismissRequest = { showSortDialog = false },
            title = {
                Text("Sắp xếp tệp trong nén", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val options = listOf(
                        ZipSortOption.NAME to "Theo Tên (A → Z)",
                        ZipSortOption.SIZE to "Theo Kích thước",
                        ZipSortOption.TYPE to "Theo Loại tệp",
                        ZipSortOption.DATE to "Theo Ngày chỉnh sửa"
                    )
                    options.forEach { (opt, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    sortOption = opt
                                    showSortDialog = false
                                }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = sortOption == opt,
                                onClick = {
                                    sortOption = opt
                                    showSortDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                    HorizontalDivider(color = DocumentsUIDivider, modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { sortAscending = !sortAscending }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Thứ tự tăng dần", color = TextPrimary, fontSize = 14.sp)
                        Switch(
                            checked = sortAscending,
                            onCheckedChange = { sortAscending = it }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSortDialog = false }) {
                    Text("Đóng", color = ElectricBlue)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Archive Info Dialog
    if (showArchiveInfoDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveInfoDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZipDocumentBadgeIcon(modifier = Modifier.size(width = 22.dp, height = 28.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Thông tin tệp nén", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DetailRow("Tên tệp:", currentArchiveItem.title)
                    DetailRow("Dung lượng file nén:", FileItem.formatBytes(activeArchiveFile.length()))
                    DetailRow("Tổng số mục:", "${rawEntries.size} mục")
                    DetailRow("Thư mục hiện tại:", if (currentNode?.name == "/") "Gốc ZIP" else currentNode?.name ?: "")
                    DetailRow("Đường dẫn:", activeArchiveFile.absolutePath)
                }
            },
            confirmButton = {
                TextButton(onClick = { showArchiveInfoDialog = false }) {
                    Text("Đóng", color = ElectricBlue)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Integrity Check Result Dialog
    testIntegrityResult?.let { (isOk, msg) ->
        AlertDialog(
            onDismissRequest = { testIntegrityResult = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isOk) Icons.Default.Verified else Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = if (isOk) ColorSuccessGreen else Color(0xFFEF5350)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isOk) "Kiểm tra toàn vẹn" else "Phát hiện lỗi",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(msg, color = TextSecondary, fontSize = 13.5.sp)
            },
            confirmButton = {
                TextButton(onClick = { testIntegrityResult = null }) {
                    Text("Đóng", color = ElectricBlue)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Confirm Delete Selected Entries from ZIP
    if (showDeleteSelectedConfirm && selectedNodes.isNotEmpty()) {
        val targetsSnapshot = selectedNodes.toList()
        AlertDialog(
            onDismissRequest = { showDeleteSelectedConfirm = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF5350))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Xóa khỏi tệp ZIP?", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Bạn có chắc muốn xóa ${targetsSnapshot.size} mục đã chọn khỏi ${activeArchiveFile.name}? Hành động này không thể hoàn tác.",
                    color = TextSecondary,
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteSelectedConfirm = false
                        deleteEntriesFromArchive(targetsSnapshot)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) {
                    Text("Xóa")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSelectedConfirm = false }) {
                    Text("Hủy", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }

    // Confirm Delete Single Entry from ZIP
    nodeToDeleteConfirm?.let { target ->
        AlertDialog(
            onDismissRequest = { nodeToDeleteConfirm = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = Color(0xFFEF5350))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Xóa mục khỏi ZIP?", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Bạn có chắc muốn xóa \"${target.name}\" khỏi ${activeArchiveFile.name}?",
                    color = TextSecondary,
                    fontSize = 13.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        nodeToDeleteConfirm = null
                        deleteEntriesFromArchive(listOf(target))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) {
                    Text("Xóa")
                }
            },
            dismissButton = {
                TextButton(onClick = { nodeToDeleteConfirm = null }) {
                    Text("Hủy", color = TextMuted)
                }
            },
            containerColor = CharcoalCard
        )
    }
}

private fun collectAllArchiveEntryPaths(node: ZipNode): List<String> {
    if (!node.isDirectory) return listOf(node.fullPath)
    val childPaths = mutableListOf<String>()
    node.children.values.forEach { child ->
        childPaths.addAll(collectAllArchiveEntryPaths(child))
    }
    return if (childPaths.isEmpty()) listOf(node.fullPath) else childPaths
}

private fun findZipNodeByPath(root: ZipNode, targetPath: String): ZipNode? {
    val cleanTarget = targetPath.trim('/')
    if (cleanTarget.isEmpty()) return root
    var cur: ZipNode = root
    val segments = cleanTarget.split('/').filter { it.isNotEmpty() }
    for (seg in segments) {
        cur = cur.children[seg] ?: return cur
    }
    return cur
}

enum class ExtractMode {
    ALL,
    CURRENT_DIR,
    SELECTED,
    SINGLE
}

@Composable
private fun ZipNodeActionsDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    node: ZipNode,
    isSelected: Boolean,
    isWritableZip: Boolean,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onInstallApk: () -> Unit,
    onInspectApk: () -> Unit,
    onBrowseNestedArchive: () -> Unit,
    onOpenSqlite: () -> Unit,
    onOpenHex: () -> Unit,
    onOpenWithExternal: () -> Unit,
    onExtractNow: () -> Unit,
    onExtractToFolder: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onShowDetails: () -> Unit,
    onDeleteFromZip: () -> Unit
) {
    val ext = node.extension.lowercase()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(CharcoalSurfaceVariant)
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    if (isSelected) "Bỏ chọn" else "Chọn mục này",
                    color = ElectricBlue,
                    fontWeight = FontWeight.Medium
                )
            },
            leadingIcon = {
                Icon(
                    if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null,
                    tint = ElectricBlue
                )
            },
            onClick = {
                onDismiss()
                onToggleSelect()
            }
        )
        DropdownMenuItem(
            text = { Text(if (node.isDirectory) "Mở thư mục" else "Mở / Xem trước", color = TextPrimary) },
            leadingIcon = {
                Icon(
                    if (node.isDirectory) Icons.Default.FolderOpen else Icons.AutoMirrored.Filled.OpenInNew,
                    null,
                    tint = CyanAccent
                )
            },
            onClick = {
                onDismiss()
                onOpen()
            }
        )
        if (!node.isDirectory) {
            if (ext == "apk") {
                DropdownMenuItem(
                    text = { Text("Cài đặt APK ngay", color = ColorSuccessGreen, fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(Icons.Default.Download, null, tint = ColorSuccessGreen) },
                    onClick = {
                        onDismiss()
                        onInstallApk()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Phân tích APK (Inspector)", color = ElectricBlue) },
                    leadingIcon = { Icon(Icons.Default.Android, null, tint = ElectricBlue) },
                    onClick = {
                        onDismiss()
                        onInspectApk()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Duyệt bên trong APK", color = TextPrimary) },
                    leadingIcon = { Icon(Icons.Default.FolderZip, null, tint = TextSecondary) },
                    onClick = {
                        onDismiss()
                        onBrowseNestedArchive()
                    }
                )
            } else if (ext in setOf("zip", "jar", "obb", "7z", "tar")) {
                DropdownMenuItem(
                    text = { Text("Duyệt bên trong tệp nén", color = ElectricBlue) },
                    leadingIcon = { Icon(Icons.Default.FolderZip, null, tint = ElectricBlue) },
                    onClick = {
                        onDismiss()
                        onBrowseNestedArchive()
                    }
                )
            } else if (ext in setOf("db", "sqlite", "sqlite3")) {
                DropdownMenuItem(
                    text = { Text("Mở bằng SQLite Viewer", color = ElectricBlue) },
                    leadingIcon = { Icon(Icons.Default.Storage, null, tint = ElectricBlue) },
                    onClick = {
                        onDismiss()
                        onOpenSqlite()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Mở bằng HEX Viewer", color = TextPrimary) },
                leadingIcon = { Icon(Icons.Default.Code, null, tint = CyanAccent) },
                onClick = {
                    onDismiss()
                    onOpenHex()
                }
            )
            DropdownMenuItem(
                text = { Text("Mở bằng ứng dụng khác...", color = TextPrimary) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = TextSecondary) },
                onClick = {
                    onDismiss()
                    onOpenWithExternal()
                }
            )
        }
        HorizontalDivider(color = DocumentsUIDivider)
        DropdownMenuItem(
            text = { Text("Trích xuất ngay (Downloads)", color = ElectricBlue, fontWeight = FontWeight.Medium) },
            leadingIcon = { Icon(Icons.Default.Download, null, tint = ElectricBlue) },
            onClick = {
                onDismiss()
                onExtractNow()
            }
        )
        DropdownMenuItem(
            text = { Text("Giải nén đến thư mục...", color = TextPrimary) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, tint = CyanAccent) },
            onClick = {
                onDismiss()
                onExtractToFolder()
            }
        )
        if (!node.isDirectory) {
            DropdownMenuItem(
                text = { Text("Chia sẻ tệp này", color = TextPrimary) },
                leadingIcon = { Icon(Icons.Default.Share, null, tint = CyanAccent) },
                onClick = {
                    onDismiss()
                    onShare()
                }
            )
        }
        DropdownMenuItem(
            text = { Text("Sao chép đường dẫn trong ZIP", color = TextPrimary) },
            leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = TextSecondary) },
            onClick = {
                onDismiss()
                onCopyPath()
            }
        )
        DropdownMenuItem(
            text = { Text("Xem thông tin", color = TextPrimary) },
            leadingIcon = { Icon(Icons.Default.Info, null, tint = TextSecondary) },
            onClick = {
                onDismiss()
                onShowDetails()
            }
        )
        if (isWritableZip) {
            HorizontalDivider(color = DocumentsUIDivider)
            DropdownMenuItem(
                text = { Text("Xóa khỏi ZIP", color = Color(0xFFEF5350)) },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF5350)) },
                onClick = {
                    onDismiss()
                    onDeleteFromZip()
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZipListNodeItem(
    node: ZipNode,
    archiveFile: File,
    password: String?,
    isSelected: Boolean,
    isWritableZip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onInstallApk: () -> Unit,
    onInspectApk: () -> Unit,
    onBrowseNestedArchive: () -> Unit,
    onOpenSqlite: () -> Unit,
    onOpenHex: () -> Unit,
    onOpenWithExternal: () -> Unit,
    onExtractNow: () -> Unit,
    onExtractToFolder: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onShowDetails: () -> Unit,
    onDeleteFromZip: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) ElectricBlue.copy(alpha = 0.22f) else CharcoalSurface,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = when {
                isSelected -> ElectricBlue
                node.isDirectory -> GoldAccent.copy(alpha = 0.30f)
                else -> CharcoalSurfaceHighlight
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(ElectricBlue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Đã chọn",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                ArchiveEntryThumbnailIcon(
                    node = node,
                    archiveFile = archiveFile,
                    password = password,
                    isGrid = false,
                    modifier = Modifier.size(38.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = if (node.isDirectory) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (node.isDirectory) {
                        "${node.directChildrenCount} mục (${node.fileCount} tệp)"
                    } else {
                        "${FileItem.formatBytes(node.size)} • Nén: ${FileItem.formatBytes(node.compressedSize)}"
                    },
                    color = TextMuted,
                    fontSize = 11.5.sp
                )
            }

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("zip_item_more_menu")
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Thao tác ${node.name}",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                ZipNodeActionsDropdownMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    node = node,
                    isSelected = isSelected,
                    isWritableZip = isWritableZip,
                    onToggleSelect = onToggleSelect,
                    onOpen = onOpen,
                    onInstallApk = onInstallApk,
                    onInspectApk = onInspectApk,
                    onBrowseNestedArchive = onBrowseNestedArchive,
                    onOpenSqlite = onOpenSqlite,
                    onOpenHex = onOpenHex,
                    onOpenWithExternal = onOpenWithExternal,
                    onExtractNow = onExtractNow,
                    onExtractToFolder = onExtractToFolder,
                    onShare = onShare,
                    onCopyPath = onCopyPath,
                    onShowDetails = onShowDetails,
                    onDeleteFromZip = onDeleteFromZip
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZipGridNodeItem(
    node: ZipNode,
    archiveFile: File,
    password: String?,
    isSelected: Boolean,
    isWritableZip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onOpen: () -> Unit,
    onInstallApk: () -> Unit,
    onInspectApk: () -> Unit,
    onBrowseNestedArchive: () -> Unit,
    onOpenSqlite: () -> Unit,
    onOpenHex: () -> Unit,
    onOpenWithExternal: () -> Unit,
    onExtractNow: () -> Unit,
    onExtractToFolder: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onShowDetails: () -> Unit,
    onDeleteFromZip: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) ElectricBlue.copy(alpha = 0.22f) else CharcoalCard
        ),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = when {
                isSelected -> ElectricBlue
                node.isDirectory -> GoldAccent.copy(alpha = 0.30f)
                else -> CharcoalSurfaceHighlight
            }
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(ElectricBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Đã chọn",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(22.dp))
                }

                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("zip_grid_item_more_menu")
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Thao tác ${node.name}",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    ZipNodeActionsDropdownMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        node = node,
                        isSelected = isSelected,
                        isWritableZip = isWritableZip,
                        onToggleSelect = onToggleSelect,
                        onOpen = onOpen,
                        onInstallApk = onInstallApk,
                        onInspectApk = onInspectApk,
                        onBrowseNestedArchive = onBrowseNestedArchive,
                        onOpenSqlite = onOpenSqlite,
                        onOpenHex = onOpenHex,
                        onOpenWithExternal = onOpenWithExternal,
                        onExtractNow = onExtractNow,
                        onExtractToFolder = onExtractToFolder,
                        onShare = onShare,
                        onCopyPath = onCopyPath,
                        onShowDetails = onShowDetails,
                        onDeleteFromZip = onDeleteFromZip
                    )
                }
            }

            ArchiveEntryThumbnailIcon(
                node = node,
                archiveFile = archiveFile,
                password = password,
                isGrid = true,
                modifier = Modifier.size(42.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = node.name,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = if (node.isDirectory) "${node.directChildrenCount} mục" else FileItem.formatBytes(node.size),
                color = TextMuted,
                fontSize = 10.5.sp
            )
        }
    }
}

@Composable
private fun ArchiveEntryThumbnailIcon(
    node: ZipNode,
    archiveFile: File,
    password: String?,
    isGrid: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (node.isDirectory) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = Color(0xFFFFB300),
            modifier = modifier
        )
        return
    }

    val ext = node.extension
    if (ext == "apk") {
        val apkBmp by produceState<Bitmap?>(initialValue = null, archiveFile.path, node.fullPath) {
            value = FileThumbnailLoader.loadArchiveEntryApkThumbnail(context, archiveFile, node.fullPath, password)
        }
        val bmp = apkBmp
        if (bmp != null) {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = node.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                )
                Surface(
                    color = Color(0xFF4CAF50),
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        text = "APK",
                        color = Color.White,
                        fontSize = 6.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 0.5.dp)
                    )
                }
            }
            return
        }
    }

    if (ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
        val imgBmp by produceState<Bitmap?>(initialValue = null, archiveFile.path, node.fullPath) {
            value = FileThumbnailLoader.loadArchiveEntryImageThumbnail(
                archiveFile = archiveFile,
                entryPath = node.fullPath,
                password = password,
                maxSizePx = if (isGrid) 144 else 96
            )
        }
        val bmp = imgBmp
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = node.name,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(0.6.dp, CharcoalSurfaceHighlight, RoundedCornerShape(6.dp))
            )
            return
        }
    }

    val badgeW = if (isGrid) 28.dp else 24.dp
    val badgeH = if (isGrid) 36.dp else 30.dp
    when (ext) {
        "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "lz4", "zst", "zstd" ->
            ZipDocumentBadgeIcon(modifier = Modifier.size(width = badgeW, height = badgeH), label = ext.uppercase())
        "pdf" -> PdfDocumentBadgeIcon(modifier = Modifier.size(width = badgeW, height = badgeH))
        "apk", "xapk", "apks", "apkm" -> ApkDocumentBadgeIcon(modifier = Modifier.size(width = badgeW, height = badgeH))
        "doc", "docx", "rtf" -> DocumentBadgeIcon("DOC", Color(0xFF2B579A), Color(0xFF2B579A), Icons.Default.Description, modifier = Modifier.size(width = badgeW, height = badgeH))
        "xls", "xlsx", "csv" -> DocumentBadgeIcon(if (ext == "csv") "CSV" else "XLS", Color(0xFF217346), Color(0xFF217346), Icons.Default.TableChart, modifier = Modifier.size(width = badgeW, height = badgeH))
        "ppt", "pptx" -> DocumentBadgeIcon("PPT", Color(0xFFD24726), Color(0xFFD24726), Icons.Default.Slideshow, modifier = Modifier.size(width = badgeW, height = badgeH))
        "ts", "tsx", "txs" -> DocumentBadgeIcon("TS", Color(0xFF3178C6), Color(0xFF3178C6), Icons.Default.Code, modifier = Modifier.size(width = badgeW, height = badgeH))
        "js", "jsx" -> DocumentBadgeIcon("JS", Color(0xFFF7DF1E), Color(0xFFC6AC00), Icons.Default.Code, modifier = Modifier.size(width = badgeW, height = badgeH))
        "css" -> DocumentBadgeIcon("CSS", Color(0xFF264DE4), Color(0xFF264DE4), Icons.Default.Code, modifier = Modifier.size(width = badgeW, height = badgeH))
        "bat", "cmd", "sh" -> DocumentBadgeIcon("BAT", Color(0xFF00E676), Color(0xFF00A854), Icons.Default.Terminal, modifier = Modifier.size(width = badgeW, height = badgeH))
        "dat", "bin", "pak", "obb" -> DocumentBadgeIcon(ext.take(3).uppercase(), Color(0xFFAB47BC), Color(0xFF8E24AA), Icons.Default.SportsEsports, modifier = Modifier.size(width = badgeW, height = badgeH))
        "db", "sqlite" -> DocumentBadgeIcon("DB", Color(0xFF00BFA5), Color(0xFF00897B), Icons.Default.Storage, modifier = Modifier.size(width = badgeW, height = badgeH))
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> Icon(Icons.Default.Image, null, tint = Color(0xFFF48FB1), modifier = modifier)
        "mp3", "wav", "ogg", "flac" -> Icon(Icons.Default.AudioFile, null, tint = CyanAccent, modifier = modifier)
        "mp4", "mkv", "avi" -> Icon(Icons.Default.VideoFile, null, tint = Color(0xFFEF5350), modifier = modifier)
        else -> Icon(Icons.Default.Article, null, tint = ElectricBlue, modifier = modifier)
    }
}

@Composable
private fun ExtractOptionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CharcoalSurface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextMuted, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
