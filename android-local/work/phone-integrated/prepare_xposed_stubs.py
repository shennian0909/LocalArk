"""Compile-time API signatures only; never packaged into the module DEX."""
from pathlib import Path
root=Path(__file__).resolve().parent/'compile-stubs'
sources={
'de/robv/android/xposed/IXposedHookLoadPackage.java':'''package de.robv.android.xposed; public interface IXposedHookLoadPackage {void handleLoadPackage(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam p) throws Throwable;}''',
'de/robv/android/xposed/IXposedHookZygoteInit.java':'''package de.robv.android.xposed; public interface IXposedHookZygoteInit {void initZygote(StartupParam p) throws Throwable; public static final class StartupParam {public String modulePath;public boolean startsSystemServer;}}''',
'de/robv/android/xposed/callbacks/XC_LoadPackage.java':'''package de.robv.android.xposed.callbacks; public final class XC_LoadPackage {public static final class LoadPackageParam {public String packageName,processName;public ClassLoader classLoader;public android.content.pm.ApplicationInfo appInfo;public boolean isFirstApplication;}}''',
'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed; public abstract class XC_MethodHook {public XC_MethodHook(){} protected void beforeHookedMethod(MethodHookParam p)throws Throwable{} protected void afterHookedMethod(MethodHookParam p)throws Throwable{} public static final class MethodHookParam {public java.lang.reflect.Member method;public Object thisObject;public Object[] args;public void setResult(Object r){}public Object getResult(){return null;}} public class Unhook {}}''',
'de/robv/android/xposed/XposedBridge.java':'''package de.robv.android.xposed; public final class XposedBridge {public static java.util.Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> c,String n,XC_MethodHook h){return null;} public static void log(String message){}}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed; public final class XposedHelpers {public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c,String n,Object... args){return null;}public static XC_MethodHook.Unhook findAndHookMethod(String c,ClassLoader l,String n,Object... args){return null;}}'''
}
for name,source in sources.items():
    path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(source,encoding='utf-8')
print('Xposed compile-only signatures prepared; exclude these classes from DEX.')
