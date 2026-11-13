package local.starccm.assistant;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local installed Javadoc discovery and non-initializing runtime signature inspection.
 * Documentation discovery grants no execution authority; NativeApiBridge owns that policy.
 * No network, subprocess, Java compilation, object getters or static field reads occur here.
 */
public final class ApiDocumentation {
    private ApiDocumentation() {}
    private static final int MAX_LIMIT = 40, MAX_OFFSET = 2000;
    private static final long MAX_INDEX_BYTES = 64L * 1024 * 1024;
    private static final long MAX_CLASS_DOC_BYTES = 8L * 1024 * 1024;
    private static final Pattern CLASS_NAME = Pattern.compile("star(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    private static final Comparator<Map<String,Object>> ORDER = Comparator
            .<Map<String,Object>>comparingInt(row -> -((Number)row.get("score")).intValue())
            .thenComparing(row -> String.valueOf(row.get("className")))
            .thenComparing(row -> String.valueOf(row.get("label")))
            .thenComparing(row -> String.valueOf(row.get("kind")));

    /** Bounded keyword search across installed STAR package, type and member indexes. */
    public static Map<String,Object> search(String query, int offset, int limit) {
        String text = cleanQuery(query);
        Map<String,Object> result = base("api_search", offset, limit);
        result.put("query", text);
        if (text.isEmpty()) return error(result, "query_required", "请输入类名、方法名或 API 关键词；不会返回完整索引。");
        Documentation source = locate();
        addSource(result, source);
        if (source.root == null) return error(result, "documentation_unavailable", source.reason);
        int start = pageOffset(offset), count = pageLimit(limit), retained = start + count;
        PriorityQueue<Map<String,Object>> top = new PriorityQueue<>(retained + 1, ORDER.reversed());
        int[] matches = {0};
        List<String> warnings = new ArrayList<>();
        String[] tokens = text.toLowerCase(Locale.ROOT).split("\\s+");
        for (String kind : List.of("package", "type", "member")) {
            Path index = source.root.resolve(kind + "-search-index.js");
            if (!Files.isRegularFile(index)) { warnings.add("缺少 " + index.getFileName()); continue; }
            try {
                scan(index, row -> {
                    Map<String,Object> entry = documentedEntry(kind, row, source.root);
                    if (entry == null) return;
                    int score = score(text, tokens, entry);
                    if (score == 0) return;
                    entry.put("score", score); matches[0]++;
                    if (top.size() < retained) top.add(entry);
                    else if (ORDER.compare(entry, top.peek()) < 0) { top.poll(); top.add(entry); }
                });
            } catch (IOException failure) { warnings.add(index.getFileName() + ": " + brief(failure)); }
        }
        List<Map<String,Object>> sorted = new ArrayList<>(top); sorted.sort(ORDER);
        List<Map<String,Object>> page = start >= sorted.size() ? List.of()
                : new ArrayList<>(sorted.subList(start, Math.min(sorted.size(), start + count)));
        for (Map<String,Object> entry : page) entry.remove("score");
        result.put("items", page); result.put("totalMatches", matches[0]);
        result.put("hasMore", start + page.size() < matches[0]);
        result.put("nextOffset", start + page.size() < matches[0] && start + count <= MAX_OFFSET ? start + count : null);
        result.put("warnings", warnings); result.put("complete", warnings.isEmpty());
        result.put("runtimeVerified", false);
        result.put("note", "检索结果仅证明本地文档存在。使用 api_class 核验当前运行时签名；可调用权限另由 API 执行器判断。");
        return result;
    }

    /** Public installed runtime signatures, including inherited public members, paginated.
     * Class loading always uses initialize=false. Missing runtime classes fall back to explicitly
     * unverified Javadoc member labels, never to guessed executable signatures.
     */
    public static Map<String,Object> classInfo(String className, String query, int offset, int limit) {
        Map<String,Object> result = base("api_class", offset, limit);
        String name = className == null ? "" : className.trim();
        String text = cleanQuery(query);
        result.put("className", name); result.put("query", text);
        if (!CLASS_NAME.matcher(name).matches()) return error(result, "invalid_class_name", "只接受完整 star.* Java 类型名，不能包含路径或代码。");
        Documentation source = locate(); addSource(result, source);
        Path docFile = source.root == null ? null : classDocument(source.root, name);
        result.put("document", docFile != null && Files.isRegularFile(docFile) ? docFile.toString() : null);
        Class<?> runtime;
        try { runtime = loadClass(name); }
        catch (ClassNotFoundException | LinkageError | SecurityException failure) {
            result.put("runtimeAvailable", false); result.put("runtimeVerified", false);
            result.put("runtimeError", brief(failure));
            documentedFallback(result, source, name, text, offset, limit);
            addDocumentationText(result, source, name);
            result.put("note", "当前插件类加载器无法核验此类型；文档标签不是可执行签名，不能据此推断 setter 或启用调用。");
            return result;
        }
        result.put("runtimeAvailable", true); result.put("runtimeVerified", true);
        result.put("runtimeClassName", runtime.getName());
        result.put("canonicalName", typeName(runtime)); result.put("runtimeSource", classSource(runtime));
        result.put("kind", runtime.isEnum() ? "enum" : runtime.isInterface() ? "interface" : "class");
        result.put("modifiers", Modifier.toString(runtime.getModifiers()));
        result.put("superclass", runtime.getSuperclass() == null ? null : typeName(runtime.getSuperclass()));
        result.put("interfaces", Arrays.stream(runtime.getInterfaces()).map(ApiDocumentation::typeName).sorted().toList());
        List<Map<String,Object>> members = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            for (Method method : runtime.getMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;
                Map<String,Object> row = runtimeEntry("method", method.getName(), method.getDeclaringClass(), method.getModifiers());
                List<String> parameters = Arrays.stream(method.getParameterTypes()).map(ApiDocumentation::typeName).toList();
                row.put("parameters", parameters); row.put("returnType", typeName(method.getReturnType()));
                row.put("varArgs", method.isVarArgs());
                row.put("signature", typeName(method.getReturnType()) + " " + method.getName() + "(" + String.join(",", parameters) + ")");
                row.put("throws", Arrays.stream(method.getExceptionTypes()).map(ApiDocumentation::typeName).toList());
                members.add(row);
            }
        } catch (LinkageError | SecurityException failure) { warnings.add("公开方法枚举不完整: " + brief(failure)); }
        try {
            for (Constructor<?> constructor : runtime.getConstructors()) {
                if (constructor.isSynthetic()) continue;
                Map<String,Object> row = runtimeEntry("constructor", runtime.getSimpleName(), runtime, constructor.getModifiers());
                List<String> parameters = Arrays.stream(constructor.getParameterTypes()).map(ApiDocumentation::typeName).toList();
                row.put("parameters", parameters); row.put("varArgs", constructor.isVarArgs());
                row.put("signature", typeName(runtime) + "(" + String.join(",", parameters) + ")"); members.add(row);
            }
        } catch (LinkageError | SecurityException failure) { warnings.add("公开构造方法枚举不完整: " + brief(failure)); }
        try {
            for (Field field : runtime.getFields()) {
                if (field.isSynthetic()) continue;
                Map<String,Object> row = runtimeEntry("field", field.getName(), field.getDeclaringClass(), field.getModifiers());
                row.put("type", typeName(field.getType())); row.put("signature", typeName(field.getType()) + " " + field.getName());
                members.add(row); // Deliberately do not call Field.get: even constants can initialize a class.
            }
        } catch (LinkageError | SecurityException failure) { warnings.add("公开字段枚举不完整: " + brief(failure)); }
        String[] tokens = text.toLowerCase(Locale.ROOT).split("\\s+");
        members.removeIf(row -> !text.isEmpty() && !allTokens((row.get("signature") + " " + row.get("declaringClass")).toLowerCase(Locale.ROOT), tokens));
        members.sort(Comparator.<Map<String,Object>,String>comparing(row -> String.valueOf(row.get("name")))
                .thenComparing(row -> String.valueOf(row.get("signature"))).thenComparing(row -> String.valueOf(row.get("declaringClass"))));
        paginate(result, members, offset, limit);
        addDocumentationText(result, source, typeName(runtime));
        result.put("warnings", warnings); result.put("complete", warnings.isEmpty());
        result.put("executionEligibility", "由 NativeApiBridge 按实际对象、参数和操作策略另行核验；公开方法不等于允许执行。");
        return result;
    }

