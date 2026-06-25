package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.BoardElement
import com.example.data.BoardJsonSerializer
import com.example.data.BoardPoint
import com.example.data.ElementType
import com.example.data.PenType
import com.example.data.ShapeType
import com.example.data.database.BoardDatabase
import com.example.data.database.BoardEntity
import com.example.data.database.BoardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class ToolType {
    PEN, PENCIL, MARKER, HIGHLIGHTER, ERASER, SELECT, LASER_POINTER
}

class WhiteboardViewModel(
    application: Application,
    private val repository: BoardRepository
) : AndroidViewModel(application) {

    val allBoards: StateFlow<List<BoardEntity>> = repository.allBoards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently opened board
    private val _currentBoard = MutableStateFlow<BoardEntity?>(null)
    val currentBoard: StateFlow<BoardEntity?> = _currentBoard.asStateFlow()

    // Canvas elements state
    private val _elements = MutableStateFlow<List<BoardElement>>(emptyList())
    val elements: StateFlow<List<BoardElement>> = _elements.asStateFlow()

    // Undo / Redo stacks
    private val undoStack = mutableListOf<List<BoardElement>>()
    private val redoStack = mutableListOf<List<BoardElement>>()

    // Selected element
    private val _selectedElementId = MutableStateFlow<String?>(null)
    val selectedElementId: StateFlow<String?> = _selectedElementId.asStateFlow()

    // Active tools & configs
    private val _activeTool = MutableStateFlow(ToolType.PEN)
    val activeTool: StateFlow<ToolType> = _activeTool.asStateFlow()

    private val _activeColorHex = MutableStateFlow("#FF000000") // Default black
    val activeColorHex: StateFlow<String> = _activeColorHex.asStateFlow()

    private val _strokeWidth = MutableStateFlow(6f)
    val strokeWidth: StateFlow<Float> = _strokeWidth.asStateFlow()

    private val _snapToGrid = MutableStateFlow(false)
    val snapToGrid: StateFlow<Boolean> = _snapToGrid.asStateFlow()

    private val _autoShapeCorrect = MutableStateFlow(false)
    val autoShapeCorrect: StateFlow<Boolean> = _autoShapeCorrect.asStateFlow()

    // Presentation mode & laser pointer
    private val _presentationMode = MutableStateFlow(false)
    val presentationMode: StateFlow<Boolean> = _presentationMode.asStateFlow()

    // Laser pointer path is temporary and animated
    private val _laserPoints = MutableStateFlow<List<BoardPoint>>(emptyList())
    val laserPoints: StateFlow<List<BoardPoint>> = _laserPoints.asStateFlow()

    // Folders
    val folders = listOf("All Boards", "Study Notes", "Mind Maps", "Planning", "Lectures")
    private val _selectedFolder = MutableStateFlow("All Boards")
    val selectedFolder: StateFlow<String> = _selectedFolder.asStateFlow()

    // Status / Messages for UI
    private val _statusMessage = MutableSharedFlow<String>()
    val statusMessage: SharedFlow<String> = _statusMessage.asSharedFlow()

    fun selectFolder(folder: String) {
        _selectedFolder.value = folder
    }

    // Boards CRUD
    fun createBoard(name: String, folder: String = "All Boards", templateType: String? = null) {
        viewModelScope.launch {
            val templateElements = if (templateType != null) {
                createTemplateElements(templateType)
            } else {
                emptyList()
            }
            
            val newBoard = BoardEntity(
                name = name.ifBlank { "Untitled Board" },
                folder = folder,
                contentJson = BoardJsonSerializer.toJson(templateElements)
            )
            repository.insertBoard(newBoard)
            _statusMessage.emit("Board '$name' created successfully!")
        }
    }

    fun openBoard(boardId: Long) {
        viewModelScope.launch {
            val board = repository.getBoardById(boardId)
            if (board != null) {
                _currentBoard.value = board
                val els = BoardJsonSerializer.fromJson(board.contentJson)
                _elements.value = els
                _selectedElementId.value = null
                undoStack.clear()
                redoStack.clear()
            }
        }
    }

    fun closeBoard() {
        viewModelScope.launch {
            saveCurrentBoardState()
            _currentBoard.value = null
            _elements.value = emptyList()
            _selectedElementId.value = null
            undoStack.clear()
            redoStack.clear()
        }
    }

    fun updateBoardMetadata(boardId: Long, name: String, folder: String) {
        viewModelScope.launch {
            repository.updateBoardMetadata(boardId, name, folder)
            // Refresh if currently opened
            val current = _currentBoard.value
            if (current != null && current.id == boardId) {
                _currentBoard.value = current.copy(name = name, folder = folder)
            }
        }
    }

    fun toggleBoardLock() {
        val current = _currentBoard.value ?: return
        val newLockState = !current.isLocked
        viewModelScope.launch {
            repository.updateBoardLockState(current.id, newLockState)
            _currentBoard.value = current.copy(isLocked = newLockState)
            _statusMessage.emit(if (newLockState) "Whiteboard locked" else "Whiteboard unlocked")
        }
    }

    fun deleteBoard(boardId: Long) {
        viewModelScope.launch {
            repository.deleteBoardById(boardId)
            _statusMessage.emit("Board deleted")
        }
    }

    // Infinite Canvas Operations
    fun saveCurrentBoardState() {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return
        val json = BoardJsonSerializer.toJson(_elements.value)
        viewModelScope.launch {
            repository.updateBoardContent(board.id, json)
        }
    }

    // Element Manipulation (drawing paths, shapes, texts, notes, etc.)
    private fun pushUndoState() {
        // Deep copy elements list
        undoStack.add(ArrayList(_elements.value))
        redoStack.clear()
        if (undoStack.size > 50) {
            undoStack.removeAt(0) // Limit undo size to 50
        }
    }

    fun undo() {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return
        if (undoStack.isNotEmpty()) {
            val prevState = undoStack.removeAt(undoStack.size - 1)
            redoStack.add(ArrayList(_elements.value))
            _elements.value = prevState
            _selectedElementId.value = null
            saveCurrentBoardState()
        }
    }

    fun redo() {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeAt(redoStack.size - 1)
            undoStack.add(ArrayList(_elements.value))
            _elements.value = nextState
            _selectedElementId.value = null
            saveCurrentBoardState()
        }
    }

    // Tool setters
    fun setTool(tool: ToolType) {
        _activeTool.value = tool
        _selectedElementId.value = null // clear selection when changing tool
        // Adjust default stroke widths based on tool
        when (tool) {
            ToolType.PEN -> _strokeWidth.value = 6f
            ToolType.PENCIL -> _strokeWidth.value = 3f
            ToolType.MARKER -> _strokeWidth.value = 14f
            ToolType.HIGHLIGHTER -> _strokeWidth.value = 28f
            else -> {}
        }
    }

    fun setColor(hex: String) {
        _activeColorHex.value = hex
        // If an element is selected, change its color
        val selId = _selectedElementId.value
        if (selId != null) {
            pushUndoState()
            _elements.value = _elements.value.map {
                if (it.id == selId) it.copy(colorHex = hex) else it
            }
            saveCurrentBoardState()
        }
    }

    fun setStrokeWidth(width: Float) {
        _strokeWidth.value = width
        // If an element is selected, change its stroke width
        val selId = _selectedElementId.value
        if (selId != null) {
            pushUndoState()
            _elements.value = _elements.value.map {
                if (it.id == selId) it.copy(strokeWidth = width) else it
            }
            saveCurrentBoardState()
        }
    }

    fun toggleSnapToGrid() {
        _snapToGrid.value = !_snapToGrid.value
    }

    fun toggleAutoShapeCorrect() {
        _autoShapeCorrect.value = !_autoShapeCorrect.value
    }

    fun togglePresentationMode() {
        _presentationMode.value = !_presentationMode.value
        if (_presentationMode.value) {
            _selectedElementId.value = null
        }
    }

    // Dynamic Color Palette Generator
    fun generateRandomPalette() {
        // Aesthetic color arrays (pastels + solid whiteboard friendly colors)
        val colors = listOf(
            "#FF1A73E8", "#FF34A853", "#FFFBBC05", "#FFE91E63", 
            "#FF9C27B0", "#FF009688", "#FF00AAFF", "#FF4CAF50",
            "#FFF44336", "#FF000000", "#FFFF5722", "#FF795548"
        )
        val shuffled = colors.shuffled()
        // We set the active color to one of them
        _activeColorHex.value = shuffled.first()
        viewModelScope.launch {
            _statusMessage.emit("Harmonious palette synchronized!")
        }
    }

    // Element management
    fun selectElementAt(x: Float, y: Float) {
        // Search from top layer (reverse list)
        val clicked = _elements.value.asReversed().firstOrNull { el ->
            if (el.type == ElementType.PATH) {
                // If it's a path, check if clicked close to any point
                el.points?.any { pt ->
                    Math.hypot((pt.x - x).toDouble(), (pt.y - y).toDouble()) < 30.0
                } == true
            } else {
                // Check bounds
                x >= el.x && x <= el.x + el.width && y >= el.y && y <= el.y + el.height
            }
        }
        _selectedElementId.value = clicked?.id
    }

    fun clearSelection() {
        _selectedElementId.value = null
    }

    fun deleteSelectedElement() {
        val selId = _selectedElementId.value ?: return
        val board = _currentBoard.value ?: return
        if (board.isLocked) return
        
        pushUndoState()
        _elements.value = _elements.value.filter { it.id != selId }
        _selectedElementId.value = null
        saveCurrentBoardState()
    }

    fun addPathElement(points: List<BoardPoint>, penType: PenType, strokeWidth: Float, colorHex: String) {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        
        var addedElement: BoardElement? = null
        
        // Auto Shape Correction Check
        if (_autoShapeCorrect.value) {
            addedElement = detectAndCorrectShape(points)
            if (addedElement != null) {
                // Decorate with active color and thickness
                addedElement = addedElement.copy(
                    colorHex = colorHex,
                    strokeWidth = strokeWidth,
                    layerIndex = getNextLayerIndex()
                )
            }
        }
        
        if (addedElement == null) {
            // Handwriting smoothing / moving average (simple box filter)
            val smoothedPoints = if (points.size > 3) {
                val smoothed = mutableListOf<BoardPoint>()
                smoothed.add(points.first())
                for (i in 1 until points.size - 1) {
                    val pPrev = points[i - 1]
                    val pCurr = points[i]
                    val pNext = points[i + 1]
                    smoothed.add(BoardPoint(
                        (pPrev.x + pCurr.x + pNext.x) / 3f,
                        (pPrev.y + pCurr.y + pNext.y) / 3f
                    ))
                }
                smoothed.add(points.last())
                smoothed
            } else {
                points
            }
            
            addedElement = BoardElement(
                id = UUID.randomUUID().toString(),
                type = ElementType.PATH,
                points = smoothedPoints,
                penType = penType,
                strokeWidth = strokeWidth,
                colorHex = colorHex,
                layerIndex = getNextLayerIndex()
            )
        }

        _elements.value = _elements.value + addedElement
        saveCurrentBoardState()
    }

    fun addShapeElement(shapeType: ShapeType, x: Float, y: Float, width: Float, height: Float, isFilled: Boolean = false) {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        
        var finalX = x
        var finalY = y
        var finalW = width
        var finalH = height
        
        if (_snapToGrid.value) {
            finalX = snapValue(finalX)
            finalY = snapValue(finalY)
            finalW = snapValue(finalW)
            finalH = snapValue(finalH)
        }

        val el = BoardElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.SHAPE,
            shapeType = shapeType,
            x = finalX,
            y = finalY,
            width = finalW.coerceAtLeast(30f),
            height = finalH.coerceAtLeast(30f),
            colorHex = _activeColorHex.value,
            strokeWidth = _strokeWidth.value,
            isFilled = isFilled,
            layerIndex = getNextLayerIndex()
        )
        _elements.value = _elements.value + el
        saveCurrentBoardState()
    }

    fun addStickyNote(text: String, x: Float, y: Float, colorHex: String = "#FFFFF9C4") {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        
        var finalX = x
        var finalY = y
        if (_snapToGrid.value) {
            finalX = snapValue(finalX)
            finalY = snapValue(finalY)
        }

        val el = BoardElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.STICKY_NOTE,
            x = finalX,
            y = finalY,
            width = 250f,
            height = 250f,
            text = text,
            colorHex = colorHex, // sticky note background
            layerIndex = getNextLayerIndex()
        )
        _elements.value = _elements.value + el
        saveCurrentBoardState()
    }

    fun addTextElement(text: String, x: Float, y: Float) {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        
        var finalX = x
        var finalY = y
        if (_snapToGrid.value) {
            finalX = snapValue(finalX)
            finalY = snapValue(finalY)
        }

        val el = BoardElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.TEXT,
            x = finalX,
            y = finalY,
            width = 300f,
            height = 100f,
            text = text,
            colorHex = _activeColorHex.value,
            layerIndex = getNextLayerIndex()
        )
        _elements.value = _elements.value + el
        saveCurrentBoardState()
    }

    fun addImageElement(uri: String, x: Float, y: Float) {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        
        var finalX = x
        var finalY = y
        if (_snapToGrid.value) {
            finalX = snapValue(finalX)
            finalY = snapValue(finalY)
        }

        val el = BoardElement(
            id = UUID.randomUUID().toString(),
            type = ElementType.IMAGE,
            x = finalX,
            y = finalY,
            width = 300f,
            height = 300f,
            imageUri = uri,
            layerIndex = getNextLayerIndex()
        )
        _elements.value = _elements.value + el
        saveCurrentBoardState()
    }

    fun updateElementPositionAndSize(id: String, x: Float, y: Float, width: Float, height: Float) {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        var finalX = x
        var finalY = y
        var finalW = width
        var finalH = height
        
        if (_snapToGrid.value) {
            finalX = snapValue(finalX)
            finalY = snapValue(finalY)
            finalW = snapValue(finalW)
            finalH = snapValue(finalH)
        }

        _elements.value = _elements.value.map {
            if (it.id == id) {
                it.copy(x = finalX, y = finalY, width = finalW, height = finalH)
            } else {
                it
            }
        }
    }

    fun updateElementText(id: String, text: String) {
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        _elements.value = _elements.value.map {
            if (it.id == id) it.copy(text = text) else it
        }
        saveCurrentBoardState()
    }

    // Laser pointer path tracking
    fun setLaserPoints(points: List<BoardPoint>) {
        _laserPoints.value = points
    }

    fun clearLaserPoints() {
        _laserPoints.value = emptyList()
    }

    // Layer orders (bring forward / send backward)
    fun bringSelectedToFront() {
        val selId = _selectedElementId.value ?: return
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        val nextIndex = getNextLayerIndex()
        _elements.value = _elements.value.map {
            if (it.id == selId) it.copy(layerIndex = nextIndex) else it
        }
        saveCurrentBoardState()
    }

    fun sendSelectedToBack() {
        val selId = _selectedElementId.value ?: return
        val board = _currentBoard.value ?: return
        if (board.isLocked) return

        pushUndoState()
        val minIndex = getMinLayerIndex() - 1
        _elements.value = _elements.value.map {
            if (it.id == selId) it.copy(layerIndex = minIndex) else it
        }
        saveCurrentBoardState()
    }

    // Helper functions
    private fun getNextLayerIndex(): Int {
        return (_elements.value.maxOfOrNull { it.layerIndex } ?: 0) + 1
    }

    private fun getMinLayerIndex(): Int {
        return _elements.value.minOfOrNull { it.layerIndex } ?: 0
    }

    private fun snapValue(value: Float, gridSize: Float = 40f): Float {
        return Math.round(value / gridSize) * gridSize
    }

    // Shape correction algorithm helper
    private fun detectAndCorrectShape(points: List<BoardPoint>): BoardElement? {
        if (points.size < 8) return null
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        
        val width = maxX - minX
        val height = maxY - minY
        val size = Math.max(width, height)
        if (size < 40f) return null
        
        val start = points.first()
        val end = points.last()
        
        // 1. Is it a line?
        var pathLength = 0f
        for (i in 0 until points.size - 1) {
            pathLength += Math.hypot((points[i+1].x - points[i].x).toDouble(), (points[i+1].y - points[i].y).toDouble()).toFloat()
        }
        val straightLength = Math.hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
        
        if (straightLength > 0.88f * pathLength) {
            return BoardElement(
                id = UUID.randomUUID().toString(),
                type = ElementType.SHAPE,
                shapeType = ShapeType.LINE,
                x = start.x,
                y = start.y,
                width = end.x - start.x,
                height = end.y - start.y
            )
        }
        
        // 2. Is it closed or nearly closed?
        val closedDistance = Math.hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
        val isNearlyClosed = closedDistance < 0.35f * size
        
        if (isNearlyClosed) {
            val centerX = (minX + maxX) / 2f
            val centerY = (minY + maxY) / 2f
            val radiusX = width / 2f
            val radiusY = height / 2f
            val avgRadius = (radiusX + radiusY) / 2f
            
            var circleVariance = 0f
            var rectVariance = 0f
            
            for (p in points) {
                val distToCenter = Math.hypot((p.x - centerX).toDouble(), (p.y - centerY).toDouble()).toFloat()
                circleVariance += Math.abs(distToCenter - avgRadius)
                
                val dx = Math.min(Math.abs(p.x - minX), Math.abs(p.x - maxX))
                val dy = Math.min(Math.abs(p.y - minY), Math.abs(p.y - maxY))
                rectVariance += Math.min(dx, dy)
            }
            
            circleVariance /= points.size
            rectVariance /= points.size
            
            if (circleVariance < rectVariance && circleVariance < 0.22f * avgRadius) {
                return BoardElement(
                    id = UUID.randomUUID().toString(),
                    type = ElementType.SHAPE,
                    shapeType = ShapeType.CIRCLE,
                    x = minX,
                    y = minY,
                    width = width,
                    height = height
                )
            } else if (rectVariance < 0.22f * size) {
                return BoardElement(
                    id = UUID.randomUUID().toString(),
                    type = ElementType.SHAPE,
                    shapeType = ShapeType.RECTANGLE,
                    x = minX,
                    y = minY,
                    width = width,
                    height = height
                )
            }
        }
        
        return null
    }

    private fun createTemplateElements(templateType: String): List<BoardElement> {
        val list = mutableListOf<BoardElement>()
        when (templateType) {
            "Study Notes" -> {
                list.add(BoardElement(
                    id = "title", type = ElementType.TEXT,
                    x = 100f, y = -120f, width = 600f, height = 70f,
                    text = "📚 Study Notes: Topic Title", colorHex = "#FF1A73E8"
                ))
                list.add(BoardElement(
                    id = "col1", type = ElementType.SHAPE, shapeType = ShapeType.RECTANGLE,
                    x = 100f, y = 0f, width = 350f, height = 450f,
                    colorHex = "#FF4285F4", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "col1_title", type = ElementType.TEXT,
                    x = 120f, y = 20f, width = 310f, height = 50f,
                    text = "🔑 Key Definitions", colorHex = "#FF4285F4"
                ))
                list.add(BoardElement(
                    id = "sticky_def", type = ElementType.STICKY_NOTE,
                    x = 130f, y = 80f, width = 290f, height = 150f,
                    text = "Write critical terms here...", colorHex = "#FFFFE082"
                ))
                list.add(BoardElement(
                    id = "col2", type = ElementType.SHAPE, shapeType = ShapeType.RECTANGLE,
                    x = 550f, y = 0f, width = 350f, height = 450f,
                    colorHex = "#FF34A853", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "col2_title", type = ElementType.TEXT,
                    x = 570f, y = 20f, width = 310f, height = 50f,
                    text = "💡 Practical Examples", colorHex = "#FF34A853"
                ))
                list.add(BoardElement(
                    id = "sticky_ex", type = ElementType.STICKY_NOTE,
                    x = 580f, y = 80f, width = 290f, height = 150f,
                    text = "Add an example or formulas...", colorHex = "#FFE1BEE7"
                ))
                list.add(BoardElement(
                    id = "arrow1", type = ElementType.SHAPE, shapeType = ShapeType.ARROW,
                    x = 465f, y = 180f, width = 70f, height = 0f,
                    colorHex = "#FFFBBC05", strokeWidth = 5f
                ))
            }
            "Mind Map" -> {
                list.add(BoardElement(
                    id = "center", type = ElementType.SHAPE, shapeType = ShapeType.CIRCLE,
                    x = 350f, y = 150f, width = 200f, height = 120f,
                    colorHex = "#FF9C27B0", strokeWidth = 5f
                ))
                list.add(BoardElement(
                    id = "center_text", type = ElementType.TEXT,
                    x = 370f, y = 190f, width = 160f, height = 50f,
                    text = "🧠 Central Idea", colorHex = "#FF9C27B0"
                ))
                list.add(BoardElement(
                    id = "branch1_arrow", type = ElementType.SHAPE, shapeType = ShapeType.ARROW,
                    x = 340f, y = 160f, width = -120f, height = -60f,
                    colorHex = "#FF3F51B5", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "node1", type = ElementType.SHAPE, shapeType = ShapeType.CIRCLE,
                    x = 80f, y = 30f, width = 140f, height = 80f,
                    colorHex = "#FF3F51B5", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "node1_text", type = ElementType.TEXT,
                    x = 90f, y = 50f, width = 120f, height = 40f,
                    text = "Concept A", colorHex = "#FF3F51B5"
                ))
                list.add(BoardElement(
                    id = "branch2_arrow", type = ElementType.SHAPE, shapeType = ShapeType.ARROW,
                    x = 560f, y = 160f, width = 120f, height = -60f,
                    colorHex = "#FF009688", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "node2", type = ElementType.SHAPE, shapeType = ShapeType.CIRCLE,
                    x = 680f, y = 30f, width = 140f, height = 80f,
                    colorHex = "#FF009688", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "node2_text", type = ElementType.TEXT,
                    x = 690f, y = 50f, width = 120f, height = 40f,
                    text = "Concept B", colorHex = "#FF009688"
                ))
            }
            "Project Planning" -> {
                list.add(BoardElement(
                    id = "title", type = ElementType.TEXT,
                    x = 50f, y = -100f, width = 600f, height = 60f,
                    text = "📋 Project Planning Board", colorHex = "#FF1A73E8"
                ))
                list.add(BoardElement(
                    id = "todo_col", type = ElementType.SHAPE, shapeType = ShapeType.RECTANGLE,
                    x = 50f, y = 0f, width = 280f, height = 450f,
                    colorHex = "#FFF44336", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "todo_title", type = ElementType.TEXT,
                    x = 70f, y = 20f, width = 240f, height = 40f,
                    text = "📌 To Do", colorHex = "#FFF44336"
                ))
                list.add(BoardElement(
                    id = "todo_note1", type = ElementType.STICKY_NOTE,
                    x = 70f, y = 70f, width = 240f, height = 100f,
                    text = "Draft core requirements", colorHex = "#FFFFCDD2"
                ))
                list.add(BoardElement(
                    id = "progress_col", type = ElementType.SHAPE, shapeType = ShapeType.RECTANGLE,
                    x = 360f, y = 0f, width = 280f, height = 450f,
                    colorHex = "#FF2196F3", strokeWidth = 4f
                ))
                list.add(BoardElement(
                    id = "progress_title", type = ElementType.TEXT,
                    x = 380f, y = 20f, width = 240f, height = 40f,
                    text = "⚡ In Progress", colorHex = "#FF2196F3"
                ))
                list.add(BoardElement(
                    id = "progress_note1", type = ElementType.STICKY_NOTE,
                    x = 380f, y = 70f, width = 240f, height = 100f,
                    text = "Build Boardly whiteboard UI", colorHex = "#FFBBDEFB"
                ))
            }
            "Lecture Notes" -> {
                list.add(BoardElement(
                    id = "title", type = ElementType.TEXT,
                    x = 50f, y = -120f, width = 700f, height = 60f,
                    text = "📚 Lecture Notes - Boardly Sketch", colorHex = "#FFFF9800"
                ))
                list.add(BoardElement(
                    id = "main_section", type = ElementType.SHAPE, shapeType = ShapeType.RECTANGLE,
                    x = 50f, y = 0f, width = 550f, height = 500f,
                    colorHex = "#FF9E9E9E", strokeWidth = 3f
                ))
                list.add(BoardElement(
                    id = "summary_section", type = ElementType.SHAPE, shapeType = ShapeType.RECTANGLE,
                    x = 620f, y = 0f, width = 330f, height = 500f,
                    colorHex = "#FFFF9800", strokeWidth = 3f
                ))
                list.add(BoardElement(
                    id = "sum_sticky1", type = ElementType.STICKY_NOTE,
                    x = 640f, y = 80f, width = 290f, height = 120f,
                    text = "Add main learning takeaways here...", colorHex = "#FFFFE082"
                ))
            }
        }
        return list
    }

    // Export Board as High-Quality PNG (saved to Internal storage options with Share option)
    fun exportBoardAsPng(context: Context, isDarkBackground: Boolean) {
        viewModelScope.launch {
            val elementsList = _elements.value
            val boardName = _currentBoard.value?.name ?: "Board"
            val sanitizedBoardName = boardName.replace("\\s+".toRegex(), "_")
            
            withContext(Dispatchers.IO) {
                try {
                    val backgroundColor = if (isDarkBackground) {
                        android.graphics.Color.parseColor("#FF121212")
                    } else {
                        android.graphics.Color.parseColor("#FFFFFFFF")
                    }
                    
                    val bitmap = renderToBitmap(elementsList, backgroundColor)
                    
                    // Save to local cache directory / internal storage
                    val exportDir = File(context.cacheDir, "exports")
                    if (!exportDir.exists()) exportDir.mkdirs()
                    
                    val file = File(exportDir, "Boardly_${sanitizedBoardName}_${System.currentTimeMillis()}.png")
                    val out = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.flush()
                    out.close()
                    bitmap.recycle()
                    
                    // Share/View via FileProvider
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    triggerShareIntent(context, uri, "image/png", "Share Whiteboard Image")
                    _statusMessage.emit("Successfully exported Board as PNG!")
                } catch (e: Exception) {
                    e.printStackTrace()
                    _statusMessage.emit("PNG Export Failed: ${e.localizedMessage}")
                }
            }
        }
    }

    // Export Board as High-Quality PDF (saved to internal storage + share options)
    fun exportBoardAsPdf(context: Context, isDarkBackground: Boolean) {
        viewModelScope.launch {
            val elementsList = _elements.value
            val boardName = _currentBoard.value?.name ?: "Board"
            val sanitizedBoardName = boardName.replace("\\s+".toRegex(), "_")
            
            withContext(Dispatchers.IO) {
                try {
                    val backgroundColor = if (isDarkBackground) {
                        android.graphics.Color.parseColor("#FF121212")
                    } else {
                        android.graphics.Color.parseColor("#FFFFFFFF")
                    }
                    
                    val bitmap = renderToBitmap(elementsList, backgroundColor)
                    
                    val pdfDocument = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
                    val page = pdfDocument.startPage(pageInfo)
                    
                    val canvas = page.canvas
                    val paint = android.graphics.Paint().apply { isFilterBitmap = true }
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                    pdfDocument.finishPage(page)
                    bitmap.recycle()
                    
                    // Save file
                    val exportDir = File(context.cacheDir, "exports")
                    if (!exportDir.exists()) exportDir.mkdirs()
                    val file = File(exportDir, "Boardly_${sanitizedBoardName}_${System.currentTimeMillis()}.pdf")
                    val out = FileOutputStream(file)
                    pdfDocument.writeTo(out)
                    pdfDocument.close()
                    out.flush()
                    out.close()
                    
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    triggerShareIntent(context, uri, "application/pdf", "Share Whiteboard PDF")
                    _statusMessage.emit("Successfully exported Board as PDF!")
                } catch (e: Exception) {
                    e.printStackTrace()
                    _statusMessage.emit("PDF Export Failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun triggerShareIntent(context: Context, uri: Uri, mimeType: String, title: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun renderToBitmap(elements: List<BoardElement>, backgroundColor: Int): Bitmap {
        if (elements.isEmpty()) {
            val bmp = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(backgroundColor)
            return bmp
        }
        
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        
        for (el in elements) {
            if (el.type == ElementType.PATH) {
                el.points?.forEach { pt ->
                    if (pt.x < minX) minX = pt.x
                    if (pt.y < minY) minY = pt.y
                    if (pt.x > maxX) maxX = pt.x
                    if (pt.y > maxY) maxY = pt.y
                }
            } else {
                if (el.x < minX) minX = el.x
                if (el.y < minY) minY = el.y
                if (el.x + el.width > maxX) maxX = el.x + el.width
                if (el.y + el.height > maxY) maxY = el.y + el.height
            }
        }
        
        val margin = 80f
        minX -= margin
        minY -= margin
        maxX += margin
        maxY += margin
        
        val width = (maxX - minX).coerceAtLeast(200f).toInt()
        val height = (maxY - minY).coerceAtLeast(200f).toInt()
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        canvas.translate(-minX, -minY)
        
        val sorted = elements.sortedBy { it.layerIndex }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        
        for (el in sorted) {
            try {
                paint.color = android.graphics.Color.parseColor(el.colorHex)
            } catch (e: Exception) {
                paint.color = android.graphics.Color.BLACK
            }
            paint.strokeWidth = el.strokeWidth
            
            when (el.type) {
                ElementType.PATH -> {
                    val pts = el.points ?: continue
                    if (pts.size < 2) continue
                    paint.style = android.graphics.Paint.Style.STROKE
                    
                    if (el.penType == PenType.HIGHLIGHTER) {
                        paint.alpha = (255 * 0.45f).toInt()
                    } else if (el.penType == PenType.PENCIL) {
                        paint.alpha = (255 * 0.7f).toInt()
                    } else {
                        paint.alpha = 255
                    }
                    
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        path.lineTo(pts[i].x, pts[i].y)
                    }
                    canvas.drawPath(path, paint)
                }
                ElementType.SHAPE -> {
                    paint.style = if (el.isFilled) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
                    paint.alpha = 255
                    when (el.shapeType) {
                        ShapeType.CIRCLE -> {
                            canvas.drawOval(android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + el.height), paint)
                        }
                        ShapeType.RECTANGLE -> {
                            canvas.drawRect(android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + el.height), paint)
                        }
                        ShapeType.LINE -> {
                            canvas.drawLine(el.x, el.y, el.x + el.width, el.y + el.height, paint)
                        }
                        ShapeType.ARROW -> {
                            val sx = el.x
                            val sy = el.y
                            val ex = el.x + el.width
                            val ey = el.y + el.height
                            canvas.drawLine(sx, sy, ex, ey, paint)
                            
                            val angle = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
                            val arrowLength = 24f
                            val arrowAngle = Math.PI / 6
                            val path = android.graphics.Path()
                            path.moveTo(ex, ey)
                            path.lineTo(
                                (ex - arrowLength * Math.cos(angle - arrowAngle)).toFloat(),
                                (ey - arrowLength * Math.sin(angle - arrowAngle)).toFloat()
                            )
                            path.lineTo(
                                (ex - arrowLength * Math.cos(angle + arrowAngle)).toFloat(),
                                (ey - arrowLength * Math.sin(angle + arrowAngle)).toFloat()
                            )
                            path.close()
                            
                            val fillPaint = android.graphics.Paint(paint).apply { style = android.graphics.Paint.Style.FILL }
                            canvas.drawPath(path, fillPaint)
                        }
                        null -> {}
                    }
                }
                ElementType.TEXT, ElementType.STICKY_NOTE -> {
                    paint.alpha = 255
                    if (el.type == ElementType.STICKY_NOTE) {
                        val bgPaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            style = android.graphics.Paint.Style.FILL
                            try {
                                color = android.graphics.Color.parseColor(el.colorHex)
                            } catch (e: Exception) {
                                color = android.graphics.Color.parseColor("#FFFFF9C4")
                            }
                        }
                        val rect = android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + el.height)
                        canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
                        
                        bgPaint.style = android.graphics.Paint.Style.STROKE
                        bgPaint.color = android.graphics.Color.parseColor("#20000000")
                        bgPaint.strokeWidth = 2f
                        canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
                    }
                    
                    val textPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = if (el.type == ElementType.STICKY_NOTE) {
                            android.graphics.Color.BLACK
                        } else {
                            try {
                                android.graphics.Color.parseColor(el.colorHex)
                            } catch (e: Exception) {
                                android.graphics.Color.BLACK
                            }
                        }
                        textSize = 24f
                    }
                    
                    val textStr = el.text ?: ""
                    var tx = el.x + 16f
                    var ty = el.y + 40f
                    val availableW = el.width - 32f
                    
                    if (availableW > 40f) {
                        val words = textStr.split(" ")
                        var currentLine = ""
                        for (w in words) {
                            val testLine = if (currentLine.isEmpty()) w else "$currentLine $w"
                            if (textPaint.measureText(testLine) > availableW) {
                                canvas.drawText(currentLine, tx, ty, textPaint)
                                currentLine = w
                                ty += 30f
                            } else {
                                currentLine = testLine
                            }
                        }
                        if (currentLine.isNotEmpty()) {
                            canvas.drawText(currentLine, tx, ty, textPaint)
                        }
                    } else {
                        canvas.drawText(textStr, tx, ty, textPaint)
                    }
                }
                ElementType.IMAGE -> {
                    val p = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        color = android.graphics.Color.GRAY
                        strokeWidth = 3f
                    }
                    canvas.drawRect(android.graphics.RectF(el.x, el.y, el.x + el.width, el.y + el.height), p)
                    val tp = android.graphics.Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.DKGRAY
                        textSize = 20f
                    }
                    canvas.drawText("[Image Inserted]", el.x + 20f, el.y + el.height / 2f, tp)
                }
            }
        }
        
        return bitmap
    }
}

class WhiteboardViewModelFactory(
    private val application: Application,
    private val repository: BoardRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WhiteboardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WhiteboardViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
