package com.naviglass.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import androidx.media3.common.MediaItem;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
import org.json.JSONArray;

/** Native-only network access. Passwords and authenticated URLs are never returned to the WebView. */
final class NetworkSources {
    private static volatile JSONObject account;
    private static volatile JSONArray remoteAlbums=new JSONArray();
    private static final class StarredIndex {
        final String snapshot; final java.util.Set<String> ids;
        StarredIndex(String snapshot,java.util.Set<String> ids){this.snapshot=snapshot;this.ids=ids;}
    }
    private static volatile StarredIndex starredIndex=new StarredIndex("",java.util.Collections.emptySet());
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("server",Context.MODE_PRIVATE);}
    private static SecretKey key()throws Exception{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("naviglass-server")){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder("naviglass-server",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
        return (SecretKey)store.getKey("naviglass-server",null);}
    private static String encrypt(String text)throws Exception{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(text.getBytes("UTF-8")),Base64.NO_WRAP);}
    private static String decrypt(String text)throws Exception{String[] parts=text.split(":");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),"UTF-8");}
    static synchronized JSONObject account(Context c)throws Exception{if(account==null){String saved=prefs(c).getString("account","");if(saved.isEmpty())throw new IllegalStateException("Connect to Navidrome in Settings first");account=new JSONObject(decrypt(saved));}return account;}
    static boolean active(Context c){return prefs(c).getBoolean("active",false)&&(account!=null||prefs(c).contains("account"));}
    static JSONObject status(Context c)throws Exception{JSONObject j=new JSONObject().put("active",active(c)).put("connected",false);try{JSONObject a=account(c);j.put("connected",true).put("url",a.getString("url")).put("username",a.getString("username"));}catch(Exception ignored){}return j;}
    static void local(Context c){prefs(c).edit().putBoolean("active",false).apply();}
    static synchronized void disconnect(Context c){
        String login=prefs(c).getString("login",prefs(c).getString("account",""));
        boolean remember=prefs(c).getBoolean("remember",true);
        account=null;remoteAlbums=new JSONArray();
        SharedPreferences.Editor cleared=prefs(c).edit().clear().putBoolean("remember",remember);
        if(remember&&!login.isEmpty())cleared.putString("login",login);
        cleared.commit();
    }
    private static synchronized JSONObject rememberedLogin(Context c)throws Exception{
        if(!prefs(c).getBoolean("remember",true))return null;
        String saved=prefs(c).getString("login",prefs(c).getString("account",""));
        return saved.isEmpty()?null:new JSONObject(decrypt(saved));
    }
    static JSONObject loginFields(Context c)throws Exception{
        JSONObject saved=rememberedLogin(c),fields=new JSONObject()
            .put("remember",prefs(c).getBoolean("remember",true)).put("passwordSaved",saved!=null);
        if(saved!=null)fields.put("url",saved.getString("url")).put("username",saved.getString("username"));
        return fields;
    }
    static synchronized void forgetLogin(Context c)throws Exception{
        // Keep the running session in memory, but prevent restoration after process death.
        if(account==null&&prefs(c).contains("account"))account(c);
        prefs(c).edit().putBoolean("remember",false).remove("login").remove("account").commit();
    }
    static void connect(Context c,JSONObject r)throws Exception{
        String base=r.getString("url").trim().replaceAll("/+$","");Uri u=Uri.parse(base);boolean plain="http".equals(u.getScheme());
        boolean approvedPlain=plain&&!BuildConfig.NAVIDROME_HTTP_HOST.isEmpty()&&BuildConfig.NAVIDROME_HTTP_HOST.equals(u.getHost())&&BuildConfig.NAVIDROME_HTTP_PORT==u.getPort();
        if((!"https".equals(u.getScheme())&&!approvedPlain)||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||base.length()>2048)throw new IllegalArgumentException("Enter an HTTPS Navidrome URL, or the exact Tailscale host and port explicitly approved for this test build");
        String user=r.getString("username").trim(),password=r.optString("password");
        JSONObject saved=rememberedLogin(c);
        if(password.isEmpty()&&saved!=null&&base.equals(saved.getString("url"))&&user.equals(saved.getString("username")))password=saved.getString("password");
        if(user.isEmpty()||user.length()>200||password.isEmpty()||password.length()>4096)throw new IllegalArgumentException("Enter your Navidrome username and password");
        JSONObject proposed=new JSONObject().put("url",base).put("username",user).put("password",password);
        boolean remember=r.optBoolean("remember",true);
        if(remember){
            String encryptedLogin=encrypt(proposed.toString());
            synchronized(NetworkSources.class){prefs(c).edit().putBoolean("remember",true).putString("login",encryptedLogin).commit();}
        }else forgetLogin(c);
        try { login(proposed); }
        catch (EndpointException notNavidrome) {
            // Original NaviGlass exposes Subsonic under this same-origin proxy.
            // Never send credentials to a different host, or retry an authentication refusal.
            if (!"https".equals(u.getScheme()) || base.endsWith("/navidrome")) throw notNavidrome;
            proposed.put("url",base+"/navidrome");login(proposed);
        }
        if(account!=null||prefs(c).contains("account"))migrateLegacy(c);
        JSONArray initial=albumPage(proposed,0);String encrypted=remember?encrypt(proposed.toString()):"";
        synchronized(NetworkSources.class){
            String login=prefs(c).getString("login","");account=proposed;remoteAlbums=initial;
            SharedPreferences.Editor connected=prefs(c).edit().clear().putBoolean("remember",remember)
                .putString("albums",initial.toString()).putBoolean("active",true).putLong("generation",System.currentTimeMillis());
            if(remember)connected.putString("account",encrypted).putString("login",login);
            connected.commit();
        }
        refresh(c);
    }
    private static void login(JSONObject proposed)throws Exception {
        try {api(proposed,"ping",new JSONObject());}
        catch(AuthenticationException refused) {
            // Original NaviGlass retries encoded-password auth when token auth is refused.
            // Only do so under HTTPS; the approved HTTP exception is token-only.
            if(!"https".equals(Uri.parse(proposed.getString("url")).getScheme()))throw refused;
            proposed.put("authMode","encoded");api(proposed,"ping",new JSONObject());
        }
    }
    private static final class AuthenticationException extends IllegalStateException {
        AuthenticationException(){super("Reached Navidrome, but it rejected the username/password (code 40). Use your Navidrome login, not your Tailscale login.");}
    }
    static JSONArray albums(Context c)throws Exception{if(remoteAlbums.length()==0)remoteAlbums=new JSONArray(prefs(c).getString("albums","[]"));return new JSONArray(remoteAlbums.toString());}
    static JSONArray more(Context c)throws Exception{prefs(c).edit().putLong("generation",prefs(c).getLong("generation",0)+1).commit();refresh(c);return albums(c);}
    static boolean refresh(Context c)throws Exception{
        JSONObject a=account(c);long startingGeneration=prefs(c).getLong("generation",0);JSONArray all=new JSONArray();java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int offset=0;offset<100000;offset+=500){JSONArray page=albumPage(a,offset);int added=0;for(int i=0;i<page.length();i++){JSONObject album=page.getJSONObject(i);if(seen.add(album.getString("id"))){all.put(album);added++;}}if(page.length()<500)break;if(added==0)throw new IllegalStateException("Navidrome repeated an album page; refresh could not complete");if(offset==99500)throw new IllegalStateException("Library exceeds 100000 albums");}
        JSONObject starred=api(a,"getStarred2",new JSONObject()).getJSONObject("starred2");
        JSONArray favouriteAlbums=starred.optJSONArray("album"),favouriteSongs=starred.optJSONArray("song");
        java.util.HashSet<String> ids=new java.util.HashSet<>();if(favouriteAlbums!=null)for(int i=0;i<favouriteAlbums.length();i++)ids.add("nd:"+favouriteAlbums.getJSONObject(i).getString("id"));
        for(int i=0;i<all.length();i++)all.getJSONObject(i).put("starred",ids.contains(all.getJSONObject(i).getString("id")));
        JSONArray artists=new JSONArray();JSONObject artistRoot=api(a,"getArtists",new JSONObject()).getJSONObject("artists");JSONArray indexes=artistRoot.optJSONArray("index");if(indexes!=null)for(int i=0;i<indexes.length();i++){JSONArray entries=indexes.getJSONObject(i).optJSONArray("artist");if(entries!=null)for(int j=0;j<entries.length();j++)artists.put(entries.getJSONObject(j));}
        JSONArray radios=new JSONArray(),radioInput=api(a,"getInternetRadioStations",new JSONObject()).getJSONObject("internetRadioStations").optJSONArray("internetRadioStation");if(radioInput!=null)for(int i=0;i<radioInput.length();i++){JSONObject r=radioInput.getJSONObject(i);String address=r.optString("streamUrl");Uri uri=Uri.parse(address);if(uri.getHost()==null||uri.getUserInfo()!=null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme())))continue;radios.put(new JSONObject().put("id","radio:nd"+Library.key(a.getString("url")+":"+a.getString("username")+":"+r.getString("id"))).put("title",r.optString("name","Radio")).put("url",address).put("provider","Navidrome").put("remote",true).put("saved",true).put("homepage",r.optString("homePageUrl")));}
        JSONArray mixes=fetchPlaylists(a);JSONArray fav=favouriteSongs==null?new JSONArray():favouriteSongs;
        // Commit a complete snapshot only after every endpoint succeeds. Never replace a failed sync with empty favourites.
        if(account(c)!=a||!active(c))return false;
        boolean changed=!fingerprint(all).equals(fingerprint(cached(c,"albums"))) || !fingerprint(fav).equals(fingerprint(cached(c,"starredSongs"))) || !fingerprint(artists).equals(fingerprint(cached(c,"artists"))) || !fingerprint(mixes).equals(fingerprint(cached(c,"playlists"))) || !fingerprint(radios).equals(fingerprint(cached(c,"radios")));
        android.content.SharedPreferences.Editor snapshot=prefs(c).edit();
        if(changed)snapshot.putLong("generation",prefs(c).getLong("generation",0)+1);
        synchronized(NetworkSources.class){if(account(c)!=a||!active(c)||prefs(c).getLong("generation",0)!=startingGeneration)return false;
        snapshot.putString("albums",all.toString()).putString("starredSongs",fav.toString()).putString("artists",artists.toString()).putString("radios",radios.toString()).putString("playlists",mixes.toString()).putLong("synced",System.currentTimeMillis()).commit();remoteAlbums=all;}
        normalizeTracks(c,fav,"",a);
        return changed;
    }
    // First connection requires a snapshot; subsequent reads use it immediately.
    static void ensureSynced(Context c)throws Exception{migrateLegacy(c);if(prefs(c).getLong("synced",0)==0)refresh(c);}
    static String source(Context c)throws Exception{return active(c)?"navidrome:"+Library.key(account(c).getString("url")+":"+account(c).getString("username")):"local";}
    private static String fingerprint(JSONArray values)throws Exception{java.util.ArrayList<String> rows=new java.util.ArrayList<>();for(int i=0;i<values.length();i++)rows.add(values.getJSONObject(i).toString());java.util.Collections.sort(rows);return rows.toString();}
    static boolean refreshIfDue(Context c)throws Exception{if(!active(c)||System.currentTimeMillis()-prefs(c).getLong("synced",0)<300000)return false;return refresh(c);}
    private static JSONObject cachedApi(Context c,String endpoint,JSONObject params)throws Exception{
        JSONObject a=account(c);String key=Library.key(a.getString("url")+":"+a.getString("username"))+":"+prefs(c).getLong("generation",0)+":"+endpoint+":"+params.toString();
        JSONObject result=CollectionCache.get(c,key,()->api(a,endpoint,params));if(account(c)!=a)throw new java.util.concurrent.CancellationException("Source changed");return result;
    }
    static JSONArray artistAlbums(Context c,String value)throws Exception {
        String artistId="";JSONArray artists=cached(c,"artists");for(int i=0;i<artists.length();i++)if(value.equals(artists.getJSONObject(i).optString("name"))||value.equals(artists.getJSONObject(i).optString("id"))){artistId=artists.getJSONObject(i).getString("id");break;}
        JSONArray out=new JSONArray();if(artistId.isEmpty())return out;
        JSONArray ids=cachedApi(c,"getArtist",new JSONObject().put("id",artistId)).getJSONObject("artist").optJSONArray("album");
        java.util.HashMap<String,JSONObject> lookup=new java.util.HashMap<>();JSONArray list=albums(c);for(int i=0;i<list.length();i++)lookup.put(list.getJSONObject(i).getString("id"),list.getJSONObject(i));
        if(ids!=null)for(int i=0;i<ids.length();i++){JSONObject entry=lookup.get("nd:"+ids.getJSONObject(i).getString("id"));if(entry!=null)out.put(entry);}
        return out;
    }
    private static void migrateLegacy(Context c)throws Exception{
        JSONObject a=account(c);String legacy="navidrome:"+Library.key(a.getString("url")),scope=Library.key(a.getString("url")+":"+a.getString("username"));
        Library library=Library.get(c);java.util.ArrayList<Track> legacyTracks=new java.util.ArrayList<>();try(android.database.Cursor rows=library.getReadableDatabase().query("tracks",null,"source=?",new String[]{legacy},null,null,null)){while(rows.moveToNext())legacyTracks.add(Track.read(rows));}
        for(Track t:legacyTracks){android.content.ContentValues values=new android.content.ContentValues();values.put("source","navidrome:"+scope);values.put("uri",Uri.parse(t.uri).buildUpon().appendQueryParameter("account",scope).build().toString());library.getWritableDatabase().update("tracks",values,"id=?",new String[]{t.id});}
    }
    static JSONArray cached(Context c,String name)throws Exception{return new JSONArray(prefs(c).getString(name,"[]"));}
    static boolean favourite(Context c,Track t){
        if(t==null)return false;if(!t.source.startsWith("navidrome:"))return Library.get(c).favourite(t.id);
        try{JSONObject a=account(c);if(!t.source.equals("navidrome:"+Library.key(a.getString("url")+":"+a.getString("username"))))return false;String snapshot=prefs(c).getString("starredSongs","[]");StarredIndex index=starredIndex;if(!snapshot.equals(index.snapshot)){java.util.Set<String> ids=new java.util.HashSet<>();JSONArray songs=new JSONArray(snapshot);for(int i=0;i<songs.length();i++)ids.add(songs.getJSONObject(i).getString("id"));index=new StarredIndex(snapshot,ids);starredIndex=index;}return index.ids.contains(Uri.parse(t.uri).getLastPathSegment());}catch(Exception ignored){return false;}
    }
    static boolean albumStarred(Context c,String id)throws Exception{if(remoteAlbums.length()==0)albums(c);JSONArray list=remoteAlbums;for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))return list.getJSONObject(i).optBoolean("starred");return false;}
    static boolean toggleAlbum(Context c,String id)throws Exception{boolean star=!albumStarred(c,id);api(account(c),star?"star":"unstar",new JSONObject().put("albumId",id.substring(3)));JSONArray list=albums(c);for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))list.getJSONObject(i).put("starred",star);synchronized(NetworkSources.class){remoteAlbums=list;prefs(c).edit().putString("albums",list.toString()).putLong("generation",prefs(c).getLong("generation",0)+1).commit();}return star;}
    static boolean toggleSong(Context c,String id)throws Exception{Track t=Library.get(c).track(id);if(t==null)throw new IllegalArgumentException("Song unavailable");if(!t.source.startsWith("navidrome:"))return Library.get(c).toggleFavourite(id);if(item(c,t)==null)throw new IllegalArgumentException("Reconnect this song's Navidrome account");boolean star=!favourite(c,t);String song=Uri.parse(t.uri).getLastPathSegment();api(account(c),star?"star":"unstar",new JSONObject().put("id",song));JSONArray current=cached(c,"starredSongs"),next=new JSONArray();for(int i=0;i<current.length();i++)if(!song.equals(current.getJSONObject(i).getString("id")))next.put(current.getJSONObject(i));if(star){JSONObject added=api(account(c),"getSong",new JSONObject().put("id",song)).getJSONObject("song");next.put(added);}synchronized(NetworkSources.class){prefs(c).edit().putString("starredSongs",next.toString()).putLong("generation",prefs(c).getLong("generation",0)+1).commit();}return star;}
    private static JSONArray albumPage(JSONObject account,int offset)throws Exception{JSONArray input=api(account,"getAlbumList2",new JSONObject().put("type","alphabeticalByName").put("size",500).put("offset",offset)).optJSONObject("albumList2").optJSONArray("album");JSONArray out=new JSONArray();if(input==null)return out;
        for(int i=0;i<input.length();i++){JSONObject a=input.getJSONObject(i);String id=a.getString("id");out.put(new JSONObject().put("id","nd:"+id).put("title",a.optString("name",a.optString("title"))).put("artist",a.optString("artist")).put("year",a.optString("year")).put("count",a.optInt("songCount")).put("coverId","nd_"+Library.key(id)).put("coverArt",a.optString("coverArt")).put("remote",true));}return out;}
    static JSONArray tracks(Context c,String mode,String value)throws Exception{return tracks(c,mode,value,()->false);}
    static JSONArray tracks(Context c,String mode,String value,java.util.function.BooleanSupplier cancelled)throws Exception{
        JSONObject owner=account(c),result;JSONArray songs;
        if(mode.equals("album")&&value.startsWith("nd:")){result=cachedApi(c,"getAlbum",new JSONObject().put("id",value.substring(3)));songs=result.getJSONObject("album").optJSONArray("song");}
        else if(mode.equals("serverPlaylist")){result=cachedApi(c,"getPlaylist",new JSONObject().put("id",value));songs=result.getJSONObject("playlist").optJSONArray("entry");}
        else if(mode.equals("favourites")){ensureSynced(c);return normalizeTracks(c,cached(c,"starredSongs"),"");}
        else if(mode.equals("playlist"))return Library.get(c).tracks(mode,value);
        else if(mode.equals("artist")){JSONArray matching=new JSONArray(),artistAlbums=artistAlbums(c,value);for(int i=0;i<artistAlbums.length();i++){if(cancelled.getAsBoolean())throw new IllegalStateException("Collection changed");JSONArray entries=tracks(c,"album",artistAlbums.getJSONObject(i).getString("id"));for(int j=0;j<entries.length();j++)matching.put(entries.getJSONObject(j));}return matching;}
        else {int offset=0;try{if(mode.equals("all"))offset=Integer.parseInt(value);}catch(Exception ignored){}result=cachedApi(c,"search3",new JSONObject().put("query",mode.equals("all")?"":value).put("songCount",200).put("songOffset",Math.max(0,offset)).put("albumCount",0).put("artistCount",0));songs=result.getJSONObject("searchResult3").optJSONArray("song");}
        return normalizeTracks(c,songs,mode.equals("search")?value:"",owner);
    }
    private static JSONArray normalizeTracks(Context c,JSONArray songs,String prefix)throws Exception{return normalizeTracks(c,songs,prefix,account(c));}
    private static JSONArray normalizeTracks(Context c,JSONArray songs,String prefix,JSONObject owner)throws Exception{
        JSONArray out=new JSONArray();if(songs==null)return out;Library library=Library.get(c);
        android.database.sqlite.SQLiteDatabase db=library.getWritableDatabase();db.beginTransaction();try {
        for(int i=0;i<songs.length();i++){JSONObject s=songs.getJSONObject(i);if(!prefix.isEmpty() && !s.optString("title").trim().toLowerCase(java.util.Locale.ROOT).startsWith(prefix.trim().toLowerCase(java.util.Locale.ROOT)))continue;Track t=new Track();String song=s.getString("id");t.id="nd_"+Library.key(owner.getString("url")+":"+owner.getString("username")+":"+song);t.source="navidrome:"+Library.key(owner.getString("url")+":"+owner.getString("username"));Track legacy=library.track("nd_"+Library.key(owner.getString("url")+":"+song));if(legacy!=null&&legacy.source.equals(t.source))t.id=legacy.id;t.uri=new Uri.Builder().scheme("navidrome").authority("track").appendPath(song).appendQueryParameter("account",Library.key(owner.getString("url")+":"+owner.getString("username"))).appendQueryParameter("cover",s.optString("coverArt")).build().toString();t.albumId="nd:"+s.optString("albumId");t.title=s.optString("title","Untitled");t.artist=s.optString("artist");t.album=s.optString("album");t.year=s.optString("year");t.number=s.optInt("track");t.disc=s.optInt("discNumber");t.duration=s.optLong("duration")*1000;t.mime=s.optString("contentType");library.getWritableDatabase().insertWithOnConflict("tracks",null,t.values(0),android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);out.put(t.json(favourite(c,t)));}db.setTransactionSuccessful();return out;}finally{db.endTransaction();}
    }
    static JSONArray playlists(Context c)throws Exception{ensureSynced(c);return cached(c,"playlists");}
    private static JSONArray fetchPlaylists(JSONObject a)throws Exception{JSONArray input=api(a,"getPlaylists",new JSONObject()).getJSONObject("playlists").optJSONArray("playlist"),out=new JSONArray();if(input!=null)for(int i=0;i<input.length();i++){JSONObject p=input.getJSONObject(i);out.put(new JSONObject().put("id",p.getString("id")).put("name",p.optString("name")).put("count",p.optInt("songCount")).put("changed",p.optString("changed")).put("remote",true));}return out;}
    static MediaItem item(Context c,Track t){if(t==null)return null;if(!t.source.startsWith("navidrome:"))return t.mediaItem();try{JSONObject a=account(c);String scope=Library.key(a.getString("url")+":"+a.getString("username"));boolean legacy=t.source.equals("navidrome:"+Library.key(a.getString("url")));if(!legacy&&!t.source.equals("navidrome:"+scope))return null;Uri address=Uri.parse(t.uri);if(legacy)address=address.buildUpon().appendQueryParameter("account",scope).build();return t.mediaItem().buildUpon().setUri(address).build();}catch(Exception e){return null;}}
    static File cover(Context c,String coverId)throws Exception{JSONObject a=account(c);String id="";
        if(coverId.startsWith("nd_")&&Library.get(c).track(coverId)==null){JSONArray list=albums(c);for(int i=0;i<list.length();i++){JSONObject album=list.getJSONObject(i);if(coverId.equals(album.getString("coverId"))){id=album.optString("coverArt");if(id.isEmpty())id=api(a,"getAlbum",new JSONObject().put("id",album.getString("id").substring(3))).getJSONObject("album").optString("coverArt");break;}}}
        else {Track t=Library.get(c).track(coverId);if(t!=null&&t.source.equals("navidrome:"+Library.key(a.getString("url")+":"+a.getString("username"))))id=Uri.parse(t.uri).getQueryParameter("cover");}
        if(id==null||id.isEmpty())return null;File dir=new File(c.getCacheDir(),"server-covers");dir.mkdirs();File f=new File(dir,Library.key(a.getString("url")+":"+id)+".jpg");if(f.isFile()){f.setLastModified(System.currentTimeMillis());return f;}
        byte[] bytes=get(url(a,"getCoverArt",new JSONObject().put("id",id).put("size",512)),4*1024*1024);android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<=0||options.outHeight<=0||options.outWidth>2048||options.outHeight>2048)return null;android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)return null;
        if(bitmap.getWidth()>2048||bitmap.getHeight()>2048){bitmap.recycle();return null;}try(FileOutputStream stream=new FileOutputStream(f)){bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,stream);}finally{bitmap.recycle();}
        File[] files=dir.listFiles();if(files!=null&&files.length>300){java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));for(int i=0;i<files.length-300;i++)files[i].delete();}return f;
    }
    static String url(JSONObject a,String endpoint,JSONObject params)throws Exception{byte[] bytes=new byte[16];new SecureRandom().nextBytes(bytes);String salt=hex(bytes),token=hex(MessageDigest.getInstance("MD5").digest((a.getString("password")+salt).getBytes("UTF-8")));
        Uri.Builder b=Uri.parse(a.getString("url")+"/rest/"+endpoint+".view").buildUpon().appendQueryParameter("u",a.getString("username")).appendQueryParameter("t",token).appendQueryParameter("s",salt).appendQueryParameter("v","1.16.1").appendQueryParameter("c","NaviGlassAndroid").appendQueryParameter("f","json");if("encoded".equals(a.optString("authMode"))) {b.clearQuery().appendQueryParameter("u",a.getString("username")).appendQueryParameter("p","enc:"+hex(a.getString("password").getBytes("UTF-8"))).appendQueryParameter("v","1.16.1").appendQueryParameter("c","NaviGlassAndroid").appendQueryParameter("f","json");}
        java.util.Iterator<String> keys=params.keys();while(keys.hasNext()){String key=keys.next();b.appendQueryParameter(key,params.getString(key));}return b.build().toString();}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();}
    private static final class EndpointException extends IllegalStateException {
        EndpointException(){super("This URL is not a Navidrome API. Check the server path (for NaviGlass, use /navidrome).");}
    }
    static JSONObject api(JSONObject account,String endpoint,JSONObject params)throws Exception {
        byte[] bytes;
        try { bytes=get(url(account,endpoint,params),4*1024*1024); }
        catch(EndpointException e){throw e;}
        catch(java.io.IOException e){throw new IllegalStateException(ConnectionErrors.message(e));}
        JSONObject root;
        try {root=new JSONObject(new String(bytes,"UTF-8")).getJSONObject("subsonic-response");}
        catch(org.json.JSONException e){throw new EndpointException();}
        if(!"ok".equals(root.optString("status"))) {
            int code=root.optJSONObject("error")==null?0:root.optJSONObject("error").optInt("code");
            if(code==40)throw new AuthenticationException();
            if(code==50)throw new IllegalStateException("Navidrome denied access to this account (code 50). Check the account's permissions.");
            throw new IllegalStateException("Navidrome refused this request (code "+code+"). Check the server and account permissions.");
        }
        return root;
    }
    static byte[] get(String address,int limit)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","NaviGlassAndroid/"+BuildConfig.VERSION_NAME);try{int status=connection.getResponseCode();if(status==404)throw new EndpointException();if(status!=200)throw new IllegalStateException("The server returned HTTP "+status+". Check the server address/path and availability.");try(InputStream stream=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1){if(out.size()+count>limit)throw new IllegalStateException("Server response too large");out.write(buffer,0,count);}return out.toByteArray();}}finally{connection.disconnect();}}
}
