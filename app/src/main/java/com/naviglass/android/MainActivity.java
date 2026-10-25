package com.naviglass.android;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends androidx.activity.ComponentActivity {
    static final String ORIGIN="https://appassets.androidplatform.net";
    private WebView web;
    private JavaScriptReplyProxy bridge;
    private MediaController player;
    private ListenableFuture<MediaController> controllerFuture;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService syncWorker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncRunning=new AtomicBoolean();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean scanning,foreground,immersivePlayer;
    private JSONObject scanStatus;
    private JSONObject summaryCache;
    private final java.util.concurrent.atomic.AtomicInteger collectionRequest=new java.util.concurrent.atomic.AtomicInteger();
    private String scanError="";
    private final Runnable ticker=new Runnable(){public void run(){if(foreground){state();handler.postDelayed(this,500);}}};
    private final Runnable serverRefresh=new Runnable(){public void run(){if(!foreground)return;if(NetworkSources.active(MainActivity.this)&&syncRunning.compareAndSet(false,true))syncWorker.execute(()->{try{if(NetworkSources.refreshIfDue(MainActivity.this))runOnUiThread(()->{if(!isDestroyed())catalog();});}catch(Exception ignored){/* Keep the last complete snapshot when offline. */}finally{syncRunning.set(false);}});handler.postDelayed(this,60000);}};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);web=new WebView(this);
        android.widget.FrameLayout root=new android.widget.FrameLayout(this);root.setBackgroundColor(0xff08080f);root.addView(web);setContentView(root);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout()|androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        getOnBackPressedDispatcher().addCallback(this,new androidx.activity.OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){web.evaluateJavascript("window.closePanel && window.closePanel()",value->{
                if(!"true".equals(value)){setEnabled(false);getOnBackPressedDispatcher().onBackPressed();setEnabled(true);}
            });}
        });
        web.setBackgroundColor(0xff08080f);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // Honour the original player's fitted compact labels; WebView defaults to an 8px floor.
        web.getSettings().setMinimumFontSize(1);
        web.getSettings().setMinimumLogicalFontSize(1);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebViewAssetLoader assets=new WebViewAssetLoader.Builder().addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/art/",path->{
                if(!path.matches("[a-zA-Z0-9_-]{1,80}\\.jpg"))return missing();
                String coverId=path.substring(0,path.length()-4);File f;
                try{f=coverId.startsWith("nd_")?NetworkSources.cover(this,coverId):Library.get(this).artwork(coverId);}catch(Exception e){return missing();}
                try {return f==null?missing():new WebResourceResponse("image/jpeg",null,new FileInputStream(f));}catch(Exception e){return missing();}
            }).build();
        final WebViewAssetLoader artAssets=new WebViewAssetLoader.Builder().addPathHandler("/radio-art/",path->{try{if(!path.matches("radio:[a-z0-9-]{1,50}\\.jpg"))return missing();File f=RadioDirectory.artwork(this,path.substring(0,path.length()-4));return f==null?missing():new WebResourceResponse("image/jpeg",null,new FileInputStream(f));}catch(Exception e){return missing();}}).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();if(!"https".equals(u.getScheme())||!"appassets.androidplatform.net".equals(u.getHost()))return missing();
                WebResourceResponse response=u.getPath().startsWith("/radio-art/")?artAssets.shouldInterceptRequest(u):assets.shouldInterceptRequest(u);return response==null?missing():response;
            }
        });
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
            android.widget.TextView message=new android.widget.TextView(this);message.setText("Please update Android System WebView, then reopen NaviGlass.");
            message.setTextColor(0xffc8cedb);message.setPadding(24,80,24,24);setContentView(message);return;
        }
        WebViewCompat.addWebMessageListener(web,"nativeBridge",Collections.singleton(ORIGIN),(v,message,origin,isMain,reply)->{
            if(!isMain||!ORIGIN.equals(origin.toString()))return;bridge=reply;
            try{String raw=message.getData();if(raw==null||raw.length()>1048576)throw new IllegalArgumentException("Request too large");
                JSONObject request=new JSONObject(raw);try{handle(request);}catch(Exception e){failure(request.optString("id"),e);}
            }catch(Exception e){event("error",new JSONObject(),friendly(e));}
        });
        controllerFuture=new MediaController.Builder(this,new SessionToken(this,new ComponentName(this,PlaybackService.class))).setListener(new MediaController.Listener(){
            @Override public void onExtrasChanged(MediaController controller,Bundle extras){try{if(extras.containsKey("audioOutputs"))event("audioOutputs",new JSONObject(extras.getString("audioOutputs")),"");}catch(Exception ignored){}}
        }).buildAsync();
        controllerFuture.addListener(()->{try{player=controllerFuture.get();player.addListener(new Player.Listener(){
            @Override public void onEvents(Player p,Player.Events events){state();}
        });state();}catch(Exception e){event("error",new JSONObject(),"Cannot start audio service");}},getMainExecutor());
        web.loadUrl(ORIGIN+"/assets/index.html");updateSystemBars();
    }
    private static WebResourceResponse missing(){return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private void handle(JSONObject request)throws Exception {
        String id=request.optString("id");if(id.length()>80)throw new IllegalArgumentException("Invalid request");
        String action=request.optString("action");
        switch(action){
            case "browseCancel":collectionRequest.incrementAndGet();respond(id,new JSONObject());break;
            case "ready": respond(id,new JSONObject().put("native",true).put("scanning",scanning));catalog();state();if(scanStatus!=null)event("progress",scanStatus,scanError);break;
            case "device": NetworkSources.local(this);permissionAndScan();respond(id,new JSONObject());break;
            case "folder": NetworkSources.local(this);chooseFolder();respond(id,new JSONObject());break;
            case "rescan": if(scanning)throw new IllegalStateException("A scan is already running");
                String tree=getPreferences(MODE_PRIVATE).getString("tree","");if(tree.isEmpty())permissionAndScan();else scan(Uri.parse(tree));respond(id,new JSONObject());break;
            case "cancel": cancelled.set(true);respond(id,new JSONObject());break;
            case "radioStations": respond(id,new JSONObject().put("stations",RadioDirectory.stations(this)));break;
            case "radioPlay":
                requirePlayer();String station=request.getString("station");java.util.List<MediaItem> radioQueue=RadioDirectory.queue(this);int stationIndex=-1;
                if(request.has("ids")){JSONArray radioIds=request.getJSONArray("ids");if(radioIds.length()>200)throw new IllegalArgumentException("Too many radio stations");radioQueue=new ArrayList<>();for(int i=0;i<radioIds.length();i++){MediaItem item=RadioDirectory.item(this,radioIds.getString(i));if(item==null)throw new IllegalArgumentException("Unknown station");radioQueue.add(item);}}
                for(int i=0;i<radioQueue.size();i++)if(radioQueue.get(i).mediaId.equals(station))stationIndex=i;
                if(stationIndex<0)throw new IllegalArgumentException("Unknown radio station");
                getSharedPreferences("playback",0).edit().putString("albumOrder","[]").putLong("contextRevision",System.nanoTime()).apply();
                player.setShuffleModeEnabled(false);player.setRepeatMode(Player.REPEAT_MODE_OFF);player.setMediaItems(radioQueue,stationIndex,0);player.prepare();player.play();respond(id,new JSONObject());
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},43);break;
            case "play":
                requirePlayer();JSONArray ids=request.getJSONArray("ids");if(ids.length()==0||ids.length()>10000)throw new IllegalArgumentException("Choose 1–10000 songs");
                ArrayList<MediaItem> items=new ArrayList<>();for(int i=0;i<ids.length();i++){Track t=Library.get(this).track(ids.getString(i));MediaItem item=NetworkSources.item(this,t);if(item==null)throw new IllegalArgumentException("Song unavailable; refresh library or reconnect your server");items.add(item);}
                int index=request.optInt("index",0);if(index<0||index>=items.size())throw new IllegalArgumentException("Invalid song selection");
                JSONArray albumOrder=request.optJSONArray("albumIds");if(albumOrder==null)albumOrder=new JSONArray();if(albumOrder.length()>10000)throw new IllegalArgumentException("Too many albums");for(int i=0;i<albumOrder.length();i++)if(albumOrder.getString(i).length()>200)throw new IllegalArgumentException("Invalid album ID");
                Track chosen=Library.get(this).track(ids.getString(index));for(int i=0;i<items.size();i++)if(!Library.get(this).track(items.get(i).mediaId).albumId.equals(chosen.albumId)){albumOrder=new JSONArray();break;}
                getSharedPreferences("playback",0).edit().putString("albumOrder",albumOrder.toString()).putLong("contextRevision",System.nanoTime()).apply();
                Bundle queueArgs=new Bundle();ArrayList<String> queueIds=new ArrayList<>();for(int i=0;i<ids.length();i++)queueIds.add(ids.getString(i));queueArgs.putStringArrayList("ids",queueIds);queueArgs.putInt("index",index);
                ListenableFuture<androidx.media3.session.SessionResult> started=player.sendCustomCommand(new androidx.media3.session.SessionCommand(PlaybackService.PLAY_QUEUE,Bundle.EMPTY),queueArgs);
                started.addListener(()->{try{if(started.get().resultCode!=androidx.media3.session.SessionResult.RESULT_SUCCESS)throw new IllegalStateException("Audio could not start; check source access and format");respond(id,new JSONObject());}catch(Exception e){failure(id,e);}},getMainExecutor());break;
            case "toggle": requirePlayer();if(player.getPlayWhenReady())player.pause();else{player.prepare();player.play();}respond(id,new JSONObject());break;
            case "next": requirePlayer();if(player.hasNextMediaItem()){player.seekToNextMediaItem();player.play();}else adjacentAlbum(1);respond(id,new JSONObject());break;
            case "previous": requirePlayer();if(player.hasPreviousMediaItem()){player.seekToPreviousMediaItem();player.play();}else adjacentAlbum(-1);respond(id,new JSONObject());break;
            case "seek": requirePlayer();long duration=player.getDuration();long position=Math.max(0,request.optLong("position"));player.seekTo(duration>0?Math.min(position,duration):position);respond(id,new JSONObject());break;
            case "shuffle": requirePlayer();player.setShuffleModeEnabled(!player.getShuffleModeEnabled());respond(id,new JSONObject());break;
            case "repeat": requirePlayer();player.setRepeatMode(AlbumPlaybackPolicy.nextRepeatMode(player.getRepeatMode()));respond(id,new JSONObject());break;
            case "volume":requirePlayer();player.setVolume((float)Math.max(0,Math.min(1,request.optDouble("value",1))));respond(id,new JSONObject());break;
            case "audioOutputs":case "audioOutputSelect":
                requirePlayer();Bundle outputArgs=new Bundle();outputArgs.putString("device",request.optString("device"));
                String outputCommand=action.equals("audioOutputSelect")?PlaybackService.SELECT_AUDIO_OUTPUT:PlaybackService.AUDIO_OUTPUTS;
                ListenableFuture<androidx.media3.session.SessionResult> outputResult=player.sendCustomCommand(new androidx.media3.session.SessionCommand(outputCommand,Bundle.EMPTY),outputArgs);
                outputResult.addListener(()->{try{androidx.media3.session.SessionResult result=outputResult.get();if(result.resultCode!=androidx.media3.session.SessionResult.RESULT_SUCCESS)throw new IllegalArgumentException(result.extras.getString("message","Audio output selection is unavailable"));respond(id,new JSONObject(result.extras.getString("audioOutputs")));}catch(Exception e){failure(id,e);}},getMainExecutor());break;
            case "immersive":immersivePlayer=request.optBoolean("enabled");updateSystemBars();respond(id,new JSONObject());break;
            case "serverDisconnect":if(player!=null&&player.getCurrentMediaItem()!=null&&player.getCurrentMediaItem().mediaId.startsWith("nd_")){player.pause();player.clearMediaItems();}NetworkSources.disconnect(this);respond(id,new JSONObject());catalog();break;
            default:
                final int collectionToken=collectionRequest.get();
                worker.execute(()->{try{
                    Library library=Library.get(this);JSONObject result=new JSONObject();
                    switch(action){
                        case "tracks": String mode=request.optString("mode","album");if(!java.util.Arrays.asList("album","all","favourites","playlist","search","artist","serverPlaylist").contains(mode))throw new IllegalArgumentException("Unknown view");
                            String value=request.optString("value").substring(0,Math.min(request.optString("value").length(),200));result.put("albumStarred",value.startsWith("nd:")?NetworkSources.albumStarred(this,value):getSharedPreferences("album-favourites",0).getBoolean("local:"+value,false));result.put("tracks",NetworkSources.active(this)||mode.equals("serverPlaylist")||value.startsWith("nd:")?NetworkSources.tracks(this,mode,value,()->collectionToken!=collectionRequest.get()):library.tracks(mode,value));break;
                        case "artistAlbums":String artist=request.optString("value");JSONArray artistList=new JSONArray();if(NetworkSources.active(this))artistList=NetworkSources.artistAlbums(this,artist);else{java.util.HashSet<String> artistIds=new java.util.HashSet<>();JSONArray songs=library.tracks("artist",artist);for(int i=0;i<songs.length();i++)artistIds.add(songs.getJSONObject(i).getString("albumId"));JSONArray localAlbums=library.albums();for(int i=0;i<localAlbums.length();i++)if(artistIds.contains(localAlbums.getJSONObject(i).getString("id")))artistList.put(localAlbums.getJSONObject(i));}result.put("albums",artistList);break;
                        case "albumFavourite":String album=request.getString("album");if(album.length()>200)throw new IllegalArgumentException("Invalid album");String albumKey=(NetworkSources.active(this)?NetworkSources.account(this).getString("url")+":" : "local:")+album;android.content.SharedPreferences favAlbums=getSharedPreferences("album-favourites",0);boolean starred=album.startsWith("nd:")?NetworkSources.toggleAlbum(this,album):!favAlbums.getBoolean(albumKey,false);if(!album.startsWith("nd:"))favAlbums.edit().putBoolean(albumKey,starred).apply();result.put("albumStarred",starred);break;
                        case "favourite":result.put("favourite",NetworkSources.toggleSong(this,request.getString("track")));break;
                        case "playlists":JSONArray lists=library.playlists();if(NetworkSources.active(this)){JSONArray remote=NetworkSources.playlists(this);for(int i=0;i<remote.length();i++)lists.put(remote.getJSONObject(i));}result.put("playlists",lists);break;
                        case "trackInfo":result.put("track",TrackDetails.read(this,request.getString("track")));break;
                        case "radioSaved":result.put("stations",RadioDirectory.saved(this));break;
                        case "radioSave":result.put("saved",RadioDirectory.save(this,request.getString("station"),request.optBoolean("save")));break;
                        case "radioSearch":result.put("stations",RadioDirectory.search(this,request));break;
                        case "serverStatus":result=NetworkSources.status(this);break;
                        case "serverLogin":result=NetworkSources.loginFields(this);break;
                        case "serverForgetLogin":NetworkSources.forgetLogin(this);break;
                        case "serverConnect":NetworkSources.connect(this,request);result=NetworkSources.status(this);runOnUiThread(this::catalog);break;
                        case "serverMore":result.put("albums",NetworkSources.more(this));runOnUiThread(this::catalog);break;
                        case "serverLocal":NetworkSources.local(this);runOnUiThread(this::catalog);break;
                        case "serverUse":NetworkSources.account(this);getSharedPreferences("server",MODE_PRIVATE).edit().putBoolean("active",true).apply();runOnUiThread(this::catalog);break;
                        case "createPlaylist":result.put("playlist",library.createPlaylist(request.getString("name")));break;
                        case "addPlaylist":library.addToPlaylist(request.getString("playlist"),request.getString("track"));break;
                        default:throw new IllegalArgumentException("Unknown action");
                    }
                    if(!java.util.Arrays.asList("tracks","artistAlbums","playlists","trackInfo","radioSaved","radioSearch","serverStatus","serverLogin","serverForgetLogin").contains(action))summaryCache=null;
                    result.put("summary",summary());final JSONObject response=result;runOnUiThread(()->respond(id,response));
                }catch(Exception e){runOnUiThread(()->failure(id,e));}});
        }
    }
    private void adjacentAlbum(int direction){Bundle args=new Bundle();args.putInt("direction",direction);player.sendCustomCommand(new androidx.media3.session.SessionCommand(PlaybackService.ADJACENT,Bundle.EMPTY),args);}
    private void requirePlayer(){if(player==null)throw new IllegalStateException("Audio service is still connecting");}
    private void respond(String id,JSONObject data){try{send(new JSONObject().put("type","response").put("id",id).put("data",data));}catch(Exception ignored){}}
    private void failure(String id,Exception e){try{send(new JSONObject().put("type","response").put("id",id).put("error",friendly(e)));}catch(Exception ignored){}}
    private String friendly(Exception e){if(e instanceof SecurityException)return "Library access expired or was denied. Choose the folder again or grant music permission.";
        if(e instanceof java.util.concurrent.CancellationException)return "Scan cancelled. Previously indexed songs were kept.";
        String text=e.getMessage();return text==null?"Operation failed; try choosing the source again.":text.substring(0,Math.min(240,text.length()));}
    private void event(String type,JSONObject data,String error){try{send(new JSONObject().put("type",type).put("data",data).put("error",error));}catch(Exception ignored){}}
    private void send(JSONObject data){if(bridge!=null&&!isDestroyed()&&WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))bridge.postMessage(data.toString());}
    private void state(){if(player==null||bridge==null||!foreground)return;try{
        JSONObject s=new JSONObject().put("playing",player.isPlaying()).put("buffering",player.getPlayWhenReady()&&player.getPlaybackState()==Player.STATE_BUFFERING)
            .put("position",Math.max(0,player.getCurrentPosition())).put("duration",Math.max(0,player.getDuration()==C.TIME_UNSET?0:player.getDuration()))
            .put("shuffle",player.getShuffleModeEnabled()).put("repeat",player.getRepeatMode()).put("volume",player.getVolume());
        MediaItem current=player.getCurrentMediaItem();Track t=current==null?null:Library.get(this).track(current.mediaId);
        if(t!=null){s.put("track",t.json(NetworkSources.favourite(this,t)));if(s.optLong("duration")==0)s.put("duration",t.duration);}
        else if(current!=null){JSONObject radio=RadioDirectory.state(this,current.mediaId);if(radio!=null){s.put("track",radio);s.put("duration",0).put("position",0);}}
        if(player.getPlayerError()!=null)s.put("error",current!=null&&current.mediaId.startsWith("radio:")?"Station unavailable. Check your internet connection or try another station.":"This song could not be played. Check source access and audio format.");
        event("state",s,"");
    }catch(Exception ignored){}}
    private JSONObject summary()throws Exception{
        if(summaryCache!=null)return new JSONObject(summaryCache.toString());
        JSONObject out=new JSONObject();JSONArray list=NetworkSources.active(this)?NetworkSources.albums(this):Library.get(this).albums();long songs=0;int favourites=0;java.util.HashSet<String> artists=new java.util.HashSet<>();for(int i=0;i<list.length();i++){JSONObject a=list.getJSONObject(i);songs+=a.optInt("count");artists.add(a.optString("artist"));if(NetworkSources.active(this)?a.optBoolean("starred"):getSharedPreferences("album-favourites",0).getBoolean("local:"+a.getString("id"),false))favourites++;}artists.remove("");summaryCache=out.put("albums",list.length()).put("favouriteAlbums",favourites).put("songs",songs).put("favouriteSongs",NetworkSources.active(this)?NetworkSources.cached(this,"starredSongs").length():Library.get(this).tracks("favourites","").length()).put("artists",NetworkSources.active(this)?NetworkSources.cached(this,"artists").length():artists.size()).put("playlists",Library.get(this).playlists().length()+(NetworkSources.active(this)?NetworkSources.cached(this,"playlists").length():0)).put("radios",RadioDirectory.saved(this).length());return new JSONObject(summaryCache.toString());
    }
    private void catalog(){worker.execute(()->{try{summaryCache=null;if(NetworkSources.active(this))NetworkSources.ensureSynced(this);JSONArray list=NetworkSources.active(this)?NetworkSources.albums(this):Library.get(this).albums();String prefix=NetworkSources.active(this)?NetworkSources.account(this).getString("url")+":" : "local:";for(int i=0;i<list.length();i++){JSONObject album=list.getJSONObject(i);if(!NetworkSources.active(this))album.put("starred",getSharedPreferences("album-favourites",0).getBoolean(prefix+album.getString("id"),false));}JSONObject data=new JSONObject().put("albums",list).put("remote",NetworkSources.active(this)).put("source",NetworkSources.source(this)).put("summary",summary()).put("artists",NetworkSources.active(this)?NetworkSources.cached(this,"artists"):new JSONArray()).put("remembered",list.length()>0||NetworkSources.active(this)||getSharedPreferences("library-source",0).getBoolean("configured",false)||!getPreferences(MODE_PRIVATE).getString("tree","").isEmpty());runOnUiThread(()->event("catalog",data,""));}
        catch(Exception e){runOnUiThread(()->event("error",new JSONObject(),friendly(e)));}});}
    private void permissionAndScan(){if(scanning){event("error",new JSONObject(),"A scan is already running");return;}
        String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE;
        if(checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED)scan(null);else requestPermissions(new String[]{permission},41);}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);
        if(code==41){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)scan(null);else event("error",new JSONObject(),"Music permission denied. You can still choose a specific folder.");}}
    private void chooseFolder(){if(scanning)throw new IllegalStateException("Wait for this scan or cancel it first");
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),42);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==42&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri tree=data.getData();try{
            getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getPreferences(MODE_PRIVATE).edit().putString("tree",tree.toString()).apply();scan(tree);
        }catch(Exception e){event("error",new JSONObject(),friendly(e));}}}
    private void scan(Uri tree){if(scanning)return;scanning=true;cancelled.set(false);scanError="";progress(0,0,0,"Starting scan",false);
        worker.execute(()->{try {LibraryScanner scanner=new LibraryScanner(this,Library.get(this),cancelled);
            LibraryScanner.Progress updates=(checked,indexed,skipped,label)->runOnUiThread(()->progress(checked,indexed,skipped,label,false));
            if(tree==null)scanner.device(updates);else scanner.folder(tree,updates);
            runOnUiThread(()->{getSharedPreferences("library-source",0).edit().putBoolean("configured",true).apply();scanning=false;progressDone("");catalog();
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},43);});
        }catch(Exception e){runOnUiThread(()->{scanning=false;progressDone(friendly(e));});}});
    }
    private void progress(long checked,long indexed,long skipped,String label,boolean done){try{scanStatus=new JSONObject().put("checked",checked).put("indexed",indexed).put("skipped",skipped).put("label",label).put("done",done);event("progress",scanStatus,scanError);}catch(Exception ignored){}}
    private void progressDone(String error){scanError=error;progress(scanStatus.optLong("checked"),scanStatus.optLong("indexed"),scanStatus.optLong("skipped"),error.isEmpty()?"Library ready":"Scan stopped",true);}
    private void updateSystemBars(){if(web==null)return;androidx.core.view.WindowInsetsControllerCompat controls=androidx.core.view.WindowCompat.getInsetsController(getWindow(),web);controls.setSystemBarsBehavior(androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);if(immersivePlayer)controls.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());else{controls.show(androidx.core.view.WindowInsetsCompat.Type.navigationBars());if(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE)controls.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars());else controls.show(androidx.core.view.WindowInsetsCompat.Type.statusBars());}}
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration){super.onConfigurationChanged(configuration);if(configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT){immersivePlayer=false;if(web!=null)web.evaluateJavascript("window.naviglassExitFullView?.()",null);}updateSystemBars();}
    @Override protected void onResume(){super.onResume();updateSystemBars();foreground=true;web.onResume();handler.removeCallbacks(ticker);handler.post(ticker);handler.removeCallbacks(serverRefresh);handler.post(serverRefresh);}
    @Override protected void onPause(){foreground=false;handler.removeCallbacks(ticker);handler.removeCallbacks(serverRefresh);web.onPause();super.onPause();}
    @Override protected void onDestroy(){foreground=false;cancelled.set(true);handler.removeCallbacksAndMessages(null);worker.shutdownNow();syncWorker.shutdownNow();
        if(controllerFuture!=null)MediaController.releaseFuture(controllerFuture);if(web!=null){bridge=null;web.stopLoading();android.view.ViewParent parent=web.getParent();if(parent instanceof android.view.ViewGroup)((android.view.ViewGroup)parent).removeView(web);web.destroy();}super.onDestroy();}
}
