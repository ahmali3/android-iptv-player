' IPTV Player for Roku: groups on the left, channels in the middle, now/next on the right,
' and a full-screen player where up/down change channel.

sub init()
    m.groups = m.top.findNode("groups")
    m.channels = m.top.findNode("channels")
    m.video = m.top.findNode("video")
    m.osd = m.top.findNode("osd")
    m.perror = m.top.findNode("perror")
    m.status = m.top.findNode("status")
    m.empty = m.top.findNode("empty")
    m.listTitle = m.top.findNode("listTitle")
    m.epgTimer = m.top.findNode("epgTimer")
    m.osdTimer = m.top.findNode("osdTimer")
    m.retryTimer = m.top.findNode("retryTimer")

    m.groups.observeField("itemSelected", "onGroupSelected")
    m.channels.observeField("itemSelected", "onChannelSelected")
    m.channels.observeField("itemFocused", "onChannelFocused")
    m.video.observeField("state", "onVideoState")
    m.epgTimer.observeField("fire", "loadFocusedEpg")
    m.osdTimer.observeField("fire", "hideOsd")
    m.retryTimer.observeField("fire", "retryPlay")

    m.video.setCertificatesFile("common:/certs/ca-bundle.crt")
    m.video.initClientCertificates()

    m.reg = CreateObject("roRegistrySection", "iptv")
    m.playlists = regGet("playlists", [])
    m.activeId = regGet("active", "")
    m.channelsAll = []
    m.groupList = []
    m.groupKeys = []
    m.shown = []
    m.listKey = ""
    m.search = ""
    m.fav = {}
    m.recent = []
    m.epg = {}
    m.playIndex = 0
    m.playList = []
    m.retries = 0
    m.form = invalid

    if m.playlists.Count() = 0
        startAddPlaylist()
    else
        loadActive()
    end if
end sub

' ------------------------------------------------------------ storage (registry)

function regGet(key as string, fallback as dynamic) as dynamic
    if not m.reg.Exists(key) then return fallback
    v = ParseJson(m.reg.Read(key))
    if v = invalid then return fallback
    return v
end function

sub regSet(key as string, value as dynamic)
    m.reg.Write(key, FormatJson(value))
    m.reg.Flush()
end sub

function activePlaylist() as dynamic
    for each p in m.playlists
        if p.id = m.activeId then return p
    end for
    if m.playlists.Count() > 0 then return m.playlists[0]
    return invalid
end function

' ------------------------------------------------------------ loading

sub loadActive()
    p = activePlaylist()
    if p = invalid
        startAddPlaylist()
        return
    end if
    m.activeId = p.id
    regSet("active", m.activeId)
    m.top.findNode("title").text = p.name
    m.fav = regGet("fav_" + p.id, {})
    m.recent = regGet("recent_" + p.id, [])
    m.epg = {}
    setStatus("Loading channels...")
    m.loader = CreateObject("roSGNode", "NetTask")
    m.loader.observeField("response", "onLoaded")
    m.loader.request = { kind: "load", playlist: p }
    m.loader.control = "RUN"
end sub

sub onLoaded()
    r = m.loader.response
    if not r.ok
        setStatus(r.error)
        m.channelsAll = []
        m.groupList = []
        fillGroups()
        showChannels("all")
        m.groups.setFocus(true)
        return
    end if
    setStatus("")
    m.channelsAll = r.channels
    m.groupList = r.groups
    fillGroups()
    first = "all"
    if m.fav.Count() > 0
        first = "fav"
    else if m.groupList.Count() > 0
        first = "g:" + m.groupList[0].name
    end if
    showChannels(first)
    m.channels.setFocus(true)
end sub

sub setStatus(text as string)
    m.status.text = text
end sub

' ------------------------------------------------------------ groups + channels

sub fillGroups()
    root = CreateObject("roSGNode", "ContentNode")
    m.groupKeys = []
    addItem(root, "Search...", "search")
    addItem(root, "Favorites (" + Str(m.fav.Count()).Trim() + ")", "fav")
    addItem(root, "Recent", "recent")
    addItem(root, "All channels (" + Str(m.channelsAll.Count()).Trim() + ")", "all")
    for each g in m.groupList
        addItem(root, g.name + "  (" + Str(g.count).Trim() + ")", "g:" + g.name)
    end for
    addItem(root, "Playlists...", "playlists")
    m.groups.content = root
