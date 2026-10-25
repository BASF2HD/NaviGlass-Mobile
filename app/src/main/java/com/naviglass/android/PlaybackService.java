package com.naviglass.android;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Audio lives outside the WebView and continues with the screen locked. */
public final class PlaybackService extends MediaSessionService {
    static final String ADJACENT="naviglass.adjacentAlbum";
    static final String PLAY_QUEUE="naviglass.playQueue";
    static final String AUDIO_OUTPUTS="naviglass.audioOutputs",SELECT_AUDIO_OUTPUT="naviglass.selectAudioOutput";
    private final java.util.concurrent.ExecutorService queueWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean advancing,closing;
    private ExoPlayer player;
    private MediaSession session;
    private AudioOutputs audioOutputs;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private final Runnable checkpoint=new Runnable(){public void run(){save();handler.postDelayed(this,5000);}};
    @androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
    @Override public void onCreate() {
        super.onCreate();prefs=getSharedPreferences("playback",MODE_PRIVATE);
        player=new ExoPlayer.Builder(this).setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(new androidx.media3.datasource.ResolvingDataSource.Factory(new androidx.media3.datasource.DefaultDataSource.Factory(this),spec->NavidromeAudio.resolve(this,spec)))).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build();
        session=new MediaSession.Builder(this,player).setSessionActivity(PendingIntent.getActivity(this,0,
            new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(new MediaSession.Callback(){
                @androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
                @Override public MediaSession.ConnectionResult onConnect(MediaSession s,MediaSession.ControllerInfo c){
                    if(!getPackageName().equals(c.getPackageName())&&!c.isTrusted())return MediaSession.ConnectionResult.reject();
                    // Media3 defaults can be read-only even for our own controller. Grant playback
                    // explicitly only after the same-package/trusted-controller gate above.
                    return new MediaSession.ConnectionResult.AcceptedResultBuilder(s,c).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(new androidx.media3.session.SessionCommand(ADJACENT,android.os.Bundle.EMPTY)).add(new androidx.media3.session.SessionCommand(PLAY_QUEUE,android.os.Bundle.EMPTY)).add(new androidx.media3.session.SessionCommand(AUDIO_OUTPUTS,android.os.Bundle.EMPTY)).add(new androidx.media3.session.SessionCommand(SELECT_AUDIO_OUTPUT,android.os.Bundle.EMPTY)).build()).setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS).build();
                }
                @Override public ListenableFuture<androidx.media3.session.SessionResult> onCustomCommand(MediaSession s,MediaSession.ControllerInfo c,androidx.media3.session.SessionCommand command,android.os.Bundle args){
                    if((AUDIO_OUTPUTS.equals(command.customAction)||SELECT_AUDIO_OUTPUT.equals(command.customAction))&&getPackageName().equals(c.getPackageName())){
                        try{
                            if(SELECT_AUDIO_OUTPUT.equals(command.customAction))audioOutputs.select(args.getString("device",""));else audioOutputs.refresh();
                            android.os.Bundle result=new android.os.Bundle();result.putString("audioOutputs",audioOutputs.snapshot().toString());
                            return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS,result));
                        }catch(Exception e){android.os.Bundle result=new android.os.Bundle();result.putString("message",e instanceof IllegalArgumentException?e.getMessage():"Could not switch audio output. Check the device connection.");return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE,result));}
                    }
                    if(PLAY_QUEUE.equals(command.customAction)&&getPackageName().equals(c.getPackageName())){
                        try{ArrayList<String> ids=args.getStringArrayList("ids");int index=args.getInt("index",0);if(ids==null||ids.isEmpty()||ids.size()>10000||index<0||index>=ids.size())throw new IllegalArgumentException("Invalid queue");ArrayList<MediaItem> items=new ArrayList<>();
                            for(String id:ids){Track t=Library.get(PlaybackService.this).track(id);MediaItem item=NetworkSources.item(PlaybackService.this,t);if(item==null)throw new IllegalArgumentException("Song unavailable");items.add(item);}
                            // Replace an explicitly selected album on the actual player, not an
                            // optimistic controller timeline; reset the old decoder/audio sink.
                            player.stop();player.clearMediaItems();player.setMediaItems(items,index,0);player.prepare();player.play();
                            return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS));
                        }catch(Exception e){return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE));}
                    }
                    if(ADJACENT.equals(command.customAction)&&getPackageName().equals(c.getPackageName())){advanceAlbum(args.getInt("direction",1)<0?-1:1,false);return Futures.immediateFuture(new androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS));}
                    return MediaSession.Callback.super.onCustomCommand(s,c,command,args);
                }
                @Override public ListenableFuture<List<MediaItem>> onAddMediaItems(MediaSession s,
                    MediaSession.ControllerInfo c,List<MediaItem> requested) {
                    ArrayList<MediaItem> safe=new ArrayList<>();
                    if(requested.size()>10000)return Futures.immediateFailedFuture(new IllegalArgumentException("Queue too large"));
                    for(MediaItem item:requested){Track t=Library.get(PlaybackService.this).track(item.mediaId);
                        MediaItem authorised=t==null?RadioDirectory.item(PlaybackService.this,item.mediaId):NetworkSources.item(PlaybackService.this,t);
                        if(authorised==null)return Futures.immediateFailedFuture(new IllegalArgumentException("Song or station unavailable"));
                        safe.add(authorised);}
                    return Futures.immediateFuture(safe);
                }
            }).build();
        audioOutputs=new AudioOutputs(this,player,()->{if(audioOutputs!=null)try{android.os.Bundle extras=new android.os.Bundle(session.getSessionExtras());extras.putString("audioOutputs",audioOutputs.snapshot().toString());session.setSessionExtras(extras);}catch(Exception ignored){}});
        restore();
        player.addListener(new Player.Listener(){@Override public void onEvents(Player p,Player.Events events){save();}
            @Override public void onPlaybackStateChanged(int playbackState){if(playbackState==Player.STATE_ENDED&&player.getPlayWhenReady())advanceAlbum(1,true);}
        });
        handler.postDelayed(checkpoint,5000);
    }
    /** Load only the adjacent album, not thousands of remote album track lists. */
    private void advanceAlbum(int direction,boolean automatic){
        if(closing||advancing||player==null||!AlbumPlaybackPolicy.canAdvance(automatic,player.getShuffleModeEnabled(),player.getRepeatMode()))return;
        MediaItem current=player.getCurrentMediaItem();Track track=current==null?null:Library.get(this).track(current.mediaId);if(track==null)return;
        final String id=current.mediaId;final long context=prefs.getLong("contextRevision",0);final String order=prefs.getString("albumOrder","[]");
        advancing=true;
        queueWorker.execute(()->{ArrayList<MediaItem> nextItems=new ArrayList<>();
            try{JSONArray albums=new JSONArray(order);int at=-1;for(int i=0;i<albums.length();i++)if(albums.getString(i).equals(track.albumId)){at=i;break;}
                if(at>=0)for(int i=at+direction;i>=0&&i<albums.length();i+=direction){String album=albums.getString(i);JSONArray songs=track.source.startsWith("navidrome:")?NetworkSources.tracks(this,"album",album):Library.get(this).tracks("album",album);
                    if(songs.length()>10000)throw new IllegalStateException("Album queue too large");for(int j=0;j<songs.length();j++){Track song=Library.get(this).track(songs.getJSONObject(j).getString("id"));MediaItem item=NetworkSources.item(this,song);if(item!=null)nextItems.add(item);}if(!nextItems.isEmpty())break;}
            }catch(Exception ignored){nextItems.clear();}
            handler.post(()->{advancing=false;if(closing||player==null||context!=prefs.getLong("contextRevision",0)||player.getCurrentMediaItem()==null||!id.equals(player.getCurrentMediaItem().mediaId)||automatic&&!player.getPlayWhenReady())return;
                if(nextItems.isEmpty()){if(direction<0)player.seekTo(0);return;}player.setMediaItems(nextItems,AlbumPlaybackPolicy.trackIndex(direction,nextItems.size()),0);player.prepare();player.play();
            });
        });
    }

    private void restore(){
        try {JSONArray saved=new JSONArray(prefs.getString("queue","[]"));ArrayList<MediaItem> items=new ArrayList<>();
            for(int i=0;i<saved.length()&&i<10000;i++){String id=saved.getString(i);Track t=Library.get(this).track(id);MediaItem item=t==null?RadioDirectory.item(this,id):NetworkSources.item(this,t);if(item!=null)items.add(item);}
            if(!items.isEmpty()){int index=Math.min(Math.max(0,prefs.getInt("index",0)),items.size()-1);player.setMediaItems(items,index,items.get(index).mediaId.startsWith("radio:")?0:Math.max(0,prefs.getLong("position",0)));}
            player.setShuffleModeEnabled(prefs.getBoolean("shuffle",false));player.setRepeatMode(prefs.getInt("repeat",Player.REPEAT_MODE_OFF));
            // Restoring a session never starts audio unexpectedly.
        }catch(Exception ignored){}
    }
    private void save(){if(player==null)return;JSONArray ids=new JSONArray();for(int i=0;i<player.getMediaItemCount();i++)ids.put(player.getMediaItemAt(i).mediaId);
        prefs.edit().putString("queue",ids.toString()).putInt("index",player.getCurrentMediaItemIndex())
            .putLong("position",Math.max(0,player.getCurrentPosition())).putBoolean("shuffle",player.getShuffleModeEnabled())
            .putInt("repeat",player.getRepeatMode()).apply();}
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller){return session;}
    @Override public void onTaskRemoved(Intent rootIntent){closing=true;if(player!=null){player.pause();save();}handler.removeCallbacksAndMessages(null);queueWorker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){closing=true;handler.removeCallbacksAndMessages(null);queueWorker.shutdownNow();save();if(audioOutputs!=null)audioOutputs.close();if(session!=null)session.release();if(player!=null){player.release();player=null;}super.onDestroy();}
}
