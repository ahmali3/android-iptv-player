sub init()
    m.top.functionName = "run"
end sub

sub run()
    r = m.top.request
    if r.kind = "load"
        m.top.response = loadPlaylist(r.playlist)
    else if r.kind = "epg"
        m.top.response = loadEpg(r)
    end if
end sub

function httpGet(url as string) as object
    t = CreateObject("roUrlTransfer")
    t.SetUrl(url)
    t.SetCertificatesFile("common:/certs/ca-bundle.crt")
    t.InitClientCertificates()
    t.AddHeader("User-Agent", "VLC/3.0.21 LibVLC/3.0.21")
    t.EnableEncodings(true)
    t.RetainBodyOnError(true)
    port = CreateObject("roMessagePort")
    t.SetMessagePort(port)
    if t.AsyncGetToString()
        msg = wait(45000, port)
        if type(msg) = "roUrlEvent"
            return { code: msg.GetResponseCode(), body: msg.GetString(), reason: msg.GetFailureReason() }
        end if
        t.AsyncCancel()
        return { code: -1, body: "", reason: "Timed out" }
    end if
    return { code: -1, body: "", reason: "Could not start the request" }
end function

function getJson(url as string) as object
    r = httpGet(url)
    if r.code <> 200 then return { ok: false, error: describe(r) }
    j = ParseJson(r.body)
    if j = invalid then return { ok: false, error: "Unexpected reply from the server" }
    return { ok: true, data: j }
end function

function describe(r as object) as string
    if r.code > 0 then return "Server answered HTTP " + Str(r.code).Trim()
    if r.reason <> invalid and r.reason <> "" then return "Network error: " + r.reason
    return "Network error"
end function

function loadPlaylist(p as object) as object
    if p.type = "xtream"
        acc = getJson(IptvXtreamApi(p, ""))
        if not acc.ok then return acc
        info = acc.data.user_info
        if type(info) <> "roAssociativeArray" then return { ok: false, error: "Login failed - check the server, username and password" }
        if IptvGet(info, "auth") <> "1"
            st = IptvGet(info, "status")
            if st <> "" then return { ok: false, error: "Login refused (" + st + ")" }
            return { ok: false, error: "Login failed - check the server, username and password" }
        end if
        cats = getJson(IptvXtreamApi(p, "get_live_categories"))
        if not cats.ok then return cats
        streams = getJson(IptvXtreamApi(p, "get_live_streams"))
        if not streams.ok then return streams
        x = IptvXtreamChannels(p, cats.data, streams.data)
        if x.channels.Count() = 0 then return { ok: false, error: "The provider returned no live channels" }
        return { ok: true, channels: x.channels, groups: x.groups, expires: IptvGet(info, "exp_date").ToInt() }
    end if
    r = httpGet(p.url)
    if r.code <> 200 then return { ok: false, error: describe(r) }
    m3u = IptvParseM3U(r.body)
    if m3u.channels.Count() = 0 then return { ok: false, error: "No channels found in this playlist" }
    return { ok: true, channels: m3u.channels, groups: IptvGroups(m3u.channels, []), expires: 0 }
end function

' Now / next for one channel, from the provider's short guide.
function loadEpg(r as object) as object
    login = invalid
    id = ""
    if r.playlist.type = "xtream"
        login = r.playlist
        id = r.channel.key
    else
        x = IptvStreamLogin(r.channel.url)
        if x <> invalid
            login = { url: x.server, username: x.username, password: x.password }
            id = x.id
        end if
    end if
    if login = invalid then return { key: r.channel.key, list: [] }
    j = getJson(IptvXtreamApi(login, "get_short_epg") + "&stream_id=" + id + "&limit=4")
    if not j.ok then return { key: r.channel.key, list: [] }
    return { key: r.channel.key, list: IptvShortEpg(j.data) }
end function
