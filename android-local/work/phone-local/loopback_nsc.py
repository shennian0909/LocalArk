import struct
def build(hosts=None,base_ca=False):
    no=0xffffffff
    names=['network-security-config','base-config','cleartextTrafficPermitted','true','domain-config','domain','includeSubdomains','false','127.1','127.0.0.1','localhost','trust-anchors','certificates','src','@raw/arklocal_ca','system']
    hosts=hosts or ['127.1','127.0.0.1','localhost']
    for h in hosts:
        if h not in names:names.append(h)
    ids={n:i for i,n in enumerate(names)};buf=b'';offsets=[]
    for n in names:
        b=n.encode();offsets.append(len(buf));buf+=bytes([len(n),len(b)])+b+b'\0'
    buf+=b'\0'*(-len(buf)%4);start=28+4*len(names)
    pool=struct.pack('<HHIIIIII',1,28,start+len(buf),len(names),0,0x100,start,0)+struct.pack('<'+'I'*len(offsets),*offsets)+buf
    def begin(name,attrs=()):
        b=struct.pack('<HHIIIIIHHHHHH',0x102,16,36+20*len(attrs),1,no,no,ids[name],20,20,len(attrs),0,0,0)
        for attr,value,typ in attrs:
            data=0x7f0e0002 if typ==1 else (int(value=='true') if typ==0x12 else ids[value])
            b+=struct.pack('<IIIHBBI',no,ids[attr],ids[value],8,0,typ,data)
        return b
    def end(name):return struct.pack('<HHIIIII',0x103,16,24,1,no,no,ids[name])
    body=pool+begin('network-security-config')+begin('base-config',[('cleartextTrafficPermitted','true',0x12)])
    if base_ca:body+=begin('trust-anchors')+begin('certificates',[('src','system',3)])+end('certificates')+begin('certificates',[('src','@raw/arklocal_ca',1)])+end('certificates')+end('trust-anchors')
    body+=end('base-config')+begin('domain-config')
    for host in hosts:
        body+=begin('domain',[('includeSubdomains','false',0x12)])+struct.pack('<HHIII',0x104,16,28,1,no)+struct.pack('<IHBBI',ids[host],8,0,3,ids[host])+end('domain')
    body+=begin('trust-anchors')+begin('certificates',[('src','@raw/arklocal_ca',1)])+end('certificates')
    if base_ca:body+=begin('certificates',[('src','system',3)])+end('certificates')
    body+=end('trust-anchors')+end('domain-config')+end('network-security-config')
    return struct.pack('<HHI',3,8,len(body)+8)+body
