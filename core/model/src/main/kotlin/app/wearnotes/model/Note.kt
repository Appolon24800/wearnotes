package app.wearnotes.model

enum class NoteColor {
    DEFAULT,
    EMBER,
    TIDE,
    VIOLET,
    CITRUS,
}

data class Note(
    val id: String,
    val title: String,
    val body: String,
    val color: NoteColor = NoteColor.DEFAULT,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val trashed: Boolean = false,
    val updatedAt: Long = 0L,
    val photoCount: Int = 0,
    val photoUris: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val labels: List<Label> = emptyList(),
)

data class Label(
    val id: String,
    val name: String,
    val color: NoteColor = NoteColor.DEFAULT,
)
