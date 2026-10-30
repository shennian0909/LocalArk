package com.codex.arklocal;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.codex.arklocal.ui.LocalOverlay;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Lab-only Activity for validating the same embedded backend and overlay code. */
public final class HarnessActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);status=new TextView(this);status.setTextSize(20);status.setPadding(32,32,32,32);status.setText("正在运行单 APK 内置后端检查…\n请点击“本地”悬浮球查看操作面板。");setContentView(status);
        LocalBackend.get().start(this,getApplicationInfo().sourceDir);LocalOverlay.attach(this,LocalBackend.get());
        new Thread(()->{
            JSONObject report=new JSONObject(true);
            try {
                report=LocalBackend.get().selfTest();
                File root=new File(getNoBackupFilesDir(),"arklocal");
                JSONObject credentials=JSON.parseObject(read(new File(root,"local-account.json")));
                JSONObject body=new JSONObject();body.put("phone",credentials.getString("account"));body.put("password",credentials.getString("password"));
                JSONObject auth=request("/user/auth/v1/token_by_phone_password",body);
                if(auth.getIntValue("status")!=0)throw new IOException("local account authentication failed");
                JSONObject version=request("/config/prod/official/Android/version",null);
                JSONObject loginBody=new JSONObject();loginBody.put("token",auth.getJSONObject("data").getString("token"));loginBody.put("platform","Android");loginBody.put("assetsVersion",version.getString("resVersion"));loginBody.put("clientVersion",version.getString("clientVersion"));
                JSONObject login=request("/account/login",loginBody);
                if(login.getJSONObject("data").getIntValue("result")!=0)throw new IOException("game account login API failed");
                JSONObject sync=request("/account/syncData",new JSONObject());
                if(sync.getIntValue("result")!=0||sync.getJSONObject("user")==null)throw new IOException("save sync API failed");
                report.put("sdkAccountApi",true);report.put("gameLoginApi",true);report.put("saveSyncApi",true);
                report.put("uid",login.getJSONObject("data").getLong("uid"));report.put("operators",sync.getJSONObject("user").getJSONObject("troop").getJSONObject("chars").size());
                report.put("sdkTlsApi",requestTLS().getIntValue("report_device_info")==10000);
                report.put("gameClientLoginVerified",false);report.put("physicalPhoneVerified",false);
                report.put("passed",true);
            } catch(Throwable error) {report.put("passed",false);report.put("errorClass",error.getClass().getSimpleName());report.put("backend",LocalBackend.get().summary());report.put("backendFailure",LocalBackend.get().technicalStatus());}
            final JSONObject savedReport=report;
            try(FileOutputStream output=openFileOutput("integration-check.json",MODE_PRIVATE)){output.write(report.toJSONString().getBytes(StandardCharsets.UTF_8));}catch(Exception ignored){}
            android.util.Log.i("ArkIntegratedHarness","check_passed="+report.getBooleanValue("passed"));
            runOnUiThread(()->status.setText((savedReport.getBooleanValue("passed")?"单 APK 内置后端检查通过":"检查尚未通过")+"\n\n"+LocalBackend.get().summary()+"\n\n点击“本地”悬浮球进行操作。"));
        },"local-integration-check").start();
    }
    private static String read(File file)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)out.write(b,0,n);}return new String(out.toByteArray(),StandardCharsets.UTF_8);}
    static JSONObject request(String path,JSONObject body)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:10029"+path).openConnection();connection.setConnectTimeout(15000);connection.setReadTimeout(45000);
        try {
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");try(OutputStream output=connection.getOutputStream()){output.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));}}
            int code=connection.getResponseCode();if(code!=200)throw new IOException("local HTTP status "+code);
            ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=connection.getInputStream()){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)out.write(b,0,n);}return JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        } finally {connection.disconnect();}
    }
    static JSONObject requestTLS()throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("https://as@127.0.0.1:8030/api/remote_config/101/prod/default/Android/ak_sdk_config").openConnection();c.setConnectTimeout(15000);c.setReadTimeout(15000);
        try{if(c.getResponseCode()!=200)throw new IOException("TLS config status");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){int b;while((b=in.read())>=0)out.write(b);}return JSON.parseObject(new String(out.toByteArray(),StandardCharsets.UTF_8));}finally{c.disconnect();}
    }
    @Override public void onResume(){super.onResume();LocalOverlay.attach(this,LocalBackend.get());}
    @Override public void onDestroy(){LocalOverlay.detach(this);super.onDestroy();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){if(!LocalBackend.get().handleExportResult(this,requestCode,resultCode,data))super.onActivityResult(requestCode,resultCode,data);}
}
