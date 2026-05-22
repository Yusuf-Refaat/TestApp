package com.example

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

enum class EraseTool {
    SMART_INPAINT, // Smart local content-aware filling
    SMART_BLUR,    // Smooth blur of watermark area
    COLOR_FILL     // Paint with selected background color
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFFFEF7FF) // M3 Professional Polish Soft Light Background
                ) { innerPadding ->
                    WatermarkRemoverApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkRemoverApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // --- State Management ---
    var originalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var displayedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Navigation & Stack History (Undo feature)
    val undoStack = remember { mutableStateListOf<Bitmap>() }

    // Brush Settings
    var brushSize by remember { mutableFloatStateOf(40f) }
    var pickedColor by remember { mutableStateOf(Color.White) }
    var activeTool by remember { mutableStateOf(EraseTool.SMART_INPAINT) }

    // Canvas sizes for safe coordinate scaling
    var canvasWidth by remember { mutableIntStateOf(1) }
    var canvasHeight by remember { mutableIntStateOf(1) }

    // Interactive gestural trails
    val currentPoints = remember { mutableStateListOf<Offset>() }
    var showColorDialog by remember { mutableStateOf(false) }

    // Status / Progress overlays
    var isSaving by remember { mutableStateOf(false) }
    var isDetecting by remember { mutableStateOf(false) }
    var apiStatusMessage by remember { mutableStateOf<String?>(null) }

    // Tutorial Dialog State
    var showTutorial by remember { mutableStateOf(true) }

    // Color Theme Definitions - "Professional Polish"
    val themeBg = Color(0xFFFEF7FF)
    val themeSurface = Color(0xFFF3EDF7)
    val themeTextPrimary = Color(0xFF1D1B20)
    val themeTextSecondary = Color(0xFF49454F)
    val themeBorder = Color(0xFFCAC4D0)
    val themeAccent = Color(0xFF6750A4)
    val themeLightAccent = Color(0xFFEADDFF)
    val themeDarkAccentText = Color(0xFF21005D)

    // Photo Picker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    try {
                        val decoded = decodeSampledBitmap(context, uri, 1800)
                        if (decoded != null) {
                            // Reset state
                            undoStack.clear()
                            originalBitmap = decoded
                            displayedBitmap = decoded
                            // Initialize mask bitmap of matching resolution
                            maskBitmap = Bitmap.createBitmap(
                                decoded.width,
                                decoded.height,
                                Bitmap.Config.ARGB_8888
                            )
                            apiStatusMessage = null
                        } else {
                            Toast.makeText(context, "فشل في تحميل الصورة", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "خطأ: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    // Execute Erase Operation
    fun executeInpaint() {
        val currentDisplay = displayedBitmap ?: return
        val currentMask = maskBitmap ?: return

        coroutineScope.launch(Dispatchers.Default) {
            isSaving = true
            try {
                // Pre-push to undo stack
                withContext(Dispatchers.Main) {
                    undoStack.add(currentDisplay.copy(currentDisplay.config ?: Bitmap.Config.ARGB_8888, false))
                }

                // Process depending on tool selection
                val result = when (activeTool) {
                    EraseTool.SMART_INPAINT -> {
                        SmartEraseEngine.smartInpaint(currentDisplay, currentMask)
                    }
                    EraseTool.SMART_BLUR -> {
                        SmartEraseEngine.smartBlur(currentDisplay, currentMask, blurRadius = 14)
                    }
                    EraseTool.COLOR_FILL -> {
                        SmartEraseEngine.solidFill(currentDisplay, currentMask, pickedColor.toArgb())
                    }
                }

                // Smooth update
                withContext(Dispatchers.Main) {
                    displayedBitmap = result
                    // Reset mask to empty transparent state
                    maskBitmap = Bitmap.createBitmap(
                        result.width,
                        result.height,
                        Bitmap.Config.ARGB_8888
                    )
                    currentPoints.clear()
                    isSaving = false
                    Toast.makeText(context, "تمت التصفية والإزالة بنجاح!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    Toast.makeText(context, "فشل الإجراء: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Trigger AI Smart Watermark Locating using Gemini API Custom Engine
    fun triggerAIDetection() {
        val currentDisplay = displayedBitmap ?: return
        coroutineScope.launch {
            isDetecting = true
            apiStatusMessage = "جاري الكشف الذكي بالذكاء الاصطناعي..."
            try {
                val detection = GeminiImageDetector.detectLogoBoundingBox(currentDisplay)
                if (detection.hasWatermark) {
                    // Coordinates returned are normalized [0.0..1.0]
                    // Scale them into the high-res physical mask bitmap
                    val currentMask = maskBitmap
                    if (currentMask != null) {
                        val canvas = AndroidCanvas(currentMask)
                        val paint = AndroidPaint().apply {
                            color = android.graphics.Color.RED
                            style = AndroidPaint.Style.FILL
                        }

                        val top = detection.ymin * currentMask.height
                        val left = detection.xmin * currentMask.width
                        val bottom = detection.ymax * currentMask.height
                        val right = detection.xmax * currentMask.width

                        // Draw a solid rect mask over the identified watermark region
                        canvas.drawRect(left, top, right, bottom, paint)

                        // Trigger visual redraw
                        maskBitmap = currentMask
                        apiStatusMessage = "✨ تم العثور على العلامة المائية وتحديدها تلقائياً!"
                        Toast.makeText(context, "تم تحديد العلامة تلقائياً! اضغط 'مسح' للإنهاء", Toast.LENGTH_LONG).show()
                    }
                } else {
                    apiStatusMessage = "لم يتم العثور على علامة مائية واضحة. يرجى تلوينها يدويًا."
                }
            } catch (e: Exception) {
                apiStatusMessage = "خطأ الاتصال: ${e.localizedMessage}"
            } finally {
                isDetecting = false
            }
        }
    }

    // Execute Immediate Crop of Selected Margin (Express crop tool)
    fun executeExpressCrop(side: String) {
        val currentDisplay = displayedBitmap ?: return
        val w = currentDisplay.width
        val h = currentDisplay.height

        coroutineScope.launch(Dispatchers.Default) {
            isSaving = true
            try {
                withContext(Dispatchers.Main) {
                    undoStack.add(currentDisplay.copy(currentDisplay.config ?: Bitmap.Config.ARGB_8888, false))
                }

                // Slice standard watermark margins instantly
                val cropped = when (side) {
                    "BOTTOM_5" -> Bitmap.createBitmap(currentDisplay, 0, 0, w, (h * 0.95f).toInt())
                    "BOTTOM_10" -> Bitmap.createBitmap(currentDisplay, 0, 0, w, (h * 0.90f).toInt())
                    "TOP_5" -> Bitmap.createBitmap(currentDisplay, 0, (h * 0.05f).toInt(), w, (h * 0.95f).toInt())
                    "LEFT_10" -> Bitmap.createBitmap(currentDisplay, (w * 0.10f).toInt(), 0, (w * 0.90f).toInt(), h)
                    "RIGHT_10" -> Bitmap.createBitmap(currentDisplay, 0, 0, (w * 0.90f).toInt(), h)
                    else -> currentDisplay
                }

                withContext(Dispatchers.Main) {
                    displayedBitmap = cropped
                    maskBitmap = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
                    currentPoints.clear()
                    isSaving = false
                    Toast.makeText(context, "تم قص وتحديث الصورة بنجاح!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    Toast.makeText(context, "فشل القص: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Execute Secure Share operation
    fun shareEditedImage() {
        val bitmap = displayedBitmap ?: return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val cachePath = File(context.cacheDir, "shared_images")
                cachePath.mkdirs()
                val file = File(cachePath, "cleaned_photo_${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, file)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                withContext(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(intent, "مشاركة الصورة المنظفة"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "فشل المشاركة: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        // Main layout
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(themeBg)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Custom Display Top Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "مزيل شعار جيميناي ✨",
                        color = themeTextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Right
                    )
                    Text(
                        text = "إزالة الشعارات والعلامات المائية بلمسة احترافية",
                        color = themeTextSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Right
                    )
                }

                // Status Badge / Interactive Indicators
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { showTutorial = true },
                        modifier = Modifier
                            .background(themeLightAccent, CircleShape)
                            .size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "تعليمات التطبيق",
                            tint = themeDarkAccentText
                        )
                    }

                    if (undoStack.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val last = undoStack.removeLast()
                                displayedBitmap = last
                                maskBitmap = Bitmap.createBitmap(last.width, last.height, Bitmap.Config.ARGB_8888)
                                currentPoints.clear()
                                Toast.makeText(context, "تم إلغاء آخر خطوة 🔙", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .background(themeLightAccent, CircleShape)
                                .size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Undo,
                                contentDescription = "تراجع",
                                tint = Color(0xFFB3261E) // Material standard Red error key color
                            )
                        }
                    }
                }
            }

            // --- Core Workspace Screen ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, themeBorder, RoundedCornerShape(24.dp))
                    .background(themeSurface),
                contentAlignment = Alignment.Center
            ) {
                val currentBitmap = displayedBitmap

                if (currentBitmap != null) {
                    // Interactive Brush Editor Surface
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Force the canvas container to strictly match the aspect ratio of the image
                        // This creates flawless 1:1 touch coordinate maps
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .aspectRatio(currentBitmap.width.toFloat() / currentBitmap.height.toFloat())
                                .onGloballyPositioned { coordinates ->
                                    canvasWidth = coordinates.size.width
                                    canvasHeight = coordinates.size.height
                                }
                                .pointerInput(canvasWidth, canvasHeight, brushSize) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentPoints.add(offset)
                                            paintPointToMask(
                                                offset,
                                                brushSize,
                                                canvasWidth,
                                                canvasHeight,
                                                currentBitmap.width,
                                                currentBitmap.height,
                                                maskBitmap
                                            )
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val position = change.position
                                            currentPoints.add(position)
                                            paintPointToMask(
                                                position,
                                                brushSize,
                                                canvasWidth,
                                                canvasHeight,
                                                currentBitmap.width,
                                                currentBitmap.height,
                                                maskBitmap
                                            )
                                        },
                                        onDragEnd = {
                                            // End of drag
                                        }
                                    )
                                }
                        ) {
                            // Display the background edited image
                            androidx.compose.foundation.Image(
                                bitmap = currentBitmap.asImageBitmap(),
                                contentDescription = "مساحة تعديل الصور",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            // Compose overlay drawing trail to show exact brushing path in real-time
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                if (currentPoints.isNotEmpty()) {
                                    val path = Path().apply {
                                        val first = currentPoints.first()
                                        moveTo(first.x, first.y)
                                        for (i in 1 until currentPoints.size) {
                                            val point = currentPoints[i]
                                            lineTo(point.x, point.y)
                                        }
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(0x996750A4), // Semi-transparent theme Accent purple for professional feedback
                                        style = Stroke(
                                            width = brushSize,
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round
                                        )
                                    )
                                }
                            }
                        }

                        // --- Loading or processing overlay ---
                        if (isSaving) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.White.copy(alpha = 0.8f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = themeAccent)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("جاري معالجة وتعبئة البكسلات بالذكاء...", color = themeTextPrimary, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                } else {
                    // Empty State Frame in Light Material 3 Style
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .shadow(8.dp, CircleShape)
                                .background(themeLightAccent, CircleShape)
                                .padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PhotoLibrary,
                                contentDescription = "اختر صورة",
                                modifier = Modifier.size(64.dp),
                                tint = themeAccent
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "لا توجد صورة محملة",
                            color = themeTextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "اختر صورة تحتوي على شعار جيميناي أو علامة مائية للبدء في مسحها فوراً.",
                            color = themeTextSecondary,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = themeAccent),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("select_first_image_btn")
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "استيراد صورة من الاستوديو 🖼️",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // --- Status Feedback Panel ---
            AnimatedVisibility(visible = apiStatusMessage != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = themeLightAccent),
                    border = BorderStroke(1.dp, themeBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = themeAccent,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = apiStatusMessage ?: "",
                            color = themeDarkAccentText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { apiStatusMessage = null }) {
                            Icon(Icons.Default.Close, contentDescription = "اغلاق", tint = themeTextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // --- Interactive Multi-Tool Control Options ---
            if (displayedBitmap != null) {
                Spacer(modifier = Modifier.height(12.dp))

                // Tool Switcher (Inpaint / Blur / Color Paint)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToolChip(
                        label = "🧹 مسح ذكي (تعبئة)",
                        isSelected = activeTool == EraseTool.SMART_INPAINT,
                        onClick = { activeTool = EraseTool.SMART_INPAINT },
                        accentColor = themeAccent,
                        lightAccentColor = themeLightAccent,
                        surfaceColor = themeSurface,
                        textPrimary = themeTextPrimary,
                        textDarkAccent = themeDarkAccentText
                    )
                    ToolChip(
                        label = "🌫️ تمويه وتغبيش",
                        isSelected = activeTool == EraseTool.SMART_BLUR,
                        onClick = { activeTool = EraseTool.SMART_BLUR },
                        accentColor = themeAccent,
                        lightAccentColor = themeLightAccent,
                        surfaceColor = themeSurface,
                        textPrimary = themeTextPrimary,
                        textDarkAccent = themeDarkAccentText
                    )
                    ToolChip(
                        label = "🎨 فرشاة لون سادة",
                        isSelected = activeTool == EraseTool.COLOR_FILL,
                        onClick = { activeTool = EraseTool.COLOR_FILL },
                        accentColor = themeAccent,
                        lightAccentColor = themeLightAccent,
                        surfaceColor = themeSurface,
                        textPrimary = themeTextPrimary,
                        textDarkAccent = themeDarkAccentText
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Slider to control brush size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(themeSurface, RoundedCornerShape(12.dp))
                        .border(1.dp, themeBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Brush,
                        contentDescription = "حجم الفرشاة",
                        tint = themeTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "الحجم: ${brushSize.toInt()}px",
                        color = themeTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.width(85.dp)
                    )
                    Slider(
                        value = brushSize,
                        onValueChange = { brushSize = it },
                        valueRange = 10f..100f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            activeTrackColor = themeAccent,
                            thumbColor = themeAccent,
                            inactiveTrackColor = themeLightAccent
                        )
                    )

                    // If solid color is active, show color picker dialog button
                    if (activeTool == EraseTool.COLOR_FILL) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(pickedColor)
                                .border(2.dp, themeBorder, CircleShape)
                                .clickable { showColorDialog = true }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Crop Drawer / Instant actions Panel
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = themeSurface),
                    border = BorderStroke(1.dp, themeBorder),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "✂️ أدوات قص سريعة للهوامش (مزيل شعار جيميناي السفلي)",
                            color = themeTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { executeExpressCrop("BOTTOM_5") },
                                colors = ButtonDefaults.buttonColors(containerColor = themeLightAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("✂️ قص الأسفل 5%", fontSize = 11.sp, color = themeDarkAccentText, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { executeExpressCrop("BOTTOM_10") },
                                colors = ButtonDefaults.buttonColors(containerColor = themeLightAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("✂️ قص الأسفل 10%", fontSize = 11.sp, color = themeDarkAccentText, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { executeExpressCrop("LEFT_10") },
                                colors = ButtonDefaults.buttonColors(containerColor = themeLightAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("⬅️ قص اليسار 10%", fontSize = 11.sp, color = themeDarkAccentText, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = { executeExpressCrop("RIGHT_10") },
                                colors = ButtonDefaults.buttonColors(containerColor = themeLightAccent),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("➡️ قص اليمين 10%", fontSize = 11.sp, color = themeDarkAccentText, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // --- Action Controls Dashboard Layout ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // CLEAR BRUSH STROKES
                    Button(
                        onClick = {
                            val currentDisplay = displayedBitmap
                            if (currentDisplay != null) {
                                maskBitmap = Bitmap.createBitmap(
                                    currentDisplay.width,
                                    currentDisplay.height,
                                    Bitmap.Config.ARGB_8888
                                )
                            }
                            currentPoints.clear()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = themeLightAccent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp), tint = themeDarkAccentText)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("مسح التلوين", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = themeDarkAccentText)
                    }

                    // AI AUTOMATIC WATERMARK PICKER
                    Button(
                        onClick = { triggerAIDetection() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDetecting) themeLightAccent else themeLightAccent
                        ),
                        border = BorderStroke(1.dp, themeAccent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = !isDetecting
                    ) {
                        if (isDetecting) {
                            CircularProgressIndicator(color = themeAccent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp), tint = themeAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("كشف ذكي AI", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = themeDarkAccentText)
                        }
                    }

                    // SUBMIT ERASE WIPE (PRIMARY ACCENT COLOR WITH WHITE COLOR TEXT)
                    Button(
                        onClick = { executeInpaint() },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = themeAccent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = !isSaving
                    ) {
                        Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تطبيق وإزالة 🧹", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // --- Footer Save & Share Row ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // CHOOSE ANOTHER PHOTO
                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, themeBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = themeAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp), tint = themeAccent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("صورة أخرى", fontSize = 12.sp, color = themeTextPrimary, fontWeight = FontWeight.SemiBold)
                    }

                    // SHARE PHOTO ACTION BUTTON
                    Button(
                        onClick = { shareEditedImage() },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = themeAccent),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("مشاركة الصورة المنظفة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    // --- Brush Color Selector Dialog ---
    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("اختر لون الفرشاة", textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth(), color = themeTextPrimary) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("اختر لوناً يطابق المنطقة المحيطة لتمويه ورسم مثالي:", fontSize = 12.sp, color = themeTextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        ColorCircle(Color.White) { pickedColor = it; showColorDialog = false }
                        ColorCircle(Color.Black) { pickedColor = it; showColorDialog = false }
                        ColorCircle(Color(0xFFE0E0E0)) { pickedColor = it; showColorDialog = false }
                        ColorCircle(Color(0xFF81C784)) { pickedColor = it; showColorDialog = false }
                        ColorCircle(Color(0xFF64B5F6)) { pickedColor = it; showColorDialog = false }
                        ColorCircle(Color(0xFFFFB74D)) { pickedColor = it; showColorDialog = false }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) {
                    Text("إغلاق", color = themeAccent, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // --- On-boarding / User Tutorial Dialog ---
    if (showTutorial) {
        AlertDialog(
            onDismissRequest = { showTutorial = false },
            title = {
                Text(
                    text = "خطوات إزالة الشعارات بسهولة 💡",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth(),
                    color = themeTextPrimary
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "1. استورد الصورة من الاستوديو التي تحتوي على شعار أو علامة مائية جيميناي.",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Right,
                        color = themeTextSecondary
                    )
                    Text(
                        text = "2. استخدم إصبعك للتلوين والرسم بدقة فوق موضع لوجو جيميناي.",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Right,
                        color = themeTextSecondary
                    )
                    Text(
                        text = "3. اضغط 'كشف ذكي AI' لتمكين محرك الذكاء الاصطناعي (Gemini) لتحديد الشعار آلياً!",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Right,
                        color = themeTextSecondary
                    )
                    Text(
                        text = "4. اضغط 'تطبيق وإزالة' لتشغيل خوارزمية التعبئة الذكية ومسح التلوين فوراً.",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Right,
                        color = themeTextSecondary
                    )
                    Text(
                        text = "5. يمكنك استخدام أداة 'القص السريع' للتخلص الفوري من الشعارات الموجودة بالهوامش السفلية واليسرى.",
                        fontSize = 13.sp,
                        textAlign = TextAlign.Right,
                        color = themeTextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showTutorial = false },
                    colors = ButtonDefaults.buttonColors(containerColor = themeAccent)
                ) {
                    Text("ابدأ الاستخدام الآن 👍", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun ToolChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    accentColor: Color,
    lightAccentColor: Color,
    surfaceColor: Color,
    textPrimary: Color,
    textDarkAccent: Color
) {
    val bgColor by animateColorAsState(targetValue = if (isSelected) accentColor else surfaceColor)
    val txtColor by animateColorAsState(targetValue = if (isSelected) Color.White else textDarkAccent)

    Box(
        modifier = Modifier
            .shadow(if (isSelected) 4.dp else 0.dp, RoundedCornerShape(10.dp))
            .background(bgColor, RoundedCornerShape(10.dp))
            .border(1.dp, if (isSelected) accentColor else Color(0xFFCAC4D0), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = txtColor,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
fun ColorCircle(color: Color, onClick: (Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .shadow(4.dp, CircleShape)
            .background(color, CircleShape)
            .border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape)
            .clickable { onClick(color) }
    )
}

/**
 * Optimized off-screen coordinate scaling helper to draw on real-res bitmap mask canvas.
 */
private fun paintPointToMask(
    offset: Offset,
    brushSize: Float,
    canvasW: Int,
    canvasH: Int,
    bitmapW: Int,
    bitmapH: Int,
    mask: Bitmap?
) {
    val currentMask = mask ?: return

    // Find physical scale ratio
    val scaleX = bitmapW.toFloat() / canvasW.toFloat()
    val scaleY = bitmapH.toFloat() / canvasH.toFloat()

    // Map display touch coordinates directly to high-res biological bitmap pixels
    val mappedX = offset.x * scaleX
    val mappedY = offset.y * scaleY

    val canvas = AndroidCanvas(currentMask)
    val paint = AndroidPaint().apply {
        color = android.graphics.Color.RED // Use solid saturated red marker for mask detection
        style = AndroidPaint.Style.FILL
    }

    // Draw solid circle over position matching physical brush scale
    val scaledBrushRadius = (brushSize / 2f) * scaleX
    canvas.drawCircle(mappedX, mappedY, scaledBrushRadius, paint)
}

/**
 * Memory-safe bitmap decoding scaled to specific maximum dimensions.
 */
private suspend fun decodeSampledBitmap(context: android.content.Context, uri: Uri, maxDim: Int): Bitmap? = withContext(Dispatchers.IO) {
    var input: InputStream? = null
    try {
        input = context.contentResolver.openInputStream(uri)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeStream(input, null, options)
        input?.close()

        // Calculate sample size
        var sampleSize = 1
        if (options.outHeight > maxDim || options.outWidth > maxDim) {
            val halfHeight = options.outHeight / 2
            val halfWidth = options.outWidth / 2
            while ((halfHeight / sampleSize) >= maxDim && (halfWidth / sampleSize) >= maxDim) {
                sampleSize *= 2
            }
        }

        // Decode with specified sampleSize
        input = context.contentResolver.openInputStream(uri)
        val outOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inMutable = true // Ensure mutability for content aware edits
        }
        val decoded = BitmapFactory.decodeStream(input, null, outOptions)
        return@withContext decoded
    } catch (e: Exception) {
        e.printStackTrace()
        return@withContext null
    } finally {
        input?.close()
    }
}
