package local.starccm.assistant;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;

/** Offline catalog tests, with optional actual installed-Javadoc and STAR-runtime checks.
 * Run with no STAR jars to prove that absent runtime classes are not invented.
 * Pass actual doc/client/html as argument 0; argument 1 "runtime" also requires STAR jars.
 */
public final class ApiDocumentation060Test {
    private static int checks;
    private static void check(boolean condition,String message) { checks++; if(!condition)throw new AssertionError(message); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> result) { return (List<Map<String,Object>>)result.get("items"); }
    @SuppressWarnings("unchecked") private static Map<String,Object> metadata(Map<String,Object> result) { return (Map<String,Object>)result.get("documentation"); }
    private static void write(Path path,String text)throws IOException { Files.createDirectories(path.getParent());Files.writeString(path,text,StandardCharsets.UTF_8); }

    public static void main(String[] args)throws Exception {
        String previous=System.getProperty("starccm.assistant.apiDocRoot"),home=System.getProperty("starccm.assistant.starHome");
        Path temporary=Files.createTempDirectory("api-documentation060-");
        try {
            Path docs=temporary.resolve("安装含空格/doc/client/html");
            write(docs.resolve("type-search-index.js"),"typeSearchIndex = [{\"p\":\"star.fake\",\"l\":\"Alpha\"},{\"p\":\"star.fake\",\"l\":\"Beta.Inner\"},{\"p\":\"javax.fake\",\"l\":\"NotStar\"}];updateSearchResults();");
            write(docs.resolve("package-search-index.js"),"packageSearchIndex = [{\"l\":\"All Packages\"},{\"l\":\"star.fake\"}];");
            StringBuilder member=new StringBuilder("memberSearchIndex = [");
            for(int i=0;i<95;i++)member.append(i==0?"":",").append("{\"p\":\"star.fake\",\"c\":\"Alpha\",\"l\":\"getAlpha").append(i).append("()\"}");
            member.append(",{\"p\":\"star.fake\",\"c\":\"Alpha\",\"l\":\"set\\u0041lpha(double)\",\"u\":\"setAlpha(double)\"}];");
            write(docs.resolve("member-search-index.js"),member.toString());
            write(docs.resolve("star/fake/Alpha.html"),"<html>fixture</html>");
            System.setProperty("starccm.assistant.apiDocRoot",docs.toString());

            Map<String,Object> first=ApiDocumentation.search("Alpha",0,2),second=ApiDocumentation.search("Alpha",2,2);
            check(first.get("totalMatches").equals(97),"exact total including class plus 96 members");
            check(items(first).size()==2&&items(first).get(0).get("kind").equals("type"),"exact type ranked first");
            HashSet<String> seen=new HashSet<>();for(Map<String,Object> item:items(first))seen.add(item.toString());
            for(Map<String,Object> item:items(second))check(!seen.contains(item.toString()),"stable non-overlapping pagination");
            check(first.get("nextOffset").equals(2)&&Boolean.TRUE.equals(first.get("hasMore")),"pagination metadata");
            check(Boolean.FALSE.equals(first.get("runtimeVerified")),"documentation search does not claim runtime verification");
            check(metadata(first).get("root").equals(docs.toString()),"Unicode and spaces preserved in detected path");
            Map<String,Object> escaped=ApiDocumentation.search("setAlpha",0,20);
            check(items(escaped).size()==1&&items(escaped).get(0).get("label").equals("setAlpha(double)"),"JSON unicode decoded");
            check(items(ApiDocumentation.search("Alpha setAlpha",0,20)).size()==1,"multi-keyword cross owner/member search");
            check(items(ApiDocumentation.search("NotStar",0,20)).isEmpty(),"non STAR packages excluded");
            check(ApiDocumentation.search("",0,20).get("error").equals("query_required"),"empty query cannot dump index");
            Map<String,Object> bounds=ApiDocumentation.search("Alpha",-100,9999);
            check(bounds.get("limit").equals(40)&&bounds.get("offset").equals(0)&&items(bounds).size()==40,"bounded result page");
            check(ApiDocumentation.search("Alpha",500000,20).get("offset").equals(2000),"bounded offsets");

            Map<String,Object> fallback=ApiDocumentation.classInfo("star.fake.Alpha","setAlpha",0,20);
            check(Boolean.FALSE.equals(fallback.get("runtimeAvailable"))&&Boolean.FALSE.equals(fallback.get("runtimeVerified")),"missing runtime correctly disclosed");
            check(items(fallback).size()==1&&items(fallback).get(0).get("signatureOrigin").equals("javadoc-search-index-label-only"),"document labels explicitly unverified fallback");
            check(fallback.get("document").equals(docs.resolve("star/fake/Alpha.html").toString()),"class doc resolves to local file");
            for(String invalid:List.of("java.lang.Runtime","star.fake/../../secret","star.fake.Alpha;System.exit(0)","", "star.fake.Alpha()"))
                check(ApiDocumentation.classInfo(invalid,"",0,20).get("error").equals("invalid_class_name"),"invalid class blocked: "+invalid);

            System.setProperty("starccm.assistant.apiDocRoot",temporary.resolve("does-not-exist").toString());
            Map<String,Object> missing=ApiDocumentation.search("Simulation",0,20);
            check(missing.get("error").equals("documentation_unavailable"),"explicit missing docs reported");
            check(metadata(missing).get("detectedBy").equals("explicit_apiDocRoot"),"explicit mismatch cannot silently use another version");
            System.clearProperty("starccm.assistant.apiDocRoot");
            System.setProperty("starccm.assistant.starHome",docs.getParent().getParent().getParent().toString());
            // Running STAR, if supplied on the test classpath, must take precedence over configured other installations.
            if(args.length<2)check(metadata(ApiDocumentation.search("Alpha",0,20)).get("root").equals(docs.toString()),"installation property discovery portable");
            System.setProperty("starccm.assistant.apiDocRoot",docs.toString());

            write(docs.resolve("member-search-index.js"),"memberSearchIndex=[{\"p\":\"star.fake\",\"c\":\"Alpha\",\"l\":42}];");
            Map<String,Object> malformed=ApiDocumentation.search("Alpha",0,20);
            check(Boolean.FALSE.equals(malformed.get("complete"))&&!((List<?>)malformed.get("warnings")).isEmpty(),"malformed index partial status reported");

            noInitialization(temporary);
            if(args.length>0)installed(Path.of(args[0]),args.length>1&&args[1].equals("runtime"));
            System.out.println("PASS ApiDocumentation060Test "+checks+" checks");
        } finally {
            restore("starccm.assistant.apiDocRoot",previous);restore("starccm.assistant.starHome",home);
            try(var paths=Files.walk(temporary)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }

    @SuppressWarnings("unchecked") private static void noInitialization(Path temporary)throws Exception {
        Path source=temporary.resolve("src/star/documentationfixture/Probe.java"),classes=temporary.resolve("classes");
        write(source,"package star.documentationfixture; public class Probe {static {System.setProperty(\"api060.initialized\",\"YES\");} public static int field=7; public Probe(){} public double sample(double[] values, Nested kind) throws java.io.IOException{return 0;} public int sample(int value){return value;} public static class Nested{} }");
        Files.createDirectories(classes);
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-encoding","UTF-8","-d",classes.toString(),source.toString())==0,"fixture compiles");
        Path docs=Path.of(System.getProperty("starccm.assistant.apiDocRoot"));
        write(docs.resolve("star/documentationfixture/Probe.html"),"<section class=\"class-description\" id=\"class-description\"><div class=\"block\">Probe &amp; coordinate helper.</div></section>"
                +"<section class=\"detail\" id=\"sample(double[],star.documentationfixture.Probe.Nested)\"><h3>sample</h3><div class=\"block\">Reads values &lt; 1; never mutates.</div><script>do not include</script></section>");
        URL implementation=ApiDocumentation.class.getProtectionDomain().getCodeSource().getLocation();
        try(URLClassLoader loader=new URLClassLoader(new URL[]{classes.toUri().toURL(),implementation},ClassLoader.getPlatformClassLoader())){
            Class<?> isolated=Class.forName("local.starccm.assistant.ApiDocumentation",true,loader);
            Method info=isolated.getMethod("classInfo",String.class,String.class,int.class,int.class);
            Map<String,Object> data=(Map<String,Object>)info.invoke(null,"star.documentationfixture.Probe","sample",0,20);
            check(Boolean.TRUE.equals(data.get("runtimeAvailable"))&&Boolean.TRUE.equals(data.get("runtimeVerified")),"actual runtime reflection verified");
            check(items(data).size()==2,"overloads retained separately");
            check(items(data).stream().anyMatch(row->row.get("signature").equals("double sample(double[],star.documentationfixture.Probe.Nested)")),"canonical arrays and nested parameter signature");
            check(items(data).stream().allMatch(row->row.get("declaringClass").equals("star.documentationfixture.Probe")),"declaring class included");
            check(data.get("classDocumentation").equals("Probe & coordinate helper."),"bounded class semantics decoded");
            check(items(data).stream().anyMatch(row->String.valueOf(row.get("documentationText")).contains("Reads values < 1; never mutates.")&&!String.valueOf(row.get("documentationText")).contains("do not include")),"exact overload documentation, HTML removed");
            Map<String,Object> nested=(Map<String,Object>)info.invoke(null,"star.documentationfixture.Probe.Nested","",0,20);
            check(Boolean.TRUE.equals(nested.get("runtimeAvailable")),"canonical nested class resolves to JVM binary name");
            Map<String,Object> field=(Map<String,Object>)info.invoke(null,"star.documentationfixture.Probe","field",0,20);
            check(items(field).size()==1&&items(field).get(0).get("kind").equals("field"),"public field signature only");
            check(System.getProperty("api060.initialized")==null,"signature discovery never initialized fixture class or read static field");
        }
    }

    private static void installed(Path docs,boolean runtime) {
        System.setProperty("starccm.assistant.apiDocRoot",docs.toString());
        long start=System.nanoTime();Map<String,Object> search=ApiDocumentation.search("getRegionManager",0,20);
        check(Boolean.TRUE.equals(search.get("complete")),"actual installed indexes parse fully: "+search);
        check(items(search).stream().anyMatch(row->row.get("className").equals("star.common.Simulation")&&row.get("label").equals("getRegionManager()")),"actual member index locates Simulation getter");
        Map<String,Object> details=ApiDocumentation.classInfo("star.common.Simulation","getRegionManager",0,20);
        check(String.valueOf(details.get("classDocumentation")).contains("Server simulation proxy."),"actual class documentation included as bounded text");
        if(runtime){
            check(Boolean.TRUE.equals(details.get("runtimeVerified")),"real STAR class runtime verification");
            check(items(details).stream().anyMatch(row->row.get("signature").equals("star.common.RegionManager getRegionManager()")),"real STAR getter signature verified");
            check(items(details).stream().anyMatch(row->String.valueOf(row.get("documentationText")).contains("getRegionManager")),"actual method documentation section included");
            check(Boolean.TRUE.equals(metadata(details).get("matchesRunningInstallation")),"docs match actual running STAR installation");
        }else check(Boolean.FALSE.equals(details.get("runtimeVerified")),"offline Javadoc never claims runtime verified");
        System.out.printf("INSTALLED lookup %.3fs; hits=%s; runtime=%s%n",(System.nanoTime()-start)/1e9,search.get("totalMatches"),details.get("runtimeVerified"));
    }
    private static void restore(String key,String value){if(value==null)System.clearProperty(key);else System.setProperty(key,value);}
}
