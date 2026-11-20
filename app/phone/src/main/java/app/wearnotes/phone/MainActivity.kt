package app.wearnotes.phone

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.wearnotes.model.Note
import app.wearnotes.model.NoteColor
import app.wearnotes.network.DeviceSession
import app.wearnotes.network.NoteApi
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { WearNotesApp() }
    }
}

private val Paper = Color(0xFFFFF9F2)
private val Parchment = Color(0xFFF4EDE3)
private val Ink = Color(0xFF1B1B1F)
private val Ember = Color(0xFFA83E14)
private val Tide = Color(0xFF146B68)
private val Violet = Color(0xFF6D5B9A)

@Composable
private fun WearNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ember,
            onPrimary = Color.White,
            secondary = Tide,
            onSecondary = Color.White,
            tertiary = Violet,
            background = Paper,
            surface = Paper,
            surfaceVariant = Parchment,
            onSurface = Ink,
            onBackground = Ink,
        ),
        content = content,
    )
}

private enum class Screen { HOME, ARCHIVE, SETTINGS, DETAIL, EDITOR, IMAGE }

@Composable
private fun WearNotesApp() {
    val context = LocalContext.current
    val api = remember { NoteApi() }
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("connection", 0) }
    var session by remember {
        mutableStateOf(
            preferences.getString("baseUrl", null)?.let { baseUrl ->
                preferences.getString("token", null)?.let { token -> DeviceSession(baseUrl, token) }
            },
        )
    }
    if (session == null) {
        WearNotesSetup(
            defaultUrl = "http://10.0.2.2:8081",
            onConnected = { newSession ->
                preferences.edit().putString("baseUrl", newSession.baseUrl).putString("token", newSession.token).apply()
                session = newSession
            },
        )
        return
    }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var searchOpen by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Note?>(null) }
    var notes by remember {
        mutableStateOf(
            listOf(
                Note("1", "Weekend market", "Pick up sourdough, oranges, and the blue flowers.", NoteColor.EMBER, pinned = true, updatedAt = 10),
                Note("2", "Trip ideas", "Lake trail\nLookout at sunset\nPack the small camera", NoteColor.TIDE, photoCount = 2, updatedAt = 8),
                Note("3", "Project outline", "A short list of things to finish before Friday.", NoteColor.VIOLET, updatedAt = 5),
                Note("4", "Books to find", "The Dispossessed\nInvisible Cities", NoteColor.CITRUS, updatedAt = 3),
            ),
        )
    }
    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }
    var draftPhotos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var syncError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(session) {
        runCatching { api.listNotes(session!!) }
            .onSuccess { remote -> notes = remote }
            .onFailure { syncError = it.message }
    }

    WearNotesTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AnimatedContent(targetState = screen, label = "screen") { current ->
                when (current) {
                    Screen.HOME, Screen.ARCHIVE -> HomeScreen(
                        notes = notes.filter { if (current == Screen.ARCHIVE) it.archived else !it.archived && !it.trashed }
                            .filter { search.isBlank() || "${it.title} ${it.body}".contains(search, ignoreCase = true) },
                        archived = current == Screen.ARCHIVE,
                        searchOpen = searchOpen,
                        search = search,
                        onSearchChange = { search = it },
                        onSearchToggle = { searchOpen = !searchOpen },
                        onSelect = { selected = it; screen = Screen.DETAIL },
                        onNew = { draftTitle = ""; draftBody = ""; draftPhotos = emptyList(); screen = Screen.EDITOR },
                        onArchive = { note ->
                            val archived = note.copy(archived = true)
                            notes = notes.map { if (it.id == note.id) archived else it }
                            scope.launch { runCatching { api.updateNote(session!!, archived) }.onFailure { syncError = it.message } }
                        },
                        onNavigate = { screen = it },
                    )

                    Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
                    Screen.DETAIL -> selected?.let { note ->
                        NoteDetailScreen(
                            note = note,
                            onBack = { screen = Screen.HOME },
                            onEdit = {
                                draftTitle = note.title
                                draftBody = note.body
                                screen = Screen.EDITOR
                            },
                            onArchive = {
                                val archived = note.copy(archived = true)
                                notes = notes.map { if (it.id == note.id) archived else it }
                                scope.launch { runCatching { api.updateNote(session!!, archived) }.onFailure { syncError = it.message } }
                                screen = Screen.HOME
                            },
                            onImage = { screen = Screen.IMAGE },
                        )
                    }

                    Screen.EDITOR -> EditorScreen(
                        title = draftTitle,
                        body = draftBody,
                        photos = draftPhotos,
                        onTitleChange = { draftTitle = it },
                        onBodyChange = { draftBody = it },
                        onPhotosChange = { draftPhotos = it },
                        onSave = {
                            val old = selected
                            val saved = Note(
                                id = old?.id ?: System.nanoTime().toString(),
                                title = draftTitle.ifBlank { "Untitled note" },
                                body = draftBody,
                                color = old?.color ?: NoteColor.DEFAULT,
                                pinned = old?.pinned ?: false,
                                updatedAt = System.currentTimeMillis(),
                                photoCount = draftPhotos.size,
                                photoUris = draftPhotos.map(Uri::toString),
                            )
                            notes = listOf(saved) + notes.filterNot { it.id == saved.id }
                            scope.launch {
                                runCatching {
                                    if (old == null) api.createNote(session!!, saved.title, saved.body)
                                    else api.updateNote(session!!, saved)
                                }.onFailure { syncError = it.message }
                            }
                            selected = saved
                            screen = Screen.DETAIL
                        },
                        onBack = { screen = if (selected == null) Screen.HOME else Screen.DETAIL },
                    )

                    Screen.IMAGE -> ImageViewer(uri = selected?.photoUris?.firstOrNull(), onBack = { screen = Screen.DETAIL })
                }
            }
        }
    }
}

