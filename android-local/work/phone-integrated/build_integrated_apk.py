from pathlib import Path
import argparse,copy,datetime,hashlib,json,re,subprocess,zipfile
from append_components import append
W=Path(__file__).resolve().parents[2];root=W/'work/phone-integrated'
p=argparse.ArgumentParser();p.add_argument('--host-test',action='store_true');p.add_argument('--host-package',default='com.codex.arklocal.hosttest');p.add_argument('--base-apk',type=Path);p.add_argument('--report-name');p.add_argument('--unity-only-control',action='store_true');p.add_argument('--proxy-routing',action='store_true');args=p.parse_args()
if args.report_name and (Path(args.report_name).name!=args.report_name or not args.report_name.endswith('.json')):raise ValueError('Invalid report filename')
stage=root/('host-build' if args.host_test else 'game-build')/datetime.datetime.now().strftime('%Y%m%d-%H%M%S');stage.mkdir(parents=True)
java=Path('C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin/java.exe');tools=W/'work/apk/patched/build-tools/android-14';android=W/'work/phone-local/android.jar'
module_report=json.loads((root/'module-build.json').read_text(encoding='utf-8'));module=Path(module_report['apk'])
digest=hashlib.file_digest(module.open('rb'),'sha256').hexdigest();assert digest==module_report['sha256']
def run(cmd,name,timeout=300):
    r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    (stage/name).write_text(r.stdout+r.stderr,encoding='utf-8')
    if r.returncode:print((r.stdout+r.stderr)[-7000:]);raise SystemExit(r.returncode)
    return r.stdout
bootstrap=stage/'bootstrap';classes=stage/'classes';dex=stage/'dex'
for path in [bootstrap,classes,dex]:path.mkdir()
sources=[]
for source in (root/'bootstrap-src').rglob('*.java'):
    target=bootstrap/source.relative_to(root/'bootstrap-src');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(source.read_text(encoding='utf-8').replace('__MODULE_SHA256__',digest),encoding='utf-8');sources.append(target)
