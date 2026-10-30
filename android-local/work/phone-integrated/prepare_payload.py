from pathlib import Path
import hashlib,json,shutil,zipfile,argparse
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.serialization import pkcs12
from credit_limited_catalog import extend_credit_catalog
W=Path(__file__).resolve().parents[2];root=W/'work/phone-integrated';stage=root/'payload';stage.mkdir(exist_ok=True)
p=argparse.ArgumentParser();p.add_argument('--pki-dir',type=Path);p.add_argument('--sdk-proxy',action='store_true');args=p.parse_args()
source=W/'work/phone-local/phone-payload/server'
if not (stage/'data').exists():shutil.copytree(source/'data',stage/'data')
credit_source=json.loads((source/'data/shop/SocialGoodList.json').read_text(encoding='utf-8'))
credit_catalog,limited_goods=extend_credit_catalog(stage/'data',credit_source)
(stage/'data/shop/SocialGoodList.json').write_text(json.dumps(credit_catalog,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(root/'feature-checks/limited-credit-catalog.json').write_text(json.dumps({'count':len(limited_goods),'goods':limited_goods},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
config=json.loads((source/'config-template.json').read_text(encoding='utf-8'))
data_version=stage/'data/source-version.json'
if data_version.is_file():
    table_source=json.loads(data_version.read_text(encoding='utf-8'))
    assert table_source['clientVersion']==config['version']['android']['clientVersion']
    config['version']['android']['resVersion']=table_source['resVersion']
config['server']['GMKey']='in-process-ui-only'
config.pop('database',None)
for entry in config.get('network',{}).get('configs',{}).values():
    if isinstance(entry.get('network'),dict):entry['network']['sl']='http://127.0.0.1:10029/protocol/local-test'
if args.sdk_proxy:config['localRouting']={'sdkProxy':True}
(stage/'config.json').write_text(json.dumps(config,ensure_ascii=False),encoding='utf-8')
for name in ['login.html','register.html','user-center.html']:
    mobile=root/'web-src'/name
    shutil.copy2(mobile if mobile.exists() else source/name,stage/name)
for name in ['client-routes.json','seed.json']:
    src=(W/'work/phone-local'/name) if name=='client-routes.json' else root/name
    shutil.copy2(src,stage/name)
shutil.copy2(W/'outputs/Arknights-Cloud/private/local-account.json',stage/'local-account.json')
pki=args.pki_dir or W/'work/phone-local/loopback-pki-v2'
key=serialization.load_pem_private_key((pki/'site-key.pem').read_bytes(),password=(pki/'key-password.txt').read_text().strip().encode())
cert=x509.load_pem_x509_certificate((pki/'site.pem').read_bytes())
ca=x509.load_pem_x509_certificate((pki/'ca.pem').read_bytes())
encryption=(serialization.PrivateFormat.PKCS12.encryption_builder().kdf_rounds(20000).key_cert_algorithm(pkcs12.PBES.PBESv1SHA1And3KeyTripleDESCBC).hmac_hash(hashes.SHA1()).build(b'local-loopback'))
(stage/'loopback.p12').write_bytes(pkcs12.serialize_key_and_certificates(b'loopback',key,cert,[ca],encryption))
out=root/'payload.zip'
with zipfile.ZipFile(out,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for p in sorted(stage.rglob('*')):
        if p.is_file():z.write(p,p.relative_to(stage).as_posix())
print(json.dumps({'payload_bytes':out.stat().st_size,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'external_runtime_included':False}))
