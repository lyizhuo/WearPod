package site.whitezaak.wearpod.presentation.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class AboutHtmlParserTest {

    @Test
    fun parsesBlockStructureInOrder() {
        val html = """
            <html><head><style>.x{}</style></head><body>
            <h1>WearPod</h1>
            <p>Intro &amp; overview.</p>
            <hr />
            <h2>Privacy</h2>
            <ul><li>No tracking</li><li>Local storage</li></ul>
            <h3>Details</h3>
            <p>Text with <strong>bold</strong> and <a href="https://example.com">link</a>.</p>
            </body></html>
        """.trimIndent()

        assertEquals(
            listOf(
                AboutBlock.Heading(1, "WearPod"),
                AboutBlock.Paragraph("Intro & overview."),
                AboutBlock.Divider,
                AboutBlock.Heading(2, "Privacy"),
                AboutBlock.Bullet("No tracking"),
                AboutBlock.Bullet("Local storage"),
                AboutBlock.Heading(3, "Details"),
                AboutBlock.Paragraph("Text with bold and link."),
            ),
            parseAboutHtml(html),
        )
    }

    @Test
    fun removesScriptsStylesCommentsAndHead() {
        val html = """
            <html><head><title>About</title></head><body>
            <!-- hidden -->
            <script>alert('x')</script>
            <p>Visible</p>
            </body></html>
        """.trimIndent()

        assertEquals(listOf(AboutBlock.Paragraph("Visible")), parseAboutHtml(html))
    }

    @Test
    fun convertsBreaksAndDecodesEntities() {
        val html = "<p>Line 1<br>Line 2 &lt;tag&gt; &#39;quote&#39; &nbsp;end</p>"

        assertEquals(
            listOf(AboutBlock.Paragraph("Line 1\nLine 2 <tag> 'quote' end")),
            parseAboutHtml(html),
        )
    }

    @Test
    fun ignoresEmptyBlocksAndWhitespace() {
        val html = "<body>  <p> </p> <li></li> <hr> </body>"

        assertEquals(listOf(AboutBlock.Divider), parseAboutHtml(html))
    }

    @Test
    fun handlesPlainTextWithoutBodyWrapper() {
        assertEquals(
            listOf(AboutBlock.Paragraph("Just text")),
            parseAboutHtml("Just text"),
        )
    }
}
