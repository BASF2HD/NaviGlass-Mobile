import * as flow from "./renderer.js";
import { request, listen } from "./bridge.js";
import { icon } from "./icons.js";
const $ = (id) => document.getElementById(id),
  native = Boolean(window.nativeBridge);
let albums = [],
  albumIndex = 0,
  tracks = [],
  state = {},
  lastTrack = "",
  seekHeld = false,
  panelMode = "",
  textureEpoch = 0,
  toastTimer,
  searchTimer;
let activeView = "albums",
  viewEpoch = 0,
  demoReady,
  lastError = "";
let libraryAlbums = [],
  browseTracks = [],
  browseMode = "albums",
  menuAnchor = "albums",
  collectionRevision = 0;
let snapBackTimer,
  drawerSelection = -1;
let remoteSource = false,
  drawerValue = "",
  drawerRevision = 0,
  drawerStarred = false;
let librarySummary = {}, libraryArtists = [], libraryById = new Map(), artistYears = new Map();
let sourceKey = null, cacheGeneration = 0, playbackBrowseMode = "";
const responseCache = new Map(), pendingResponses = new Map(), collectionMemory = new Map();
const groupSettings = { artists: true, playlists: false };
const collectionPositions = new Map();
function invalidateCollections() { cacheGeneration++; responseCache.clear(); pendingResponses.clear(); collectionMemory.clear(); }
function rememberCollection() {
  if (albums.length) collectionPositions.set(browseMode, albums[albumIndex]?.track?.id || albums[albumIndex]?.id);
  if (sourceKey) try {
    localStorage.setItem("naviglass-collection:" + sourceKey, JSON.stringify({ selection, groups: groupSettings, positions: [...collectionPositions], view: ["albums", "songs", "artists", "playlists"].includes(activeView) ? activeView : browseMode }));
  } catch {}
}
function collectionKey(nav) { return JSON.stringify([nav, selection[nav], nav === "songs" ? selection.grouped : groupSettings[nav], sortSettings[nav], nav === "songs" ? songOffset : 0]); }
function cachedResponse(action, data) {
  const item = responseCache.get(JSON.stringify([action, data]));
  return item && Date.now() - item.time < 300000 ? item.value : null;
}
const selection = { albums: "all", songs: "all", grouped: false, artists: "", playlists: "", more: "" };
function menuCount(name) {
  if (name === "albums") return selection.albums === "favourite" ? libraryAlbums.filter(a => a.starred).length : libraryAlbums.length;
  if (name === "songs") return selection.songs === "favourites" ? librarySummary.favouriteSongs ?? browseTracks.filter(t => t.favourite).length : librarySummary.songs ?? libraryAlbums.reduce((n,a) => n + Number(a.count || 0),0);
  if (name === "artists") return librarySummary.artists ?? new Set(libraryAlbums.map(a => a.artist).filter(Boolean)).size;
  if (name === "playlists") return librarySummary.playlists ?? 0;
  if (name === "more") return selection.more === "favourites" ? librarySummary.favouriteSongs ?? 0 : selection.more === "browser" ? radioResultCount : selection.more === "starter" ? starterCount : librarySummary.radios ?? 0;
  return null;
}
let starterCount = 0, radioResultCount = 0;
function updateMenuCount(count = menuCount(activeView)) {
  const value = $("content").querySelector(".menuCount");
  if (value && count != null) value.textContent = Number(count).toLocaleString();
}
async function ensureDemo() {
  if (!native) {
    demoReady ||= import("./demo.js").then(() => call("ready"));
    await demoReady;
  }
}
let sortSettings = {
  albums: "year-desc",
  songs: "year-desc",
  artists: "year-desc",
  playlists: "year-desc",
};
try {
  const saved = JSON.parse(
    localStorage.getItem("naviglass-browse-sort") || "{}",
  );
  for (const key of Object.keys(sortSettings))
    if (["title", "year-asc", "year-desc"].includes(saved[key]))
      sortSettings[key] = saved[key];
} catch {}
function sortedEntries(entries, mode) {
  const sort = sortSettings[mode] || "title";
  return [...entries].sort((a, b) => {
    const alpha = String(a.title || a.name || "").localeCompare(
      String(b.title || b.name || ""),
      undefined,
      { numeric: true, sensitivity: "base" },
    );
    if (sort === "title") return alpha;
    const ay = Number(a.year) || 0,
      by = Number(b.year) || 0;
    if (!ay || !by) return ay === by ? alpha : ay ? -1 : 1;
    return (sort === "year-asc" ? ay - by : by - ay) || alpha;
  });
}
function sortOptions(body, mode, onChange) {
  const section = document.createElement("div");
  section.className = "sortOptions";
  section.setAttribute("aria-label", "Sort " + mode);
  for (const [value, title, detail] of [
    ["year-desc", "Year: High to Low", "Newest first"],
    ["year-asc", "Year: Low to High", "Oldest first"],
    ["title", "Title (A-Z)", "Sort by name"],
  ]) {
    const option = menuItem(section, title, detail, () => {
      sortSettings[mode] = value;
      collectionMemory.delete(mode);
      try {
        localStorage.setItem(
          "naviglass-browse-sort",
          JSON.stringify(sortSettings),
        );
      } catch {}
      if (browseMode === mode && albums.length) {
        const key = albums[albumIndex]?.track?.id || albums[albumIndex]?.id;
        const entries = sortedEntries(albums, mode);
        setCollection(
          entries,
          mode,
          entries.flatMap(e => e.groupTracks || (e.track ? [e.track] : [])),
        );
        selectAlbum(
          Math.max(
            0,
            entries.findIndex((e) => (e.track?.id || e.id) === key),
          ),
          false,
        );
      } else {
        section
          .querySelectorAll("button")
          .forEach((b) =>
            markSelected(b, b.dataset.sort === value),
          );
      }
      onChange?.();
    });
    option.dataset.sort = value;
    markSelected(option, sortSettings[mode] === value);
  }
  body.append(section);
}
const loaded = new Map();
function time(ms) {
  const seconds = Math.floor(Math.max(0, ms || 0) / 1000);
  return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
}
function button(text, action, className = "") {
  const b = document.createElement("button");
  b.textContent = text;
  b.className = className;
  b.onclick = action;
  return b;
}
function toast(text) {
  $("toast").textContent = text;
  $("toast").hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($("toast").hidden = true), 5000);
}
async function call(action, data = {}) {
  const cacheable = ["tracks", "artistAlbums", "playlists", "radioStations", "radioSaved"].includes(action);
  const key = JSON.stringify([action, data]), generation = cacheGeneration;
  if (cacheable) {
    const saved = cachedResponse(action, data);
    if (saved) return saved;
    if (pendingResponses.has(key)) return pendingResponses.get(key);
  }
  const operation = (async () => {
    try {
      const result = await request(action, data);
      if (generation !== cacheGeneration) return null;
      if (["favourite", "albumFavourite", "addPlaylist", "createPlaylist", "radioSave"].includes(action)) invalidateCollections();
      if (result?.summary) { librarySummary = result.summary; updateMenuCount(); }
      if (cacheable && result) {
        const value = { ...result }; delete value.summary;
        responseCache.set(key, { time: Date.now(), value });
        // Bound retained song lists, independently of library size.
        let total = [...responseCache.values()].reduce((n,r) => n + (r.value.tracks?.length || 0), 0);
        while (responseCache.size > 64 || total > 20000) {
          const oldest = responseCache.keys().next().value;
          total -= responseCache.get(oldest).value.tracks?.length || 0;
          responseCache.delete(oldest);
        }
      }
      return result;
    } catch (e) { if (generation === cacheGeneration && e.message !== "Collection changed") toast(e.message); return null; }
  })();
  if (cacheable) pendingResponses.set(key, operation);
  try { return await operation; }
  finally { if (pendingResponses.get(key) === operation) pendingResponses.delete(key); }
}
// Consume outside drawer taps instead of passing them to Cover Flow beneath it.
const drawerBackdrop = document.createElement("button");
drawerBackdrop.type = "button";
drawerBackdrop.className = "drawerBackdrop";
drawerBackdrop.setAttribute("aria-label", "Close song drawer");
drawerBackdrop.tabIndex = -1;
drawerBackdrop.hidden = true;
document.body.append(drawerBackdrop);
// A click generated by the cover-opening gesture must not dismiss the new sheet.
let backdropPress = false;
drawerBackdrop.onpointerdown = () => { backdropPress = true; };
drawerBackdrop.onpointercancel = () => { backdropPress = false; };
drawerBackdrop.onclick = () => { if (!backdropPress) return; backdropPress = false; closeSongMenu(); window.closePanel(); };
$("panel").addEventListener("close", () => { drawerBackdrop.hidden = !($("panel").open && $("panel").classList.contains("songDrawer")); });
function openPanel(title, mode) {
  closeSongMenu();
  closeBrowseMenu();
  $("panelTitle").textContent = title;
  $("panelBody").replaceChildren();
  panelMode = mode;
  const songDrawer = [
    "album",
    "artist",
    "playlist",
    "serverPlaylist",
    "favourites",
  ].includes(mode);
  if ($("panel").open) $("panel").close();
  $("panel").classList.toggle("songDrawer", songDrawer);
  $("drawerFavourite").hidden = mode !== "album";
  $("drawerCount").hidden = !songDrawer;
  $("drawerCount").textContent = "…";
  if (songDrawer) {
    $("panel").show();
    drawerBackdrop.hidden = false;
    backdropPress = false;
    positionDrawer();
    if (!matchMedia("(prefers-reduced-motion: reduce)").matches)
      $("panel").animate(
        [
          {
            transform:
              innerHeight > innerWidth
                ? "translateY(100%)"
                : "translateY(12px)",
            opacity: 0,
          },
          { transform: "none", opacity: 1 },
        ],
        { duration: 220, easing: "cubic-bezier(.2,.7,.2,1)" },
      );
  } else {
    drawerBackdrop.hidden = true;
    $("panel").removeAttribute("style");
    $("panel").showModal();
  }
}
window.closePanel = () => {
  if (closeSongMenu()) return true;
  if (!$("panel").open) {
    if (!$("content").hidden) {
      closeBrowseMenu();
      return true;
    }
    return false;
  }
  $("panel").close();
  drawerBackdrop.hidden = true;
  panelMode = "";
  drawerRevision++;
  scheduleReturnToPlaying();
  return true;
};
$("close").onclick = () => {
  closeSongMenu();
  window.closePanel();
};
$("panel").addEventListener("click", (event) => {
  if (event.target === $("panel") && !$("panel").classList.contains("songDrawer")) window.closePanel();
});
$("panel").addEventListener("cancel", () => {
  panelMode = "";
});
function coverURL(album) {
  return (
    album.demoCover || "/art/" + encodeURIComponent(album.coverId) + ".jpg"
  );
}
async function loadWindow(index) {
  const epoch = ++textureEpoch,
    radius = flow.getSideCount() + 4,
    start = Math.max(0, index - radius),
    end = Math.min(albums.length - 1, index + radius);
  const needed = new Set();
  for (let i = start; i <= end; i++) needed.add(i);
  const queue = [
    index,
    ...Array.from(needed)
      .filter((i) => i !== index)
      .sort((a, b) => Math.abs(a - index) - Math.abs(b - index)),
  ];
  async function consume() {
    while (queue.length && epoch === textureEpoch) {
      const i = queue.shift();
      if (loaded.has(i)) continue;
      const url = coverURL(albums[i]);
      const texture = await flow.loadTexture(url);
      if (epoch !== textureEpoch) continue;
      loaded.set(i, { url, texture });
      flow.setTextureAtIndex(i, texture);
    }
  }
  await Promise.all([consume(), consume(), consume()]);
  setTimeout(() => {
    if (epoch !== textureEpoch) return;
    for (const i of loaded.keys())
      if (!needed.has(i)) {
        flow.setTextureAtIndex(i, flow.getDefaultTexture());
        loaded.delete(i);
      }
    flow.retainTextures(
      new Set(Array.from(loaded.values(), (entry) => entry.url)),
    );
  }, 550);
}
function selectAlbum(index, animate = true) {
  if (!albums.length) return;
  const nextIndex = Math.max(0, Math.min(albums.length - 1, index));
  if (animate && nextIndex === albumIndex) return;
  albumIndex = nextIndex;
  rememberCollection();
  $("browseStrip").value = albumIndex;
  animate ? flow.navigateTo(albumIndex) : flow.jumpTo(albumIndex);
  caption();
  loadWindow(albumIndex);
  scheduleReturnToPlaying();
}
function caption() {
  const album = albums[albumIndex];
  if (!album) return;
  const playing = album.track
    ? state.track?.id === album.track.id
    : state.track?.albumId === album.id;
  $("title").textContent = playing ? state.track.title : album.title;
  const song = album.track || (playing ? state.track : null);
  $("subtitle").textContent = [
    song?.album || album.artist,
    song?.year || album.year,
  ]
    .filter(Boolean)
    .join(" | ");
  $("albumPosition").textContent = albumIndex + 1 + " / " + albums.length;
  const bounds = flow.getActiveCoverBounds();
  if (bounds) {
    document.documentElement.style.setProperty(
      "--chrome-width",
      Math.min(bounds.width, innerWidth - 20) + "px",
    );
    fitChrome(bounds);
    const landscape = innerWidth > innerHeight && innerHeight < 600;
    const titleSize = landscape
      ? Math.max(22, Math.min(54, bounds.width * 0.14))
      : Math.max(26, Math.min(54, bounds.width * 0.115));
    const subtitleSize = landscape
      ? Math.max(16, Math.min(34, bounds.width * 0.09))
      : Math.max(18, Math.min(34, bounds.width * 0.07));
    $("caption").style.setProperty("--title-size", titleSize + "px");
    $("caption").style.setProperty("--subtitle-size", subtitleSize + "px");
    $("caption").style.width =
      Math.min(bounds.width, $("scene").clientWidth - 32) + "px";
    const panel = $("caption"),
      controlsTop = $("controls").getBoundingClientRect().top;
    const captionGap = landscape ? 4 : 8;
    const minimumTop = bounds.bottom + captionGap;
    const available = document.body.classList.contains("immersive")
      ? innerHeight - minimumTop - 32
      : Math.max(36, controlsTop - minimumTop - captionGap);
    if (panel.offsetHeight > available) {
      const scale = available / panel.offsetHeight;
      panel.style.setProperty(
        "--title-size",
        Math.max(landscape ? 22 : 18, titleSize * scale) + "px",
      );
      panel.style.setProperty(
        "--subtitle-size",
        Math.max(landscape ? 16 : 14, subtitleSize * scale) + "px",
      );
    }
    const nearCover =
      document.body.classList.contains("immersive") ||
      (innerWidth < 700 && innerHeight > innerWidth);
    panel.style.top =
      (nearCover
        ? minimumTop
        : Math.max(minimumTop, controlsTop - panel.offsetHeight - captionGap)) +
      "px";
  }
}
flow.initScene($("scene"));
function positionDrawer() {
  if (!$("panel").classList.contains("songDrawer")) return;
  closeSongMenu();
  const portrait = innerHeight >= innerWidth;
  const width = portrait
    ? innerWidth
    : Math.min(innerWidth * 0.7, 500, innerWidth - 64);
  const height = portrait
    ? Math.min(innerHeight * 0.62, 520)
    : Math.min(innerHeight * 0.82, 420, innerHeight - 24);
  const p = $("panel");
  p.style.left = (portrait ? 0 : (innerWidth - width) / 2) + "px";
  p.style.top =
    (portrait ? innerHeight - height : (innerHeight - height) / 2) + "px";
  p.style.width = p.style.maxWidth = width + "px";
  p.style.height = p.style.maxHeight = height + "px";
}

