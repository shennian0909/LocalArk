from pathlib import Path
from datetime import datetime,timedelta,timezone
import ipaddress,secrets,json
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID,ExtendedKeyUsageOID
p=Path(__file__).resolve().parent/'proxy-pki-r3';p.mkdir(exist_ok=True)
if (p/'ca.pem').exists():print('Existing application-only proxy certificates retained.');raise SystemExit()
now=datetime.now(timezone.utc);ca_key=rsa.generate_private_key(public_exponent=65537,key_size=2048);key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Arknights private-app local routing CA')])
ca=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(ca_key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(days=1)).not_valid_after(now+timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=True,path_length=0),True).sign(ca_key,hashes.SHA256())
hosts=['localhost','as.hypergryph.com','game-config.hypergryph.com','ak-conf.hypergryph.com','launcher.hypergryph.com']
cert=x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'localhost')])).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(days=1)).not_valid_after(now+timedelta(days=3650)).add_extension(x509.BasicConstraints(ca=False,path_length=None),True).add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]+[x509.DNSName(h) for h in hosts]),False).add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]),False).sign(ca_key,hashes.SHA256())
password=secrets.token_urlsafe(36).encode()
for filename,data in [('ca.pem',ca.public_bytes(serialization.Encoding.PEM)),('site.pem',cert.public_bytes(serialization.Encoding.PEM)),('site-key.pem',key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.BestAvailableEncryption(password))),('key-password.txt',password+b'\n')]:
    with (p/filename).open('xb') as output:output.write(data)
(p/'scope.json').write_text(json.dumps({'scope':'single local-test app only, private loopback backend','trusted_domains':hosts+['127.0.0.1'],'system_trust_modified':False,'ca_private_key_persisted':False},indent=2))
print('Application-only routing TLS material prepared; no system trust changes.')
