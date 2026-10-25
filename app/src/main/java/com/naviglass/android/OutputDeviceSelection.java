package com.naviglass.android;

import java.util.List;

/** Stable device matching without guessing between identically named DACs. */
final class OutputDeviceSelection {
    static final class Device {
        final int id,type; final String name,address;
        Device(int id,int type,String name,String address){this.id=id;this.type=type;this.name=name;this.address=address;}
    }
    static Device find(List<Device> devices,Device saved,int liveId){
        if(saved==null)return null;
        for(Device d:devices)if(d.id==liveId&&d.type==saved.type&&d.name.equals(saved.name)&&d.address.equals(saved.address))return d;
        Device match=null;int matches=0;
        if(!saved.address.isEmpty()){
            for(Device d:devices)if(d.type==saved.type&&d.address.equals(saved.address)){match=d;matches++;}
            if(matches==1)return match;
        }
        match=null;matches=0;
        for(Device d:devices)if(d.type==saved.type&&d.name.equals(saved.name)){match=d;matches++;}
        return matches==1?match:null;
    }
}