flow.onSnap(() => {
  caption();
  positionDrawer();
});
window.addEventListener("resize", () =>
  setTimeout(() => {
    caption();
    positionDrawer();
    positionBrowse();
    positionVolumePopover();
  }, 180),
);
listen("catalog", (data) => {
  document.body.classList.remove("libraryLoading");
  rememberCollection();
  const nextSource = data.source || (data.remote ? "remote" : "local"), sameSource = sourceKey === nextSource;
  sourceKey = nextSource;
  invalidateCollections();
  libraryAlbums = data.albums || [];
  libraryById = new Map(libraryAlbums.map(a => [a.id, a]));
  artistYears = new Map();
  for (const a of libraryAlbums) artistYears.set(a.artist, Math.max(artistYears.get(a.artist) || 0, Number(a.year) || 0));
  librarySummary = data.summary || {}; libraryArtists = data.artists || [];
  remoteSource = Boolean(data.remote);
  if (!sameSource) {
    Object.assign(selection, { albums: "all", songs: "all", grouped: false, artists: "", playlists: "", more: "" });
    groupSettings.artists = true; groupSettings.playlists = false;
    collectionPositions.clear(); activeView = "albums"; songOffset = 0;
    try {
      const saved = JSON.parse(localStorage.getItem("naviglass-collection:" + sourceKey) || "null");
      if (saved) {
        if (["all", "favourite"].includes(saved.selection?.albums)) selection.albums = saved.selection.albums;
        if (["all", "favourites"].includes(saved.selection?.songs)) selection.songs = saved.selection.songs;
        selection.grouped = Boolean(saved.selection?.grouped);
        for (const nav of ["artists", "playlists"]) {
          selection[nav] = typeof saved.selection?.[nav] === "string" ? saved.selection[nav] : "";
          if (typeof saved.groups?.[nav] === "boolean") groupSettings[nav] = saved.groups[nav];
        }
        for (const [mode, key] of saved.positions || []) if (typeof key === "string") collectionPositions.set(mode, key);
        if (["albums", "songs", "artists", "playlists"].includes(saved.view)) activeView = saved.view;
      }
    } catch {}
    albums = []; browseTracks = []; browseMode = activeView;
    closeBrowseMenu();
  }
  if (!libraryAlbums.length) {
    $("welcome").querySelector("h2").textContent = data.remembered ? "Your saved library" : "Meet your music again.";
    $("welcome").querySelector("p").textContent = data.remembered ? "No albums are currently available. Refresh your source in Settings; your saved location is retained." : "Load local music or connect your Navidrome library once to get started.";
    $("device").hidden = $("folder").hidden = $("connectWelcome").hidden = Boolean(data.remembered);
  }
  activateCollection(["albums", "songs", "artists", "playlists"].includes(activeView) ? activeView : browseMode, { keepMenu: sameSource && !$("content").hidden });
  updateMenuCount();
});
listen("error", (_, error) => toast(error || "Operation failed"));
listen("progress", (data, error) => {
  $("scan").hidden = Boolean(data.done);
  $("scanText").textContent =
    data.label +
    " · " +
    data.indexed.toLocaleString() +
    " songs · " +
    data.checked.toLocaleString() +
    " checked" +
    (data.skipped ? " · " + data.skipped + " skipped" : "");
  if (data.done)
    toast(
      error ||
        "Library ready: " +
          data.indexed.toLocaleString() +
          " songs indexed" +
          (data.skipped ? " (" + data.skipped + " skipped)" : ""),
    );
});
function playingMarker(number, active) {
  if (!active) {
    number.className = "trackNr";
    number.textContent =
      number.closest(".track")?.dataset.nr || number.dataset.nr || "";
    number.removeAttribute("aria-label");
    return;
  }
  number.className =
    "trackNr song-current-marker " +
    (state.playing ? "is-playing" : "is-paused");
  number.setAttribute(
    "aria-label",
    state.playing ? "Now playing" : "Current track",
  );
  if (state.playing) {
    if (number.children.length !== 3 || number.querySelector("img"))
      number.replaceChildren(
        ...Array.from({ length: 3 }, () => document.createElement("span")),
      );
  } else number.replaceChildren();
}
function playingIndex() {
  return albums.findIndex((a) =>
    a.track
      ? a.track.id === state.track?.id
      : a.station
        ? a.station.id === state.track?.id
        : a.id === state.track?.albumId,
  );
}
function scheduleReturnToPlaying() {
  clearTimeout(snapBackTimer);
  if (
    !state.playing ||
    (playbackBrowseMode && playbackBrowseMode !== browseMode) ||
    !state.track ||
    $("panel").open ||
    !$("content").hidden ||
    songMenuAnchor ||
    pointer ||
    seekHeld
  )
    return;
  const index = playingIndex();
  if (index < 0 || index === albumIndex) return;
  snapBackTimer = setTimeout(() => {
    if (
      !state.playing ||
      $("panel").open ||
      !$("content").hidden ||
      songMenuAnchor ||
      pointer ||
      seekHeld
    )
      return;
    const target = playingIndex();
    if (target >= 0 && target !== albumIndex) selectAlbum(target);
  }, 5000);
}
function updateModeButtons() {
  const repeatIcon = state.repeat === 1 ? "repeat-song" : "repeat";
  if (
    !$("repeat")
      .querySelector("img")
      ?.getAttribute("src")
      .endsWith("/" + repeatIcon + ".svg")
  )
    icon($("repeat"), repeatIcon);
  const repeat =
    state.repeat === 1 ? "Song" : state.repeat === 2 ? "All" : "Off";
  $("repeat").setAttribute("aria-label", "Repeat " + repeat);
  $("repeat").title = "Repeat " + repeat;
  $("repeat").setAttribute("aria-pressed", String(Boolean(state.repeat)));
  $("shuffle").setAttribute(
    "aria-label",
    "Shuffle " + (state.shuffle ? "On" : "Off"),
  );
  $("shuffle").title = $("shuffle").getAttribute("aria-label");
  $("shuffle").setAttribute("aria-pressed", String(Boolean(state.shuffle)));
}