    private static Map<String,Object> runtimeEntry(String kind, String name, Class<?> owner, int modifiers) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("kind", kind); row.put("name", name); row.put("declaringClass", typeName(owner));
        row.put("modifiers", Modifier.toString(modifiers)); row.put("static", Modifier.isStatic(modifiers));
        row.put("runtimeVerified", true); return row;
    }

    private static void documentedFallback(Map<String,Object> result, Documentation source, String name, String text, int offset, int limit) {
        if (source.root == null || !Files.isRegularFile(source.root.resolve("member-search-index.js"))) {
            result.put("items", List.of()); result.put("totalMatches", 0); result.put("hasMore", false);
            result.put("complete", false); result.put("documentationError", source.reason); return;
        }
        List<Map<String,Object>> members = new ArrayList<>();
        String canonical = name.replace('$', '.'); String[] tokens = text.toLowerCase(Locale.ROOT).split("\\s+");
        int[] matches = {0}; int retained = pageOffset(offset) + pageLimit(limit);
        try {
            scan(source.root.resolve("member-search-index.js"), entry -> {
                String owner = entry.getOrDefault("p", "") + "." + entry.getOrDefault("c", "");
                if (!owner.equals(canonical) || (!text.isEmpty() && !allTokens(entry.getOrDefault("l", "").toLowerCase(Locale.ROOT), tokens))) return;
                matches[0]++;
                if (members.size() < retained) {
                    Map<String,Object> row = documentedEntry("member", entry, source.root);
                    row.put("runtimeVerified", false); row.put("signatureOrigin", "javadoc-search-index-label-only"); members.add(row);
                }
            });
            int start = pageOffset(offset);
            result.put("items", start >= members.size() ? List.of() : new ArrayList<>(members.subList(start, members.size())));
            result.put("totalMatches", matches[0]); result.put("hasMore", start + pageLimit(limit) < matches[0]);
            result.put("nextOffset", start + pageLimit(limit) < matches[0] && start + pageLimit(limit) <= MAX_OFFSET ? start + pageLimit(limit) : null);
            result.put("complete", true);
        } catch (IOException failure) {
            result.put("items", List.of()); result.put("complete", false); result.put("documentationError", brief(failure));
        }
    }

    private static Map<String,Object> documentedEntry(String kind, Map<String,String> entry, Path root) {
        String label = entry.getOrDefault("l", ""), pkg = kind.equals("package") ? label : entry.getOrDefault("p", "");
        if (!pkg.startsWith("star.") && !pkg.equals("star")) return null;
        String owner = kind.equals("type") ? label : entry.getOrDefault("c", "");
        String className = owner.isEmpty() ? "" : pkg + "." + owner;
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("kind", kind); row.put("label", label); row.put("package", pkg); row.put("className", className);
        String relative = kind.equals("package") ? pkg.replace('.', '/') + "/package-summary.html"
                : pkg.replace('.', '/') + "/" + owner + ".html";
        String document = root.resolve(relative).normalize().toString();
        if (kind.equals("member")) {
            String fragment = entry.getOrDefault("u", label.replace(" ", ""));
            document += "#" + fragment;
        }
        row.put("document", document); return row;
    }

    private static int score(String original, String[] tokens, Map<String,Object> row) {
        String label = String.valueOf(row.get("label")).toLowerCase(Locale.ROOT), owner = String.valueOf(row.get("className")).toLowerCase(Locale.ROOT);
        String pkg = String.valueOf(row.get("package")).toLowerCase(Locale.ROOT), q = original.toLowerCase(Locale.ROOT);
        if (!allTokens(label + " " + owner + " " + pkg, tokens)) return 0;
        int rank = row.get("kind").equals("type") ? 30 : row.get("kind").equals("package") ? 10 : 20;
        String plain = label.contains("(") ? label.substring(0,label.indexOf('(')) : label;
        if (label.equals(q) || plain.equals(q) || owner.equals(q)) rank += 200;
        else if (label.startsWith(q)) rank += 100;
        else if (label.contains(q)) rank += 50;
        return rank;
    }

    private static boolean allTokens(String text, String[] tokens) { for (String token : tokens) if (!text.contains(token)) return false; return true; }
    private static int pageLimit(int limit) { return Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? 20 : limit)); }
    private static int pageOffset(int offset) { return Math.max(0, Math.min(MAX_OFFSET, offset)); }
    private static String cleanQuery(String value) { String query = value == null ? "" : value.trim().replaceAll("[\\p{Cntrl}]", " "); return query.length() > 256 ? query.substring(0,256) : query; }
    private static String typeName(Class<?> type) { return type.getCanonicalName() == null ? type.getName() : type.getCanonicalName(); }
    private static String brief(Throwable failure) { String message = failure.getMessage(); return failure.getClass().getSimpleName() + (message == null ? "" : ": " + cleanQuery(message)); }
    private static Map<String,Object> base(String tool, int offset, int limit) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("tool", tool);
        result.put("offset", pageOffset(offset)); result.put("limit", pageLimit(limit));
        result.put("maxOffset", MAX_OFFSET); result.put("readOnly", true); return result;
    }
    private static Map<String,Object> error(Map<String,Object> result, String code, String message) {
        result.put("error", code); result.put("message", message); result.put("items", List.of()); result.put("complete", false); return result;
    }
    private static void paginate(Map<String,Object> result, List<Map<String,Object>> members, int offset, int limit) {
        int start = pageOffset(offset), end = Math.min(members.size(), start + pageLimit(limit));
        result.put("items", start >= members.size() ? List.of() : new ArrayList<>(members.subList(start, end)));
        result.put("totalMatches", members.size()); result.put("hasMore", end < members.size());
        result.put("nextOffset", end < members.size() && start + pageLimit(limit) <= MAX_OFFSET ? start + pageLimit(limit) : null);
    }

    /** Return only a short relevant documentation passage; never expose a complete class page.
     * Runtime signatures above remain authoritative if installed documentation is out of date.
     */
    @SuppressWarnings("unchecked")
    private static void addDocumentationText(Map<String,Object> result, Documentation source, String className) {
        if(source.root==null)return;
        Map<String,String> pages=new LinkedHashMap<>();List<String> warnings=new ArrayList<>();
        String current=readClassDocument(source.root,className,pages,warnings);
        if(current!=null){
            int begin=current.indexOf("<section class=\"class-description\"");
            if(begin>=0){int end=current.indexOf("</section>",begin);if(end>=0)result.put("classDocumentation",htmlText(current.substring(begin,Math.min(end,begin+24000)),1600));}
        }
        Object raw=result.get("items");if(!(raw instanceof List<?>))return;
        for(Map<String,Object> row:(List<Map<String,Object>>)raw){
            String owner=String.valueOf(row.getOrDefault("declaringClass",className));
            if(!CLASS_NAME.matcher(owner).matches())continue;
            String page=readClassDocument(source.root,owner,pages,warnings);if(page==null)continue;
            String anchor;
            if(row.get("kind").equals("method")||row.get("kind").equals("constructor")){
                List<String> parameters=(List<String>)row.getOrDefault("parameters",List.of());
                List<String> values=new ArrayList<>(parameters);
                if(Boolean.TRUE.equals(row.get("varArgs"))&&!values.isEmpty()){
                    int last=values.size()-1;String value=values.get(last);if(value.endsWith("[]"))values.set(last,value.substring(0,value.length()-2)+"...");
                }
                anchor=(row.get("kind").equals("constructor")?"&lt;init&gt;":row.get("name"))+"("+String.join(",",values)+")";
            }else if(row.get("kind").equals("field"))anchor=String.valueOf(row.get("name"));
            else continue; // Search-index labels do not establish exact overload parameter types.
            String marker="<section class=\"detail\" id=\""+anchor+"\"";int begin=page.indexOf(marker);
            if(begin<0)continue;int end=page.indexOf("</section>",begin);if(end<0)continue;
            String excerpt=htmlText(page.substring(begin,Math.min(end,begin+24000)),1200);
            row.put("documentationText",excerpt);row.put("documentation",classDocument(source.root,owner)+"#"+anchor.replace("&lt;","%3C").replace("&gt;","%3E"));
        }
        if(!warnings.isEmpty())result.put("documentationWarnings",warnings);
    }
    private static String readClassDocument(Path root,String name,Map<String,String> pages,List<String> warnings){
        if(pages.containsKey(name))return pages.get(name);
        if(pages.size()>=8)return null; // Bounded inherited-class page fan-out.
        Path path=classDocument(root,name);String text=null;
        try{if(Files.isRegularFile(path)){if(Files.size(path)>MAX_CLASS_DOC_BYTES)warnings.add("类文档超过 8 MiB，省略说明: "+name);else text=Files.readString(path,StandardCharsets.UTF_8);}}
        catch(IOException failure){warnings.add(name+": "+brief(failure));}
        pages.put(name,text);return text;
    }
    private static String htmlText(String html,int limit){
        String value=html.replaceAll("(?s)<script\\b[^>]*>.*?</script>"," ").replaceAll("<[^>]+>"," ");
        Matcher entities=Pattern.compile("&(#x[0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);").matcher(value);StringBuffer decoded=new StringBuffer();
        while(entities.find()){
            String entity=entities.group(1),replacement=switch(entity){case "lt"->"<";case "gt"->">";case "amp"->"&";case "quot"->"\"";case "apos"->"'";case "nbsp"->" ";default->null;};
            if(replacement==null&&entity.startsWith("#"))try{int point=entity.startsWith("#x")?Integer.parseInt(entity.substring(2),16):Integer.parseInt(entity.substring(1));if(Character.isValidCodePoint(point))replacement=new String(Character.toChars(point));}catch(IllegalArgumentException ignored){}
            entities.appendReplacement(decoded,Matcher.quoteReplacement(replacement==null?entities.group():replacement));
        }
        entities.appendTail(decoded);value=decoded.toString().replaceAll("\\s+"," ").trim();return value.length()>limit?value.substring(0,limit)+"…":value;
    }

    /** Javadoc indexes contain flat JSON objects with string keys and values. Streaming keeps
     * memory independent of the 100,000+ member count and returns only the bounded page.
     */
    private static void scan(Path file, Consumer<Map<String,String>> consumer) throws IOException {
        long bytes = Files.size(file);
        if (bytes > MAX_INDEX_BYTES) throw new IOException("索引超过 64 MiB，拒绝无界读取");
        long deadline = System.nanoTime() + 10_000_000_000L;
        try (Reader reader = new BufferedReader(Files.newBufferedReader(file, StandardCharsets.UTF_8), 131072)) {
            int character, objects = 0;
            while ((character = reader.read()) != -1) {
                if (character != '{') continue;
                if ((++objects & 1023) == 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("API 文档查询已取消");
                    if (System.nanoTime() > deadline || objects > 500000) throw new IOException("索引扫描达到有界查询限制");
                }
                Map<String,String> entry = new LinkedHashMap<>();
                while (true) {
                    character = whitespace(reader);
                    if (character == '}') break;
                    if (character == ',') character = whitespace(reader);
                    if (character != '"') throw new IOException("不支持的 Javadoc 索引格式");
                    String key = jsonString(reader);
                    if (whitespace(reader) != ':' || whitespace(reader) != '"') throw new IOException("不支持的 Javadoc 索引字段");
                    entry.put(key, jsonString(reader));
                    if (entry.size() > 12) throw new IOException("索引对象字段过多");
                }
                consumer.accept(entry);
            }
        }
    }
    private static int whitespace(Reader reader) throws IOException { int c; do { c = reader.read(); } while (c != -1 && Character.isWhitespace(c)); return c; }
    private static String jsonString(Reader reader) throws IOException {
        StringBuilder value = new StringBuilder(); int c;
        while ((c = reader.read()) != -1) {
            if (c == '"') return value.toString();
            if (c == '\\') {
                c = reader.read();
                switch (c) {
                    case '"': case '\\': case '/': value.append((char)c); break;
                    case 'b': value.append('\b'); break; case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break; case 'r': value.append('\r'); break; case 't': value.append('\t'); break;
                    case 'u':
                        int unicode = 0;
                        for (int i=0;i<4;i++) { int digit=Character.digit(reader.read(),16); if(digit<0)throw new IOException("无效 Unicode 转义"); unicode=(unicode<<4)+digit; }
                        value.append((char)unicode); break;
                    default: throw new IOException("无效 JSON 转义");
                }
            } else {if(c<0x20)throw new IOException("JSON 字符串含未转义控制字符");value.append((char)c);}
            if (value.length() > 16384) throw new IOException("索引标签过长");
        }
        throw new IOException("截断的 JSON 字符串");
    }

    private record Documentation(Path root, String detectedBy, String reason, Path runtimeHome) {}
    private static Documentation locate() {
        Path runtimeHome = runtimeHome();
        String explicit = property("starccm.assistant.apiDocRoot");
        if (!explicit.isEmpty()) {
            Path root = normalized(explicit);
            return root != null && isDocumentation(root) ? new Documentation(root,"explicit_apiDocRoot","",runtimeHome)
                    : new Documentation(null,"explicit_apiDocRoot","指定的 API 文档目录不存在或缺少 type-search-index.js；不会替换为其他版本。",runtimeHome);
        }
        if (runtimeHome != null) {
            Path root=runtimeHome.resolve("doc/client/html");
            return isDocumentation(root) ? new Documentation(root,"running_star_class_source","",runtimeHome)
                    : new Documentation(null,"running_star_class_source","运行中 STAR 安装缺少 Java API 文档；不会用其他版本文档代替。实际反射签名仍可查询。",runtimeHome);
        }
        Set<Path> candidates = new java.util.LinkedHashSet<>();
        for (String key : List.of("starccm.assistant.starHome","star.home","starccm.home")) addCandidate(candidates, property(key));
        for (String key : List.of("STARCCM_HOME","STAR_HOME","STARHOME")) addCandidate(candidates, environment(key));
        for (String entry : property("java.class.path").split(Pattern.quote(java.io.File.pathSeparator))) addCandidate(candidates, entry);
        addCandidate(candidates, property("user.dir"));
        for (Path candidate : candidates) {
            Path root = findDocumentation(candidate);
            if (root != null) return new Documentation(root,"installation_property_environment_or_classpath","",null);
        }
        return new Documentation(null,"not_found","未找到当前安装的 STAR Java API 文档。可配置 starccm.assistant.apiDocRoot 为本机 doc/client/html；运行时签名查询仍可独立使用。",null);
    }
    private static void addSource(Map<String,Object> result, Documentation source) {
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put("available", source.root != null); metadata.put("root", source.root == null ? null : source.root.toString());
        metadata.put("detectedBy", source.detectedBy); metadata.put("networkUsed", false);
        metadata.put("runtimeInstallation", source.runtimeHome == null ? null : source.runtimeHome.toString());
        metadata.put("matchesRunningInstallation", source.root == null || source.runtimeHome == null ? null : samePath(source.root,source.runtimeHome.resolve("doc/client/html")));
        metadata.put("versionVerification", source.runtimeHome == null ? "运行时安装来源尚未核验；以 api_class 的实际反射签名为准。" : "安装路径与运行中 STAR 类来源比较；文档文本不是执行权限。");
        if (!source.reason.isEmpty()) metadata.put("reason", source.reason);
        result.put("documentation", metadata);
    }
    private static Path runtimeHome() {
        try {
            Class<?> simulation = Class.forName("star.common.Simulation",false,ApiDocumentation.class.getClassLoader());
            String location = classSource(simulation);
            if (location == null) return null;
            Path path=Paths.get(location);
            if(Files.isRegularFile(path))path=path.getParent();
            for(int level=0;path!=null&&level<12;level++,path=path.getParent())
                if(Files.isDirectory(path.resolve("star/lib/java")))return path;
            Path docs = findDocumentation(Paths.get(location));
            return docs == null ? null : docs.getParent().getParent().getParent();
        } catch (ClassNotFoundException | LinkageError | SecurityException failure) { return null; }
    }
    private static String classSource(Class<?> type) {
        try {
            URL url = type.getProtectionDomain().getCodeSource() == null ? null : type.getProtectionDomain().getCodeSource().getLocation();
            return url == null || !url.getProtocol().equals("file") ? null : Paths.get(url.toURI()).toAbsolutePath().normalize().toString();
        } catch (Exception failure) { return null; }
    }
    private static Path findDocumentation(Path candidate) {
        Path path = candidate;
        if (Files.isRegularFile(path)) path = path.getParent();
        for (int level=0; path != null && level<12; level++,path=path.getParent()) {
            if (isDocumentation(path)) return path;
            Path docs = path.resolve("doc/client/html"); if (isDocumentation(docs)) return docs;
        }
        return null;
    }
    private static boolean isDocumentation(Path path) { return Files.isDirectory(path) && Files.isRegularFile(path.resolve("type-search-index.js")); }
    private static boolean samePath(Path first,Path second){try{return Files.isSameFile(first,second);}catch(IOException ignored){return first.equals(second);}}
    private static void addCandidate(Set<Path> candidates,String value) { Path path=normalized(value); if(path!=null)candidates.add(path); }
    private static Path normalized(String value) { try{return value==null||value.isBlank()?null:Paths.get(value).toAbsolutePath().normalize();}catch(RuntimeException ignored){return null;} }
    private static String property(String key) { try{return System.getProperty(key,"");}catch(SecurityException ignored){return "";} }
    private static String environment(String key) { try{String value=System.getenv(key);return value==null?"":value;}catch(SecurityException ignored){return "";} }
    private static Path classDocument(Path root,String className) {
        String canonical=className.replace('$','.'); Path direct=root.resolve(canonical.replace('.','/')+".html");
        if(Files.isRegularFile(direct))return direct;
        int split=canonical.lastIndexOf('.');
        while(split>4){Path candidate=root.resolve(canonical.substring(0,split).replace('.','/')+"/"+canonical.substring(split+1)+".html");if(Files.isRegularFile(candidate))return candidate;split=canonical.lastIndexOf('.',split-1);}
        return direct;
    }
    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader loader=ApiDocumentation.class.getClassLoader();
        try{return Class.forName(className,false,loader);}catch(ClassNotFoundException original){
            String candidate=className;int split=candidate.lastIndexOf('.');
            while(split>4){candidate=candidate.substring(0,split)+"$"+candidate.substring(split+1);try{return Class.forName(candidate,false,loader);}catch(ClassNotFoundException ignored){}split=candidate.lastIndexOf('.',split-1);}
            throw original;
        }
    }
}
