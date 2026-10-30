package com.codex.arklocal;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import fi.iki.elonen.NanoHTTPD;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** In-process transport for the existing local-server controller implementations. */
public final class LocalHttpServer extends NanoHTTPD {
    private final LocalBackend backend;
    private volatile List<Route> routes;
    private final java.util.concurrent.atomic.AtomicInteger syncTraces=new java.util.concurrent.atomic.AtomicInteger();
    LocalHttpServer(int port,LocalBackend backend) {super("127.0.0.1",port);this.backend=backend;}
    @Override protected boolean useGzipWhenAccepted(Response response) {return false;}
    void registerControllers() throws Exception {
        List<Route> collected=new ArrayList<>();
        for(Class<?> cls:ControllerRegistry.CLASSES) {
            if(cls.getName().endsWith(".Admin"))continue;
            Object controller;
            try {controller=cls.getDeclaredConstructor().newInstance();}
            catch(NoSuchMethodException missing) {
                Constructor<?> constructor=cls.getDeclaredConstructors()[0];Object[] values=new Object[constructor.getParameterCount()];
                for(int i=0;i<values.length;i++)values[i]=constructor.getParameterTypes()[i].getDeclaredConstructor().newInstance();
                controller=constructor.newInstance(values);
            }
            Annotation root=find(cls.getAnnotations(),"RequestMapping");
            for(java.lang.reflect.Method method:cls.getDeclaredMethods()) {
                Annotation mapping=null;String verb="*";
                for(Annotation annotation:method.getAnnotations()) {
                    String kind=annotation.annotationType().getSimpleName();
                    if(Arrays.asList("RequestMapping","GetMapping","PostMapping","PutMapping","DeleteMapping").contains(kind)) {
                        mapping=annotation;
                        if(!kind.equals("RequestMapping"))verb=kind.substring(0,kind.length()-7).toUpperCase(Locale.ROOT);
                        break;
                    }
                }
                if(mapping==null)continue;
                for(String prefix:paths(root))for(String suffix:paths(mapping))collected.add(new Route(controller,method,verb,join(prefix,suffix)));
            }
        }
        collected.sort(Comparator.comparingInt((Route r)->r.variableNames.size()).thenComparingInt(r->r.verb.equals("*")?1:0));
        routes=collected;
    }
    int routeCount(){return routes==null?0:routes.size();}
    @Override public Response serve(IHTTPSession session) {
        Response response=serveRequest(session);
        backend.recordRequest(session.getUri(),response.getStatus().getRequestStatus());
        android.util.Log.i("ArkLocalRouting","local_response "+session.getUri()+" "+response.getStatus().getRequestStatus());
        return response;
    }
    private Response serveRequest(IHTTPSession session) {
        String path=session.getUri();
        try {
            if(!"127.0.0.1".equals(session.getRemoteIpAddress()))return answer(403,"{}",null);
            String host=session.getHeaders().get("host");
            if(!Arrays.asList("127.0.0.1:10029","127.0.0.1:8030","localhost:10029","localhost:8030","as.hypergryph.com","as.hypergryph.com:443","game-config.hypergryph.com","game-config.hypergryph.com:443","ak-conf.hypergryph.com","ak-conf.hypergryph.com:443","launcher.hypergryph.com","launcher.hypergryph.com:443").contains(host))return answer(421,"{}",null);
            String canonical=URLDecoder.decode(URLDecoder.decode(path,"UTF-8"),"UTF-8").replace('\\','/');
            if(Arrays.asList(canonical.split("/")).contains("..") || canonical.toLowerCase(Locale.ROOT).startsWith("/admin"))return answer(403,"{}",null);
            if(path.equals("/__local_health__"))return answer(200,"{\"storage\":\"android-sqlite\",\"gateway\":\"ready\"}",null);
            if(Arrays.asList("/protocol/local-test","/protocol/plain/ak/service","/protocol/plain/ak/privacy","/protocol/plain/ak/children_privacy","/protocol/plain/ak/overview_of_changes").contains(path)
                    && (session.getMethod()==Method.GET||session.getMethod()==Method.HEAD)) {
                String page="<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>本地测试说明</title><body style=\"font-family:sans-serif;padding:24px;line-height:1.8\"><h2>本地测试说明</h2><p>这是非官方的个人本地测试环境。本页仅说明本地模式，不是官方游戏服务协议。</p><p>账号和存档保存在此应用内。请使用自设的英文字母或数字账号、密码；新账号首次密码登录会自动创建独立存档。请勿输入官方账号密码。</p><p>游戏资源仍可能联网下载。卸载应用或清除应用数据会移除本地存档，请先通过悬浮球配置同步存档，并确认最新进度已上传。</p></body></html>";
                Response result=newFixedLengthResponse(Response.Status.OK,"text/html; charset=utf-8",session.getMethod()==Method.HEAD?"":page);
                result.addHeader("Connection","close");result.addHeader("Cache-Control","no-store");return result;
            }
            if(session.getMethod()==Method.OPTIONS)return answer(200,"{}",null);
            JSONObject config=backend.config();
            String mapped=mapPath(path);
            if(mapped.equals("@sdk"))return answer(200,"{\"report_device_info\": 10000}",null);
            if(config!=null) {
                Object early=earlyConfig(mapped,config);
                if(early!=null)return answer(200,JSON.toJSONString(early,SerializerFeature.WriteMapNullValue),null);
            }
            if(!backend.awaitReady(45))return answer(503,"{\"result\":2,\"error\":\"本地服务正在准备，请稍后重试\"}",null);
            String sizeText=session.getHeaders().get("content-length");
            if(sizeText!=null && Long.parseLong(sizeText)>16*1024*1024)return answer(413,"{}",null);
            byte[] bodyBytes=new byte[0];
            if(session.getMethod()==Method.POST || session.getMethod()==Method.PUT || session.getMethod()==Method.PATCH)bodyBytes=RawHttpBody.readBytes(session.getHeaders(),session.getInputStream());
            String body=new String(bodyBytes,StandardCharsets.UTF_8);
            Map<String,String> parameters=new LinkedHashMap<>(session.getParms());
            String contentType=session.getHeaders().getOrDefault("content-type","");
            if(contentType.startsWith("application/x-www-form-urlencoded")&&!body.trim().startsWith("{"))for(String pair:body.split("&")){
                if(pair.isEmpty())continue;int eq=pair.indexOf('=');parameters.put(URLDecoder.decode(eq<0?pair:pair.substring(0,eq),"UTF-8"),URLDecoder.decode(eq<0?"":pair.substring(eq+1),"UTF-8"));
            }
            if(mapped.equals("/u8/user/v1/getToken"))try{
                JSONObject shape=JSON.parseObject(body);Object extension=shape.get("extension");
                android.util.Log.i("ArkLocalRouting","token_request_shape contentType="+contentType+" keys="+shape.keySet()+" extensionType="+(extension==null?"null":extension.getClass().getSimpleName())+(extension instanceof JSONObject?" extensionKeys="+((JSONObject)extension).keySet():""));
            }catch(Exception ignored){}
            Exchange exchange=new Exchange(session,mapped,parameters,body,bodyBytes);
            if(mapped.equals("/account/login")||mapped.equals("/user/auth/v1/token_by_phone_password")
                    ||mapped.equals("/user/oauth2/v2/grant"))backend.beforeLogin();
            boolean traceGame=(mapped.startsWith("/building/")||mapped.startsWith("/charBuild/")||mapped.startsWith("/shop/")||mapped.startsWith("/quest/")||mapped.startsWith("/mission/")||mapped.startsWith("/charRotation/")||mapped.startsWith("/background/")||mapped.startsWith("/homeTheme/")||mapped.startsWith("/tower/")||mapped.startsWith("/rlv2/")||mapped.startsWith("/sandboxPerm/")||mapped.startsWith("/recalRune/")||mapped.startsWith("/retro/")||mapped.startsWith("/campaignV2/")||mapped.startsWith("/crisisV2/")||mapped.startsWith("/social/")||mapped.equals("/user/changeSecretary")||mapped.startsWith("/activity/")||mapped.equals("/social/receiveSocialPoint"))&&(!mapped.equals("/building/sync")||syncTraces.incrementAndGet()<=3);
            if(traceGame)try {
                JSONObject requestShape=JSON.parseObject(body),safe=new JSONObject(true);
                if(requestShape!=null) {
                    for(String key:new String[]{"instId","flag","isHard","tower"}) if(requestShape.containsKey(key)) {
                        Object value=requestShape.get(key); safe.put(key,value);
                        safe.put(key+"Type",value==null?"null":value.getClass().getSimpleName());
                    }
                    if(mapped.equals("/charRotation/updatePreset")&&requestShape.get("data") instanceof JSONObject) {
                        JSONObject data=requestShape.getJSONObject("data"),fields=new JSONObject(true);
                        for(String key:new String[]{"profileInst","profile","profileSp","secretaryCharInstId","secretarySkinId","secretaryShowSpDynIllust"})if(data.containsKey(key)) {
                            Object value=data.get(key);fields.put(key,value);
                            fields.put(key+"Type",value==null?"null":value.getClass().getSimpleName());
                        }
                        safe.put("data",fields);
                    }
                }
                if(requestShape!=null)for(String key:new String[]{"slotId","roomSlotId","roomId","level","targetLevel","charInstId","targetRank","itemId","goodId","groupId","costType","goods","count","stageId","multiple","expMats","activityId","index","poolId","seasonId","topicId","nodeId","difficultyId","modeId","retroId","grade","rewardType","charId","missionId","missionGroupId","targetRewardsId","targetFormulaId","solutionCount","type"})if(requestShape.containsKey(key))safe.put(key,requestShape.get(key));
                if(requestShape!=null&&mapped.startsWith("/sandboxPerm/"))for(String key:new String[]{"nodeId","difficultyId"})if(requestShape.containsKey(key))safe.put(key+"Type",requestShape.get(key)==null?"null":requestShape.get(key).getClass().getSimpleName());
                android.util.Log.i("ArkLocalRouting","game_request "+mapped+" keys="+(requestShape==null?"[]":requestShape.keySet())+" fields="+safe.toJSONString());
            }catch(Exception ignored){}
            for(Route route:routes) {
                if(!route.verb.equals("*")&&!route.verb.equals(session.getMethod().name()))continue;
                Matcher matcher=route.pattern.matcher(mapped);
                if(!matcher.matches())continue;
                Map<String,String> variables=new HashMap<>();
                for(int i=0;i<route.variableNames.size();i++)variables.put(route.variableNames.get(i),matcher.group(i+1));
                Object result;
                synchronized(LocalBackend.DATA_LOCK) {result=route.method.invoke(route.controller,route.arguments(exchange,variables));}
                if(traceGame&&result instanceof JSONObject){JSONObject obj=(JSONObject)result;android.util.Log.i("ArkLocalRouting","game_result "+mapped+" keys="+obj.keySet()+" result="+obj.get("result")+" error="+obj.getString("error"));}
                return exchange.response(result);
            }
            return answer(404,"{\"result\":2,\"error\":\"本地接口未实现\"}",null);
        } catch(Throwable failure) {
            Throwable cause=failure instanceof InvocationTargetException?((InvocationTargetException)failure).getTargetException():failure;
            backend.recordError(path,cause);
            return answer(500,"{\"result\":2,\"error\":\"本地接口处理失败，请查看悬浮球诊断\"}",null);
        }
    }
    private String mapPath(String path) throws Exception {
        if(path.equals("/config/prod/official/network_config"))return "/";
        for(Object item:backend.routes()) {
            JSONObject route=(JSONObject)item;String prefix=route.getString("prefix");
            if(path.equals(prefix)||path.startsWith(prefix+"/")) {
                String suffix=path.substring(prefix.length());String kind=route.getString("kind");
                if(kind.equals("network"))return "/"+suffix.replaceFirst("^/+","");
                if(kind.equals("update"))return "/api/game/get_latest"+suffix;
                return "/config/prod/b/remote_config";
            }
        }
        if(path.matches("/api/remote_config/[0-9]+/prod/default/Android/ak_sdk_config/?"))return "@sdk";
        if(path.matches("/api/remote_config/[0-9]+/prod/default/Android/network_config/?"))return "/api/remote_config/1/prod/default/Windows/network_config";
        if(path.equals("/api/remote_config")||path.matches("/api/remote_config/[0-9]+/prod/default/Android/remote_config/?"))return "/config/prod/b/remote_config";
        return path;
    }
    private Object earlyConfig(String path,JSONObject config) {
        if(path.equals("/config/prod/official/Android/version")||path.equals("/config/prod/b/Android/version"))return backend.androidVersion();
        if(path.equals("/config/prod/announce_meta/Android/announcement.meta.json"))return backend.announcement();
        if(path.equals("/config/prod/announce_meta/Android/preannouncement.meta.json"))return backend.preannouncement();
        if(path.equals("/config/prod/b/remote_config"))return config.getJSONObject("remote");
        if(path.equals("/")||path.equals("/config/prod/b/network_config")) {
            JSONObject network=config.getJSONObject("network");JSONObject result=new JSONObject(true);result.put("sign",network.get("sign"));
            JSONObject content=new JSONObject(true);content.put("configVer",network.get("configVer"));content.put("funcVer",network.get("funcVer"));content.put("configs",network.get("configs"));
            result.put("content",content.toJSONString());result.put("type","A");return result;
        }
        if(path.equals("/api/remote_config/1/prod/default/Windows/network_config")) {
            JSONObject network=config.getJSONObject("network");JSONObject flat=new JSONObject(network.getJSONObject("configs").getJSONObject(network.getString("funcVer")).getJSONObject("network"));
            flat.put("configVer",network.get("configVer"));flat.put("funcVer",network.get("funcVer"));return flat;
        }
        return null;
    }
    private static Response answer(int status,String text,Map<String,String> headers) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        Response response=newFixedLengthResponse(status(status),"application/json; charset=utf-8",new ByteArrayInputStream(bytes),bytes.length);
        response.addHeader("Connection","close");if(headers!=null)headers.forEach(response::addHeader);return response;
    }
    private static Response.IStatus status(int code) {return new Response.IStatus(){public int getRequestStatus(){return code;} public String getDescription(){return code+" Local";}};}
    private static Annotation find(Annotation[] annotations,String name){for(Annotation a:annotations)if(a.annotationType().getSimpleName().equals(name))return a;return null;}
    private static String[] paths(Annotation a)throws Exception {
        if(a==null)return new String[]{""};String[] values=(String[])a.annotationType().getMethod("value").invoke(a);
        if(values.length==0)try{values=(String[])a.annotationType().getMethod("path").invoke(a);}catch(NoSuchMethodException ignored){}
        return values.length==0?new String[]{""}:values;
    }
    private static String join(String root,String child){String value=("/"+root+"/"+child).replaceAll("/+","/");return value.length()>1&&value.endsWith("/")?value.substring(0,value.length()-1):value;}
    private static Object scalar(String s,Class<?> type) {
        if(type==String.class)return s;if(type==Long.class||type==long.class)return s==null?0L:Long.valueOf(s);
        if(type==Integer.class||type==int.class)return s==null?0:Integer.valueOf(s);if(type==Boolean.class||type==boolean.class)return Boolean.valueOf(s);
        if(type==Double.class||type==double.class)return s==null?0d:Double.valueOf(s);return s;
    }
    private static final class Route {
        final Object controller;final java.lang.reflect.Method method;final String verb;final Pattern pattern;final List<String> variableNames=new ArrayList<>();
        Route(Object controller,java.lang.reflect.Method method,String verb,String path) {
            this.controller=controller;this.method=method;this.verb=verb;
            Matcher m=Pattern.compile("\\{([^}]+)\\}").matcher(path);StringBuilder p=new StringBuilder("^");int at=0;
            while(m.find()){p.append(Pattern.quote(path.substring(at,m.start()))).append("([^/]+)");variableNames.add(m.group(1));at=m.end();}
            p.append(Pattern.quote(path.substring(at))).append("/?$");pattern=Pattern.compile(p.toString());
        }
        Object[] arguments(Exchange x,Map<String,String> variables)throws Exception {
            Parameter[] parameters=method.getParameters();Object[] values=new Object[parameters.length];
            for(int i=0;i<parameters.length;i++) {
                Parameter p=parameters[i];Class<?> type=p.getType();Annotation[] annotations=p.getAnnotations();
                if(type==HttpServletRequest.class){values[i]=x.request;continue;}if(type==HttpServletResponse.class){values[i]=x.response;continue;}
                if(find(annotations,"RequestBody")!=null){values[i]=type==byte[].class?x.bodyBytes:(type==String.class?x.body:JSON.parseObject(x.body.isEmpty()?"{}":x.body,type));continue;}
                Annotation header=find(annotations,"RequestHeader"),param=find(annotations,"RequestParam"),path=find(annotations,"PathVariable");
                Annotation binding=header!=null?header:(param!=null?param:path);
                if(Map.class.isAssignableFrom(type)){values[i]=new LinkedHashMap<>(header!=null?x.headers:x.parameters);continue;}
                String name=p.getName();String defaultValue=null;
                if(binding!=null) {
                    String v=(String)binding.annotationType().getMethod("value").invoke(binding);if(!v.isEmpty())name=v;
                    else try{v=(String)binding.annotationType().getMethod("name").invoke(binding);if(!v.isEmpty())name=v;}catch(NoSuchMethodException ignored){}
                    try{defaultValue=(String)binding.annotationType().getMethod("defaultValue").invoke(binding);if(defaultValue.contains("\n"))defaultValue=null;}catch(NoSuchMethodException ignored){}
                }
                String text=header!=null?x.headers.get(name.toLowerCase(Locale.ROOT)):(path!=null?variables.get(name):x.parameters.get(name));
                values[i]=scalar(text==null?defaultValue:text,type);
            }
            return values;
        }
    }
    private static final class Exchange implements InvocationHandler {
        final IHTTPSession session;final String path,body;final byte[] bodyBytes;final Map<String,String> parameters,headers=new TreeMap<>(String.CASE_INSENSITIVE_ORDER),outHeaders=new LinkedHashMap<>();
        final Map<String,Object> attributes=new HashMap<>();final ByteArrayOutputStream output=new ByteArrayOutputStream();final PrintWriter writer;
        final HttpServletRequest request;final HttpServletResponse response;int status=200;String mime="application/json; charset=utf-8";
        Exchange(IHTTPSession session,String path,Map<String,String> parameters,String body,byte[] bodyBytes) {
            this.session=session;this.path=path;this.parameters=parameters;this.body=body;this.bodyBytes=bodyBytes;headers.putAll(session.getHeaders());headers.put("X-Platform","Android");
            writer=new PrintWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));
            request=(HttpServletRequest)Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),new Class[]{HttpServletRequest.class},this);
            response=(HttpServletResponse)Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),new Class[]{HttpServletResponse.class},this);
        }
        @Override public Object invoke(Object proxy,java.lang.reflect.Method method,Object[] args)throws Throwable {
            String name=method.getName();
            switch(name) {
                case "getHeader":return headers.get((String)args[0]);case "getHeaders":return Collections.enumeration(Collections.singletonList(headers.get((String)args[0])));
                case "getHeaderNames":return Collections.enumeration(headers.keySet());case "getRemoteAddr":case "getLocalAddr":return "127.0.0.1";
                case "getRemoteHost":case "getServerName":return "localhost";case "getRequestURI":case "getServletPath":return path;
                case "getRequestURL":return new StringBuffer("http://127.0.0.1:10029"+path);case "getMethod":return session.getMethod().name();
                case "getQueryString":return session.getQueryParameterString();case "getParameter":return parameters.get((String)args[0]);
                case "getParameterMap":Map<String,String[]> result=new HashMap<>();parameters.forEach((k,v)->result.put(k,new String[]{v}));return result;
                case "getParameterNames":return Collections.enumeration(parameters.keySet());case "getAttribute":return attributes.get((String)args[0]);
                case "setAttribute":attributes.put((String)args[0],args[1]);return null;case "removeAttribute":attributes.remove((String)args[0]);return null;
                case "getReader":return new BufferedReader(new StringReader(body));case "getCharacterEncoding":return "UTF-8";
                case "setStatus":status=((Number)args[0]).intValue();return null;case "getStatus":return status;
                case "setHeader":case "addHeader":if(args[1]!=null)outHeaders.put((String)args[0],String.valueOf(args[1]));return null;
                case "setContentType":mime=(String)args[0];return null;case "getContentType":return mime;case "getWriter":return writer;
                case "getOutputStream":return new ServletOutputStream(){@Override public void write(int b){output.write(b);}};
                case "sendError":status=((Number)args[0]).intValue();return null;case "sendRedirect":status=302;outHeaders.put("Location",(String)args[0]);return null;
                case "toString":return "LocalExchange";case "hashCode":return System.identityHashCode(proxy);case "equals":return proxy==args[0];
                default:Class<?> type=method.getReturnType();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;
            }
        }
        Response response(Object returned)throws IOException {
            writer.flush();byte[] payload=output.toByteArray();
            if(payload.length==0&&returned!=null) {
                String text=returned instanceof String?(String)returned:JSON.toJSONString(returned,SerializerFeature.WriteMapNullValue);
                if(returned instanceof String&&text.trim().startsWith("<"))mime="text/html; charset=utf-8";
                payload=text.getBytes(StandardCharsets.UTF_8);
            }
            Response response=newFixedLengthResponse(status(status),mime,new ByteArrayInputStream(payload),payload.length);response.addHeader("Connection","close");outHeaders.forEach(response::addHeader);return response;
        }
    }
}
