package dev.ahmed.iptv.data

import com.google.gson.stream.JsonReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="http://other/ignored.xml"
        #EXTINF:-1 tvg-id="news.uk" tvg-name="News 24" tvg-logo="http://img/news.png" group-title="UK | NEWS",News 24 HD
        http://srv/live/news.ts
        #EXTINF:-1 tvg-id="" group-title="SOCCER" tvg-chno="7" catchup-days="3",Match, Live "Extra"
        #EXTVLCOPT:http-user-agent=Foo
        http://srv/live/match.m3u8

        #EXTINF:-1,No Attributes Channel
        #EXTGRP:KIDS
        http://srv/kids.ts
        #EXTINF:-1 group-title="Broken"
    """.trimIndent()

    @Test
    fun parsesChannelsGroupsAndHeader() {
        var epg = ""
        val out = ArrayList<ParsedChannel>()
        val n = M3uParser.parse(sample.reader().buffered(), onEpgUrl = { epg = it }) { out += it }

        assertEquals(3, n)
        assertEquals("http://epg.example/guide.xml.gz", epg)

        assertEquals("News 24 HD", out[0].name)
        assertEquals("UK | NEWS", out[0].group)
        assertEquals("news.uk", out[0].epgId)
        assertEquals("http://img/news.png", out[0].logo)
        assertEquals("http://srv/live/news.ts", out[0].url)
        assertEquals(1, out[0].number)

        // a comma and quotes inside the display name are kept
        assertEquals("Match, Live \"Extra\"", out[1].name)
        assertEquals(7, out[1].number)
        assertEquals(3, out[1].catchupDays)
        assertEquals("http://srv/live/match.m3u8", out[1].url)

        assertEquals("No Attributes Channel", out[2].name)
        assertEquals("KIDS", out[2].group)
    }

    @Test
    fun missingGroupBecomesUncategorized() {
        val out = ArrayList<ParsedChannel>()
        M3uParser.parse("#EXTM3U\n#EXTINF:-1,A\nhttp://a\n".reader().buffered()) { out += it }
        assertEquals("Uncategorized", out.single().group)
    }

    @Test
    fun detectsXtreamLoginsInM3uLinks() {
        val x = M3uParser.xtreamFromUrl("http://host.example:8080/get.php?username=bob&password=p%40ss&type=m3u_plus&output=ts")!!
        assertEquals("http://host.example:8080", x.server)
        assertEquals("bob", x.username)
        assertEquals("p@ss", x.password)

        assertNull(M3uParser.xtreamFromUrl("http://host.example/playlist.m3u"))
        assertNull(M3uParser.xtreamFromUrl("http://host.example/get.php?type=m3u"))
        assertNull(M3uParser.xtreamFromUrl("content://com.android.providers/document/1"))
    }
}

class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="news.uk"><display-name lang="en">News 24</display-name><icon src="x.png"/></channel>
          <channel id="kids.ca"><display-name>Kids Zone</display-name><display-name>KZ</display-name></channel>
          <programme start="20261017180000 +0000" stop="20261017190000 +0000" channel="news.uk">
            <title lang="en">Evening Report</title>
            <desc lang="en">The day's news &amp; weather.</desc>
            <category>News</category>
          </programme>
          <programme start="20261017190000 -0500" stop="20261017193000 -0500" channel="kids.ca">
            <title><![CDATA[Cartoon <Block>]]></title>
          </programme>
          <programme start="20261017190000 +0000" stop="20261017200000 +0000" channel="ignored"><title>Skip</title></programme>
          <programme start="garbage" stop="20261017200000 +0000" channel="news.uk"><title>Bad time</title></programme>
        </tv>
    """.trimIndent()

    @Test
    fun parsesProgrammesAndChannelNames() {
        val p = KXmlParser()
        p.setInput(StringReader(xml))
        var names: Map<String, String> = emptyMap()
        val progs = ArrayList<Program>()
        val n = XmltvParser(p).parse(
            onChannelsDone = { names = it },
            accept = { id, _, _ -> id != "ignored" },
            onProgram = { progs += it },
        )
        assertEquals(2, n)
        assertEquals("news.uk", names["news 24"])
        assertEquals("kids.ca", names["kz"])

        val news = progs[0]
        assertEquals("news.uk", news.epgId)
        assertEquals("Evening Report", news.title)
        assertEquals("The day's news & weather.", news.desc)
        assertEquals(60 * 60_000L, news.stop - news.start)

        val kids = progs[1]
        assertEquals("Cartoon <Block>", kids.title)
        // 19:00 at -05:00 is 00:00 UTC the next day
        assertEquals(XmltvParser.parseTime("20261018000000 +0000"), kids.start)
    }

    @Test
    fun parsesTimeFormats() {
        val utc = XmltvParser.parseTime("20261017183000 +0000")!!
        assertEquals(utc, XmltvParser.parseTime("20261017183000"))
        assertEquals(utc, XmltvParser.parseTime("20261017200000 +0130"))
        assertEquals(utc, XmltvParser.parseTime("202610171830"))
        assertNull(XmltvParser.parseTime(""))
        assertNull(XmltvParser.parseTime("2026"))
    }
}

class XtreamParserTest {

    @Test
    fun parsesLooselyTypedStreams() {
        val json = """[
          {"num":1,"name":"Sports One","stream_type":"live","stream_id":101,"stream_icon":"http://i/1.png",
           "epg_channel_id":"s1.uk","added":"1700000000","category_id":"5","category_ids":[5],"custom_sid":null,
           "tv_archive":1,"direct_source":"","tv_archive_duration":"3"},
          {"num":"2","name":"Kids","stream_id":"102","stream_icon":null,"epg_channel_id":null,"category_id":6,"tv_archive":0,"tv_archive_duration":7},
          {"name":"No id"}
        ]"""
        val out = ArrayList<XtreamParser.Stream>()
        val n = XtreamParser.streams(JsonReader(StringReader(json))) { out += it }
        assertEquals(2, n)
        assertEquals(XtreamParser.Stream(1, "Sports One", "101", "http://i/1.png", "s1.uk", "5", 3), out[0])
        assertEquals(XtreamParser.Stream(2, "Kids", "102", "", "", "6", 0), out[1])
    }

    @Test
    fun parsesCategoriesInOrder() {
        val json = """[{"category_id":"9","category_name":"SOCCER","parent_id":0},{"category_id":"3","category_name":" KIDS "}]"""
        val cats = XtreamParser.categories(JsonReader(StringReader(json)))
        assertEquals(listOf("9" to "SOCCER", "3" to "KIDS"), cats)
    }

    @Test
    fun parsesAccount() {
        val ok = XtreamParser.account(JsonReader(StringReader(
            """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1893456000","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"x"}}"""
        )))
        assertTrue(ok.auth)
        assertEquals("Active", ok.status)
        assertEquals(1893456000_000L, ok.expires)

        val bad = XtreamParser.account(JsonReader(StringReader("""{"user_info":{"auth":0}}""")))
        assertFalse(bad.auth)

        val empty = XtreamParser.account(JsonReader(StringReader("[]")))
        assertFalse(empty.auth)
    }
}
