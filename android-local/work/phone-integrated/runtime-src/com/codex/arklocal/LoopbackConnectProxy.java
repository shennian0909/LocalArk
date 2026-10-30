package com.codex.arklocal;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Application-private routing for the two local-test SDK origins; no device VPN. */
final class LoopbackConnectProxy implements Closeable {
    private final ServerSocket listener;
    private final ExecutorService connections=Executors.newFixedThreadPool(8,r->{Thread t=new Thread(r,"ArkLocalConnect");t.setDaemon(true);return t;});
    private final Semaphore slots=new Semaphore(8);
    private final ProxySelector previous;
    private volatile boolean closed;
    private static boolean sdkHost(String name){return name!=null&&Arrays.asList("as.hypergryph.com","game-config.hypergryph.com","ak-conf.hypergryph.com").contains(name.toLowerCase(Locale.ROOT));}
    LoopbackConnectProxy()throws IOException {
        listener=new ServerSocket();listener.bind(new InetSocketAddress("127.0.0.1",8031),8);
        previous=ProxySelector.getDefault();
        // Unity's Android proxy discovery also consults these app-process properties.
        System.setProperty("http.proxyHost","127.0.0.1");System.setProperty("http.proxyPort","8031");
        System.setProperty("https.proxyHost","127.0.0.1");System.setProperty("https.proxyPort","8031");
        System.setProperty("http.nonProxyHosts","localhost|127.*|*.hycdn.cn|launcher.hypergryph.com|*.geetest.com|*.taptap.cn|*.taptap.com");
        try {
            android.system.Os.setenv("http_proxy","http://127.0.0.1:8031",true);
            android.system.Os.setenv("https_proxy","http://127.0.0.1:8031",true);
            android.system.Os.setenv("no_proxy","127.0.0.1,localhost,.hycdn.cn,launcher.hypergryph.com,.geetest.com,.taptap.cn,.taptap.com",true);
        }catch(android.system.ErrnoException e){throw new IOException("Cannot configure in-app native routing",e);}
        ProxySelector.setDefault(new ProxySelector(){
            public List<Proxy> select(URI uri){
                if(uri!=null&&"https".equalsIgnoreCase(uri.getScheme())&&sdkHost(uri.getHost())&&(uri.getPort()==-1||uri.getPort()==443)) {
                    android.util.Log.i("ArkLocalRouting","proxy_select "+uri.getHost());
                    return Collections.singletonList(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",8031)));
                }
                return Collections.singletonList(Proxy.NO_PROXY);
            }
            public void connectFailed(URI uri,SocketAddress address,IOException error){if(previous!=null&&!sdkHost(uri.getHost()))previous.connectFailed(uri,address,error);}
        });
        Thread accept=new Thread(()->{while(!closed)try{
            Socket socket=listener.accept();
            if(!slots.tryAcquire()){socket.close();continue;}
            connections.execute(()->{try{handle(socket);}catch(Exception ignored){}finally{slots.release();try{socket.close();}catch(IOException ignored){}}});
        }catch(IOException e){if(!closed)LocalBackend.get().recordError("local-connect",e);}},"ArkLocalConnectAccept");accept.setDaemon(true);accept.start();
    }
    private static String line(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1){if(value=='\n')break;if(bytes.size()>=4096)throw new IOException("Proxy header too long");if(value!='\r')bytes.write(value);}
        return bytes.toString("US-ASCII");
    }
    private void handle(Socket client)throws Exception {
        client.setSoTimeout(45000);InputStream input=client.getInputStream();OutputStream output=client.getOutputStream();
        String[] request=line(input).split(" ");
        if(request.length!=3)return;
        List<String> headers=new ArrayList<>();String header;
        while(!(header=line(input)).isEmpty()){if(headers.size()>=64)throw new IOException("Proxy headers exceeded");headers.add(header);}
        boolean tunnel=request[0].equals("CONNECT");String remoteHost="127.0.0.1";int remotePort;byte[] httpHead=null;
        if(tunnel) {
            String target=request[1];int colon=target.lastIndexOf(':');
            if(colon<0||!target.substring(colon+1).equals("443"))return;
            String host=target.substring(0,colon);
            if(sdkHost(host))remotePort=8030;
            else if(Arrays.asList("ak.hycdn.cn","web.hycdn.cn","ak-webview.hypergryph.com").contains(host.toLowerCase(Locale.ROOT))){remoteHost=host;remotePort=443;}
            else {android.util.Log.i("ArkLocalRouting","proxy_rejected_connect "+target);return;}
            android.util.Log.i("ArkLocalRouting","proxy_connect "+target);
        } else {
            if(!Arrays.asList("GET","POST","HEAD","PUT","DELETE","OPTIONS","PATCH").contains(request[0]))return;
            URI target=new URI(request[1]);
            if(!"http".equalsIgnoreCase(target.getScheme())||!("127.0.0.1".equals(target.getHost())||"localhost".equals(target.getHost()))||target.getPort()!=10029||target.getUserInfo()!=null||target.getFragment()!=null)return;
            remotePort=10029;String path=target.getRawPath();if(path==null||path.isEmpty())path="/";
            if(target.getRawQuery()!=null)path+="?"+target.getRawQuery();
            StringBuilder head=new StringBuilder(request[0]+" "+path+" HTTP/1.1\r\n");
            for(String value:headers){String lower=value.toLowerCase(Locale.ROOT);if(!lower.startsWith("host:")&&!lower.startsWith("proxy-connection:")&&!lower.startsWith("proxy-authorization:")&&!lower.startsWith("connection:"))head.append(value).append("\r\n");}
            head.append("Host: 127.0.0.1:10029\r\nConnection: close\r\n\r\n");httpHead=head.toString().getBytes(StandardCharsets.ISO_8859_1);
            android.util.Log.i("ArkLocalRouting","proxy_http "+request[0]+" "+target.getPath());
        }
        if(!LocalBackend.get().awaitReady(45))throw new IOException("Local backend is unavailable");
        try(Socket upstream=new Socket()) {
            upstream.connect(new InetSocketAddress(remoteHost,remotePort),5000);upstream.setSoTimeout(45000);
            if(tunnel){output.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));output.flush();}
            else {upstream.getOutputStream().write(httpHead);upstream.getOutputStream().flush();}
            Thread sender=new Thread(()->{try{copy(input,upstream.getOutputStream());}catch(IOException ignored){}finally{try{upstream.shutdownOutput();}catch(IOException ignored){}}},"ArkLocalConnectUpload");sender.setDaemon(true);sender.start();
            copy(upstream.getInputStream(),output);
        }
    }
    private static void copy(InputStream in,OutputStream out)throws IOException {byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);out.flush();}}
    public void close()throws IOException {closed=true;listener.close();connections.shutdownNow();ProxySelector.setDefault(previous);}
}