listen("state", (data) => {
  const wasPlaying = state.playing;
  state = data;
  const t = data.track,
    live = Boolean(t?.radio),
    playing = data.playing || data.buffering;
  icon($("play"), playing ? "pause" : "play", !playing);
  $("play").setAttribute("aria-label", playing ? "Pause" : "Play");
  $("nowTitle").textContent = t?.title || "Nothing playing";
  $("nowArtist").textContent = t
    ? [t.artist, t.album].filter(Boolean).join(" · ")
    : "Choose an album to begin";
  $("elapsed").textContent = live
    ? data.buffering
      ? "Loading"
      : "Live"
    : time(data.position);
  $("duration").textContent = live
    ? "LIVE"
    : data.duration
      ? time(data.duration)
      : "--:--";
  if (!seekHeld)
    $("seek").value = data.duration
      ? Math.round((data.position / data.duration) * 1000)
      : 0;
  $("seek").disabled = !t || live || !data.duration;
  for (const name of [
    "play",
    "next",
    "previous",
    "shuffle",
    "repeat",
    "nowFavourite",
  ])
    $(name).disabled =
      (!t && !["play", "shuffle", "repeat"].includes(name)) ||
      (!t && name === "play" && !albums.length) ||
      (live && ["shuffle", "repeat", "nowFavourite"].includes(name));
  $("shuffle").classList.toggle("active", Boolean(data.shuffle));
  $("repeat").classList.toggle("active", Boolean(data.repeat));
  $("repeat").dataset.mode =
    data.repeat === 1 ? "1" : data.repeat === 2 ? "all" : "";
  $("nowFavourite").classList.toggle("active", Boolean(t?.favourite));
  icon($("nowFavourite"), "heart", Boolean(t?.favourite));
  document.querySelectorAll(".track").forEach((row) => {
    const active = row.dataset.id === t?.id;
    row.classList.toggle("active", active);
    const marker = row.querySelector(".trackNr");
    playingMarker(marker, active);
  });
  document.querySelectorAll(".station").forEach((row) => {
    const active = row.dataset.id === t?.id;
    row.classList.toggle("active", active);
    const b = row.querySelector("button");
    b.textContent =
      active && playing ? (data.buffering ? "Loading…" : "Pause") : "Listen";
    b.setAttribute(
      "aria-label",
      (active && playing ? "Pause " : "Listen to ") +
        row.querySelector("strong").textContent,
    );
  });
  if (data.error && data.error !== lastError) toast(data.error);
  lastError = data.error || "";
  if (t && t.id !== lastTrack && (!playbackBrowseMode || playbackBrowseMode === browseMode)) {
    lastTrack = t.id;
    const index = albums.findIndex((album) =>
      album.track
        ? album.track.id === t.id
        : album.station
          ? album.station.id === t.id
          : album.id === t.albumId,
    );
    if (index >= 0) selectAlbum(index);
  }
  updateModeButtons();
  if (!state.playing) clearTimeout(snapBackTimer);
  else if (!wasPlaying) scheduleReturnToPlaying();
  caption();
});
async function showTracks(
  mode = "album",
  value = albums[albumIndex]?.id || "",
  title = albums[albumIndex]?.title || "Songs",
) {
  const entry = albums[albumIndex];
  if (arguments.length === 0 && entry?.station) {
    playCurrentEntry();
    return;
  }
  if (arguments.length === 0 && entry?.groupTracks) {
    openPanel(entry.title, "favourites"); tracks = entry.groupTracks; drawerSelection = -1; drawerValue = ""; drawerRevision++; $("drawerCount").textContent = tracks.length + " songs"; drawTracks(); return;
  }
  if (arguments.length === 0 && entry?.track) {
    openPanel(entry.track.album || "Songs", "favourites");
    tracks = browseTracks;
    drawerSelection = -1;
    drawerValue = "";
    drawerRevision++;
    $("drawerCount").textContent = tracks.length + " songs";
    drawTracks();
    return;
  }
  openPanel(title, mode);
  tracks = [];
  drawerSelection = -1;
  const revision = ++drawerRevision;
  drawerValue = value;
  $("drawerFavourite").disabled = true;
  const data = await call("tracks", { mode, value });
  if (panelMode !== mode || revision !== drawerRevision || !data) return;
  tracks = data.tracks;
  drawerSelection = -1;
  $("drawerCount").textContent = tracks.length + " songs";
  drawerStarred = Boolean(data.albumStarred);
  updateDrawerFavourite();
  $("drawerFavourite").disabled = false;
  drawTracks();
}
function drawTracks(body = $("panelBody"), list = tracks) {
  body.replaceChildren();
  if (!list.length) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent =
      "No songs here yet. Load music using the library button, or add favourites with the heart.";
    body.append(p);
    return;
  }
  list.forEach((t, index) => {
    const row = document.createElement("div");
    row.className = "track";
    row.classList.toggle("keyboardSelected", index === drawerSelection);
    row.dataset.id = t.id;
    row.dataset.nr = t.number || index + 1;
    row.classList.toggle("active", state.track?.id === t.id);
    const play = button("", () => playTrack(index, list), "trackPlay");
    const number = document.createElement("span");
    number.className = "trackNr";
    number.dataset.nr = row.dataset.nr;
    playingMarker(number, state.track?.id === t.id);
    const label = document.createElement("span");
    label.className = "trackLabel";
    const title = document.createElement("strong");
    title.textContent = t.title;
    const artist = document.createElement("small");
    artist.textContent = t.artist;
    label.append(title, artist);
    play.append(number, label);
    {
      const duration = document.createElement("span");
      duration.className = "trackTime";
      duration.textContent = time(t.duration);
      const more = button(
        "",
        (event) => openSongMenu(event.currentTarget, t),
        "songMenu",
      );
      icon(more, "more");
      more.setAttribute("aria-label", "Actions for " + t.title);
      more.setAttribute("aria-expanded", "false");
      row.append(play, duration, more);
    }
    body.append(row);
  });
}
// A top-layer popover cannot be clipped by the drawer's scrolling rows.
let songMenuAnchor = null;
const songMenu = document.createElement("div");
songMenu.className = "songContext";
songMenu.setAttribute("popover", "manual");
songMenu.setAttribute("role", "menu");
document.body.append(songMenu);
function closeSongMenu() {
  if (!songMenuAnchor) return false;
  songMenuAnchor.setAttribute("aria-expanded", "false");
  songMenuAnchor = null;
  songMenu.hidePopover();
  queueMicrotask(scheduleReturnToPlaying);
  return true;
}
function openSongMenu(anchor, track) {
  const same = songMenuAnchor === anchor;
  closeSongMenu();
  if (same) return;
  songMenuAnchor = anchor;
  anchor.setAttribute("aria-expanded", "true");
  function action(label, run) {
    const item = button(label, async () => {
      closeSongMenu();
      await run();
    });
    item.setAttribute("role", "menuitem");
    return item;
  }
  songMenu.replaceChildren(
    action("Add to playlist", () => addPlaylist(track)),
    action(track.favourite ? "Remove favourite" : "Favourite", async () => {
      const result = await call("favourite", { track: track.id });
      if (result) {
        track.favourite = result.favourite;
        toast(
          result.favourite ? "Added to favourites" : "Removed from favourites",
        );
      }
    }),
    action("More info", () => showSongInfo(track)),
  );
  songMenu.showPopover();
  const r = anchor.getBoundingClientRect(),
    p = songMenu.getBoundingClientRect();
  songMenu.style.left =
    Math.max(8, Math.min(innerWidth - p.width - 8, r.left - p.width - 6)) +
    "px";
  songMenu.style.top =
    Math.max(8, Math.min(innerHeight - p.height - 8, r.top)) + "px";
}
async function showSongInfo(track) {
  const previousTitle = $("panelTitle").textContent,
    previousMode = panelMode,
    previousTracks = tracks;
  openPanel("More Info", "songInfo");
  const details = document.createElement("dl");
  details.className = "songDetails";
  $("panelBody").append(details);
  const token = ++drawerRevision;
  let full = track;
  if (!track.radio) {
    const result = await call("trackInfo", { track: track.id });
    if (result?.track) full = { ...track, ...result.track };
  }
  if (token !== drawerRevision || panelMode !== "songInfo") return;
  const rows = track.radio
    ? [
        ["Station", full.title],
        ["Type", "Internet radio"],
        ["Provider / country", full.provider],
        ["Genre", full.genre],
        ["Codec", full.codec],
        ["Bitrate", full.bitRate ? full.bitRate + " kb/s" : null],
        ["Home page", full.homepage],
        ["Stream", full.url],
        ["ID", full.id],
      ]
    : [
        ["Title", full.title],
        ["Artist", full.artist],
        ["Album artist", full.albumArtist],
        ["Composer", full.composer],
        ["Album", full.album],
        ["Genre", full.genre],
        ["Year / date", full.date || full.year],
        ["Track", full.number],
        ["Disc", full.disc],
        ["Length", full.duration ? time(full.duration) : null],
        ["Codec / format", full.suffix || full.mime],
        ["Bitrate", full.bitRate ? full.bitRate + " kb/s" : null],
        ["Sample rate", full.samplingRate ? full.samplingRate + " Hz" : null],
        ["Bit depth", full.bitDepth ? full.bitDepth + " bit" : null],
        ["Channels", full.channels],
        [
          "File size",
          full.size ? (full.size / 1048576).toFixed(2) + " MB" : null,
        ],
        ["Path", full.file],
        ["Source", full.sourceLabel],
        [
          "Modified",
          full.modified ? new Date(full.modified).toLocaleString() : null,
        ],
        ["ID", full.id],
      ];
  for (const [name, value] of rows) {
    const term = document.createElement("dt"),
      detail = document.createElement("dd");
    term.textContent = name;
    detail.textContent =
      value === undefined || value === null || value === "" || value === 0
        ? "Unavailable"
        : String(value);
    details.append(term, detail);
  }
  $("panelBody").append(
    button(previousMode ? "Back to songs" : "Close", () => {
      if (!previousMode) {
        window.closePanel();
        return;
      }
      openPanel(previousTitle, previousMode);
      tracks = previousTracks;
      $("drawerCount").textContent = tracks.length + " songs";
      updateDrawerFavourite();
      drawTracks();
    }),
  );
}
$("panelBody").addEventListener("scroll", closeSongMenu, { passive: true });
document.addEventListener("keydown", (event) => {
  if (event.key === "Escape" && closeSongMenu()) {
    event.preventDefault();
    event.stopPropagation();
  }
});
document.addEventListener("pointerdown", (event) => {
  if (!event.target.closest(".songContext, .songMenu")) closeSongMenu();
});
function updateDrawerFavourite() {
  const b = $("drawerFavourite");
  icon(b, "heart", drawerStarred);
  b.classList.toggle("active", drawerStarred);
  b.setAttribute("aria-pressed", String(drawerStarred));
  b.setAttribute(
    "aria-label",
    drawerStarred ? "Remove favourite album" : "Favourite album",
  );
}
$("drawerFavourite").onclick = async () => {
  const album = drawerValue,
    revision = drawerRevision;
  $("drawerFavourite").disabled = true;
  const data = await call("albumFavourite", { album });
  if (revision !== drawerRevision) return;
  if (data) {
    drawerStarred = Boolean(data.albumStarred);
    const item = libraryAlbums.find((a) => a.id === album);
    if (item) item.starred = drawerStarred;
    updateDrawerFavourite();
  }
  $("drawerFavourite").disabled = false;
};
async function playTrack(index, list = tracks) {
  if (!list[index]) return;
  playbackBrowseMode = browseMode;
  if (albums[albumIndex]?.groupTracks) { const id = list[index].id; list = browseTracks; index = list.findIndex(t => t.id === id); if (index < 0) return; }
  const albumIds =
    list.every((t) => t.albumId === list[index].albumId) &&
    albums.every((a) => !a.track && !a.station && !a.groupTracks)
      ? albums.map((a) => a.id)
      : [];
  const result = await call("play", {
    ids: list.map((t) => t.id),
    index,
    albumIds,
  });
  if (result && !$("panel").classList.contains("songDrawer"))
    window.closePanel();
}
async function playlists(addTrack) {
  openPanel(addTrack ? "Add to playlist" : "Playlists", "playlists");
  const result = await call("playlists");
  if (!result || panelMode !== "playlists") return;
  const body = $("panelBody");
  body.append(
    button("＋ New playlist", () => newPlaylist(addTrack), "primary"),
  );
  for (const p of result.playlists)
    body.append(
      button(
        p.name + " · " + p.count + " songs",
        async () => {
          if (addTrack) {
            const saved = await call("addPlaylist", {
              playlist: p.id,
              track: addTrack.id,
            });
            if (saved) {
              toast("Added to " + p.name);
              window.closePanel();
            }
          } else showTracks("playlist", p.id, p.name);
        },
        "playlistItem",
      ),
    );
  if (!result.playlists.length) {
    const hint = document.createElement("p");
    hint.className = "hint";
    hint.textContent = "Create a playlist and add songs using the + button.";
    body.append(hint);
  }
}
function addPlaylist(t) {
  playlists(t);
}
function newPlaylist(track) {
  openPanel("New playlist", "create");
  const input = document.createElement("input");
  input.type = "text";
  input.maxLength = 100;
  input.placeholder = "Playlist name";
  input.setAttribute("aria-label", "Playlist name");
  $("panelBody").append(
    input,
    button(
      "Create",
      async () => {
        const result = await call("createPlaylist", { name: input.value });
        if (result) {
          if (track)
            await call("addPlaylist", {
              playlist: result.playlist,
              track: track.id,
            });
          window.closePanel();
          if (activeView === "playlists") switchView("playlists");
          else playlists();
        }
      },
      "primary",
    ),
  );
  input.focus();
}
function library() {
  openPanel("Your library", "library");
  const body = $("panelBody"),
    sources = document.createElement("div");
  sources.className = "sourceButtons";
  sources.append(
    button("Device music", () => call("device")),
    button("Choose folder", () => call("folder")),
    button("Refresh source", () => call("rescan")),
    button("Favourites", () => showTracks("favourites", "", "Favourites")),
    button("Playlists", () => playlists()),
  );
  body.append(sources);
  const hint = document.createElement("p");
  hint.className = "hint";
  hint.textContent =
    albums.length.toLocaleString() +
    " albums · " +
    albums.reduce((total, a) => total + a.count, 0).toLocaleString() +
    " songs. Folder access is read-only. Refresh reuses unchanged folder metadata.";
  body.append(hint);
  const grid = document.createElement("div");
  grid.className = "albumGrid";
  libraryAlbums.slice(0, 120).forEach((a, i) => {
    const b = button(
        "",
        () => {
          setCollection(sortedEntries(libraryAlbums, "albums"), "albums");
          selectAlbum(albums.findIndex(entry=>entry.id===a.id));
          window.closePanel();
        },
        "albumCard",
      ),
      image = document.createElement("img"),
      text = document.createElement("span");
    image.src = coverURL(a);
    image.loading = "lazy";
    image.alt = "";
    image.onerror = () => {
      image.onerror = null;
      image.src = "icon.svg";
    };
    text.textContent = a.title;
    b.append(image, text);
    grid.append(b);
  });
  body.append(grid);
  if (albums.length > 120) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent =
      "Showing the first 120 covers here. Browse all albums in Cover Flow or search any song.";
    body.append(p);
  }
}
function search() {
  openPanel("Search music", "search");
  const revision = ++drawerRevision;
  const input = document.createElement("input");
  input.type = "search";
  input.placeholder = "Song title starts with…";
  input.setAttribute("aria-label", "Search music");
  const results = document.createElement("div"),
    status = document.createElement("p");
  status.className = "hint";
  status.setAttribute("role", "status");
  status.textContent = "Type the beginning of a song title.";
  $("panelBody").append(input, status, results);
  let queryRevision = 0;
  input.oninput = () => {
    clearTimeout(searchTimer);
    const term = input.value.trim(),
      query = ++queryRevision;
    results.replaceChildren();
    status.textContent = term
      ? "Searching…"
      : "Type the beginning of a song title.";
    if (!term) {
      tracks = [];
      return;
    }
    searchTimer = setTimeout(async () => {
      const data = await call("tracks", { mode: "search", value: term });
      if (
        panelMode !== "search" ||
        drawerRevision !== revision ||
        query !== queryRevision ||
        !data
      )
        return;
      const needle = term.toLocaleLowerCase();
      tracks = data.tracks.filter((t) =>
        String(t.title || "").trimStart().toLocaleLowerCase().startsWith(needle),
      );
      tracks.sort((a, b) => (a.title || "").localeCompare(b.title || "", undefined, { sensitivity: "base", numeric: true }));
      status.textContent = tracks.length
        ? tracks.length + " matching songs"
        : "No matching songs.";
      if (tracks.length) drawTracks(results, tracks);
    }, 220);
  };
  input.focus();
}
$("search").onclick = search;
$("library").onclick = library;
$("songs").onclick = () =>
  $("panel").open && $("panel").classList.contains("songDrawer")
    ? window.closePanel()
    : showTracks();
