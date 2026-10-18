' IPTV core for Roku: M3U and Xtream parsing, stream URLs and the short guide.
' Pure functions with no Roku-only objects, so they can be unit-tested off the device.

function IptvIsKeyChar(c as string) as boolean
    a = Asc(c)
    if a >= 48 and a <= 57 then return true
    if a >= 65 and a <= 90 then return true
    if a >= 97 and a <= 122 then return true
    return c = "-" or c = "_"
end function

' key="value" attributes of an #EXTINF / #EXTM3U line (keys lower-case).
function IptvAttrs(line as string) as object
    out = {}
    q = Chr(34)
    i = 1
    while true
        eq = Instr(i, line, "=" + q)
        if eq = 0 then exit while
        k = eq - 1
        while k >= 1
            if not IptvIsKeyChar(Mid(line, k, 1)) then exit while
            k = k - 1
        end while
        key = Mid(line, k + 1, eq - k - 1)
        closing = Instr(eq + 2, line, q)
        if closing = 0 then exit while
        if key <> "" then out[LCase(key)] = Mid(line, eq + 2, closing - eq - 2)
        i = closing + 1
    end while
    return out
end function

function IptvGet(aa as object, key as string) as string
    v = aa[key]
    if v = invalid then return ""
    return IptvStr(v)
end function

' Any JSON value as a string (providers send ids as numbers or strings).
function IptvStr(v as dynamic) as string
    t = type(v)
    if t = "String" or t = "roString" then return v
    if t = "Integer" or t = "roInteger" or t = "LongInteger" or t = "roLongInteger" then return Str(v).Trim()
    if t = "Float" or t = "roFloat" or t = "Double" or t = "roDouble" then return Str(Int(v)).Trim()
    if t = "Boolean" or t = "roBoolean"
        if v then return "1"
        return "0"
    end if
    return ""
end function

' The display name is the text after the first comma that is not inside quotes.
function IptvDisplayName(info as string) as string
    inQ = false
    for i = 1 to Len(info)
        c = Mid(info, i, 1)
        if c = Chr(34)
            inQ = not inQ
        else if c = "," and not inQ
            return Mid(info, i + 1).Trim()
        end if
    end for
    return ""
end function

' Parses an extended M3U playlist into { epgUrl, channels: [ {key, num, name, group, logo, url, epgId} ] }.
function IptvParseM3U(text as string) as object
    channels = []
    epg = ""
    info = ""
    extGroup = ""
    for each raw in text.Split(Chr(10))
        line = raw.Trim()
        if Len(line) > 0
            if Asc(line) = 65279 then line = Mid(line, 2)
            head = UCase(Left(line, 8))
            if Left(head, 7) = "#EXTM3U"
                a = IptvAttrs(line)
                e = IptvGet(a, "url-tvg")
                if e = "" then e = IptvGet(a, "x-tvg-url")
                if e = "" then e = IptvGet(a, "tvg-url")
                if e <> "" then epg = e.Split(",")[0].Trim()
            else if Left(head, 7) = "#EXTINF"
                info = line
                extGroup = ""
            else if head = "#EXTGRP:"
                extGroup = Mid(line, 9).Trim()
            else if Left(line, 1) = "#"
                ' other directives (#EXTVLCOPT…)
            else if info <> ""
                a = IptvAttrs(info)
                n = channels.Count() + 1
                name = IptvDisplayName(info)
                if name = "" then name = IptvGet(a, "tvg-name")
                if name = "" then name = "Channel " + Str(n).Trim()
                group = IptvGet(a, "group-title").Trim()
                if group = "" then group = extGroup
                if group = "" then group = "Uncategorized"
                num = IptvGet(a, "tvg-chno").ToInt()
                if num <= 0 then num = n
                channels.Push({ key: name, num: num, name: name, group: group, logo: IptvGet(a, "tvg-logo"), url: line, epgId: IptvGet(a, "tvg-id").Trim() })
                info = ""
            end if
        end if
    end for
    return { epgUrl: epg, channels: channels }
end function

function IptvHex2(n as integer) as string
    d = "0123456789ABCDEF"
    return Mid(d, (n \ 16) + 1, 1) + Mid(d, (n mod 16) + 1, 1)
end function

' Percent-encodes a value for a URL path or query.
function IptvEnc(s as string) as string
    out = ""
    for i = 1 to Len(s)
        c = Mid(s, i, 1)
        a = Asc(c)
        if (a >= 48 and a <= 57) or (a >= 65 and a <= 90) or (a >= 97 and a <= 122) or c = "-" or c = "_" or c = "." or c = "~"
            out = out + c
        else if a < 128
            out = out + "%" + IptvHex2(a)
        else
            out = out + c
        end if
    end for
    return out
end function

