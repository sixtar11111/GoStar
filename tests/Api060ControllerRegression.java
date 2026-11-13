package local.starccm.assistant;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the real selected-model controller with loopback transport and a synthetic native bridge.
 * No user key, external model call, licensed STAR process or SIM write is involved.
 */
public final class Api060ControllerRegression {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static String plan(Map<String,Object> fields){Map<String,Object> p=new LinkedHashMap<>();p.put("explanation","fixture API stage");p.put("complete",false);for(String key:List.of("calls","actions","changes","unsupported"))p.put(key,List.of());p.putAll(fields);return ConfigurationEngine.json(p);}
    public static final class ApiEngine {
        public final List<String> reads=new CopyOnWriteArrayList<>();
        public final AtomicInteger writes=new AtomicInteger(),begins=new AtomicInteger();
        public volatile int value=2;
        public volatile String status="idle",goal="",note="";
        public void beginTask(String goal,String destination){this.goal=goal;status="planning";begins.incrementAndGet();}
        public String refreshPlanningCatalog(){return "fixture target available; invoke_api through exact api_read evidence";}
        public String refreshCatalog(){return refreshPlanningCatalog();}
        public String initialCatalog(String goal){return "fixture:sample";}
        public String engineeringPolicy(){return "Synthetic fixture; selected model alone plans writes.";}
        public String engineeringContext(String goal){return "{}";}
        public String geometryContext(){return "{}";}
        public String readiness(){return "{\"ready\":true,\"incomplete_reasons\":[]}";}
        public String inspectionJson(Map<?,?> request){return ConfigurationEngine.json(request);}
        public String searchApi(String query,int offset,int limit){reads.add("search:"+query+":"+offset+":"+limit);return "fixture_api_search_verified";}
        public String inspectApiClass(String name,String filter,int offset,int limit){reads.add("class:"+name+":"+filter+":"+offset+":"+limit);return "fixture_api_class_verified";}
        public String inspectApi(String request)throws Exception{Map<?,?> p=(Map<?,?>)CodexAssistant.parseJson(request);reads.add("object:"+p.get("scope")+":"+p.get("query")+":"+p.get("path")+":"+p.get("readback"));return ConfigurationEngine.json(Map.of("object_ref","api:fixture-task:"+begins.get(),"expected_state","native-value-"+value,"value",value,"evidence","fixture_api_object_verified"));}
        public String actionHelp(String name){reads.add("help:"+name);return "invoke_api fixture: exact method, arguments, readback, expected_after";}
        public String preview(String response)throws Exception{Map<?,?> p=(Map<?,?>)CodexAssistant.parseJson(response);if(p.get("actions") instanceof List&&!((List<?>)p.get("actions")).isEmpty()){Map<?,?> a=(Map<?,?>)((List<?>)p.get("actions")).get(0);check("invoke_api".equals(a.get("type")),"only selected generic action reaches preflight");check(("native-value-"+value).equals(a.get("expected_state")),"current live-state token reaches preflight unchanged");return "fixture generic setter preview";}return "";}
        public boolean isLongOperation(String response){return true;}
        public Map<String,Object> apply(String response)throws Exception{Map<?,?> p=(Map<?,?>)CodexAssistant.parseJson(response);Map<?,?> a=(Map<?,?>)((List<?>)p.get("actions")).get(0);check("setValue(int)".equals(a.get("method")),"exact native signature survives planner parsing");check("getValue()".equals(a.get("readback")),"exact native readback survives planner parsing");value=((Number)((List<?>)a.get("arguments")).get(0)).intValue();writes.incrementAndGet();return Map.of("continue",true,"progress",true,"summary","fixture saved readback="+value,"report","fixture-only");}
        public String planningLedger(){return ConfigurationEngine.json(Map.of("saved_action_count",writes.get(),"latest_saved_actions",writes.get()==0?List.of():List.of(Map.of("kind","invoke_api","status","saved","method","setValue(int)","actual",value))));}
        public String taskHistory(){return planningLedger();}
        public String completionStatus(){return "{\"ready\":true,\"blocked\":false}";}
        public String transactionClosure(){return "{\"certified\":false}";}
        public String unsupportedFactConflict(String response){return "";}
        public Map<String,Object> finish(String explanation,boolean incomplete){status=incomplete?"incomplete":"completed";note=explanation;return Map.of("summary",note,"report","fixture-only");}
        public String taskStatus(){return status;}
        public String chatSummary(){return status+": "+note;}
        public String latestReport(){return planningLedger();}
        public boolean hasActiveTask(){return status.equals("planning");}
        public boolean abortOperation(){return true;}
        public void recordMetric(Map<?,?> metric){}
        public void recordInspection(String scope,String query,int offset,String result){}
    }
    public static void main(String[] args)throws Exception{
        for(String goal:List.of("只检查当前参数，禁止修改任何配置，不运行计算。","只读检查，严禁修改当前仿真。","检查当前设置，不得更改配置。","仅查看参数，不准写入任何设置。","只检查当前设置，不允许修改。","Read-only inspection; do not change any settings."))check(CodexAssistant.nativeApiReadOnlyGoal(goal),"explicit no-write directive blocks generic setters: "+goal);
        for(String goal:List.of("先只读检查参数，然后修改入口速度。","修改入口速度，禁止重划网格。","设置参考面积为1.2，不运行计算。","修改参数，不修改网格。"))check(!CodexAssistant.nativeApiReadOnlyGoal(goal),"unrelated prohibition preserves authorized edit: "+goal);

        Path out=Path.of(args.length==0?"/tmp/starccm-capabilities060/controller-api060":args[0]);Files.createDirectories(out);Controller059Regression.out=out;
        List<Map<String,Object>> firstReads=List.of(
            Map.of("scope","api_search","query","sample value","offset",3),
            Map.of("scope","api_class","query","star.fixture.Sample","filter","setValue","offset",1),
            Map.of("scope","api_object","query","fixture:sample","path",List.of("getChild()")),
            Map.of("scope","api_read","query","fixture:sample","readback","getValue()"),
            Map.of("scope","action_help","query","invoke_api"));
        Map<String,Object> selected=new LinkedHashMap<>();selected.put("type","invoke_api");selected.put("id","api:fixture-task:1");selected.put("expected_state","native-value-2");selected.put("method","setValue(int)");selected.put("arguments",List.of(7));selected.put("readback","getValue()");selected.put("expected_after",7);selected.put("reason","explicit user target");
        try(Controller059Regression.Endpoint endpoint=new Controller059Regression.Endpoint();Controller059Regression.Ui ui=new Controller059Regression.Ui(endpoint)){
            ApiEngine engine=new ApiEngine();Controller059Regression.set(ui.core,"configurationEngine",engine);
            endpoint.reset(plan(Map.of("calls",firstReads)),plan(Map.of("actions",List.of(selected))),plan(Map.of("calls",List.of(Map.of("scope","api_read","query","fixture:sample","readback","getValue()")))),Controller059Regression.COMPLETE);
            ui.send("把 sample 参数改成7，使用扩展接口并保存，只修改此参数，不运行求解。");
            Files.writeString(out.resolve("first-task-debug.json"),ConfigurationEngine.json(Map.of("status",engine.status,"note",engine.note,"reads",engine.reads,"writes",engine.writes.get(),"requests",endpoint.requests)));
            check(engine.status.equals("completed"),"real selected controller completes generic bridge task: "+engine.status+"; note="+engine.note+"; reads="+engine.reads);
            check(engine.writes.get()==1&&engine.value==7,"exactly one model-selected generic write");
            check(engine.reads.contains("search:sample value:3:20"),"api_search pagination routed");
            check(engine.reads.contains("class:star.fixture.Sample:setValue:1:6"),"api_class filter/pagination routed with bounded signature page");
            check(engine.reads.stream().anyMatch(s->s.equals("object:api_object:fixture:sample:[getChild()]:null")),"api_object path preserved");
            check(engine.reads.stream().filter(s->s.equals("object:api_read:fixture:sample:null:getValue()")).count()==2,"same api_read executed again after write instead of stale cached value");
            check(endpoint.requests.size()==4,"bounded discovery→write→reread→completion calls");
            check(endpoint.prompt(1).contains("fixture_api_search_verified")&&endpoint.prompt(1).contains("fixture_api_class_verified")&&endpoint.prompt(1).contains("fixture_api_object_verified"),"actual tool evidence fed to selected model");
            check(endpoint.prompt(2).contains("latest_saved_actions")&&endpoint.prompt(2).contains("invoke_api")&&endpoint.prompt(2).contains("setValue(int)"),"next stage sees exact saved generic receipt");
            check(endpoint.prompt(3).contains("native-value-7"),"post-write api_read returns current value to selected model");
            check(endpoint.requests.stream().allMatch(p->Controller059Regression.MODEL.equals(p.get("model")))&&endpoint.paths.stream().allMatch(p->p.equals("/Selected%2F057/Exact")),"all stages preserve selected endpoint/model");
            check(endpoint.requests.stream().allMatch(p->!p.containsKey("tools")&&!p.containsKey("response_format")),"API feature retains plain provider-compatible messages");
            endpoint.reset(plan(Map.of("calls",List.of(Map.of("scope","api_read","query","fixture:sample","readback","getValue()")))),Controller059Regression.COMPLETE);
            ui.send("检查并配置当前 sample 参数；保持它为7，不运行求解。");
            check(engine.begins.get()==2&&engine.writes.get()==1,"new user task works and does not replay prior write");
            check(endpoint.prompt(1).contains("api:fixture-task:2")&&!endpoint.prompt(1).contains("api:fixture-task:1"),"new task receives new handle and cleared inspection cache");
        }
        String result="PASS Api060ControllerRegression "+checks+" checks; loopback only, fake native bridge, zero external APIs or SIM writes.\n";Files.writeString(out.resolve("result.txt"),result);System.out.print(result);
    }
}
