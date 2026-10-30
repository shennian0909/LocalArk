from pathlib import Path
import argparse,datetime,hashlib,json,shutil,subprocess,zipfile
W=Path(__file__).resolve().parents[2];root=W/'work/phone-integrated'
p=argparse.ArgumentParser();p.add_argument('--compile-only',action='store_true');p.add_argument('--precompiled-classes',type=Path);p.add_argument('--overlay-source',type=Path);args=p.parse_args()
stage=root/'build'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S');stage.mkdir(parents=True)
java=Path('C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/java.exe');tools=W/'work/apk/patched/build-tools/android-14';android=W/'work/phone-local/android.jar'
deps=[p for p in (root/'deps').glob('*.jar') if p.name!='xposed-api-82.jar']
def run(cmd,name,timeout=240):
    result=subprocess.run(list(map(str,cmd)),capture_output=True,encoding='utf-8',errors='replace',timeout=timeout)
    (stage/name).write_text(result.stdout+result.stderr,encoding='utf-8')
    if result.returncode:print((result.stdout+result.stderr)[-13000:]);raise SystemExit(result.returncode)
    return result.stdout
classes=stage/'classes';classes.mkdir();stubs=stage/'compile-stubs';stubs.mkdir()
stub_sources=list((root/'compile-stubs').rglob('*.java'))
run([java.parent/'javac.exe','-J-Duser.language=en','--release','8','-Xlint:-options','-encoding','UTF-8','-classpath',android,'-d',stubs,*stub_sources],'compile-api.txt')
sources=[]
for directory in ['backend-src','framework-src','storage-src','ui-src','runtime-src']:
    sources.extend(p for p in (root/directory).rglob('*.java') if not any(part in p.parts for part in ['.build','.validation','.verify','tests']))
classpath=';'.join(map(str,[android,stubs,*deps]))
response=stage/'javac.args'
response.write_text('\n'.join('"'+str(p).replace('\\','/')+'"' for p in sources),encoding='utf-8')
if args.precompiled_classes:
    shutil.copytree(args.precompiled_classes,classes,dirs_exist_ok=True)
    if args.overlay_source:run([java.parent/'javac.exe','--release','17','-parameters','-encoding','UTF-8','-classpath',str(classes)+';'+classpath,'-d',classes,args.overlay_source],'compile-overlay.txt')
else:
    run([java.parent/'javac.exe','-J-Duser.language=en','--release','17','-parameters','-encoding','UTF-8','-classpath',classpath,'-d',classes,'@'+str(response)],'compile.txt')
print(json.dumps({'stage':str(stage),'sources':len(sources),'compile_passed':True}),flush=True)
(root/'last-compile.json').write_text(json.dumps({'stage':str(stage),'classes':str(classes)},indent=2),encoding='utf-8')
if args.compile_only:raise SystemExit()
program=stage/'program.jar'
with zipfile.ZipFile(program,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for file in classes.rglob('*.class'):z.write(file,file.relative_to(classes).as_posix())
dex=stage/'dex';dex.mkdir()
d8=[java,'-Xmx2g','-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--classpath',stubs,'--output',dex,program,*deps]
run(d8,'d8.txt')
res=stage/'res';(res/'xml').mkdir(parents=True);(res/'raw').mkdir();(res/'values').mkdir()
(res/'xml/network.xml').write_text('<network-security-config><base-config cleartextTrafficPermitted="true"/><domain-config><domain>127.0.0.1</domain><domain>localhost</domain><trust-anchors><certificates src="@raw/local_ca"/></trust-anchors></domain-config></network-security-config>',encoding='utf-8')
shutil.copy2(W/'work/phone-local/loopback-pki-v2/ca.pem',res/'raw/local_ca.pem')
(res/'values/strings.xml').write_text('<resources><string-array name="xposed_scope"><item>com.hypergryph.arknights.localtest</item></string-array></resources>',encoding='utf-8')
manifest=stage/'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.codex.arklocal.module" android:versionCode="1" android:versionName="0.1-test"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="28"/><uses-permission android:name="android.permission.INTERNET"/><application android:largeHeap="true" android:debuggable="true" android:allowBackup="false" android:usesCleartextTraffic="true" android:networkSecurityConfig="@xml/network" android:label="本地服集成测试"><meta-data android:name="xposedmodule" android:value="true"/><meta-data android:name="xposeddescription" android:value="明日方舟手机本地后端与游戏内悬浮面板"/><meta-data android:name="xposedminversion" android:value="82"/><meta-data android:name="xposedscope" android:resource="@array/xposed_scope"/><activity android:name="com.codex.arklocal.HarnessActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''',encoding='utf-8')
unsigned=stage/'module-unaligned.apk'
run([tools/'aapt.exe','package','-f','-M',manifest,'-S',res,'-I',android,'-F',unsigned],'aapt.txt')
with zipfile.ZipFile(unsigned,'a',compression=zipfile.ZIP_DEFLATED) as z:
    for file in sorted(dex.glob('*.dex')):z.write(file,file.name)
    z.writestr('assets/xposed_init','com.codex.arklocal.ModuleEntry\n')
    z.write(root/'payload.zip','assets/arklocal/payload.zip',compress_type=zipfile.ZIP_STORED)
aligned=stage/'module-unsigned.apk';signed=stage/'ArkLocal-module-test.apk'
run([tools/'zipalign.exe','-p','4',unsigned,aligned],'align.txt')
key=W/'outputs/Arknights-Local/android-client/signing-key'
run([java,'--class-path',W/'work/apk/tools/apksig-8.9.3.jar',W/'work/apk/patched/20261023T163128.412501Z/AndroidApkBridge.java','sign',aligned,signed,key/'arknights-local-android.p12',key/'keystore-password.txt'],'signature.txt')
report={'apk':str(signed),'sha256':hashlib.file_digest(signed.open('rb'),'sha256').hexdigest(),'bytes':signed.stat().st_size,'sources':len(sources),'runtime_verified':False}
(root/'module-build.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report),flush=True)