function IptvDec(s as string) as string
    out = ""
    i = 1
    n = Len(s)
    hexd = "0123456789ABCDEF"
    while i <= n
        c = Mid(s, i, 1)
        if c = "+"
            out = out + " "
            i = i + 1
        else if c = "%" and i + 2 <= n
            hi = Instr(1, hexd, UCase(Mid(s, i + 1, 1))) - 1
            lo = Instr(1, hexd, UCase(Mid(s, i + 2, 1))) - 1
            if hi >= 0 and lo >= 0
                out = out + Chr(hi * 16 + lo)
                i = i + 3
            else
                out = out + c
                i = i + 1
            end if
        else
            out = out + c
            i = i + 1
        end if
    end while
    return out
end function

' An M3U link like http://host:port/get.php?username=U&password=P is really an Xtream login.
function IptvXtreamFromUrl(url as string) as object
    u = url.Trim()
    q = Instr(1, u, "?")
    if q = 0 then return invalid
    lower = LCase(u)
    if Left(lower, 7) <> "http://" and Left(lower, 8) <> "https://" then return invalid
    path = Left(u, q - 1)
    lp = LCase(path)
    if Right(lp, 7) <> "get.php" and Right(lp, 14) <> "player_api.php" then return invalid
    params = {}
    for each part in Mid(u, q + 1).Split("&")
        i = Instr(1, part, "=")
        if i > 1 then params[Left(part, i - 1)] = IptvDec(Mid(part, i + 1))
    end for
    user = IptvGet(params, "username")
    pass = IptvGet(params, "password")
    if user = "" or pass = "" then return invalid
    slash = 0
    for i = Len(path) to 1 step -1
        if Mid(path, i, 1) = "/"
            slash = i
            exit for
        end if
    end for
    return { server: Left(path, slash - 1), username: user, password: pass }
end function

' Cleans a playlist before saving: trims, adds http://, converts hidden Xtream logins.
function IptvNormalize(p as object) as object
    q = {}
    q.Append(p)
    if q.type = "m3u"
        x = IptvXtreamFromUrl(q.url)
        if x <> invalid
            q.type = "xtream"
            q.url = x.server
            q.username = x.username
            q.password = x.password
        else
            q.url = q.url.Trim()
        end if
        return q
    end if
    s = q.url.Trim()
    while Right(s, 1) = "/"
        s = Left(s, Len(s) - 1)
    end while
    if Left(LCase(s), 7) <> "http://" and Left(LCase(s), 8) <> "https://" then s = "http://" + s
    if Right(LCase(s), 15) = "/player_api.php" then s = Left(s, Len(s) - 15)
    if Right(LCase(s), 8) = "/get.php" then s = Left(s, Len(s) - 8)
    q.url = s
    q.username = q.username.Trim()
    q.password = q.password.Trim()
    return q
end function

function IptvXtreamApi(p as object, action as string) as string
    u = p.url + "/player_api.php?username=" + IptvEnc(p.username) + "&password=" + IptvEnc(p.password)
    if action <> "" then u = u + "&action=" + action
    return u
end function

' Roku plays HLS, not raw MPEG-TS, so streams are requested as .m3u8 (Xtream panels serve both).
function IptvXtreamStream(p as object, id as string) as string
    return p.url + "/live/" + IptvEnc(p.username) + "/" + IptvEnc(p.password) + "/" + id + ".m3u8"
end function

' Xtream-style stream URL -> its login and stream id (to fetch the guide for M3U channels too).
function IptvStreamLogin(url as string) as object
    u = url
    q = Instr(1, u, "?")
    if q > 0 then return invalid
    sch = Instr(1, u, "://")
    if sch = 0 then return invalid
    rest = Mid(u, sch + 3)
    parts = rest.Split("/")
    ' host / [live] / user / pass / 123[.ts|.m3u8]
    if parts.Count() = 5 and LCase(parts[1]) = "live"
        user = parts[2] : pass = parts[3] : last = parts[4]
    else if parts.Count() = 4
        user = parts[1] : pass = parts[2] : last = parts[3]
    else
        return invalid
    end if
    dot = Instr(1, last, ".")
    id = last
    if dot > 0 then id = Left(last, dot - 1)
    if id = "" or Str(id.ToInt()).Trim() <> id then return invalid
    return { server: Left(u, sch + 2) + parts[0], username: IptvDec(user), password: IptvDec(pass), id: id }
end function

' The HLS version of a stream URL, and its format for the Roku Video node.
function IptvToHls(url as string) as string
    u = url.Trim()
    q = Instr(1, u, "?")
    path = u
    if q > 0 then path = Left(u, q - 1)
    lp = LCase(path)
    if Right(lp, 5) = ".m3u8" or Right(lp, 4) = ".mpd" or Right(lp, 4) = ".mp4" or Right(lp, 4) = ".mkv" then return u
    x = IptvStreamLogin(u)
    if x <> invalid then return x.server + "/live/" + IptvEnc(x.username) + "/" + IptvEnc(x.password) + "/" + x.id + ".m3u8"
    if Right(lp, 3) = ".ts" then return Left(path, Len(path) - 3) + ".m3u8" + Mid(u, Len(path) + 1)
    return u
