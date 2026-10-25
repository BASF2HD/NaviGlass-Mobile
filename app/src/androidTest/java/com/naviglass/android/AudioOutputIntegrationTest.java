package com.naviglass.android;

import android.content.Context;
import android.content.Intent;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.*;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Uses actual emulator media outputs; no physical USB DAC is simulated as tested hardware. */
@RunWith(AndroidJUnit4.class)
public class AudioOutputIntegrationTest {
    private final Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private void main(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
    @Test public void settingsRoutesNativeAudioAndRetainsQueueAcrossRecreation()throws Exception{
        c.getSharedPreferences("audio-output",0).edit().clear().commit();NetworkSources.local(c);
        File audio=File.createTempFile("audio-output-fixture-",".mp3",c.getCacheDir());MediaController controller=null;
        try(InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("synthetic.mp3");FileOutputStream out=new FileOutputStream(audio)){in.transferTo(out);}
        Track track=new Track();track.id="output-fixture";track.source="output-fixture";track.uri=Uri.fromFile(audio).toString();track.albumId="output-fixture";track.title="Output fixture";track.artist="Synthetic Ensemble";track.album="Output fixture";track.year="2026";track.mime="audio/mpeg";
        Library.get(c).getWritableDatabase().insertWithOnConflict("tracks",null,track.values(0),android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
        try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)){
            final com.google.common.util.concurrent.ListenableFuture<MediaController>[] future=new com.google.common.util.concurrent.ListenableFuture[1];main(()->future[0]=new MediaController.Builder(c,new SessionToken(c,new android.content.ComponentName(c,PlaybackService.class))).buildAsync());controller=future[0].get(15,TimeUnit.SECONDS);final MediaController player=controller;
            main(()->{player.setMediaItems(Collections.singletonList(track.mediaItem()));player.setRepeatMode(Player.REPEAT_MODE_ONE);player.prepare();player.play();});
            for(int i=0;i<100;i++){boolean[] ready={false};main(()->ready[0]=player.isPlaying());if(ready[0])break;Thread.sleep(100);}
            JSONObject outputs=command(player,PlaybackService.AUDIO_OUTPUTS,"");assertTrue("The emulator must expose a real media output",outputs.getJSONArray("devices").length()>0);
            String selected=outputs.getJSONArray("devices").getJSONObject(0).getString("id");
            await(activity,"typeof naviglassDebug !== 'undefined'");js(activity,"document.querySelector('[data-view=settings]').click();true");await(activity,"document.querySelector('#audio-output-select')?.options.length>1 && !document.querySelector('#audio-output-select').disabled");
            final long[] before={0};main(()->before[0]=player.getCurrentPosition());
            js(activity,"document.querySelector('#audio-output-select').value="+JSONObject.quote(selected)+";document.querySelector('#audio-output-select').dispatchEvent(new Event('change'));true");
            await(activity,"document.querySelector('#audio-output-select').value==="+JSONObject.quote(selected)+" && !document.querySelector('#audio-output-select').disabled");
            assertEquals(selected,command(player,PlaybackService.AUDIO_OUTPUTS,"").getString("selected"));
            AudioDeviceInfo expected=null;for(AudioDeviceInfo d:((AudioManager)c.getSystemService(Context.AUDIO_SERVICE)).getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(selected.equals(String.valueOf(d.getId())))expected=d;
            assertNotNull(expected);assertEquals(expected.getType(),c.getSharedPreferences("audio-output",0).getInt("type",-1));
            Thread.sleep(200);main(()->{assertEquals(1,player.getMediaItemCount());assertEquals(track.id,player.getCurrentMediaItem().mediaId);assertTrue(player.isPlaying());assertTrue(player.getCurrentPosition()>before[0]);assertNull(player.getPlayerError());});
            Bundle invalid=new Bundle();invalid.putString("device","999999999");assertEquals(SessionResult.RESULT_SUCCESS,send(player,PlaybackService.AUDIO_OUTPUTS,Bundle.EMPTY).resultCode);assertNotEquals(SessionResult.RESULT_SUCCESS,send(player,PlaybackService.SELECT_AUDIO_OUTPUT,invalid).resultCode);assertEquals(selected,command(player,PlaybackService.AUDIO_OUTPUTS,"").getString("selected"));
            Thread.sleep(350);android.graphics.Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(c.getCacheDir(),"audio-output-settings.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
            activity.recreate();await(activity,"typeof naviglassDebug !== 'undefined'");js(activity,"document.querySelector('[data-view=settings]').click();true");await(activity,"document.querySelector('#audio-output-select')?.value==="+JSONObject.quote(selected)+" && !document.querySelector('#audio-output-select').disabled");
            main(()->{assertEquals(track.id,player.getCurrentMediaItem().mediaId);player.pause();});
            // Stop/recreate the service to verify stored device identity, not an Activity-only choice.
            Object previousSession=session();activity.close();main(player::release);controller=null;c.stopService(new Intent(c,PlaybackService.class));
            for(int i=0;i<50&&session()==previousSession;i++)Thread.sleep(100);
            assertNotSame("The preceding playback service must actually stop",previousSession,session());
            main(()->future[0]=new MediaController.Builder(c,new SessionToken(c,new android.content.ComponentName(c,PlaybackService.class))).buildAsync());controller=future[0].get(15,TimeUnit.SECONDS);
            assertEquals(selected,command(controller,PlaybackService.AUDIO_OUTPUTS,"").getString("selected"));
            assertNotSame(previousSession,session());
            assertEquals("",command(controller,PlaybackService.SELECT_AUDIO_OUTPUT,"").getString("selected"));assertFalse(c.getSharedPreferences("audio-output",0).contains("type"));
        }finally{if(controller!=null){final MediaController p=controller;main(()->{p.pause();p.clearMediaItems();p.release();});}c.stopService(new Intent(c,PlaybackService.class));c.getSharedPreferences("audio-output",0).edit().clear().commit();c.getSharedPreferences("playback",0).edit().clear().commit();Library.get(c).getWritableDatabase().delete("tracks","source=?",new String[]{"output-fixture"});audio.delete();}
    }
    private Object session()throws Exception{java.lang.reflect.Field field=androidx.media3.session.MediaSession.class.getDeclaredField("SESSION_ID_TO_SESSION_MAP");field.setAccessible(true);final Object[] result={null};main(()->{try{for(Object value:((java.util.Map<?,?>)field.get(null)).values())result[0]=value;}catch(Exception e){throw new AssertionError(e);}});return result[0];}
    private SessionResult send(MediaController p,String action,Bundle args)throws Exception{final com.google.common.util.concurrent.ListenableFuture<SessionResult>[] result=new com.google.common.util.concurrent.ListenableFuture[1];main(()->result[0]=p.sendCustomCommand(new androidx.media3.session.SessionCommand(action,Bundle.EMPTY),args));return result[0].get(10,TimeUnit.SECONDS);}
    private JSONObject command(MediaController p,String action,String id)throws Exception{Bundle args=new Bundle();args.putString("device",id);SessionResult result=send(p,action,args);assertEquals(SessionResult.RESULT_SUCCESS,result.resultCode);return new JSONObject(result.extras.getString("audioOutputs"));}
    private void await(ActivityScenario<MainActivity> a,String condition)throws Exception{for(int i=0;i<150;i++){if("true".equals(js(a,condition)))return;Thread.sleep(100);}fail("Output settings UI did not satisfy: "+condition);}
    private String js(ActivityScenario<MainActivity> a,String script)throws Exception{CountDownLatch done=new CountDownLatch(1);String[] result={""};a.onActivity(activity->{android.view.ViewGroup root=activity.findViewById(android.R.id.content);android.webkit.WebView web=(android.webkit.WebView)((android.view.ViewGroup)root.getChildAt(0)).getChildAt(0);web.evaluateJavascript(script,r->{result[0]=r;done.countDown();});});assertTrue(done.await(10,TimeUnit.SECONDS));return result[0];}
}
