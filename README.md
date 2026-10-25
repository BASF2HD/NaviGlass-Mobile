# NaviGlass — Mobile

**[Visit the NaviGlass Mobile website](https://basf2hd.github.io/NaviGlass-Mobile/)**

A dedicated Android music player with NaviGlass's black Cover Flow, reflections, top seek bar and bottom playback/browse controls. Local music works without an account; Navidrome and internet radio are optional.

This is a public early release, distributed directly from GitHub rather than Google Play. The app name is **NaviGlass**; Android identifies the current platform, not the product name. This mobile repository hosts the Android APK; iOS is planned next. Development stays separate from the original NaviGlassPlayer and PiTunes apps.

**Download: [Android 0.2.5 APK](https://github.com/BASF2HD/NaviGlass-Mobile/releases/download/v0.2.5/NaviGlass-Android-0.2.5.apk)** · **[Latest release and checksum](https://github.com/BASF2HD/NaviGlass-Mobile/releases/latest)**. Android 10+ is required.

**Current status: refreshed 0.2.5 Android release (same version).** All browse menus use compact single-line choices, visible selection ticks and dynamic `Title | number` totals. Navidrome loads the complete album catalog, server favourites, artists, playlists and saved radio stations. Favourite songs grouped by album continue through the selected favourite queue. Remote playback negotiates supported formats with Navidrome; unsupported formats can be converted by the server. Synthetic M4A/AAC, WAV and FLAC audio renders through the native Android player, including seeking and background checks. VersionName 0.2.5, versionCode 8, package ID and development signer remain unchanged. The reported Honor-phone silence still requires a listening check on that phone.

<img src="docs/screenshots/portrait.png" alt="Portrait player with synthetic albums" width="300">

![Landscape player with compact controls and wider seek bar](docs/screenshots/landscape.png)

<img src="docs/screenshots/modes-portrait.png" alt="Shuffle and repeat highlighted when enabled" width="300"> <img src="docs/screenshots/volume-portrait.png" alt="Vertical volume control opens directly below the speaker" width="300">

<img src="docs/screenshots/drawer-portrait.png" alt="Full-width portrait song drawer with album favourite header" width="300">

<img src="docs/screenshots/search-no-match.png" alt="Strict title-prefix search for R correctly shows No matching songs" width="300">

<img src="docs/screenshots/compact-menu.png" alt="Compact Songs menu with numeric total and visible selected checkmarks" width="300">

All project screenshots use invented albums and artwork. The browser preview simulates local songs; radio uses live online audio. Real local/remote song playback uses native Media3 in the APK.

## Player and browsing

- Swipeable Cover Flow with aligned side covers, clipped reflections and smooth album transitions.
- Top audio seek slider, elapsed/duration display, repeat, search, volume and shuffle.
- Previous/play/pause/next, album-browse slider and song-drawer toggle above the six bottom navigation items: **Albums, Songs, Artists, Playlist, More, Settings**.
- Portrait and landscape layouts, artwork-relative caption sizing and bounded, ellipsised titles. Bottom navigation immediately restores the selected collection and opens its compact dropdown. All Songs browses one cover per song. Songs, Artists and Playlist offer independent Group by album choices; Artists initially shows albums without fetching every album’s songs. Selections, grouping and cover positions are remembered for each source.
- Portrait song drawer slides up from the bottom and fills the phone width. Landscape uses the original wider, centred drawer. Header contains album name, song count, favourite-album heart and close. Rows contain number, title/artist, duration and a three-dot action menu in every song list. There is no Play all button; choosing a song queues that album/list and keeps the drawer open.
- Live case-insensitive **song-title prefix search only**. Typing R shows only titles beginning with R; if none do, it shows “No matching songs.” Artist/album and title-substring matches are excluded. Typing stays focused while results update. Search and 200-song pages, a complete Navidrome artist index, source-aware favourites and named playlists. Compact menu choices show checkmarks; header totals follow All/Favourite selections and artist filters, including empty collections. Song actions include Add to playlist, Favourite and More info. Menus stay inside the viewport, including the last scrolled row.
- Square, proportional playback buttons and separated navigation underline. Immersive mode hides the bottom menus/browse slider and keeps floating previous/play/next controls. Playing-song captions show album name | year.
- Albums, Songs, Artists and Playlist menus offer title A–Z and year ascending/descending sorting; choices persist.
- Shuffle and repeat are grey when disabled and visibly highlighted when enabled; Repeat Song alone shows the 1 glyph. Shuffle changes the playback order. Repeat cycles Off → All → Song: All wraps the current queue; Song repeats the current track automatically without preventing manual skips. With shuffle/repeat off, an album queue continues into the next browsed album; previous at its first song goes to the prior album’s last song. The end of the entire collection stops.
- Playing rows use the original animated three-bar marker (static with reduced motion), with a triangle when paused. Five seconds of idle browsing returns to the playing cover only within the collection where playback was started; open menus/drawers/search or paused playback suppress this return.
- Keyboard: Left/Right browse covers, Space toggles playback, Enter plays the selected entry, Down opens the song drawer, Up/Down selects a drawer row, Enter plays it, Escape dismisses overlays. Text fields retain normal keyboard input. Mouse wheel/trackpad and browse arrows also move covers.
- Native audio focus, seeking, unplugged-headphone handling, Android media session and notifications. Locking the screen/Home retains background playback; dismissing the task from Recent Apps now stops it. Native local adjacent-album playback and background audio pass the emulator regression; actual Recent Apps dismissal still needs physical-device verification.
- Full-view button appears only in landscape. Landscape uses larger album captions, shorter square transport, a smaller browse thumb and a wider top seek strip. Portrait control sizes are unchanged. The volume button toggles a narrow vertical, light-dismiss popover directly beneath the speaker; clicking it again closes it. Outside drawer taps close the drawer without activating the cover behind it. Rotating landscape Full View into portrait restores normal controls automatically. Landscape browse-arrow end caps match the track height. Native source auto-hides the status bar in normal landscape; the browser cannot demonstrate Android system bars.
- Favicon, welcome, Settings and Android launcher use the same Cover Flow logo.
- Cover Flow advances tweens and draws in one animation frame. High-rate trackpad/wheel events are batched once per frame; clamped/same-cover navigation does no repeated layout/artwork work. Original wheel sensitivity and 420 ms move duration are retained. Renderer stops scheduling animation frames when idle or the document is hidden. Nearby artwork textures and extracted artwork are cached with limits.

## Sources

### Local music

Use **Settings → Device music** for Android's existing media index, or **Choose folder** for read-only folder access. First folder indexing must read metadata; subsequent scans reuse unchanged metadata. Scan progress and cancellation are included. No music deletion or tag/artwork writing is supported.

Android **10+**, an up-to-date Android System WebView and WebGL are required. MP3, FLAC, WAV/WAVE, M4A/M4B, AAC, Ogg/OGA and Opus are recognised. Actual playback depends on the device decoder and codec/container. DRM and archive extraction are not supported. Artwork is extracted from embedded metadata; loose folder.jpg discovery is not included yet.

### Navidrome

Use **Settings → Connect Navidrome**. Enter the HTTPS Navidrome URL, username and password on the device. The original NaviGlass homepage is recognised automatically: if its root is not a Subsonic API, Android checks the same-host **/navidrome** proxy path. You can also enter that path explicitly. Android does not require a desktop development proxy. If the app reports a connection failure, first open the same HTTPS address in the phone browser. Check address spelling, Wi-Fi/mobile data and DNS if the browser cannot reach it either. A Tailscale-only server needs Tailscale connected on that phone; a public HTTPS/Funnel server does not. DNS, certificate, timeout and reachability errors now have distinct messages.

Login uses salted-token Subsonic authentication. As in the original player, token rejection can fall back to encoded-password authentication under HTTPS only. Direct approved HTTP remains token-only. Stored credentials are encrypted with Android Keystore AES-GCM, excluded from backup and never included in web-preview storage, source code or Git. **Remember login** stores your last attempt encrypted, including after a network failure. The form restores URL and username and shows **Saved password**; native code reuses the password only for the exact server/account. Unchecking immediately removes persisted credentials while preserving a running session in memory; it cannot restore after process death. Disconnect clears remote playback and retains an opted-in login for reconnection. Native-only requests resolve authenticated artwork/streams; passwords and authenticated URLs are not sent back into the renderer.

All album pages load on connection. Existing installations upgrade their cached catalog automatically. **Refresh Navidrome library** in Settings refreshes albums, starred albums/songs, the artist index, playlists and server radio stations. Song totals come from the full catalog, independent of the 200-song browsing page. Album/song hearts update Navidrome stars while using that source; local hearts remain on the device. Server playlists and radio lists are imported for browsing and playback. Server playlist/radio editing is not implemented; new playlists and directory-radio saves remain local. Cached catalogs display immediately. While the app is visible, a separate worker checks for library changes after five minutes; opening a menu never starts a full-library network refresh. Changed albums, favourites, artists, playlists or radio metadata update the current view while preserving its selection. Settings refresh is available immediately. Album, artist and playlist detail responses are cached on-device for up to 30 minutes, scoped to the server/account and catalog generation, with a 128-response / 32 MiB limit. Detected catalog changes invalidate those responses; unchanged refreshes retain them. Song/playlist collections and nearby artwork are also reused between menu visits. Obsolete full-artist song loads stop when you choose another collection. First visits to uncached song lists still require the server; audio itself is streamed and is not downloaded for offline use.

The native audio loader asks Navidrome for a playback decision using the Android decoder capabilities and uses the returned stream token. It prefers a supported original stream or lossless FLAC conversion, with MP3 available when the server cannot supply FLAC. Older servers use raw streams for recognised supported MIME types and request FLAC for uncertain/unsupported types, including ambiguous M4A containers. Conversion requires a working server transcoder. M4A is a container and can hold AAC or ALAC; the filename alone does not identify the codec. Music files are never rewritten. Device output/Bluetooth may still resample; this is not bit-perfect certification. **A publicly accessible HTTPS server does not require Tailscale.** Only a direct private/tailnet route needs access to that network.

HTTPS is the default. The published APK is HTTPS-only and contains no private server address or preloaded account. A source build can optionally enable one explicitly approved Tailscale IPv4 HTTP host, with the exact port enforced in native validation; no broad HTTP switch is provided. No private endpoint is committed.

### Internet radio

Open **More → Internet radio browser**. Four SomaFM starter stations are bundled. Search [Radio Browser](https://docs.radio-browser.info/) with one station-name box: results load as you type, or press Enter. There are no country/language/genre filter controls. Paged results and loading/error feedback are included. Validated HTTPS progressive and HLS streams are accepted; HLS playback is bundled in native Media3. HTTP/private-literal URLs are excluded. The native directory discovers official mirrors and retries another mirror on failure. Broadcasters and directory availability vary.

Stations show provider artwork where available, with a music-icon fallback. **More → Saved stations** combines stations imported from the active Navidrome account with stations saved locally from the directory. Server stations are read-only here. Directory saves persist on this device/browser. Imported HTTP stations are listed but the published HTTPS-only APK cannot play their cleartext streams; use an HTTPS station address on the server. Local station actions offer Play, Save/Remove and More info.

Play/pause and previous/next work for radio queues. Live streams cannot be sought and do not support song shuffle/repeat/favourites. Streaming and searches use internet data. The directory and broadcasters receive your network address and may log requests; no account is required.

## USB and audio output selection

Open **Settings → Audio output / USB DAC**. Choose a connected USB DAC or another available media output, or **System default** to let Android route automatically. The native playback service applies the preference, including during background playback, without replacing the current queue or position. The choice survives Activity/service restarts. Disconnecting a saved DAC falls back to system routing and reconnecting restores it; Android may pause playback on unplug for headphone safety. Device matching avoids guessing between indistinguishable DACs. No USB driver or microphone permission is requested by the app.

This is a preferred system output, subject to Android routing policy. It does not enable exclusive or bit-perfect playback. Physical phone/DAC routing still needs a hardware check. The emulator verifies the actual Settings → native command path, selection of a real emulator output, unchanged/advancing playback, invalid-device rejection, Activity recreation and an actual playback-service restart. JVM checks cover DAC identity changes, reconnection and ambiguous devices.

## Install NaviGlass 0.2.5

Download the APK and optional SHA-256 checksum from the [0.2.5 GitHub release](https://github.com/BASF2HD/NaviGlass-Mobile/releases/tag/v0.2.5).

1. Transfer **NaviGlass-Android-0.2.5.apk** from the GitHub release to your phone. It updates the earlier APK without uninstalling.
2. Open it and permit installation from that source if Android asks. It is development-signed; device compatibility and production distribution remain under development.
3. Choose local device music/folder, or connect Navidrome in Settings. Credentials must be entered on the device; none are preloaded.
4. Swipe covers or use the browse slider. Open an album drawer and select a song.
5. Rotate the phone, use seeking, then lock it to check background playback and notification controls.
6. Check radio under More. Allow notification permission if requested.

On reopening, NaviGlass restores indexed local metadata without a new scan or repeated first-run source picker. A short “Opening your library” message may appear during restoration. Expired/moved folder permissions require choosing the folder again. The APK contains no architecture-specific native libraries and can run on ARM and x86 Android devices.

## Privacy and limits

No analytics, advertisements or music upload. No mandatory account. Local access is read-only. All scripts/libraries are bundled. The WebView permits bundled assets and authorised artwork only, with a main-frame, origin-scoped message bridge. Media sessions accept indexed/authorised track or station IDs, not arbitrary playback URLs. HTTPS redirects are refused and platform certificate validation remains enabled.

Clearing app data removes metadata, favourites, playlists and login—not music files. Optional remote access uses the configured server. A selected storage provider may itself use a network connection.

Physical-phone, SD-card, Bluetooth, large-library and manufacturer battery-management verification is still required. Emulator success is not universal device certification. Original desktop features such as advanced menus/settings, playlist synchronisation, equalizer, gapless certification, Android Auto browsing, archive support and production signing are not complete. This is an Android adaptation, not yet full feature parity with every original desktop option.

The thumbnail library grid is limited to 120 albums; Cover Flow browses all loaded albums. Searches/pages return at most 200 songs; remote prefix search filters the server’s first 200 search candidates and can miss later matches. Album pages contain at most 500 entries and are fetched through completion, with a 100,000-album guard; queues at most 10,000 songs, folder scans at most 100,000 songs and 50,000 directories. Overlapping local device/folder indexes can produce duplicate albums. Navidrome tracks/artwork need network access and are not downloaded for offline listening.

## Build and verification

Use JDK 17, Android SDK platform 36 and build tools 36.0.0. Set JAVA_HOME and ANDROID_HOME or an ignored local.properties with sdk.dir. Gradle 8.13 and its checksum, and Android Gradle Plugin 8.13.2 are pinned. First builds download dependencies from Google Maven/Maven Central.

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Output: app/build/outputs/apk/debug/app-debug.apk. No Android Studio installation is required for command-line builds.

Native integration checks require a **clean emulator**, internet for radio checks, and an isolated, ignored PKCS12 HTTPS fixture. See [validation notes](docs/VALIDATION.md) for the full native/JVM verification commands. Never run development checks against a personal music library. No production signing key is included.

For the current Mac design review, run:

```sh
node tools/preview.mjs
```

Open http://127.0.0.1:8769/?v=0.2.5 and choose **Explore demo covers**. Use portrait/landscape window sizes to review the layouts. The loopback-only server supplies a bounded, validated radio-artwork cache and live directory failover. Synthetic local songs use a simulated clock, not actual music; radio uses live audio. Navidrome login intentionally works only in the native app. No credentials enter this preview. The browser is not a substitute for native playback/system-bar/task-dismiss verification.

Current verification: APK build/lint and ten JVM checks pass; login, playback, layout and HTTPS server suites pass on the isolated emulator. They check native local queue transitions, actual OFF/ON icon brightness, native volume, A–Z strict title-prefix SQLite search, synthetic HTTPS login, full metadata imports, format negotiation/legacy fallback, native M4A/WAV/FLAC rendering, encrypted restoration, saved-login prefill/failure/retry/forget behavior and layout/touch/rotation. The 0.2.5 browser checks cover actual mouse clicks, strict prefix/no-match results, drawer dismissal, arrow heights and Full View rotation. Broader playback/keyboard/sorting/radio checks also pass for 0.2.5; live radio artwork needed one retry. Ten matched-source visual states were reviewed at 0.2.4 and are documented separately. An earlier authorized read-only personal-server login succeeded in Android; the reported phone's browser/network reachability and acoustic output still require a handset check. See the validation and design reports for evidence and limits.

See [mobile/Navidrome codebase review](docs/NAVIDROME-REVIEW.md), [original-player parity analysis](docs/ORIGINAL-PARITY.md), [design comparison](design-qa.md) and [third-party notices](THIRD-PARTY-NOTICES.md). The source is public for inspection, but no open-source licence has yet been granted for the project's own code. Third-party components retain their own licences.

## Website

The [mobile website](https://basf2hd.github.io/NaviGlass-Mobile/) provides portrait/landscape screenshots, feature and source details, Android download links, installation guidance and an honest iOS availability section. It is a buildless static site in `site/`, deployed by the pinned GitHub Pages workflow. All images are synthetic-library screenshots; the site has no analytics, external fonts or remote scripts.

## Platforms and feedback

The [main NaviGlassPlayer project](https://github.com/BASF2HD/NaviGlassPlayer) is the shared download/discovery hub. Android source and APK versions stay in this NaviGlass-Mobile repository; the APK cannot run on iOS. An iOS adaptation is next after Android review, not built or available yet. Local iOS builds require full Xcode; installable distribution needs Apple signing and a supported Apple download route.

Please report device model, Android/WebView version, portrait or landscape, steps to reproduce and a redacted screenshot in [Issues](https://github.com/BASF2HD/NaviGlass-Mobile/issues). Never include passwords, authenticated stream URLs or a private library screenshot without reviewing it first. Check shuffle/repeat, album boundaries, search, volume, rotation, background playback and Recent Apps dismissal.
