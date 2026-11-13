package local.starccm.assistant;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Real selected-provider controller with loopback responses and a synthetic report executor. */
public final class Reports061ControllerRegression {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static String plan(Map<String,Object> fields){Map<String,Object> p=new LinkedHashMap<>();p.put("explanation","report fixture");p.put("complete",false);for(String k:List.of("calls","actions","changes","unsupported"))p.put(k,List.of());p.putAll(fields);return ConfigurationEngine.json(p);}
    public static final class ReportEngine {
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
        public String inspectOperations(String query,int offset){reads.add("operations:"+query);return ConfigurationEngine.json(Map.of("id",query,"expected_state","report-state-"+value,"supported_actions",List.of("create_aerodynamic_report","configure_aerodynamic_report")));}
        public String actionHelp(String name){reads.add("help:"+name);return AerodynamicReports.contract();}
        public String preview(String response)throws Exception{Map<?,?> p=(Map<?,?>)CodexAssistant.parseJson(response);if(!(p.get("actions") instanceof List)||((List<?>)p.get("actions")).isEmpty())return "";Map<?,?> a=(Map<?,?>)((List<?>)p.get("actions")).get(0);check(("report-state-"+value).equals(a.get("expected_state")),"fresh state reaches report preflight");return "fixture report preflight";}
        public boolean isLongOperation(String response){return true;}
        public Map<String,Object> apply(String response)throws Exception{Map<?,?> p=(Map<?,?>)CodexAssistant.parseJson(response);Map<?,?> a=(Map<?,?>)((List<?>)p.get("actions")).get(0);String type=String.valueOf(a.get("type"));check(Set.of("create_aerodynamic_report","configure_aerodynamic_report").contains(type),"dedicated report action reaches executor");if(type.startsWith("create")){check(a.get("selection_ids").equals(List.of("wall:Wing")),"model selection preserved");check(a.get("reference_area_m2").equals(2.5),"model reference area preserved");check(Boolean.TRUE.equals(a.get("create_monitor")),"model monitor request preserved");}else check(a.get("reference_area_m2").equals(3.25),"second-task partial update preserved");value++;writes.incrementAndGet();return Map.of("continue",true,"progress",true,"summary","report saved and read back","report","fixture-only");}
        public String planningLedger(){return ConfigurationEngine.json(Map.of("saved_action_count",writes.get(),"latest_saved_actions",writes.get()==0?List.of():List.of(Map.of("kind","create_aerodynamic_report","status","saved","method","setValue(int)","actual",value))));}
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
        Path out=Path.of(args[0]);Files.createDirectories(out);Controller059Regression.out=out;
        Map<String,Object> creation=new LinkedHashMap<>();creation.put("type","create_aerodynamic_report");creation.put("id","sim:current");creation.put("expected_state","report-state-2");creation.put("name","CD");creation.put("report_kind","force_coefficient");creation.put("selection_ids",List.of("wall:Wing"));creation.put("direction",List.of(1,0,0));creation.put("reference_area_m2",2.5);creation.put("reference_density_kg_m3",1.18);creation.put("reference_velocity_m_s",210.);creation.put("create_monitor",true);creation.put("plot_name","CD History");creation.put("reason","explicit user request");
        try(Controller059Regression.Endpoint endpoint=new Controller059Regression.Endpoint();Controller059Regression.Ui ui=new Controller059Regression.Ui(endpoint)){
            ReportEngine engine=new ReportEngine();Controller059Regression.set(ui.core,"configurationEngine",engine);
            endpoint.reset(plan(Map.of("calls",List.of(Map.of("scope","action_help","query","create_aerodynamic_report"),Map.of("scope","operations","query","sim:current")))),plan(Map.of("actions",List.of(creation))),Controller059Regression.COMPLETE);
            ui.send("在 Wing 壁面创建阻力系数报告 CD，Lab方向[1,0,0]，参考面积2.5平方米、密度1.18、速度210，创建监视器和历史图；不运行CFD。创建并保存。");
            Files.writeString(out.resolve("first-debug.json"),ConfigurationEngine.json(Map.of("status",engine.status,"note",engine.note,"reads",engine.reads,"writes",engine.writes.get(),"requests",endpoint.requests)));
            check(engine.status.equals("completed")&&engine.writes.get()==1,"first natural-language report task completes");check(engine.reads.contains("help:create_aerodynamic_report"),"selected model discovers report schema");check(endpoint.prompt(1).contains("report-state-2")&&endpoint.prompt(1).contains("reference_area_m2"),"native schema/catalog evidence returned to selected model");
            endpoint.reset(plan(Map.of("calls",List.of(Map.of("scope","operations","query","report:CD"),Map.of("scope","action_help","query","configure_aerodynamic_report")))),plan(Map.of("actions",List.of(Map.of("type","configure_aerodynamic_report","id","report:CD","expected_state","report-state-3","reference_area_m2",3.25,"reason","explicit follow-up")))),Controller059Regression.COMPLETE);
            ui.send("只将刚才 CD 报告的参考面积改为3.25平方米并保存，其他参数不变，不运行求解。");
            check(engine.begins.get()==2&&engine.writes.get()==2&&engine.status.equals("completed"),"same conversation accepts another report edit");
            check(endpoint.requests.stream().allMatch(p->Controller059Regression.MODEL.equals(p.get("model")))&&endpoint.paths.stream().allMatch(p->p.equals("/Selected%2F057/Exact")),"follow-up retains exact selected provider and model");
            check("GoStar".equals(AssistantTopComponent.openAction().getValue(javax.swing.Action.NAME)),"Tools action is branded GoStar");
            java.util.List<String> labels=new ArrayList<>();collect(ui.frame,labels);check(labels.stream().anyMatch(t->t.trim().equals("GoStar")),"chat header is branded GoStar");
            Files.writeString(out.resolve("model-requests.json"),ConfigurationEngine.json(endpoint.requests));
        }
        String result="PASS Reports061ControllerRegression "+checks+" checks; loopback model and synthetic native bridge; zero external API calls.\n";Files.writeString(out.resolve("result.txt"),result);System.out.print(result);
    }
    private static void collect(java.awt.Container c,java.util.List<String> labels){for(java.awt.Component child:c.getComponents()){if(child instanceof javax.swing.JLabel)labels.add(((javax.swing.JLabel)child).getText());if(child instanceof java.awt.Container)collect((java.awt.Container)child,labels);}}
}