end sub

sub addItem(root as object, text as string, key as string)
    n = root.createChild("ContentNode")
    n.title = text
    m.groupKeys.Push(key)
end sub

function channelsFor(key as string) as object
    if key = "all" then return m.channelsAll
    out = []
    if key = "fav"
        for each c in m.channelsAll
            if m.fav.DoesExist(c.key) then out.Push(c)
        end for
        return out
    end if
    if key = "recent"
        byKey = {}
        for each c in m.channelsAll
            byKey[c.key] = c
        end for
        for each k in m.recent
            if byKey.DoesExist(k) then out.Push(byKey[k])
        end for
        return out
    end if
    if key = "search" then return IptvSearch(m.channelsAll, m.search)
    name = Mid(key, 3)
    for each c in m.channelsAll
        if c.group = name then out.Push(c)
    end for
    return out
end function

sub showChannels(key as string)
    m.listKey = key
    m.shown = channelsFor(key)
    root = CreateObject("roSGNode", "ContentNode")
    for each c in m.shown
        n = root.createChild("ContentNode")
        star = ""
        if m.fav.DoesExist(c.key) then star = "  *"
        n.title = Str(c.num).Trim() + "   " + c.name + star
    end for
    m.channels.content = root
    title = ""
    if key = "fav"
        title = "FAVORITES"
    else if key = "recent"
        title = "RECENT"
    else if key = "all"
        title = "ALL CHANNELS"
    else if key = "search"
        title = "RESULTS FOR " + Chr(34) + UCase(m.search) + Chr(34)
    else
        title = UCase(Mid(key, 3))
    end if
    m.listTitle.text = title + "  -  " + Str(m.shown.Count()).Trim()
    m.empty.visible = m.shown.Count() = 0
    if key = "fav"
        m.empty.text = "No favorites yet. Press * on a channel to add it."
    else
        m.empty.text = "Nothing here."
    end if
    if m.shown.Count() > 0 then onChannelFocused()
end sub

sub onGroupSelected()
    key = m.groupKeys[m.groups.itemSelected]
    if key = "search"
        askText("Search channels", m.search, false, "search")
    else if key = "playlists"
        showPlaylists()
    else
        showChannels(key)
        if m.shown.Count() > 0 then m.channels.setFocus(true)
    end if
end sub

sub onChannelSelected()
    play(m.shown, m.channels.itemSelected)
end sub

sub onChannelFocused()
    if m.shown.Count() = 0 then return
    i = m.channels.itemFocused
    if i < 0 or i >= m.shown.Count() then i = 0
    c = m.shown[i]
    m.top.findNode("infoName").text = c.name
    showEpgFor(c)
    m.epgTimer.control = "stop"
    m.epgTimer.control = "start"
end sub

' ------------------------------------------------------------ guide (now / next)

sub loadFocusedEpg()
    if m.shown.Count() = 0 then return
    c = m.shown[m.channels.itemFocused]
    if c = invalid then return
    cached = m.epg[c.key]
    now = CreateObject("roDateTime").AsSeconds()
    if cached <> invalid
        if now - cached.at < 600 then return
    end if
    m.epgTask = CreateObject("roSGNode", "NetTask")
    m.epgTask.observeField("response", "onEpg")
    m.epgTask.request = { kind: "epg", playlist: activePlaylist(), channel: c }
    m.epgTask.control = "RUN"
end sub

sub onEpg()
    r = m.epgTask.response
    m.epg[r.key] = { at: CreateObject("roDateTime").AsSeconds(), list: r.list }
    if m.video.visible
        if m.playList.Count() > 0 and m.playList[m.playIndex].key = r.key then updateOsd()
    else if m.shown.Count() > 0
        c = m.shown[m.channels.itemFocused]
        if c <> invalid and c.key = r.key then showEpgFor(c)
    end if
end sub

