package app.mebox.android.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuParserTest {

    @Test
    fun `bilibili xml maps time mode color and size`() {
        val raw = """
            <i>
              <d p="1.5,1,25,16711680,0,0,abc,1">红色 &amp; 弹幕</d>
              <d p="2,4,25,16777215,0,0,abc,2">底部弹幕</d>
            </i>
        """.trimIndent()

        val comments = DanmakuParser.parse(raw, "xml")

        assertEquals(2, comments.size)
        assertEquals(1_500L, comments[0].timeMs)
        assertEquals("红色 & 弹幕", comments[0].text)
        assertEquals(DanmakuMode.SCROLL, comments[0].mode)
        assertEquals(0xFFFF0000.toInt(), comments[0].color)
        assertEquals(1f, comments[0].sizeScale, 0.001f)
        assertEquals(DanmakuMode.BOTTOM, comments[1].mode)
        assertEquals(DanmakuComment.WHITE, comments[1].color)
    }

    @Test
    fun `dandanplay xml with four fields keeps color at index two`() {
        val raw = """<d p="3,5,255,user">顶部</d>"""

        val comments = DanmakuParser.parse(raw, "xml")

        assertEquals(1, comments.size)
        assertEquals(3_000L, comments[0].timeMs)
        assertEquals(DanmakuMode.TOP, comments[0].mode)
        assertEquals(0xFF0000FF.toInt(), comments[0].color)
        assertEquals(1f, comments[0].sizeScale, 0.001f)
    }

    @Test
    fun `dandanplay json uses p and m fields`() {
        val raw = """
            {"count":2,"comments":[
              {"p":"12.5,1,16777215,user","m":"hello","t":12.5},
              {"p":"13,5,255,user","m":"top","t":13}
            ]}
        """.trimIndent()

        val comments = DanmakuParser.parse(raw, "json")

        assertEquals(2, comments.size)
        assertEquals(12_500L, comments[0].timeMs)
        assertEquals("hello", comments[0].text)
        assertEquals(DanmakuMode.SCROLL, comments[0].mode)
        assertEquals(DanmakuMode.TOP, comments[1].mode)
        assertEquals(0xFF0000FF.toInt(), comments[1].color)
    }

    @Test
    fun `generic json supports keys and color strings`() {
        val raw = """
            [
              {"time":1,"text":"first","mode":"top","color":"#00ff00","size":50},
              {"time":2,"text":"second","mode":4,"color":"#f00"}
            ]
        """.trimIndent()

        val comments = DanmakuParser.parse(raw, "json")

        assertEquals(2, comments.size)
        assertEquals(DanmakuMode.TOP, comments[0].mode)
        assertEquals(0xFF00FF00.toInt(), comments[0].color)
        assertEquals(1.6f, comments[0].sizeScale, 0.001f)
        assertEquals(DanmakuMode.BOTTOM, comments[1].mode)
        assertEquals(0xFFFF0000.toInt(), comments[1].color)
    }

    @Test
    fun `ccl tuples are parsed`() {
        val raw = """[[1,1,25,"#00ff00","tuple"]]"""

        val comments = DanmakuParser.parse(raw, "json")

        assertEquals(1, comments.size)
        assertEquals("tuple", comments[0].text)
        assertEquals(1_000L, comments[0].timeMs)
        assertEquals(0xFF00FF00.toInt(), comments[0].color)
    }

    @Test
    fun `auto detects xml and json payloads`() {
        val xml = DanmakuParser.parse("""<d p="1,1,25,16777215">xml</d>""", "auto")
        val json = DanmakuParser.parse("""{"comments":[{"p":"1,1,16777215,u","m":"json"}]}""", "auto")

        assertEquals("xml", xml.single().text)
        assertEquals("json", json.single().text)
    }

    @Test
    fun `invalid entries are dropped and results are sorted`() {
        val raw = """
            <d p="5,1,25,16777215">later</d>
            <d p="-1,1,25,16777215">negative</d>
            <d p="2,1,25,16777215">   </d>
            <d p="1,1,25,16777215">first</d>
        """.trimIndent()

        val comments = DanmakuParser.parse(raw, "xml")

        assertEquals(listOf("first", "later"), comments.map { it.text })
    }

    @Test
    fun `empty payload yields no comments`() {
        assertTrue(DanmakuParser.parse("", "auto").isEmpty())
        assertTrue(DanmakuParser.parse("not a payload", "auto").isEmpty())
    }
}