end function

function IptvStreamFormat(url as string) as string
    path = url
    q = Instr(1, url, "?")
    if q > 0 then path = Left(url, q - 1)
    lp = LCase(path)
    if Right(lp, 4) = ".mpd" then return "dash"
    if Right(lp, 4) = ".mp4" then return "mp4"
    if Right(lp, 4) = ".mkv" then return "mkv"
    return "hls"
end function

' Xtream categories + streams -> { channels, groups } keeping the provider's group order.
function IptvXtreamChannels(p as object, cats as object, streams as object) as object
    names = {}
    order = []
    seen = {}
    if type(cats) = "roArray"
        for each c in cats
            n = IptvGet(c, "category_name").Trim()
            names[IptvGet(c, "category_id")] = n
            if not seen.DoesExist(n)
                seen[n] = true
                order.Push(n)
            end if
        end for
    end if
    channels = []
    if type(streams) = "roArray"
        for each s in streams
            id = IptvGet(s, "stream_id")
            if id <> ""
                g = names[IptvGet(s, "category_id")]
                if g = invalid or g = "" then g = "Uncategorized"
                if not seen.DoesExist(g)
                    seen[g] = true
                    order.Push(g)
                end if
                num = IptvGet(s, "num").ToInt()
                if num <= 0 then num = channels.Count() + 1
                name = IptvGet(s, "name").Trim()
                if name = "" then name = "Channel " + id
                channels.Push({ key: id, num: num, name: name, group: g, logo: IptvGet(s, "stream_icon"), url: IptvXtreamStream(p, id), epgId: IptvGet(s, "epg_channel_id") })
            end if
        end for
    end if
    return { channels: channels, groups: IptvGroups(channels, order) }
end function

' Groups with channel counts, in the given order (then any others in first-seen order).
function IptvGroups(channels as object, order as object) as object
    counts = {}
    seenOrder = []
    for each c in channels
        if counts.DoesExist(c.group)
            counts[c.group] = counts[c.group] + 1
        else
            counts[c.group] = 1
            seenOrder.Push(c.group)
        end if
    end for
    out = []
    used = {}
    for each g in order
        if counts.DoesExist(g) and not used.DoesExist(g)
            out.Push({ name: g, count: counts[g] })
            used[g] = true
        end if
    end for
    for each g in seenOrder
        if not used.DoesExist(g)
            out.Push({ name: g, count: counts[g] })
            used[g] = true
        end if
    end for
    return out
end function

' Base64 (with UTF-8 text inside) -> string. Xtream sends guide titles this way.
function IptvB64(s as string) as string
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    pow = [1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096]
    bytes = []
    buf = 0
    bits = 0
    for i = 1 to Len(s)
        v = Instr(1, alphabet, Mid(s, i, 1)) - 1
        if v >= 0
            buf = buf * 64 + v
            bits = bits + 6
            if bits >= 8
                bits = bits - 8
                bytes.Push((buf \ pow[bits]) mod 256)
                buf = buf mod pow[bits]
            end if
        end if
    end for
    out = ""
    i = 0
    n = bytes.Count()
    while i < n
        b = bytes[i]
        if b < 128
            out = out + Chr(b)
            i = i + 1
        else if b >= 240 and i + 3 < n
            out = out + Chr((b - 240) * 262144 + (bytes[i + 1] - 128) * 4096 + (bytes[i + 2] - 128) * 64 + (bytes[i + 3] - 128))
            i = i + 4
        else if b >= 224 and i + 2 < n
            out = out + Chr((b - 224) * 4096 + (bytes[i + 1] - 128) * 64 + (bytes[i + 2] - 128))
            i = i + 3
        else if b >= 192 and i + 1 < n
            out = out + Chr((b - 192) * 64 + (bytes[i + 1] - 128))
            i = i + 2
        else
            i = i + 1
        end if
    end while
    return out
end function

' Xtream get_short_epg answer -> [ {title, start, stop} ] (times in epoch seconds).
function IptvShortEpg(json as dynamic) as object
    out = []
    if type(json) <> "roAssociativeArray" then return out
    list = json.epg_listings
    if type(list) <> "roArray" then return out
    for each e in list
        out.Push({ title: IptvB64(IptvGet(e, "title")), start: IptvGet(e, "start_timestamp").ToInt(), stop: IptvGet(e, "stop_timestamp").ToInt() })
    end for
    return out
end function

' Channels whose name contains the query (case-insensitive), at most 300.
function IptvSearch(channels as object, query as string) as object
    out = []
    q = LCase(query.Trim())
    if q = "" then return out
    for each c in channels
        if Instr(1, LCase(c.name), q) > 0
            out.Push(c)
            if out.Count() >= 300 then exit for
        end if
    end for
    return out
end function