function nowNext(c as object) as object
    out = { cur: invalid, upcoming: invalid }
    e = m.epg[c.key]
    if e = invalid then return out
    t = CreateObject("roDateTime").AsSeconds()
    for each p in e.list
        if p.start <= t and p.stop > t
            out.cur = p
        else if p.start > t and out.upcoming = invalid
            out.upcoming = p
        end if
    end for
    return out
end function

function hhmm(seconds as integer) as string
    d = CreateObject("roDateTime")
    d.FromSeconds(seconds)
    d.ToLocalTime()
    h = d.GetHours()
    mi = d.GetMinutes()
    hs = Str(h).Trim()
    ms = Str(mi).Trim()
    if h < 10 then hs = "0" + hs
    if mi < 10 then ms = "0" + ms
    return hs + ":" + ms
end function

sub showEpgFor(c as object)
    nn = nowNext(c)
    nowT = m.top.findNode("infoNowTime")
    nowL = m.top.findNode("infoNow")
    nextT = m.top.findNode("infoNextTime")
    nextL = m.top.findNode("infoNext")
    if nn.cur <> invalid
        nowT.text = "NOW  " + hhmm(nn.cur.start) + " - " + hhmm(nn.cur.stop)
        nowL.text = nn.cur.title
    else
        nowT.text = ""
        if m.epg.DoesExist(c.key)
            nowL.text = "No guide information"
        else
            nowL.text = ""
        end if
    end if
    if nn.upcoming <> invalid
        nextT.text = "NEXT  " + hhmm(nn.upcoming.start)
        nextL.text = nn.upcoming.title
    else
        nextT.text = ""
        nextL.text = ""
    end if
end sub

' ------------------------------------------------------------ favorites + recent

sub toggleFavorite(c as object)
    p = activePlaylist()
    if p = invalid then return
    if m.fav.DoesExist(c.key)
        m.fav.Delete(c.key)
        setStatus("Removed " + c.name + " from favorites")
    else
        m.fav[c.key] = true
        setStatus("Added " + c.name + " to favorites")
    end if
    regSet("fav_" + p.id, m.fav)
end sub

sub addRecent(c as object)
    p = activePlaylist()
    if p = invalid then return
    out = [c.key]
    for each k in m.recent
        if k <> c.key and out.Count() < 30 then out.Push(k)
    end for
    m.recent = out
    regSet("recent_" + p.id, m.recent)
end sub

' ------------------------------------------------------------ player

sub play(list as object, index as integer)
    if index < 0 or index >= list.Count() then return
    m.playList = list
    m.playIndex = index
    m.retries = 0
    c = list[index]
    startStream(c)
    m.home = m.top.findNode("home")
    m.home.visible = false
    m.video.visible = true
    m.perror.visible = false
    m.top.setFocus(true)
    addRecent(c)
    updateOsd()
    showOsd()
    if not m.epg.DoesExist(c.key)
        m.epgTask = CreateObject("roSGNode", "NetTask")
        m.epgTask.observeField("response", "onEpg")
        m.epgTask.request = { kind: "epg", playlist: activePlaylist(), channel: c }
        m.epgTask.control = "RUN"
    end if
end sub

sub startStream(c as object)
    url = IptvToHls(c.url)
    content = CreateObject("roSGNode", "ContentNode")
    content.url = url
    content.streamFormat = IptvStreamFormat(url)
    content.title = c.name
    content.live = true
    content.HttpHeaders = ["User-Agent:VLC/3.0.21 LibVLC/3.0.21"]
    m.video.content = content
    m.video.control = "play"
end sub

sub zap(stepBy as integer)
    n = m.playList.Count()
    if n = 0 then return
    i = ((m.playIndex + stepBy) mod n + n) mod n
    play(m.playList, i)
end sub

sub closePlayer()
    m.video.control = "stop"
    m.video.visible = false
    m.osd.visible = false
    m.perror.visible = false
    m.top.findNode("home").visible = true
    ' refresh stars / recent counts, keep the list and position
    focusKey = m.listKey
    pos = m.channels.itemFocused
    fillGroups()
    showChannels(focusKey)
    if pos >= 0 and pos < m.shown.Count() then m.channels.jumpToItem = pos
    m.channels.setFocus(true)
