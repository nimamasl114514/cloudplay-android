package com.cloudplay.app.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3u8RewriterTest {

    @Test
    fun rewritesRelativeAndAbsoluteUris() {
        val src = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:10
            #EXTINF:9.0,
            seg0.ts
            #EXTINF:9.0,
            https://cdn.example.com/a/seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent() + "\n"

        val out = M3u8Rewriter.rewrite(src, "https://cdn.example.com/a/play.m3u8") { "/s/" + it.hashCode() }

        assertTrue(out.contains("#EXTM3U"))
        assertTrue(out.contains("seg0.ts"))
        assertTrue(out.contains("https://cdn.example.com/a/seg1.ts"))
        assertTrue(out.contains("/s/"))
        assertEquals(src.lines().size, out.lines().size)
    }

    @Test
    fun rewritesKeyUriInTag() {
        val src = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x1\n#EXTINF:1,\na.ts\n"
        val out = M3u8Rewriter.rewrite(src, "https://x/y/play.m3u8") { "LOCAL($it)" }
        assertTrue(out.contains("URI=\"LOCAL(https://x/y/key.bin)\""))
        assertTrue(out.contains("LOCAL(https://x/y/a.ts)"))
    }

    @Test
    fun suffixKeepsUpstreamExtension() {
        assertEquals(".ts", M3u8Rewriter.suffixOf("https://a/b/seg0.ts"))
        assertEquals(".m4s", M3u8Rewriter.suffixOf("https://a/b/seg0.m4s?token=1"))
        assertEquals(".ts", M3u8Rewriter.suffixOf("https://a/b/segment"))
    }
}
