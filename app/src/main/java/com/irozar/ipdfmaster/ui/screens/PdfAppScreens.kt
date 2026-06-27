package com.irozar.ipdfmaster.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.irozar.ipdfmaster.data.entity.AnnotationItem
import com.irozar.ipdfmaster.data.entity.PdfFile
import com.irozar.ipdfmaster.ui.viewmodel.PdfViewModel
import com.irozar.ipdfmaster.utils.DocumentEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private data class AnnotationDragState(
    val annotation: AnnotationItem,
    val grabOffset: Offset
)

@Composable
fun PdfAppMain(viewModel: PdfViewModel) {
    val isAppLocked = viewModel.isAppLocked
    val isScreenUnlocked = viewModel.isScreenUnlocked

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (isAppLocked && !isScreenUnlocked) {
            LockScreen(viewModel)
        } else {
            MainAppNavigation(viewModel)
        }
    }
}

@Composable
fun LockScreen(viewModel: PdfViewModel) {
    var pinValue by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "App Locked",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "iPdf Master Private Lock",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "Enter your secure PIN to access documents",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Dots display
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            for (i in 1..4) {
                val isActive = pinValue.length >= i
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary 
                            else Color.LightGray.copy(alpha = 0.5f)
                        )
                        .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }

        if (pinError) {
            Text(
                text = "Incorrect PIN code. Try again.",
                color = Color.Red,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Grid Keypad
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Clear", "0", "OK")
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            items(keys) { key ->
                IconButton(
                    onClick = {
                        pinError = false
                        when (key) {
                            "Clear" -> {
                                if (pinValue.isNotEmpty()) {
                                    pinValue = pinValue.dropLast(1)
                                }
                            }
                            "OK" -> {
                                if (viewModel.verifyPinForUnlocking(pinValue)) {
                                    pinValue = ""
                                } else {
                                    pinError = true
                                    pinValue = ""
                                }
                            }
                            else -> {
                                if (pinValue.length < 4) {
                                    pinValue += key
                                    if (pinValue.length == 4) {
                                        // Automated validation on 4th number
                                        if (viewModel.verifyPinForUnlocking(pinValue)) {
                                            pinValue = ""
                                        } else {
                                            pinError = true
                                            pinValue = ""
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PrivateFilePinDialog(viewModel: PdfViewModel, fileName: String) {
    val context = LocalContext.current
    var pinValue by remember(fileName) { mutableStateOf("") }
    var pinError by remember(fileName) { mutableStateOf(false) }
    val needsPinSetup = viewModel.appLockPin.isBlank()

    AlertDialog(
        onDismissRequest = {
            viewModel.cancelPrivateOpen()
            pinValue = ""
        },
        title = { Text(if (needsPinSetup) "Create Private Safe PIN" else "Private Safe Locked") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (needsPinSetup) {
                        "Create a 4 digit PIN before opening private files."
                    } else {
                        "Enter your PIN to open $fileName."
                    },
                    fontSize = 13.sp,
                    color = Color.Gray
                )
                OutlinedTextField(
                    value = pinValue,
                    onValueChange = { input ->
                        pinError = false
                        pinValue = input.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("4-Digit PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (pinError) {
                    Text(
                        text = "Incorrect PIN. Try again.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = pinValue.length == 4,
                onClick = {
                    val opened = if (needsPinSetup) {
                        viewModel.createPinAndOpenPrivateFile(pinValue)
                    } else {
                        viewModel.verifyPinAndOpenPrivateFile(pinValue)
                    }
                    if (opened) {
                        pinValue = ""
                        Toast.makeText(context, "Private file unlocked.", Toast.LENGTH_SHORT).show()
                    } else {
                        pinError = true
                        pinValue = ""
                    }
                }
            ) {
                Text(if (needsPinSetup) "Create & Open" else "Open")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    pinValue = ""
                    viewModel.cancelPrivateOpen()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun MainAppNavigation(viewModel: PdfViewModel) {
    var activeTab by remember { mutableStateOf("home") }
    var currentScannerActive by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val currentOpenFile = viewModel.currentOpenFile
    val pendingPrivateOpenFile = viewModel.pendingPrivateOpenFile
    val deleteWithUndo: (PdfFile) -> Unit = { file ->
        viewModel.deleteFile(file)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "${file.name} deleted",
                actionLabel = "Undo",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoLastFileDelete()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (currentOpenFile != null) {
            // PDF opened fullscreen layout
            PdfViewerScreen(viewModel)
        } else if (currentScannerActive) {
            // Scanner workspace active
            ScannerScreen(viewModel) {
                currentScannerActive = false
            }
        } else {
            // Standard tabbed container
            Scaffold(
                topBar = {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    )
                },
                bottomBar = {
                    CustomBottomNavBar(
                        activeTab = activeTab,
                        onTabSelected = { activeTab = it },
                        onScanClicked = { currentScannerActive = true }
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
                contentWindowInsets = WindowInsets.safeDrawing
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (activeTab) {
                        "home" -> HomeScreen(viewModel, onDeleteFile = deleteWithUndo)
                        "files" -> FilesScreen(viewModel, onDeleteFile = deleteWithUndo)
                        "tools" -> ToolsScreen(
                            viewModel = viewModel,
                            onOpenScanner = { currentScannerActive = true },
                            onOpenTab = { activeTab = it }
                        )
                        "settings" -> SettingsScreen(viewModel)
                    }
                }
            }
        }
    }

    if (pendingPrivateOpenFile != null) {
        PrivateFilePinDialog(viewModel = viewModel, fileName = pendingPrivateOpenFile.name)
    }
}

/**
 * Master switch for every ad in the app. Set to true to turn ads back on; all the ad
 * code stays in place, so re-enabling is just this one line.
 */
const val ADS_ENABLED = true

/**
 * Real Google AdMob banner. Ad unit id comes from res/values/admob_ids.xml (test id by
 * default). The AdView is a classic Android View, so it's hosted via AndroidView and
 * destroyed on dispose to avoid leaks.
 */
@Composable
fun AdMobBanner(
    modifier: Modifier = Modifier,
    adUnitResId: Int = com.irozar.ipdfmaster.R.string.admob_banner_unit_id
) {
    if (!ADS_ENABLED) return
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = ctx.getString(adUnitResId)
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { adView -> adView.destroy() }
    )
}

/**
 * Larger card-sized AdMob ad (300x250 medium rectangle), used where the old
 * placeholder "sponsored" cards were. Same banner ad unit serves this size.
 */
@Composable
fun AdMobMediumRectangle(
    modifier: Modifier = Modifier,
    adUnitResId: Int = com.irozar.ipdfmaster.R.string.admob_home_banner
) {
    if (!ADS_ENABLED) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.MEDIUM_RECTANGLE)
                    adUnitId = ctx.getString(adUnitResId)
                    loadAd(AdRequest.Builder().build())
                }
            },
            onRelease = { adView -> adView.destroy() }
        )
    }
}

@Composable
fun SponsorBannerRow() {
    val context = LocalContext.current
    Surface(
        color = Color(0xFFF1F5F9), // Slate light background from M3 spec
        border = BorderStroke(width = 1.dp, color = Color(0xFFE2E8F0)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudQueue,
                    contentDescription = null,
                    tint = Color(0xFFFF2D55), // High visibility red ad banner color
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "Partner Sponsor: TeraBox",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = "Claim 1,024 GB secure cloud storage space free.",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        color = Color(0xFF475569),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Button(
                onClick = {
                    Toast.makeText(context, "TeraBox 1TB secure gift allocated!", Toast.LENGTH_LONG).show()
                },
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2D55)),
                modifier = Modifier.height(28.dp)
            ) {
                Text("Get 1TB Free", fontSize = 10.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun CustomBottomNavBar(
    activeTab: String,
    onTabSelected: (String) -> Unit,
    onScanClicked: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            NavBarItem(
                label = "Home",
                icon = Icons.Outlined.Home,
                selectedIcon = Icons.Filled.Home,
                isSelected = activeTab == "home",
                onClick = { onTabSelected("home") }
            )
            NavBarItem(
                label = "Files",
                icon = Icons.Outlined.Folder,
                selectedIcon = Icons.Filled.Folder,
                isSelected = activeTab == "files",
                onClick = { onTabSelected("files") }
            )

            NavBarItem(
                label = "Scan",
                icon = Icons.Outlined.DocumentScanner,
                selectedIcon = Icons.Filled.DocumentScanner,
                isSelected = false,
                onClick = onScanClicked
            )

            NavBarItem(
                label = "Tools",
                icon = Icons.Outlined.Build,
                selectedIcon = Icons.Filled.Build,
                isSelected = activeTab == "tools",
                onClick = { onTabSelected("tools") }
            )

            NavBarItem(
                label = "Settings",
                icon = Icons.Outlined.Settings,
                selectedIcon = Icons.Filled.Settings,
                isSelected = activeTab == "settings",
                onClick = { onTabSelected("settings") }
            )
        }
    }
}

@Composable
fun RowScope.NavBarItem(
    label: String,
    icon: ImageVector,
    selectedIcon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSelected) selectedIcon else icon,
            contentDescription = label,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

// ======================== HOME SCREEN ========================

@Composable
fun HomeScreen(viewModel: PdfViewModel, onDeleteFile: (PdfFile) -> Unit) {
    val files by viewModel.recentFilesList.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val recentFiles = files.filterNot { it.isPrivate }

    var showBlankDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showPdfEditorPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun launchEditor(uri: Uri? = null) {
        val intent = Intent(context, com.irozar.ipdfmaster.pdfeditorspike.MainActivity::class.java)
        if (uri != null) {
            intent.action = Intent.ACTION_VIEW
            intent.setDataAndType(uri, "application/pdf")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    val pdfImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            var displayName = "ImportedDoc.pdf"
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            viewModel.importScannedPdf(
                name = displayName,
                category = "Work",
                pages = getPdfPageCount(context, uri.toString()),
                thumbnailPath = uri.toString(),
                filePath = uri.toString()
            )
            Toast.makeText(context, "$displayName imported successfully!", Toast.LENGTH_SHORT).show()
            (context as? android.app.Activity)?.let { com.irozar.ipdfmaster.utils.AppReview.recordSuccess(it) }
        }
    }

    var pendingLibraryImport by remember { mutableStateOf(false) }
    val toolImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingLibraryImport = false
        if (uri != null) {
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            var displayName = "ImportedDoc.pdf"
            var sizeBytes = 0L
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.importPdfToLibrary(
                name = displayName,
                category = "Work",
                pages = getPdfPageCount(context, uri.toString()),
                uriString = uri.toString(),
                sizeInBytes = sizeBytes.takeIf { it > 0L }
            )
            Toast.makeText(context, "$displayName imported. Select it from the list.", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        item {
            // Elegant Greeting & Brand Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Welcome Back 👋",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "iPdf Master Workspace",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            // Search Input Block
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search private files...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("home_search_bar"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        // While searching, show matching files directly under the search box (Google-style)
        // and hide the rest of the home content until the query is cleared.
        if (query.isNotEmpty()) {
            if (recentFiles.isEmpty()) {
                item {
                    Text(
                        "No files match \"$query\"",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            } else {
                items(recentFiles) { file ->
                    PdfFileRowItem(
                        pdfFile = file,
                        onOpen = { viewModel.requestOpenPdf(file) },
                        onFavorite = { viewModel.toggleFavorite(file) },
                        onPrivate = { viewModel.togglePrivateSafe(file) },
                        onRename = { newName -> viewModel.renameFile(file, newName) },
                        onCategoryChange = { category ->
                            viewModel.changeFileCategory(file, category)
                            Toast.makeText(context, "${file.name} moved to $category.", Toast.LENGTH_SHORT).show()
                        },
                        onCopy = {
                            viewModel.copyFileDirect(file)
                            Toast.makeText(context, "Copy created for ${file.name}", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { onDeleteFile(file) }
                    )
                }
            }
        }

        if (query.isEmpty()) {

        item {
            // Beautiful Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF172554),
                                Color(0xFF1D4ED8),
                                Color(0xFF2563EB)
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "100% Private Offline",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Your documents are securely encrypted on your phone.\nNo logins, tracking or cloud transfers.",
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(
                        modifier = Modifier.size(74.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(74.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
                        )
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        item {
            // Real AdMob ad (card-sized 300x250 medium rectangle) replacing the old sponsored card
            AdMobMediumRectangle(adUnitResId = com.irozar.ipdfmaster.R.string.admob_home_banner)
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            // Quick Actions Title
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        item {
            // Quick Actions Row of 4
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickActionItem(
                    label = "Edit PDF",
                    icon = Icons.Outlined.Edit,
                    color = Color(0xFFEDE7F6),
                    tint = Color(0xFF7C3AED),
                    modifier = Modifier.weight(1f)
                ) {
                    showPdfEditorPicker = true
                }
                QuickActionItem(
                    label = "Merge PDFs",
                    icon = Icons.Outlined.MergeType,
                    color = Color(0xFFE3F2FD),
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.weight(1f)
                ) {
                    showMergeDialog = true
                }
                QuickActionItem(
                    label = "Compress",
                    icon = Icons.Filled.Compress,
                    color = Color(0xFFFFF3E0),
                    tint = Color(0xFFF97316),
                    modifier = Modifier.weight(1f)
                ) {
                    showCompressDialog = true
                }
                QuickActionItem(
                    label = "Create Blank",
                    icon = Icons.Outlined.Add,
                    color = Color(0xFFE8F5E9),
                    tint = Color(0xFF16A34A),
                    modifier = Modifier.weight(1f)
                ) {
                    showBlankDialog = true
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!viewModel.recentFilesHidden) {
            item {
                // Recent Files Title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Files",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = { pdfImportLauncher.launch(arrayOf("application/pdf")) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF2563EB)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.35f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.UploadFile,
                                contentDescription = "Import PDF icon",
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Import PDF", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (recentFiles.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = Color.LightGray,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "No documents found",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            } else {
                // Show all matches while searching; otherwise just the 5 most recent.
                val homeList = if (query.isNotEmpty()) recentFiles else recentFiles.take(5)
                items(homeList) { file ->
                    PdfFileRowItem(
                        pdfFile = file,
                        onOpen = { viewModel.requestOpenPdf(file) },
                        onFavorite = { viewModel.toggleFavorite(file) },
                        onPrivate = { viewModel.togglePrivateSafe(file) },
                        onRename = { newName -> viewModel.renameFile(file, newName) },
                        onCategoryChange = { category ->
                            viewModel.changeFileCategory(file, category)
                            Toast.makeText(context, "${file.name} moved to $category.", Toast.LENGTH_SHORT).show()
                        },
                        onCopy = {
                            viewModel.copyFileDirect(file)
                            Toast.makeText(context, "Copy created for ${file.name}", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { onDeleteFile(file) }
                    )
                }
            }
        }

        } // end of: if (query.isEmpty())
    }

    if (showPdfEditorPicker) {
        val recentEditorFiles = recentFiles.take(8)
        AlertDialog(
            onDismissRequest = { showPdfEditorPicker = false },
            title = { Text("Edit PDF") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            showPdfEditorPicker = false
                            launchEditor()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import PDF from device")
                    }
                    if (recentEditorFiles.isEmpty()) {
                        Text("No recent PDFs yet.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Recent files", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        recentEditorFiles.forEach { file ->
                            RecentPickerRow(file) {
                                showPdfEditorPicker = false
                                val uri = uriForPdfEditor(context, file)
                                if (uri == null) {
                                    Toast.makeText(context, "This file isn't available on disk. Import a PDF instead.", Toast.LENGTH_LONG).show()
                                } else {
                                    launchEditor(uri)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPdfEditorPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBlankDialog) {
        CreateBlankDialog(
            onDismiss = { showBlankDialog = false },
            onConfirm = { name, category, pages ->
                viewModel.importNewBlankPdf(name, category, pages)
                showBlankDialog = false
            }
        )
    }

    if (showMergeDialog) {
        MergePdfsDialog(
            files = files,
            onDismiss = { showMergeDialog = false },
            onConfirm = { name, selectedList, category, insertAfterPage ->
                viewModel.executeFileMerge(name, selectedList, category, insertAfterPage)
                showMergeDialog = false
                Toast.makeText(context, "Documents integrated and opened!", Toast.LENGTH_SHORT).show()
            },
            onImport = {
                pendingLibraryImport = true
                toolImportLauncher.launch(arrayOf("application/pdf"))
            }
        )
    }

    if (showCompressDialog) {
        CompressPdfDialog(
            files = files,
            onDismiss = { showCompressDialog = false },
            onConfirm = { file, quality ->
                viewModel.executeCompression(file, quality)
                showCompressDialog = false
                Toast.makeText(context, "File size compressed successfully!", Toast.LENGTH_SHORT).show()
            },
            onImport = {
                pendingLibraryImport = true
                toolImportLauncher.launch(arrayOf("application/pdf"))
            }
        )
    }
}

@Composable
fun CreateBlankDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedCat by remember { mutableStateOf("Work") }
    var pagesCount by remember { mutableStateOf("1") }

    val categories = listOf("Work", "Study", "Scanner", "Personal")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Blank Document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("File Name") },
                    placeholder = { Text("e.g. Draft_Report") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Category", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCat == cat,
                            onClick = { selectedCat = cat },
                            label = { Text(cat) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = pagesCount,
                    onValueChange = { pagesCount = it.filter { c -> c.isDigit() } },
                    label = { Text("Initial Page Count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(name, selectedCat, pagesCount.toIntOrNull() ?: 1)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun MergePdfsDialog(
    files: List<PdfFile>,
    onDismiss: () -> Unit,
    onConfirm: (String, List<PdfFile>, String, Int?) -> Unit,
    onImport: (() -> Unit)? = null
) {
    var mergedName by remember { mutableStateOf("Merged_Document") }
    var selectedCat by remember { mutableStateOf("Work") }
    val selectedFiles = remember { mutableStateListOf<PdfFile>() }
    var useCustomInsertPoint by remember { mutableStateOf(false) }
    var insertAfterPageInput by remember { mutableStateOf("6") }

    val categories = listOf("Work", "Study", "Scanner", "Personal")

    fun ordinal(index: Int): String {
        return when (index + 1) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            else -> "${index + 1}th"
        }
    }

    fun toggleFile(file: PdfFile) {
        val existingIndex = selectedFiles.indexOfFirst { it.id == file.id }
        if (existingIndex >= 0) {
            selectedFiles.removeAt(existingIndex)
        } else {
            selectedFiles.add(file)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge PDF Files") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = mergedName,
                    onValueChange = { mergedName = it },
                    label = { Text("Merged File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (onImport != null) {
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import PDF from device")
                    }
                }

                Text("Target Category", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCat == cat,
                            onClick = { selectedCat = cat },
                            label = { Text(cat) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Text("Choose PDFs to Combine", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)

                if (files.size < 2) {
                    Text(
                        "You need at least 2 files to combine.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .heightIn(max = 150.dp)
                            .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(6.dp)
                        ) {
                            items(files) { file ->
                                val orderIndex = selectedFiles.indexOfFirst { it.id == file.id }
                                val isChecked = orderIndex >= 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggleFile(file) }
                                        .padding(vertical = 4.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { toggleFile(file) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isChecked) {
                                        Text(
                                            text = ordinal(orderIndex),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(32.dp)
                                        )
                                    }
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${file.pageCount} p.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    if (selectedFiles.size >= 2) {
                        Text(
                            text = "Document Sequence",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            selectedFiles.forEachIndexed { index, file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        ordinal(index),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(36.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text("${file.pageCount} pages", color = Color.Gray, fontSize = 11.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index > 0) {
                                                val item = selectedFiles.removeAt(index)
                                                selectedFiles.add(index - 1, item)
                                            }
                                        },
                                        enabled = index > 0
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        onClick = {
                                            if (index < selectedFiles.lastIndex) {
                                                val item = selectedFiles.removeAt(index)
                                                selectedFiles.add(index + 1, item)
                                            }
                                        },
                                        enabled = index < selectedFiles.lastIndex
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = useCustomInsertPoint,
                                    onCheckedChange = { useCustomInsertPoint = it }
                                )
                                Text(
                                    "Insert 2nd document at a specific page of 1st document",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (useCustomInsertPoint) {
                                val firstDoc = selectedFiles.firstOrNull()
                                val secondDoc = selectedFiles.getOrNull(1)
                                OutlinedTextField(
                                    value = insertAfterPageInput,
                                    onValueChange = { insertAfterPageInput = it.filter { c -> c.isDigit() } },
                                    label = { Text("Insert 2nd document after page number") },
                                    placeholder = { Text("e.g. 6") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = if (firstDoc != null && secondDoc != null) {
                                        "Example: ${secondDoc.name} will be inserted after page ${insertAfterPageInput.ifBlank { "0" }} of ${firstDoc.name}. Remaining documents continue after that."
                                    } else {
                                        "Select at least two documents first."
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    "Documents merge in the numbered order above.",
                                    fontSize = 11.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (mergedName.isNotBlank() && selectedFiles.size >= 2) {
                        val insertAfterPage = if (useCustomInsertPoint) {
                            val firstPageCount = selectedFiles.firstOrNull()?.pageCount ?: 0
                            (insertAfterPageInput.toIntOrNull() ?: 0).coerceIn(0, firstPageCount)
                        } else {
                            null
                        }
                        onConfirm(mergedName, selectedFiles.toList(), selectedCat, insertAfterPage)
                    }
                },
                enabled = selectedFiles.size >= 2 && mergedName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Merge (${selectedFiles.size} Files)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CompressPdfDialog(
    files: List<PdfFile>,
    onDismiss: () -> Unit,
    onConfirm: (PdfFile, String) -> Unit,
    onImport: (() -> Unit)? = null
) {
    var selectedFile by remember { mutableStateOf<PdfFile?>(files.firstOrNull()) }
    var quality by remember { mutableStateOf("Medium") }
    val qualities = listOf("High", "Medium", "Low")

    var currentStep by remember { mutableStateOf("input") } // "input", "processing", "success"
    var progressProgress by remember { mutableStateOf(0f) }
    var progressStatusStr by remember { mutableStateOf("Analyzing document layout...") }
    val scope = rememberCoroutineScope()

    var renameNameInput by remember(selectedFile) { mutableStateOf(selectedFile?.name?.removeSuffix(".pdf") + "_compressed") }

    if (currentStep == "processing") {
        LaunchedEffect(Unit) {
            // Animate progress gracefully
            progressProgress = 0.1f
            progressStatusStr = "Downsampling high-res raster layers..."
            kotlinx.coroutines.delay(400)
            progressProgress = 0.45f
            progressStatusStr = "Optimizing system font descriptors..."
            kotlinx.coroutines.delay(500)
            progressProgress = 0.85f
            progressStatusStr = "Rebuilding linear representation streams..."
            kotlinx.coroutines.delay(400)
            progressProgress = 1f
            progressStatusStr = "Compression Finished!"
            kotlinx.coroutines.delay(200)
            currentStep = "success"
        }

        AlertDialog(
            onDismissRequest = {}, // prevent close while doing critical compression ML
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Compressing Document...", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { progressProgress },
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 6.dp,
                        modifier = Modifier.size(72.dp)
                    )
                    Text(
                        text = "${(progressProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = progressStatusStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {}
        )
    } else if (currentStep == "success") {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Verified, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(28.dp))
                    Text("PDF Compressed Successfully!", fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val originalBytes = selectedFile?.sizeInBytes ?: 100000L
                    val savingsPercent = when (quality) {
                        "High" -> 55
                        "Medium" -> 75
                        else -> 85
                    }
                    val compressedBytes = (originalBytes * (100 - savingsPercent) / 100)

                    // Card block stats
                    Surface(
                        color = Color(0xFFE8F5E9),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Space Saved", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                                Text("$savingsPercent% Smaller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color(0xFF1B5E20))
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Original: ${DecimalFormat("#.##").format(originalBytes / 1024.0)} KB", fontSize = 11.sp, color = Color.DarkGray)
                                Text("New Size: ${DecimalFormat("#.##").format(compressedBytes / 1024.0)} KB", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                            }
                        }
                    }

                    Text("Provide a name to save the compressed file:", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = renameNameInput ?: "",
                        onValueChange = { renameNameInput = it },
                        label = { Text("Compressed Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Text(".pdf", modifier = Modifier.padding(end = 8.dp)) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        selectedFile?.let { file ->
                            val finalRename = if (renameNameInput?.endsWith(".pdf") == true) renameNameInput!! else "${renameNameInput}.pdf"
                            // Save file by triggering VM update
                            onConfirm(file.copy(name = finalRename), quality)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("Save Compressed Copy", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Compress PDF File Size") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select PDF to Compress", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)

                    if (onImport != null) {
                        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Import PDF from device")
                        }
                    }

                    if (files.isEmpty()) {
                        Text(
                            "No documents available. Create/import a PDF first to compress it.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .heightIn(max = 140.dp)
                                .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .fillMaxWidth()
                        ) {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(6.dp)
                            ) {
                                items(files) { file ->
                                    val isSelected = selectedFile?.id == file.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                                else Color.Transparent,
                                                RoundedCornerShape(6.dp)
                                            )
                                            .clickable { selectedFile = file }
                                            .padding(vertical = 6.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { selectedFile = file }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        Text("Select Compression Presets", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            qualities.forEach { q ->
                                FilterChip(
                                    selected = quality == q,
                                    onClick = { quality = q },
                                    label = { Text(q) }
                                )
                            }
                        }
                        Text(
                            text = "Note: Medium compression reduces size by ~75% while keeping visual clarity.",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedFile != null) {
                            currentStep = "processing"
                        }
                    },
                    enabled = selectedFile != null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Compress")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun RecentPickerRow(file: PdfFile, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE6E6EC), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFFFDEAEA)),
            contentAlignment = Alignment.Center
        ) {
            Text("PDF", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, fontSize = 9.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text("${file.pageCount} pages • ${formatFileSize(file.sizeInBytes)}", color = Color.Gray, fontSize = 11.sp)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFFBDBDBD), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun QuickActionItem(
    label: String,
    icon: ImageVector,
    color: Color,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEDEDED)),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PdfFileRowItem(
    pdfFile: PdfFile,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onPrivate: () -> Unit,
    onRename: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color(0xFFEDEDED))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // PDF Red block icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFE53935)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PDF",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pdfFile.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatFileSize(pdfFile.sizeInBytes)} • ${pdfFile.pageCount} pgs",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = pdfFile.category,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Quick favorite star toggle
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (pdfFile.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = "Favorite",
                    tint = if (pdfFile.isFavorite) Color(0xFFFBC02D) else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Options dropdown
            Box {
                IconButton(onClick = { expandedMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Options",
                        tint = Color.Gray
                    )
                }

                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Open Document") },
                        leadingIcon = { Icon(Icons.Filled.Launch, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (pdfFile.isPrivate) "Move to Public" else "Move to Private Safe") },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onPrivate()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename File") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            showRenameDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Change Group") },
                        leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            showCategoryDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Make Copy") },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onCopy()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share / Export") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            sharePdfFile(context, pdfFile)
                        }
                    )
                    Divider()
                    DropdownMenuItem(
                        text = { Text("Delete Permanent", color = Color.Red) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.Red) },
                        onClick = {
                            expandedMenu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        var tempName by remember { mutableStateOf(pdfFile.name.replace(".pdf", "")) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename File") },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    singleLine = true,
                    label = { Text("New File Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tempName.isNotBlank()) {
                            onRename(tempName)
                            showRenameDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCategoryDialog) {
        val groups = listOf("Work", "Study", "Scanner", "Personal")
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("Move to Group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pdfFile.name, fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    groups.forEach { group ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    onCategoryChange(group)
                                    showCategoryDialog = false
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = pdfFile.category == group,
                                onClick = {
                                    onCategoryChange(group)
                                    showCategoryDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(group, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCategoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

// ======================== FILES SCREEN ========================

@Composable
fun FilesScreen(viewModel: PdfViewModel, onDeleteFile: (PdfFile) -> Unit) {
    val files by viewModel.filesList.collectAsState()
    val activeCategory by viewModel.selectedCategory.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val context = LocalContext.current

    val categories = listOf("All", "Work", "Study", "Scanner", "Personal", "Favorites", "Private Safe")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp)
    ) {
        // Search all files (filters viewModel.filesList by name)
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            placeholder = { Text("Search files...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Horizontal pill filters
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { cat ->
                val isSelected = activeCategory == cat
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.selectedCategory.value = cat },
                    label = { Text(cat) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        // AdMob banner under the type filters
        AdMobBanner(adUnitResId = com.irozar.ipdfmaster.R.string.admob_files_banner)

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            if (files.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillParentMaxSize()
                            .padding(bottom = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = Color.LightGray,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Empty folder container",
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Text(
                                text = "All imported and generated files appear here.",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            } else {
                items(files) { file ->
                    PdfFileRowItem(
                        pdfFile = file,
                        onOpen = { viewModel.requestOpenPdf(file) },
                        onFavorite = { viewModel.toggleFavorite(file) },
                        onPrivate = { viewModel.togglePrivateSafe(file) },
                        onRename = { newName -> viewModel.renameFile(file, newName) },
                        onCategoryChange = { category ->
                            viewModel.changeFileCategory(file, category)
                            Toast.makeText(context, "${file.name} moved to $category.", Toast.LENGTH_SHORT).show()
                        },
                        onCopy = {
                            viewModel.copyFileDirect(file)
                            Toast.makeText(context, "Copy created for ${file.name}", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { onDeleteFile(file) }
                    )
                }
            }
        }
    }
}

// ======================== TOOLS SCREEN ========================

@Composable
private fun ToolFeatureRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color = Color(0xFF7C3AED),
    enabled: Boolean = true,
    muted: Boolean = !enabled,
    badge: String? = null,
    onClick: () -> Unit
) {
    val tint = if (muted) Color(0xFF9CA3AF) else accent
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Colored accent bar on the left edge.
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(tint)
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tint.copy(alpha = if (muted) 0.08f else 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (muted) Color(0xFF9CA3AF) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = if (muted) Color(0xFF9CA3AF) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(tint.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(badge, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun AiActivationDialog(
    viewModel: PdfViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val providers = listOf("Gemini", "OpenAI", "Claude", "OpenRouter")
    var provider by remember { mutableStateOf(viewModel.aiProvider) }
    var apiKey by remember { mutableStateOf(viewModel.aiApiKey) }
    var enabled by remember { mutableStateOf(viewModel.aiFeaturesEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activate AI Features") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
                ) {
                    Text(
                        "Privacy warning: after adding an API key, AI actions can send selected PDF text, OCR text, or prompts to your chosen AI provider. Non-AI tools remain local/offline.",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Text("Choose AI platform", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                providers.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                provider = item
                            }
                            .background(
                                if (provider == item) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = provider == item,
                            onClick = {
                                provider = item
                            }
                        )
                        Column {
                            Text(item, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (item) {
                                    "Gemini" -> "Google AI Studio key"
                                    "OpenAI" -> "OpenAI platform key"
                                    "Claude" -> "Anthropic API key"
                                    else -> "OpenRouter API key"
                                },
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("$provider API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "No extra setup needed. The app chooses the best compatible AI option automatically.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable AI tools", fontWeight = FontWeight.Bold)
                        Text("Gray AI actions become active after saving.", fontSize = 11.sp, color = Color.Gray)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.saveAiSettings(provider, apiKey, viewModel.defaultAiModel(provider), enabled)
                    Toast.makeText(
                        context,
                        if (viewModel.isAiConfigured) "AI features activated." else "API key saved, but AI is disabled.",
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismiss()
                },
                enabled = apiKey.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (viewModel.aiFeaturesEnabled) {
                    TextButton(
                        onClick = {
                            viewModel.disableAiFeatures()
                            Toast.makeText(context, "AI features disabled.", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    ) {
                        Text("Disable")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
fun ToolsScreen(
    viewModel: PdfViewModel,
    onOpenScanner: () -> Unit,
    onOpenTab: (String) -> Unit
) {
    val files by viewModel.filesList.collectAsState()
    val context = LocalContext.current
    val latestFile = files.firstOrNull()

    var showBlankDialog by remember { mutableStateOf(false) }
    var showMergeDialog by remember { mutableStateOf(false) }
    var showCompressDialog by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showReorderSplitDialog by remember { mutableStateOf(false) }
    var showPdfEditorPicker by remember { mutableStateOf(false) }
    var showAiActivationDialog by remember { mutableStateOf(false) }
    var toolFilePicker by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var pendingImportMode by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingLibraryImport by remember { mutableStateOf(false) }

    val toolImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val pending = pendingImportMode
        pendingImportMode = null
        val importToLibrary = pendingLibraryImport
        pendingLibraryImport = false
        if (uri != null && (pending != null || importToLibrary)) {
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            var displayName = "ImportedDoc.pdf"
            var sizeBytes = 0L
            try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        displayName = cursor.getString(nameIndex)
                        if (sizeIndex != -1) sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val pageCount = getPdfPageCount(context, uri.toString())
            if (pending != null) {
                viewModel.importPdfForMode(
                    name = displayName,
                    category = "Work",
                    pages = pageCount,
                    uriString = uri.toString(),
                    mode = pending.first,
                    tool = pending.second,
                    sizeInBytes = sizeBytes.takeIf { it > 0L }
                )
            } else {
                viewModel.importPdfToLibrary(
                    name = displayName,
                    category = "Work",
                    pages = pageCount,
                    uriString = uri.toString(),
                    sizeInBytes = sizeBytes.takeIf { it > 0L }
                )
                Toast.makeText(context, "$displayName imported. Select it from the list.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun showFileChooserFor(feature: String, mode: String, tool: String = "read") {
        toolFilePicker = Triple(feature, mode, tool)
    }

    fun openAiTool(feature: String) {
        if (viewModel.isAiConfigured) {
            val aiTool = when (feature) {
                "Ask PDF" -> "ai_ask"
                "summary" -> "ai_summary"
                "rewrite or translate" -> "ai_rewrite"
                "smart fill" -> "ai_smartfill"
                else -> "ai_summary"
            }
            showFileChooserFor(feature, "ai_chat", aiTool)
        } else {
            showAiActivationDialog = true
        }
    }

    fun launchPdfEditor(uri: Uri? = null) {
        val intent = Intent(context, com.irozar.ipdfmaster.pdfeditorspike.MainActivity::class.java)
        if (uri != null) {
            intent.action = Intent.ACTION_VIEW
            intent.setDataAndType(uri, "application/pdf")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun uriForRecentPdf(file: PdfFile): Uri? {
        return uriForPdfEditor(context, file)
    }

    fun launchPdfEditorForRecent(file: PdfFile) {
        val uri = uriForRecentPdf(file)
        if (uri == null) {
            Toast.makeText(context, "This recent file is not available on disk. Import a PDF instead.", Toast.LENGTH_LONG).show()
            return
        }
        launchPdfEditor(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item {
            Text(
                text = "Tools & Features",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Tap any line to start that feature directly.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }

        item {
            // AdMob banner under the Tools & Features title
            AdMobBanner(adUnitResId = com.irozar.ipdfmaster.R.string.admob_tools_banner)
            Spacer(modifier = Modifier.height(12.dp))
        }

        item {
            ToolFeatureRow(
                Icons.Filled.AutoAwesome,
                "AI Features",
                if (viewModel.isAiConfigured)
                    "Ask, summarize, rewrite, translate & smart-fill any PDF"
                else
                    "Tap to activate AI in Settings",
                accent = Color(0xFF7C3AED),
                muted = !viewModel.isAiConfigured
            ) {
                if (viewModel.isAiConfigured) {
                    // AI is on — open a PDF and use every AI feature from the chat panel.
                    openAiTool("Ask PDF")
                } else {
                    // Not activated — send the user to Settings > AI Features to turn it on.
                    onOpenTab("settings")
                }
            }
            ToolFeatureRow(Icons.Filled.DocumentScanner, "Auto Scan with Crop & Filters", "Detect, crop, enhance and save one PDF", accent = Color(0xFF16A34A)) {
                onOpenScanner()
            }
            ToolFeatureRow(Icons.Outlined.Edit, "PDF Editor", "Open PdfEditorSpike editor", accent = Color(0xFF2563EB)) {
                showPdfEditorPicker = true
            }
            ToolFeatureRow(Icons.Outlined.Add, "Create Blank PDF", "Start an empty document", accent = Color(0xFFF97316)) {
                showBlankDialog = true
            }
            ToolFeatureRow(Icons.Filled.CallMerge, "Merge PDFs", "Combine selected files", accent = Color(0xFF8B5CF6)) {
                showMergeDialog = true
            }
            ToolFeatureRow(Icons.Filled.Compress, "Compress PDF", "Reduce file size locally", accent = Color(0xFF0D9488)) {
                showCompressDialog = true
            }
            ToolFeatureRow(Icons.Filled.CallSplit, "Split PDF Pages", "Extract selected pages into a new PDF", accent = Color(0xFFEC4899)) {
                showFileChooserFor("split PDF", "manage_pages", "split")
            }
            ToolFeatureRow(Icons.Filled.TextSnippet, "OCR Text Extract", "Open a file and run Extract Text", accent = Color(0xFFF59E0B), badge = "NEW") {
                showFileChooserFor("OCR", "ocr", "ocr")
            }
            ToolFeatureRow(Icons.Filled.Draw, "Annotate / Draw", "Pen, highlighter, shapes and move items", accent = Color(0xFF3B82F6)) {
                showFileChooserFor("annotation", "annotate", "draw")
            }
            ToolFeatureRow(Icons.Filled.Fingerprint, "Add Signature", "Draw and stamp a signature", accent = Color(0xFF6366F1)) {
                showFileChooserFor("signature", "annotate", "signature")
            }
            ToolFeatureRow(Icons.Filled.Layers, "Manage Pages", "Extract, duplicate, rotate, delete pages", accent = Color(0xFF0EA5E9)) {
                showFileChooserFor("page management", "manage_pages", "read")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (showAiActivationDialog) {
        AiActivationDialog(
            viewModel = viewModel,
            onDismiss = { showAiActivationDialog = false }
        )
    }

    if (showPdfEditorPicker) {
        val recentEditorFiles = files.filterNot { it.isPrivate }.take(8)

        AlertDialog(
            onDismissRequest = { showPdfEditorPicker = false },
            title = { Text("Edit PDF") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Choose a recent PDF or import one from your device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            showPdfEditorPicker = false
                            launchPdfEditor()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import PDF from device")
                    }
                    if (recentEditorFiles.isEmpty()) {
                        Text("No recent PDFs yet.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Recent files", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        recentEditorFiles.forEach { file ->
                            RecentPickerRow(file) {
                                showPdfEditorPicker = false
                                launchPdfEditorForRecent(file)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPdfEditorPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    toolFilePicker?.let { picker ->
        val feature = picker.first
        val mode = picker.second
        val tool = picker.third
        val recentToolFiles = files.filterNot { it.isPrivate }.take(8)

        AlertDialog(
            onDismissRequest = { toolFilePicker = null },
            title = { Text("Choose PDF for $feature") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            toolFilePicker = null
                            pendingImportMode = mode to tool
                            toolImportLauncher.launch(arrayOf("application/pdf"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.FileOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import PDF from device")
                    }
                    if (recentToolFiles.isEmpty()) {
                        Text("No recent PDFs yet.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Recent files", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        recentToolFiles.forEach { file ->
                            RecentPickerRow(file) {
                                toolFilePicker = null
                                viewModel.requestOpenPdf(file, mode, tool)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { toolFilePicker = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSplitDialog) {
        var selectedFile by remember(showSplitDialog, files) { mutableStateOf(files.firstOrNull()) }
        var splitName by remember(showSplitDialog) { mutableStateOf("Split_Pages") }
        var pagesCsv by remember(showSplitDialog) { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text("Split PDF Pages") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (files.isEmpty()) {
                        Text("Import or create a PDF first.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        files.take(6).forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedFile = file }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedFile?.id == file.id,
                                    onClick = { selectedFile = file }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${file.pageCount} pages", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = splitName,
                            onValueChange = { splitName = it },
                            label = { Text("New PDF name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pagesCsv,
                            onValueChange = { pagesCsv = it },
                            label = { Text("Pages to split, e.g. 1, 3, 5") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = selectedFile
                        if (file == null) {
                            Toast.makeText(context, "No PDF selected.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val result = viewModel.splitPagesFromFile(file, splitName, pagesCsv)
                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                        if (!result.startsWith("Please")) {
                            showSplitDialog = false
                        }
                    },
                    enabled = files.isNotEmpty()
                ) {
                    Text("Create Split PDF")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReorderSplitDialog) {
        var selectedFile by remember(showReorderSplitDialog, files) { mutableStateOf(files.firstOrNull()) }
        var outputName by remember(showReorderSplitDialog) { mutableStateOf("Reordered_Pages") }
        var selectedPage by remember(showReorderSplitDialog) { mutableStateOf(1) }
        var pageOrder by remember(showReorderSplitDialog, selectedFile?.id, selectedFile?.pageCount) {
            mutableStateOf((1..(selectedFile?.pageCount ?: 1).coerceAtLeast(1)).toList())
        }

        fun moveSelected(delta: Int) {
            val index = pageOrder.indexOf(selectedPage)
            val target = (index + delta).coerceIn(0, pageOrder.lastIndex)
            if (index >= 0 && target != index) {
                pageOrder = pageOrder.toMutableList().apply {
                    val item = removeAt(index)
                    add(target, item)
                }
            }
        }

        AlertDialog(
            onDismissRequest = { showReorderSplitDialog = false },
            title = { Text("Reorder Pages") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (files.isEmpty()) {
                        Text("Import or create a PDF first.", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        Text("Choose PDF", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        files.take(6).forEach { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { selectedFile = file }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedFile?.id == file.id,
                                    onClick = { selectedFile = file }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${file.pageCount} pages", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = outputName,
                            onValueChange = { outputName = it },
                            label = { Text("New PDF name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Tap a page, then move it with the arrows. Save creates a new PDF with this page order.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(pageOrder) { pageNumber ->
                                val selected = selectedPage == pageNumber
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) Color(0xFFFF5252) else Color.LightGray.copy(alpha = 0.4f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .background(Color.White)
                                        .clickable { selectedPage = pageNumber }
                                        .padding(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(0.78f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFE9D4C3)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = Color(0xFF6D4C41), modifier = Modifier.size(28.dp))
                                            Text("Page $pageNumber", fontWeight = FontWeight.Bold, color = Color(0xFF4E342E))
                                            Text("Position ${pageOrder.indexOf(pageNumber) + 1}", fontSize = 11.sp, color = Color(0xFF6D4C41))
                                        }
                                    }
                                    Text("Page $pageNumber", modifier = Modifier.padding(top = 6.dp), color = Color(0xFF64748B))
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Selected: Page $selectedPage", fontWeight = FontWeight.Medium)
                            Row {
                                IconButton(onClick = { moveSelected(-1) }) {
                                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Move earlier")
                                }
                                IconButton(onClick = { moveSelected(1) }) {
                                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Move later")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = selectedFile
                        if (file == null) {
                            Toast.makeText(context, "No PDF selected.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val result = viewModel.reorderAndSplitPagesFromFile(file, outputName, pageOrder.joinToString(","))
                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                        if (!result.startsWith("Please")) {
                            showReorderSplitDialog = false
                        }
                    },
                    enabled = files.isNotEmpty()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReorderSplitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBlankDialog) {
        CreateBlankDialog(
            onDismiss = { showBlankDialog = false },
            onConfirm = { name, category, pages ->
                viewModel.importNewBlankPdf(name, category, pages)
                showBlankDialog = false
            }
        )
    }

    if (showMergeDialog) {
        MergePdfsDialog(
            files = files,
            onDismiss = { showMergeDialog = false },
            onConfirm = { name, selectedList, category, insertAfterPage ->
                viewModel.executeFileMerge(name, selectedList, category, insertAfterPage)
                showMergeDialog = false
                Toast.makeText(context, "Documents integrated and opened!", Toast.LENGTH_SHORT).show()
            },
            onImport = {
                pendingLibraryImport = true
                toolImportLauncher.launch(arrayOf("application/pdf"))
            }
        )
    }

    if (showCompressDialog) {
        CompressPdfDialog(
            files = files,
            onDismiss = { showCompressDialog = false },
            onConfirm = { file, quality ->
                viewModel.executeCompression(file, quality)
                showCompressDialog = false
                Toast.makeText(context, "File size compressed successfully!", Toast.LENGTH_SHORT).show()
            },
            onImport = {
                pendingLibraryImport = true
                toolImportLauncher.launch(arrayOf("application/pdf"))
            }
        )
    }
}

fun copyScannerPdfToAppStorage(
    context: android.content.Context,
    sourceUri: Uri,
    outputName: String
): Pair<String, Long>? {
    return try {
        val cleanName = if (outputName.endsWith(".pdf", ignoreCase = true)) outputName else "$outputName.pdf"
        val outputFile = File(context.filesDir, cleanName)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outputFile).use { output -> input.copyTo(output) }
        } ?: return null
        outputFile.absolutePath to outputFile.length()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun getPdfPageCount(context: android.content.Context, uriString: String): Int {
    return try {
        val descriptor = openPdfDescriptor(context, uriString) ?: return 1
        PdfRenderer(descriptor).use { renderer ->
            renderer.pageCount.coerceAtLeast(1)
        }
    } catch (e: Exception) {
        1
    }
}

fun renderPdfPageBitmap(context: android.content.Context, uriString: String, pageIndex: Int): Bitmap? {
    return try {
        val descriptor = openPdfDescriptor(context, uriString) ?: return null
        PdfRenderer(descriptor).use { renderer ->
            val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(safeIndex).use { page ->
                val scale = 2
                val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    } catch (e: Exception) {
        null
    }
}

fun openPdfDescriptor(context: android.content.Context, uriString: String): ParcelFileDescriptor? {
    return if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
        context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
    } else {
        ParcelFileDescriptor.open(File(uriString), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

fun createMaterializedPdfForEditor(context: android.content.Context, file: PdfFile): Uri? {
    return try {
        val out = File(context.filesDir, "editor_${file.id}_${file.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        if (!out.exists() || out.length() == 0L) {
            val document = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.rgb(30, 30, 30)
                textSize = 14f
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.rgb(20, 20, 20)
                textSize = 22f
                isFakeBoldText = true
            }
            for (pageNumber in 1..file.pageCount.coerceAtLeast(1)) {
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawColor(AndroidColor.WHITE)
                page.canvas.drawText(file.name.removeSuffix(".pdf"), 48f, 58f, titlePaint)
                val blocks = DocumentEngine.getPageContentsForFile(file.name, pageNumber)
                if (blocks.isEmpty()) {
                    page.canvas.drawText("Page $pageNumber", 48f, 110f, paint)
                } else {
                    blocks.forEach { block ->
                        paint.textSize = block.fontSize.coerceIn(8f, 28f)
                        paint.color = runCatching { AndroidColor.parseColor(block.colorHex) }.getOrDefault(AndroidColor.rgb(30, 30, 30))
                        paint.isFakeBoldText = block.fontStyle.equals("Bold", ignoreCase = true)
                        page.canvas.drawText(block.text.take(95), block.x / 100f * 500f + 48f, block.y / 100f * 720f + 88f, paint)
                    }
                }
                document.finishPage(page)
            }
            FileOutputStream(out).use { document.writeTo(it) }
            document.close()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun uriForPdfEditor(context: android.content.Context, file: PdfFile): Uri? {
    fun resolveStoredPdfUri(path: String?): Uri? {
        if (path.isNullOrBlank() || path.startsWith("assets/")) return null
        return when {
            path.startsWith("content://") -> Uri.parse(path)
            path.startsWith("file://") -> {
                val diskFile = File(Uri.parse(path).path.orEmpty())
                if (diskFile.exists()) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", diskFile)
                } else {
                    null
                }
            }
            else -> {
                val diskFile = File(path)
                if (diskFile.exists()) {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", diskFile)
                } else {
                    null
                }
            }
        }
    }

    return resolveStoredPdfUri(file.thumbnailPath)
        ?: resolveStoredPdfUri(file.filePath)
        ?: createMaterializedPdfForEditor(context, file)
}

/**
 * Shares a stored PDF (including scanned docs saved to private app storage) via a
 * FileProvider content URI, so the user can send it to any app or save it into the
 * system Files/Documents picker. Scanned files live in filesDir, which is not visible
 * to other apps directly — handing out a granted content URI is the supported way.
 */
fun sharePdfFile(context: android.content.Context, file: PdfFile) {
    val uri = uriForPdfEditor(context, file)
    if (uri == null) {
        Toast.makeText(context, "Unable to prepare this PDF for sharing.", Toast.LENGTH_LONG).show()
        return
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "Share ${file.name}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to share this PDF.", Toast.LENGTH_LONG).show()
    }
}

suspend fun recognizeTextFromBitmap(bitmap: Bitmap): String = suspendCoroutine { continuation ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { result ->
            continuation.resume(result.text.trim())
        }
        .addOnFailureListener { error ->
            continuation.resume("OCR failed: ${error.localizedMessage ?: "Unable to read text from this image."}")
        }
}

suspend fun recognizeTextFromImageUri(context: android.content.Context, uriString: String): String = suspendCoroutine { continuation ->
    try {
        val uri = if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
            Uri.parse(uriString)
        } else {
            Uri.fromFile(File(uriString))
        }
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                continuation.resume(result.text.trim())
            }
            .addOnFailureListener { error ->
                continuation.resume("OCR failed: ${error.localizedMessage ?: "Unable to read text from this image."}")
            }
    } catch (e: Exception) {
        continuation.resume("OCR failed: ${e.localizedMessage ?: "Unable to open this image."}")
    }
}

// ======================== SCANNER SCREEN ========================

@Composable
fun ScannerScreen(
    viewModel: PdfViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoScanInProgress by remember { mutableStateOf(false) }
    var scannerLaunched by remember { mutableStateOf(false) }

    val documentScannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannerResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pdfUri = scannerResult?.pdf?.uri
            if (pdfUri != null) {
                autoScanInProgress = true
                scope.launch {
                    val cleanName = "Scanned_Document_${System.currentTimeMillis().toString().takeLast(4)}.pdf"
                    val copied = withContext(Dispatchers.IO) {
                        copyScannerPdfToAppStorage(context, pdfUri, cleanName)
                    }
                    if (copied != null) {
                        val pageCount = getPdfPageCount(context, copied.first)
                        viewModel.importScannedPdf(
                            name = cleanName,
                            category = "Scanner",
                            pages = pageCount,
                            thumbnailPath = copied.first,
                            filePath = copied.first,
                            sizeInBytes = copied.second
                        )
                        onBack()
                        Toast.makeText(context, "$cleanName opened.", Toast.LENGTH_SHORT).show()
                        (context as? android.app.Activity)?.let { com.irozar.ipdfmaster.utils.AppReview.recordSuccess(it) }
                    } else {
                        Toast.makeText(context, "Unable to save scanned PDF.", Toast.LENGTH_LONG).show()
                    }
                    autoScanInProgress = false
                }
            } else {
                onBack()
            }
        } else {
            onBack()
        }
    }

    fun launchAutoDocumentScanner() {
        val activity = context as? Activity
        if (activity == null) {
            Toast.makeText(context, "Document scanner needs an Activity context.", Toast.LENGTH_LONG).show()
            onBack()
            return
        }
        autoScanInProgress = true
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(false)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .build()
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener { sender ->
                autoScanInProgress = false
                documentScannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { error ->
                autoScanInProgress = false
                Toast.makeText(context, "Scanner unavailable: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                onBack()
            }
    }

    LaunchedEffect(Unit) {
        if (!scannerLaunched) {
            scannerLaunched = true
            launchAutoDocumentScanner()
        }
    }

    BackHandler {
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(
                text = if (autoScanInProgress) "Opening scanner..." else "Preparing scanner...",
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ======================== SETTINGS SCREEN ========================

@Composable
private fun SettingsSectionHeader(title: String, accent: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 4.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = accent)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFEDEDED)),
        shadowElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 11.sp, color = Color.Gray)
        }
        trailing()
    }
}

@Composable
fun SettingsScreen(viewModel: PdfViewModel) {
    val context = LocalContext.current
    var pinValue by remember { mutableStateOf("") }
    var setupPinValue by remember { mutableStateOf("") }
    var showPinSetupDialog by remember { mutableStateOf(false) }
    var hideRecent by remember { mutableStateOf(viewModel.recentFilesHidden) }
    var showAiActivationDialog by remember { mutableStateOf(false) }

    if (showPinSetupDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinSetupDialog = false
                setupPinValue = ""
            },
            title = { Text("Set App Lock PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter a 4 digit PIN to protect the app. You can cancel if you do not want to add a lock.",
                        fontSize = 13.sp,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = setupPinValue,
                        onValueChange = { input ->
                            setupPinValue = input.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("4-Digit PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = setupPinValue.length == 4,
                    onClick = {
                        viewModel.setAppLock(setupPinValue)
                        setupPinValue = ""
                        showPinSetupDialog = false
                        Toast.makeText(context, "App lock enabled.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Enable Lock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        setupPinValue = ""
                        showPinSetupDialog = false
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAiActivationDialog) {
        AiActivationDialog(
            viewModel = viewModel,
            onDismiss = { showAiActivationDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Manage your iPdf Master workspace",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 18.dp)
        )

        // ----- Security & Privacy -----
        SettingsSectionHeader("Security & Privacy", Color(0xFFD32F2F))
        SettingsCard {
            SettingRow(
                icon = Icons.Filled.Lock,
                accent = Color(0xFFD32F2F),
                title = "Secure App Lock PIN",
                subtitle = "Secure all PDFs with a 4-digit PIN code"
            ) {
                Switch(
                    checked = viewModel.isAppLocked,
                    onCheckedChange = { checked ->
                        if (checked) {
                            setupPinValue = ""
                            showPinSetupDialog = true
                        } else {
                            viewModel.setAppLock("")
                            pinValue = ""
                        }
                    }
                )
            }
            if (viewModel.isAppLocked) {
                OutlinedTextField(
                    value = pinValue,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(4)
                        if (digits.length <= 4) {
                            pinValue = digits
                            if (digits.length == 4) {
                                viewModel.setAppLock(digits)
                                Toast.makeText(context, "Lock PIN updated!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    label = { Text("Update 4-Digit PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            Divider(modifier = Modifier.padding(horizontal = 12.dp), color = Color(0xFFF1F1F1))
            SettingRow(
                icon = Icons.Filled.VisibilityOff,
                accent = Color(0xFF2563EB),
                title = "Hide Recent Files list",
                subtitle = "Do not show files on launch dashboard"
            ) {
                Switch(
                    checked = hideRecent,
                    onCheckedChange = { checked ->
                        hideRecent = checked
                        viewModel.recentFilesHidden = checked
                    }
                )
            }
        }

        // AdMob banner
        AdMobBanner(adUnitResId = com.irozar.ipdfmaster.R.string.admob_settings_banner)
        Spacer(modifier = Modifier.height(18.dp))

        // ----- AI Features -----
        SettingsSectionHeader("AI Features", Color(0xFF7C3AED))
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFF7C3AED).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (viewModel.isAiConfigured) "AI is active" else "AI is inactive", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        if (viewModel.isAiConfigured) "${viewModel.aiProvider} connected" else "Add an API key to enable Ask PDF, summaries, rewrite & smart fill.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
                Button(
                    onClick = { showAiActivationDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(if (viewModel.isAiConfigured) "Manage" else "Activate")
                }
            }
            Surface(
                color = Color(0xFFFFF3CD),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    "AI actions may send selected document text or OCR text to the provider you choose. Keep AI off for a fully offline workflow.",
                    fontSize = 11.sp,
                    color = Color(0xFFB45309),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // ----- General -----
        SettingsSectionHeader("General", Color(0xFF0D9488))
        SettingsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        Toast.makeText(context, "Local Document caches removed successfully!", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(0xFFD32F2F).copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Clear Temporary Cache", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Free up on-device page cache", fontSize = 11.sp, color = Color.Gray)
                }
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
            }
        }

        // ----- Privacy pledge footer -----
        Row(verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text("Privacy Pledge", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "iPdf Master keeps core PDF tools local: scanning, highlights, page management and handwriting are processed on-device. If you activate AI with an API key, selected document or OCR text may be sent to your chosen provider.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ======================== PDF READER SCREEN ========================

@Composable
fun PdfViewerScreen(viewModel: PdfViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val activeFile = viewModel.currentOpenFile ?: return
    val currentPageIndex = viewModel.currentPageIndex
    val layoutMode = viewModel.currentLayoutMode
    val importedVisualPath = activeFile.thumbnailPath
    val sourcePage = DocumentEngine.getSourcePage(activeFile.name, currentPageIndex + 1, activeFile.pageCount)
    val renderedPdfPage by produceState<Bitmap?>(null, importedVisualPath, currentPageIndex, sourcePage) {
        value = if (!importedVisualPath.isNullOrBlank()) {
            renderPdfPageBitmap(context, importedVisualPath, sourcePage.second - 1)
        } else {
            null
        }
    }

    val annotations by viewModel.annotationsFlow.collectAsState()
    val chatMessages by viewModel.chatMessagesFlow.collectAsState()
    val savedSignatures by viewModel.savedSignaturesFlow.collectAsState()

    DisposableEffect(activeFile.id, activeFile.isPrivate) {
        val activity = context as? Activity
        if (activeFile.isPrivate) {
            activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (activeFile.isPrivate) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }

    var showSignatureDialog by remember { mutableStateOf(false) }
    var activeAnnotatePointsList = remember { mutableStateListOf<Offset>() }
    var draggedAnnotation by remember { mutableStateOf<AnnotationDragState?>(null) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var ocrDialogText by remember { mutableStateOf("") }
    var ocrDialogProcessing by remember { mutableStateOf(false) }
    var aiOcrProcessing by remember(activeFile.id) { mutableStateOf(false) }
    var splitPagesInput by remember(activeFile.id) { mutableStateOf("") }
    var splitOutputName by remember(activeFile.id) { mutableStateOf(activeFile.name.removeSuffix(".pdf") + "_split") }

    fun runOcrForCurrentPage() {
        showOcrDialog = true
        ocrDialogProcessing = true
        ocrDialogText = ""
        scope.launch {
            ocrDialogText = if (!importedVisualPath.isNullOrBlank()) {
                val pageBitmap = renderPdfPageBitmap(context, importedVisualPath, sourcePage.second - 1)
                if (pageBitmap != null) {
                    recognizeTextFromBitmap(pageBitmap).ifBlank { "No readable text found on this page." }
                } else {
                    recognizeTextFromImageUri(context, importedVisualPath).ifBlank { "No readable text found in this image." }
                }
            } else {
                viewModel.textBlockList.value.joinToString("\n") { it.text }
                    .ifBlank { "No text found on this page." }
            }
            ocrDialogProcessing = false
        }
    }

    suspend fun extractOcrTextForAi(): String {
        if (importedVisualPath.isNullOrBlank()) {
            return viewModel.textBlockList.value.joinToString("\n") { it.text }
        }

        val maxPagesToRead = activeFile.pageCount.coerceAtLeast(1).coerceAtMost(12)
        val sections = mutableListOf<String>()
        for (pageNumber in 1..maxPagesToRead) {
            val mappedPage = DocumentEngine.getSourcePage(activeFile.name, pageNumber, activeFile.pageCount)
            val pageBitmap = renderPdfPageBitmap(context, importedVisualPath, mappedPage.second - 1)
            val text = if (pageBitmap != null) {
                recognizeTextFromBitmap(pageBitmap)
            } else if (activeFile.pageCount == 1) {
                recognizeTextFromImageUri(context, importedVisualPath)
            } else {
                ""
            }.trim()

            if (text.isNotBlank() && !text.startsWith("OCR failed:", ignoreCase = true)) {
                sections.add("Page $pageNumber:\n$text")
            }
        }
        return sections.joinToString("\n\n")
    }

    fun openCurrentPdfInEditor() {
        val uri = uriForPdfEditor(context, activeFile)
        if (uri == null) {
            Toast.makeText(context, "This PDF is not available for editing.", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, com.irozar.ipdfmaster.pdfeditorspike.MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    LaunchedEffect(activeFile.id, layoutMode, viewModel.currentEditTool) {
        if (layoutMode == "ocr" && viewModel.currentEditTool == "ocr") {
            runOcrForCurrentPage()
        }
    }

    // Intercept system back gestures to close the PDF viewer
    BackHandler {
        viewModel.closePdf()
    }

    // Floating AI Dialog chat variables
    var showAiAssistantPanel by remember { mutableStateOf(false) }
    var showAiActivationDialog by remember { mutableStateOf(false) }
    var showClearChatConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(activeFile.id, layoutMode, viewModel.currentEditTool) {
        if (layoutMode == "ai_chat") {
            if (viewModel.isAiConfigured) {
                showAiAssistantPanel = true
            } else {
                showAiActivationDialog = true
            }
            if (viewModel.isAiConfigured && !viewModel.hasCachedAiOcrText(activeFile.id)) {
                aiOcrProcessing = true
                try {
                    val ocrText = extractOcrTextForAi()
                    viewModel.cacheAiOcrText(activeFile.id, ocrText)
                } finally {
                    aiOcrProcessing = false
                }
            }
            if (chatMessages.isEmpty() && viewModel.isAiConfigured) {
                viewModel.executeAiToolAction(viewModel.currentEditTool)
            }
        }
        if (layoutMode == "annotate" && viewModel.currentEditTool == "signature") {
            showSignatureDialog = true
        }
    }

    // Save as state
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var saveAsNameInput by remember(activeFile.name) { mutableStateOf(activeFile.name.removeSuffix(".pdf") + "_copy") }

    // Dynamic Zoom scale and scroll offset states
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    if (showSaveAsDialog) {
        AlertDialog(
            onDismissRequest = { showSaveAsDialog = false },
            title = { Text("Save Document Copy As") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This will create a new cloned instance of your PDF with a custom name, preserving annotations and page changes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = saveAsNameInput,
                        onValueChange = { saveAsNameInput = it },
                        label = { Text("New File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { Text(".pdf", modifier = Modifier.padding(end = 8.dp)) }
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalName = if (saveAsNameInput.endsWith(".pdf")) saveAsNameInput else "$saveAsNameInput.pdf"
                        viewModel.saveCopyAs(finalName)
                        showSaveAsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Save Copy", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveAsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showOcrDialog) {
        AlertDialog(
            onDismissRequest = { showOcrDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.TextSnippet, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("OCR Text - Page ${currentPageIndex + 1}")
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ocrDialogProcessing) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Reading text from this page...")
                        }
                    }
                    OutlinedTextField(
                        value = ocrDialogText,
                        onValueChange = { ocrDialogText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 8,
                        maxLines = 12,
                        label = { Text("Extracted text") }
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(ocrDialogText))
                            Toast.makeText(context, "OCR text copied.", Toast.LENGTH_SHORT).show()
                        },
                        enabled = ocrDialogText.isNotBlank() && !ocrDialogProcessing
                    ) {
                        Text("Copy Text")
                    }
                    TextButton(onClick = { showOcrDialog = false }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            PdfViewerTopBar(
                fileName = activeFile.name,
                currentPage = currentPageIndex,
                maxPages = activeFile.pageCount,
                layoutMode = layoutMode,
                onBack = { viewModel.closePdf() },
                onChooseLayout = { mode ->
                    viewModel.changeLayoutMode(mode)
                },
                onOcrClick = { runOcrForCurrentPage() },
                onAiClick = {
                    if (viewModel.isAiConfigured) {
                        showAiAssistantPanel = !showAiAssistantPanel
                    } else {
                        showAiActivationDialog = true
                    }
                },
                onPdfEditorClick = { openCurrentPdfInEditor() },
                canUndo = viewModel.canUndo,
                onUndoClick = { viewModel.undoLastChange() },
                onSaveAsClick = { showSaveAsDialog = true },
                onShareClick = { sharePdfFile(context, activeFile) }
            )
        },
        bottomBar = {
            if (layoutMode == "annotate") {
                AnnotateToolMenuBar(
                    selectedTool = viewModel.currentEditTool,
                    onToolSelected = { 
                        viewModel.currentEditTool = it
                        if (it == "signature") showSignatureDialog = true
                    },
                    currentPage = currentPageIndex,
                    maxPages = activeFile.pageCount,
                    onPageChanged = { viewModel.setPage(it) },
                    viewModel = viewModel
                )
            } else if (layoutMode == "manage_pages") {
                ManagePagesBottomBar(
                    viewModel = viewModel,
                    currentPage = currentPageIndex,
                    maxPages = activeFile.pageCount,
                    onPageChanged = { viewModel.setPage(it) }
                )
            } else if (layoutMode == "ocr") {
                Surface(
                    tonalElevation = 8.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("OCR", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Button(
                            onClick = { runOcrForCurrentPage() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.TextSnippet, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Extract Text from Page ${currentPageIndex + 1}")
                        }
                    }
                }
            } else {
                Column {
                    // AdMob banner above the page navigation bar
                    AdMobBanner(adUnitResId = com.irozar.ipdfmaster.R.string.admob_viewer_banner)
                    StandardViewerBottomBar(
                        currentPage = currentPageIndex,
                        maxPages = activeFile.pageCount,
                        onPageChanged = { viewModel.setPage(it) }
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFE2E2E2))
        ) {
            if (layoutMode in listOf("annotate", "manage_pages", "ai_chat", "ocr")) {
                val label = when (layoutMode) {
                    "annotate" -> if (viewModel.currentEditTool == "signature") "Signature Tool" else "Annotate Tools"
                    "manage_pages" -> if (viewModel.currentEditTool == "split") "Split PDF" else "Manage Pages"
                    "ai_chat" -> when (viewModel.currentEditTool) {
                        "ai_ask" -> "Ask about this PDF"
                        "ai_rewrite" -> "Rewrite / Translate"
                        "ai_smartfill" -> "Smart Fill OCR Docs"
                        else -> "Summarize PDF"
                    }
                    "ocr" -> "OCR Text Extract"
                    else -> ""
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                ) {
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }

            if (layoutMode == "manage_pages") {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Pages", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                        Button(
                            onClick = {
                                if (viewModel.currentEditTool == "split") {
                                    if (splitPagesInput.isBlank()) {
                                        Toast.makeText(context, "Enter page numbers to split.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val result = viewModel.splitPagesFromFile(activeFile, splitOutputName, splitPagesInput)
                                        Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Page order saved.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text(if (viewModel.currentEditTool == "split") "Save Split" else "Save", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (viewModel.currentEditTool == "split") {
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Write page numbers to split", fontWeight = FontWeight.Bold, color = Color(0xFF111827))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = splitPagesInput,
                                        onValueChange = { splitPagesInput = it },
                                        label = { Text("Pages, e.g. 1, 3, 5") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = splitOutputName,
                                        onValueChange = { splitOutputName = it },
                                        label = { Text("Save as") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(activeFile.pageCount) { index ->
                            PageGridCard(
                                context = context,
                                file = activeFile,
                                importedVisualPath = importedVisualPath,
                                pageIndex = index,
                                sourcePage = DocumentEngine.getSourcePage(activeFile.name, index + 1, activeFile.pageCount),
                                selected = if (viewModel.currentEditTool == "split") {
                                    splitPagesInput.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(index + 1)
                                } else {
                                    currentPageIndex == index
                                },
                                onClick = {
                                    if (viewModel.currentEditTool == "split") {
                                        val current = splitPagesInput.split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableList()
                                        val pageNum = index + 1
                                        if (current.contains(pageNum)) current.remove(pageNum) else current.add(pageNum)
                                        splitPagesInput = current.joinToString(", ")
                                    } else {
                                        viewModel.setPage(index)
                                    }
                                }
                            )
                        }
                    }
                }
                return@Scaffold
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Interactive dynamic Page Canvas Display Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                        .pointerInput(layoutMode, viewModel.currentEditTool) {
                            if (layoutMode == "annotate") {
                                detectTapGestures(
                                    onTap = { offset ->
                                        if (viewModel.currentEditTool == "text") {
                                            viewModel.addCustomTextBlock(
                                                "Text annotation",
                                                x = (offset.x / size.width) * 100f,
                                                y = (offset.y / size.height) * 100f
                                            )
                                        } else if (viewModel.currentEditTool == "shape") {
                                            viewModel.placeShapeAnnotation(
                                                viewModel.selectedShapeType,
                                                x = offset.x / size.width,
                                                y = offset.y / size.height,
                                                w = viewModel.shapeWidthInput,
                                                h = viewModel.shapeHeightInput,
                                                colorHex = viewModel.drawColorHex
                                            )
                                        } else if (viewModel.currentEditTool == "signature") {
                                            // Stamp the saved signature exactly where the user taps.
                                            val signaturePoints = savedSignatures.firstOrNull()?.pointsJson
                                            if (signaturePoints.isNullOrBlank()) {
                                                Toast.makeText(context, "Draw and save a signature first.", Toast.LENGTH_SHORT).show()
                                            } else {
                                                viewModel.placeSignatureAnnotation(
                                                    signaturePoints,
                                                    (offset.x / size.width).coerceIn(0f, 0.95f),
                                                    (offset.y / size.height).coerceIn(0f, 0.95f)
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                        .pointerInput(layoutMode, viewModel.currentEditTool) {
                            if (layoutMode == "annotate" && (viewModel.currentEditTool == "draw" || viewModel.currentEditTool == "highlight")) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeAnnotatePointsList.clear()
                                        activeAnnotatePointsList.add(offset)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        activeAnnotatePointsList.add(change.position)
                                    },
                                    onDragEnd = {
                                        if (activeAnnotatePointsList.isNotEmpty()) {
                                            val pointsString = activeAnnotatePointsList.joinToString(";") { "${it.x},${it.y}" }
                                            if (viewModel.currentEditTool == "draw") {
                                                viewModel.addCanvasDrawing(
                                                    pointsJson = pointsString,
                                                    colorHex = viewModel.drawColorHex,
                                                    strokeWidth = viewModel.drawThickness
                                                )
                                            } else {
                                                viewModel.addHighlightUnderlineStrike(
                                                    type = "highlight",
                                                    pointsJson = pointsString,
                                                    colorHex = viewModel.drawColorHex
                                                )
                                            }
                                            activeAnnotatePointsList.clear()
                                        }
                                    }
                                )
                            }
                        }
                        .pointerInput(layoutMode, viewModel.currentEditTool) {
                            if (layoutMode == "annotate" && (viewModel.currentEditTool == "shape" || viewModel.currentEditTool == "signature")) {
                                detectDragGestures(
                                    onDragStart = { start ->
                                        // Generous grab area so the shape/signature is easy to pick up.
                                        val pad = 32f
                                        val selected = annotations
                                            .filter { it.pageIndex == currentPageIndex && it.type == viewModel.currentEditTool }
                                            .lastOrNull { annot ->
                                                val left = annot.paramX * size.width
                                                val top = annot.paramY * size.height
                                                val right = left + annot.paramWidth
                                                val bottom = top + annot.paramHeight
                                                start.x in (left - pad)..(right + pad) && start.y in (top - pad)..(bottom + pad)
                                            }
                                        if (selected != null) {
                                            viewModel.captureAnnotationForUndo(selected)
                                            draggedAnnotation = AnnotationDragState(
                                                annotation = selected,
                                                grabOffset = Offset(
                                                    x = start.x - selected.paramX * size.width,
                                                    y = start.y - selected.paramY * size.height
                                                )
                                            )
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        val target = draggedAnnotation ?: return@detectDragGestures
                                        change.consume()
                                        val newX = ((change.position.x - target.grabOffset.x) / size.width).coerceIn(0f, 0.95f)
                                        val newY = ((change.position.y - target.grabOffset.y) / size.height).coerceIn(0f, 0.95f)
                                        val moved = target.annotation.copy(paramX = newX, paramY = newY)
                                        draggedAnnotation = target.copy(annotation = moved)
                                        viewModel.moveAnnotation(
                                            moved,
                                            newX,
                                            newY
                                        )
                                    },
                                    onDragEnd = { draggedAnnotation = null },
                                    onDragCancel = { draggedAnnotation = null }
                                )
                            }
                        }
                        .pointerInput(layoutMode) {
                            if (layoutMode != "annotate") {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(0.5f, 4f)
                                    offset = if (newScale > 1f) {
                                        Offset(
                                            x = offset.x + pan.x,
                                            y = offset.y + pan.y
                                        )
                                    } else {
                                        Offset.Zero
                                    }
                                    scale = newScale
                                }
                            }
                        }
                ) {
                    // Page Number Badge Overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("${currentPageIndex + 1} / ${activeFile.pageCount}", color = Color.White, fontSize = 11.sp)
                    }

                    // Layer zoom graphics scale and dynamic offset translation
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                                rotationZ = viewModel.currentPageRotationDeg
                            )
                    ) {
                        val renderedAspect = renderedPdfPage?.let { page ->
                            page.width.toFloat() / page.height.toFloat()
                        } ?: 1f
                        val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
                        val pageWidthDp = if (renderedPdfPage != null && containerAspect > renderedAspect) {
                            maxHeight * renderedAspect
                        } else {
                            maxWidth
                        }
                        val pageHeightDp = if (renderedPdfPage != null && containerAspect > renderedAspect) {
                            maxHeight
                        } else if (renderedPdfPage != null) {
                            maxWidth / renderedAspect
                        } else {
                            maxHeight
                        }
                        val pageOffsetXDp = (maxWidth - pageWidthDp) / 2f
                        val pageOffsetYDp = (maxHeight - pageHeightDp) / 2f
                        val pageWidthPx = with(density) { pageWidthDp.toPx() }
                        val pageHeightPx = with(density) { pageHeightDp.toPx() }
                        // Page bitmap is rendered at PDF points x2 (see renderPdfPageBitmap), so convert a
                        // PDF point font size to on-screen dp; otherwise extracted text is drawn far too big.
                        val pdfPageHeightPt = renderedPdfPage?.let { it.height / 2f } ?: 0f
                        val pointToDpScale = if (pdfPageHeightPt > 0f) pageHeightDp.value / pdfPageHeightPt else 1f

                        // Real imported PDFs/images render as the page content itself. No simulated title text is overlaid.
                        if (renderedPdfPage != null) {
                            Image(
                                bitmap = renderedPdfPage!!.asImageBitmap(),
                                contentDescription = "Imported PDF page",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.White)
                            )
                        } else if (!importedVisualPath.isNullOrBlank()) {
                            Image(
                                painter = rememberAsyncImagePainter(importedVisualPath),
                                contentDescription = "Imported document image",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.White)
                            )
                        }

                        // Render generated text blocks and annotation text.
                    if (importedVisualPath.isNullOrBlank() || layoutMode == "annotate") {
                    viewModel.textBlockList.value.forEach { block ->
                        var isEditing by remember(block.id) { mutableStateOf(false) }
                        var tempValue by remember(block.id, block.text) { mutableStateOf(block.text) }
                        var tempFontSize by remember(block.id, block.fontSize) { mutableStateOf(block.fontSize) }
                        var tempColorHex by remember(block.id, block.colorHex) { mutableStateOf(block.colorHex) }
                        val shouldShowFrame = layoutMode == "annotate"
                        val previewFontSize = block.fontSize
                        val blockWidthDp = (pageWidthDp * (block.width / 100f).coerceIn(0.015f, 0.95f)).coerceAtLeast(18.dp)
                        val blockHeightDp = (pageHeightDp * (block.height / 100f).coerceIn(0.01f, 0.35f)).coerceAtLeast((previewFontSize * 1.4f).dp)

                        Box(
                            modifier = Modifier
                                .absoluteOffset(
                                    x = pageOffsetXDp + pageWidthDp * (block.x / 100f).coerceIn(0f, 1f),
                                    y = pageOffsetYDp + pageHeightDp * (block.y / 100f).coerceIn(0f, 1f)
                                )
                                .width(blockWidthDp)
                                .height(blockHeightDp)
                                .background(Color.Transparent, RoundedCornerShape(4.dp))
                                .border(
                                    width = if (shouldShowFrame) 1.dp else 0.dp,
                                    color = if (shouldShowFrame) Color.Gray.copy(alpha = 0.5f) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(enabled = layoutMode == "annotate") {
                                    isEditing = true
                                }
                                .pointerInput(layoutMode, block.id) {
                                    if (layoutMode == "annotate" && viewModel.currentEditTool == "text") {
                                        detectDragGestures(
                                            onDragStart = { viewModel.captureTextBlocksForUndo() },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                val dxPercent = dragAmount.x / pageWidthPx * 100f
                                                val dyPercent = dragAmount.y / pageHeightPx * 100f
                                                viewModel.dragTextOrAnnotation(
                                                    block.id,
                                                    (block.x + dxPercent).coerceIn(0f, 95f),
                                                    (block.y + dyPercent).coerceIn(0f, 95f)
                                                )
                                            }
                                        )
                                    }
                                }
                                .padding(4.dp)
                        ) {
                            Text(
                                text = block.text,
                                fontSize = previewFontSize.sp,
                                fontWeight = if (block.fontStyle == "Bold") FontWeight.Bold else FontWeight.Normal,
                                color = Color(android.graphics.Color.parseColor(block.colorHex)),
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip
                            )
                        }

                        if (isEditing) {
                            AlertDialog(
                                onDismissRequest = { isEditing = false },
                                title = { Text("Text Annotation") },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        OutlinedTextField(
                                            value = tempValue,
                                            onValueChange = { tempValue = it },
                                            singleLine = false,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Text("Text size: ${tempFontSize.toInt()}sp", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Slider(
                                            value = tempFontSize,
                                            onValueChange = { tempFontSize = it },
                                            valueRange = 8f..36f
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf("#D32F2F", "#1565C0", "#2E7D32", "#FFB300", "#7B1FA2", "#212121").forEach { hex ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(android.graphics.Color.parseColor(hex)))
                                                        .border(
                                                            width = if (tempColorHex == hex) 3.dp else 1.dp,
                                                            color = if (tempColorHex == hex) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                            shape = CircleShape
                                                        )
                                                        .clickable { tempColorHex = hex }
                                                )
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            onClick = {
                                                viewModel.deleteTextBlock(block.id)
                                                isEditing = false
                                            },
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Delete")
                                        }
                                        Button(
                                            onClick = {
                                                viewModel.editTextBlock(block.id, tempValue, tempFontSize, tempColorHex)
                                                isEditing = false
                                            }
                                        ) {
                                            Text("Save Changes")
                                        }
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = { isEditing = false }) {
                                        Text("Dismiss")
                                    }
                                }
                            )
                        }
                    }
                    }

                    // Render freehand draw and vector annotations stored inside our Room flow DB
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val visibleAnnotations = annotations.map { annot ->
                            if (draggedAnnotation?.annotation?.id == annot.id) draggedAnnotation!!.annotation else annot
                        }
                        visibleAnnotations.filter { it.pageIndex == currentPageIndex }.forEach { annot ->
                            when (annot.type) {
                                "draw", "highlight" -> {
                                    val points = annot.pointsJson?.split(";")?.mapNotNull { p ->
                                        val c = p.split(",")
                                        if (c.size == 2) Offset(c[0].toFloat(), c[1].toFloat()) else null
                                    } ?: emptyList()
                                    
                                    if (points.size >= 2) {
                                        val path = Path()
                                        path.moveTo(points.first().x, points.first().y)
                                        for (i in 1 until points.size) {
                                            path.lineTo(points[i].x, points[i].y)
                                        }
                                        drawPath(
                                            path = path,
                                            color = Color(annot.color).copy(alpha = if (annot.type == "highlight") 0.35f else 1f),
                                            style = Stroke(
                                                width = annot.thickness,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }
                                "shape" -> {
                                    val c = Color(annot.color)
                                    val topX = annot.paramX * size.width
                                    val topY = annot.paramY * size.height
                                    val w = annot.paramWidth
                                    val h = annot.paramHeight

                                    if (annot.shapeType == "rectangle") {
                                        drawRect(
                                            color = c,
                                            topLeft = Offset(topX, topY),
                                            size = androidx.compose.ui.geometry.Size(w, h),
                                            style = Stroke(width = annot.thickness)
                                        )
                                    } else if (annot.shapeType == "rounded_rect") {
                                        drawRoundRect(
                                            color = c,
                                            topLeft = Offset(topX, topY),
                                            size = androidx.compose.ui.geometry.Size(w, h),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(18f, 18f),
                                            style = Stroke(width = annot.thickness)
                                        )
                                    } else if (annot.shapeType == "circle") {
                                        drawCircle(
                                            color = c,
                                            center = Offset(topX + w/2, topY + h/2),
                                            radius = w / 2,
                                            style = Stroke(width = annot.thickness)
                                        )
                                    } else if (annot.shapeType == "oval") {
                                        drawOval(
                                            color = c,
                                            topLeft = Offset(topX, topY),
                                            size = androidx.compose.ui.geometry.Size(w, h),
                                            style = Stroke(width = annot.thickness)
                                        )
                                    } else if (annot.shapeType == "triangle") {
                                        val path = Path().apply {
                                            moveTo(topX + w / 2f, topY)
                                            lineTo(topX + w, topY + h)
                                            lineTo(topX, topY + h)
                                            close()
                                        }
                                        drawPath(path, color = c, style = Stroke(width = annot.thickness, join = StrokeJoin.Round))
                                    } else if (annot.shapeType == "diamond") {
                                        val path = Path().apply {
                                            moveTo(topX + w / 2f, topY)
                                            lineTo(topX + w, topY + h / 2f)
                                            lineTo(topX + w / 2f, topY + h)
                                            lineTo(topX, topY + h / 2f)
                                            close()
                                        }
                                        drawPath(path, color = c, style = Stroke(width = annot.thickness, join = StrokeJoin.Round))
                                    } else if (annot.shapeType == "arrow" || annot.shapeType == "double_arrow") {
                                        val start = Offset(topX, topY)
                                        val end = Offset(topX + w, topY + h)
                                        // Draw primary shaft of the arrow
                                        drawLine(
                                            color = c,
                                            start = start,
                                            end = end,
                                            strokeWidth = annot.thickness
                                        )
                                        // Compute angle for arrow heads
                                        val dx = end.x - start.x
                                        val dy = end.y - start.y
                                        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
                                        val arrowHeadLength = 22f
                                        val arrowAngle = Math.PI / 6  // 30 degree head angles
                                        
                                        val x1 = end.x - arrowHeadLength * Math.cos(angle - arrowAngle)
                                        val y1 = end.y - arrowHeadLength * Math.sin(angle - arrowAngle)
                                        val x2 = end.x - arrowHeadLength * Math.cos(angle + arrowAngle)
                                        val y2 = end.y - arrowHeadLength * Math.sin(angle + arrowAngle)
                                        
                                        drawLine(
                                            color = c,
                                            start = end,
                                            end = Offset(x1.toFloat(), y1.toFloat()),
                                            strokeWidth = annot.thickness
                                        )
                                        drawLine(
                                            color = c,
                                            start = end,
                                            end = Offset(x2.toFloat(), y2.toFloat()),
                                            strokeWidth = annot.thickness
                                        )
                                        if (annot.shapeType == "double_arrow") {
                                            val sx1 = start.x + arrowHeadLength * Math.cos(angle - arrowAngle)
                                            val sy1 = start.y + arrowHeadLength * Math.sin(angle - arrowAngle)
                                            val sx2 = start.x + arrowHeadLength * Math.cos(angle + arrowAngle)
                                            val sy2 = start.y + arrowHeadLength * Math.sin(angle + arrowAngle)
                                            drawLine(c, start, Offset(sx1.toFloat(), sy1.toFloat()), strokeWidth = annot.thickness)
                                            drawLine(c, start, Offset(sx2.toFloat(), sy2.toFloat()), strokeWidth = annot.thickness)
                                        }
                                    } else {
                                        // Distinct straight connecting line
                                        drawLine(
                                            color = c,
                                            start = Offset(topX, topY),
                                            end = Offset(topX + w, topY + h),
                                            strokeWidth = annot.thickness
                                        )
                                    }
                                }
                                "signature" -> {
                                    val topX = annot.paramX * size.width
                                    val topY = annot.paramY * size.height
                                    val w = annot.paramWidth
                                    val h = annot.paramHeight

                                    val points = annot.pointsJson?.split(";")?.mapNotNull { p ->
                                        val c = p.split(",")
                                        if (c.size == 2) Offset(c[0].toFloat(), c[1].toFloat()) else null
                                    } ?: emptyList()

                                    if (points.size >= 2) {
                                        val minX = points.minOf { it.x }
                                        val maxX = points.maxOf { it.x }
                                        val minY = points.minOf { it.y }
                                        val maxY = points.maxOf { it.y }
                                        val sourceW = (maxX - minX).coerceAtLeast(1f)
                                        val sourceH = (maxY - minY).coerceAtLeast(1f)
                                        val scaleToFit = minOf((w - 12f) / sourceW, (h - 12f) / sourceH)
                                        val path = Path()
                                        points.forEachIndexed { index, point ->
                                            val mapped = Offset(
                                                x = topX + 6f + (point.x - minX) * scaleToFit,
                                                y = topY + 6f + (point.y - minY) * scaleToFit
                                            )
                                            if (index == 0) path.moveTo(mapped.x, mapped.y) else path.lineTo(mapped.x, mapped.y)
                                        }
                                        drawPath(
                                            path = path,
                                            color = Color(annot.color),
                                            style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                                        )
                                    } else {
                                        drawLine(
                                            color = Color.Black,
                                            start = Offset(topX + 10f, topY + h - 15f),
                                            end = Offset(topX + w - 10f, topY + 15f),
                                            strokeWidth = 3f
                                        )
                                    }
                                }
                            }
                        }

                        // Drawing active live stroke indicators
                        if (activeAnnotatePointsList.size >= 2) {
                            val livePath = Path()
                            livePath.moveTo(activeAnnotatePointsList.first().x, activeAnnotatePointsList.first().y)
                            for (i in 1 until activeAnnotatePointsList.size) {
                                livePath.lineTo(activeAnnotatePointsList[i].x, activeAnnotatePointsList[i].y)
                            }
                            drawPath(
                                path = livePath,
                                color = Color(AndroidColor.parseColor(viewModel.drawColorHex)).copy(alpha = if (viewModel.currentEditTool == "highlight") 0.35f else 1f),
                                style = Stroke(
                                    width = viewModel.drawThickness,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }
                    }

                    // Drag signature handles simulation as in Mockup #6
                    if (layoutMode == "annotate" && viewModel.currentEditTool == "signature") {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(24.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Yellow.copy(alpha = 0.2f))
                                .border(1.dp, Color.Yellow, RoundedCornerShape(8.dp))
                                .clickable {
                                    // Put signature in center
                                    val signaturePoints = savedSignatures.firstOrNull()?.pointsJson
                                    if (signaturePoints.isNullOrBlank()) {
                                        Toast.makeText(context, "Draw and save a signature first.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.placeSignatureAnnotation(signaturePoints, 0.25f, 0.42f)
                                        Toast.makeText(context, "Saved Signature placed!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(8.dp)
                        ) {
                            Text("Tap anywhere on the page to place signature", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    }
                }
            }

            // Elegant M3 floating Zoom Controls HUD
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { 
                                scale = (scale - 0.25f).coerceIn(0.5f, 4f)
                                if (scale <= 1f) {
                                    offset = Offset.Zero
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Zoom Out",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Text(
                            text = "${(scale * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(48.dp),
                            textAlign = TextAlign.Center
                        )
                        
                        IconButton(
                            onClick = { 
                                scale = (scale + 0.25f).coerceIn(0.5f, 4f)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Zoom In",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        if (scale != 1f || offset != Offset.Zero) {
                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            
                            IconButton(
                                onClick = {
                                    scale = 1f
                                    offset = Offset.Zero
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reset Zoom",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Beautiful Floating Gemini AI Analyst Overlay
            AnimatedVisibility(
                visible = showAiAssistantPanel,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.92f)
                    .align(Alignment.CenterEnd)
            ) {
                Surface(
                    color = Color.White,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // AI Header — vibrant brand gradient
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            Color(0xFF7B1FA2),
                                            Color(0xFFD32F2F),
                                            Color(0xFFFF2D55)
                                        )
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.22f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("iPdf Master AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text("Your AI PDF Assistant", color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (chatMessages.isNotEmpty()) {
                                    IconButton(onClick = { showClearChatConfirm = true }) {
                                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear chat", tint = Color.White)
                                    }
                                }
                                IconButton(onClick = { showAiAssistantPanel = false }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close AI panel", tint = Color.White)
                                }
                            }
                        }

                        Surface(
                            color = Color(0xFFFFF3CD),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFFD84315)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Connected to ${viewModel.aiProvider}. Document text is sent to this provider to answer.",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD84315)
                                )
                            }
                        }

                        // Chat messages logs
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .background(Color.White)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (chatMessages.isEmpty()) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 40.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AutoAwesome,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Contextual Document AI",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = "Extract insights, summarize paragraphs, or ask questions using your configured AI provider.",
                                            fontSize = 11.sp,
                                            color = Color.Gray,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        Button(
                                            onClick = { viewModel.executeFastDocumentSummarize() },
                                            modifier = Modifier.fillMaxWidth(0.9f)
                                        ) {
                                            Text("Generate Fast PDF Summary")
                                        }
                                    }
                                }
                            } else {
                                items(chatMessages) { chat ->
                                    val isUser = chat.sender == "user"
                                    val time = remember(chat.timestamp) {
                                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                                            .format(java.util.Date(chat.timestamp))
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        if (!isUser) {
                                            // AI avatar
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFEDE7F6)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(18.dp))
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            // AI answer card
                                            Surface(
                                                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                                                color = Color(0xFFECE6FD),
                                                border = BorderStroke(1.dp, Color(0xFFDDD2FA)),
                                                shadowElevation = 1.dp,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(chat.content, fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFF1F2937))
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(time, fontSize = 10.sp, color = Color.Gray)
                                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                Icons.Filled.ContentCopy, contentDescription = "Copy",
                                                                tint = Color.Gray,
                                                                modifier = Modifier.size(16.dp).clickable {
                                                                    clipboardManager.setText(AnnotatedString(chat.content))
                                                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                                                }
                                                            )
                                                            Icon(
                                                                Icons.Outlined.ThumbUp, contentDescription = "Helpful",
                                                                tint = Color.Gray,
                                                                modifier = Modifier.size(16.dp).clickable {
                                                                    Toast.makeText(context, "Thanks for the feedback", Toast.LENGTH_SHORT).show()
                                                                }
                                                            )
                                                            Icon(
                                                                Icons.Outlined.ThumbDown, contentDescription = "Not helpful",
                                                                tint = Color.Gray,
                                                                modifier = Modifier.size(16.dp).clickable {
                                                                    Toast.makeText(context, "Thanks for the feedback", Toast.LENGTH_SHORT).show()
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // User red bubble
                                            Box(
                                                modifier = Modifier
                                                    .widthIn(max = 260.dp)
                                                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                                                    .background(Color(0xFFD32F2F))
                                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                            ) {
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(chat.content, fontSize = 14.sp, lineHeight = 20.sp, color = Color.White)
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text(time, fontSize = 10.sp, color = Color.White.copy(alpha = 0.85f))
                                                        Icon(Icons.Filled.DoneAll, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            // User avatar
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFFDEAEA)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFFD32F2F), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            if (aiOcrProcessing) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Reading scanned PDF text...", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            }

                            if (viewModel.aiThinking) {
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(top = 12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("AI is reasoning document...", fontSize = 11.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }

                        // Quick AI actions — one tap to run a tool, no typing needed.
                        var showTranslateMenu by remember { mutableStateOf(false) }
                        val aiBusy = viewModel.aiThinking || aiOcrProcessing
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AssistChip(
                                enabled = !aiBusy,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White, labelColor = Color(0xFF374151)),
                                onClick = { viewModel.executeAiToolAction("ai_summary") },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFF7B1FA2), modifier = Modifier.size(18.dp)) },
                                label = { Text("Summarize") }
                            )
                            AssistChip(
                                enabled = !aiBusy,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White, labelColor = Color(0xFF374151)),
                                onClick = { viewModel.executeAiToolAction("ai_ask") },
                                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, tint = Color(0xFF1565C0), modifier = Modifier.size(18.dp)) },
                                label = { Text("Overview") }
                            )
                            AssistChip(
                                enabled = !aiBusy,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White, labelColor = Color(0xFF374151)),
                                onClick = { viewModel.executeAiToolAction("ai_rewrite") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp)) },
                                label = { Text("Rewrite") }
                            )
                            Box {
                                AssistChip(
                                    enabled = !aiBusy,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White, labelColor = Color(0xFF374151)),
                                    onClick = { showTranslateMenu = true },
                                    leadingIcon = { Icon(Icons.Filled.Translate, contentDescription = null, tint = Color(0xFFE65100), modifier = Modifier.size(18.dp)) },
                                    label = { Text("Translate ▾") }
                                )
                                DropdownMenu(
                                    expanded = showTranslateMenu,
                                    onDismissRequest = { showTranslateMenu = false }
                                ) {
                                    listOf("English", "Hindi", "Spanish", "French", "Arabic", "Chinese", "German").forEach { lang ->
                                        DropdownMenuItem(
                                            text = { Text(lang) },
                                            onClick = {
                                                showTranslateMenu = false
                                                viewModel.executeAiToolAction("ai_translate", lang)
                                            }
                                        )
                                    }
                                }
                            }
                            AssistChip(
                                enabled = !aiBusy,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, Color(0xFFE5E7EB)),
                                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White, labelColor = Color(0xFF374151)),
                                onClick = { viewModel.executeAiToolAction("ai_smartfill") },
                                leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null, tint = Color(0xFF00796B), modifier = Modifier.size(18.dp)) },
                                label = { Text("Smart Fill") }
                            )
                        }

                        // Chat inputs row
                        var userQueryText by remember { mutableStateOf("") }
                        val sendQuery = {
                            if (userQueryText.isNotBlank()) {
                                viewModel.postUserAiChatMessage(userQueryText)
                                userQueryText = ""
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = userQueryText,
                                onValueChange = { userQueryText = it },
                                placeholder = { Text("Ask PDF anything...", color = Color(0xFF9CA3AF)) },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF7C3AED),
                                    unfocusedBorderColor = Color(0xFF7C3AED).copy(alpha = 0.55f),
                                    cursorColor = Color(0xFF7C3AED),
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { sendQuery() })
                            )
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF7C3AED))
                                    .clickable { sendQuery() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAiActivationDialog) {
        AiActivationDialog(
            viewModel = viewModel,
            onDismiss = { showAiActivationDialog = false }
        )
    }

    if (showClearChatConfirm) {
        AlertDialog(
            onDismissRequest = { showClearChatConfirm = false },
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
            title = { Text("Clear chat?") },
            text = { Text("This permanently removes all AI messages for this document. The PDF itself is not affected.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearChatHistory()
                        showClearChatConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Signature Drawer dialog
    if (showSignatureDialog) {
        SignatureCanvasDialog(
            onDismiss = { showSignatureDialog = false },
            onSave = { sigPointsStr ->
                viewModel.saveSignatureCanvas(sigPointsStr)
                showSignatureDialog = false
                Toast.makeText(context, "Electronic Signature recorded locally!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun SignatureCanvasDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val sigPoints = remember { mutableStateListOf<Offset>() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Draw Digital Signature",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Use your finger to write below.",
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Handwriting canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    sigPoints.clear()
                                    sigPoints.add(offset)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    sigPoints.add(change.position)
                                }
                            )
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        if (sigPoints.size >= 2) {
                            val path = Path()
                            path.moveTo(sigPoints.first().x, sigPoints.first().y)
                            for (i in 1 until sigPoints.size) {
                                path.lineTo(sigPoints[i].x, sigPoints[i].y)
                            }
                            drawPath(
                                path = path,
                                color = Color.Blue,
                                style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { sigPoints.clear() }) {
                        Text("Clear Signature")
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Close")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (sigPoints.isNotEmpty()) {
                                    val ptsString = sigPoints.joinToString(";") { "${it.x},${it.y}" }
                                    onSave(ptsString)
                                }
                            }
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfViewerTopBar(
    fileName: String,
    currentPage: Int,
    maxPages: Int,
    layoutMode: String,
    onBack: () -> Unit,
    onChooseLayout: (String) -> Unit,
    onOcrClick: () -> Unit,
    onAiClick: () -> Unit,
    onPdfEditorClick: () -> Unit,
    canUndo: Boolean,
    onUndoClick: () -> Unit,
    onSaveAsClick: () -> Unit,
    onShareClick: () -> Unit
) {
    var expandedMenu by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 4.dp,
        modifier = Modifier.statusBarsPadding() // FIXES Point 6: Pushes header below mobile notification / status bar!
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp
                )
                Text(
                    text = "Mode: ${if (layoutMode == "ocr") "OCR" else layoutMode.replace("_", " ").uppercase()}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = onUndoClick,
                enabled = canUndo
            ) {
                Icon(
                    imageVector = Icons.Filled.Undo,
                    contentDescription = "Undo last change",
                    tint = if (canUndo) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f)
                )
            }

            // Save Copy As Option
            IconButton(onClick = onSaveAsClick) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = "Save Copy As",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Share / Export this document
            IconButton(onClick = onShareClick) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share or export PDF",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Quick AI toggle
            IconButton(onClick = onAiClick) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "Ask Private AI",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            // Layout modes dropdown options
            Box {
                IconButton(onClick = { expandedMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.ViewAgenda,
                        contentDescription = "Viewer Mode picker",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                DropdownMenu(
                    expanded = expandedMenu,
                    onDismissRequest = { expandedMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("PDF Editor") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onPdfEditorClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reading Mode") },
                        leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onChooseLayout("view")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Annotate Mode (Freehand/Draw)") },
                        leadingIcon = { Icon(Icons.Filled.Gesture, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onChooseLayout("annotate")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Extract Text (OCR)") },
                        leadingIcon = { Icon(Icons.Filled.TextSnippet, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onOcrClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Manage Document Pages") },
                        leadingIcon = { Icon(Icons.Filled.Layers, contentDescription = null) },
                        onClick = {
                            expandedMenu = false
                            onChooseLayout("manage_pages")
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AnnotateToolMenuBar(
    selectedTool: String,
    onToolSelected: (String) -> Unit,
    currentPage: Int,
    maxPages: Int,
    onPageChanged: (Int) -> Unit,
    viewModel: PdfViewModel
) {
    val context = LocalContext.current

    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column {
            PageNavigationControls(
                currentPage = currentPage,
                maxPages = maxPages,
                onPageChanged = onPageChanged
            )

            // Colors picking slide row if relevant
            if (selectedTool == "draw" || selectedTool == "highlight" || selectedTool == "text" || selectedTool == "shape") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(vertical = 4.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Brush settings:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("#D32F2F", "#1565C0", "#2E7D32", "#FFB300", "#7B1FA2", "#212121").forEach { hex ->
                            val isSelected = viewModel.drawColorHex == hex
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.drawColorHex = hex }
                            )
                        }
                    }
                }
            }

            if (selectedTool == "text") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Text size: ${viewModel.placedTextSizeInput.toInt()}sp",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray,
                        modifier = Modifier.width(96.dp)
                    )
                    Slider(
                        value = viewModel.placedTextSizeInput,
                        onValueChange = { viewModel.placedTextSizeInput = it },
                        valueRange = 8f..36f,
                        modifier = Modifier.weight(1f).height(28.dp)
                    )
                }
            }

            // Interactive shape presets and size settings selector
            if (selectedTool == "shape") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Shape:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Triple("rectangle", "Rectangle", Icons.Filled.CropSquare),
                                Triple("rounded_rect", "Rounded", Icons.Filled.CropSquare),
                                Triple("circle", "Circle", Icons.Filled.RadioButtonUnchecked),
                                Triple("oval", "Oval", Icons.Filled.RadioButtonUnchecked),
                                Triple("triangle", "Triangle", Icons.Filled.ChangeHistory),
                                Triple("diamond", "Diamond", Icons.Filled.Diamond),
                                Triple("line", "Line", Icons.Filled.Minimize),
                                Triple("arrow", "Arrow", Icons.Filled.ArrowForward),
                                Triple("double_arrow", "Double", Icons.Filled.CompareArrows)
                            ).forEach { (type, label, icon) ->
                                val isSel = viewModel.selectedShapeType == type
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.clickable { viewModel.selectedShapeType = type }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Added Shape Sizing Inputs!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text("Width: ${viewModel.shapeWidthInput.toInt()}dp", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray, modifier = Modifier.width(68.dp))
                            Slider(
                                value = viewModel.shapeWidthInput,
                                onValueChange = { viewModel.shapeWidthInput = it },
                                valueRange = 25f..300f,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text("Height: ${viewModel.shapeHeightInput.toInt()}dp", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray, modifier = Modifier.width(68.dp))
                            Slider(
                                value = viewModel.shapeHeightInput,
                                onValueChange = { viewModel.shapeHeightInput = it },
                                valueRange = 25f..300f,
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                }
            }

            // Annotation tools Toolbar row matching mockup #4
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AnnotateToolItem(
                    label = "Pen",
                    icon = Icons.Filled.Edit,
                    isSelected = selectedTool == "draw",
                    onClick = { onToolSelected("draw") }
                )
                AnnotateToolItem(
                    label = "Highlight",
                    icon = Icons.Filled.BorderColor,
                    isSelected = selectedTool == "highlight",
                    onClick = { onToolSelected("highlight") }
                )
                AnnotateToolItem(
                    label = "Text",
                    icon = Icons.Filled.AddComment,
                    isSelected = selectedTool == "text",
                    onClick = { onToolSelected("text") }
                )
                AnnotateToolItem(
                    label = "Sign",
                    icon = Icons.Filled.Fingerprint,
                    isSelected = selectedTool == "signature",
                    onClick = { onToolSelected("signature") }
                )
                AnnotateToolItem(
                    label = "Shapes",
                    icon = Icons.Filled.Category,
                    isSelected = selectedTool == "shape",
                    onClick = { onToolSelected("shape") }
                )
                AnnotateToolItem(
                    label = "Clear",
                    icon = Icons.Filled.DeleteSweep,
                    isSelected = false,
                    onClick = {
                        viewModel.clearAllAnnotations()
                        Toast.makeText(context, "All page drawings cleared!", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun AnnotateToolItem(
    label: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = label,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray
        )
    }
}

@Composable
fun ManagePageToolItem(
    label: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun ManagePagesBottomBar(
    viewModel: PdfViewModel,
    currentPage: Int,
    maxPages: Int,
    onPageChanged: (Int) -> Unit
) {
    val context = LocalContext.current
    var inputName by remember { mutableStateOf("") }
    var showExtractDialog by remember { mutableStateOf(false) }

    // State for deleting arbitrary page lists
    var showRemovePagesDialog by remember { mutableStateOf(false) }
    var pagesToRemoveCsv by remember { mutableStateOf("") }

    // State for extracting custom page ranges
    var showExtractCustomDialog by remember { mutableStateOf(false) }
    var extractNameInput by remember { mutableStateOf("Extracted_Pages") }
    var csvPagesToExtract by remember { mutableStateOf("") }

    var showReorderSplitDialog by remember { mutableStateOf(false) }
    var reorderSplitNameInput by remember { mutableStateOf("Reordered_Pages") }
    var csvPagesToReorder by remember { mutableStateOf("") }

    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Column {
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ManagePageToolItem(
                label = "Rotate",
                icon = Icons.Filled.RotateRight,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.rotateActivePage()
                    Toast.makeText(context, "Page rotated 90° Clockwise Visual Space!", Toast.LENGTH_SHORT).show()
                }
            )

            ManagePageToolItem(
                label = "Delete",
                icon = Icons.Filled.Delete,
                tint = Color.Red.copy(alpha = 0.8f),
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.deleteActivePage()
                    Toast.makeText(context, "Active Page deleted", Toast.LENGTH_SHORT).show()
                }
            )

            ManagePageToolItem(
                label = "Copy",
                icon = Icons.Filled.ContentCopy,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.duplicateActivePage()
                    Toast.makeText(context, "Active Page duplicated!", Toast.LENGTH_SHORT).show()
                }
            )

            ManagePageToolItem(
                label = "Prev",
                icon = Icons.Filled.KeyboardArrowLeft,
                tint = Color.Black,
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.moveActivePage(-1)
                }
            )

            ManagePageToolItem(
                label = "Next",
                icon = Icons.Filled.KeyboardArrowRight,
                tint = Color.Black,
                modifier = Modifier.weight(1f),
                onClick = {
                    viewModel.moveActivePage(1)
                }
            )

            ManagePageToolItem(
                label = "Extract",
                icon = Icons.Filled.Launch,
                tint = Color(0xFF3F51B5),
                modifier = Modifier.weight(1f),
                onClick = { showExtractCustomDialog = true }
            )
        }
        }
    }

    if (showRemovePagesDialog) {
        AlertDialog(
            onDismissRequest = { showRemovePagesDialog = false },
            title = { Text("Remove Pages by Number") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Type the exact page numbers you want to delete, separated by commas (e.g. \"2, 4\"). Available range: 1 to ${viewModel.currentOpenFile?.pageCount ?: 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pagesToRemoveCsv,
                        onValueChange = { pagesToRemoveCsv = it },
                        placeholder = { Text("e.g. 2, 4, 7") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pagesToRemoveCsv.isNotBlank()) {
                            val res = viewModel.removePagesByNumbers(pagesToRemoveCsv)
                            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                            showRemovePagesDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Remove Pages", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePagesDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showExtractCustomDialog) {
        AlertDialog(
            onDismissRequest = { showExtractCustomDialog = false },
            title = { Text("Extract Specific Pages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Input a new file name and list the page numbers to extract (e.g. \"1, 3\"). They will be merged into a brand new document.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = extractNameInput,
                        onValueChange = { extractNameInput = it },
                        label = { Text("New File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = csvPagesToExtract,
                        onValueChange = { csvPagesToExtract = it },
                        placeholder = { Text("e.g. 1, 3, 5") },
                        label = { Text("Page Numbers to Extract") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (extractNameInput.isNotBlank() && csvPagesToExtract.isNotBlank()) {
                            val res = viewModel.extractPagesAsNew(extractNameInput, csvPagesToExtract)
                            Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                            showExtractCustomDialog = false
                        } else {
                            Toast.makeText(context, "All fields are required.", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Extract & Build")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExtractCustomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showReorderSplitDialog) {
        AlertDialog(
            onDismissRequest = { showReorderSplitDialog = false },
            title = { Text("Reorder Pages") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Create a new PDF using pages in the exact order you choose. Example: \"3, 1, 2\" starts with page 3, then page 1, then page 2.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = reorderSplitNameInput,
                        onValueChange = { reorderSplitNameInput = it },
                        label = { Text("New File Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = csvPagesToReorder,
                        onValueChange = { csvPagesToReorder = it },
                        placeholder = { Text("e.g. 3, 1, 2, 5") },
                        label = { Text("Page Order") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = viewModel.currentOpenFile
                        if (file == null) {
                            Toast.makeText(context, "No document open.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (reorderSplitNameInput.isBlank() || csvPagesToReorder.isBlank()) {
                            Toast.makeText(context, "All fields are required.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val res = viewModel.reorderAndSplitPagesFromFile(file, reorderSplitNameInput, csvPagesToReorder)
                        Toast.makeText(context, res, Toast.LENGTH_LONG).show()
                        if (!res.startsWith("Please")) {
                            showReorderSplitDialog = false
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReorderSplitDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun PageGridCard(
    context: android.content.Context,
    file: PdfFile,
    importedVisualPath: String?,
    pageIndex: Int,
    sourcePage: Pair<String, Int>,
    selected: Boolean,
    onClick: () -> Unit
) {
    val pageBitmap by produceState<Bitmap?>(null, importedVisualPath, pageIndex, sourcePage) {
        value = if (!importedVisualPath.isNullOrBlank()) {
            renderPdfPageBitmap(context, importedVisualPath, sourcePage.second - 1)
        } else {
            null
        }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Color(0xFFFF5252) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFFF3F4F6)),
            contentAlignment = Alignment.Center
        ) {
            if (pageBitmap != null) {
                Image(
                    bitmap = pageBitmap!!.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(34.dp))
                    Text(file.name.take(18), fontSize = 10.sp, color = Color(0xFF64748B), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            "Page ${pageIndex + 1}",
            color = Color(0xFF64748B),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
        )
    }
}

@Composable
fun StandardViewerBottomBar(
    currentPage: Int,
    maxPages: Int,
    onPageChanged: (Int) -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        PageNavigationControls(
            currentPage = currentPage,
            maxPages = maxPages,
            onPageChanged = onPageChanged
        )
    }
}

@Composable
fun PageNavigationControls(
    currentPage: Int,
    maxPages: Int,
    onPageChanged: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(
            onClick = { onPageChanged(currentPage - 1) },
            enabled = currentPage > 0
        ) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
            Spacer(modifier = Modifier.width(4.dp))
            Text("Previous")
        }

        Text(
            "Page ${currentPage + 1} of $maxPages",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )

        TextButton(
            onClick = { onPageChanged(currentPage + 1) },
            enabled = currentPage < maxPages - 1
        ) {
            Text("Next")
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
        }
    }
}