document.addEventListener("pointerdown", (event) => {
  if (
    $("panel").open &&
    $("panel").classList.contains("songDrawer") &&
    !event.target.closest("#panel, #songs, .songContext, .drawerBackdrop")
  )
    window.closePanel();
});
$("device").onclick = () => call("device");
$("folder").onclick = () => call("folder");
$("cancel").onclick = () => call("cancel");
$("backAlbum").onclick = () => selectAlbum(albumIndex - 1);
$("nextAlbum").onclick = () => selectAlbum(albumIndex + 1);
for (const [id, action] of [
  ["play", "toggle"],
  ["next", "next"],
  ["previous", "previous"],
  ["shuffle", "shuffle"],
  ["repeat", "repeat"],
])
  $(id).onclick = async () => {
    await ensureDemo();
    call(action);
  };
$("nowFavourite").onclick = () =>
  state.track && call("favourite", { track: state.track.id });
$("infoMenu").onclick = () => {
  const entry = albums[albumIndex];
  if (!entry) return;
  if (entry.station) {
    openStationMenu($("infoMenu"), entry.station);
    return;
  }
  const track =
    entry.track || (state.track?.albumId === entry.id ? state.track : null);
  if (track) {
    openSongMenu($("infoMenu"), track);
    return;
  }
  showTracks();
};
icon($("infoMenu"), "more");
$("seek").onpointerdown = () => (seekHeld = true);
$("seek").oninput = () => {
  seekHeld = true;
  $("elapsed").textContent = time(
    (Number($("seek").value) / 1000) * state.duration,
  );
};
$("seek").onchange = () => {
  const value = (Number($("seek").value) / 1000) * state.duration;
  seekHeld = false;
  call("seek", { position: Math.round(value) });
};
$("seek").onpointercancel = () => (seekHeld = false);
let pointer;
const scene = $("scene");
window.addEventListener("resize", () => { pointer = null; });
scene.addEventListener("lostpointercapture", () => { pointer = null; });
scene.addEventListener("pointerdown", (e) => {
  if (!e.isPrimary) return;
  closeBrowseMenu();
  pointer = {
    id: e.pointerId,
    x: e.clientX,
    y: e.clientY,
    last: e.clientX,
    moved: false,
  };
  scene.setPointerCapture(e.pointerId);
  e.preventDefault();
});
scene.addEventListener("pointermove", (e) => {
  if (!pointer || pointer.id !== e.pointerId) return;
  const dx = e.clientX - pointer.x,
    dy = e.clientY - pointer.y;
  if (Math.abs(dx) < 12 || Math.abs(dx) <= Math.abs(dy) * 1.15) return;
  const threshold = Math.max(
    45,
    Math.min(110, (flow.getActiveCoverBounds()?.width || 320) * 0.22),
  );
  const delta = e.clientX - pointer.last,
    steps = Math.trunc(Math.abs(delta) / threshold);
  if (steps) {
    selectAlbum(albumIndex + (delta < 0 ? 1 : -1) * Math.min(steps, 4));
    pointer.last += Math.sign(delta) * steps * threshold;
    pointer.moved = true;
  }
  e.preventDefault();
});
scene.addEventListener("pointerup", (e) => {
  if (!pointer || pointer.id !== e.pointerId) return;
  const p = pointer;
  pointer = null;
  const dx = e.clientX - p.x,
    dy = e.clientY - p.y;
  if (!p.moved && Math.abs(dx) >= 28 && Math.abs(dx) > Math.abs(dy) * 1.15)
    selectAlbum(albumIndex + (dx < 0 ? 1 : -1));
  else if (
    !p.moved &&
    Math.abs(dx) < 14 &&
    Math.abs(dy) < 14 &&
    albums.length
  ) {
    const b = flow.getActiveCoverBounds();
    if (b && e.clientX < b.left) selectAlbum(albumIndex - 1);
    else if (b && e.clientX > b.right) selectAlbum(albumIndex + 1);
    else showTracks();
  }
  if (scene.hasPointerCapture(e.pointerId))
    scene.releasePointerCapture(e.pointerId);
  scheduleReturnToPlaying();
  e.preventDefault();
});
scene.addEventListener("pointercancel", () => {
  pointer = null;
});
window.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    e.preventDefault();
    if (volumePopover.matches(":popover-open")) { volumePopover.hidePopover(); return; }
    if (!window.closePanel() && document.body.classList.contains("immersive"))
      $("fullscreen").click();
    scheduleReturnToPlaying();
    return;
  }
  if (
    e.target.closest?.("input,textarea,select,[contenteditable=true]") ||
    e.ctrlKey ||
    e.metaKey ||
    e.altKey
  )
    return;
  if (e.target.closest?.("#shuffle,#repeat")) return;
  if ($("panel").open && !$("panel").classList.contains("songDrawer")) return;
  if (e.repeat && [" ", "Enter"].includes(e.key)) return;
  if (e.key === " ") {
    e.preventDefault();
    $("play").click();
    return;
  }
  if ($("panel").open) {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      drawerSelection = Math.max(
        0,
        Math.min(
          tracks.length - 1,
          (drawerSelection < 0
            ? e.key === "ArrowUp"
              ? tracks.length
              : -1
            : drawerSelection) + (e.key === "ArrowDown" ? 1 : -1),
        ),
      );
      const rows = [...$("panelBody").querySelectorAll(".track")];
      rows.forEach((row, i) =>
        row.classList.toggle("keyboardSelected", i === drawerSelection),
      );
      rows[drawerSelection]?.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter" && !e.target.closest?.("button")) {
      e.preventDefault();
      playTrack(drawerSelection);
    }
    return;
  }
  if (
    !e.target.closest?.("button") ||
    ["ArrowLeft", "ArrowRight", "ArrowDown"].includes(e.key)
  ) {
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
      e.preventDefault();
      selectAlbum(albumIndex + (e.key === "ArrowRight" ? 1 : -1));
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      showTracks();
    } else if (e.key === "Enter") {
      e.preventDefault();
      playCurrentEntry();
    }
  }
});
let wheelTotal = 0,
  wheelReset,
  wheelFrame = 0,
  wheelTarget = 0;
