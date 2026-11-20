package app.wearnotes.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wearnotes.model.Note
import app.wearnotes.model.NoteColor
import app.wearnotes.network.DeviceSession
import app.wearnotes.network.NoteApi
import kotlinx.coroutines.launch
import android.text.method.LinkMovementMethod
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.tasklist.TaskListPlugin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WearNotesWearApp() }
    }
}

private val Ink = Color(0xFFF5EEE8)
private val Cocoa = Color(0xFF17110E)
private val CocoaRaised = Color(0xFF251C17)
private val Coral = Color(0xFFFFB59A)
private val SeaGlass = Color(0xFF76D0C9)
private val RailViolet = Color(0xFFD8BFFF)
private val RailGold = Color(0xFFFFD36E)

@Composable
private fun WearNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Cocoa,
            surface = CocoaRaised,
            onBackground = Ink,
            onSurface = Ink,
            primary = Coral,
            onPrimary = Cocoa,
            secondary = SeaGlass,
            onSecondary = Cocoa,
            outline = Ink.copy(alpha = .35f),
        ),
        content = content,
    )
}

private enum class Page {
    RECENT,
    DETAIL,
    WRITE,
}

@Composable
private fun WearNotesWearApp() {
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
        WearSetupScreen(
            defaultUrl = "http://10.0.2.2:8081",
            onConnected = { connected ->
                preferences.edit()
                    .putString("baseUrl", connected.baseUrl)
                    .putString("token", connected.token)
                    .apply()
                session = connected
            },
        )
        return
    }

    var page by remember { mutableStateOf(Page.RECENT) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var selected by remember { mutableStateOf<Note?>(null) }
    var draft by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        syncing = true
        runCatching { api.listNotes(session!!) }
            .onSuccess { notes = it }
        syncing = false
    }

    LaunchedEffect(session) { refresh() }

    WearNotesTheme {
        Surface(Modifier.fillMaxSize(), color = Cocoa) {
            when (page) {
                Page.RECENT -> RecentPage(
                    notes = notes.filterNot { it.archived || it.trashed },
                    syncing = syncing,
                    onRefresh = { scope.launch { refresh() } },
                    onNew = {
                        draft = ""
                        page = Page.WRITE
                    },
                    onSelect = {
                        selected = it
                        page = Page.DETAIL
                    },
                )

                Page.DETAIL -> selected?.let { note ->
                    DetailPage(
                        note = note,
                        onBack = { page = Page.RECENT },
                        onArchive = {
                            val archived = note.copy(archived = true)
                            notes = notes.map { if (it.id == note.id) archived else it }
                            scope.launch { runCatching { api.updateNote(session!!, archived) } }
                            page = Page.RECENT
                        },
                    )
                }

                Page.WRITE -> WritePage(
                    draft = draft,
                    onDraftChange = { draft = it },
                    onBack = { page = Page.RECENT },
                    onSave = {
                        if (draft.isNotBlank()) {
                            val local = Note(
                                id = System.nanoTime().toString(),
                                title = "New note",
                                body = draft,
                                color = NoteColor.EMBER,
                            )
                            notes = listOf(local) + notes
                            scope.launch { runCatching { api.createNote(session!!, local.title, local.body) } }
                        }
                        draft = ""
                        page = Page.RECENT
                    },
                )
            }
        }
    }
}

