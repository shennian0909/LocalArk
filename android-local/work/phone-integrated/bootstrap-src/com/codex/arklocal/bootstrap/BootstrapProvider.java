package com.codex.arklocal.bootstrap;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;

/** Loads only the checksum-pinned module packaged inside this application. */
public final class BootstrapProvider extends ContentProvider {
    private static Class<?> runtime;
    private static final String EXPECTED_SHA="__MODULE_SHA256__";
    @Override public boolean onCreate(){
        try {load(getContext());return true;}
        catch(Throwable e){android.util.Log.e("ArkLocalBootstrap","module_load_failed "+e.getClass().getSimpleName());return false;}
    }
    static synchronized Class<?> load(Context context)throws Exception {
        if(runtime!=null)return runtime;
        File directory=new File(context.getCodeCacheDir(),"arklocal-module");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot prepare module directory");
        File module=new File(directory,"module-"+EXPECTED_SHA+".apk");
        if(!module.isFile()) {
            File temporary=File.createTempFile("module-",".new",directory);
            try {
                try(InputStream in=context.getAssets().open("arklocal/module.apk");FileOutputStream out=new FileOutputStream(temporary)) {
                    // Android 14 requires dynamically loaded code to be read-only.
                    if(!temporary.setReadOnly())throw new IOException("Cannot protect module file");
                    byte[] block=new byte[65536];int n;while((n=in.read(block))>=0)out.write(block,0,n);out.getFD().sync();
                }
                if(!sha(temporary).equals(EXPECTED_SHA))throw new SecurityException("Embedded module checksum mismatch");
                if(!temporary.renameTo(module))throw new IOException("Cannot publish module file");
            } finally {if(temporary.exists())temporary.delete();}
        }
        if(!sha(module).equals(EXPECTED_SHA))throw new SecurityException("Installed module checksum mismatch");
        if(!module.setReadOnly())throw new IOException("Module file permissions invalid");
        ClassLoader parent=Context.class.getClassLoader();
        DexClassLoader loader=new DexClassLoader(module.getAbsolutePath(),directory.getAbsolutePath(),null,parent);
        Class<?> entry=loader.loadClass("com.codex.arklocal.ModuleRuntime");
        entry.getMethod("attach",Context.class,String.class).invoke(null,context,module.getAbsolutePath());
        runtime=entry;return entry;
    }
    private static String sha(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)digest.update(b,0,n);}
        StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