run([java.parent/'javac.exe','--release','8','-Xlint:-options','-encoding','UTF-8','-classpath',android,'-d',classes,*sources],'compile-bootstrap.txt')
bootstrap_classes=list(classes.rglob('*.class'))
run([java,'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--output',dex,*bootstrap_classes],'d8-bootstrap.txt')
package='com.hypergryph.arknights.localtest'
source=W/'outputs/Arknights-Phone-Local/Arknights-Phone-Local-candidate.apk'
if args.base_apk:source=args.base_apk.resolve()
if args.host_test:
    package=args.host_package
    if not re.fullmatch(r'com\.codex\.arklocal\.hosttest(?:\.[a-z0-9]+)*',package):raise ValueError('Invalid lab package')
    host=stage/'HostActivity.java'
    host.write_text('package com.codex.arklocal.hosttest; public final class HostActivity extends android.app.Activity {public void onCreate(android.os.Bundle b){super.onCreate(b);android.widget.TextView v=new android.widget.TextView(this);v.setTextSize(20);v.setPadding(40,40,40,40);v.setText("内置模块宿主测试\\n若模块自动加载成功，右侧会显示本地悬浮球。");setContentView(v);}}',encoding='utf-8')
    host_classes=stage/'host-classes';host_classes.mkdir();host_dex=stage/'host-dex';host_dex.mkdir()
    run([java.parent/'javac.exe','--release','8','-Xlint:-options','-encoding','UTF-8','-classpath',android,'-d',host_classes,host],'compile-host.txt')
    run([java,'-cp',tools/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--output',host_dex,*host_classes.rglob('*.class')],'d8-host.txt')
    res=stage/'res';(res/'xml').mkdir(parents=True);(res/'raw').mkdir()
    (res/'xml/network.xml').write_text('<network-security-config><base-config cleartextTrafficPermitted="true"/><domain-config><domain>127.0.0.1</domain><trust-anchors><certificates src="@raw/local_ca"/></trust-anchors></domain-config></network-security-config>')
    (res/'raw/local_ca.pem').write_bytes((W/'work/phone-local/loopback-pki-v2/ca.pem').read_bytes())
    manifest=stage/'AndroidManifest.xml';manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'+package+'" android:versionCode="1"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="28"/><uses-permission android:name="android.permission.INTERNET"/><application android:largeHeap="true" android:debuggable="true" android:allowBackup="false" android:usesCleartextTraffic="true" android:networkSecurityConfig="@xml/network" android:label="内置模块宿主测试"><activity android:name="com.codex.arklocal.hosttest.HostActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>',encoding='utf-8')
    source=stage/'host-original.apk';run([tools/'aapt.exe','package','-f','-M',manifest,'-S',res,'-I',android,'-F',source],'host-aapt.txt')
    with zipfile.ZipFile(source,'a',compression=zipfile.ZIP_DEFLATED) as z:z.write(host_dex/'classes.dex','classes.dex')
unaligned=stage/'integrated-unaligned.apk';expected={}
control_changes={}
if args.unity_only_control:
    with zipfile.ZipFile(W/'outputs/Arknights-Phone-Local/Arknights-Phone-Local-candidate.apk') as z:
        for name in ['assets/bin/Data/sharedassets1.assets.split14','res/xml/network_security_config.xml','res/raw/arklocal_ca.pem']:control_changes[name]=z.read(name)
if args.proxy_routing:
    import sys
    sys.path.insert(0,str(W/'work/phone-local'))
    import loopback_nsc
    control_changes['res/xml/network_security_config.xml']=loopback_nsc.build(['127.0.0.1','localhost','as.hypergryph.com','game-config.hypergryph.com','ak-conf.hypergryph.com'],base_ca=True)
    control_changes['res/raw/arklocal_ca.pem']=(root/'proxy-pki-r3/ca.pem').read_bytes()
if not args.host_test:
    with zipfile.ZipFile(source) as base:
        portal_bytes=base.read('assets/webportalconfig.json')
    portal_before=json.loads(portal_bytes)
    portal_after=copy.deepcopy(portal_before)
    entry=portal_after['targets']['ak_announcement']
    assert entry['entry']=='https://ak-webview.hypergryph.com/gameBulletin?target={target}'
    entry['entry']='https://ak-webview.hypergryph.com/gameBulletin?target=Android'
    portal_bytes=portal_bytes.replace(b'https://ak-webview.hypergryph.com/gameBulletin?target={target}',entry['entry'].encode('utf-8'),1)
    assert json.loads(portal_bytes)==portal_after
    control_changes['assets/webportalconfig.json']=portal_bytes
    with zipfile.ZipFile(source) as base:
        webview_before=json.loads(base.read('assets/webviewconfig.json'))
    webview_after=copy.deepcopy(webview_before)
    assert webview_after['core']['timeout']==10000
    # The SDK closed the real bulletin page at ten seconds during the emulator probe.
    webview_after['core']['timeout']=45000
    control_changes['assets/webviewconfig.json']=json.dumps(webview_after,ensure_ascii=False,separators=(',',':')).encode('utf-8')
with zipfile.ZipFile(source) as original,zipfile.ZipFile(unaligned,'w',allowZip64=True) as target:
    main_manifest=append(original.read('AndroidManifest.xml'),package,None if args.host_test else '明日方舟')
    dex_indices=[1 if n=='classes.dex' else int(re.fullmatch(r'classes([0-9]+)\.dex',n).group(1)) for n in original.namelist() if re.fullmatch(r'classes(?:[0-9]+)?\.dex',n)]
    new_dex='classes'+str(max(dex_indices)+1)+'.dex'
    for info in original.infolist():
        upper=info.filename.upper()
        if upper.startswith('META-INF/') and upper.endswith(('.MF','.SF','.RSA','.DSA','.EC')):continue
        h=hashlib.sha256()
        with target.open(copy.copy(info),'w') as output:
            if info.filename=='AndroidManifest.xml':output.write(main_manifest);h.update(main_manifest)
            elif info.filename in control_changes:output.write(control_changes[info.filename]);h.update(control_changes[info.filename])
            else:
                with original.open(info) as input:
                    while block:=input.read(1024*1024):output.write(block);h.update(block)
        expected[info.filename]=h.hexdigest()
    target.writestr(new_dex,(dex/'classes.dex').read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    target.write(module,'assets/arklocal/module.apk',compress_type=zipfile.ZIP_STORED)
aligned=stage/'integrated-unsigned.apk';signed=stage/('ArkLocal-host-integrated.apk' if args.host_test else 'Arknights-Phone-Integrated-candidate.apk')
run([tools/'zipalign.exe','-p','4',unaligned,aligned],'align.txt')
key=W/'outputs/Arknights-Local/android-client/signing-key'
run([java,'--class-path',W/'work/apk/tools/apksig-8.9.3.jar',W/'work/apk/patched/20261023T163128.412501Z/AndroidApkBridge.java','sign',aligned,signed,key/'arknights-local-android.p12',key/'keystore-password.txt'],'signature.txt')
run([tools/'aapt.exe','dump','xmltree',signed,'AndroidManifest.xml'],'manifest-check.txt')
with zipfile.ZipFile(signed) as z:
    for name,digest_before in expected.items():
        with z.open(name) as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==digest_before,name
    with z.open('assets/arklocal/module.apk') as stream:assert hashlib.file_digest(stream,'sha256').hexdigest()==module_report['sha256']
    assert z.read(new_dex)==(dex/'classes.dex').read_bytes()
report={'apk':str(signed),'sha256':hashlib.file_digest(signed.open('rb'),'sha256').hexdigest(),'bytes':signed.stat().st_size,'module_sha256':module_report['sha256'],'bootstrap_dex':new_dex,'existing_payloads_verified':len(expected),'added_components':['private BootstrapProvider','private ExportActivity'],'runtime_verified':False,'physical_phone_verified':False}
report['base_apk']=str(source)
for intermediate in [unaligned,aligned]:
    intermediate.unlink()
report['intermediate_apks_removed']=True
(root/(args.report_name or ('host-build.json' if args.host_test else 'integrated-build.json'))).write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report),flush=True)
