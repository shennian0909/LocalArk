package com.codex.arklocal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Preserve JSON wire bytes even when a native client labels them as form data. */
public final class RawHttpBody {
    private static final int MAX=16*1024*1024;
    private RawHttpBody(){}
    public static String read(Map<String,String> headers,InputStream input)throws IOException {
        return new String(readBytes(headers, input), StandardCharsets.UTF_8);
    }
    public static byte[] readBytes(Map<String,String> headers,InputStream input)throws IOException {
        String length=headers.get("content-length"),transfer=headers.get("transfer-encoding");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        if(transfer!=null&&!transfer.trim().isEmpty()) {
            if(length!=null||!transfer.trim().equalsIgnoreCase("chunked"))throw new IOException("Unsupported request framing");
            int total=0;
            while(true) {
                String line=line(input);int semicolon=line.indexOf(';');String value=(semicolon<0?line:line.substring(0,semicolon)).trim();
                long count;try{if(!value.matches("[0-9a-fA-F]{1,8}"))throw new NumberFormatException();count=Long.parseLong(value,16);}catch(NumberFormatException e){throw new IOException("Invalid chunk size");}
                if(count>MAX-total)throw new IOException("Request body exceeded limit");
                if(count==0){int trailers=0;while(!line(input).isEmpty())if(++trailers>64)throw new IOException("Too many trailers");break;}
                copy(input,output,(int)count);total+=(int)count;
                if(input.read()!=13||input.read()!=10)throw new IOException("Invalid chunk delimiter");
            }
        } else if(length!=null) {
            long count;try{count=Long.parseLong(length);}catch(NumberFormatException e){throw new IOException("Invalid request length");}
            if(count<0||count>MAX)throw new IOException("Request body exceeded limit");
            copy(input,output,(int)count);
        }
        return output.toByteArray();
    }
    private static void copy(InputStream input,OutputStream output,int count)throws IOException {
        byte[] buffer=new byte[Math.min(16384,Math.max(count,1))];
        while(count>0){int n=input.read(buffer,0,Math.min(count,buffer.length));if(n<0)throw new EOFException("Incomplete request body");if(n==0)continue;output.write(buffer,0,n);count-=n;}
    }
    private static String line(InputStream input)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
        while((value=input.read())!=-1){if(value==13){if(input.read()!=10)throw new IOException("Invalid header delimiter");return bytes.toString("US-ASCII");}if(value==10||bytes.size()>=4096)throw new IOException("Invalid framing line");bytes.write(value);}
        throw new EOFException("Incomplete framing line");
    }
}
