package com.codex.arklocal.bootstrap;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Owns the document picker result without changing the game's Activity methods. */
public final class ExportActivity extends Activity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state==null)try{BootstrapProvider.load(this).getMethod("openExport",Activity.class).invoke(null,this);}
        catch(Throwable error){android.widget.Toast.makeText(this,"无法打开保存窗口，请返回游戏重试。",android.widget.Toast.LENGTH_LONG).show();finish();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        try{BootstrapProvider.load(this).getMethod("handleExportResult",Activity.class,int.class,int.class,Intent.class).invoke(null,this,request,result,data);}
        catch(Throwable error){android.widget.Toast.makeText(this,"文件保存未完成，请重试。",android.widget.Toast.LENGTH_LONG).show();}
        finish();
    }
}
