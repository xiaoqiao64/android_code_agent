package com.xiaoqiao.codeagent.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoqiao.codeagent.runtime.FsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.zwobble.mammoth.DocumentConverter
import java.io.File

data class Viewing(
    val entry: FsEntry,
    val file: File,
    val kind: FileKind,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(viewing: Viewing, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(viewing.entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (viewing.kind) {
                FileKind.Pdf -> PdfViewer(viewing.file)
                FileKind.Docx -> DocxViewer(viewing.file)
                FileKind.Html, FileKind.Svg -> LocalWebView(viewing.file)
                FileKind.Text -> TextViewer(viewing.file)
                FileKind.Image -> ImageViewer(viewing.file)
                FileKind.External -> Text("无法在应用内打开此文件", modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun PdfViewer(file: File) {
    val holder = remember(file) { PdfRendererHolder(file) }
    DisposableEffect(holder) {
        onDispose { holder.close() }
    }
    val error = holder.error
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(holder.pageCount) { index ->
                PdfPage(holder, index)
            }
        }
    }
}

@Composable
private fun PdfPage(holder: PdfRendererHolder, index: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.coerceAtLeast(1)
        var bitmap by remember(index, widthPx) { mutableStateOf<Bitmap?>(null) }
        LaunchedEffect(index, widthPx) {
            bitmap = withContext(Dispatchers.IO) { holder.render(index, widthPx) }
        }
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "第 ${index + 1} 页",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
    }
}

private class PdfRendererHolder(file: File) {
    private val pfd: ParcelFileDescriptor?
    private val renderer: PdfRenderer?
    val pageCount: Int
    val error: String?

    init {
        var opened: PdfRenderer? = null
        var desc: ParcelFileDescriptor? = null
        var err: String? = null
        var count = 0
        try {
            desc = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            opened = PdfRenderer(desc)
            count = opened.pageCount
        } catch (e: Exception) {
            err = e.message ?: "无法打开 PDF"
            opened?.close()
            desc?.close()
            opened = null
            desc = null
        }
        renderer = opened
        pfd = desc
        pageCount = count
        error = err
    }

    fun render(index: Int, widthPx: Int): Bitmap? {
        val renderer = renderer ?: return null
        synchronized(this) {
            renderer.openPage(index).use { page ->
                val scale = widthPx.toFloat() / page.width.coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bmp
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (renderer != null) {
                renderer.close()
            } else {
                pfd?.close()
            }
        }
    }
}

@Composable
private fun DocxViewer(file: File) {
    var html by remember(file) { mutableStateOf<String?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        val result = withContext(Dispatchers.IO) {
            runCatching { DocumentConverter().convertToHtml(file).value }
        }
        html = result.getOrNull()
        error = result.exceptionOrNull()?.message
    }
    when {
        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        html == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            val content = html!!
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.allowFileAccess = false
                        loadDataWithBaseURL(null, content, "text/html", "utf-8", null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LocalWebView(file: File) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                loadUrl(file.toURI().toString())
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private const val TEXT_LIMIT = 2 * 1024 * 1024

@Composable
private fun TextViewer(file: File) {
    val text = remember(file) {
        runCatching {
            file.reader(Charsets.UTF_8).use { reader ->
                val buf = CharArray(TEXT_LIMIT)
                val n = reader.read(buf)
                if (n <= 0) ""
                else String(buf, 0, n) + if (n == TEXT_LIMIT) "\n\n… 已截断" else ""
            }
        }.getOrElse { it.message ?: "无法读取文件" }
    }
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun ImageViewer(file: File) {
    val bmp = remember(file) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
    if (bmp == null) {
        Text("无法显示图片", modifier = Modifier.padding(16.dp))
    } else {
        Image(
            bitmap = bmp,
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentScale = ContentScale.Fit,
        )
    }
}