@Composable
private fun WearNotesSetup(defaultUrl: String, onConnected: (DeviceSession) -> Unit) {
    val api = remember { NoteApi() }
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(defaultUrl) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Connect WearNotes", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text("Use the same server on your phone and watch. The watch can sync over Wi-Fi without the phone.", color = Tide)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; error = null },
                label = { Text("Server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    connecting = true
                    scope.launch {
                        runCatching { api.pair(serverUrl) }
                            .onSuccess(onConnected)
                            .onFailure { error = it.message ?: "Could not connect" }
                        connecting = false
                    }
                },
                enabled = !connecting && serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (connecting) "Connecting…" else "Connect")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    notes: List<Note>,
    archived: Boolean,
    searchOpen: Boolean,
    search: String,
    onSearchChange: (String) -> Unit,
    onSearchToggle: () -> Unit,
    onSelect: (Note) -> Unit,
    onNew: () -> Unit,
    onArchive: (Note) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = search,
                            onValueChange = onSearchChange,
                            placeholder = { Text("Search notes") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Column {
                            Text(if (archived) "Archive" else "Notes", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                            Text("Your ideas, close at hand", color = Tide, fontSize = 13.sp)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSearchToggle) {
                        Icon(if (searchOpen) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Search, "Search")
                    }
                    if (!searchOpen) {
                        IconButton(onClick = { }) { Icon(Icons.Default.Sync, "Sync now") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        floatingActionButton = {
            if (!archived) {
                FloatingActionButton(onClick = onNew, containerColor = Ember, contentColor = Color.White) {
                    Icon(Icons.Default.Add, "New note")
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Parchment) {
                NavigationBarItem(selected = !archived, onClick = { onNavigate(Screen.HOME) }, icon = { Icon(Icons.Default.Edit, null) }, label = { Text("Notes") })
                NavigationBarItem(selected = archived, onClick = { onNavigate(Screen.ARCHIVE) }, icon = { Icon(Icons.Default.Archive, null) }, label = { Text("Archive") })
                NavigationBarItem(selected = false, onClick = { onNavigate(Screen.SETTINGS) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            EmptyNotes(modifier = Modifier.padding(padding), archived = archived, onNew = onNew)
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (!archived && notes.any { it.pinned }) {
                    item { SectionLabel("Pinned") }
                    items(notes.filter { it.pinned }, key = { it.id }) { note ->
                        NoteRow(note = note, onClick = { onSelect(note) }, onArchive = { onArchive(note) })
                    }
                    item { SectionLabel("Recent") }
                }
                items(if (archived) notes else notes.filterNot { it.pinned }, key = { it.id }) { note ->
                    NoteRow(note = note, onClick = { onSelect(note) }, onArchive = { onArchive(note) })
                }
                item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Tide, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit, onArchive: () -> Unit) {
    val rail = noteColor(note.color)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(58.dp).clip(RoundedCornerShape(4.dp)).background(rail))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(note.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(note.body.replace("\n", " "), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (note.photoCount > 0) "${note.photoCount} photos  ·  Updated today" else "Updated today", fontSize = 12.sp, color = Tide)
            }
            IconButton(onClick = onArchive) { Icon(Icons.Default.Archive, "Archive") }
        }
    }
    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f), modifier = Modifier.padding(start = 18.dp))
}

@Composable
private fun EmptyNotes(modifier: Modifier, archived: Boolean, onNew: () -> Unit) {
    Column(modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (archived) "Nothing archived" else "Nothing here yet", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(if (archived) "Archived notes will stay here." else "Create a note on your phone or watch.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!archived) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onNew) { Text("New note") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteDetailScreen(note: Note, onBack: () -> Unit, onEdit: () -> Unit, onArchive: () -> Unit, onImage: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { }) { Icon(Icons.Default.MoreVert, "More actions") } },
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = Parchment) {
                TextButton(onClick = onEdit) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(8.dp)); Text("Edit") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onArchive) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(8.dp)); Text("Archive") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(note.body, fontSize = 17.sp, lineHeight = 26.sp)
            if (note.photoUris.isNotEmpty()) {
                note.photoUris.forEach { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Photo attached to ${note.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onImage),
                    )
                }
            } else if (note.photoCount > 0) {
                Card(onClick = onImage, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Parchment)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Image, null, tint = Ember)
                        Spacer(Modifier.width(12.dp))
                        Text("${note.photoCount} attached photos", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                note.labels.forEach { label -> OutlinedButton(onClick = { }) { Text(label.name) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    title: String,
    body: String,
    photos: List<Uri>,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onPhotosChange: (List<Uri>) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        onPhotosChange(photos + uris)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (title.isBlank()) "New note" else "Edit note") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { TextButton(onClick = onSave) { Icon(Icons.Default.Check, null); Spacer(Modifier.width(6.dp)); Text("Save") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.titleLarge,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                placeholder = { Text("Write in Markdown…") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                shape = RoundedCornerShape(14.dp),
            )
            if (photos.isNotEmpty()) {
                Text("${photos.size} photos attached", color = Tide, modifier = Modifier.padding(top = 10.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch("image/*") }) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Photos")
                }
                OutlinedButton(onClick = { }) {
                    Icon(Icons.Default.AttachFile, null); Spacer(Modifier.width(8.dp)); Text("Checklist")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Sync", color = Tide, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            SettingRow("Server", "Not connected")
            SettingRow("Last sync", "Waiting for first sync")
            SettingRow("Watch access", "Available over Wi-Fi")
            Text("Appearance", color = Tide, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
            SettingRow("Theme", "System")
            SettingRow("Dynamic color", "Off")
            Text("Storage", color = Tide, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
            SettingRow("Photos", "Stored on this device and server")
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ImageViewer(uri: String?, onBack: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                offsetX += pan.x
                offsetY += pan.y
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Photo viewer",
            modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offsetX, translationY = offsetY),
        )
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
        }
    }
}

private fun noteColor(color: NoteColor): Color = when (color) {
    NoteColor.DEFAULT -> Tide
    NoteColor.EMBER -> Ember
    NoteColor.TIDE -> Tide
    NoteColor.VIOLET -> Violet
    NoteColor.CITRUS -> Color(0xFFB27600)
}
