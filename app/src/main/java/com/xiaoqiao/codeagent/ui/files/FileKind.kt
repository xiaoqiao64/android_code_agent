package com.xiaoqiao.codeagent.ui.files

const val FILE_PROVIDER = "com.xiaoqiao.codeagent.fileprovider"

enum class FileKind {
    Pdf,
    Docx,
    Html,
    Text,
    Image,
    Svg,
    External,
    ;

    val internal: Boolean get() = this != External
}

object FileKinds {
    private val PDF = setOf("pdf")
    private val DOCX = setOf("docx")
    private val HTML = setOf("html", "htm")
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif", "webp")
    private val SVG = setOf("svg")
    private val TEXT = setOf(
        "txt", "md", "markdown", "json", "xml", "csv",
        "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx",
        "go", "rs", "c", "h", "cpp", "cc", "hpp", "cs", "swift", "rb", "php",
        "sh", "bash", "zsh", "yml", "yaml", "toml", "ini", "properties",
        "gradle", "log", "sql", "css", "scss", "less",
    )

    fun of(name: String): FileKind {
        val ext = extension(name)
        if (ext.isEmpty()) return FileKind.External
        return when (ext) {
            in PDF -> FileKind.Pdf
            in DOCX -> FileKind.Docx
            in HTML -> FileKind.Html
            in IMAGE -> FileKind.Image
            in SVG -> FileKind.Svg
            in TEXT -> FileKind.Text
            else -> FileKind.External
        }
    }

    fun mimeOf(name: String): String {
        val ext = extension(name)
        return when (of(name)) {
            FileKind.Pdf -> "application/pdf"
            FileKind.Docx -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            FileKind.Html -> "text/html"
            FileKind.Svg -> "image/svg+xml"
            FileKind.Image -> when (ext) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            FileKind.Text -> when (ext) {
                "json" -> "application/json"
                "xml" -> "application/xml"
                "csv" -> "text/csv"
                "md", "markdown" -> "text/markdown"
                else -> "text/plain"
            }
            FileKind.External -> "application/octet-stream"
        }
    }

    fun extension(name: String): String {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return if (ext.isEmpty() || ext == name.lowercase()) "" else ext
    }
}
