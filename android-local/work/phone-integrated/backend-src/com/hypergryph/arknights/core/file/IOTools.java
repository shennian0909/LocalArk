package com.hypergryph.arknights.core.file;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONReader;
import com.alibaba.fastjson.parser.Feature;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.codex.arklocal.BackendPaths;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Stream metadata from the private application directory without full-file copies. */
public class IOTools {
    public IOTools(){}
    private static File resolveFile(String path){File file=new File(path);return file.isAbsolute()?file:new File(BackendPaths.root(),path);}
    public static String ReadNormalFile(String path){
        try(Reader reader=new InputStreamReader(new FileInputStream(resolveFile(path)),StandardCharsets.UTF_8)){
            StringBuilder out=new StringBuilder();char[] block=new char[8192];int n;while((n=reader.read(block))>=0)out.append(block,0,n);return out.toString();
        }catch(IOException e){return null;}
    }
    public static JSONObject ReadJsonFile(String path){
        try(JSONReader reader=new JSONReader(new InputStreamReader(new FileInputStream(resolveFile(path)),StandardCharsets.UTF_8),Feature.OrderedField)){
            return reader.readObject(JSONObject.class);
        }catch(IOException e){return null;}
    }
    public static Boolean SaveJsonFile(String path,JSONObject data){
        try(Writer writer=new OutputStreamWriter(new FileOutputStream(resolveFile(path)),StandardCharsets.UTF_8)){
            JSON.writeJSONString(writer,data,SerializerFeature.PrettyFormat,SerializerFeature.WriteMapNullValue);writer.flush();return true;
        }catch(IOException e){return false;}
    }
    public static JSONArray ReadJsonArray(String path){
        try(JSONReader reader=new JSONReader(new InputStreamReader(new FileInputStream(resolveFile(path)),StandardCharsets.UTF_8),Feature.OrderedField)){
            return reader.readObject(JSONArray.class);
        }catch(IOException|JSONException e){return new JSONArray();}
    }
}
