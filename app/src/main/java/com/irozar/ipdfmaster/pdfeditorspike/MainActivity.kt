package com.irozar.ipdfmaster.pdfeditorspike

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.irozar.ipdfmaster.R
import com.irozar.ipdfmaster.databinding.ActivityMainBinding
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream.AppendMode
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private enum class Tool { SELECT, MOVE, TYPE, IMAGE }
    private enum class OverlayMode { EDIT_EXISTING, ADD_TEXT }

    private val TEAL = 0xFFEF233C.toInt()
    private val GRAY = 0xFF64748B.toInt()

    private var tool = Tool.SELECT
    private var workingFile: File? = null
    private var hasUnsavedChanges = false
    private var exitAfterSave = false
    private var activeRecord: DocumentRecord? = null
    private lateinit var store: DocumentStore
    private lateinit var homeShell: LinearLayout
    private lateinit var homeContent: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerContent: LinearLayout
    private var deletedRecord: DocumentRecord? = null
    private var deletedBackup: File? = null
    private var pageCount = 0
    private var currentPage = 0
    private var pageHeightPts = 792f
    private var lines: List<TextBlock> = emptyList()
    private var words: List<WordBlock> = emptyList()

    private var overlayMode = OverlayMode.EDIT_EXISTING
    private var editingBlock: TextBlock? = null
    private var addBaselineX = 0f
    private var addBaselineY = 0f
    private var typeSize = 14f
    private var colorRGB = intArrayOf(0, 0, 0)
    private var matchOriginalColor = true   // when editing, keep the original text colour unless the user picks one
    private var typeBold = false
    private var typeItalic = false
    private var typeFontName = "Poppins"

    private var selectedWord: WordBlock? = null
    private var selectedLine: TextBlock? = null
    private var selectedParagraph: List<TextBlock>? = null
    private var moveGranularity = "line" // "word" | "line" | "paragraph"
    private var moveDx = 0f
    private var moveDy = 0f
    private var dragLastX = 0f
    private var dragLastY = 0f

    private val undoStack = ArrayDeque<File>()
    private var undoCounter = 0

    private val renderDpi = 144f
    private val scale get() = renderDpi / 72f
    private val ptsToScreen get() = scale * b.pageView.currentScale()

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null && workingFile == null) finish() else uri?.let { importAndLoad(it) }
        }
    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) exportTo(uri) else exitAfterSave = false
        }
    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) startImagePlacement(uri)
        }
    private var pendingImageBitmap: Bitmap? = null
    private val imageScaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val f = d.scaleFactor
                val lp = b.imageBox.layoutParams
                lp.width = (lp.width * f).toInt().coerceIn(48, b.root.width)
                lp.height = (lp.height * f).toInt().coerceIn(48, b.root.height)
                b.imageBox.layoutParams = lp
                return true
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyBottomInsetFixes()
        store = DocumentStore(this)
        buildFeatureDrawer()

        b.btnSave.setOnClickListener {
            if (workingFile == null) toast("Open a PDF first") else saveLauncher.launch(defaultSaveName())
        }
        b.btnUndo.setOnClickListener { undo() }
        b.btnClose.setOnClickListener { confirmExitIfDirty { closeDoc() } }
        b.btnClose.setOnLongClickListener { showDrawer("Editor"); true }
        b.btnPrev.setOnClickListener { if (currentPage > 0) { currentPage--; showPage() } }
        b.btnNext.setOnClickListener { if (currentPage < pageCount - 1) { currentPage++; showPage() } }

        b.toolSelect.setOnClickListener { setTool(Tool.SELECT) }
        b.toolMove.setOnClickListener { setTool(Tool.MOVE); showMoveModeChooser() }
        b.toolType.setOnClickListener { setTool(Tool.TYPE) }
        b.toolImage.setOnClickListener { onImageToolTapped() }

        b.imgBehind.setOnClickListener { applyImage(behind = true) }
        b.imgFront.setOnClickListener { applyImage(behind = false) }
        b.imgCancel.setOnClickListener { cancelImage() }
        b.imageBox.setOnTouchListener { v, e ->
            imageScaleDetector.onTouchEvent(e)
            if (!imageScaleDetector.isInProgress) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dragLastX = e.rawX; dragLastY = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        v.translationX += e.rawX - dragLastX
                        v.translationY += e.rawY - dragLastY
                        dragLastX = e.rawX; dragLastY = e.rawY
                    }
                }
            }
            true
        }
        b.toolSelect.setOnLongClickListener { showDrawer("Editor"); true }
        b.toolMove.setOnLongClickListener { showDrawer("Annotations"); true }
        b.toolType.setOnLongClickListener { showDrawer("Editor"); true }

        b.pageView.onTapBitmap = { bx, by -> handleTap(bx, by) }
        b.pageView.onUserScroll = { if (b.overlayScrim.isShown) cancelOverlay() }

        b.overlayScrim.setOnClickListener { cancelOverlay() }
        b.overlayBox.setOnClickListener { }
        b.overlayApply.setOnClickListener { applyOverlay() }
        b.sizeMinus.setOnClickListener { typeSize = (typeSize - 0.5f).coerceAtLeast(4f); refreshTextFormatUi() }
        b.sizePlus.setOnClickListener { typeSize = (typeSize + 0.5f).coerceAtMost(96f); refreshTextFormatUi() }
        // Picking a colour means the user wants that exact colour (stop auto-matching the original).
        b.colorBlack.setOnClickListener { colorRGB = intArrayOf(0, 0, 0); matchOriginalColor = false; refreshTextFormatUi() }
        b.colorRed.setOnClickListener { colorRGB = intArrayOf(220, 38, 38); matchOriginalColor = false; refreshTextFormatUi() }
        b.colorBlue.setOnClickListener { colorRGB = intArrayOf(37, 99, 235); matchOriginalColor = false; refreshTextFormatUi() }
        b.boldButton.setOnClickListener { typeBold = !typeBold; refreshTextFormatUi() }
        b.italicButton.setOnClickListener { typeItalic = !typeItalic; refreshTextFormatUi() }
        b.fontButton.setOnClickListener { chooseOverlayFont() }
        b.fontChevron.setOnClickListener { chooseOverlayFont() }

        b.nudgeUp.setOnClickListener { nudge(0f, step()) }
        b.nudgeDown.setOnClickListener { nudge(0f, -step()) }
        b.nudgeLeft.setOnClickListener { nudge(-step(), 0f) }
        b.nudgeRight.setOnClickListener { nudge(step(), 0f) }
        b.moveDone.setOnClickListener { commitMove() }
        b.moveCancel.setOnClickListener { cancelMove() }

        // Left-edge formatting pop-out for the Move tool.
        b.moveSizePlus.setOnClickListener { typeSize = (typeSize + 0.5f).coerceAtMost(96f); refreshMoveFormatBar() }
        b.moveSizeMinus.setOnClickListener { typeSize = (typeSize - 0.5f).coerceAtLeast(4f); refreshMoveFormatBar() }
        b.moveBold.setOnClickListener { typeBold = !typeBold; refreshMoveFormatBar() }
        b.moveItalic.setOnClickListener { typeItalic = !typeItalic; refreshMoveFormatBar() }
        b.moveColor.setOnClickListener { showMoveColorChooser() }
        b.moveFont.setOnClickListener { chooseOverlayFont() }
        b.selectionBox.setOnTouchListener { v, e -> onDragSelection(v, e) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    b.overlayScrim.isShown -> cancelOverlay()
                    b.movePanel.isShown -> cancelMove()
                    b.imagePanel.isShown -> cancelImage()
                    workingFile != null -> confirmExitIfDirty { closeDoc() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })

        setTool(Tool.SELECT)
        val openedFromIntent = handleIntent(intent)
        if (!openedFromIntent) {
            b.root.post { openLauncher.launch(arrayOf("application/pdf")) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    /**
     * Keeps the bottom panels clear of the on-screen keyboard and the gesture navigation bar.
     * On edge-to-edge devices (e.g. Samsung A34) the edit/add-text overlay's Apply button was
     * hidden behind the keyboard or the nav bar; padding the panel by the IME / nav-bar insets
     * lifts its content above them.
     */
    private fun applyBottomInsetFixes() {
        val overlayBasePad = b.overlayBox.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.overlayBox) { v, insets ->
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, overlayBasePad + maxOf(ime, nav))
            insets
        }
        val viewerBarBasePad = b.viewerBottomBar.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.viewerBottomBar) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, viewerBarBasePad + nav)
            insets
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let { importAndLoad(it) }
        return uri != null
    }

    private fun setTool(t: Tool) {
        tool = t
        cancelOverlay(); cancelMove()
        if (t != Tool.IMAGE) cancelImage()
        b.annotationCanvas.mode = AnnotationCanvasView.Mode.NONE
        val red = 0xFFEF4444.toInt()
        val gray = 0xFF9CA3AF.toInt()

        // Update UI for tool selection
        updateToolUI(b.toolSelect, t == Tool.SELECT, red, gray)
        updateToolUI(b.toolMove, t == Tool.MOVE, red, gray)
        updateToolUI(b.toolType, t == Tool.TYPE, red, gray)
        updateToolUI(b.toolImage, t == Tool.IMAGE, red, gray)
    }

    private fun updateToolUI(layout: LinearLayout, active: Boolean, activeColor: Int, inactiveColor: Int) {
        val color = if (active) activeColor else inactiveColor
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child is TextView) {
                child.setTextColor(color)
            }
        }
    }

    private fun chooseOverlayFont() {
        val fonts = arrayOf("Original", "Poppins", "Helvetica", "Times", "Courier")
        AlertDialog.Builder(this)
            .setTitle("Font")
            .setItems(fonts) { _, which ->
                typeFontName = fonts[which]
                refreshTextFormatUi()
            }
            .show()
    }

    private fun refreshTextFormatUi() {
        b.sizeLabel.text = if (typeSize % 1f == 0f) typeSize.toInt().toString()
                           else String.format("%.1f", typeSize)
        b.fontButton.text = typeFontName
        b.overlayEdit.textSize = typeSize
        b.overlayEdit.setTextColor(android.graphics.Color.rgb(colorRGB[0], colorRGB[1], colorRGB[2]))
        val style = when {
            typeBold && typeItalic -> Typeface.BOLD_ITALIC
            typeBold -> Typeface.BOLD
            typeItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val previewFamily = when (typeFontName.lowercase()) {
            "times" -> "serif"
            else -> "sans-serif"
        }
        b.overlayEdit.typeface = Typeface.create(previewFamily, style)
        b.boldButton.typeface = Typeface.create(Typeface.DEFAULT, if (typeBold) Typeface.BOLD else Typeface.NORMAL)
        b.italicButton.typeface = Typeface.create(Typeface.DEFAULT, if (typeItalic) Typeface.ITALIC else Typeface.NORMAL)
        b.boldButton.setTextColor(if (typeBold) TEAL else GRAY)
        b.italicButton.setTextColor(if (typeItalic) TEAL else GRAY)
        b.boldButton.isSelected = typeBold
        b.italicButton.isSelected = typeItalic
    }

    private fun buildFeatureDrawer() {
        buildHomeShell()
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_soft_panel)
            elevation = 12f
            visibility = View.GONE
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(18, 14, 12, 8)
        }
        val title = TextView(this).apply {
            text = "PDFMaster"
            textSize = 20f
            setTextColor(0xFF111827.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Button(this).apply {
            text = "Close"
            setAllCaps(false)
            setTextColor(0xFF111827.toInt())
            setBackgroundResource(R.drawable.bg_soft_button)
            setOnClickListener { drawer.visibility = View.GONE }
        })
        drawer.addView(titleRow)
        drawerContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 14, 14)
        }
        drawer.addView(ScrollView(this).apply { addView(drawerContent) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        b.root.addView(drawer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.58f).toInt()
        ).apply { gravity = android.view.Gravity.BOTTOM })
    }

    private fun buildHomeShell() {
        homeShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF8FAFC.toInt())
            visibility = View.GONE
        }
        homeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(10))
        }
        homeShell.addView(ScrollView(this).apply { addView(homeContent) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        homeShell.addView(bottomNav())
        b.root.addView(homeShell, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun showHomeScreen(tab: String) {
        homeShell.setBackgroundColor(0xFFEEF9FC.toInt())
        homeContent.removeAllViews()
        homeContent.setPadding(dp(12), dp(18), dp(12), dp(10))
        when (tab) {
            "Files" -> renderFilesScreen()
            "Tools" -> renderAllToolsScreen()
            "Settings" -> renderSettingsScreen()
            else -> renderHomeScreen()
        }
        homeShell.visibility = View.VISIBLE
        b.viewerBar.visibility = View.GONE
        b.pageView.visibility = View.GONE
        b.annotationCanvas.visibility = View.GONE
        b.emptyHint.visibility = View.GONE
    }

    private fun showViewerScreen() {
        homeShell.visibility = View.GONE
        b.viewerBar.visibility = View.VISIBLE
        b.viewerBottomBar.visibility = View.VISIBLE
        b.pageView.visibility = View.VISIBLE
        // Tell the page view about the bars so the PDF stays inside the visible area
        // (between top and bottom bars) instead of sliding behind them.
        b.pageView.post {
            b.pageView.setInsets(b.viewerBar.height, b.viewerBottomBar.height)
        }
    }

    private fun renderHomeScreen() {
        homeContent.addView(topLogoHeader())
        homeContent.addView(headerBlock("Good Morning \uD83D\uDC4B", "What would you like to do today?"))
        homeContent.addView(searchBox())
        homeContent.addView(label("Quick Actions"))
        val grid = GridLayout(this).apply {
            columnCount = 4
            setPadding(0, dp(8), 0, dp(16))
        }
        listOf(
            actionCard("Edit PDF", "✎", 0xFFFFE4E6.toInt(), 0xFFEF4444.toInt()) { openLauncher.launch(arrayOf("application/pdf")) },
            actionCard("Merge PDF", "☍", 0xFFEDE9FE.toInt(), 0xFF7C3AED.toInt()) { openMainAppFeature() },
            actionCard("Compress", "⤓", 0xFFD1FAE5.toInt(), 0xFF10B981.toInt()) { pageEdit("Compressed") { PdfLocalTools.compress(it) } },
            actionCard("Scan PDF", "📷", 0xFFCFFAFE.toInt(), 0xFF06B6D4.toInt()) { scanDocument() },
            actionCard("Image to PDF", "🖼", 0xFFFEF3C7.toInt(), 0xFFF59E0B.toInt()) { openMainAppFeature() },
            actionCard("PDF to Word", "W", 0xFFDBEAFE.toInt(), 0xFF2563EB.toInt()) { toast("Coming soon") }
        ).forEach { grid.addView(it) }
        homeContent.addView(grid)
        homeContent.addView(recentHeader())
        renderRecentList()
    }

    private fun topLogoHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(20))
        
        // Red Icon
        addView(TextView(this@MainActivity).apply {
            text = "PDF"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEF4444.toInt())
                cornerRadius = dp(10).toFloat()
            }
            background = gd
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        
        // Text
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "PDFMaster"
                textSize = 20f
                setTextColor(0xFF111827.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "All-in-one PDF Editor"
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
            })
        })
    }

    private fun renderRecentList() {
        val records = store.records().take(5)
        if (records.isEmpty()) {
            homeContent.addView(emptyCard("No recent files"))
        } else {
            records.forEach { homeContent.addView(fileRow(it)) }
        }
    }

    private fun renderFilesScreen() {
        homeContent.addView(headerBlock("All Files", ""))
        
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
            addView(chip("All") { renderFilesScreen() })
            addView(chip("PDF") { renderCategory(FileCategory.WORK) })
            addView(chip("Folder") { toast("Folders coming soon") })
            addView(chip("Favorite") { renderFilteredFiles(true) })
        }
        homeContent.addView(tabs)

        val records = store.records()
        if (records.isEmpty()) {
            homeContent.addView(emptyCard("No files imported yet"))
        } else {
            records.forEach { homeContent.addView(fileRow(it)) }
        }
    }

    private fun chip(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setTextColor(if (text == "All") 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt())
        val gd = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (text == "All") 0xFFEF4444.toInt() else 0xFFF3F4F6.toInt())
            cornerRadius = dp(20).toFloat()
        }
        background = gd
        setOnClickListener { action() }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(8) }
    }

    private fun renderFilteredFiles(favoritesOnly: Boolean) {
        homeContent.removeAllViews()
        homeContent.addView(headerBlock("Favorites", "Starred files"))
        store.records().filter { !favoritesOnly || it.favorite }.forEach { homeContent.addView(fileRow(it)) }
    }

    private fun renderCategory(category: FileCategory) {
        homeContent.removeAllViews()
        homeContent.addView(headerBlock(category.label, "Category files"))
        store.records().filter { it.category == category }.forEach { homeContent.addView(fileRow(it)) }
    }

    private fun renderAllToolsScreen() {
        homeContent.addView(headerBlock("All Tools", "Direct shortcuts"))
        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(0, dp(10), 0, dp(10))
        }
        listOf(
            toolCard("Merge PDF", "☍", 0xFFEDE9FE.toInt(), 0xFF7C3AED.toInt()) { openMainAppFeature() },
            toolCard("Split PDF", "✂", 0xFFF3F4F6.toInt(), 0xFF6B7280.toInt()) { splitPdf() },
            toolCard("Compress", "⤓", 0xFFD1FAE5.toInt(), 0xFF10B981.toInt()) { pageEdit("Compressed") { PdfLocalTools.compress(it) } },
            toolCard("Image to PDF", "🖼", 0xFFD1FAE5.toInt(), 0xFF10B981.toInt()) { openMainAppFeature() },
            toolCard("PDF to Image", "📷", 0xFFFEF3C7.toInt(), 0xFFF59E0B.toInt()) { toast("Coming soon") },
            toolCard("PDF to Word", "W", 0xFFDBEAFE.toInt(), 0xFF2563EB.toInt()) { toast("Coming soon") },
            toolCard("Rotate", "⟳", 0xFFDBEAFE.toInt(), 0xFF2563EB.toInt()) { pageEdit("Rotated") { PdfLocalTools.rotatePage(it, currentPage) } },
            toolCard("Delete", "🗑", 0xFFF3F4F6.toInt(), 0xFF6B7280.toInt()) { pageEdit("Deleted") { PdfLocalTools.deletePage(it, currentPage) } },
            toolCard("Extract", "⇥", 0xFFD1FAE5.toInt(), 0xFF10B981.toInt()) { extractActivePage() },
            toolCard("Protect", "🔒", 0xFFDBEAFE.toInt(), 0xFF2563EB.toInt()) { setPrivateSafe() },
            toolCard("Unlock", "🔓", 0xFFFEF3C7.toInt(), 0xFFF59E0B.toInt()) { toast("Unlock feature coming soon") },
            toolCard("Organize", "▦", 0xFFEDE9FE.toInt(), 0xFF7C3AED.toInt()) { toast("Organize pages coming soon") }
        ).forEach { grid.addView(it) }
        homeContent.addView(grid)
    }

    private fun toolCard(title: String, icon: String, bgColor: Int, iconColor: Int, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        setPadding(dp(4), dp(12), dp(4), dp(12))
        setOnClickListener { action() }
        addView(TextView(this@MainActivity).apply {
            text = icon
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(iconColor)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(14).toFloat()
            }
            background = gd
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF111827.toInt())
            setPadding(0, dp(6), 0, 0)
        })
    }.also {
        it.layoutParams = GridLayout.LayoutParams().apply {
            width = dp(110)
            height = dp(100)
        }
    }

    private fun renderSettingsScreen() {
        homeContent.addView(headerBlock("Settings", "General and security"))
        homeContent.addView(settingsRow("Dark Mode", "Coming soon"))
        homeContent.addView(settingsRow("Language", "English"))
        homeContent.addView(settingsRow("Default Viewer", "PDF Viewer"))
        val appLock = Switch(this).apply {
            text = "App Lock"
            setTextColor(0xFF111827.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        homeContent.addView(appLock)
        val hide = Switch(this).apply {
            text = "Hide Recent Files"
            isChecked = store.hideRecent
            setTextColor(0xFF111827.toInt())
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> store.hideRecent = checked }
        }
        homeContent.addView(hide)
        homeContent.addView(settingsRow("Rate Us", ">"))
        homeContent.addView(settingsRow("Share App", ">"))
    }

    private fun bottomNav() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xFFFFFFFF.toInt())
        elevation = 20f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(navButton("Home", "🏠", true) { showHomeScreen("Home") })
        addView(navButton("Files", "📁", false) { showHomeScreen("Files") })
        addView(navButton("", "⊕", false) { scanDocument() })
        addView(navButton("Tools", "⚒", false) { showHomeScreen("Tools") })
        addView(navButton("Profile", "👤", false) { showHomeScreen("Settings") })
    }

    private fun navButton(label: String, icon: String, active: Boolean, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        setOnClickListener { action() }
        val color = if (active) 0xFFEF4444.toInt() else 0xFF9CA3AF.toInt()
        
        if (label.isEmpty()) {
            // Special middle button
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 28f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFEF4444.toInt())
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                }
                background = gd
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        } else {
            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 20f
                setTextColor(color)
                gravity = android.view.Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 10f
                setTextColor(color)
                gravity = android.view.Gravity.CENTER
            })
        }
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
    }

    private fun headerBlock(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(8), 0, dp(16))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 24f
            setTextColor(0xFF111827.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (subtitle.isNotEmpty()) {
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 14f
                setTextColor(0xFF6B7280.toInt())
                setPadding(0, dp(2), 0, 0)
            })
        }
    }

    private fun searchBox() = TextView(this).apply {
        text = "\u2315   Search private files..."
        textSize = 18f
        setTextColor(0xFF44515A.toInt())
        setBackgroundResource(R.drawable.bg_home_search)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) }
    }

    private fun actionCard(title: String, icon: String, bgColor: Int, iconColor: Int, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
        setOnClickListener { action() }
        addView(TextView(this@MainActivity).apply {
            text = icon
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setTextColor(iconColor)
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(14).toFloat()
            }
            background = gd
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF4B5563.toInt())
            setPadding(0, dp(6), 0, 0)
        })
    }.also {
        it.layoutParams = GridLayout.LayoutParams().apply {
            width = dp(85)
            height = dp(95)
        }
    }


    private fun privateOfflineCard() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_private_offline)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "100% Private Offline"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Your documents are securely\nencrypted on your phone. No logins,\ntracking or cloud transfers."
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(10), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "\u25E9"
            textSize = 54f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(70), dp(86)))
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) }
    }

    private fun sponsoredBackupCard(compact: Boolean) = LinearLayout(this).apply {
        orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.bg_sponsored_card)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        if (compact) {
            addView(TextView(this@MainActivity).apply {
                text = "\u2601"
                textSize = 30f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFF7900.toInt())
                setBackgroundResource(R.drawable.bg_sponsored_card)
            }, LinearLayout.LayoutParams(dp(52), dp(76)))
        }
        val copyBlock = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Sponsored"
                textSize = 11f
                setTextColor(0xFF8A7562.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (compact) "Backup your PDF files\nsafely" else "TeraBox Cloud Backup"
                textSize = 17f
                setTextColor(0xFF17212A.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(6))
            })
            addView(TextView(this@MainActivity).apply {
                text = if (compact) {
                    "Get secure cloud storage for scanned\ndocuments and important files."
                } else {
                    "Need secure off-device backup for your documents?\nAccess 1,024 GB free, high-speed encrypted cloud\nworkspace via our verified storage partner."
                }
                textSize = 13f
                setTextColor(0xFF66574D.toInt())
            })
        }
        addView(copyBlock, if (compact) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        addView(Button(this@MainActivity).apply {
            text = if (compact) "Open" else "Get 1TB Free Storage"
            setAllCaps(false)
            setTextColor(if (compact) 0xFFFFFFFF.toInt() else 0xFF006B8F.toInt())
            setBackgroundResource(if (compact) R.drawable.bg_red_button else R.drawable.bg_import_button)
            setOnClickListener { toast("Sponsored backup") }
        })
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) }
    }

    private fun recentHeader() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(12))
        addView(TextView(this@MainActivity).apply {
            text = "Recent Files"
            textSize = 18f
            setTextColor(0xFF111827.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = "See all"
            textSize = 12f
            setTextColor(0xFFEF4444.toInt())
            setOnClickListener { showHomeScreen("Files") }
        })
    }

    private fun sectionRow(title: String, actionText: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(4))
        addView(label(title), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@MainActivity).apply {
            text = actionText
            setTextColor(0xFFEF233C.toInt())
            setOnClickListener { action() }
        })
    }

    private fun fileRow(record: DocumentRecord) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setBackgroundColor(0xFFFFFFFF.toInt())
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener { openRecord(record) }
        
        // PDF Icon
        addView(TextView(this@MainActivity).apply {
            text = "PDF"
            setTextColor(0xFFFFFFFF.toInt())
            val gd = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFEF4444.toInt())
                cornerRadius = dp(8).toFloat()
            }
            background = gd
            gravity = android.view.Gravity.CENTER
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = record.name
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(0xFF111827.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                val size = formatSize(File(record.path).length())
                text = "$size \u2022 10 May 2024" // Placeholder date
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(TextView(this@MainActivity).apply {
            text = if (record.favorite) "★" else "☆"
            setTextColor(if (record.favorite) 0xFFF59E0B.toInt() else 0xFFD1D5DB.toInt())
            textSize = 20f
            setOnClickListener { 
                store.setFavorite(record, !record.favorite)
                showHomeScreen("Files") 
            }
        })
        
        addView(TextView(this@MainActivity).apply {
            text = "⋮"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
        })
    }.also {
        it.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(1)
        }
    }

    private fun folderRow(name: String, detail: String, action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { action() }
        addView(TextView(this@MainActivity).apply {
            text = "Folder"
            setTextColor(0xFFF59E0B.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = "$name\n$detail"
            setTextColor(0xFF111827.toInt())
        })
    }

    private fun privacyStrip() = TextView(this).apply {
        text = "100% Offline    No Sign Up    Secure & Private    Lightweight"
        setTextColor(0xFF111827.toInt())
        setBackgroundResource(R.drawable.bg_blue_soft_button)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.1f MB", kb / 1024.0)
    }

    private fun settingsRow(left: String, right: String) = TextView(this).apply {
        text = "$left                                      $right"
        setTextColor(0xFF111827.toInt())
        setBackgroundResource(R.drawable.bg_soft_panel)
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun emptyCard(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFF64748B.toInt())
        setBackgroundResource(R.drawable.bg_soft_panel)
        setPadding(dp(12), dp(14), dp(12), dp(14))
    }

    private fun addAdBlockTo(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFF111827.toInt())
            setBackgroundResource(R.drawable.bg_blue_soft_button)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showDrawer(section: String) {
        drawerContent.removeAllViews()
        drawerContent.addView(label("PDF Editor"))
        drawerContent.addView(row(
            button("Open PDF") { openLauncher.launch(arrayOf("application/pdf")) },
            button("Save copy") { if (workingFile == null) toast("Open a PDF first") else saveLauncher.launch(defaultSaveName()) },
            button("Close") { drawer.visibility = View.GONE }
        ))
        if (section == "Annotations" || section == "Editor") addAnnotationControls()
        if (section == "Tools" || section == "Editor") addToolControls()
        drawer.visibility = View.VISIBLE
    }

    private fun addDashboardControls() {
        drawerContent.addView(label("Files"))
        drawerContent.addView(row(
            button("Open PDF") { openLauncher.launch(arrayOf("application/pdf")) },
            button("Merge PDFs") { openMainAppFeature() },
            button("Images to PDF") { openMainAppFeature() }
        ))
        drawerContent.addView(row(
            button("Favorite") { toggleFavorite() },
            button("Category") { chooseCategory() },
            button("Private Safe") { setPrivateSafe() }
        ))
        drawerContent.addView(row(
            button("Delete undo") { deleteWithUndo() },
            button("Recover") { recoverDeleted() },
            button("Copy file") { duplicateCurrentFile() }
        ))
        val sw = Switch(this).apply {
            text = "Hide recent files"
            isChecked = store.hideRecent
            setOnCheckedChangeListener { _, checked ->
                store.hideRecent = checked
                showDrawer("Editor")
            }
        }
        drawerContent.addView(sw)
        if (!store.hideRecent) {
            store.records().take(6).forEach { record ->
                drawerContent.addView(button("${if (record.favorite) "*" else " "} ${record.category.label}: ${record.name}") {
                    openRecord(record)
                })
            }
        }
    }

    private fun addAnnotationControls() {
        drawerContent.addView(label("Annotations"))
        drawerContent.addView(row(
            button("Pen") { annotationMode(AnnotationCanvasView.Mode.PEN) },
            button("Highlighter") { annotationMode(AnnotationCanvasView.Mode.HIGHLIGHTER) },
            button("Shape") { annotationMode(AnnotationCanvasView.Mode.SHAPE) }
        ))
        drawerContent.addView(row(
            button("Signature") { annotationMode(AnnotationCanvasView.Mode.SIGNATURE) },
            button("Save markups") { bakeAnnotations() },
            button("Clear") { b.annotationCanvas.clearAll() }
        ))
        drawerContent.addView(row(
            button("Undo markup") { if (!b.annotationCanvas.undoLast()) toast("No annotation to undo") },
            button("Text note") { setTool(Tool.TYPE); drawer.visibility = View.GONE },
            button("Move items") { setTool(Tool.MOVE); drawer.visibility = View.GONE }
        ))
    }

    private fun addToolControls() {
        drawerContent.addView(label("Tools"))
        drawerContent.addView(row(
            button("Edit text") { setTool(Tool.SELECT); drawer.visibility = View.GONE },
            button("Add text") { setTool(Tool.TYPE); drawer.visibility = View.GONE },
            button("Move text") { setTool(Tool.MOVE); drawer.visibility = View.GONE }
        ))
    }

    private fun addAdBlock(text: String) {
        drawerContent.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFF475569.toInt())
            setBackgroundResource(R.drawable.bg_blue_soft_button)
            setPadding(12, 10, 12, 10)
        })
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 3, 0, 3)
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 3
                rightMargin = 3
            })
        }
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        setAllCaps(false)
        setTextColor(0xFF111827.toInt())
        setBackgroundResource(when {
            text.contains("PDF", ignoreCase = true) || text.contains("OCR", ignoreCase = true) -> R.drawable.bg_soft_button
            text.contains("Scan", ignoreCase = true) || text.contains("Compress", ignoreCase = true) -> R.drawable.bg_green_soft_button
            else -> R.drawable.bg_blue_soft_button
        })
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(0xFF0F172A.toInt())
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 12, 0, 4)
    }

    private fun scanDocument() {
        toast("Scanner is available in the main app.")
    }

    private fun openMainAppFeature() {
        toast("This feature is available in the main app.")
    }

    // ---- load / render ----

    private fun importAndLoad(uri: Uri) = lifecycleScope.launch {
        try {
            val displayName = displayName(uri)
            val f = withContext(Dispatchers.IO) {
                val out = store.fileForNewImport(displayName)
                contentResolver.openInputStream(uri)!!.use { inp -> out.outputStream().use { inp.copyTo(it) } }
                out
            }
            workingFile = f
            activeRecord = store.upsert(f, displayName)
            updateSecureFlag()
            clearUndo()
            pageCount = withContext(Dispatchers.IO) { PDDocument.load(f).use { it.numberOfPages } }
            currentPage = 0
            showViewerScreen()
            b.emptyHint.visibility = View.GONE
            showPage()
        } catch (e: Exception) { toast("Could not open PDF: ${e.message}") }
    }

    private fun showPage() = lifecycleScope.launch {
        val f = workingFile ?: return@launch
        val bmp: Bitmap = withContext(Dispatchers.IO) {
            PDDocument.load(f).use {
                pageHeightPts = it.getPage(currentPage).mediaBox.height
                PDFRenderer(it).renderImageWithDPI(currentPage, renderDpi)
            }
        }
        b.pageView.setPageBitmap(bmp)
        val extracted = withContext(Dispatchers.IO) { PDDocument.load(f).use { PdfTextExtractor.extractAll(it, currentPage) } }
        lines = extracted.first; words = extracted.second
        b.pageLabel.text = "Page ${currentPage + 1} / $pageCount"
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    /** Default Save name = original document name + " copy". */
    private fun defaultSaveName(): String {
        val original = activeRecord?.name ?: workingFile?.name ?: "document.pdf"
        val base = original.removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "document" }
        return "$base copy.pdf"
    }

    private fun openRecord(record: DocumentRecord) {
        if (record.isPrivate) {
            requestPin { openRecordUnlocked(record) }
            return
        }
        openRecordUnlocked(record)
    }

    private fun openRecordUnlocked(record: DocumentRecord) {
        workingFile = File(record.path)
        activeRecord = record
        store.touch(record)
        updateSecureFlag()
        clearUndo()
        pageCount = PDDocument.load(workingFile).use { it.numberOfPages }
        currentPage = 0
        showViewerScreen()
        b.emptyHint.visibility = View.GONE
        drawer.visibility = View.GONE
        showPage()
    }

    private fun updateSecureFlag() {
        if (activeRecord?.isPrivate == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun requestPin(onUnlocked: () -> Unit) {
        val input = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        AlertDialog.Builder(this)
            .setTitle("Private Safe PIN")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                if (input.text.toString() == store.privatePin) onUnlocked() else toast("Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- tap routing ----

    private fun handleTap(bx: Float, by: Float) {
        val pdfX = bx / scale
        val pdfTopY = by / scale
        when (tool) {
            Tool.SELECT -> pickLine(pdfX, pdfTopY)?.let { startEdit(it) }
            Tool.MOVE -> when (moveGranularity) {
                "word" -> hitWord(pdfX, pdfTopY)?.let { startMoveWord(it) }
                "paragraph" -> pickParagraph(pdfX, pdfTopY)?.let { startMoveParagraph(it) }
                else -> pickLine(pdfX, pdfTopY)?.let { startMoveLine(it) }
            }
            Tool.TYPE -> startAdd(pdfX, pageHeightPts - pdfTopY, bx, by)
            Tool.IMAGE -> {} // placement is handled by the draggable image box, not a page tap
        }
    }

    // ---- IMAGE: place a picture behind or over the page text ----

    private fun onImageToolTapped() {
        if (workingFile == null) { toast("Open a PDF first"); return }
        setTool(Tool.IMAGE)
        pickImageLauncher.launch("image/*")
    }

    private fun startImagePlacement(uri: Uri) {
        val bmp = runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return run { toast("Couldn't load that image") }
        cancelOverlay(); cancelMove()
        pendingImageBitmap = bmp
        b.imageBox.setImageBitmap(bmp)
        // Default size ~45% of the screen width, keeping the image's aspect ratio, centered.
        val targetW = (b.root.width * 0.45f).toInt().coerceAtLeast(120)
        val targetH = (targetW * bmp.height.toFloat() / bmp.width.coerceAtLeast(1)).toInt().coerceAtLeast(80)
        (b.imageBox.layoutParams as FrameLayout.LayoutParams).apply {
            width = targetW
            height = targetH
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            leftMargin = ((b.root.width - targetW) / 2).coerceAtLeast(0)
            topMargin = ((b.root.height - targetH) / 2).coerceAtLeast(0)
            b.imageBox.layoutParams = this
        }
        b.imageBox.translationX = 0f
        b.imageBox.translationY = 0f
        b.imageBox.visibility = View.VISIBLE
        b.imagePanel.visibility = View.VISIBLE
        toast("Drag to move, pinch to resize, then choose a layer")
    }

    private fun applyImage(behind: Boolean) {
        val f = workingFile ?: return
        val bmp = pendingImageBitmap ?: return
        val lp = b.imageBox.layoutParams as FrameLayout.LayoutParams
        val left = lp.leftMargin + b.imageBox.translationX
        val top = lp.topMargin + b.imageBox.translationY
        val right = left + lp.width
        val bottomScreen = top + lp.height
        // Screen -> page bitmap -> PDF points (top-left origin), then flip Y for PDF.
        val tl = b.pageView.screenToBitmap(left, top)
        val br = b.pageView.screenToBitmap(right, bottomScreen)
        val pdfLeft = tl.x / scale
        val pdfTop = tl.y / scale
        val pdfW = (br.x - tl.x) / scale
        val pdfH = (br.y - tl.y) / scale
        if (pdfW <= 1f || pdfH <= 1f) { toast("Place the image on the page first"); return }
        val pdfBottom = pageHeightPts - (pdfTop + pdfH)
        pushUndo()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                PdfBlockEditor.placeImage(f, currentPage, bmp, pdfLeft, pdfBottom, pdfW, pdfH, behind)
            }
            reportResult(r)
            cancelImage()
            showPage()
        }
    }

    private fun cancelImage() {
        b.imageBox.visibility = View.GONE
        b.imagePanel.visibility = View.GONE
        b.imageBox.setImageDrawable(null)
        pendingImageBitmap = null
    }

    /** Finds the text line for a tap. Uses a generous hit box, then falls back to the
     *  nearest row so the user doesn't have to tap exactly on the glyphs. */
    private fun pickLine(pdfX: Float, pdfTopY: Float): TextBlock? {
        lines.firstOrNull { it.containsTopLeft(pdfX, pdfTopY, pad = 10f) }?.let { return it }
        return lines.minByOrNull { lb -> abs(pdfTopY - (lb.yTop + lb.height / 2f)) }
            ?.takeIf { lb ->
                abs(pdfTopY - (lb.yTop + lb.height / 2f)) <= lb.height.coerceAtLeast(8f) * 2f + 14f
            }
    }

    private fun hitWord(pdfX: Float, pdfTopY: Float): WordBlock? {
        val tapBaseline = pageHeightPts - pdfTopY
        return words.firstOrNull { wb ->
            pdfX >= wb.wordX - 2 && pdfX <= wb.wordX + wb.wordWidth + 2 &&
                abs(tapBaseline - wb.baselineY) <= wb.fontSize
        }
    }

    // ---- SELECT: edit existing line ----

    private fun startEdit(block: TextBlock) {
        overlayMode = OverlayMode.EDIT_EXISTING
        editingBlock = block
        typeSize = block.fontSize.coerceIn(4f, 96f)
        matchOriginalColor = true   // keep the original colour/shade unless the user picks one
        b.formatRow.visibility = View.VISIBLE
        refreshTextFormatUi()
        b.overlayEdit.setText(block.text)
        placeOverlayAt(topLeftRect(block.x, block.yTop, block.width, block.height))
        showOverlay()
    }

    // ---- TYPE: add new text ----

    private fun startAdd(baselineX: Float, baselineY: Float, bx: Float, by: Float) {
        overlayMode = OverlayMode.ADD_TEXT
        addBaselineX = baselineX; addBaselineY = baselineY
        matchOriginalColor = false   // brand-new text uses the chosen colour
        b.formatRow.visibility = View.VISIBLE
        refreshTextFormatUi()
        b.overlayEdit.setText("")
        placeOverlayAt(RectF(bx, by, bx, by))
        showOverlay()
    }

    private fun applyOverlay() {
        val f = workingFile ?: return
        val text = b.overlayEdit.text.toString()
        pushUndo()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (overlayMode) {
                    OverlayMode.EDIT_EXISTING ->
                        editingBlock?.let {
                            PdfBlockEditor.editBlock(
                                this@MainActivity,
                                f,
                                it,
                                text,
                                typeSize,
                                colorRGB[0],
                                colorRGB[1],
                                colorRGB[2],
                                typeFontName,
                                typeBold,
                                typeItalic,
                                matchOriginalColor
                            )
                        }
                            ?: PdfBlockEditor.Result.ERROR
                    OverlayMode.ADD_TEXT ->
                        PdfBlockEditor.addText(this@MainActivity, f, currentPage, addBaselineX, addBaselineY,
                            text, typeSize, colorRGB[0], colorRGB[1], colorRGB[2], lines.map { it.baselineY },
                            typeFontName, typeBold, typeItalic)
                }
            }
            reportResult(result)
            cancelOverlay()
            showPage()
        }
    }

    // ---- MOVE ----

    private fun step() = if (b.stepToggle.isChecked) 8f else 2f

    private fun startMoveWord(wb: WordBlock) {
        selectedWord = wb; selectedLine = null; selectedParagraph = null
        val yTop = pageHeightPts - wb.baselineY - wb.fontSize
        showSelection(wb.wordX, yTop, wb.wordWidth, wb.fontSize * 1.2f)
    }

    private fun startMoveLine(block: TextBlock) {
        selectedLine = block; selectedWord = null; selectedParagraph = null
        initFormatFrom(block)
        showSelection(block.x, block.yTop, block.width, block.height)
    }

    private fun showMoveModeChooser() {
        val options = arrayOf("Word", "Line", "Paragraph")
        val current = when (moveGranularity) { "word" -> 0; "paragraph" -> 2; else -> 1 }
        AlertDialog.Builder(this)
            .setTitle("Move what?")
            .setSingleChoiceItems(options, current) { dialog, which ->
                moveGranularity = when (which) { 0 -> "word"; 2 -> "paragraph"; else -> "line" }
                dialog.dismiss()
                toast("Move ${options[which].lowercase()} — tap the text to select it")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Groups the tapped line with adjacent, closely-spaced lines into a paragraph. */
    private fun pickParagraph(pdfX: Float, pdfTopY: Float): List<TextBlock>? {
        val anchor = pickLine(pdfX, pdfTopY) ?: return null
        val sorted = lines.sortedBy { it.yTop }
        val idx = sorted.indexOf(anchor)
        if (idx < 0) return listOf(anchor)
        val gapLimit = anchor.height.coerceAtLeast(8f) * 1.8f
        var top = idx
        while (top > 0) {
            val gap = sorted[top].yTop - (sorted[top - 1].yTop + sorted[top - 1].height)
            if (gap <= gapLimit) top-- else break
        }
        var bottom = idx
        while (bottom < sorted.size - 1) {
            val gap = sorted[bottom + 1].yTop - (sorted[bottom].yTop + sorted[bottom].height)
            if (gap <= gapLimit) bottom++ else break
        }
        return sorted.subList(top, bottom + 1).toList()
    }

    private fun startMoveParagraph(para: List<TextBlock>) {
        selectedParagraph = para; selectedWord = null; selectedLine = null
        para.firstOrNull()?.let { initFormatFrom(it) }
        val minX = para.minOf { it.x }
        val minYTop = para.minOf { it.yTop }
        val maxRight = para.maxOf { it.x + it.width }
        val maxBottom = para.maxOf { it.yTop + it.height }
        showSelection(minX, minYTop, maxRight - minX, maxBottom - minYTop)
    }

    private fun showSelection(xPts: Float, yTopPts: Float, wPts: Float, hPts: Float) {
        moveDx = 0f; moveDy = 0f
        val s = b.pageView.bitmapToScreenRect(topLeftRect(xPts, yTopPts, wPts, hPts))
        (b.selectionBox.layoutParams as FrameLayout.LayoutParams).apply {
            width = s.width().toInt().coerceAtLeast(24)
            height = s.height().toInt().coerceAtLeast(16)
            leftMargin = s.left.toInt(); topMargin = s.top.toInt()
            b.selectionBox.layoutParams = this
        }
        b.selectionBox.translationX = 0f; b.selectionBox.translationY = 0f
        b.selectionBox.visibility = View.VISIBLE
        b.movePanel.visibility = View.VISIBLE
        b.moveFormatBar.visibility = View.VISIBLE
        refreshMoveFormatBar()
    }

    private fun nudge(dxPts: Float, dyPts: Float) {
        if (selectedWord == null && selectedLine == null && selectedParagraph == null) return
        moveDx += dxPts; moveDy += dyPts
        b.selectionBox.translationX += dxPts * ptsToScreen
        b.selectionBox.translationY += -dyPts * ptsToScreen
    }

    private fun onDragSelection(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragLastX = e.rawX; dragLastY = e.rawY }
            MotionEvent.ACTION_MOVE -> {
                val dX = e.rawX - dragLastX; val dY = e.rawY - dragLastY
                v.translationX += dX; v.translationY += dY
                moveDx += dX / ptsToScreen; moveDy += -dY / ptsToScreen
                dragLastX = e.rawX; dragLastY = e.rawY
            }
        }
        return true
    }

    private fun commitMove() {
        val f = workingFile ?: return
        val wb = selectedWord; val lb = selectedLine; val para = selectedParagraph
        if (wb == null && lb == null && para == null) return
        pushUndo()
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                when {
                    wb != null -> PdfBlockEditor.moveWord(this@MainActivity, f, wb, moveDx, moveDy, lines.map { it.baselineY })
                    para != null -> {
                        // Move every line of the paragraph by the same offset, applying the
                        // chosen size/font/weight (colour matches the original per line).
                        var last = PdfBlockEditor.Result.EDITED
                        para.forEach { line ->
                            val res = PdfBlockEditor.moveBlock(this@MainActivity, f, line, moveDx, moveDy,
                                typeSize, colorRGB[0], colorRGB[1], colorRGB[2],
                                typeFontName, typeBold, typeItalic, matchOriginalColor)
                            if (res != PdfBlockEditor.Result.EDITED) last = res
                        }
                        last
                    }
                    else -> PdfBlockEditor.moveBlock(this@MainActivity, f, lb!!, moveDx, moveDy,
                        typeSize, colorRGB[0], colorRGB[1], colorRGB[2],
                        typeFontName, typeBold, typeItalic, matchOriginalColor)
                }
            }
            reportResult(r); cancelMove(); showPage()
        }
    }

    private fun cancelMove() {
        b.selectionBox.visibility = View.GONE
        b.movePanel.visibility = View.GONE
        b.moveFormatBar.visibility = View.GONE
        selectedWord = null; selectedLine = null; selectedParagraph = null
    }

    private fun annotationMode(mode: AnnotationCanvasView.Mode) {
        cancelOverlay(); cancelMove()
        tool = Tool.SELECT
        b.annotationCanvas.mode = mode
        drawer.visibility = View.GONE
        toast("$mode mode")
    }

    private fun bakeAnnotations() {
        val f = workingFile ?: return toast("Open a PDF first")
        if (b.annotationCanvas.strokes.isEmpty()) return toast("No annotations to save")
        pushUndo()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PDDocument.load(f).use { doc ->
                        val page = doc.getPage(currentPage)
                        PDPageContentStream(doc, page, AppendMode.APPEND, true, true).use { cs ->
                            b.annotationCanvas.strokes.forEach { stroke ->
                                cs.setStrokingColor(
                                    android.graphics.Color.red(stroke.color),
                                    android.graphics.Color.green(stroke.color),
                                    android.graphics.Color.blue(stroke.color)
                                )
                                cs.setLineWidth(stroke.width / ptsToScreen)
                                val mapped = stroke.points.map {
                                    val p = b.pageView.screenToBitmap(it.first, it.second)
                                    (p.x / scale) to (pageHeightPts - p.y / scale)
                                }
                                if (stroke.mode == AnnotationCanvasView.Mode.SHAPE && mapped.size > 1) {
                                    val a = mapped.first()
                                    val z = mapped.last()
                                    cs.addRect(a.first, z.second, z.first - a.first, a.second - z.second)
                                    cs.stroke()
                                } else if (mapped.isNotEmpty()) {
                                    cs.moveTo(mapped.first().first, mapped.first().second)
                                    mapped.drop(1).forEach { cs.lineTo(it.first, it.second) }
                                    cs.stroke()
                                }
                            }
                        }
                        val tmp = File(f.parentFile, "tmp_${f.name}")
                        doc.save(tmp)
                        tmp.copyTo(f, overwrite = true)
                        tmp.delete()
                    }
                }
                b.annotationCanvas.clearAll()
                b.annotationCanvas.mode = AnnotationCanvasView.Mode.NONE
                showPage()
                toast("Annotations saved")
            } catch (e: Exception) { toast("Could not save annotations: ${e.message}") }
        }
    }

    private fun extractOcrText() {
        val text = lines.joinToString("\n") { it.text }
        if (text.isBlank()) toast("No text found on this page") else showTextDialog("OCR text", text)
    }

    private fun copyOcrText() {
        val text = lines.joinToString("\n") { it.text }
        if (text.isBlank()) return toast("No text found on this page")
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("PDF OCR text", text))
        toast("OCR text copied")
    }

    private fun showTextDialog(title: String, text: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton("Copy") { _, _ -> copyOcrText() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pageEdit(done: String, op: (File) -> Unit) {
        val f = workingFile ?: return toast("Open a PDF first")
        pushUndo()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { op(f) }
                pageCount = withContext(Dispatchers.IO) { PDDocument.load(f).use { it.numberOfPages } }
                currentPage = currentPage.coerceAtMost(pageCount - 1)
                showPage()
                toast(done)
            } catch (e: Exception) { toast("Tool failed: ${e.message}") }
        }
    }

    private fun extractActivePage() {
        val f = workingFile ?: return toast("Open a PDF first")
        lifecycleScope.launch {
            val out = File(filesDir, "extracted_page_${System.currentTimeMillis()}.pdf")
            try {
                withContext(Dispatchers.IO) { PdfLocalTools.extractPage(f, currentPage, out) }
                activeRecord = store.upsert(out, out.name)
                workingFile = out
                pageCount = 1
                currentPage = 0
                showViewerScreen()
                showPage()
                toast("Extracted as new PDF")
            } catch (e: Exception) { toast("Extract failed: ${e.message}") }
        }
    }

    private fun splitPdf() {
        val f = workingFile ?: return toast("Open a PDF first")
        lifecycleScope.launch {
            try {
                val outs = withContext(Dispatchers.IO) { PdfLocalTools.splitPages(f, File(filesDir, "splits_${System.currentTimeMillis()}")) }
                outs.forEach { store.upsert(it, it.name) }
                toast("Split into ${outs.size} PDFs")
                showDrawer("Editor")
            } catch (e: Exception) { toast("Split failed: ${e.message}") }
        }
    }

    private fun mergePdfs(uris: List<Uri>) = lifecycleScope.launch {
        try {
            val files = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val out = File(cacheDir, "merge_${System.nanoTime()}.pdf")
                    contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
                    out
                }
            }
            val out = store.fileForNewImport("merged.pdf")
            withContext(Dispatchers.IO) { PdfLocalTools.merge(files, out) }
            activeRecord = store.upsert(out, "merged.pdf", FileCategory.WORK)
            workingFile = out
            pageCount = withContext(Dispatchers.IO) { PDDocument.load(out).use { it.numberOfPages } }
            currentPage = 0
            showViewerScreen()
            showPage()
            toast("Merged PDFs")
        } catch (e: Exception) { toast("Merge failed: ${e.message}") }
    }

    private fun galleryImagesToPdf(uris: List<Uri>) = lifecycleScope.launch {
        try {
            val out = store.fileForNewImport("gallery.pdf")
            withContext(Dispatchers.IO) { PdfLocalTools.imagesToPdf(this@MainActivity, uris, out) }
            activeRecord = store.upsert(out, "gallery.pdf", FileCategory.SCANNER)
            workingFile = out
            pageCount = withContext(Dispatchers.IO) { PDDocument.load(out).use { it.numberOfPages } }
            currentPage = 0
            showViewerScreen()
            showPage()
            toast("Gallery PDF created")
        } catch (e: Exception) { toast("Image import failed: ${e.message}") }
    }

    private fun toggleFavorite() {
        val record = activeRecord ?: return toast("Open a PDF first")
        store.setFavorite(record, !record.favorite)
        activeRecord = record.copy(favorite = !record.favorite)
        showDrawer("Editor")
    }

    private fun chooseCategory() {
        val record = activeRecord ?: return toast("Open a PDF first")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, FileCategory.entries.map { it.label })
            setSelection(FileCategory.entries.indexOf(record.category))
        }
        AlertDialog.Builder(this)
            .setTitle("File category")
            .setView(spinner)
            .setPositiveButton("Save") { _, _ ->
                val cat = FileCategory.fromLabel(spinner.selectedItem.toString())
                store.setCategory(record, cat)
                activeRecord = record.copy(category = cat)
                updateSecureFlag()
                showDrawer("Editor")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setPrivateSafe() {
        val record = activeRecord ?: return toast("Open a PDF first")
        val pin = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Move to Private Safe")
            .setView(pin)
            .setPositiveButton("Save") { _, _ ->
                if (pin.text.isNotBlank()) store.privatePin = pin.text.toString()
                store.setCategory(record, FileCategory.PRIVATE_SAFE)
                activeRecord = record.copy(category = FileCategory.PRIVATE_SAFE)
                updateSecureFlag()
                showDrawer("Editor")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun duplicateCurrentFile() {
        val f = workingFile ?: return toast("Open a PDF first")
        val copy = File(f.parentFile, "${f.nameWithoutExtension}_copy_${System.currentTimeMillis()}.pdf")
        f.copyTo(copy, overwrite = true)
        activeRecord = store.upsert(copy, copy.name, activeRecord?.category ?: FileCategory.PERSONAL)
        workingFile = copy
        showDrawer("Editor")
        toast("File copied")
    }

    private fun deleteWithUndo() {
        val record = activeRecord ?: return toast("Open a PDF first")
        val f = File(record.path)
        deletedBackup = File(cacheDir, "deleted_${System.currentTimeMillis()}.pdf")
        f.copyTo(deletedBackup!!, overwrite = true)
        deletedRecord = record
        store.remove(record)
        f.delete()
        closeDoc()
        showDrawer("Editor")
        toast("Deleted. Tap Recover to undo.")
    }

    private fun recoverDeleted() {
        val record = deletedRecord ?: return toast("Nothing to recover")
        val backup = deletedBackup ?: return toast("Nothing to recover")
        val restored = File(record.path)
        backup.copyTo(restored, overwrite = true)
        activeRecord = store.upsert(restored, record.name, record.category)
        workingFile = restored
        deletedRecord = null
        deletedBackup = null
        openRecordUnlocked(activeRecord!!)
        toast("Recovered")
    }

    // ---- undo / save / close ----

    private fun pushUndo() {
        val f = workingFile ?: return
        val bak = File(filesDir, "undo_${undoCounter++}.pdf")
        f.copyTo(bak, overwrite = true)
        undoStack.addLast(bak)
        while (undoStack.size > 20) undoStack.removeFirst().delete()
        // An edit is about to happen -> the document now has unsaved (unexported) changes.
        hasUnsavedChanges = true
    }

    private fun undo() {
        val bak = undoStack.removeLastOrNull() ?: return run { toast("Nothing to undo") }
        val f = workingFile ?: return
        bak.copyTo(f, overwrite = true); bak.delete()
        showPage()
    }

    private fun clearUndo() { undoStack.forEach { it.delete() }; undoStack.clear() }

    private fun exportTo(uri: Uri) = lifecycleScope.launch {
        val f = workingFile ?: return@launch
        try {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
            hasUnsavedChanges = false
            toast("Saved")
            com.irozar.ipdfmaster.utils.AppReview.recordSuccess(this@MainActivity)
            if (exitAfterSave) { exitAfterSave = false; closeDoc() }
        } catch (e: Exception) {
            exitAfterSave = false
            toast("Save failed: ${e.message}")
        }
    }

    /** If there are unsaved edits, ask the user to Save / Discard / Cancel before leaving. */
    private fun confirmExitIfDirty(onProceed: () -> Unit) {
        if (!hasUnsavedChanges || workingFile == null) { onProceed(); return }
        AlertDialog.Builder(this)
            .setTitle("Save changes?")
            .setMessage("You have unsaved changes to this PDF. Save them before leaving?")
            .setPositiveButton("Save") { _, _ ->
                exitAfterSave = true
                saveLauncher.launch(defaultSaveName())
            }
            .setNegativeButton("Discard") { _, _ ->
                hasUnsavedChanges = false
                onProceed()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun closeDoc() {
        cancelOverlay(); cancelMove(); clearUndo()
        b.annotationCanvas.clearAll()
        b.annotationCanvas.mode = AnnotationCanvasView.Mode.NONE
        workingFile = null; lines = emptyList(); words = emptyList()
        activeRecord = null
        updateSecureFlag()
        pageCount = 0; currentPage = 0
        b.pageView.setImageDrawable(null)
        b.pageLabel.text = "No document"
        b.viewerBar.visibility = View.GONE
        b.viewerBottomBar.visibility = View.GONE
        finish()
    }

    // ---- overlay helpers ----

    private fun topLeftRect(xPts: Float, yTopPts: Float, wPts: Float, hPts: Float) =
        RectF(xPts * scale, yTopPts * scale, (xPts + wPts) * scale, (yTopPts + hPts) * scale)

    private fun placeOverlayAt(bmpRect: RectF) {
        // Anchor the editing panel to the bottom of the scrim. The activity uses
        // windowSoftInputMode=adjustResize, so when the keyboard opens the scrim shrinks to
        // the area above it and this bottom-anchored panel rides just above the keyboard --
        // with the text field, colors and Apply button all staying visible.
        (b.overlayBox.layoutParams as FrameLayout.LayoutParams).apply {
            width = FrameLayout.LayoutParams.MATCH_PARENT
            gravity = android.view.Gravity.BOTTOM
            leftMargin = dp(8)
            rightMargin = dp(8)
            topMargin = 0
            bottomMargin = dp(8)
            b.overlayBox.layoutParams = this
        }
    }

    private fun showOverlay() {
        // Hide the bottom tool bar while editing so it doesn't float above the keyboard.
        b.viewerBottomBar.visibility = View.GONE
        b.overlayScrim.visibility = View.VISIBLE
        b.overlayEdit.requestFocus()
        imm().showSoftInput(b.overlayEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun cancelOverlay() {
        b.overlayScrim.visibility = View.GONE
        // Bring the bottom tool bar back (a document is open while editing).
        b.viewerBottomBar.visibility = if (workingFile != null) View.VISIBLE else View.GONE
        editingBlock = null
        imm().hideSoftInputFromWindow(b.overlayEdit.windowToken, 0)
    }

    /** Updates the left-edge Move formatting pop-out to reflect the current size/weight/colour. */
    private fun refreshMoveFormatBar() {
        b.moveSizeLabel.text = if (typeSize % 1f == 0f) typeSize.toInt().toString()
                               else String.format("%.1f", typeSize)
        b.moveBold.setTextColor(if (typeBold) TEAL else GRAY)
        b.moveItalic.setTextColor(if (typeItalic) TEAL else GRAY)
        b.moveColor.setTextColor(
            if (matchOriginalColor) GRAY
            else android.graphics.Color.rgb(colorRGB[0], colorRGB[1], colorRGB[2])
        )
    }

    private fun showMoveColorChooser() {
        val names = arrayOf("Match original", "Black", "Dark gray", "Red", "Blue", "Green")
        val colors = arrayOf(
            null, intArrayOf(0, 0, 0), intArrayOf(80, 80, 80),
            intArrayOf(220, 38, 38), intArrayOf(37, 99, 235), intArrayOf(22, 163, 74)
        )
        AlertDialog.Builder(this)
            .setTitle("Text color")
            .setItems(names) { _, which ->
                if (which == 0) matchOriginalColor = true
                else { colorRGB = colors[which]!!; matchOriginalColor = false }
                refreshMoveFormatBar()
            }
            .show()
    }

    /** Seed the format controls from the text being moved so a move keeps its look by default. */
    private fun initFormatFrom(block: TextBlock) {
        typeSize = block.fontSize.coerceIn(4f, 96f)
        typeFontName = "Original"
        typeBold = block.fontName.contains("bold", ignoreCase = true)
        typeItalic = block.fontName.contains("italic", ignoreCase = true) ||
            block.fontName.contains("oblique", ignoreCase = true)
        matchOriginalColor = true
    }

    private fun reportResult(r: PdfBlockEditor.Result) = when (r) {
        PdfBlockEditor.Result.EDITED -> {}
        PdfBlockEditor.Result.NO_MATCH -> toast("Couldn't locate that text (see logcat).")
        PdfBlockEditor.Result.FONT_UNSUPPORTED -> toast("Bundled font can't render that text (add Noto CJK/Arabic).")
        PdfBlockEditor.Result.ERROR -> toast("Operation failed (see logcat).")
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
