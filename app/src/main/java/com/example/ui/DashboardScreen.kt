package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.BoardEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: WhiteboardViewModel,
    onOpenBoard: (Long) -> Unit
) {
    val context = LocalContext.current
    val boards by viewModel.allBoards.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    
    var currentTab by remember { mutableStateOf(0) } // 0: Boards, 1: About Dev
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Filtered boards list
    val filteredBoards = boards.filter { board ->
        val folderMatch = selectedFolder == "All Boards" || board.folder == selectedFolder
        val queryMatch = board.name.contains(searchQuery, ignoreCase = true)
        folderMatch && queryMatch
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Gesture,
                            contentDescription = "Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Boardly",
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                actions = {
                    if (currentTab == 0) {
                        IconButton(
                            onClick = { viewModel.generateRandomPalette() },
                            modifier = Modifier.testTag("color_palette_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Palette,
                                contentDescription = "Palette Gen",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Boards") },
                    label = { Text("Whiteboards") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.Info, contentDescription = "About Developer") },
                    label = { Text("Developer") }
                )
            }
        },
        floatingActionButton = {
            if (currentTab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showCreateDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = "New Board") },
                    text = { Text("Create Board") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("create_board_fab")
                )
            }
        }
    ) { innerPadding ->
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
            },
            modifier = Modifier.padding(innerPadding),
            label = "tab_navigation"
        ) { tabIndex ->
            when (tabIndex) {
                0 -> {
                    // Boards Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        // Search and Folder selectors
                        SearchBarSection(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it }
                        )

                        FolderFilterChips(
                            folders = viewModel.folders,
                            selectedFolder = selectedFolder,
                            onFolderSelect = { viewModel.selectFolder(it) }
                        )

                        if (filteredBoards.isEmpty()) {
                            EmptyStatePlaceholder(searchQuery.isNotEmpty(), selectedFolder)
                        } else {
                            BoardsGrid(
                                boards = filteredBoards,
                                onOpen = onOpenBoard,
                                onDelete = { viewModel.deleteBoard(it) }
                            )
                        }
                    }
                }
                1 -> {
                    // About Tab
                    AboutDeveloperSection(context)
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateBoardDialog(
            folders = viewModel.folders.filter { it != "All Boards" },
            onDismiss = { showCreateDialog = false },
            onCreate = { name, folder, template ->
                viewModel.createBoard(name, folder, template)
                showCreateDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBarSection(
    query: String,
    onQueryChange: (String) -> Unit
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search whiteboards...", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "SearchIcon") },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("search_bar")
    )
}

@Composable
fun FolderFilterChips(
    folders: List<String>,
    selectedFolder: String,
    onFolderSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        folders.take(3).forEach { folder ->
            val isSelected = selectedFolder == folder
            FilterChip(
                selected = isSelected,
                onClick = { onFolderSelect(folder) },
                label = { Text(folder) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier.testTag("folder_chip_$folder")
            )
        }
        
        // Overflow or extra items can be shown in a simplified drop-down if needed, but showing first 4 is extremely clean.
        if (folders.size > 3) {
            val remaining = folders.drop(3)
            var showMenu by remember { mutableStateOf(false) }
            val currentSelectedExtra = remaining.find { it == selectedFolder }
            
            Box {
                FilterChip(
                    selected = currentSelectedExtra != null,
                    onClick = { showMenu = true },
                    label = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(currentSelectedExtra ?: "More")
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "More", Modifier.size(16.dp))
                    } },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    remaining.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item) },
                            onClick = {
                                onFolderSelect(item)
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BoardsGrid(
    boards: List<BoardEntity>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(boards, key = { it.id }) { board ->
            BoardCardItem(board, onOpen, onDelete)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardCardItem(
    board: BoardEntity,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    val dateString = remember(board.updatedAt) {
        val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        formatter.format(Date(board.updatedAt))
    }

    var showConfirmDelete by remember { mutableStateOf(false) }

    Card(
        onClick = { onOpen(board.id) },
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Color(0xFF49454F)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("board_card_${board.id}")
    ) {
        Column {
            // Visual board header / mini representation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.linearGradient(
                            colors = when (board.folder) {
                                "Study Notes" -> listOf(Color(0xFF4285F4), Color(0xFF1A73E8))
                                "Mind Maps" -> listOf(Color(0xFF9C27B0), Color(0xFFE91E63))
                                "Planning" -> listOf(Color(0xFF34A853), Color(0xFF4CAF50))
                                "Lectures" -> listOf(Color(0xFFFBBC05), Color(0xFFFF9800))
                                else -> listOf(Color(0xFF607D8B), Color(0xFF455A64))
                            }
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (board.folder) {
                        "Study Notes" -> Icons.Default.School
                        "Mind Maps" -> Icons.Default.Hub
                        "Planning" -> Icons.Default.ViewWeek
                        "Lectures" -> Icons.Default.MenuBook
                        else -> Icons.Default.Palette
                    },
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(48.dp)
                )
                
                if (board.isLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Locked",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(18.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = board.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = board.folder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = dateString,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = { showConfirmDelete = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete board",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete Board?") },
            text = { Text("Are you sure you want to delete '${board.name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(board.id)
                        showConfirmDelete = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EmptyStatePlaceholder(
    isSearching: Boolean,
    selectedFolder: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isSearching) Icons.Default.SearchOff else Icons.Default.EditNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier.size(80.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (isSearching) "No search results" else "Your Canvas is Clean",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isSearching) {
                "We couldn't find any whiteboards matching your search query. Try another keyword!"
            } else {
                "Create a new whiteboard or select a quick template from folder '$selectedFolder' to sketch your ideas instantly."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateBoardDialog(
    folders: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf(folders.firstOrNull() ?: "General") }
    var selectedTemplate by remember { mutableStateOf<String?>(null) } // null = Blank

    var folderExpanded by remember { mutableStateOf(false) }

    val templates = listOf(
        "Study Notes" to Icons.Default.School,
        "Mind Map" to Icons.Default.Hub,
        "Project Planning" to Icons.Default.ViewWeek,
        "Lecture Notes" to Icons.Default.MenuBook
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Create Board",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Board Title") },
                    placeholder = { Text("Enter title...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("board_name_input")
                )

                // Folder Picker
                Box(modifier = Modifier.fillMaxWidth()) {
                    ExposedDropdownMenuBox(
                        expanded = folderExpanded,
                        onExpandedChange = { folderExpanded = !folderExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedFolder,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Folder Category") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = folderExpanded,
                            onDismissRequest = { folderExpanded = false }
                        ) {
                            folders.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f) },
                                    onClick = {
                                        selectedFolder = f
                                        folderExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Templates Selector Header
                Text(
                    "Optional Quick Templates:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(
                        onClick = { selectedTemplate = null },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedTemplate == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(70.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(Icons.Default.BorderOuter, contentDescription = "Blank")
                            Text("Blank", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    templates.take(3).forEach { (tName, tIcon) ->
                        Card(
                            onClick = { selectedTemplate = tName },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedTemplate == tName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(70.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(tIcon, contentDescription = tName)
                                Text(tName, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, selectedFolder, selectedTemplate) },
                modifier = Modifier.testTag("confirm_create_board_button")
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
fun AboutDeveloperSection(context: Context) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("about_developer_section"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            // Profile Card Header
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.sweepGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary,
                                        MaterialTheme.colorScheme.tertiary,
                                        MaterialTheme.colorScheme.primary
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "PR",
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        "Prince AR Abdur Rahman",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Independent App Developer",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        "NexVora Lab's Ofc",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        item {
            // Bio Section
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "About Developer",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Prince AR Abdur Rahman is a passionate Independent App Developer focused on crafting high-performance, modern Android productivity tools, media players, educational utilities, and privacy-first digital experiences.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            // Company Mission
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "NexVora Lab's Ofc",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Our Mission: Build fast, beautiful, privacy-friendly, and user-focused digital applications accessible to everyone, everywhere.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            // Contact Buttons
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Get in Touch",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    ContactRow(
                        icon = Icons.Default.Phone,
                        title = "WhatsApp Contact 1",
                        detail = "01707424006",
                        onClick = {
                            openUrl(context, "https://api.whatsapp.com/send?phone=8801707424006")
                        }
                    )
                    ContactRow(
                        icon = Icons.Default.Phone,
                        title = "WhatsApp Contact 2",
                        detail = "01796951709",
                        onClick = {
                            openUrl(context, "https://api.whatsapp.com/send?phone=8801796951709")
                        }
                    )
                    ContactRow(
                        icon = Icons.Default.Link,
                        title = "Facebook Profile",
                        detail = "facebook.com/prince.abdur.rahman",
                        onClick = {
                            openUrl(context, "https://www.facebook.com/share/1BNn32qoJo/")
                        }
                    )
                    ContactRow(
                        icon = Icons.Default.CameraAlt,
                        title = "Instagram Profile",
                        detail = "@ur___abdur____rahman__2008",
                        onClick = {
                            openUrl(context, "https://www.instagram.com/ur___abdur____rahman__2008")
                        }
                    )
                }
            }
        }

        item {
            Text(
                "© 2026 NexVora Lab's Ofc. All Rights Reserved.\nVersion 1.0.0 (Offline First)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
fun ContactRow(
    icon: ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ArrowForwardIos, contentDescription = "Go", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(14.dp))
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