end sub

sub onVideoState()
    st = m.video.state
    if st = "playing"
        m.perror.visible = false
        m.retries = 0
    else if st = "error"
        if m.retries < 3
            m.retries = m.retries + 1
            m.perror.text = "Reconnecting... (" + Str(m.retries).Trim() + "/3)"
            m.perror.visible = true
            m.retryTimer.control = "start"
        else
            m.perror.text = "This channel won't play right now. Your provider may be down, or your connection limit is reached. Press up or down for another channel."
            m.perror.visible = true
        end if
    end if
end sub

sub retryPlay()
    if m.video.visible and m.playList.Count() > 0 then startStream(m.playList[m.playIndex])
end sub

sub updateOsd()
    c = m.playList[m.playIndex]
    m.top.findNode("osdNum").text = Str(c.num).Trim()
    m.top.findNode("osdName").text = c.name
    nn = nowNext(c)
    if nn.cur <> invalid
        m.top.findNode("osdNow").text = hhmm(nn.cur.start) + " - " + hhmm(nn.cur.stop) + "   " + nn.cur.title
    else
        m.top.findNode("osdNow").text = ""
    end if
    if nn.upcoming <> invalid
        m.top.findNode("osdNext").text = "Next  " + hhmm(nn.upcoming.start) + "   " + nn.upcoming.title
    else
        m.top.findNode("osdNext").text = ""
    end if
end sub

sub showOsd()
    m.osd.visible = true
    m.osdTimer.control = "stop"
    m.osdTimer.control = "start"
end sub

sub hideOsd()
    m.osd.visible = false
end sub

' ------------------------------------------------------------ playlists

sub showPlaylists()
    d = CreateObject("roSGNode", "StandardMessageDialog")
    d.title = "Playlists"
    names = []
    for each p in m.playlists
        mark = ""
        if p.id = m.activeId then mark = "  (current)"
        names.Push(p.name + mark)
    end for
    names.Push("Add playlist")
    names.Push("Delete current playlist")
    names.Push("Close")
    d.buttons = names
    d.observeField("buttonSelected", "onPlaylistsButton")
    m.top.dialog = d
end sub

sub onPlaylistsButton()
    i = m.top.dialog.buttonSelected
    n = m.playlists.Count()
    m.top.dialog.close = true
    if i < n
        m.activeId = m.playlists[i].id
        loadActive()
    else if i = n
        startAddPlaylist()
    else if i = n + 1
        confirmDelete()
    end if
end sub

sub confirmDelete()
    p = activePlaylist()
    if p = invalid then return
    d = CreateObject("roSGNode", "StandardMessageDialog")
    d.title = "Delete " + p.name + "?"
    d.message = ["Its favorites will be removed from this Roku."]
    d.buttons = ["Delete", "Cancel"]
    d.observeField("buttonSelected", "onDeleteButton")
    m.top.dialog = d
end sub

sub onDeleteButton()
    i = m.top.dialog.buttonSelected
    m.top.dialog.close = true
    if i <> 0 then return
    p = activePlaylist()
    keep = []
    for each x in m.playlists
        if x.id <> p.id then keep.Push(x)
    end for
    m.playlists = keep
    regSet("playlists", m.playlists)
    m.reg.Delete("fav_" + p.id)
    m.reg.Delete("recent_" + p.id)
    m.reg.Flush()
    m.activeId = ""
    if m.playlists.Count() = 0
        m.channelsAll = []
        startAddPlaylist()
    else
        loadActive()
    end if
end sub

' Adding a playlist: choose the type, then one on-screen keyboard per field.
sub startAddPlaylist()
    d = CreateObject("roSGNode", "StandardMessageDialog")
    if m.playlists.Count() = 0
        d.title = "Welcome to IPTV Player"
        d.message = ["Add your provider's Xtream Codes login or an M3U playlist link to start watching."]
        d.buttons = ["Xtream Codes login", "M3U link"]
    else
        d.title = "Add playlist"
        d.buttons = ["Xtream Codes login", "M3U link", "Cancel"]
    end if
    d.observeField("buttonSelected", "onTypeChosen")
    m.top.dialog = d