@Composable
private fun WearSetupScreen(defaultUrl: String, onConnected: (DeviceSession) -> Unit) {
    val api = remember { NoteApi() }
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(defaultUrl) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    WearSafeFrame {
        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Connect", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("WearNotes server", fontSize = 13.sp, color = SeaGlass)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; error = null },
                label = { Text("Server") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink,
                    focusedLabelColor = SeaGlass,
                    unfocusedLabelColor = SeaGlass,
                    focusedBorderColor = SeaGlass,
                    unfocusedBorderColor = SeaGlass.copy(alpha = .7f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
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
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Cocoa),
            ) {
                Text(if (connecting) "Connecting…" else "Connect")
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentPage(
    notes: List<Note>,
    syncing: Boolean,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onSelect: (Note) -> Unit,
) {
    Scaffold(containerColor = Cocoa) { padding ->
        BoxWithConstraints(
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val sideInset = if (maxWidth < 300.dp) 24.dp else 32.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sideInset,
                    end = sideInset,
                    top = 18.dp,
                    bottom = 86.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Header(onRefresh = onRefresh, syncing = syncing)
                    Spacer(Modifier.height(10.dp))
                }
                if (notes.isEmpty()) {
                    item { EmptyRecent(onNew = onNew) }
                } else {
                    val pinned = notes.filter { it.pinned }
                    val recent = notes.filterNot { it.pinned }
                    if (pinned.isNotEmpty()) {
                        item { SmallLabel("Pinned") }
                        items(pinned, key = { it.id }) { note ->
                            NoteRow(note = note, onClick = { onSelect(note) })
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item { SmallLabel(if (pinned.isEmpty()) "Recent" else "More") }
                        items(recent, key = { it.id }) { note ->
                            NoteRow(note = note, onClick = { onSelect(note) })
                        }
                    }
                }
            }

            Button(
                onClick = onNew,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .width(140.dp)
                    .height(40.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Cocoa),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New note", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Header(onRefresh: () -> Unit, syncing: Boolean) {
    Box(Modifier.fillMaxWidth().height(58.dp)) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Notes", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (syncing) "Syncing…" else "Updated just now",
                color = SeaGlass,
                fontSize = 11.sp,
            )
        }
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.CenterEnd).size(48.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Ink)
        }
    }
}

@Composable
private fun SmallLabel(text: String) {
    Text(
        text = text,
        color = SeaGlass,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        color = CocoaRaised,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = 36.dp)
                    .clip(CircleShape)
                    .background(noteRail(note.color)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = note.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = note.body.replace("\n", " "),
                    color = Ink.copy(alpha = .72f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyRecent(onNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No notes yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Write one from your watch.", color = Ink.copy(alpha = .68f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onNew,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CocoaRaised, contentColor = Ink),
        ) {
            Text("Create note", fontSize = 12.sp)
        }
    }
}

@Composable
private fun DetailPage(note: Note, onBack: () -> Unit, onArchive: () -> Unit) {
    Scaffold(containerColor = Cocoa) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val sideInset = if (maxWidth < 300.dp) 24.dp else 32.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = sideInset,
                    end = sideInset,
                    top = 12.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
                    }
                }
                item {
                    Text(
                        text = note.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    )
                }
                item {
                    MarkdownText(note.body)
                }
                item {
                    Button(
                        onClick = onArchive,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CocoaRaised, contentColor = Ink),
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Archive", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownText(markdown: String) {
    val context = LocalContext.current
    val markwon = remember {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .headingTextSizeMultipliers(floatArrayOf(1.2f, 1.1f, 1.04f, 1f, 1f, 1f))
                        .headingBreakHeight(0)
                        .codeTextColor(android.graphics.Color.rgb(118, 208, 201))
                        .codeBlockTextColor(android.graphics.Color.rgb(245, 238, 232))
                        .codeBackgroundColor(android.graphics.Color.rgb(37, 28, 23))
                        .codeBlockBackgroundColor(android.graphics.Color.rgb(37, 28, 23))
                        .codeBlockMargin(8)
                }
            })
            .usePlugin(TaskListPlugin.create(context))
            .build()
    }
    AndroidView(
        factory = {
            TextView(it).apply {
                setTextColor(android.graphics.Color.rgb(245, 238, 232))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(0f, 1.25f)
                setPadding(0, 0, 0, 0)
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
            }
        },
        update = { textView -> markwon.setMarkdown(textView, markdown) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun WritePage(
    draft: String,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Scaffold(containerColor = Cocoa) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val sideInset = if (maxWidth < 300.dp) 24.dp else 32.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = sideInset, end = sideInset, top = 12.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
                    }
                    Text("New note", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = { Text("Write a note", color = Ink.copy(alpha = .55f)) },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        focusedContainerColor = CocoaRaised,
                        unfocusedContainerColor = CocoaRaised,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                Button(
                    onClick = onSave,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).width(132.dp).height(46.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Cocoa),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WearSafeFrame(content: @Composable () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val width = if (maxWidth < 300.dp) maxWidth - 44.dp else 300.dp
        Box(Modifier.width(width).fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

private fun noteRail(color: NoteColor): Color = when (color) {
    NoteColor.DEFAULT -> SeaGlass
    NoteColor.EMBER -> Coral
    NoteColor.TIDE -> SeaGlass
    NoteColor.VIOLET -> RailViolet
    NoteColor.CITRUS -> RailGold
}
