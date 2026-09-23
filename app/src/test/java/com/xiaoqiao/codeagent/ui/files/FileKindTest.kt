package com.xiaoqiao.codeagent.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindTest {
    @Test
    fun internalFormats() {
        assertEquals(FileKind.Pdf, FileKinds.of("a.pdf"))
        assertEquals(FileKind.Docx, FileKinds.of("report.DOCX"))
        assertEquals(FileKind.Html, FileKinds.of("index.html"))
        assertEquals(FileKind.Html, FileKinds.of("page.htm"))
        assertEquals(FileKind.Text, FileKinds.of("Main.kt"))
        assertEquals(FileKind.Text, FileKinds.of("notes.md"))
        assertEquals(FileKind.Image, FileKinds.of("shot.png"))
        assertEquals(FileKind.Svg, FileKinds.of("logo.svg"))
        assertTrue(FileKinds.of("a.pdf").internal)
        assertTrue(FileKinds.of("a.kt").internal)
    }

    @Test
    fun externalFormats() {
        assertEquals(FileKind.External, FileKinds.of("a.bin"))
        assertEquals(FileKind.External, FileKinds.of("deck.pptx"))
        assertEquals(FileKind.External, FileKinds.of("noext"))
        assertFalse(FileKinds.of("a.bin").internal)
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/pdf", FileKinds.mimeOf("a.pdf"))
        assertEquals("text/html", FileKinds.mimeOf("a.html"))
        assertEquals("image/png", FileKinds.mimeOf("a.png"))
        assertEquals("application/octet-stream", FileKinds.mimeOf("a.bin"))
    }
}
