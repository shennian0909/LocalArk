package com.codex.arklocal;
import android.app.*;
import android.content.*;
import android.os.Bundle;
import com.codex.arklocal.ui.LocalOverlay;
import java.util.concurrent.atomic.AtomicBoolean;

/** Public entry point used by the APK's small application-private module loader. */
public final class ModuleRuntime {
    private static final AtomicBoolean registered=new AtomicBoolean();
    public static void attach(Context context,String modulePath) {
        Application app=(Application)context.getApplicationContext();
        LocalBackend.get().start(app,modulePath);
        if(!registered.compareAndSet(false,true))return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){String n=a.getClass().getName();if(n.equals("com.u8.sdk.U8UnityContext")||n.equals("com.codex.arklocal.hosttest.HostActivity")){LocalOverlay.attach(a,LocalBackend.get());LocalProtocolWindow.attach(a);}}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){LocalProtocolWindow.detach(a);LocalOverlay.detach(a);}
        });
        android.util.Log.i("ArkLocalIntegrated","embedded_module_attached");
    }
    public static void openExport(Activity activity){LocalBackend.get().openPendingExport(activity);}
    public static boolean handleExportResult(Activity activity,int requestCode,int resultCode,Intent data){return LocalBackend.get().handleExportResult(activity,requestCode,resultCode,data);}
}
