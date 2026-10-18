' Unit tests for source/core.brs, run off the device with the "brs" BrightScript interpreter:
'   npx brs source/core.brs tests/test_core.brs
' Prints ALL TESTS PASSED, or the failures.

sub Main()
    m.failures = 0
    m.checks = 0
    q = Chr(34)

    ' --- M3U
    nl = Chr(10)
    m3u = "#EXTM3U url-tvg=" + q + "http://g/guide.xml.gz,http://g/b.xml" + q + nl
    m3u = m3u + "#EXTINF:-1 tvg-id=" + q + "bbc1" + q + " tvg-logo=" + q + "http://l/bbc.png" + q + " group-title=" + q + "UK, News" + q + ",BBC One, HD" + Chr(13) + nl
    m3u = m3u + "http://s/1.ts" + Chr(13) + nl
    m3u = m3u + "#EXTINF:-1,No Group" + nl + "#EXTVLCOPT:x=y" + nl + "http://s/2.ts" + nl
    m3u = m3u + "#EXTINF:-1 tvg-chno=" + q + "42" + q + ",Numbered" + nl + "#EXTGRP:Sports" + nl + "http://s/3.ts" + nl
    m3u = m3u + "#EXTINF:-1,Missing URL" + nl
    r = IptvParseM3U(m3u)
    eq(r.epgUrl, "http://g/guide.xml.gz", "m3u guide url")
    eq(r.channels.Count(), 3, "m3u channel count")
    eq(r.channels[0].name, "BBC One, HD", "name with comma")
    eq(r.channels[0].group, "UK, News", "group with comma")
    eq(r.channels[0].epgId, "bbc1", "tvg-id")
    eq(r.channels[0].logo, "http://l/bbc.png", "logo")
    eq(r.channels[0].url, "http://s/1.ts", "CRLF trimmed")
    eq(r.channels[1].group, "Uncategorized", "default group")
    eq(r.channels[2].num, 42, "tvg-chno")
    eq(r.channels[2].group, "Sports", "EXTGRP")

    ' --- Xtream login hidden in an M3U link
    x = IptvXtreamFromUrl("http://host:8080/get.php?username=a%20b&password=p%26w&type=m3u_plus")
    ok(x <> invalid, "xtream detected")
    eq(x.server, "http://host:8080", "xtream server")
    eq(x.username, "a b", "xtream user decoded")
    eq(x.password, "p&w", "xtream pass decoded")
    ok(IptvXtreamFromUrl("http://host/list.m3u") = invalid, "plain m3u is not xtream")
    n = IptvNormalize({ id: "1", name: "x", type: "m3u", url: "http://h:1/get.php?username=u&password=p", username: "", password: "" })
    eq(n.type, "xtream", "normalize converts")
    eq(n.url, "http://h:1", "normalize server")
    n2 = IptvNormalize({ id: "2", name: "y", type: "xtream", url: " provider.tv:8080/player_api.php/ ", username: " u ", password: "p" })
    eq(n2.url, "http://provider.tv:8080", "normalize adds http and strips api path")
    eq(n2.username, "u", "normalize trims user")

    ' --- Xtream channels and URLs
    p = { url: "http://h", username: "u", password: "p w" }
    cats = ParseJson("[{" + q + "category_id" + q + ":" + q + "2" + q + "," + q + "category_name" + q + ":" + q + "KIDS" + q + "},{" + q + "category_id" + q + ":1," + q + "category_name" + q + ":" + q + "SOCCER" + q + "}]")
    streams = ParseJson("[{" + q + "num" + q + ":1," + q + "name" + q + ":" + q + "Pitch" + q + "," + q + "stream_id" + q + ":101," + q + "category_id" + q + ":" + q + "1" + q + "," + q + "epg_channel_id" + q + ":" + q + "p1" + q + "},{" + q + "num" + q + ":0," + q + "name" + q + ":" + q + "Toons" + q + "," + q + "stream_id" + q + ":" + q + "102" + q + "," + q + "category_id" + q + ":2},{" + q + "name" + q + ":" + q + "Orphan" + q + "," + q + "stream_id" + q + ":103," + q + "category_id" + q + ":99}]")
    xc = IptvXtreamChannels(p, cats, streams)
    eq(xc.channels.Count(), 3, "xtream channel count")
    eq(xc.channels[0].url, "http://h/live/u/p%20w/101.m3u8", "xtream stream url is HLS")
    eq(xc.channels[0].key, "101", "numeric id as text")
    eq(xc.channels[1].num, 2, "missing num numbered")
    eq(xc.groups.Count(), 3, "group count")
    eq(xc.groups[0].name, "KIDS", "provider group order 1")
    eq(xc.groups[1].name, "SOCCER", "provider group order 2")
    eq(xc.groups[2].name, "Uncategorized", "unknown category")

    ' --- HLS conversion for Roku playback
    eq(IptvToHls("http://h:8080/live/u/p/123.ts"), "http://h:8080/live/u/p/123.m3u8", "xtream ts to hls")
    eq(IptvToHls("http://h:8080/u/p/123"), "http://h:8080/live/u/p/123.m3u8", "xtream short to hls")
    eq(IptvToHls("https://cdn/x/master.m3u8?t=1"), "https://cdn/x/master.m3u8?t=1", "hls kept")
    eq(IptvToHls("http://h/a/b/c/stream.ts?x=1"), "http://h/a/b/c/stream.m3u8?x=1", "plain ts to hls")
    eq(IptvStreamFormat("http://h/a.mpd"), "dash", "dash format")
    eq(IptvStreamFormat("http://h/a.m3u8"), "hls", "hls format")
    sl = IptvStreamLogin("http://h:8080/live/us%40r/pw/555.ts")
    ok(sl <> invalid, "stream login parsed")
    eq(sl.server, "http://h:8080", "stream login server")
    eq(sl.username, "us@r", "stream login user")
    eq(sl.id, "555", "stream login id")
    ok(IptvStreamLogin("http://h/movies/film.mp4") = invalid, "non-xtream stream")

    ' --- short guide (base64 + UTF-8 titles)
    eq(IptvB64("RGVyYnkgRGF5"), "Derby Day", "base64 ascii")
    eq(IptvB64("Q2Fmw6k="), "Caf" + Chr(233), "base64 utf-8")
    eq(IptvB64("2KfZhNij2K7YqNin2LE="), Chr(1575) + Chr(1604) + Chr(1571) + Chr(1582) + Chr(1576) + Chr(1575) + Chr(1585), "base64 arabic")
    epg = ParseJson("{" + q + "epg_listings" + q + ":[{" + q + "title" + q + ":" + q + "RGVyYnkgRGF5" + q + "," + q + "start_timestamp" + q + ":" + q + "1700000000" + q + "," + q + "stop_timestamp" + q + ":1700007200}]}")
    list = IptvShortEpg(epg)
    eq(list.Count(), 1, "short epg count")
    eq(list[0].title, "Derby Day", "short epg title")
    eq(list[0].start, 1700000000, "short epg start")
    eq(list[0].stop, 1700007200, "short epg stop (number)")
    eq(IptvShortEpg(invalid).Count(), 0, "short epg invalid")

    ' --- search and encoding
    found = IptvSearch(r.channels, "  bbc ")
    eq(found.Count(), 1, "search case-insensitive")
    eq(IptvSearch(r.channels, "").Count(), 0, "empty search")
    eq(IptvEnc("a b/c?&"), "a%20b%2Fc%3F%26", "percent-encoding")
    eq(IptvDec("a%20b+c"), "a b c", "percent-decoding")

    if m.failures = 0
        print "ALL TESTS PASSED (" + Str(m.checks).Trim() + " checks)"
    else
        print Str(m.failures).Trim() + " TEST(S) FAILED"
    end if
end sub

sub eq(actual as dynamic, expected as dynamic, label as string)
    m.checks = m.checks + 1
    if type(actual) <> type(expected) and not (IsNum(actual) and IsNum(expected))
        m.failures = m.failures + 1
        print "FAIL " + label + ": type " + type(actual) + " vs " + type(expected)
        return
    end if
    if actual <> expected
        m.failures = m.failures + 1
        print "FAIL " + label + ": got [" + ToText(actual) + "] expected [" + ToText(expected) + "]"
    end if
end sub

sub ok(cond as boolean, label as string)
    m.checks = m.checks + 1
    if not cond
        m.failures = m.failures + 1
        print "FAIL " + label
    end if
end sub

function IsNum(v as dynamic) as boolean
    t = type(v)
    return t = "Integer" or t = "roInteger" or t = "LongInteger" or t = "Float" or t = "Double" or t = "roFloat" or t = "roDouble"
end function

function ToText(v as dynamic) as string
    if IsNum(v) then return Str(v).Trim()
    if type(v) = "String" or type(v) = "roString" then return v
    return type(v)
end function
