package com.naviglass.android;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RememberLoginIntegrationTest {
    private final Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private JSONObject login(String url,boolean remember)throws Exception{return new JSONObject().put("url",url).put("username","fixture").put("password","fixture-only").put("remember",remember);}
    @Test public void encryptedPrefillRetryForgetAndSessionOnly()throws Exception {
        NetworkSources.disconnect(c);c.getSharedPreferences("server",0).edit().clear().commit();
        try(ServerIntegrationTest.Fixture server=new ServerIntegrationTest.Fixture(false)) {
            NetworkSources.connect(c,login(server.address(),true));
            String encrypted=c.getSharedPreferences("server",0).getString("login","");assertFalse(encrypted.isEmpty());assertFalse(encrypted.contains("fixture-only"));assertFalse(encrypted.contains("localhost"));
            JSONObject fields=NetworkSources.loginFields(c);assertEquals(server.address(),fields.getString("url"));assertEquals("fixture",fields.getString("username"));assertTrue(fields.getBoolean("passwordSaved"));assertFalse("Renderer must never receive the password",fields.has("password"));
            NetworkSources.disconnect(c);assertTrue(NetworkSources.loginFields(c).getBoolean("passwordSaved"));
            clearMemory();NetworkSources.connect(c,login(server.address(),true).put("password",""));assertTrue(NetworkSources.active(c));
            try(ActivityScenario<MainActivity> activity=ActivityScenario.launch(MainActivity.class)) {
                await(activity,"typeof naviglassDebug !== 'undefined'");js(activity,"document.querySelector('#connectWelcome').click();true");
                await(activity,"document.querySelector('#server-password').placeholder==='Saved password' && !document.querySelector('#panelBody button[type=submit]').disabled");
                assertEquals("true",js(activity,"document.querySelector('#server-url').value==="+JSONObject.quote(server.address())+" && document.querySelector('#server-username').value==='fixture' && document.querySelector('#server-password').value==='' && !document.querySelector('#server-password').required && document.querySelector('#server-remember').checked"));
                if("true".equals(InstrumentationRegistry.getArguments().getString("captureLogin"))){Thread.sleep(350);android.graphics.Bitmap image=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(c.getCacheDir(),"remember-login.png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
                js(activity,"document.querySelector('#server-password').form.requestSubmit();true");await(activity,"!document.querySelector('#panel').open");
                activity.recreate();await(activity,"typeof naviglassDebug !== 'undefined'");js(activity,"document.querySelector('#connectWelcome').click();true");await(activity,"document.querySelector('#server-password').placeholder==='Saved password'");
                js(activity,"document.querySelector('#server-remember').click();true");await(activity,"document.querySelector('#server-password').required && !document.querySelector('#panelBody button[type=submit]').disabled");
                assertFalse(c.getSharedPreferences("server",0).contains("login"));assertFalse(c.getSharedPreferences("server",0).contains("account"));assertTrue("Forgetting persistence must preserve the running session",NetworkSources.active(c));
                clearMemory();assertFalse("Unchecked credentials must not restore after process death",NetworkSources.active(c));assertFalse(NetworkSources.status(c).getBoolean("connected"));
            }
            NetworkSources.connect(c,login(server.address(),false));assertTrue(NetworkSources.active(c));assertFalse(c.getSharedPreferences("server",0).contains("account"));assertFalse(c.getSharedPreferences("server",0).contains("login"));clearMemory();assertFalse(NetworkSources.active(c));
            if(server.failure.get()!=null)throw new AssertionError(server.failure.get());
        }finally{cleanup();}
    }
    @Test public void encryptedLoginSurvivesConnectionFailureWithoutAcceptingAnotherAccount()throws Exception {
        NetworkSources.disconnect(c);c.getSharedPreferences("server",0).edit().clear().commit();
        try(ServerIntegrationTest.Fixture server=new ServerIntegrationTest.Fixture(false)) {
            server.unavailable=true;
            try{NetworkSources.connect(c,login(server.address(),true));fail("Unavailable server must fail");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("503"));}
            assertFalse(NetworkSources.active(c));assertTrue(NetworkSources.loginFields(c).getBoolean("passwordSaved"));clearMemory();assertEquals(server.address(),NetworkSources.loginFields(c).getString("url"));
            try{NetworkSources.connect(c,login(server.address(),true).put("username","another-account").put("password",""));fail("Saved password must be limited to its exact server/account");}catch(IllegalArgumentException expected){}
            server.unavailable=false;NetworkSources.connect(c,login(server.address(),true).put("password",""));assertTrue(NetworkSources.active(c));
            if(server.failure.get()!=null)throw new AssertionError(server.failure.get());
        }finally{cleanup();}
    }
    @Test public void sessionOnlyAccountSwitchPreservesLegacyOwnership()throws Exception {
        NetworkSources.disconnect(c);c.getSharedPreferences("server",0).edit().clear().commit();
        String oldScope;Track legacy=new Track();
        try {
            try(ServerIntegrationTest.Fixture original=new ServerIntegrationTest.Fixture(false)) {
                NetworkSources.connect(c,login(original.address(),true));
                String url=NetworkSources.account(c).getString("url");oldScope="navidrome:"+Library.key(url+":fixture");
                legacy.id="nd_legacy_connection_test";legacy.source="navidrome:"+Library.key(url);
                legacy.uri="navidrome://track/legacy-connection-test";legacy.albumId="nd:legacy";
                legacy.title="Synthetic legacy track";legacy.artist="Fixture";legacy.album="Fixture";legacy.year="2026";legacy.mime="audio/flac";
                Library.get(c).getWritableDatabase().insertWithOnConflict("tracks",null,legacy.values(0),android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE);
                if(original.failure.get()!=null)throw new AssertionError(original.failure.get());
            }
            try(ServerIntegrationTest.Fixture replacement=new ServerIntegrationTest.Fixture(false)) {
                NetworkSources.connect(c,login(replacement.address(),false));
                Track restored=Library.get(c).track(legacy.id);assertNotNull(restored);
                assertEquals("Session-only reconnect must retain the preceding account's legacy playlist ownership",oldScope,restored.source);
                assertNull("The replacement account must not play another account's track",NetworkSources.item(c,restored));
                if(replacement.failure.get()!=null)throw new AssertionError(replacement.failure.get());
            }
        }finally{cleanup();}
    }
    private void clearMemory()throws Exception{java.lang.reflect.Field field=NetworkSources.class.getDeclaredField("account");field.setAccessible(true);field.set(null,null);}
    private void cleanup(){NetworkSources.disconnect(c);c.getSharedPreferences("server",0).edit().clear().commit();c.getSharedPreferences("playback",0).edit().clear().commit();Library.get(c).getWritableDatabase().delete("tracks","source LIKE ?",new String[]{"navidrome:%"});}
    private void await(ActivityScenario<MainActivity> a,String condition)throws Exception{for(int i=0;i<150;i++){if("true".equals(js(a,condition)))return;Thread.sleep(100);}fail("Connection UI condition failed");}
    private String js(ActivityScenario<MainActivity> a,String script)throws Exception{CountDownLatch done=new CountDownLatch(1);String[] result={""};a.onActivity(activity->{android.view.ViewGroup root=activity.findViewById(android.R.id.content);android.webkit.WebView web=(android.webkit.WebView)((android.view.ViewGroup)root.getChildAt(0)).getChildAt(0);web.evaluateJavascript(script,r->{result[0]=r;done.countDown();});});assertTrue(done.await(10,TimeUnit.SECONDS));return result[0];}
}
