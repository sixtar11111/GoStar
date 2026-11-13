package local.starccm.assistant;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.function.Consumer;

/** Local attack fixtures only: no external provider, user credential or production SIM. */
public final class Security062Regression {
    static final String KEY="GoStar062SyntheticCredential";
    static final List<Map<String,Object>> checks=new ArrayList<>();
    static Object call(CodexAssistant core,String name,Class<?>[] types,Object...args)throws Exception{
        Method m=CodexAssistant.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(core,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw (Exception)cause;throw (Error)cause;}
    }
    static void check(String name,boolean passed){checks.add(Map.of("name",name,"passed",passed));}
    static final class CountingStream extends InputStream {
        int read;final int count;CountingStream(int count){this.count=count;}
        public int read(){return read++>=count?-1:read==1?'d':read==2?'a':read==3?'t':read==4?'a':read==5?':':'x';}
        public int read(byte[] b,int offset,int length){if(read>=count)return -1;int n=Math.min(length,count-read);for(int i=0;i<n;i++)b[offset+i]=(byte)(read++<5?"data:".charAt(read-1):'x');return n;}
    }
    static void helperChecks()throws Exception{
        String old=System.getProperty("user.dir");Path work=Files.createTempDirectory("gostar-untrusted-cwd-");Path script=work.resolve("scripts/starccm_skill_bridge.py");Files.createDirectories(script.getParent());Files.writeString(script,"# Harmless marker: this file must never be auto-selected.\n");
        CodexAssistant core=new CodexAssistant();System.setProperty("user.dir",work.toString());
        try{
            boolean blocked=false;try{call(core,"findSkillBridge",new Class<?>[0]);}catch(IOException expected){blocked=true;}
            check("unconfigured working directory cannot supply executable help",blocked);
            Field selected=CodexAssistant.class.getDeclaredField("assistantHomeOverride");selected.setAccessible(true);selected.set(core,work.toString());
            check("explicitly selected library still resolves",script.equals(call(core,"findSkillBridge",new Class<?>[0])));
        }finally{System.setProperty("user.dir",old);Files.deleteIfExists(script);Files.deleteIfExists(script.getParent());Files.deleteIfExists(work);}
        Path runtime=(Path)call(null,"runtimeDirectory",new Class<?>[0]);boolean privateMode=true;
        if(Files.getFileStore(runtime).supportsFileAttributeView("posix")){
            Set<PosixFilePermission> mode=Files.getPosixFilePermissions(runtime);privateMode=mode.stream().noneMatch(p->p.name().startsWith("GROUP_")||p.name().startsWith("OTHERS_"));
        }
        check("CLI temporary directory is process-private",privateMode&&!runtime.getFileName().toString().equals("gostar"));
    }
    static void streamChecks()throws Exception{
        CountingStream source=new CountingStream(2_000_000);String error="";
        try{call(null,"readApiStream",new Class<?>[]{InputStream.class,Consumer.class,Consumer.class},source,null,null);}catch(IOException expected){error=expected.getMessage();}
        check("SSE giant line rejected before whole allocation",source.read<220_000&&error.contains("过大"));
        StringBuilder visible=new StringBuilder();String[] segments={"result "+KEY.substring(0,8),KEY.substring(8,17),KEY.substring(17)+" done"};StringBuilder events=new StringBuilder();
        for(String part:segments)events.append("data: ").append(ConfigurationEngine.json(Map.of("choices",List.of(Map.of("delta",Map.of("content",part)))))).append("\n\n");
        events.append("data: [DONE]\n\n");
        // Actual transport redaction is exercised below, not this static parser fixture.
        String parsed=(String)call(null,"readApiStream",new Class<?>[]{InputStream.class,Consumer.class,Consumer.class},new ByteArrayInputStream(events.toString().getBytes(StandardCharsets.UTF_8)),(Consumer<String>)visible::append,null);
        check("normal split SSE remains parseable",parsed.equals("result "+KEY+" done")&&visible.toString().equals(parsed));
    }
    static void transportChecks()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{try{
            String kind=e.getRequestURI().getPath();String response;int code=200;
            if(kind.contains("json-error"))response=ConfigurationEngine.json(Map.of("error",Map.of("message","bad credential "+KEY)));
            else if(kind.contains("http-error")){code=401;response="bad credential "+KEY;}
            else if(kind.contains("stream-error")){e.getResponseHeaders().set("Content-Type","text/event-stream");response="data: "+ConfigurationEngine.json(Map.of("error",Map.of("message","bad credential "+KEY)))+"\n\n";}
            else if(kind.contains("stream-success")){
                e.getResponseHeaders().set("Content-Type","text/event-stream");StringBuilder b=new StringBuilder();for(String chunk:List.of("result "+KEY.substring(0,8),KEY.substring(8,17),KEY.substring(17)+" done"))b.append("data: ").append(ConfigurationEngine.json(Map.of("choices",List.of(Map.of("delta",Map.of("content",chunk)))))).append("\n\n");response=b+"data: [DONE]\n\n";
            }else response=ConfigurationEngine.json(Map.of("choices",List.of(Map.of("message",Map.of("content","result "+KEY+" done")))));
            if(e.getResponseHeaders().getFirst("Content-Type")==null)e.getResponseHeaders().set("Content-Type","application/json");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(code,bytes.length);e.getResponseBody().write(bytes);
        }finally{e.close();}});server.start();
        try{
            CodexAssistant core=new CodexAssistant();
            for(String kind:List.of("json-error","stream-error","http-error","json-success","stream-success")){
                String url="http://127.0.0.1:"+server.getAddress().getPort()+"/"+kind;StringBuilder visible=new StringBuilder();String returned="",error="";
                try{returned=(String)call(core,"runApi",new Class<?>[]{String.class,String.class,String.class,String.class,int.class,boolean.class,Consumer.class,Consumer.class},url,"fixture-model",KEY,"fixture",3,kind.startsWith("stream"),(Consumer<String>)visible::append,null);}catch(Exception expected){for(Throwable t=expected;t!=null;t=t.getCause())error+=t.toString();}
                check(kind+" credential never escapes transport",!returned.contains(KEY)&&!error.contains(KEY)&&!visible.toString().contains(KEY));
                if(kind.endsWith("success"))check(kind+" ordinary answer remains visible",returned.contains("result")&&returned.contains("done"));
            }
        }finally{server.stop(0);}
    }
    public static void main(String[] args)throws Exception{
        helperChecks();streamChecks();transportChecks();Path out=Path.of(args[0]);Files.createDirectories(out);boolean passed=checks.stream().allMatch(c->Boolean.TRUE.equals(c.get("passed")));Files.writeString(out.resolve("result.json"),ConfigurationEngine.json(Map.of("passed",passed,"checks",checks,"external_api_calls",0,"sim_writes",0)));
        for(Map<String,Object> c:checks)System.out.println((Boolean.TRUE.equals(c.get("passed"))?"PASS ":"FAIL ")+c.get("name"));
        if(!passed&&(args.length<2||!args[1].equals("baseline")))throw new AssertionError("Security regression failed");
        System.out.println((passed?"PASS":"BASELINE REPRODUCED")+" Security062Regression checks="+checks.size());
    }
}
