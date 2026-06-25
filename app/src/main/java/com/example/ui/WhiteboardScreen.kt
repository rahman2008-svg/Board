package com.example.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BoardElement
import com.example.data.BoardPoint
import com.example.data.ElementType
import com.example.data.PenType
import com.example.data.ShapeType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhiteboardScreen(
    viewModel: WhiteboardViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val board by viewModel.currentBoard.collectAsState()
    val elements by viewModel.elements.collectAsState()
    val selectedId by viewModel.selectedElementId.collectAsState()
    val activeTool by viewModel.activeTool.collectAsState()
    val activeColorHex by viewModel.activeColorHex.collectAsState()
    val strokeWidth by viewModel.strokeWidth.collectAsState()
    val snapToGrid by viewModel.snapToGrid.collectAsState()
    val autoShapeCorrect by viewModel.autoShapeCorrect.collectAsState()
    val presentationMode by viewModel.presentationMode.collectAsState()
    val laserPoints by viewModel.laserPoints.collectAsState()

    // Infinite Canvas parameters
    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Screen size estimation (for centering new elements)
    var canvasSize by remember { mutableStateOf(Size(1000f, 1000f)) }

    // Floating dialogs states
    var textEditElementId by remember { mutableStateOf<String?>(null) }
    var textEditText by remember { mutableStateOf("") }
    var showTextDialog by remember { mutableStateOf(false) }

    var showExportDialog by remember { mutableStateOf(false) }

    // Current temporary drawing path
    val currentDrawingPoints = remember { mutableStateListOf<BoardPoint>() }

    // Show status toasts/snackbars from VM
    LaunchedEffect(Unit) {
        viewModel.statusMessage.collectLatest { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // Dynamic clean laser pointer trail fader
    LaunchedEffect(laserPoints) {
        if (laserPoints.isNotEmpty()) {
            delay(800)
            viewModel.clearLaserPoints()
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val centerCanvas = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
            viewModel.addImageElement(uri.toString(), centerCanvas.x - 150f, centerCanvas.y - 150f)
        }
    }

    Scaffold(
        topBar = {
            if (!presentationMode) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = board?.name ?: "Whiteboard",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = board?.folder ?: "All Boards",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.closeBoard()
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    ),
                    actions = {
                        IconButton(onClick = { viewModel.undo() }) {
                            Icon(Icons.AutoMirrored.Default.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = { viewModel.redo() }) {
                            Icon(Icons.AutoMirrored.Default.Redo, contentDescription = "Redo")
                        }
                        
                        // Board lock/unlock
                        IconButton(onClick = { viewModel.toggleBoardLock() }) {
                            Icon(
                                imageVector = if (board?.isLocked == true) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock State",
                                tint = if (board?.isLocked == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        
                        // Export Action
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(Icons.Default.IosShare, contentDescription = "Export")
                        }

                        // Presentation Mode Trigger
                        IconButton(onClick = { viewModel.togglePresentationMode() }) {
                            Icon(Icons.Default.Tv, contentDescription = "Presentation Mode")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (presentationMode) PaddingValues(0.dp) else innerPadding)
                .background(Color(0xFF211F26)) // Modern sleek canvas dark background
        ) {
            // THE INFINITE CANVAS WRAPPER
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activeTool, board?.isLocked) {
                        if (board?.isLocked == true) {
                            // Infinite canvas zoom-pan ONLY when locked
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.4f, 5f)
                                panOffset += pan
                            }
                            return@pointerInput
                        }

                        if (activeTool == ToolType.SELECT) {
                            // Zoom-pan via transform gestures (or tap selection if single finger)
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.4f, 5f)
                                panOffset += pan
                            }
                        } else {
                            // Non-selection tools zoom via two-finger touch detection
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.4f, 5f)
                                panOffset += pan
                            }
                        }
                    }
                    .pointerInput(activeTool, board?.isLocked) {
                        if (board?.isLocked == true) return@pointerInput

                        // Single finger operations: drawing, moving, resizing, laser pointing
                        detectDragGestures(
                            onDragStart = { startPos ->
                                val canvasPoint = (startPos - panOffset) / scale
                                if (activeTool == ToolType.SELECT) {
                                    // Hit test to select or drag elements
                                    viewModel.selectElementAt(canvasPoint.x, canvasPoint.y)
                                } else if (activeTool == ToolType.LASER_POINTER) {
                                    viewModel.setLaserPoints(listOf(BoardPoint(canvasPoint.x, canvasPoint.y)))
                                } else if (activeTool != ToolType.ERASER) {
                                    // Drawing path starting
                                    currentDrawingPoints.clear()
                                    currentDrawingPoints.add(BoardPoint(canvasPoint.x, canvasPoint.y))
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val canvasPoint = (change.position - panOffset) / scale
                                
                                if (activeTool == ToolType.SELECT) {
                                    val sel = selectedId
                                    if (sel != null) {
                                        val el = elements.find { it.id == sel }
                                        if (el != null) {
                                            // Move element
                                            val newX = el.x + dragAmount.x / scale
                                            val newY = el.y + dragAmount.y / scale
                                            viewModel.updateElementPositionAndSize(sel, newX, newY, el.width, el.height)
                                        }
                                    }
                                } else if (activeTool == ToolType.ERASER) {
                                    // Intersection collision check
                                    viewModel.selectElementAt(canvasPoint.x, canvasPoint.y)
                                    viewModel.deleteSelectedElement()
                                } else if (activeTool == ToolType.LASER_POINTER) {
                                    val currentLaser = viewModel.laserPoints.value
                                    viewModel.setLaserPoints(currentLaser + BoardPoint(canvasPoint.x, canvasPoint.y))
                                } else {
                                    // Append point to drawing paths
                                    currentDrawingPoints.add(BoardPoint(canvasPoint.x, canvasPoint.y))
                                }
                            },
                            onDragEnd = {
                                if (activeTool == ToolType.SELECT) {
                                    viewModel.saveCurrentBoardState()
                                } else if (activeTool == ToolType.LASER_POINTER) {
                                    // Fades away in effect
                                } else if (activeTool != ToolType.ERASER && currentDrawingPoints.isNotEmpty()) {
                                    val penType = when (activeTool) {
                                        ToolType.PEN -> PenType.PEN
                                        ToolType.PENCIL -> PenType.PENCIL
                                        ToolType.MARKER -> PenType.MARKER
                                        ToolType.HIGHLIGHTER -> PenType.HIGHLIGHTER
                                        else -> PenType.PEN
                                    }
                                    viewModel.addPathElement(
                                        currentDrawingPoints.toList(),
                                        penType,
                                        strokeWidth,
                                        activeColorHex
                                    )
                                    currentDrawingPoints.clear()
                                }
                            }
                        )
                    }
                    .testTag("infinite_drawing_canvas")
            ) {
                // Actual drawings canvas with scaling/panning applied
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { pressOffset ->
                                val canvasPoint = (pressOffset - panOffset) / scale
                                if (activeTool == ToolType.SELECT) {
                                    viewModel.selectElementAt(canvasPoint.x, canvasPoint.y)
                                }
                            }
                        }
                ) {
                    canvasSize = size

                    // Apply coordinate transformation
                    drawContext.canvas.save()
                    drawContext.canvas.translate(panOffset.x, panOffset.y)
                    drawContext.canvas.scale(scale, scale)

                    // 1. Draw Background Grid System
                    drawInfiniteGrid(size, scale, panOffset, isDark = (activeColorHex == "#FFFFFFFF"))

                    // 2. Render all stored elements from database
                    val sorted = elements.sortedBy { it.layerIndex }
                    for (el in sorted) {
                        drawBoardElement(el)
                    }

                    // 3. Render currently drawing path (real time feedback)
                    if (currentDrawingPoints.size >= 2) {
                        val path = Path().apply {
                            moveTo(currentDrawingPoints[0].x, currentDrawingPoints[0].y)
                            for (i in 1 until currentDrawingPoints.size) {
                                lineTo(currentDrawingPoints[i].x, currentDrawingPoints[i].y)
                            }
                        }
                        
                        val pathColor = Color(android.graphics.Color.parseColor(activeColorHex))
                        val alpha = when (activeTool) {
                            ToolType.HIGHLIGHTER -> 0.45f
                            ToolType.PENCIL -> 0.7f
                            else -> 1.0f
                        }
                        
                        drawPath(
                            path = path,
                            color = pathColor.copy(alpha = alpha),
                            style = Stroke(
                                width = strokeWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // 4. Render Laser pointer temporary path
                    if (laserPoints.size >= 2) {
                        val laserPath = Path().apply {
                            moveTo(laserPoints[0].x, laserPoints[0].y)
                            for (i in 1 until laserPoints.size) {
                                lineTo(laserPoints[i].x, laserPoints[i].y)
                            }
                        }
                        drawPath(
                            path = laserPath,
                            color = Color.Red,
                            style = Stroke(
                                width = 12f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    drawContext.canvas.restore()
                }

                // 4.5 Overlay Elements (Text, Sticky Notes, Images) rendered in Screen Space dynamically
                for (el in elements) {
                    if (el.type == ElementType.TEXT || el.type == ElementType.STICKY_NOTE || el.type == ElementType.IMAGE) {
                        val screenX = el.x * scale + panOffset.x
                        val screenY = el.y * scale + panOffset.y
                        val screenW = el.width * scale
                        val screenH = el.height * scale

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
                                .size(screenW.dp, screenH.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    if (activeTool == ToolType.SELECT) {
                                        viewModel.selectElementAt(el.x + 10f, el.y + 10f)
                                    }
                                }
                        ) {
                            when (el.type) {
                                ElementType.STICKY_NOTE -> {
                                    Card(
                                        shape = RoundedCornerShape((8 * scale).dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = Color(android.graphics.Color.parseColor(el.colorHex))
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = (2 * scale).dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding((12 * scale).dp)
                                        ) {
                                            Text(
                                                text = el.text ?: "",
                                                color = Color.Black,
                                                fontSize = (15 * scale).sp,
                                                lineHeight = (18 * scale).sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                                ElementType.TEXT -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding((4 * scale).dp)
                                    ) {
                                        Text(
                                            text = el.text ?: "",
                                            color = Color(android.graphics.Color.parseColor(el.colorHex)),
                                            fontSize = (16 * scale).sp,
                                            lineHeight = (20 * scale).sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                ElementType.IMAGE -> {
                                    Card(
                                        shape = RoundedCornerShape((6 * scale).dp),
                                        modifier = Modifier.fillMaxSize(),
                                        colors = CardDefaults.cardColors(containerColor = Color.LightGray.copy(alpha = 0.3f))
                                    ) {
                                        if (el.imageUri != null) {
                                            androidx.compose.foundation.Image(
                                                painter = coil.compose.rememberAsyncImagePainter(el.imageUri),
                                                contentDescription = "Inserted Image",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.Image, contentDescription = null, tint = Color.Gray)
                                            }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                }

                // 5. Draw bounding box selection controls in Screen Space
                val sel = selectedId
                if (sel != null && activeTool == ToolType.SELECT) {
                    val element = elements.find { it.id == sel }
                    if (element != null && element.type != ElementType.PATH) {
                        // Position of element in Screen Space
                        val screenX = element.x * scale + panOffset.x
                        val screenY = element.y * scale + panOffset.y
                        val screenW = element.width * scale
                        val screenH = element.height * scale

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(screenX.roundToInt(), screenY.roundToInt()) }
                                .size(screenW.dp, screenH.dp)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                        ) {
                            // Top action icons
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(y = (-36).dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Delete button
                                IconButton(
                                    onClick = { viewModel.deleteSelectedElement() },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                                }
                                
                                // Text Edit trigger
                                if (element.type == ElementType.TEXT || element.type == ElementType.STICKY_NOTE) {
                                    IconButton(
                                        onClick = {
                                            textEditElementId = element.id
                                            textEditText = element.text ?: ""
                                            showTextDialog = true
                                        },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Text", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            // Layer manipulation action buttons at the bottom
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .offset(y = (36).dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewModel.bringSelectedToFront() }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.VerticalAlignTop, contentDescription = "Bring Forward", modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { viewModel.sendSelectedToBack() }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.VerticalAlignBottom, contentDescription = "Send Backward", modifier = Modifier.size(16.dp))
                                }
                            }

                            // Bottom-right resize handle
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(18.dp)
                                    .offset(8.dp, 8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val newW = (element.width + dragAmount.x / scale).coerceAtLeast(100f)
                                            val newH = (element.height + dragAmount.y / scale).coerceAtLeast(100f)
                                            viewModel.updateElementPositionAndSize(element.id, element.x, element.y, newW, newH)
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            // TOOLBARS OVERLAY
            if (presentationMode) {
                // Minimal Presentation Mode controls
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Presentation Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Divider(modifier = Modifier.height(20.dp).width(1.dp))
                        
                        // Active tool indicator
                        IconButton(
                            onClick = { viewModel.setTool(ToolType.LASER_POINTER) },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (activeTool == ToolType.LASER_POINTER) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                        ) {
                            Icon(Icons.Default.Gesture, contentDescription = "Laser", tint = if (activeTool == ToolType.LASER_POINTER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        }

                        // Exit presentation button
                        IconButton(onClick = { viewModel.togglePresentationMode() }) {
                            Icon(Icons.Default.ExitToApp, contentDescription = "Exit Mode", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                // NORMAL MODE EDITING TOOLBARS
                
                // Left-side Toolbar: PEN type options, Eraser, Move
                Card(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(12.dp)
                        .shadow(12.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFF49454F)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930))
                ) {
                    Column(
                        modifier = Modifier.padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ToolIcon(ToolType.SELECT, Icons.Default.PanTool, activeTool) { viewModel.setTool(ToolType.SELECT) }
                        ToolIcon(ToolType.PEN, Icons.Default.Create, activeTool) { viewModel.setTool(ToolType.PEN) }
                        ToolIcon(ToolType.PENCIL, Icons.Default.Brush, activeTool) { viewModel.setTool(ToolType.PENCIL) }
                        ToolIcon(ToolType.MARKER, Icons.Default.Gesture, activeTool) { viewModel.setTool(ToolType.MARKER) }
                        ToolIcon(ToolType.HIGHLIGHTER, Icons.Default.BorderColor, activeTool) { viewModel.setTool(ToolType.HIGHLIGHTER) }
                        ToolIcon(ToolType.ERASER, Icons.Default.CleaningServices, activeTool) { viewModel.setTool(ToolType.ERASER) }
                    }
                }

                // Bottom toolbar: Add shapes, text, images, notes, palette
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 24.dp)
                        .shadow(16.dp, RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color(0xFF49454F)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2930))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Quick Action Buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Shape Menu
                            var showShapesMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showShapesMenu = true }) {
                                    Icon(Icons.Default.Category, contentDescription = "Add Shape")
                                }
                                DropdownMenu(expanded = showShapesMenu, onDismissRequest = { showShapesMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Circle") },
                                        leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, null) },
                                        onClick = {
                                            val c = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
                                            viewModel.addShapeElement(ShapeType.CIRCLE, c.x - 100f, c.y - 100f, 200f, 200f)
                                            showShapesMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Rectangle") },
                                        leadingIcon = { Icon(Icons.Default.CheckBoxOutlineBlank, null) },
                                        onClick = {
                                            val c = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
                                            viewModel.addShapeElement(ShapeType.RECTANGLE, c.x - 150f, c.y - 100f, 300f, 200f)
                                            showShapesMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Line") },
                                        leadingIcon = { Icon(Icons.Default.HorizontalRule, null) },
                                        onClick = {
                                            val c = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
                                            viewModel.addShapeElement(ShapeType.LINE, c.x - 100f, c.y - 50f, 200f, 100f)
                                            showShapesMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Arrow") },
                                        leadingIcon = { Icon(Icons.Default.TrendingFlat, null) },
                                        onClick = {
                                            val c = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
                                            viewModel.addShapeElement(ShapeType.ARROW, c.x - 100f, c.y - 50f, 200f, 100f)
                                            showShapesMenu = false
                                        }
                                    )
                                }
                            }

                            // Sticky Note
                            IconButton(onClick = {
                                val c = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
                                viewModel.addStickyNote("Sticky Note text...", c.x - 125f, c.y - 125f)
                            }) {
                                Icon(Icons.Default.StickyNote2, contentDescription = "Add Sticky Note")
                            }

                            // Text Box
                            IconButton(onClick = {
                                val c = (Offset(canvasSize.width / 2f, canvasSize.height / 2f) - panOffset) / scale
                                viewModel.addTextElement("Edit text...", c.x - 150f, c.y - 50f)
                            }) {
                                Icon(Icons.Default.TextFields, contentDescription = "Add Text")
                            }

                            // Insert Image
                            IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                                Icon(Icons.Default.Image, contentDescription = "Add Image")
                            }

                            Divider(modifier = Modifier.height(24.dp).width(1.dp))

                            // Grid Snap toggle
                            IconButton(
                                onClick = { viewModel.toggleSnapToGrid() },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (snapToGrid) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                                )
                            ) {
                                Icon(Icons.Default.GridOn, contentDescription = "Snap to Grid", tint = if (snapToGrid) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                            }

                            // Auto Correction toggle
                            IconButton(
                                onClick = { viewModel.toggleAutoShapeCorrect() },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (autoShapeCorrect) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                                )
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = "Auto Correction", tint = if (autoShapeCorrect) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Color Palette row (Material You colors)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            val drawingColors = listOf(
                                "#FF000000", // Black
                                "#FFFFFFFF", // White (Great for Dark canvas)
                                "#FFF44336", // Red
                                "#FF2196F3", // Blue
                                "#FF4CAF50", // Green
                                "#FFFBBC05", // Yellow
                                "#FF9C27B0", // Purple
                                "#FFFF5722"  // Orange
                            )
                            drawingColors.forEach { colorHex ->
                                val color = Color(android.graphics.Color.parseColor(colorHex))
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .border(
                                            width = if (activeColorHex == colorHex) 2.5.dp else 1.dp,
                                            color = if (activeColorHex == colorHex) MaterialTheme.colorScheme.primary else Color.LightGray,
                                            shape = CircleShape
                                        )
                                        .clickable { viewModel.setColor(colorHex) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 6. Sticky Note / Text Editing dialog
    if (showTextDialog && textEditElementId != null) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Edit Content") },
            text = {
                OutlinedTextField(
                    value = textEditText,
                    onValueChange = { textEditText = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateElementText(textEditElementId!!, textEditText)
                        showTextDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 7. EXPORT DIAGRAM OPTIONS DIALOG (PNG vs PDF)
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = {
                Text(
                    "Export Whiteboard",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save and share your sketch in high quality local storage.")
                    
                    Button(
                        onClick = {
                            viewModel.exportBoardAsPng(context, isDarkBackground = (activeColorHex == "#FFFFFFFF"))
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_png_button")
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export as High-Quality PNG")
                    }

                    Button(
                        onClick = {
                            viewModel.exportBoardAsPdf(context, isDarkBackground = (activeColorHex == "#FFFFFFFF"))
                            showExportDialog = false
                        },
                        modifier = Modifier.fillMaxWidth().testTag("export_pdf_button")
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export as PDF Document")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun ToolIcon(
    tool: ToolType,
    icon: ImageVector,
    activeTool: ToolType,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (activeTool == tool) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        ),
        modifier = Modifier.size(44.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = tool.name,
            tint = if (activeTool == tool) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    }
}

// Draw infinite grid helper
fun DrawScope.drawInfiniteGrid(
    size: Size,
    scale: Float,
    pan: Offset,
    isDark: Boolean
) {
    val dotColor = Color(0xFF49454F)
    val gridSize = 60f // Spacing of dot grids (corresponds to 24dp spacing)
    val dotRadius = (2f / scale).coerceIn(1.5f, 4.0f) // Keep dots sharp and visible but tiny

    // Calculate the visible bounds in Canvas coordinate space to only render visible dots
    val left = ((-pan.x) / scale) - gridSize
    val right = ((-pan.x + size.width) / scale) + gridSize
    val top = ((-pan.y) / scale) - gridSize
    val bottom = ((-pan.y + size.height) / scale) + gridSize

    // Align start coordinates to multiples of gridSize
    val startX = (left / gridSize).toInt() * gridSize
    val endX = (right / gridSize).toInt() * gridSize
    val startY = (top / gridSize).toInt() * gridSize
    val endY = (bottom / gridSize).toInt() * gridSize

    var x = startX
    while (x <= endX) {
        var y = startY
        while (y <= endY) {
            drawCircle(
                color = dotColor,
                radius = dotRadius,
                center = Offset(x, y)
            )
            y += gridSize
        }
        x += gridSize
    }
}

// Board Element Rendering on Jetpack Compose Canvas
fun DrawScope.drawBoardElement(el: BoardElement) {
    when (el.type) {
        ElementType.PATH -> {
            val pts = el.points ?: return
            if (pts.size < 2) return
            
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    lineTo(pts[i].x, pts[i].y)
                }
            }
            
            val pathColor = Color(android.graphics.Color.parseColor(el.colorHex))
            val alpha = when (el.penType) {
                PenType.HIGHLIGHTER -> 0.45f
                PenType.PENCIL -> 0.7f
                else -> 1.0f
            }

            drawPath(
                path = path,
                color = pathColor.copy(alpha = alpha),
                style = Stroke(
                    width = el.strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
        ElementType.SHAPE -> {
            val pathColor = Color(android.graphics.Color.parseColor(el.colorHex))
            val style = if (el.isFilled) androidx.compose.ui.graphics.drawscope.Fill else Stroke(width = el.strokeWidth)
            
            when (el.shapeType) {
                ShapeType.CIRCLE -> {
                    drawOval(
                        color = pathColor,
                        topLeft = Offset(el.x, el.y),
                        size = Size(el.width, el.height),
                        style = style
                    )
                }
                ShapeType.RECTANGLE -> {
                    drawRect(
                        color = pathColor,
                        topLeft = Offset(el.x, el.y),
                        size = Size(el.width, el.height),
                        style = style
                    )
                }
                ShapeType.LINE -> {
                    drawLine(
                        color = pathColor,
                        start = Offset(el.x, el.y),
                        end = Offset(el.x + el.width, el.y + el.height),
                        strokeWidth = el.strokeWidth,
                        cap = StrokeCap.Round
                    )
                }
                ShapeType.ARROW -> {
                    val sx = el.x
                    val sy = el.y
                    val ex = el.x + el.width
                    val ey = el.y + el.height
                    
                    drawLine(
                        color = pathColor,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = el.strokeWidth,
                        cap = StrokeCap.Round
                    )
                    
                    // Draw arrowhead manually
                    val angle = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                    val arrowLength = 24f
                    val arrowAngle = Math.PI / 6
                    
                    val ap1 = Offset(
                        (ex - arrowLength * cos(angle - arrowAngle)).toFloat(),
                        (ey - arrowLength * sin(angle - arrowAngle)).toFloat()
                    )
                    val ap2 = Offset(
                        (ex - arrowLength * cos(angle + arrowAngle)).toFloat(),
                        (ey - arrowLength * sin(angle + arrowAngle)).toFloat()
                    )
                    
                    val arrowPath = Path().apply {
                        moveTo(ex, ey)
                        lineTo(ap1.x, ap1.y)
                        lineTo(ap2.x, ap2.y)
                        close()
                    }
                    
                    drawPath(
                        path = arrowPath,
                        color = pathColor,
                        style = androidx.compose.ui.graphics.drawscope.Fill
                    )
                }
                null -> {}
            }
        }
        ElementType.TEXT, ElementType.STICKY_NOTE -> {
            // Drawn using Native Compose Layout wrappers around the canvas, or simple Text.
            // For Canvas rendering, we draw the sticky note card.
            if (el.type == ElementType.STICKY_NOTE) {
                val noteColor = Color(android.graphics.Color.parseColor(el.colorHex))
                drawRoundRect(
                    color = noteColor,
                    topLeft = Offset(el.x, el.y),
                    size = Size(el.width, el.height),
                    cornerRadius = CornerRadius(16f, 16f)
                )
                
                // Draw soft border
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.15f),
                    topLeft = Offset(el.x, el.y),
                    size = Size(el.width, el.height),
                    cornerRadius = CornerRadius(16f, 16f),
                    style = Stroke(width = 2f)
                )
            }
            
            // To support fully functional and crisp multiline Text rendering inside drawing canvases
            // we render it beautifully overlayed, or draw text path representation. To keep the app robust,
            // we overlay text views on the canvas dynamically. In standard drawing, we draw text using DrawScope.
            // But since native drawString is complex, we handle sticky notes and text as Composables overlayed
            // inside the infinite canvas box! This is extremely smooth and guarantees perfect typing.
            // So we don't need to draw text manually inside the DrawScope! We will render them as Composable overlays.
        }
        ElementType.IMAGE -> {
            // Handled as overlay
        }
    }
}
