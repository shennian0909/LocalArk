"""Append private bootstrap components without renumbering existing XML strings."""
import struct
NO=0xffffffff
def append(raw,package,app_label=None):
    assert struct.unpack_from('<HH',raw)==(3,8)
    chunks=[];pos=8
    while pos<len(raw):
        typ,head,size=struct.unpack_from('<HHI',raw,pos);chunks.append((typ,bytearray(raw[pos:pos+size])));pos+=size
    assert pos==len(raw)
    pool=next(data for typ,data in chunks if typ==1)
    _,head,_,count,styles,flags,start,style_start=struct.unpack_from('<HHIIIIII',pool)
    assert head==28 and styles==0 and style_start==0
    utf8=bool(flags&0x100)
    def length8(at):
        value=pool[at];return (((value&127)<<8)|pool[at+1],at+2) if value&128 else (value,at+1)
    def length16(at):
        value=struct.unpack_from('<H',pool,at)[0];return (((value&32767)<<16)|struct.unpack_from('<H',pool,at+2)[0],at+4) if value&32768 else (value,at+2)
    values=[]
    for index in range(count):
        at=start+struct.unpack_from('<I',pool,28+4*index)[0]
        if utf8:
            _,at=length8(at);n,at=length8(at);values.append(bytes(pool[at:at+n]).decode('utf-8'))
        else:
            n,at=length16(at);values.append(bytes(pool[at:at+2*n]).decode('utf-16-le'))
    def add(value):
        if value not in values:values.append(value)
        return values.index(value)
    resources=next((list(struct.unpack('<'+'I'*((len(data)-8)//4),data[8:])) for typ,data in chunks if typ==0x180),[])
    resource_ids={'theme':0x01010000,'name':0x01010003,'exported':0x01010010,'authorities':0x01010018,'initOrder':0x0101001a}
    for name,resid in resource_ids.items():
        index=add(name)
        while len(resources)<=index:resources.append(0)
        assert resources[index] in (0,resid)
        resources[index]=resid
    android=add('http://schemas.android.com/apk/res/android')
    if app_label is not None:
        label=add(app_label)
        application_label=None
        for typ,node in chunks:
            if typ!=0x102 or values[struct.unpack_from('<I',node,20)[0]]!='application':continue
            attr_start,attr_size,attr_count=struct.unpack_from('<HHH',node,24)
            for i in range(attr_count):
                at=16+attr_start+i*attr_size
                index=struct.unpack_from('<I',node,at+4)[0]
                if index<len(resources) and resources[index]==0x01010001:
                    application_label=bytes(node[at+12:at+20])
        assert application_label is not None,'Application label missing'
        for typ,node in chunks:
            if typ!=0x102:continue
            attr_start,attr_size,attr_count=struct.unpack_from('<HHH',node,24)
            for i in range(attr_count):
                at=16+attr_start+i*attr_size
                index=struct.unpack_from('<I',node,at+4)[0]
                if index<len(resources) and resources[index]==0x01010001 and bytes(node[at+12:at+20])==application_label:
                    struct.pack_into('<IHBBI',node,at+8,label,8,0,3,label)
    def component(tag,attributes):
        name=add(tag);attrs=[]
        for key,kind,value in sorted(attributes,key=lambda a:resource_ids[a[0]]):
            index=add(key)
            if kind==3:raw_index=data=add(value)
            else:raw_index=NO;data=value
            attrs.append(struct.pack('<IIIHBBI',android,index,raw_index,8,0,kind,data))
        node=struct.pack('<HHIIIIIHHHHHH',0x102,16,36+20*len(attrs),0,NO,NO,name,20,20,len(attrs),0,0,0)+b''.join(attrs)
        end=struct.pack('<HHIIIII',0x103,16,24,0,NO,NO,name)
        return [(0x102,node),(0x103,end)]
    extra=component('provider',[('name',3,'com.codex.arklocal.bootstrap.BootstrapProvider'),('exported',0x12,0),('authorities',3,package+'.arklocal.bootstrap'),('initOrder',0x10,1999)])
    extra+=component('activity',[('name',3,'com.codex.arklocal.bootstrap.ExportActivity'),('exported',0x12,0),('theme',1,0x01030010)])
    for i,(typ,node) in enumerate(chunks):
        if typ==0x103 and values[struct.unpack_from('<I',node,20)[0]]=='application':chunks[i:i]=extra;break
    else:raise AssertionError('Application end missing')
    def len8(n):return bytes([n]) if n<128 else bytes([128|(n>>8),n&255])
    def len16(n):return struct.pack('<H',n) if n<32768 else struct.pack('<HH',32768|(n>>16),n&65535)
    buf=b'';offsets=[]
    for value in values:
        offsets.append(len(buf));units=len(value.encode('utf-16-le'))//2
        if utf8:
            encoded=value.encode('utf-8');buf+=len8(units)+len8(len(encoded))+encoded+b'\0'
        else:buf+=len16(units)+value.encode('utf-16-le')+b'\0\0'
    buf+=b'\0'*(-len(buf)%4);start=28+4*len(values)
    new_pool=struct.pack('<HHIIIIII',1,28,start+len(buf),len(values),0,flags&~1,start,0)+struct.pack('<'+'I'*len(values),*offsets)+buf
    resource_map=struct.pack('<HHI',0x180,8,8+4*len(resources))+struct.pack('<'+'I'*len(resources),*resources)
    body=b'';mapped=False
    for typ,node in chunks:
        if typ==1:body+=new_pool
        elif typ==0x180:body+=resource_map;mapped=True
        else:body+=node
    if not mapped:raise AssertionError('Source XML must contain resource ID map')
    return struct.pack('<HHI',3,8,len(body)+8)+body