scene.addEventListener(
  "wheel",
  (e) => {
    if (
      !albums.length ||
      e.ctrlKey ||
      (Math.abs(e.deltaY) < Math.abs(e.deltaX) && !e.deltaX)
    )
      return;
    e.preventDefault();
    if ($("panel").open) window.closePanel();
    const delta = Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY;
    wheelTotal += Math.max(
      -1.2,
      Math.min(
        1.2,
        delta * (e.deltaMode === 1 ? 0.12 : e.deltaMode === 2 ? 1.2 : 0.018),
      ),
    );
    const steps =
      wheelTotal > 0 ? Math.floor(wheelTotal) : Math.ceil(wheelTotal);
    if (steps) {
      wheelTarget = Math.max(0, Math.min(albums.length - 1, (wheelFrame ? wheelTarget : albumIndex) + steps));
      if (!wheelFrame) wheelFrame = requestAnimationFrame(() => {
        wheelFrame = 0;
        if (!document.hidden && !$("panel").open) selectAlbum(wheelTarget);
      });
      wheelTotal -= steps;
    }
    clearTimeout(wheelReset);
    wheelReset = setTimeout(() => (wheelTotal = 0), 140);
  },
  { passive: false },
);
if (!native) {
  document.body.classList.add("demo");
  const p = document.createElement("p");
  p.className = "hint";
  p.textContent =
    "Browser preview: local songs are simulated; radio plays live online audio. Real local music requires the APK.";
  $("welcome").append(p, button("Explore demo covers", ensureDemo, "primary"));
} else {
  document.body.classList.add("libraryLoading");
  $("welcome").querySelector("h2").textContent = "Opening your library…";
  $("welcome").querySelector("p").textContent = "Restoring saved music. No new scan is needed.";
  call("ready");
}
for (const [id, name] of [
  ["play", "play"],
  ["previous", "previous"],
  ["next", "next"],
  ["shuffle", "shuffle"],
  ["repeat", "repeat"],
  ["nowFavourite", "heart"],
  ["search", "search"],
  ["songs", "menu"],
  ["volume", "volume"],
  ["fullscreen", "fullscreen"],
  ["close", "close"],
  ["backAlbum", "previous"],
  ["nextAlbum", "next"],
])
  icon($(id), name, id === "play");
for (const name of [
  "play",
  "next",
  "previous",
  "shuffle",
  "repeat",
  "nowFavourite",
  "seek",
])
  $(name).disabled = !state.track && !["shuffle", "repeat"].includes(name);
