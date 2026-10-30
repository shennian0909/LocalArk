package com.codex.arklocal;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.codex.arklocal.storage.LocalStore;
import com.codex.arklocal.ui.LocalOverlay;
import com.hypergryph.arknights.ArknightsApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import javax.net.ssl.*;

/** Backend, data and lifecycle owned entirely by the host Android application. */
public final class LocalBackend implements LocalOverlay.Controller {
    public static final Object DATA_LOCK=new Object();
    public static final int EXPORT_REQUEST=0x4A71;
    private static final String GACHA_PREFS="arklocal-gacha-picker";
    private static final String GACHA_SELECTED_POOL="selected-pool";
    private static final LocalBackend INSTANCE=new LocalBackend();
    public static LocalBackend get(){return INSTANCE;}
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean ready,starting;
    private volatile String stage="尚未启动",lastError="";
    private volatile JSONObject config;
    private OfficialPublicContent publicContent;
    private volatile JSONArray routeDefinitions=new JSONArray();
    private volatile CountDownLatch readyLatch=new CountDownLatch(1);
    private Context context;private String modulePath;private LocalStore store;
    private LocalSaveSync saveSync;
    private LocalHttpServer http,tls;private File root;private LoopbackConnectProxy sdkProxy;
    private byte[] pendingExport;
    private LocalOverlay.Callback pendingExportCallback;
    private String pendingExportTitle;
    private final ArrayDeque<JSONObject> recentRequests=new ArrayDeque<>();
    private LocalBackend(){}
    public synchronized void start(Context context,String modulePath) {
        this.context=context.getApplicationContext();this.modulePath=modulePath;
        if(ready||starting)return;
        starting=true;readyLatch=new CountDownLatch(1);stage="正在准备本地服务";
        worker.execute(()->{
            try {
                root=new File(this.context.getNoBackupFilesDir(),"arklocal");
                if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建本地目录");
                extractPayload(modulePath,root);
                config=JSON.parseObject(read(new File(root,"config.json")));
                publicContent=new OfficialPublicContent(root,config);
                routeDefinitions=JSON.parseArray(read(new File(root,"client-routes.json")));
                startListeners();
                JSONObject routing=config.getJSONObject("localRouting");
                if(routing!=null&&routing.getBooleanValue("sdkProxy"))sdkProxy=new LoopbackConnectProxy();
                BackendPaths.setRoot(root);
                boolean hadExistingSave=new File(root,LocalStore.DATABASE_NAME).isFile();
                long existingSaveTimestamp=hadExistingSave?new File(root,LocalStore.DATABASE_NAME).lastModified():0;
                synchronized(DATA_LOCK) {
                    try(InputStream seed=new FileInputStream(new File(root,"seed.json"))){store=LocalStore.open(this.context,seed);}
                    ArknightsApplication.jdbcTemplate=store.jdbcTemplate();
                    ArknightsApplication.initialize(root);
                    upgradeLocalSaves();
                    ArknightsApplication.DefaultSyncData.put("_localCampaignRewardFormat",2);
                    com.hypergryph.arknights.game.CurrentActivitySupport.setStageTable(ArknightsApplication.stageTable);
                    ArknightsApplication.serverConfig.getJSONObject("version").put("android",config.getJSONObject("version").getJSONObject("android"));
                    com.hypergryph.arknights.auth.LocalAccountRegistration.setTransactionRunner(operation -> store.transaction(jdbc -> operation.run(jdbc)));
                    restoreGachaSelection();
                    if(!this.context.getSharedPreferences("arklocal-grants",Context.MODE_PRIVATE).getBoolean("materials-and-skins-v1",false)) {
                        long migrateUid=seedGrantUid();
                        if(migrateUid>0)grantSave(migrateUid,true,true);
                        this.context.getSharedPreferences("arklocal-grants",Context.MODE_PRIVATE).edit().putBoolean("materials-and-skins-v1",true).commit();
                    }
                    saveSync=new LocalSaveSync(this.context,store,root,existingSaveTimestamp);
                    http.registerControllers();tls.registerControllers();
                }
                saveSync.beforeLogin();
                ready=true;lastError="";stage="本地服务已就绪";
                android.util.Log.i("ArkLocalIntegrated","backend_ready routes="+http.routeCount());
            } catch(Throwable error) {
                lastError=describe(error);stage="本地服务启动失败";
                if(http!=null)http.stop();if(tls!=null)tls.stop();
                if(sdkProxy!=null){try{sdkProxy.close();}catch(IOException ignored){}sdkProxy=null;}
                if(store!=null){try{store.close();}catch(Exception ignored){}store=null;}
                android.util.Log.e("ArkLocalIntegrated","backend_start_failed "+error.getClass().getSimpleName());
            } finally {starting=false;readyLatch.countDown();}
        });
    }
    public Context context(){return context;}
    private void upgradeLocalSaves()throws Exception {
        Map<Long,JSONObject> updates=new LinkedHashMap<>();
        for(Long uid:store.jdbcTemplate().queryForList("SELECT uid FROM account",Long.class)) {
            JSONObject user=JSON.parseObject(store.jdbcTemplate().queryForObject("SELECT user FROM account WHERE uid=?",String.class,uid));
            boolean changed=LocalSaveMigrations.upgradeCampaignRewards(user);
            changed|=LocalSaveMigrations.repairSandboxSave(user);
            if(changed)updates.put(uid,user);
        }
        if(updates.isEmpty())return;
        File directory=new File(root,"save-backups");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot preserve save before local upgrade");
        File backup=new File(directory,"before-local-upgrade-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".json");
        try(FileOutputStream output=new FileOutputStream(backup)){store.exportSnapshot(output);output.getFD().sync();}
        store.transaction(jdbc->{
            for(Map.Entry<Long,JSONObject> entry:updates.entrySet()) {
                String json=JSON.toJSONString(entry.getValue(),com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue);
                if(jdbc.update("UPDATE account SET user=? WHERE uid=?",json,entry.getKey())!=1)throw new IOException("Local upgrade account missing");
            }
            return null;
        });
    }
    /** Restores a previously saved content choice without allowing stale data to block startup. */
    private void restoreGachaSelection() {
        SharedPreferences preferences=context.getSharedPreferences(GACHA_PREFS,Context.MODE_PRIVATE);
        LocalGachaOptions.setSelectedPool(root,null);
        preferences.edit().remove(GACHA_SELECTED_POOL).apply();
    }
    /** Caller holds DATA_LOCK. Validation precedes the preference write; failures roll back. */
    private String persistGachaSelectionLocked(String id)throws IOException {
        String previous=LocalGachaOptions.getSelectedPool();
        String normalized=id==null||id.trim().isEmpty()?null:id.trim();
        LocalGachaOptions.setSelectedPool(root,normalized);
        SharedPreferences preferences=context.getSharedPreferences(GACHA_PREFS,Context.MODE_PRIVATE);
        boolean saved;
        try {
            SharedPreferences.Editor editor=preferences.edit();
            if(normalized==null)editor.remove(GACHA_SELECTED_POOL);
            else editor.putString(GACHA_SELECTED_POOL,normalized);
            saved=editor.commit();
        } catch(RuntimeException error) {
            saved=false;
        }
        if(!saved) {
            try{LocalGachaOptions.setSelectedPool(root,previous);}catch(RuntimeException ignored){LocalGachaOptions.setSelectedPool(root,null);}
            try {
                SharedPreferences.Editor rollback=preferences.edit();
                if(previous==null)rollback.remove(GACHA_SELECTED_POOL);
                else rollback.putString(GACHA_SELECTED_POOL,previous);
                rollback.commit();
            } catch(RuntimeException ignored) { }
            throw new IOException("Gacha selection preference was not saved");
        }
        return LocalGachaOptions.getSelectedPool();
    }
    JSONObject config(){return config;}
    JSONObject androidVersion() {
        JSONObject version=publicContent.version();
        synchronized(DATA_LOCK) {
            config.getJSONObject("version").put("android",version);
            JSONObject active=ArknightsApplication.serverConfig.getJSONObject("version");
            if(active!=null)active.put("android",version);
        }
        return version;
    }
    JSONObject announcement(){return publicContent.announcement();}
    JSONObject preannouncement(){return publicContent.preannouncement();}
    JSONArray routes(){return routeDefinitions;}
    boolean awaitReady(int seconds)throws InterruptedException {if(!ready)readyLatch.await(seconds,TimeUnit.SECONDS);return ready;}
    public boolean isReady(){return ready;}
    void beforeLogin(){if(saveSync!=null)saveSync.beforeLogin();}
    String technicalStatus(){return lastError;}
    public void recordError(String path,Throwable error){lastError=path+" · "+describe(error);android.util.Log.w("ArkLocalIntegrated","request_failed "+path+" "+error.getClass().getSimpleName());}
    public void recordRequest(String path,int status) {
        JSONObject event=new JSONObject(true);event.put("path",path.substring(0,Math.min(path.length(),200)));event.put("status",status);event.put("time",System.currentTimeMillis());
        synchronized(recentRequests){recentRequests.addLast(event);while(recentRequests.size()>40)recentRequests.removeFirst();}
    }
    private static String describe(Throwable error){
        StackTraceElement[] trace=error.getStackTrace();
        return error.getClass().getSimpleName()+(trace.length==0?"":" @ "+trace[0].getClassName()+"."+trace[0].getMethodName()+":"+trace[0].getLineNumber());
    }
    private void startListeners()throws Exception {
        http=new LocalHttpServer(10029,this);tls=new LocalHttpServer(8030,this);
        KeyStore keys=KeyStore.getInstance("PKCS12");char[] password="local-loopback".toCharArray();
        try(InputStream input=new FileInputStream(new File(root,"loopback.p12"))){keys.load(input,password);}
        KeyManagerFactory factory=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());factory.init(keys,password);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(factory.getKeyManagers(),null,new SecureRandom());
        tls.makeSecure(ssl.getServerSocketFactory(),null);
        http.start(30000,true);tls.start(30000,true);
    }
    private static String read(File file)throws IOException {
        try(InputStream in=new FileInputStream(file)){return new String(readAll(in),StandardCharsets.UTF_8);}
    }
    private static byte[] readAll(InputStream input)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[65536];int n;while((n=input.read(block))>=0)out.write(block,0,n);return out.toByteArray();}
    private static String digest(byte[] bytes)throws Exception {byte[] h=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte v:h)b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();}
    private void extractPayload(String apkPath,File destination)throws Exception {
        try(ZipFile apk=new ZipFile(apkPath)) {
            ZipEntry payload=apk.getEntry("assets/arklocal/payload.zip");
            if(payload==null)throw new IOException("内置本地服数据缺失");
            byte[] bytes;try(InputStream in=apk.getInputStream(payload)){bytes=readAll(in);}
            String expected=digest(bytes);File marker=new File(destination,"payload-version.txt");
            if(marker.isFile()&&read(marker).trim().equals(expected))return;
            String base=destination.getCanonicalPath()+File.separator;
            try(ZipInputStream input=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while((entry=input.getNextEntry())!=null) {
                    File target=new File(destination,entry.getName());
                    if(!target.getCanonicalPath().startsWith(base)||entry.getName().contains("\\"))throw new IOException("内置数据路径无效");
                    if(entry.isDirectory()){if(!target.isDirectory()&&!target.mkdirs())throw new IOException("数据目录创建失败");continue;}
                    if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("数据目录创建失败");
                    File temporary=new File(target.getPath()+".new");
                    try(FileOutputStream output=new FileOutputStream(temporary)) {byte[] block=new byte[65536];int n;while((n=input.read(block))>=0)output.write(block,0,n);output.getFD().sync();}
                    if(!temporary.renameTo(target))throw new IOException("内置数据更新失败");
                }
            }
            try(FileOutputStream output=new FileOutputStream(marker)){output.write(expected.getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
        }
    }
    @Override public void perform(Activity activity,String action,LocalOverlay.Callback callback) {
        if(action.equals("start")){start(activity,modulePath);callback.onResult(true,stage);return;}
        worker.execute(()->{
            try {
                if(action.equals("diagnostics")) {
                    JSONObject report=new JSONObject(true);report.put("stage",stage);report.put("android",android.os.Build.VERSION.RELEASE);report.put("routes",http==null?0:http.routeCount());report.put("lastError",lastError);report.put("storage","android-sqlite");report.put("gameLoginVerified",false);
                    report.put("moduleRevision","20261029-gameplay-r38");report.put("sdkProxy",sdkProxy!=null);
                    report.put("selectedDrawContent",LocalGachaOptions.getSelectedPool());
                    report.put("officialPublicContent",publicContent==null?null:publicContent.status());
                    report.put("storagePublication","locked-same-directory-rename");
                    report.put("androidApi",android.os.Build.VERSION.SDK_INT);
                    report.put("supportedAbis",android.os.Build.SUPPORTED_ABIS);
                    report.put("deviceModel",android.os.Build.MODEL);
                    report.put("maxHeapMiB",Runtime.getRuntime().maxMemory()/1048576);
                    try{report.put("pageSize",android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE));}catch(Exception ignored){}
                    synchronized(recentRequests){report.put("recentRequests",new ArrayList<>(recentRequests));}
                    if(android.os.Build.VERSION.SDK_INT>=30)try {
                        android.app.ActivityManager manager=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
                        Object history=manager.getClass().getMethod("getHistoricalProcessExitReasons",String.class,int.class,int.class).invoke(manager,context.getPackageName(),0,1);
                        if(history instanceof List&&!((List<?>)history).isEmpty()) {
                            Object exit=((List<?>)history).get(0);JSONObject previous=new JSONObject(true);
                            for(String key:new String[]{"Reason","Status","Timestamp"})previous.put(key.toLowerCase(Locale.ROOT),exit.getClass().getMethod("get"+key).invoke(exit));
                            report.put("previousProcessExit",previous);
                        }
                    }catch(Exception ignored){}
                    export(activity,report.toJSONString().getBytes(StandardCharsets.UTF_8),"Arknights-local-diagnostics-",callback);return;
                }
                if(action.equals("status")){String save="存档尚未打开";if(ready&&store!=null)synchronized(DATA_LOCK){save=saveSummary();}callback.onStatus(true,stage,save,(saveSync==null?"":saveSync.status())+(lastError.isEmpty()?"":"\n最近一次操作未完成，可导出诊断信息查看。"));return;}
                if(!ready)throw new IllegalStateException(stage);
                if(action.equals(LocalOverlay.ACTION_GACHA_OPTIONS)) {
                    try {
                        List<LocalOverlay.GachaOption> options=new ArrayList<>();
                        String selected;
                        synchronized(DATA_LOCK) {
                            JSONArray catalog=LocalGachaOptions.catalog(root);
                            for(int i=0;i<catalog.size();i++) {
                                Object value=catalog.get(i);
                                if(!(value instanceof JSONObject))continue;
                                JSONObject entry=(JSONObject)value;
                                String id=entry.getString("id"),name=entry.getString("name");
                                if(id!=null&&!id.trim().isEmpty())options.add(new LocalOverlay.GachaOption(id,id.equals(name)?id:name));
                            }
                            selected=LocalGachaOptions.getSelectedPool();
                        }
                        callback.onGachaOptions(true,options,selected,"请选择要替换当前寻访的出货内容。");
                    } catch(RuntimeException error) {
                        lastError=describe(error);
                        callback.onGachaOptions(false,new ArrayList<>(),LocalGachaOptions.getSelectedPool(),"无法读取寻访内容，请确认本地服务已就绪后重试。");
                    }
                    return;
                }
                String gachaPrefix=LocalOverlay.ACTION_GACHA_SELECT+":";
                if(action.startsWith(gachaPrefix)) {
                    String requested=action.substring(gachaPrefix.length());
                    try {
                        String selected;
                        synchronized(DATA_LOCK){selected=persistGachaSelectionLocked(requested);}
                        callback.onResult(true,selected==null?"已恢复原卡池。":"已保存自选寻访内容。返回寻访界面后生效。");
                    } catch(IllegalArgumentException unavailable) {
                        callback.onResult(false,"该寻访内容当前不可用，请重新打开列表选择。");
                    } catch(IOException notSaved) {
                        lastError=describe(notSaved);
                        callback.onResult(false,"自选寻访内容未保存，请稍后重试。");
                    }
                    return;
                }
                if(action.equals("refill")) {
                    synchronized(DATA_LOCK){store.refillResources(activeUid());}
                    callback.onResult(true,"抽卡资源已补充。返回游戏后重新登录可刷新显示。\n"+summary());return;
                }
                if(action.equals("materials")||action.equals("skins")) {
                    JSONObject granted;synchronized(DATA_LOCK){granted=grantSave(activeUid(),action.equals("materials"),action.equals("skins"));}
                    callback.onResult(true,action.equals("materials")?"已补齐本版收录的养成材料、作战记录和龙门币。重新登录游戏刷新显示。":"已加入本版收录的 "+granted.getIntValue("costumeItems")+" 套可购买时装。重新登录游戏后在干员装扮中选择。");return;
                }
                if(action.equals("sync")) {showSyncSettings(activity,callback);return;}
                throw new IllegalArgumentException("未知操作");
            } catch(Throwable e){lastError=describe(e);callback.onResult(false,"操作未完成，请确认本地服务已就绪；必要时导出诊断信息。");}
        });
    }
    private void showSyncSettings(Activity activity,LocalOverlay.Callback callback) {
        activity.runOnUiThread(()->{
            android.widget.LinearLayout form=new android.widget.LinearLayout(activity);
            form.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padding=(int)(20*activity.getResources().getDisplayMetrics().density);
            form.setPadding(padding,padding,padding,padding);
            android.widget.TextView explanation=new android.widget.TextView(activity);
            explanation.setText(saveSync.status()+"\n填写已存在的 WebDAV 文件夹。坚果云根目录将使用 Arknights-local 子文件夹。保存后立即同步，登录时优先使用较新存档；启用自动同步后，进度变动会自动上传。离线时保留本地进度。");
            form.addView(explanation);
            android.widget.EditText directory=new android.widget.EditText(activity);
            directory.setHint("WebDAV 文件夹地址（https://…/dav/）");
            directory.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
            directory.setText(saveSync.directory());form.addView(directory);
            android.widget.EditText username=new android.widget.EditText(activity);
            username.setHint("用户名");username.setSingleLine(true);username.setText(saveSync.username());form.addView(username);
            android.widget.EditText password=new android.widget.EditText(activity);
            password.setHint("密码或应用专用密码");password.setSingleLine(true);
            password.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.setText(saveSync.password());form.addView(password);
            android.widget.CheckBox automatic=new android.widget.CheckBox(activity);
            automatic.setText("启用自动同步");automatic.setChecked(saveSync.directory().isEmpty()||saveSync.enabled());form.addView(automatic);
            android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(form);
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity).setTitle("同步存档").setView(scroll)
                    .setPositiveButton("保存并同步",null).setNegativeButton("取消",(d,w)->callback.onResult(false,"已取消同步配置"))
                    .setOnCancelListener(d->callback.onResult(false,"已取消同步配置")).create();
            dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try {
                    saveSync.configure(directory.getText().toString(),username.getText().toString(),password.getText().toString(),automatic.isChecked());
                    dialog.dismiss();
                    worker.execute(()->{
                        try {callback.onResult(true,saveSync.synchronize(true));}
                        catch(Exception unavailable){callback.onResult(false,saveSync.status());}
                    });
                }catch(Exception invalid){directory.setError("请检查 WebDAV 文件夹地址及配置");}
            }));
            dialog.show();
        });
    }
    private void export(Activity activity,byte[] bytes,String prefix,LocalOverlay.Callback callback) {
        pendingExport=bytes;pendingExportCallback=callback;pendingExportTitle=prefix+System.currentTimeMillis()+".json";
        activity.runOnUiThread(()->{
            try {
                if(activity.isFinishing()||activity.isDestroyed())throw new IllegalStateException("Activity closed");
                android.content.ComponentName component=new android.content.ComponentName(activity.getPackageName(),"com.codex.arklocal.bootstrap.ExportActivity");
                try{activity.getPackageManager().getActivityInfo(component,0);activity.startActivity(new Intent().setComponent(component));}
                catch(android.content.pm.PackageManager.NameNotFoundException absent){openPendingExport(activity);}
            }catch(Throwable e){pendingExport=null;pendingExportCallback=null;callback.onResult(false,"无法打开保存窗口，请返回游戏重试。");}
        });
    }
    private long activeUid() {
        String secret=ArknightsApplication.getSecretByIP("127.0.0.1");
        if(secret==null||secret.isEmpty())throw new IllegalStateException("请先登录本地账号");
        return com.hypergryph.arknights.core.dao.userDao.queryUidBySecret(secret);
    }
    /** Legacy one-time stock-up target: only the seeded uid 10000000, and only still absent grant rows matter.
     * Returns 0 when that row no longer exists, so fresh multi-account saves keep their own per-account grants. */
    private long seedGrantUid() {
        long seed=10000000L;
        try {
            List<Long> hits=ArknightsApplication.jdbcTemplate.queryForList(
                "SELECT uid FROM account WHERE uid=?", Long.class, seed);
            if(hits.size()!=1)return 0L;
            Long found=hits.get(0);
            return found==null?0L:found.longValue();
        } catch(RuntimeException unavailable) {
            return 0L;
        }
    }
    private JSONObject grantSave(long uid,boolean materials,boolean skins)throws Exception {
        if(uid<1)throw new IllegalArgumentException("Missing target local account");
        File backupDirectory=new File(root,"save-backups");
        if(!backupDirectory.isDirectory()&&!backupDirectory.mkdirs())throw new IOException("Cannot prepare local backup directory");
        File backup=new File(backupDirectory,"before-grants-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".json");
        try(FileOutputStream output=new FileOutputStream(backup)){store.exportSnapshot(output);output.getFD().sync();}
        return store.transaction(jdbc->{
            String original=jdbc.queryForObject("SELECT user FROM account WHERE uid=?",String.class,uid);
            if(original==null)throw new IllegalStateException("Local account is unavailable");
            JSONObject user=JSON.parseObject(original);
            JSONObject result=LocalSaveGrants.apply(user,ArknightsApplication.itemTable,ArknightsApplication.skinTable,materials,skins,System.currentTimeMillis()/1000L);
            if(jdbc.update("UPDATE account SET user=? WHERE uid=?",JSON.toJSONString(user,com.alibaba.fastjson.serializer.SerializerFeature.WriteMapNullValue),uid)!=1)throw new IllegalStateException("Local grant account update failed");
            return result;
        });
    }
    public void openPendingExport(Activity activity) {
        if(pendingExport==null||pendingExportCallback==null){activity.finish();return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,pendingExportTitle);
        activity.startActivityForResult(intent,EXPORT_REQUEST);
    }
    public boolean handleExportResult(Activity activity,int requestCode,int resultCode,Intent data) {
        if(requestCode!=EXPORT_REQUEST)return false;
        byte[] snapshot=pendingExport;LocalOverlay.Callback callback=pendingExportCallback;pendingExport=null;pendingExportCallback=null;
        if(resultCode!=Activity.RESULT_OK||data==null||data.getData()==null||snapshot==null){if(callback!=null)callback.onResult(false,"已取消保存文件。");return true;}
        Uri destination=data.getData();worker.execute(()->{
            try(OutputStream output=activity.getContentResolver().openOutputStream(destination,"w")) {
                if(output==null)throw new IOException("无法打开目标文件");output.write(snapshot);output.flush();
                if(callback!=null)callback.onResult(true,"文件已保存到你选择的位置。");
            } catch(Exception e){if(callback!=null)callback.onResult(false,"文件保存失败，请检查目标位置后重试。");}
        });return true;
    }
    public String summary() {
        String result=stage;
        if(ready&&store!=null)synchronized(DATA_LOCK){result+="\n"+saveSummary();}
        if(!lastError.isEmpty())result+="\n最近一次操作未完成，可导出诊断信息查看。";
        return result;
    }
    public String diagnostics(){return summary()+"\n应用内本地模式\nAndroid "+android.os.Build.VERSION.RELEASE+"\n"+(http==null?0:http.routeCount())+" 个本地接口\n无需外部终端或联网隧道";}
    private String saveSummary() {
        JSONArray entries=JSON.parseArray(store.summary());if(entries.isEmpty())return "没有本地账号";
        long uid;try{uid=activeUid();}catch(IllegalStateException notLoggedIn){return "尚未登录本地账号；输入数字或英文账号、密码即可自动建号。";}
        JSONObject account=null;for(int index=0;index<entries.size();index++)if(entries.getJSONObject(index).getLongValue("uid")==uid){account=entries.getJSONObject(index);break;}
        if(account==null)return "未找到当前本地账号";
        JSONObject resources=account.getJSONObject("resources");
        return "本地账号 "+account.getLongValue("uid")+" · "+account.getIntValue("operatorCount")+" 名干员\n源石 "+resources.getLongValue("androidDiamond")+" · 合成玉 "+resources.getLongValue("diamondShard")+"\n寻访凭证 "+resources.getLongValue("gachaTicket")+" · 十连凭证 "+resources.getLongValue("tenGachaTicket");
    }
    public JSONObject selfTest()throws Exception {
        if(!awaitReady(90))throw new IllegalStateException(summary());
        JSONObject r=new JSONObject(true);r.put("backendReady",ready);r.put("storage","android-sqlite");r.put("routes",http.routeCount());r.put("requiresTermux",false);r.put("requiresRoot",false);r.put("summary",store.summary());return r;
    }
}
