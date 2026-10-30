package com.codex.arklocal;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import com.codex.arklocal.ui.LocalOverlay;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rootless module entry, scoped exclusively to this task's separate game copy. */
public final class ModuleEntry implements IXposedHookLoadPackage,IXposedHookZygoteInit {
    private static String modulePath;
    private static final AtomicBoolean registered=new AtomicBoolean();
    @Override public void initZygote(StartupParam param){modulePath=param.modulePath;}
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param)throws Throwable {
        if(!"com.hypergryph.arknights.localtest".equals(param.packageName)||!param.packageName.equals(param.processName))return;
        XposedBridge.log("ArkLocal: integrated module loaded");
        XposedBridge.hookAllMethods(Application.class,"attach",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){initialize((Application)p.thisObject);}
        });
        XposedBridge.hookAllMethods(Instrumentation.class,"callApplicationOnCreate",new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){if(p.args.length>0&&p.args[0] instanceof Application)initialize((Application)p.args[0]);}
        });
        XposedBridge.hookAllMethods(Activity.class,"onResume",new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){Activity a=(Activity)p.thisObject;if(isGame(a)){initialize(a.getApplication());LocalOverlay.attach(a,LocalBackend.get());}}
        });
        XC_MethodHook result=new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                if(p.args.length==3&&LocalBackend.get().handleExportResult((Activity)p.thisObject,(Integer)p.args[0],(Integer)p.args[1],(Intent)p.args[2]))p.setResult(null);
            }
        };
        XposedHelpers.findAndHookMethod(Activity.class,"onActivityResult",int.class,int.class,Intent.class,result);
        try{XposedHelpers.findAndHookMethod("com.u8.sdk.U8UnityContext",param.classLoader,"onActivityResult",int.class,int.class,Intent.class,result);}catch(Throwable ignored){}
    }
    private static boolean isGame(Activity activity){return activity.getClass().getName().equals("com.u8.sdk.U8UnityContext");}
    private static void initialize(Application app) {
        if(app==null||!"com.hypergryph.arknights.localtest".equals(app.getPackageName()))return;
        LocalBackend.get().start(app,modulePath);
        if(!registered.compareAndSet(false,true))return;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,Bundle b){}
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){if(isGame(a))LocalOverlay.attach(a,LocalBackend.get());}
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a,Bundle b){}
            public void onActivityDestroyed(Activity a){LocalOverlay.detach(a);}
        });
    }
}