window.naviglassDebug = {
  get playback() {
    return structuredClone(state);
  },
  get albums() {
    return albums.length;
  },
  get selectedAlbum() {
    return albumIndex;
  },
  selectAlbum,
  bounds: flow.getActiveCoverBounds,
  get browseMode() {
    return browseMode;
  },
  get entry() {
    return albums[albumIndex];
  },
  get menuOpen() {
    return !$("content").hidden;
  },
  render: flow.debugRenderState,
};
function heading(title, hint, count = menuCount(activeView)) {
  const body = $("content");
  body.replaceChildren();
  const h = document.createElement("h1"),
    p = document.createElement("p");
  h.textContent = title;
  if (count != null) {
    const separator = document.createElement("span"), total = document.createElement("span");
    separator.className = "menuSeparator"; separator.textContent = "|"; separator.setAttribute("aria-hidden", "true");
    total.className = "menuCount"; total.textContent = Number(count).toLocaleString(); total.setAttribute("aria-label", "Total items"); h.append(separator, total);
  }
  p.className = "hint";
  p.textContent = hint;
  const close = button("", closeBrowseMenu, "browseClose");
  icon(close, "close");
  close.setAttribute("aria-label", "Close music browser");
  body.append(h); if (hint) body.append(p); body.append(close);
  return body;
}
function closeBrowseMenu() {
  queueMicrotask(scheduleReturnToPlaying);
  $("content").hidden = true;
  viewEpoch++;
  $("welcome").hidden = Boolean(libraryAlbums.length || albums.length);
  document
    .querySelectorAll("#mainNav button")
    .forEach((b) => b.setAttribute("aria-expanded", "false"));
}
function markView(name) {
  document.querySelectorAll("#mainNav button").forEach((b) => {
    if (b.dataset.view === name) b.setAttribute("aria-current", "page");
    else b.removeAttribute("aria-current");
  });
}
function fitChrome(b) {
  const landscape = innerWidth > innerHeight && innerHeight < 600;
  const t = Math.max(0, Math.min(1, (Math.min(b.width, b.height) - 150) / 170));
  const root = document.documentElement.style;
  root.setProperty("--seek-strip-width", landscape
    ? Math.min(innerWidth - 32, Math.max(b.width, innerWidth * 0.68)) + "px"
    : Math.min(b.width, innerWidth - 20) + "px");
  root.setProperty("--strip-button", 18 + 6 * t + "px");
  root.setProperty("--strip-font", 9 + 4 * t + "px");
  root.setProperty("--strip-icon", 11 + 5 * t + "px");
  root.setProperty("--strip-gap", 4 + 6 * t + "px");
  root.setProperty("--strip-hit", 26 + 8 * t + "px");
  $("playbackStrip").style.top = Math.max(2, b.top - (26 + 8 * t) - 3) + "px";
  const heightT = Math.max(0, Math.min(1, (innerHeight - 360) / 140));
  const widthT = Math.max(0, Math.min(1, (innerWidth - 620) / 420));
  const controlsT = (heightT + widthT) / 2;
  const controlsWidth = landscape
    ? Math.min(innerWidth - 32, 320 + 240 * controlsT)
    : innerWidth - 8;
  root.setProperty(
    "--transport-button",
    landscape ? 18 + 4 * controlsT + "px" : "44px",
  );
  root.setProperty(
    "--transport-play",
    landscape ? 22 + 4 * controlsT + "px" : "52px",
  );
  root.setProperty(
    "--transport-icon",
    landscape ? 15 + 4 * controlsT + "px" : "26px",
  );
  root.setProperty(
    "--play-icon",
    landscape ? 18 + 4 * controlsT + "px" : "28px",
  );
  root.setProperty("--nav-font", landscape ? "9px" : "10px");
  $("controls").style.width = controlsWidth + "px";
}
function positionBrowse() {
  if ($("content").hidden) return;
  const anchor =
    document.querySelector('[data-view="' + menuAnchor + '"]') || $("mainNav");
  const r = anchor.getBoundingClientRect(),
    p = $("content");
  const width = Math.min(
    innerWidth - 16,
    ["radio", "radioSaved"].includes(activeView)
      ? 420
      : activeView === "settings"
        ? 280
        : 240,
  );
  p.style.width = width + "px";
  p.style.height = "auto";
  p.style.left =
    Math.max(
      8,
      Math.min(innerWidth - width - 8, r.left + (r.width - width) / 2),
    ) + "px";
  p.style.top = "auto";
  p.style.bottom = Math.max(8, innerHeight - r.top + 8) + "px";
  p.style.maxHeight = Math.max(100, r.top - 16) + "px";
}
function setCollection(entries, mode, queue = [], options = {}) {
  rememberCollection();
  const position = collectionPositions.get(mode);
  albums = entries; browseTracks = queue; browseMode = mode;
  albumIndex = Math.max(0, entries.findIndex(e => (e.track?.id || e.id) === position));
  loaded.clear(); textureEpoch++;
  flow.setAlbumData(albums.map(() => flow.getDefaultTexture()));
  $("browseStrip").max = Math.max(0, albums.length - 1);
  $("browseStrip").disabled = !albums.length;
  $("scene").hidden = false;
  $("caption").hidden = !albums.length;
  for (const id of ["songs", "backAlbum", "nextAlbum"]) $(id).disabled = !albums.length;
  $("welcome").hidden = Boolean(libraryAlbums.length || albums.length);
  if (albums.length) selectAlbum(albumIndex, false);
  else if (!options.loading) toast("No items in this collection yet.");
  $("albumPosition").textContent = albums.length ? albumIndex + 1 + " / " + albums.length : "";
  markView(mode === "favourites" ? "songs" : mode);
  if (!options.keepMenu) closeBrowseMenu();
}
async function browseSongs(mode = "all", value = "0", nav = "songs", options = {}) {
  if (native) call("browseCancel");
  if (mode === "artist") pendingResponses.delete(JSON.stringify(["tracks", { mode, value }]));
  const rev = ++collectionRevision, key = collectionKey(nav), remembered = collectionMemory.get(nav);
  if (remembered?.key === key && Date.now() - remembered.time < 300000) {
    setCollection(remembered.entries, nav, remembered.queue, options); return;
  }
  const grouped = nav === "songs" ? selection.grouped : groupSettings[nav];
  if (grouped && (mode === "all" || mode === "artist")) {
    let list = libraryAlbums;
    if (mode === "artist") {
      setCollection([], nav, [], { ...options, loading: true });
      const data = await call("artistAlbums", { value });
      if (!data || rev !== collectionRevision) return;
      list = data.albums;
    }
    const entries = sortedEntries(list, nav);
    collectionMemory.set(nav, { key, entries, queue: [], time: Date.now() });
    setCollection(entries, nav, [], options); return;
  }
  // Change the active view immediately, even on the first uncached request.
  setCollection([], nav, [], { ...options, loading: true });
  const data = await call("tracks", { mode, value });
  if (!data || rev !== collectionRevision) return;
  const entries = data.tracks.map(t => {
    const a = libraryById.get(t.albumId);
    return { ...a, id: t.albumId, title: t.title, artist: t.artist, year: t.year || a?.year, coverId: a?.coverId || t.id, track: t };
  });
  let ordered = sortedEntries(entries, nav), queue;
  if (grouped) {
    const groups = new Map();
    for (const e of ordered) {
      if (!groups.has(e.id)) groups.set(e.id, { ...libraryById.get(e.id), id: e.id, title: e.track.album, artist: e.artist, year: e.year, coverId: e.coverId, groupTracks: [] });
      groups.get(e.id).groupTracks.push(e.track);
    }
    for (const g of groups.values()) g.groupTracks.sort((a,b) => (Number(a.disc)||0)-(Number(b.disc)||0) || (Number(a.number)||0)-(Number(b.number)||0) || a.title.localeCompare(b.title));
    ordered = sortedEntries([...groups.values()], nav); queue = ordered.flatMap(e => e.groupTracks);
  } else queue = ordered.map(e => e.track);
  collectionMemory.set(nav, { key, entries: ordered, queue, time: Date.now() });
  setCollection(ordered, nav, queue, options);
}
async function activateCollection(name, options = {}) {
  rememberCollection();
  if (name === "albums") {
    collectionRevision++;
    setCollection(sortedEntries(selection.albums === "favourite" ? libraryAlbums.filter(a => a.starred) : libraryAlbums, "albums"), "albums", [], options);
  } else if (name === "songs") await browseSongs(selection.songs, selection.songs === "all" ? String(songOffset) : "", name, options);
  else if (name === "artists") {
    selection.artists ||= libraryArtists[0]?.name || libraryAlbums[0]?.artist || "";
    await browseSongs("artist", selection.artists, name, options);
  } else if (name === "playlists") {
    const rev = ++collectionRevision;
    if (!cachedResponse("playlists", {})) setCollection([], name, [], { ...options, loading: true });
    const data = await call("playlists"); if (!data || rev !== collectionRevision) return;
    const list = data.playlists.find(p => (p.remote ? "remote:" : "local:") + p.id === selection.playlists) || data.playlists[0];
    if (!list) { setCollection([], name, [], options); return; }
    selection.playlists = (list.remote ? "remote:" : "local:") + list.id;
    await browseSongs(list.remote ? "serverPlaylist" : "playlist", list.id, name, options);
  }
}
function markSelected(button, selected) {
  button.setAttribute("aria-pressed", String(selected));
  let tick = button.querySelector(".selectionTick");
  if (!tick) { tick = document.createElement("span"); tick.className = "selectionTick"; tick.textContent = "✓"; tick.setAttribute("aria-hidden", "true"); button.append(tick); }
  tick.hidden = !selected;
}
function menuItem(body, title, detail, action, selected) {
  const b = button("", action, "browseItem"), label = document.createElement("strong");
  label.textContent = title; b.append(label); if (selected !== undefined) markSelected(b, selected); body.append(b); return b;
}
async function switchView(name) {
  if (!$("content").hidden && activeView === name) {
    closeBrowseMenu();
    return;
  }
  if ($("panel").open) window.closePanel();
  if (native) call("browseCancel");
  activeView = name;
  menuAnchor = ["radio", "radioSaved", "favourites"].includes(name)
    ? "more"
    : name;
  const epoch = ++viewEpoch;
  $("content").hidden = false;
  $("content").className = "browsePopover";
  $("welcome").hidden = true;
  document
    .querySelector('[data-view="' + menuAnchor + '"]')
    ?.setAttribute("aria-expanded", "true");
  const titles = {
    albums: "Albums",
    songs: "Songs",
    artists: "Artists",
    playlists: "Playlists",
    more: "More",
    settings: "Settings",
    radio: "Internet radio",
    radioSaved: "Saved stations",
  };
  let body = heading(titles[name] || name, "");
  positionBrowse();
  await ensureDemo();
  if (epoch !== viewEpoch && !native) {
    // Demo catalog can arrive on the first menu click.
    if (!$("content").hidden) return;
    return switchView(name);
  }
  if (epoch !== viewEpoch) return;
  if (["albums", "songs", "artists", "playlists"].includes(name)) activateCollection(name, { keepMenu: true });
  else { collectionRevision++; markView(menuAnchor); }
  if (name === "albums") {
    menuItem(body, "All Albums", "Browse your full library", () => {
      collectionRevision++; selection.albums = "all";
      setCollection(sortedEntries(libraryAlbums,"albums"), "albums");
    }, selection.albums === "all");
    menuItem(body, "Favourite", "Browse starred albums", () => {
      collectionRevision++;
      selection.albums = "favourite"; const list = libraryAlbums.filter((a) => a.starred);
      setCollection(sortedEntries(list, "albums"), "albums");
    }, selection.albums === "favourite");
    sortOptions(body, "albums");
  } else if (name === "songs") {
    menuItem(body, "All Songs", "", () => { selection.songs = "all"; if (selection.more === "favourites") selection.more = ""; songOffset = 0; return browseSongs(); }, selection.songs === "all");
    menuItem(body, "Group by album", "", () => { selection.grouped = !selection.grouped; return browseSongs(selection.songs, selection.songs === "all" ? "0" : ""); }, selection.grouped);
    menuItem(body, "Favourite", "", () => { selection.songs = "favourites"; return browseSongs("favourites", "", "songs"); }, selection.songs === "favourites");
    if (browseTracks.length === 200)
      menuItem(body, "Next 200 songs", "Load the next page", () => {
        songOffset += 200;
        browseSongs("all", String(songOffset));
      });
    sortOptions(body, "songs");
    menuItem(body, "Search songs", "Find the beginning of a song title", search);
  } else if (name === "artists") {
    menuItem(body, "Group by album", "", () => { groupSettings.artists = !groupSettings.artists; return activateCollection("artists"); }, groupSettings.artists);
    const artists = libraryArtists.length ? libraryArtists.map(a => a.name) : [...new Set(libraryAlbums.map(a => a.artist).filter(Boolean))];
    const input = document.createElement("input");
    input.type = "search";
    input.placeholder = "Find artist";
    input.setAttribute("aria-label", "Find artist");
    body.append(input);
    const rows = document.createElement("div");
    body.append(rows);
    const draw = () => {
      rows.replaceChildren();
      const artistEntries = artists.map(title => ({ title, year: artistYears.get(title) || 0 }));
      const visible = sortedEntries(artistEntries, "artists").map(a => a.title).filter(a => a.toLowerCase().includes(input.value.toLowerCase()));
      updateMenuCount(visible.length);
      for (const artist of visible) menuItem(rows, artist, "", () => { selection.artists = artist; return browseSongs("artist", artist, "artists"); }, selection.artists === artist);
    };
    sortOptions(body, "artists", draw);
    input.oninput = draw;
    draw();
  } else if (name === "playlists") {
    menuItem(body, "Group by album", "", () => { groupSettings.playlists = !groupSettings.playlists; return activateCollection("playlists"); }, groupSettings.playlists);
    menuItem(body, "New playlist", "Create a mix on this device", () =>
      newPlaylist(),
    );
    const data = await call("playlists");
    if (epoch !== viewEpoch || !data) return;
    updateMenuCount(data.playlists.length);
    sortOptions(body, "playlists");
    for (const p of [...data.playlists].sort((a, b) =>
      a.name.localeCompare(b.name),
    ))
      menuItem(body, p.name, "", () => { selection.playlists = (p.remote ? "remote:" : "local:") + p.id; return browseSongs(p.remote ? "serverPlaylist" : "playlist", p.id, "playlists"); }, selection.playlists === (p.remote ? "remote:" : "local:") + p.id);
  } else if (name === "more") {
    const stations = await call("radioStations"); if (epoch !== viewEpoch) return; starterCount = stations?.stations.length || 0; updateMenuCount();
    if (selection.more === "favourites") browseSongs("favourites", "", "songs", { keepMenu: true });
    else {
      const saved = selection.more === "saved" ? await call("radioSaved") : null;
      if (epoch !== viewEpoch) return;
      setStationCollection(saved?.stations || stations?.stations || [], 0, { keepMenu: true });
      markView("more");
    }
    menuItem(body, "Internet radio browser", "", () => { selection.more = "browser"; return switchView("radio"); }, selection.more === "browser");
    menuItem(
      body,
      "Starter stations",
      "Browse live radio in Cover Flow",
      async () => {
        const d = await call("radioStations");
        if (d) { selection.more = "starter"; setStationCollection(d.stations); }
      }, selection.more === "starter",
    );
    menuItem(body, "Saved stations", "", () => { selection.more = "saved"; return switchView("radioSaved"); }, selection.more === "saved");
    menuItem(body, "Favourite songs", "", () => { selection.more = "favourites"; selection.songs = "favourites"; return browseSongs("favourites", "", "songs"); }, selection.more === "favourites");
    menuItem(body, "Album library", "Choose local music folders", library);
  } else if (name === "radio") await radioBrowser(epoch);
  else if (name === "radioSaved") await radioBrowser(epoch, true);
  else if (name === "settings") await settingsPage(epoch);
  positionBrowse();
}
let songOffset = 0;
function openStationMenu(anchor, station, onSaved) {
  closeSongMenu();
  songMenuAnchor = anchor;
  anchor.setAttribute("aria-expanded", "true");
  songMenu.replaceChildren(
    button(
      station.saved ? "Remove saved station" : "Save station",
      async () => {
        closeSongMenu();
        const result = await call("radioSave", {
          station: station.id,
          save: !station.saved,
        });
        if (result) {
          station.saved = result.saved;
          onSaved?.(result.saved);
          toast(
            result.saved
              ? "Station saved"
              : "Station removed from saved stations",
          );
        }
      },
    ),
    button("More info", () => {
      closeSongMenu();
      showSongInfo({ ...station, radio: true });
    }),
  );
  if (station.remote) songMenu.firstElementChild.remove();
  songMenu
    .querySelectorAll("button")
    .forEach((b) => b.setAttribute("role", "menuitem"));
  songMenu.showPopover();
  const r = anchor.getBoundingClientRect(),
    p = songMenu.getBoundingClientRect();
  songMenu.style.left =
    Math.max(8, Math.min(innerWidth - p.width - 8, r.left - p.width - 6)) +
    "px";
  songMenu.style.top =
    Math.max(8, Math.min(innerHeight - p.height - 8, r.top)) + "px";
}
function stationCover(station) {
  return "/radio-art/" + encodeURIComponent(station.id) + ".jpg";
}
function setStationCollection(stations, index = 0, options = {}) {
  setCollection(
    stations.map((s) => ({
      id: s.id,
      title: s.title,
      artist: s.provider,
      year: "Live",
      station: s,
      demoCover: stationCover(s),
    })),
    "more",
    [], options,
  );
  selectAlbum(index);
}
async function playCurrentEntry() {
  playbackBrowseMode = browseMode;
  const e = albums[albumIndex];
  if (!e) return;
  if (e.station)
    return call("radioPlay", {
      station: e.station.id,
      ids: albums.filter((a) => a.station).map((a) => a.station.id),
    });
  if (e.groupTracks) return playTrack(0, e.groupTracks);
  if (e.track)
    return playTrack(
      browseTracks.findIndex((t) => t.id === e.track.id),
      browseTracks,
    );
  const data = await call("tracks", { mode: "album", value: e.id });
  if (data?.tracks.length) return playTrack(0, data.tracks);
}
document.addEventListener("pointerdown", (e) => {
  if (!$("content").hidden && !e.target.closest("#content, #mainNav"))
    closeBrowseMenu();
});
document.querySelectorAll("#mainNav button").forEach((b) => {
  b.onclick = () => switchView(b.dataset.view);
  icon(
    b.querySelector(".navIcon"),
    {
      albums: "album",
      songs: "music",
      playlists: "playlist",
      favourites: "heart",
      radio: "radio",
      artists: "artist",
      more: "more",
      settings: "settings",
    }[b.dataset.view],
  );
});
$("browseStrip").oninput = () => selectAlbum(Number($("browseStrip").value));
$("play").onclick = async () => {
  if (state.track) {
    call("toggle");
    return;
  }
  await ensureDemo();
  await playCurrentEntry();
};
$("connectWelcome").onclick = connectServer;
// A light-dismiss popover anchored to the speaker, never a modal panel.
const volumePopover = document.createElement("div");
volumePopover.className = "volumePopover";
volumePopover.id = "volumePopover";
volumePopover.setAttribute("popover", "auto");
volumePopover.setAttribute("role", "dialog");
volumePopover.setAttribute("aria-label", "Player volume");
const volumeShell = document.createElement("div");
volumeShell.className = "volumeSliderShell";
const volumeSlider = document.createElement("input");
volumeSlider.type = "range";
volumeSlider.min = "0";
volumeSlider.max = "100";
volumeSlider.value = Math.round((state.volume ?? 1) * 100);
volumeSlider.setAttribute("aria-label", "Player volume");
volumeShell.append(volumeSlider);
volumePopover.append(volumeShell);
document.body.append(volumePopover);
$("volume").setAttribute("popovertarget", "volumePopover");
$("volume").setAttribute("popovertargetaction", "toggle");
$("volume").setAttribute("aria-haspopup", "dialog");
$("volume").setAttribute("aria-expanded", "false");
function positionVolumePopover() {
  if (!volumePopover.matches(":popover-open")) return;
  const anchor = $("volume").getBoundingClientRect();
  const rect = volumePopover.getBoundingClientRect();
  volumePopover.style.left = Math.max(8, Math.min(innerWidth - rect.width - 8, anchor.right - rect.width)) + "px";
  volumePopover.style.top = Math.max(8, Math.min(innerHeight - rect.height - 8, anchor.bottom + 6)) + "px";
}
volumeSlider.oninput = () => {
  volumeSlider.style.setProperty("--volume-progress", volumeSlider.value + "%");
  call("volume", { value: Number(volumeSlider.value) / 100 });
};
volumePopover.addEventListener("toggle", () => {
  $("volume").setAttribute("aria-expanded", String(volumePopover.matches(":popover-open")));
  positionVolumePopover();
});
$("volume").onclick = () => {
  // Native popover toggle knows its invoker, so pointer-down cannot close/reopen it.
  volumeSlider.value = Math.round((state.volume ?? 1) * 100);
  volumeSlider.style.setProperty("--volume-progress", volumeSlider.value + "%");
};
volumePopover.addEventListener("beforetoggle", (event) => {
  if (event.newState === "open") queueMicrotask(positionVolumePopover);
});
window.addEventListener("resize", positionVolumePopover);
updateModeButtons();
let immersive = false;
function setImmersive(enabled) {
  immersive = Boolean(enabled && innerWidth > innerHeight);
  closeSongMenu();
  closeBrowseMenu();
  volumePopover.hidePopover();
  document.body.classList.toggle("immersive", immersive);
  caption();
  if (native) call("immersive", { enabled: immersive });
  else if (immersive)
    document.documentElement.requestFullscreen?.().catch(() => toast("Fullscreen is unavailable"));
  else if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
}
window.naviglassExitFullView = () => { if (immersive) setImmersive(false); };
function restorePortraitMode() {
  if (innerHeight >= innerWidth) window.naviglassExitFullView();
}
$("fullscreen").onclick = () => setImmersive(!immersive);
window.addEventListener("resize", restorePortraitMode);
matchMedia("(orientation: portrait)").addEventListener("change", restorePortraitMode);
document.addEventListener("fullscreenchange", () => {
  if (!native && !document.fullscreenElement) {
    immersive = false;
    document.body.classList.remove("immersive");
    caption();
  }
});
document.body.classList.add("coverView");
async function settingsPage(epoch) {
  const data = await call("serverStatus");
  if (epoch !== viewEpoch) return;
  const body = heading(
    "Settings",
    "Music access is read-only. Local songs work offline.",
  );
  const logo = document.createElement("div");
  logo.className = "settingsLogo";
  const image = document.createElement("img"),
    name = document.createElement("strong");
  image.src = "icon.svg";
  image.alt = "";
  name.textContent = "NaviGlass";
  logo.append(image, name);
  body.append(logo);
  const actions = document.createElement("div");
  actions.className = "sourceButtons";
  actions.append(
    button("Device music", () => call("device")),
    button("Choose folder", () => call("folder")),
    button("Refresh local source", () => call("rescan")),
    button("Local library", () => call("serverLocal")),
    button("Connect Navidrome", connectServer),
  );
  if (data?.connected) {
    actions.append(
      button("Use Navidrome", () => call("serverUse")),
      button("Refresh Navidrome library", async () => {
        toast("Refreshing Navidrome albums, favourites and stations…");
        await call("serverMore");
      }),
      button("Disconnect and forget login", () => call("serverDisconnect")),
    );
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent =
      "Connected: " +
      data.url +
      " · " +
      data.username +
      ". " +
      (data.active ? "Browsing Navidrome." : "Browsing local music.");
    body.append(p);
  }
  body.append(actions);
  const hint = document.createElement("p");
  hint.className = "hint";
  hint.textContent =
    "Use your public HTTPS Navidrome address; a publicly accessible server does not require Tailscale. Android logins are encrypted with Keystore. Navidrome favourites sync with your server. Server playlists and saved stations are imported; new playlists and directory-station saves stay on this device. This browser preview is for UI review and does not use server credentials.";
  body.append(hint);
}
function connectServer() {
  openPanel("Connect Navidrome", "connect");
  const form = document.createElement("form");
  const fields = {};
  let savedLogin = null;
  for (const [key, label, type, placeholder] of [
    ["url", "Server URL", "url", "https://music.example.com"],
    ["username", "Username", "text", "Your Navidrome username"],
    ["password", "Password", "password", "Your Navidrome password"],
  ]) {
    const l = document.createElement("label"),
      input = document.createElement("input");
    l.textContent = label;
    input.type = type;
    input.placeholder = placeholder;
    input.required = true;
    input.autocomplete =
      key === "password"
        ? "current-password"
        : key === "username"
          ? "username"
          : "off";
    input.name = key;
    input.id = "server-" + key;
    l.htmlFor = input.id;
    fields[key] = input;
    form.append(l, input);
  }
  const hint = document.createElement("p");
  hint.className = "hint";
  hint.textContent =
    "Use your HTTPS Navidrome address. Tailscale-only servers need Tailscale connected on this phone. Remembered logins are encrypted on this phone.";
  const rememberLabel = document.createElement("label");
  rememberLabel.className = "rememberLogin";
  const remember = document.createElement("input");
  remember.type = "checkbox";
  remember.id = "server-remember";
  remember.checked = true;
  rememberLabel.append(remember, document.createTextNode("Remember login"));
  const save = button("Connect", null, "primary");
  save.type = "submit";
  const error = document.createElement("p");
  error.className = "error";
  error.setAttribute("role", "alert");
  form.append(rememberLabel, hint, error, save);
  const updateSavedPassword = () => {
    const matches = savedLogin?.passwordSaved &&
      fields.url.value.trim().replace(/\/+$/, "") === savedLogin.url &&
      fields.username.value.trim() === savedLogin.username;
    fields.password.required = !matches;
    fields.password.placeholder = matches ? "Saved password" : "Your Navidrome password";
  };
  fields.url.oninput = fields.username.oninput = updateSavedPassword;
  remember.onchange = async () => {
    if (remember.checked || !native) return;
    savedLogin = null;
    updateSavedPassword();
    save.disabled = true;
    try { await request("serverForgetLogin"); }
    catch (e) { error.textContent = e.message; }
    finally { save.disabled = false; }
  };
  form.onsubmit = async (e) => {
    e.preventDefault();
    if (!native) {
      error.textContent =
        "Browser UI preview only: no login request was sent. Navidrome authentication uses the Android native connection service; use the test APK to connect. This is not a network error.";
      fields.password.value = "";
      return;
    }
    save.disabled = true;
    save.textContent = "Connecting…";
    try {
      await request("serverConnect", {
        url: fields.url.value,
        username: fields.username.value,
        password: fields.password.value,
        remember: remember.checked,
      });
      fields.password.value = "";
      window.closePanel();
      switchView("albums");
      toast(
        "Connected to Navidrome. Your albums, favourites, playlists and stations are available.",
      );
    } catch (e) {
      error.textContent = e.message;
    } finally {
      save.disabled = false;
      save.textContent = "Connect";
    }
  };
  $("panelBody").append(form);
  if (native) {
    save.disabled = true;
    request("serverLogin").then((saved) => {
      if (panelMode !== "connect" || $("server-url") !== fields.url) return;
      savedLogin = saved;
      if (!fields.url.value) fields.url.value = saved.url || "";
      if (!fields.username.value) fields.username.value = saved.username || "";
      remember.checked = saved.remember;
      updateSavedPassword();
    }).catch((e) => { error.textContent = e.message; })
      .finally(() => { save.disabled = false; });
  }
  fields.url.focus();
}
async function radioBrowser(epoch, savedOnly = false) {
  const data = await call(savedOnly ? "radioSaved" : "radioStations");
  if (epoch !== viewEpoch || !data) return;
  const body = heading(savedOnly ? "Saved stations" : "Internet radio", "", data.stations.length);
  const filters = document.createElement("form");
  filters.className = "radioFilters";
  const fields = {};
  for (const [key, label] of [["name", "Station name"]]) {
    const input = document.createElement("input");
    input.type = "search";
    input.placeholder = label;
    input.setAttribute("aria-label", label);
    fields[key] = input;
    filters.append(input);
  }
  const actions = document.createElement("div");
  actions.className = "pageActions";
  const status = document.createElement("p");
  status.className = "hint";
  status.setAttribute("role", "status");
  status.setAttribute("aria-live", "polite");
  const rows = document.createElement("div");
  let offset = 0,
    revision = 0,
    list = data.stations;
  function draw(stations) {
    list = stations;
    if (!savedOnly) radioResultCount = stations.length;
    if (!body.querySelector(".menuCount")) { const h = body.querySelector("h1"); const sep = document.createElement("span"), total = document.createElement("span"); sep.className = "menuSeparator"; sep.textContent = "|"; total.className = "menuCount"; h.append(sep,total); }
    updateMenuCount(stations.length);
    rows.replaceChildren();
    for (const s of stations) {
      const row = document.createElement("div"),
        label = document.createElement("div"),
        title = document.createElement("strong"),
        detail = document.createElement("small");
      row.className = "station";
      row.dataset.id = s.id;
      title.textContent = s.title;
      detail.textContent = [s.genre, s.provider].filter(Boolean).join(" · ");
      label.append(title);
      const active = state.track?.id === s.id,
        playing = state.playing || state.buffering;
      row.classList.toggle("active", active);
      const play = button(active && playing ? "Pause" : "Listen", () =>
        state.track?.id === s.id && (state.playing || state.buffering)
          ? call("toggle")
          : (setStationCollection(list, list.indexOf(s)),
            call("radioPlay", { station: s.id, ids: list.map((s) => s.id) })),
      );
      play.setAttribute("aria-label", "Listen to " + s.title);
      const artwork = document.createElement("img");
      artwork.className = "stationArt";
      artwork.src = stationCover(s);
      artwork.alt = "";
      artwork.onerror = () => {
        artwork.onerror = null;
        artwork.classList.add("fallback");
        artwork.src = "icons/music.svg";
      };
      const save = button(s.saved ? "Saved" : "Save", async () => {
        save.disabled = true;
        const result = await call("radioSave", {
          station: s.id,
          save: !s.saved,
        });
        save.disabled = false;
        if (!result) return;
        s.saved = result.saved;
        save.textContent = s.saved ? "Saved" : "Save";
        save.setAttribute("aria-pressed", String(s.saved));
        if (savedOnly && !s.saved) switchView("radioSaved");
      });
      if (s.remote) { save.disabled = true; save.title = "Saved in Navidrome"; }
      save.setAttribute("aria-label", "Save " + s.title);
      save.setAttribute("aria-pressed", String(Boolean(s.saved)));
      const more = button(
        "",
        (event) =>
          openStationMenu(event.currentTarget, s, (saved) => {
            save.textContent = saved ? "Saved" : "Save";
            save.setAttribute("aria-pressed", String(saved));
            if (savedOnly && !saved) switchView("radioSaved");
          }),
        "stationMore songMenu",
      );
      icon(more, "more");
      more.setAttribute("aria-label", "Actions for " + s.title);
      const controls = document.createElement("div");
      controls.className = "stationActions";
      controls.append(play, save, more);
      row.append(artwork, label, controls);
      rows.append(row);
    }
  }
  if (savedOnly) {
    const controls = document.createElement("div");
    controls.className = "pageActions";
    controls.append(
      button("Browse stations", () => switchView("radio")),
      button("Play saved stations", () => {
        if (list.length) {
          setStationCollection(list);
          call("radioPlay", {
            station: list[0].id,
            ids: list.map((s) => s.id),
          });
        }
      }),
    );
    body.append(controls, rows);
    draw(data.stations);
    if (!list.length) {
      const empty = document.createElement("p");
      empty.className = "hint";
      empty.textContent =
        "No saved stations yet. Search or browse stations, then choose Save.";
      body.append(empty);
      controls.lastElementChild.disabled = true;
    }
    positionBrowse();
    return;
  }
  async function search(reset) {
    if (epoch !== viewEpoch) return;
    if (reset) offset = 0;
    const rev = ++revision;
    find.disabled = true;
    more.disabled = true;
    status.textContent = "Searching the radio directory…";
    try {
      const result = await request("radioSearch", {
        ...Object.fromEntries(
          Object.entries(fields).map(([k, v]) => [k, v.value]),
        ),
        offset,
      });
      if (epoch !== viewEpoch || rev !== revision) return;
      draw(
        reset
          ? result.stations
          : [
              ...list,
              ...result.stations.filter(
                (s) => !list.some((item) => item.id === s.id),
              ),
            ],
      );
      status.textContent =
        result.stations.length +
        " HTTPS stations on this page. Availability depends on the broadcaster.";
      more.disabled = false;
      more.hidden = result.stations.length < 60;
      if (!result.stations.length)
        status.textContent =
          "No playable stations found. Try another station name.";
      // Keep the station search box visible while results update.
      positionBrowse();
    } catch (e) {
      if (epoch === viewEpoch) status.textContent = e.message;
    } finally {
      if (epoch === viewEpoch) find.disabled = false;
    }
  }
  const find = button("Search", () => search(true)),
    more = button("Load more stations", () => {
      offset += 60;
      search(false);
    });
  filters.onsubmit = (e) => {
    e.preventDefault();
    clearTimeout(radioTimer);
    search(true);
  };
  more.disabled = true;
  more.hidden = true;
  find.hidden = true;
  actions.append(
    button("Saved stations", () => switchView("radioSaved")),
    find,
    more,
    button("Starter stations", () => {
      revision++;
      offset = 0;
      draw(data.stations);
      status.textContent = "Four starter SomaFM stations.";
      more.disabled = true;
    }),
  );
  body.append(filters, actions, status, rows);
  draw(list);
  status.textContent =
    "Four starter SomaFM stations. Search the internet directory for more.";
  let radioTimer;
  fields.name.oninput = () => {
    clearTimeout(radioTimer);
    revision++;
    const value = fields.name.value.trim();
    if (!value) {
      draw(data.stations);
      status.textContent = "Starter stations";
      more.hidden = true;
      return;
    }
    if (value.length < 2) {
      status.textContent = "Type at least two characters";
      return;
    }
    radioTimer = setTimeout(() => search(true), 320);
  };
}
