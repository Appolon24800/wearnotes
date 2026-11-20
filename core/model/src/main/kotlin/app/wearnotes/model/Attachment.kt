package app.wearnotes.model

data class Attachment(
    val id: String,
    val noteId: String,
    val uri: String,
    val width: Int,
    val height: Int,
    val mimeType: String,
)
