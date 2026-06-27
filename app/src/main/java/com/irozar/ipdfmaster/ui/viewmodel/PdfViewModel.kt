package com.irozar.ipdfmaster.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irozar.ipdfmaster.api.AiGateway
import com.irozar.ipdfmaster.api.GeminiHelper
import com.irozar.ipdfmaster.data.database.AppDatabase
import com.irozar.ipdfmaster.data.entity.*
import com.irozar.ipdfmaster.data.repository.PdfRepository
import com.irozar.ipdfmaster.utils.DocumentEngine
import com.irozar.ipdfmaster.utils.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val dbInstance = AppDatabase.getDatabase(application)
    private val repository = PdfRepository(dbInstance.pdfDao())
    private val aiPrefs = application.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    private val securePrefs = application.getSharedPreferences("secure_settings", Context.MODE_PRIVATE)
    
    // --- UI Filters and Search ---
    val searchQuery = MutableStateFlow("")
    val selectedCategory = MutableStateFlow("All") // "All", "Work", "Study", "Scanner", "Favorites", "Private Safe"

    // --- Active Document State ---
    var currentOpenFile by mutableStateOf<PdfFile?>(null)
        private set
    var pendingPrivateOpenFile by mutableStateOf<PdfFile?>(null)
        private set
    private var pendingPrivateOpenMode: String = "view"
    var currentPageIndex by mutableStateOf(0) // 0-based
        private set
    var currentLayoutMode by mutableStateOf("view") // "view" (reading), "annotate", "manage_pages", "ai_chat"
        private set
    private var pendingPrivateOpenTool: String = "read"
    
    // --- AI Area Selection State ---
    var selectedArea by mutableStateOf<RectF?>(null)
    private val aiOcrTextByFileId = mutableStateMapOf<Int, String>()
    var aiFeaturesEnabled by mutableStateOf(aiPrefs.getBoolean("enabled", false))
        private set
    var aiProvider by mutableStateOf(aiPrefs.getString("provider", "Gemini") ?: "Gemini")
        private set
    var aiApiKey by mutableStateOf(aiPrefs.getString("api_key", "") ?: "")
        private set
    var aiModel by mutableStateOf(normalizeAiModel(aiProvider, aiPrefs.getString("model", "gemini-flash-latest") ?: "gemini-flash-latest"))
        private set
    val isAiConfigured: Boolean
        get() = aiFeaturesEnabled && aiApiKey.isNotBlank()

    // --- Annotation Tool Settings ---
    var currentEditTool by mutableStateOf("read") // "read", "draw", "highlight", "underline", "strike", "text", "signature", "shape", "eraser"
    var drawColorHex by mutableStateOf("#D32F2F") // Default branding red color
    var drawThickness by mutableStateOf(5f)
    var placedTextSizeInput by mutableStateOf(16f)
    var selectedShapeType by mutableStateOf("rectangle") // "rectangle", "circle", "arrow", "line"
    var shapeWidthInput by mutableStateOf(120f)
    var shapeHeightInput by mutableStateOf(100f)

    // --- Dynamic Text Layers for generated docs and annotations ---
    var textBlockList = mutableStateOf<List<TextBlock>>(emptyList())

    // --- PDF Operations Interaction states ---
    var mergeCandidateIds = mutableStateOf<Set<Int>>(emptySet())
    var compressionResultPercent by mutableStateOf<Int?>(null)

    private sealed class UndoAction {
        data class TextBlocks(val previous: List<TextBlock>) : UndoAction()
        data class DeleteAnnotation(val annotationId: Int) : UndoAction()
        data class RestoreAnnotation(val annotation: AnnotationItem) : UndoAction()
        data class RestoreAnnotations(val annotations: List<AnnotationItem>) : UndoAction()
        data class PageState(
            val file: PdfFile,
            val pageIndex: Int,
            val rotation: Float,
            val rotationKey: String,
            val pageMapping: List<Pair<String, Int>>
        ) : UndoAction()
    }

    private data class DeletedFileSnapshot(
        val file: PdfFile,
        val annotations: List<AnnotationItem>,
        val chatMessages: List<ChatMessage>
    )

    private val undoStack = mutableStateListOf<UndoAction>()
    private var lastDeletedFileSnapshot: DeletedFileSnapshot? = null
    val canUndo: Boolean
        get() = undoStack.isNotEmpty()

    private fun pushUndo(action: UndoAction) {
        undoStack.add(action)
        if (undoStack.size > 50) {
            undoStack.removeAt(0)
        }
    }

    private fun capturePageStateForUndo(file: PdfFile) {
        pushUndo(
            UndoAction.PageState(
                file = file,
                pageIndex = currentPageIndex,
                rotation = currentPageRotationDeg,
                rotationKey = pageRotationKey(file.id, currentPageIndex),
                pageMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            )
        )
    }

    fun undoLastChange() {
        val action = undoStack.removeLastOrNull() ?: return
        viewModelScope.launch {
            when (action) {
                is UndoAction.TextBlocks -> {
                    textBlockList.value = action.previous
                }
                is UndoAction.DeleteAnnotation -> {
                    repository.removeAnnotation(action.annotationId)
                }
                is UndoAction.RestoreAnnotation -> {
                    repository.addAnnotation(action.annotation)
                }
                is UndoAction.RestoreAnnotations -> {
                    action.annotations.forEach { repository.addAnnotation(it) }
                }
                is UndoAction.PageState -> {
                    repository.updateFile(action.file)
                    savePageMapping(action.file.name, action.pageMapping)
                    currentOpenFile = action.file
                    pageRotationByKey[action.rotationKey] = action.rotation
                    currentPageIndex = action.pageIndex.coerceIn(0, action.file.pageCount - 1)
                    loadPageTextBlocks(action.file.name, currentPageIndex)
                }
            }
        }
    }

    // --- Offline Scanner State ---
    var currentScanFilter by mutableStateOf("Original")
    var isOcrActive by mutableStateOf(false)
    var extractedOcrText by mutableStateOf("")

    // --- Security Configuration state (persisted across restarts) ---
    var isAppLocked by mutableStateOf(securePrefs.getBoolean("app_locked", false))
    var appLockPin by mutableStateOf(securePrefs.getString("app_lock_pin", "") ?: "")
    var isScreenUnlocked by mutableStateOf(true) // Unlocked in this run

    private var _recentFilesHidden by mutableStateOf(securePrefs.getBoolean("recent_hidden", false))
    var recentFilesHidden: Boolean
        get() = _recentFilesHidden
        set(value) {
            _recentFilesHidden = value
            securePrefs.edit().putBoolean("recent_hidden", value).apply()
        }

    // --- Base File Flow Lists ---
    val filesList: StateFlow<List<PdfFile>> = combine(
        repository.allFiles,
        searchQuery,
        selectedCategory
    ) { all, query, category ->
        var list = all

        if (category == "Favorites") {
            list = list.filter { it.isFavorite }
        } else if (category == "Private Safe") {
            list = list.filter { it.isPrivate }
        } else if (category != "All") {
            list = list.filter { it.category.lowercase(Locale.ROOT) == category.lowercase(Locale.ROOT) }
        }

        if (query.isNotEmpty()) {
            list = list.filter { it.name.contains(query, ignoreCase = true) }
        }

        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentFilesList: StateFlow<List<PdfFile>> = combine(
        repository.allFiles,
        searchQuery
    ) { all, query ->
        if (query.isNotEmpty()) {
            all.filter { it.name.contains(query, ignoreCase = true) }
        } else {
            all
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedSignaturesFlow: StateFlow<List<SavedSignature>>
    val annotationsFlow = MutableStateFlow<List<AnnotationItem>>(emptyList())
    val chatMessagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    var aiThinking by mutableStateOf(false)
        private set

    init {
        savedSignaturesFlow = repository.savedSignatures.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        // Load general preference lock
        viewModelScope.launch {
            // Remove any built-in demo files (also cleans up installs seeded by older versions)
            removeSeededSampleFiles(dbInstance.pdfDao())
        }
        viewModelScope.launch {
            repository.pageSourceMappings.collect { rows ->
                rows.groupBy { it.targetFileName }.forEach { (targetFileName, mappings) ->
                    val orderedSources = mappings
                        .sortedBy { it.pageIndex }
                        .map { it.sourceFileName to it.sourcePageNumber }
                    DocumentEngine.registerExtractedFile(targetFileName, orderedSources)
                }
            }
        }
    }

    private suspend fun savePageMapping(fileName: String, mapping: List<Pair<String, Int>>) {
        DocumentEngine.registerExtractedFile(fileName, mapping)
        repository.replacePageSourceMappings(fileName, mapping)
    }

    private suspend fun removeSeededSampleFiles(pdfDao: com.irozar.ipdfmaster.data.dao.PdfDao) {
        // The app no longer ships with built-in demo PDFs. Delete any that were
        // pre-seeded by earlier versions so the library starts empty for the user.
        pdfDao.deleteSeededSampleFiles()
    }

    // --- Action Methods ---

    private var annotationsJob: Job? = null
    private var chatJob: Job? = null

    fun closePdf() {
        currentOpenFile = null
        annotationsJob?.cancel()
        chatJob?.cancel()
    }

    fun openPdf(file: PdfFile) {
        val openedFile = if (file.id != 0) file.copy(createdAt = System.currentTimeMillis()) else file
        currentOpenFile = openedFile
        currentPageIndex = 0
        currentLayoutMode = "view"
        loadPageTextBlocks(openedFile.name, 0)

        if (openedFile.id != 0) {
            viewModelScope.launch {
                repository.updateFile(openedFile)
            }
        }
        
        // Cancel legacy active subscriptions to prevent leaking data observers
        annotationsJob?.cancel()
        chatJob?.cancel()
        
        // Retrieve and observe annotations and chat for this specific opened file
        annotationsJob = viewModelScope.launch {
            repository.getAnnotationsForFile(openedFile.id).collect {
                annotationsFlow.value = it
            }
        }
        chatJob = viewModelScope.launch {
            repository.getChatMessages(openedFile.id).collect {
                chatMessagesFlow.value = it
            }
        }
    }

    fun requestOpenPdf(file: PdfFile) {
        if (file.isPrivate) {
            pendingPrivateOpenMode = "view"
            pendingPrivateOpenTool = "read"
            pendingPrivateOpenFile = file
        } else {
            openPdf(file)
        }
    }

    fun requestOpenPdf(file: PdfFile, mode: String, tool: String = "read") {
        if (file.isPrivate) {
            pendingPrivateOpenMode = mode
            pendingPrivateOpenTool = tool
            pendingPrivateOpenFile = file
        } else {
            openPdf(file)
            currentLayoutMode = mode
            currentEditTool = tool
        }
    }

    fun cancelPrivateOpen() {
        pendingPrivateOpenFile = null
        pendingPrivateOpenMode = "view"
        pendingPrivateOpenTool = "read"
    }

    fun verifyPinAndOpenPrivateFile(input: String): Boolean {
        val file = pendingPrivateOpenFile ?: return false
        val accepted = appLockPin.isNotEmpty() && input == appLockPin
        if (accepted) {
            val mode = pendingPrivateOpenMode
            val tool = pendingPrivateOpenTool
            pendingPrivateOpenFile = null
            pendingPrivateOpenMode = "view"
            pendingPrivateOpenTool = "read"
            openPdf(file)
            currentLayoutMode = mode
            currentEditTool = tool
        }
        return accepted
    }

    fun createPinAndOpenPrivateFile(pin: String): Boolean {
        val file = pendingPrivateOpenFile ?: return false
        if (pin.length != 4 || pin.any { !it.isDigit() }) return false
        val mode = pendingPrivateOpenMode
        val tool = pendingPrivateOpenTool
        setAppLock(pin)
        pendingPrivateOpenFile = null
        pendingPrivateOpenMode = "view"
        pendingPrivateOpenTool = "read"
        openPdf(file)
        currentLayoutMode = mode
        currentEditTool = tool
        return true
    }

    fun setPage(index: Int) {
        val maxPages = currentOpenFile?.pageCount ?: 1
        if (index in 0 until maxPages) {
            currentPageIndex = index
            currentOpenFile?.let {
                loadPageTextBlocks(it.name, index)
            }
        }
    }

    private fun loadPageTextBlocks(fileName: String, pageIndex: Int) {
        textBlockList.value = DocumentEngine.getPageContentsForFile(fileName, pageIndex + 1)
    }

    fun editTextBlock(blockId: String, newText: String, fontSize: Float, colorHex: String) {
        pushUndo(UndoAction.TextBlocks(textBlockList.value))
        textBlockList.value = textBlockList.value.map {
            if (it.id == blockId) {
                it.copy(text = newText, fontSize = fontSize, colorHex = colorHex)
            } else it
        }
    }

    fun captureTextBlocksForUndo() {
        pushUndo(UndoAction.TextBlocks(textBlockList.value))
    }

    fun addCustomTextBlock(text: String, x: Float = 30f, y: Float = 30f) {
        pushUndo(UndoAction.TextBlocks(textBlockList.value))
        val newId = "custom_" + System.currentTimeMillis()
        val block = TextBlock(
            id = newId,
            text = text,
            x = x,
            y = y,
            fontSize = placedTextSizeInput,
            colorHex = drawColorHex,
            originalText = "",
            pageIndex = currentPageIndex,
            source = "user"
        )
        textBlockList.value = textBlockList.value + block
    }

    fun changeLayoutMode(mode: String) {
        currentLayoutMode = mode
    }

    fun deleteTextBlock(blockId: String) {
        pushUndo(UndoAction.TextBlocks(textBlockList.value))
        textBlockList.value = textBlockList.value.mapNotNull { block ->
            when {
                block.id != blockId -> block
                else -> null
            }
        }
    }

    // --- Annotation CRUD ---

    fun addCanvasDrawing(pointsJson: String, colorHex: String, strokeWidth: Float) {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            val annotation = AnnotationItem(
                fileId = file.id,
                pageIndex = currentPageIndex,
                type = "draw",
                color = android.graphics.Color.parseColor(colorHex),
                thickness = strokeWidth,
                pointsJson = pointsJson
            )
            val insertedId = repository.addAnnotation(annotation)
            pushUndo(UndoAction.DeleteAnnotation(insertedId.toInt()))
        }
    }

    fun addHighlightUnderlineStrike(type: String, pointsJson: String, colorHex: String) {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            val annotation = AnnotationItem(
                fileId = file.id,
                pageIndex = currentPageIndex,
                type = type, // "highlight", "underline", "strike"
                color = android.graphics.Color.parseColor(colorHex),
                thickness = if (type == "highlight") 18f else 3f,
                pointsJson = pointsJson
            )
            val insertedId = repository.addAnnotation(annotation)
            pushUndo(UndoAction.DeleteAnnotation(insertedId.toInt()))
        }
    }

    fun captureAnnotationForUndo(annotation: AnnotationItem) {
        pushUndo(UndoAction.RestoreAnnotation(annotation))
    }

    fun dragTextOrAnnotation(blockId: String, newX: Float, newY: Float) {
        textBlockList.value = textBlockList.value.map {
            if (it.id == blockId) {
                it.copy(x = newX, y = newY)
            } else it
        }
    }

    fun placeSignatureAnnotation(pointsJson: String, x: Float, y: Float) {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            val annotation = AnnotationItem(
                fileId = file.id,
                pageIndex = currentPageIndex,
                type = "signature",
                color = android.graphics.Color.BLACK,
                pointsJson = pointsJson,
                paramX = x,
                paramY = y,
                paramWidth = 220f,
                paramHeight = 100f
            )
            val insertedId = repository.addAnnotation(annotation)
            pushUndo(UndoAction.DeleteAnnotation(insertedId.toInt()))
        }
    }

    fun placeShapeAnnotation(shape: String, x: Float, y: Float, w: Float, h: Float, colorHex: String) {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            val annotation = AnnotationItem(
                fileId = file.id,
                pageIndex = currentPageIndex,
                type = "shape",
                color = android.graphics.Color.parseColor(colorHex),
                thickness = 4f,
                paramX = x,
                paramY = y,
                paramWidth = w,
                paramHeight = h,
                shapeType = shape
            )
            val insertedId = repository.addAnnotation(annotation)
            pushUndo(UndoAction.DeleteAnnotation(insertedId.toInt()))
        }
    }

    fun moveAnnotation(annotation: AnnotationItem, newX: Float, newY: Float) {
        viewModelScope.launch {
            repository.updateAnnotation(
                annotation.copy(
                    paramX = newX.coerceIn(0f, 0.95f),
                    paramY = newY.coerceIn(0f, 0.95f)
                )
            )
        }
    }

    fun deleteAnnotation(id: Int) {
        viewModelScope.launch {
            annotationsFlow.value.firstOrNull { it.id == id }?.let {
                pushUndo(UndoAction.RestoreAnnotation(it))
            }
            repository.removeAnnotation(id)
        }
    }

    fun clearAllAnnotations() {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            val previous = annotationsFlow.value.filter { it.fileId == file.id }
            pushUndo(UndoAction.RestoreAnnotations(previous))
            repository.clearAnnotations(file.id)
        }
    }

    // --- Signatures Manager ---

    fun saveSignatureCanvas(pointsJson: String) {
        viewModelScope.launch {
            repository.saveSignature(pointsJson)
        }
    }

    fun deleteSavedSignature(id: Int) {
        viewModelScope.launch {
            repository.deleteSignature(id)
        }
    }

    // --- File Options Commands ---

    fun toggleFavorite(file: PdfFile) {
        viewModelScope.launch {
            repository.toggleFavorite(file.id, !file.isFavorite)
        }
    }

    fun togglePrivateSafe(file: PdfFile) {
        viewModelScope.launch {
            repository.togglePrivate(file.id, !file.isPrivate)
        }
    }

    fun renameFile(file: PdfFile, newName: String) {
        viewModelScope.launch {
            val updated = file.copy(name = if (newName.endsWith(".pdf")) newName else "$newName.pdf")
            repository.updateFile(updated)
            if (currentOpenFile?.id == file.id) {
                currentOpenFile = updated
            }
        }
    }

    fun changeFileCategory(file: PdfFile, category: String) {
        viewModelScope.launch {
            val updated = file.copy(
                category = category,
                isPrivate = category == "Private Safe"
            )
            repository.updateFile(updated)
            if (currentOpenFile?.id == file.id) {
                currentOpenFile = updated
            }
        }
    }

    fun deleteFile(file: PdfFile) {
        viewModelScope.launch {
            val annotations = repository.getAnnotationsForFile(file.id).first()
            val chatMessages = repository.getChatMessages(file.id).first()
            lastDeletedFileSnapshot = DeletedFileSnapshot(file, annotations, chatMessages)
            repository.deleteFileById(file.id)
            if (currentOpenFile?.id == file.id) {
                currentOpenFile = null
            }
        }
    }

    fun undoLastFileDelete() {
        val snapshot = lastDeletedFileSnapshot ?: return
        lastDeletedFileSnapshot = null
        viewModelScope.launch {
            repository.insertFile(snapshot.file)
            snapshot.annotations.forEach { annotation ->
                repository.addAnnotation(annotation.copy(id = 0, fileId = snapshot.file.id))
            }
            snapshot.chatMessages.forEach { message ->
                repository.addChatMessage(snapshot.file.id, message.sender, message.content)
            }
        }
    }

    // --- Magic AI features ---
    fun analyzeSelectedArea(bitmap: Bitmap, prompt: String) {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            repository.addChatMessage(file.id, "user", "[Image Selection] $prompt")
            if (!isAiConfigured) {
                addAiInactiveMessage(file)
                selectedArea = null
                return@launch
            }
            if (!aiProvider.equals("Gemini", ignoreCase = true)) {
                repository.addChatMessage(
                    file.id,
                    "assistant",
                    "Selected-area image analysis currently needs Gemini because it sends a cropped page image. Switch AI provider to Gemini, or use Ask PDF with text/OCR content."
                )
                selectedArea = null
                return@launch
            }
            aiThinking = true
            try {
                val answer = GeminiHelper.generateAiContent(
                    prompt = prompt,
                    bitmap = bitmap,
                    systemInstruction = "You are analyzing a specific area of a PDF document image. Answer the user prompt based on this visual context.",
                    apiKeyOverride = aiApiKey,
                    model = aiModel
                )
                
                if (answer == "APIKeyMissing") {
                    addAiInactiveMessage(file)
                } else {
                    repository.addChatMessage(file.id, "assistant", answer)
                }
            } catch (e: Exception) {
                repository.addChatMessage(file.id, "assistant", "Error: ${e.message}")
            } finally {
                aiThinking = false
                selectedArea = null
            }
        }
    }

    fun clearSelectedArea() {
        selectedArea = null
    }

    fun importNewBlankPdf(name: String, category: String, pages: Int = 1) {
        viewModelScope.launch {
            val cleanName = if (name.endsWith(".pdf")) name else "$name.pdf"
            val newFile = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = pages * 15320L,
                pageCount = pages,
                category = category
            )
            val newId = repository.insertFile(newFile)
            val created = newFile.copy(id = newId.toInt())
            openPdf(created)
        }
    }

    fun importScannedPdf(
        name: String,
        category: String,
        pages: Int = 1,
        thumbnailPath: String? = null,
        filePath: String? = null,
        sizeInBytes: Long? = null
    ) {
        viewModelScope.launch {
            val cleanName = if (name.endsWith(".pdf")) name else "$name.pdf"
            val newFile = PdfFile(
                name = cleanName,
                filePath = filePath ?: "assets/$cleanName",
                sizeInBytes = sizeInBytes ?: pages * 23150L,
                pageCount = pages,
                category = category,
                thumbnailPath = thumbnailPath
            )
            val newId = repository.insertFile(newFile)
            val created = newFile.copy(id = newId.toInt())
            openPdf(created)
        }
    }

    fun importPdfForMode(
        name: String,
        category: String,
        pages: Int = 1,
        uriString: String,
        mode: String,
        tool: String = "read",
        sizeInBytes: Long? = null
    ) {
        viewModelScope.launch {
            val cleanName = if (name.endsWith(".pdf")) name else "$name.pdf"
            val newFile = PdfFile(
                name = cleanName,
                filePath = uriString,
                sizeInBytes = sizeInBytes ?: pages * 23150L,
                pageCount = pages,
                category = category,
                thumbnailPath = uriString
            )
            val newId = repository.insertFile(newFile)
            val created = newFile.copy(id = newId.toInt())
            openPdf(created)
            currentLayoutMode = mode
            currentEditTool = tool
        }
    }

    fun importPdfToLibrary(
        name: String,
        category: String,
        pages: Int = 1,
        uriString: String,
        sizeInBytes: Long? = null
    ) {
        viewModelScope.launch {
            val cleanName = if (name.endsWith(".pdf")) name else "$name.pdf"
            val newFile = PdfFile(
                name = cleanName,
                filePath = uriString,
                sizeInBytes = sizeInBytes ?: pages * 23150L,
                pageCount = pages,
                category = category,
                thumbnailPath = uriString
            )
            repository.insertFile(newFile)
        }
    }

    // --- Tools Page logic ---

    fun toggleMergeSelection(id: Int) {
        val currentSet = mergeCandidateIds.value
        if (currentSet.contains(id)) {
            mergeCandidateIds.value = currentSet - id
        } else {
            mergeCandidateIds.value = currentSet + id
        }
    }

    fun executeFileMerge(
        newFileName: String,
        files: List<PdfFile>,
        targetCategory: String = "All",
        insertSecondAfterPage: Int? = null
    ) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            val cleanName = if (newFileName.endsWith(".pdf")) newFileName else "$newFileName.pdf"
            val ctx = getApplication<Application>()

            // Real merge of the actual PDF bytes so the result is NOT blank.
            val merged = withContext(Dispatchers.IO) { tryRealMergeMulti(ctx, cleanName, files) }
            if (merged != null) {
                val (path, pages) = merged
                val mergedFile = PdfFile(
                    name = cleanName,
                    filePath = path,
                    thumbnailPath = path, // the viewer renders pages from thumbnailPath
                    sizeInBytes = File(path).length(),
                    pageCount = pages,
                    category = if (targetCategory == "All") "Work" else targetCategory
                )
                val insertedId = repository.insertFile(mergedFile)
                mergeCandidateIds.value = emptySet()
                openPdf(mergedFile.copy(id = insertedId.toInt()))
                return@launch
            }

            // Fallback for built-in sample files that have no real PDF bytes.
            val filesToMerge = files.map { it.name to it.pageCount }
            val combinedBytes = files.sumOf { it.sizeInBytes }
            val mergedMapping = if (insertSecondAfterPage != null && files.size >= 2) {
                val base = files[0]
                val insert = files[1]
                val baseMapping = DocumentEngine.getOrCreatePageMapping(base.name, base.pageCount)
                val insertMapping = DocumentEngine.getOrCreatePageMapping(insert.name, insert.pageCount)
                val insertAt = insertSecondAfterPage.coerceIn(0, baseMapping.size)
                val remainingMappings = files.drop(2).flatMap { file ->
                    DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
                }
                baseMapping.take(insertAt) + insertMapping + baseMapping.drop(insertAt) + remainingMappings
            } else {
                filesToMerge.flatMap { source ->
                    DocumentEngine.getOrCreatePageMapping(source.first, source.second)
                }
            }
            val mergedFile = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = combinedBytes,
                pageCount = mergedMapping.size,
                category = targetCategory
            )
            val insertedId = repository.insertFile(mergedFile)
            savePageMapping(cleanName, mergedMapping)
            mergeCandidateIds.value = emptySet()
            openPdf(mergedFile.copy(id = insertedId.toInt()))
        }
    }

    /** Merges several real PDFs (in selection order) into one file in app storage. Returns
     *  (path, pageCount), or null if any source has no real PDF bytes (e.g. sample files). */
    private fun tryRealMergeMulti(ctx: Context, cleanName: String, files: List<PdfFile>): Pair<String, Int>? {
        val temps = mutableListOf<File>()
        return try {
            files.forEachIndexed { i, f ->
                val t = File(ctx.cacheDir, "merge_src_${i}_${System.currentTimeMillis()}.pdf")
                if (!materializeToCache(ctx, f, t)) return null
                temps.add(t)
            }
            val safe = cleanName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(ctx.filesDir, "merged_${System.currentTimeMillis()}_$safe")
            com.irozar.ipdfmaster.pdfeditorspike.PdfLocalTools.merge(temps, outFile)
            val pages = PDDocument.load(outFile).use { it.numberOfPages }
            outFile.absolutePath to pages
        } catch (e: Exception) {
            null
        } finally {
            temps.forEach { it.delete() }
        }
    }

    fun executeCompression(file: PdfFile, quality: String) {
        viewModelScope.launch {
            val discountFactor = when (quality) {
                "High" -> 0.45f  // 55% smaller
                "Medium" -> 0.25f // 75% smaller
                else -> 0.15f    // 85% smaller
            }
            val originalSize = file.sizeInBytes
            val estimatedSize = (originalSize * discountFactor).toLong()
            
            val updated = file.copy(sizeInBytes = estimatedSize)
            repository.updateFile(updated)
            
            compressionResultPercent = (discountFactor * 100).toInt()
            if (currentOpenFile?.id == file.id) {
                currentOpenFile = updated
            }
        }
    }

    // --- Active Page Rotation ---
    private val pageRotationByKey = mutableStateMapOf<String, Float>()

    private fun pageRotationKey(fileId: Int, pageIndex: Int): String = "$fileId:$pageIndex"

    val currentPageRotationDeg: Float
        get() {
            val file = currentOpenFile ?: return 0f
            return pageRotationByKey[pageRotationKey(file.id, currentPageIndex)] ?: 0f
        }

    fun saveCopyAs(newFileName: String) {
        val file = currentOpenFile ?: return
        copyFileDirect(file, newFileName, openAfterCopy = true)
    }

    fun copyFileDirect(file: PdfFile, newFileName: String = defaultCopyName(file.name), openAfterCopy: Boolean = false) {
        viewModelScope.launch {
            val cleanName = if (newFileName.endsWith(".pdf")) newFileName else "$newFileName.pdf"
            val newFile = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = file.sizeInBytes,
                pageCount = file.pageCount,
                category = file.category,
                thumbnailPath = file.thumbnailPath,
                isFavorite = file.isFavorite,
                isPrivate = file.isPrivate
            )
            val newId = repository.insertFile(newFile)
            
            // Preserve the visible page sources for derived documents.
            val mappings = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            savePageMapping(cleanName, mappings)

            // Duplicate selected file annotations and chat, even when the file is not open.
            val currentAnnoList = repository.getAnnotationsForFile(file.id).first()
            currentAnnoList.forEach { anno ->
                val copiedAnno = anno.copy(id = 0, fileId = newId.toInt())
                repository.addAnnotation(copiedAnno)
            }
            
            val currentChatList = repository.getChatMessages(file.id).first()
            currentChatList.forEach { msg ->
                repository.addChatMessage(newId.toInt(), msg.sender, msg.content)
            }
            
            if (openAfterCopy) {
                val finalized = newFile.copy(id = newId.toInt())
                openPdf(finalized)
            }
        }
    }

    private fun defaultCopyName(fileName: String): String {
        val baseName = fileName.removeSuffix(".pdf")
        return "${baseName}_copy.pdf"
    }

    fun executeAdvancedMerge(
        newFileName: String,
        baseFile: PdfFile,
        insertFile: PdfFile,
        positionType: String, // "first", "last", "custom"
        customPageNum: Int,
        targetCategory: String = "All"
    ) {
        viewModelScope.launch {
            val cleanName = if (newFileName.endsWith(".pdf")) newFileName else "$newFileName.pdf"
            val ctx = getApplication<Application>()

            // Real merge: combine the two PDFs' actual bytes so the result is NOT blank.
            val merged = withContext(Dispatchers.IO) {
                tryRealMerge(ctx, cleanName, baseFile, insertFile, positionType)
            }

            if (merged != null) {
                val (path, pages) = merged
                val mergedFile = PdfFile(
                    name = cleanName,
                    filePath = path,
                    thumbnailPath = path, // the viewer renders pages from thumbnailPath
                    sizeInBytes = File(path).length(),
                    pageCount = pages,
                    category = if (targetCategory == "All") "Work" else targetCategory
                )
                val insertedId = repository.insertFile(mergedFile)
                openPdf(mergedFile.copy(id = insertedId.toInt()))
                return@launch
            }

            // Fallback for built-in sample files that have no real PDF bytes: keep the
            // previous page-mapping behaviour so those still open.
            val totalPagesCombined = baseFile.pageCount + insertFile.pageCount
            val combinedBytes = baseFile.sizeInBytes + insertFile.sizeInBytes
            val mergedFile = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = combinedBytes,
                pageCount = totalPagesCombined,
                category = targetCategory
            )
            val insertedId = repository.insertFile(mergedFile)
            val baseMapping = DocumentEngine.getOrCreatePageMapping(baseFile.name, baseFile.pageCount)
            val insertMapping = DocumentEngine.getOrCreatePageMapping(insertFile.name, insertFile.pageCount)
            val insertAt = when (positionType) {
                "first" -> 0
                "custom" -> customPageNum.coerceIn(0, baseMapping.size)
                else -> baseMapping.size
            }
            val mergedMapping = baseMapping.take(insertAt) + insertMapping + baseMapping.drop(insertAt)
            savePageMapping(cleanName, mergedMapping)
            val baseAnnos = annotationsFlow.value
            baseAnnos.forEach { anno ->
                repository.addAnnotation(anno.copy(id = 0, fileId = insertedId.toInt()))
            }
            openPdf(mergedFile.copy(id = insertedId.toInt()))
        }
    }

    /** Copies a file's real PDF bytes (content uri / file path) into [dest]. Returns false
     *  for built-in sample files that only live as "assets/..." placeholders. */
    private fun materializeToCache(ctx: Context, file: PdfFile, dest: File): Boolean {
        val path = file.thumbnailPath?.takeIf { it.isNotBlank() } ?: file.filePath
        return try {
            val input = when {
                path.isNullOrBlank() || path.startsWith("assets/") -> null
                path.startsWith("content://") || path.startsWith("file://") ->
                    ctx.contentResolver.openInputStream(Uri.parse(path))
                else -> File(path).takeIf { it.exists() }?.inputStream()
            } ?: return false
            input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Merges two real PDFs into a new file in app storage. "first" prepends the second
     *  file, anything else appends it. Returns (path, pageCount) or null if either source
     *  has no real PDF bytes. */
    private fun tryRealMerge(
        ctx: Context,
        cleanName: String,
        baseFile: PdfFile,
        insertFile: PdfFile,
        positionType: String
    ): Pair<String, Int>? {
        val baseTmp = File(ctx.cacheDir, "merge_base_${System.currentTimeMillis()}.pdf")
        val insTmp = File(ctx.cacheDir, "merge_ins_${System.currentTimeMillis()}.pdf")
        return try {
            if (!materializeToCache(ctx, baseFile, baseTmp)) return null
            if (!materializeToCache(ctx, insertFile, insTmp)) return null
            val safe = cleanName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val outFile = File(ctx.filesDir, "merged_${System.currentTimeMillis()}_$safe")
            val order = if (positionType == "first") listOf(insTmp, baseTmp) else listOf(baseTmp, insTmp)
            com.irozar.ipdfmaster.pdfeditorspike.PdfLocalTools.merge(order, outFile)
            val pages = PDDocument.load(outFile).use { it.numberOfPages }
            outFile.absolutePath to pages
        } catch (e: Exception) {
            null
        } finally {
            baseTmp.delete()
            insTmp.delete()
        }
    }

    // --- Page Management Screen Options ---

    fun rotateActivePage() {
        val file = currentOpenFile ?: return
        capturePageStateForUndo(file)
        val key = pageRotationKey(file.id, currentPageIndex)
        pageRotationByKey[key] = ((pageRotationByKey[key] ?: 0f) + 90f) % 360f
    }

    fun deleteActivePage() {
        val file = currentOpenFile ?: return
        if (file.pageCount > 1) {
            capturePageStateForUndo(file)
            viewModelScope.launch {
                val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
                val newMapping = currentMapping.filterIndexed { index, _ -> index != currentPageIndex }
                
                val updated = file.copy(pageCount = file.pageCount - 1)
                repository.updateFile(updated)
                
                savePageMapping(file.name, newMapping)
                
                currentOpenFile = updated
                currentPageIndex = Math.max(0, currentPageIndex - 1)
            }
        }
    }

    fun removePagesByNumbers(numbersCsv: String): String {
        val file = currentOpenFile ?: return "No document open."
        val numbers = numbersCsv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..file.pageCount }
            .distinct()
            .sortedDescending()
        if (numbers.isEmpty()) return "No valid page numbers found."
        val newCount = file.pageCount - numbers.size
        if (newCount < 1) return "Cannot delete all pages. At least 1 page must remain."

        capturePageStateForUndo(file)
        viewModelScope.launch {
            val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            val newMapping = currentMapping.filterIndexed { index, _ -> (index + 1) !in numbers }
            
            val updated = file.copy(pageCount = newCount)
            repository.updateFile(updated)
            
            savePageMapping(file.name, newMapping)
            
            currentOpenFile = updated
            currentPageIndex = Math.min(currentPageIndex, newCount - 1)
        }
        return "Page(s) ${numbers.reversed().joinToString(", ")} removed successfully!"
    }

    fun insertBlankPagesAt(pageNum: Int, count: Int = 1) {
        val file = currentOpenFile ?: return
        capturePageStateForUndo(file)
        viewModelScope.launch {
            val updated = file.copy(pageCount = file.pageCount + count)
            repository.updateFile(updated)
            currentOpenFile = updated
        }
    }

    fun duplicateActivePage() {
        val file = currentOpenFile ?: return
        capturePageStateForUndo(file)
        viewModelScope.launch {
            val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            val sourcePage = currentMapping.getOrNull(currentPageIndex) ?: (file.name to (currentPageIndex + 1))
            val newMapping = currentMapping.toMutableList().apply {
                add(currentPageIndex + 1, sourcePage)
            }
            val updated = file.copy(pageCount = file.pageCount + 1)
            repository.updateFile(updated)
            savePageMapping(file.name, newMapping)
            currentOpenFile = updated
            currentPageIndex += 1
            loadPageTextBlocks(updated.name, currentPageIndex)
        }
    }

    fun moveActivePage(delta: Int) {
        val file = currentOpenFile ?: return
        val targetIndex = (currentPageIndex + delta).coerceIn(0, file.pageCount - 1)
        if (targetIndex == currentPageIndex) return
        capturePageStateForUndo(file)
        viewModelScope.launch {
            val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount).toMutableList()
            if (currentPageIndex in currentMapping.indices && targetIndex in currentMapping.indices) {
                val item = currentMapping.removeAt(currentPageIndex)
                currentMapping.add(targetIndex, item)
                savePageMapping(file.name, currentMapping)
                currentPageIndex = targetIndex
                loadPageTextBlocks(file.name, currentPageIndex)
            }
        }
    }

    fun extractPageAsNew(newName: String) {
        val file = currentOpenFile ?: return
        val pageNum = currentPageIndex + 1
        viewModelScope.launch {
            val cleanName = if (newName.endsWith(".pdf")) newName else "$newName.pdf"
            val extracted = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = 25600,
                pageCount = 1,
                category = file.category,
                thumbnailPath = file.thumbnailPath
            )
            repository.insertFile(extracted)
            val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            val sourcePage = currentMapping.getOrNull(currentPageIndex) ?: (file.name to pageNum)
            savePageMapping(cleanName, listOf(sourcePage))
        }
    }

    fun extractPagesAsNew(newName: String, pageNumbersCsv: String): String {
        val file = currentOpenFile ?: return "No document open."
        return splitPagesFromFile(file, newName, pageNumbersCsv)
    }

    fun splitPagesFromFile(file: PdfFile, newName: String, pageNumbersCsv: String): String {
        val numbers = pageNumbersCsv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..file.pageCount }
            .distinct()
            .sorted()
        if (numbers.isEmpty()) return "Please provide valid page numbers."
        
        val cleanName = if (newName.endsWith(".pdf")) newName else "$newName.pdf"
        viewModelScope.launch {
            val extracted = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = numbers.size * 21500L,
                pageCount = numbers.size,
                category = file.category,
                thumbnailPath = file.thumbnailPath
            )
            repository.insertFile(extracted)
            val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            val mappings = numbers.mapNotNull { pageNum ->
                currentMapping.getOrNull(pageNum - 1)
            }
            savePageMapping(cleanName, mappings)
        }
        return "Extracted pages ${numbers.joinToString(", ")} into '$cleanName'!"
    }

    fun reorderAndSplitPagesFromFile(file: PdfFile, newName: String, orderedPageNumbersCsv: String): String {
        val numbers = orderedPageNumbersCsv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..file.pageCount }
        if (numbers.isEmpty()) return "Please provide valid page numbers."

        val cleanName = if (newName.endsWith(".pdf")) newName else "$newName.pdf"
        viewModelScope.launch {
            val reordered = PdfFile(
                name = cleanName,
                filePath = "assets/$cleanName",
                sizeInBytes = numbers.size * 21500L,
                pageCount = numbers.size,
                category = file.category,
                thumbnailPath = file.thumbnailPath
            )
            repository.insertFile(reordered)
            val currentMapping = DocumentEngine.getOrCreatePageMapping(file.name, file.pageCount)
            val mappings = numbers.mapNotNull { pageNum ->
                currentMapping.getOrNull(pageNum - 1)
            }
            savePageMapping(cleanName, mappings)
        }
        return "Created '$cleanName' with pages in this order: ${numbers.joinToString(", ")}."
    }


    // --- Gemini AI Assistant Integration ---

    fun saveAiSettings(provider: String, apiKey: String, model: String, enabled: Boolean) {
        aiProvider = provider
        aiApiKey = apiKey.trim()
        aiModel = normalizeAiModel(provider, model.trim().ifBlank { defaultAiModel(provider) })
        aiFeaturesEnabled = enabled && aiApiKey.isNotBlank()
        aiPrefs.edit()
            .putString("provider", aiProvider)
            .putString("api_key", aiApiKey)
            .putString("model", aiModel)
            .putBoolean("enabled", aiFeaturesEnabled)
            .apply()
    }

    fun disableAiFeatures() {
        aiFeaturesEnabled = false
        aiPrefs.edit().putBoolean("enabled", false).apply()
    }

    fun defaultAiModel(provider: String): String {
        return when (provider.lowercase(Locale.ROOT)) {
            "openai" -> "gpt-4o-mini"
            "claude" -> "claude-3-5-sonnet-latest"
            "openrouter" -> "openrouter/auto"
            else -> "gemini-flash-latest"
        }
    }

    private fun normalizeAiModel(provider: String, model: String): String {
        if (!provider.equals("Gemini", ignoreCase = true)) return model
        return when (model.trim().lowercase(Locale.ROOT)) {
            "", "gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash", "gemini-2.0-pro" -> "gemini-flash-latest"
            else -> model.trim()
        }
    }

    private suspend fun addAiInactiveMessage(file: PdfFile) {
        repository.addChatMessage(
            file.id,
            "assistant",
            "AI features are inactive. Add an API key in Activate AI Features to use Ask PDF, summaries, rewrite/translate, and smart fill. After activation, selected document text may be sent to your chosen AI provider."
        )
    }

    fun cacheAiOcrText(fileId: Int, text: String) {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length >= 20) {
            aiOcrTextByFileId[fileId] = cleaned
        }
    }

    fun hasCachedAiOcrText(fileId: Int): Boolean {
        return aiOcrTextByFileId[fileId]?.length ?: 0 >= 20
    }

    fun executeFastDocumentSummarize() {
        val file = currentOpenFile ?: return
        if (!isAiConfigured) {
            viewModelScope.launch { addAiInactiveMessage(file) }
            return
        }
        aiThinking = true
        viewModelScope.launch {
            try {
                val fullText = collectSummaryText(file)
                val answer = AiGateway.generateText(
                    provider = aiProvider,
                    apiKey = aiApiKey,
                    model = aiModel,
                    prompt = "Summarize this PDF clearly. Include key points, important fields, and anything that needs attention.\n\nPDF name: ${file.name}\n\nDocument text:\n$fullText",
                    systemInstruction = "You are iPdf Master AI Assistant. Be accurate, concise, and only use the document text supplied by the user."
                )
                if (answer == AiGateway.API_KEY_MISSING) {
                    addAiInactiveMessage(file)
                } else {
                    repository.addChatMessage(file.id, "assistant", answer)
                }
            } catch (e: Exception) {
                repository.addChatMessage(
                    file.id,
                    "assistant",
                    "AI Summary failed: ${e.message ?: "Please check your API settings and try again."}"
                )
            } finally {
                aiThinking = false
            }
        }
    }

    /**
     * Runs the AI tool the user picked from the Tools list automatically once a file is
     * opened, so they don't have to retype the request. Driven by [currentEditTool], which
     * carries the chosen tool ("ai_ask", "ai_summary", "ai_rewrite", "ai_smartfill").
     */
    fun executeAiToolAction(tool: String, targetLanguage: String? = null) {
        val file = currentOpenFile ?: return
        if (!isAiConfigured) {
            viewModelScope.launch { addAiInactiveMessage(file) }
            return
        }
        val request = aiToolRequest(tool, targetLanguage)
        aiThinking = true
        viewModelScope.launch {
            try {
                request.lead?.let { repository.addChatMessage(file.id, "user", it) }
                val fullText = collectSummaryText(file)
                val answer = AiGateway.generateText(
                    provider = aiProvider,
                    apiKey = aiApiKey,
                    model = aiModel,
                    prompt = "${request.prompt}\n\nPDF name: ${file.name}\n\nDocument text:\n$fullText",
                    systemInstruction = request.system
                )
                if (answer == AiGateway.API_KEY_MISSING) {
                    addAiInactiveMessage(file)
                } else {
                    repository.addChatMessage(file.id, "assistant", answer)
                }
            } catch (e: Exception) {
                repository.addChatMessage(
                    file.id,
                    "assistant",
                    "AI request failed: ${e.message ?: "Please check your API settings and try again."}"
                )
            } finally {
                aiThinking = false
            }
        }
    }

    private data class AiToolRequest(val lead: String?, val prompt: String, val system: String)

    private fun aiToolRequest(tool: String, targetLanguage: String? = null): AiToolRequest {
        val system = "You are iPdf Master AI Assistant. Be accurate and only use the document text supplied by the user."
        return when (tool) {
            "ai_rewrite" -> AiToolRequest(
                lead = "Rewrite this document for clarity.",
                prompt = "Rewrite the document text to improve clarity, grammar and flow while keeping the original meaning and the same language. Keep all names, numbers and facts unchanged. Output only the rewritten text.",
                system = system
            )
            "ai_translate" -> {
                val lang = targetLanguage?.takeIf { it.isNotBlank() } ?: "English"
                AiToolRequest(
                    lead = "Translate this document into $lang.",
                    prompt = "Translate the document text into $lang. Preserve the original meaning, structure, line breaks, names and numbers. Do not add explanations — output only the translation.",
                    system = system
                )
            }
            "ai_smartfill" -> AiToolRequest(
                lead = "Smart-fill the fields in this document.",
                prompt = "This is a scanned form or structured document. Extract every field and its value as a clean list in 'Field: Value' format. Then list any fields that look empty or still need to be filled in.",
                system = system
            )
            "ai_ask" -> AiToolRequest(
                lead = "Give me an overview of this PDF.",
                prompt = "Give a clear overview of this PDF: what it is, its key points, and any important fields, tables or figures. End by inviting the user to ask follow-up questions.",
                system = system
            )
            else -> AiToolRequest( // ai_summary and any fallback
                lead = null,
                prompt = "Summarize this PDF clearly. Include key points, important fields, and anything that needs attention.",
                system = "You are iPdf Master AI Assistant. Be accurate, concise, and only use the document text supplied by the user."
            )
        }
    }

    fun postUserAiChatMessage(question: String) {
        val file = currentOpenFile ?: return
        if (question.trim().isEmpty()) return
        if (!isAiConfigured) {
            viewModelScope.launch {
                repository.addChatMessage(file.id, "user", question)
                addAiInactiveMessage(file)
            }
            return
        }
        
        viewModelScope.launch {
            // Write user question to room
            repository.addChatMessage(file.id, "user", question)
            aiThinking = true
            
            try {
                val fullText = collectSummaryText(file)
                val systemPrompt = "You are iPdf Master AI Assistant, running private queries about '${file.name}'. " +
                        "Analyze the text provided and answer the user accurately. Always refer to specific contents from the document."

                val answer = AiGateway.generateText(
                    provider = aiProvider,
                    apiKey = aiApiKey,
                    model = aiModel,
                    prompt = "Current Document Text context:\n\n$fullText\n\nUser Question:\n$question",
                    systemInstruction = systemPrompt
                )
                
                if (answer == AiGateway.API_KEY_MISSING) {
                    addAiInactiveMessage(file)
                } else {
                    repository.addChatMessage(file.id, "assistant", answer)
                }
            } catch (e: Exception) {
                repository.addChatMessage(file.id, "assistant", "AI request failed: ${e.message ?: "Please check your API settings."}")
            } finally {
                aiThinking = false
            }
        }
    }

    private suspend fun simulateLocalSummarize(file: PdfFile) {
        val summaryText = buildString {
            append("💡 **iPdf Master Local Summary** (Offline Mode)\n\n")
            append("📄 **Document:** `${file.name}`\n")
            append("📏 **Size:** `${(file.sizeInBytes / 1024) / 1000f} MB` | **Total Pages:** `${file.pageCount}`\n\n")
            append("🔑 **Core Extracted Insights:**\n")
            if (file.name == "Project_Proposal.pdf") {
                append("• **Target:** Develop a fully secure, 100% offline-first responsive Android PDF manager.\n")
                append("• **Tech:** Built on top of Kotlin, Jetpack Compose, Room SQLite databases, and Material 3 layouts.\n")
                append("• **Security:** High privacy enforcement - contains zero third-party cloud trackers or uploads.\n")
            } else if (file.name == "Math_Notes.pdf") {
                append("• **Subject:** Advanced Calculus limits derivation equations and integration properties.\n")
                append("• **Method:** Limit formulations, Riemann summation parameters, and derivative graphs.\n")
            } else {
                append("• This custom document contains generated TextBlocks, layered Vector annotations, and drawing elements.\n")
            }
            append("\n🛡️ *To unlock unlimited generative smart answers, summary diagrams, and semantic checks, enter your Google Gemini API Key in the AI Studio Secrets Panel!*")
        }
        repository.addChatMessage(file.id, "assistant", summaryText)
    }

    private fun buildOfflineSummary(file: PdfFile): String {
        val sourceText = collectSummaryText(file)
        val hasUsableText = sourceText.length >= 30 && !looksLikeGeneratedPlaceholder(sourceText, file)
        val sizeLabel = if (file.sizeInBytes > 0L) {
            "%.2f MB".format(Locale.US, file.sizeInBytes / (1024f * 1024f))
        } else {
            "Unknown"
        }

        return buildString {
            append("Local Summary\n\n")
            append("Document: ${file.name}\n")
            append("Pages: ${file.pageCount}\n")
            append("Size: $sizeLabel\n\n")

            if (!hasUsableText) {
                append("I could not find enough readable text inside this file yet.\n\n")
                append("This usually means the PDF is image-based, scanned, or imported from the camera. ")
                append("Use Extract Text (OCR) from the document menu first, then summarize again for a richer result.\n\n")
                append("Quick note: the file is saved and can still be viewed; only the text summary needs OCR text.")
                return@buildString
            }

            val sentences = extractSummarySentences(sourceText)
            append("Main Points:\n")
            sentences.take(5).forEach { sentence ->
                append("- $sentence\n")
            }

            val keywords = extractKeywords(sourceText)
            if (keywords.isNotEmpty()) {
                append("\nImportant Terms: ")
                append(keywords.joinToString(", "))
                append("\n")
            }

            append("\nSuggested Next Step: ")
            append("Use Ask AI for a specific question, or run OCR if a page image is missing text.")
        }
    }

    private fun collectSummaryText(file: PdfFile): String {
        val ocrText = aiOcrTextByFileId[file.id].orEmpty()
        val visiblePageText = textBlockList.value.joinToString(" ") { it.text }
        val documentText = DocumentEngine.getFullDocumentTranscript(file.name, file.pageCount)
        return listOf(ocrText, visiblePageText, documentText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun looksLikeGeneratedPlaceholder(text: String, file: PdfFile): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val name = file.name.lowercase(Locale.ROOT)
        return lower.contains("newly created private document") ||
                lower.contains("document title: $name") ||
                lower.contains("tap the '+' to add text elements")
    }

    private fun extractSummarySentences(text: String): List<String> {
        return text
            .split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim().trim('-', '*', ' ') }
            .filter { it.length in 20..220 }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .ifEmpty {
                text.chunked(160)
                    .take(3)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "about", "after", "again", "also", "because", "before", "being", "between",
            "could", "document", "first", "from", "have", "into", "page", "pages",
            "should", "that", "their", "there", "these", "this", "through", "using",
            "where", "which", "with", "would", "your"
        )
        return Regex("[A-Za-z][A-Za-z0-9_-]{4,}")
            .findAll(text.lowercase(Locale.ROOT))
            .map { it.value }
            .filterNot { it in stopWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(8)
            .map { it.key.replaceFirstChar { char -> char.uppercase(Locale.ROOT) } }
    }

    private suspend fun simulateLocalChatResponse(file: PdfFile, question: String) {
        val response = buildString {
            val q = question.lowercase(Locale.ROOT)
            append("🤖 **iPdf Master Local AI Response:**\n\n")
            if (q.contains("hello") || q.contains("hi ")) {
                append("Hello! I am your companion PDF Assistant. Ask me anything about the content of `${file.name}` or request highlights!")
            } else if (q.contains("summary") || q.contains("summarize")) {
                append("This document consists of ${file.pageCount} pages containing structured notes. Let me know if you would like me to highlight key dates or technical diagrams.")
            } else if (q.contains("tech") || q.contains("architecture") || q.contains("kotlin")) {
                append("The document references using Kotlin, Jetpack Compose, Room databases, and full Material 3 design tokens. All storage happens locally.")
            } else {
                append("I analyzed your request about \"$question\". I can see matching contents in page ${currentPageIndex + 1}. Try setting Up your server-side Gemini API key in the AI Studio sidebar to enable deep automated reasoning!")
            }
        }
        repository.addChatMessage(file.id, "assistant", response)
    }

    fun clearChatHistory() {
        val file = currentOpenFile ?: return
        viewModelScope.launch {
            repository.clearChatMessages(file.id)
        }
    }


    // --- Security & PIN Settings ---

    fun setAppLock(pin: String) {
        appLockPin = pin
        isAppLocked = pin.isNotEmpty()
        isScreenUnlocked = true
        // Persist so the lock survives app restarts.
        securePrefs.edit()
            .putString("app_lock_pin", pin)
            .putBoolean("app_locked", isAppLocked)
            .apply()
    }

    fun verifyPinForUnlocking(input: String): Boolean {
        return if (input == appLockPin) {
            isScreenUnlocked = true
            true
        } else false
    }

    fun logoutLock() {
        if (isAppLocked) {
            isScreenUnlocked = false
        }
    }
}