end sub

sub onTypeChosen()
    i = m.top.dialog.buttonSelected
    m.top.dialog.close = true
    if i = 0
        m.form = { type: "xtream", fields: ["url", "username", "password", "name"], idx: 0, values: {} }
    else if i = 1
        m.form = { type: "m3u", fields: ["url", "name"], idx: 0, values: {} }
    else
        m.form = invalid
        return
    end if
    askField()
end sub

sub askField()
    f = m.form.fields[m.form.idx]
    titles = { url: "Server URL (e.g. http://provider.tv:8080)", username: "Username", password: "Password", name: "Name for this playlist" }
    if m.form.type = "m3u" and f = "url" then titles.url = "Playlist link (http://...)"
    v = ""
    if m.form.values.DoesExist(f) then v = m.form.values[f]
    if f = "name" and v = "" then v = "My IPTV"
    askText(titles[f], v, f = "password", "form")
end sub

' One on-screen keyboard; `purpose` decides what happens with the text.
sub askText(title as string, value as string, secret as boolean, purpose as string)
    k = CreateObject("roSGNode", "StandardKeyboardDialog")
    k.title = title
    k.text = value
    k.buttons = ["OK", "Cancel"]
    if secret then k.textEditBox.secureMode = true
    m.askPurpose = purpose
    k.observeField("buttonSelected", "onAskButton")
    m.top.dialog = k
end sub

sub onAskButton()
    d = m.top.dialog
    i = d.buttonSelected
    text = d.text
    d.close = true
    if m.askPurpose = "search"
        if i = 0
            m.search = text
            showChannels("search")
            if m.shown.Count() > 0 then m.channels.setFocus(true)
        end if
        return
    end if
    if i <> 0
        m.form = invalid
        if m.playlists.Count() = 0 then startAddPlaylist()
        return
    end if
    f = m.form.fields[m.form.idx]
    if text.Trim() = "" and f <> "name"
        askField()
        return
    end if
    m.form.values[f] = text
    m.form.idx = m.form.idx + 1
    if m.form.idx < m.form.fields.Count()
        askField()
        return
    end if
    v = m.form.values
    name = v.name.Trim()
    if name = "" then name = "My IPTV"
    p = { id: Str(CreateObject("roDateTime").AsSeconds()).Trim(), name: name, type: m.form.type, url: v.url, username: "", password: "" }
    if m.form.type = "xtream"
        p.username = v.username
        p.password = v.password
    end if
    wasM3u = p.type = "m3u"
    p = IptvNormalize(p)
    m.playlists.Push(p)
    regSet("playlists", m.playlists)
    m.activeId = p.id
    m.form = invalid
    loadActive()
    if wasM3u and p.type = "xtream" then setStatus("That link is an Xtream login, so it was added as Xtream. Loading...")
end sub

' ------------------------------------------------------------ remote

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.video.visible
        if key = "up"
            zap(1)
        else if key = "down"
            zap(-1)
        else if key = "OK" or key = "info"
            updateOsd()
            showOsd()
        else if key = "play"
            if m.video.state = "paused"
                m.video.control = "resume"
            else
                m.video.control = "pause"
            end if
        else if key = "options"
            toggleFavorite(m.playList[m.playIndex])
        else if key = "back"
            closePlayer()
        else
            return false
        end if
        return true
    end if
    if key = "right" and m.groups.hasFocus() and m.shown.Count() > 0
        m.channels.setFocus(true)
        return true
    end if
    if (key = "left" or key = "back") and m.channels.hasFocus()
        m.groups.setFocus(true)
        return true
    end if
    if key = "options" and m.channels.hasFocus() and m.shown.Count() > 0
        toggleFavorite(m.shown[m.channels.itemFocused])
        pos = m.channels.itemFocused
        fillGroups()
        showChannels(m.listKey)
        if pos < m.shown.Count() then m.channels.jumpToItem = pos
        m.channels.setFocus(true)
        return true
    end if
    return false
end function
