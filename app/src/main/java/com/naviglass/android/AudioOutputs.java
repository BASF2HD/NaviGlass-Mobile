package com.naviglass.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.exoplayer.ExoPlayer;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Media routing owned by the playback service, including while the Activity is closed. */
@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class AudioOutputs implements AutoCloseable {
    private final AudioManager manager;
    private final SharedPreferences prefs;
    private final ExoPlayer player;
    private final Runnable changed;
    private final AudioDeviceCallback callback;
    private OutputDeviceSelection.Device saved;
    private int liveId=-1,appliedId=-1;

    AudioOutputs(Context context,ExoPlayer player,Runnable changed){
        this.manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        this.prefs=context.getSharedPreferences("audio-output",Context.MODE_PRIVATE);
        this.player=player;this.changed=changed;
        if(prefs.contains("type"))saved=new OutputDeviceSelection.Device(-1,prefs.getInt("type",0),prefs.getString("name",""),prefs.getString("address",""));
        callback=new AudioDeviceCallback(){
            @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added){refresh();}
            @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed){refresh();}
        };
        manager.registerAudioDeviceCallback(callback,new Handler(Looper.getMainLooper()));
        refresh();
    }
    private List<AudioDeviceInfo> devices(){
        List<AudioDeviceInfo> out=new ArrayList<>();
        for(AudioDeviceInfo device:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            int type=device.getType();
            if(device.isSink()&&type!=AudioDeviceInfo.TYPE_TELEPHONY&&type!=AudioDeviceInfo.TYPE_BUILTIN_EARPIECE&&type!=AudioDeviceInfo.TYPE_REMOTE_SUBMIX)out.add(device);
        }
        return out;
    }
    private static OutputDeviceSelection.Device describe(AudioDeviceInfo d){
        String name=d.getProductName()==null?"Audio output":d.getProductName().toString();
        String address="";try{address=d.getAddress();}catch(SecurityException ignored){}
        return new OutputDeviceSelection.Device(d.getId(),d.getType(),name,address==null?"":address);
    }
    private AudioDeviceInfo resolved(List<AudioDeviceInfo> devices){
        List<OutputDeviceSelection.Device> descriptions=new ArrayList<>();for(AudioDeviceInfo d:devices)descriptions.add(describe(d));
        OutputDeviceSelection.Device selected=OutputDeviceSelection.find(descriptions,saved,liveId);
        if(selected!=null)for(AudioDeviceInfo d:devices)if(d.getId()==selected.id)return d;
        return null;
    }
    void refresh(){
        AudioDeviceInfo selected=resolved(devices());int id=selected==null?-1:selected.getId();
        if(id!=appliedId){player.setPreferredAudioDevice(selected);appliedId=id;}
        changed.run();
    }
    void select(String id){
        AudioDeviceInfo selected=null;
        if(!id.isEmpty()){
            if(!id.matches("[0-9]{1,10}"))throw new IllegalArgumentException("Invalid audio output");
            for(AudioDeviceInfo d:devices())if(id.equals(String.valueOf(d.getId())))selected=d;
            if(selected==null)throw new IllegalArgumentException("That output is no longer connected. Refresh devices and choose again.");
        }
        // Apply before saving, so a rejected switch never becomes a remembered choice.
        player.setPreferredAudioDevice(selected);
        saved=selected==null?null:describe(selected);liveId=selected==null?-1:selected.getId();appliedId=liveId;
        SharedPreferences.Editor edit=prefs.edit().clear();
        if(saved!=null)edit.putInt("type",saved.type).putString("name",saved.name).putString("address",saved.address);
        edit.apply();changed.run();
    }
    JSONObject snapshot()throws Exception{
        List<AudioDeviceInfo> connected=devices();AudioDeviceInfo selected=resolved(connected);JSONArray outputs=new JSONArray();
        for(AudioDeviceInfo d:connected){boolean usb=d.getType()==AudioDeviceInfo.TYPE_USB_DEVICE||d.getType()==AudioDeviceInfo.TYPE_USB_HEADSET||d.getType()==AudioDeviceInfo.TYPE_USB_ACCESSORY;
            String name=describe(d).name;outputs.put(new JSONObject().put("id",String.valueOf(d.getId())).put("label",name.substring(0,Math.min(160,name.length()))+(usb?" · USB":"")).put("usb",usb));}
        return new JSONObject().put("devices",outputs).put("selected",selected==null?"":String.valueOf(selected.getId()))
            .put("preferredLabel",saved==null?"":saved.name.substring(0,Math.min(160,saved.name.length())))
            .put("unavailable",saved!=null&&selected==null);
    }
    @Override public void close(){manager.unregisterAudioDeviceCallback(callback);}
}
