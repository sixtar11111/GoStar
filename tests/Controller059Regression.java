package local.starccm.assistant;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import star.base.neo.ClientServerObjectKey;
import star.common.Simulation;

/** Independent 0.5.9 controller recovery tests against a fixed built JAR; synthetic engine only. */
public final class Controller059Regression {
    static final String KEY="test-only-controller059", MODEL="Fixture/Selected-Exact@057";
    static final String COMPLETE="{\"explanation\":\"fixture complete\",\"complete\":true,\"calls\":[],\"actions\":[],\"changes\":[],\"unsupported\":[]}";
    static final String EMPTY="{\"explanation\":\"正在检查\",\"complete\":false,\"calls\":[],\"actions\":[],\"changes\":[],\"unsupported\":[]}";
    static final String BLOCKED="{\"explanation\":\"fixture blocked\",\"complete\":false,\"calls\":[],\"actions\":[],\"changes\":[],\"unsupported\":[\"缺少目标参数\"]}";
    static final String CHANGE="{\"explanation\":\"fixture scalar\",\"complete\":true,\"actions\":[],\"changes\":[{\"id\":\"fixture:p\",\"expected_value\":2,\"value\":3,\"unit\":\"\",\"reason\":\"explicit request\"}],\"unsupported\":[]}";
    static final String SOLVE="{\"explanation\":\"fixture solve\",\"complete\":true,\"actions\":[{\"type\":\"run_solver\",\"id\":\"fixture:solver\",\"expected_state\":\"fixture-state\",\"steps\":30,\"reason\":\"explicit thirty steps\"}],\"changes\":[],\"unsupported\":[]}";
    static final String SOLVE_STAGE=SOLVE.replace("\"complete\":true","\"complete\":false");
    // Exact selected DeepSeek response after saved native deletion in RC12.
    static final String RC12_STALE_DELETE_UNSUPPORTED="{\"explanation\":\"删除区域前须解除SubtractPartsOperation的静态引用；但是previous指令只删当前流体域且要求随后继续半模外流场。解除引用需调用configure_boolean移除Boolean的`body_id`引用并执行布尔；这将破坏已有布尔输出，已超出单纯删除区域范围。当前目标不明确是否允许改变布尔。需用户确认：是否允许删除或修改`External_Aero_Subtract`减运算的引用（这将改变布尔输出），还是仅删除区域并保留原有布尔和域几何？\",\"complete\":false,\"calls\":[],\"actions\":[],\"changes\":[],\"unsupported\":[\"删除区域star.common.Region_1783的静态依赖：SubtractPartsOperation_1736引用它且目录只允许configure_boolean/delete_operation；单纯delete_object会被拒绝，须先解除布尔引用。请明确是否允许修改或删除External_Aero_Subtract减运算，或改用repair_external_aero修复半模链而非硬删区域。\"]}";

    // Exact RC14 long-dependency unsupported response retained as historical failed-attempt evidence, SHA256 658cf0eb7ec3463b9e0627162ca4bf6742de2f7230d9523e9177a79b32b6367c.
    static final String RC14_LONG_DEPENDENCY_UNSUPPORTED="{\"explanation\":\"直接删除Region_1783被Boundary、PhysicsContinuum、SubtractPartsOperation、AutoMeshOperation等引用阻断。按依赖顺序先删除下游消费者：清空场景显示引用、删除边界（含空边界）、删除物理连续体，最后删除AutoMeshOperation和SubtractPartsOperation（会连带移除其输出部件和区域）。原始CAD Body 1保留。\",\"complete\":false,\"calls\":[],\"actions\":[],\"changes\":[],\"unsupported\":[\"当前工具仅支持单action/轮，需逐项删除：先set_object_selection清空场景引用，再按空边界→非空边界→PhysicsContinuum→AutoMeshOperation→SubtractPartsOperation顺序逐轮删除，每轮需真实回执。流程过长，建议使用repair_external_aero目标模式重建半模外域以闭合依赖，或允许分轮执行删除。\"]}";
    static final String RC18_POST_SUCCESS_REPLAN="{\n  \"explanation\": \"当前域已为半模包围（domain_mode=half，symmetry在y=0），但velocity_inlet分配了4个面（Block Surface 2/3/4/5），pressure_outlet仅1个面（Block Surface 6），且存在2个空开的Wall边界。需要将远场开口合理分配：入口面为+X方向（Block Surface 6），出口为-X方向（Block Surface 3），其余4个远场面（上/下/前/侧）应设为自由流。先读取set_boundary_type动作签名和这些面的当前状态。\",\n  \"complete\": false,\n  \"calls\": [\n    {\n      \"scope\": \"action_help\",\n      \"query\": \"set_boundary_type\"\n    },\n    {\n      \"scope\": \"operations\",\n      \"query\": \"star.common.Boundary_1842\"\n    },\n    {\n      \"scope\": \"operations\",\n      \"query\": \"star.common.Boundary_1845\"\n    }\n  ],\n  \"actions\": [],\n  \"changes\": [],\n  \"unsupported\": []\n}";
    static final String RC18_CORRECTION_GOAL="飞机的机翼是半模，对称面在xoz，而你现在全部包围了，这显然不对，请你修改流体域以及对应的边界条件。来流仍按0.75mach、飞行高度10km、y+目标1；本轮只完成外流场和边界修正，先不执行网格和CFD。";
    static final String REPLAY_PROOF=ConfigurationEngine.json(Map.of("guard","completed_workflow_replay","target_id","fixture:simulation","saved_workflow","repair_external_aero","actual",Map.of("workflow","prepare_external_aero","region_id","fixture:region","domain_mode","half","domain_bounds_m",Map.of("min_m",List.of(-20,0,-20),"max_m",List.of(20,80,20))),"verified_completion",Map.of("ready",true,"blocked",false,"target_scope",Map.of("region_ids",List.of("fixture:region"),"mesh_ids",List.of("fixture:mesh")))));
    static final String OUTPUT_PROOF=REPLAY_PROOF.replace("completed_workflow_replay","completed_workflow_output").replace("fixture:simulation","fixture:region");
    // Exact RC13 native rejection, SHA256 0ef317080d4a16130a626db37a30ad3b84a22f8a1f8eb4caae17d58c445615d3.
    static final String RC13_OUTPUT_REJECTION="原生完成读回已通过，未删除本轮刚刚完成的新输出：{\"guard\":\"completed_workflow_output\",\"target_id\":\"star.common.Region_736581\",\"saved_workflow\":\"repair_external_aero\",\"actual\":{\"workflow\":\"prepare_external_aero\",\"requested_action\":\"repair_external_aero\",\"mode\":\"repair\",\"domain_mode\":\"half\",\"meshing_executed\":false,\"body_id\":\"star.cadmodeler.SolidModelPart_931\",\"mesh_id\":\"star.meshing.AutoMeshOperation_526847\",\"domain_id\":\"star.meshing.SimpleBlockPart_1716\",\"boolean_id\":\"star.meshing.SubtractPartsOperation_1736\",\"fluid_id\":\"star.meshing.MeshOperationPart_1740\",\"region_id\":\"star.common.Region_736581\",\"validation\":\"原生顺序配置和读回完成；未运行CFD。网格执行状态与质量分开，实际y+和数值精度仍须求解验证。\",\"domain_bounds_m\":{\"min_m\":[-75.3860507830359,0.0,-75.58894097805023],\"max_m\":[78.63536167144775,79.15532970428467,75.7328491806984]},\"reference_length_m\":1.6891245468832494,\"domain_scale_m\":7.538602828979492,\"prism_settings\":{\"layers\":35,\"first_layer_m\":9.22431E-6,\"total_thickness_m\":0.027196412714348854,\"distribution_mode\":1,\"stretching_function\":0,\"total_thickness_mode\":1,\"readback_source\":\"current native AutoMeshOperation default values; custom surface exclusions listed separately\"},\"mesh_validity\":{\"previous_volume_representation_is_not_acceptance\":true,\"requires_regeneration\":true,\"current_geometry_meshed\":false},\"prism_enabled\":true,\"control_roles\":[{\"step\":\"outer_prism_exclusion\",\"control\":\"External_Aero_Outer\",\"enabled\":true,\"purpose\":\"Exclude outer and symmetry faces from prism layers; keep this control enabled\"}],\"steps\":[{\"step\":\"cad_boolean_settings\",\"actual\":{\"validation\":\"布尔模式和明确指定的精度已读回；原CAD未修改，尚未执行布尔或重划网格，不能据此宣称共面薄片已修复\"}},{\"step\":\"geometry_boundaries\"},{\"step\":\"physics_profile\",\"actual\":{\"profile\":\"steady_compressible_sa\",\"model_chain_complete\":true,\"required_models\":{},\"wall_treatment\":\"star.saturb.SaAllYplusWallTreatment\",\"space_dimension\":3,\"enabled_models_added\":[],\"disabled_conflicting_models\":[],\"validation\":\"完整模型链已原生同步写入、逐项读回，当前requiredModels为空，理想气体为可压缩；近壁网格、实际y+、工况数值和迭代收敛需另行核验\"}},{\"step\":\"flow_conditions\",\"actual\":{\"validation\":\"初始值和明确选中的边界工况已写入并按SI逐项读回；未划网格、未初始化解、未进行求解\"}},{\"step\":\"mesh_defaults\",\"actual\":{\"validation\":\"指定默认网格数值已逐项读回；基础尺寸未改，未指定项未写入。数值已保存不等于相关细化已启用或网格已生效；活动状态限制见notices，尚未执行网格或验证工程精度\"}},{\"step\":\"outer_prism_exclusion\",\"control\":\"External_Aero_Outer\",\"enabled\":true,\"purpose\":\"Exclude outer and symmetry faces from prism layers; keep this control enabled\"}],\"readiness\":{\"requested_configuration_items_present\":true,\"flow_solver_basics_ready\":true,\"incomplete_reasons\":[],\"flow_solver_incomplete_reasons\":[],\"boundary_counts\":{\"nonempty_open\":2,\"nonempty_symmetry\":1,\"nonempty_freestream\":0,\"nonempty_velocity_inlet\":1,\"nonempty_pressure_outlet\":1},\"goal_requirements\":{\"steady\":true,\"nonempty_velocity_inlet\":true,\"sst\":false,\"nonempty_pressure_outlet\":true,\"full_model_no_symmetry\":false,\"unsteady\":false,\"sa\":true,\"nonempty_symmetry\":true,\"nonempty_freestream\":false,\"gas\":true,\"wall_treatment\":\"\"},\"inspection_errors\":[]}},\"verified_completion\":{\"ready\":true,\"complete\":true,\"blocked\":false,\"incomplete_reasons\":[],\"target_scope\":{\"region_ids\":[\"star.common.Region_736581\"],\"mesh_ids\":[\"star.meshing.AutoMeshOperation_526847\"]}},\"guidance\":\"该对象是本轮原生创建/重建后已保存的新输出，不是尚待删除的旧对象；当前原生完成项已满足。不要重复删除并重做已经完成的事务。请依据回执仅处理其余尚未执行的用户要求；全部要求已完成时返回complete=true且空操作。这不是接口缺失或权限问题。后续新的用户删除指令会建立新任务，不受本轮保护限制。\"}";
    static final String DELETION_LEDGER=ConfigurationEngine.json(Map.of("saved_action_count",3,"confirmed_deletions",List.of(Map.of("id","star.meshing.AutoMeshOperation_530780","saved_native_delete",true,"exists_now",false),Map.of("id","star.common.Region_1783","saved_native_delete",true,"exists_now",false)),"known_body_measurements",Map.of("body_id","star.cadmodeler.SolidModelPart_931","reference_length_m",1.6891245468832494,"source","previous saved native measurement; not rebuilt region evidence"),"latest_native_readback",Map.of("ready",false,"incomplete_reasons",List.of("new live Region is not yet rebuilt")),"dependency_direction","deletion_blockers are consumers blocking deletion of the record itself, not producers blocking consumer deletion"));
    static final String REBUILD=plan(Map.of("complete",true,"actions",List.of(Map.of("type","repair_external_aero","id","fixture:simulation","body_id","star.cadmodeler.SolidModelPart_931","reference_length_m",1.6891245468832494,"execute_mesh",false,"reason","rebuild live Region after confirmed native deletion"))));
    static final String RECREATE=REBUILD.replace("\"execute_mesh\":false","\"execute_mesh\":false,\"recreate_region\":true");
    static final String CLOSURE_REPAIR=REBUILD.replace("\"complete\":true","\"complete\":false");
    static final Map<String,Object> CLOSURE_RECOMMENDED=Map.of("domain_mode","half","flow_axis","+x","symmetry_axis","y","symmetry_plane_m",0.0,"mach",0.75,"altitude_m",10000.0,"target_yplus",1.0,"execute_mesh",false);
    static final Map<String,Object> CLOSURE_ACTUAL=Map.of("domain_mode","half","domain_bounds_m",Map.of("min_m",List.of(-75,0,-75),"max_m",List.of(78,79,75)));
    static final StringBuilder LOG=new StringBuilder();
    static final List<String> FAILURES=new ArrayList<>(),EXTERNAL=new CopyOnWriteArrayList<>();
    static Path out;static int count;
    static void check(boolean yes,String label){String line=(yes?"PASS ":"FAIL ")+(++count)+" "+label;LOG.append(line).append('\n');System.out.println(line);if(!yes)FAILURES.add(label);}
    static Method method(String name,Class<?>...types)throws Exception{Method m=CodexAssistant.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;}
    static Object invoke(CodexAssistant core,String name,Class<?>[] types,Object...args)throws Exception{try{return method(name,types).invoke(core,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception)throw (Exception)e.getCause();if(e.getCause() instanceof Error)throw (Error)e.getCause();throw e;}}
    static Object get(CodexAssistant core,String name)throws Exception{Field f=CodexAssistant.class.getDeclaredField(name);f.setAccessible(true);return f.get(core);}
    static void set(CodexAssistant core,String name,Object value)throws Exception{Field f=CodexAssistant.class.getDeclaredField(name);f.setAccessible(true);f.set(core,value);}
    static boolean predicate(String name,String value)throws Exception{return (Boolean)invoke(null,name,new Class<?>[]{String.class},value);}
    static Object allocate(Class<?> type)throws Exception{Class<?> u=Class.forName("sun.misc.Unsafe");Field f=u.getDeclaredField("theUnsafe");f.setAccessible(true);return u.getMethod("allocateInstance",Class.class).invoke(f.get(null),type);}
    static void routing()throws Exception{
        String pending="完整配置当前外流计算";
        for(String message:List.of(
            "请执行30步求解，检查残差和网格质量，将只读证据和代码放报告",
            "请执行30步求解，检查结果时只读分析，将证据和代码放报告",
            "不要重新导入，不重划网格，请执行30步求解",
            "请先检查当前模型，然后执行30步求解，不要重新导入，不重划网格",
            "只读检查残差，然后执行30步求解，不要重划网格",
            "只读检查残差，然后求解30步",
            "先只读检查网格质量，再迭代30步，报告中附证据",
            "只读检查当前模型并执行30步求解",
            "请运行30步，再只读检查质量并给建议")){
            check(!predicate("readOnlyRequest",message),"explicit solve is not read-only: "+message);
            for(String p:List.of("",pending))check((Boolean)invoke(null,"shouldConfigure",new Class<?>[]{String.class,String.class},message,p),"explicit solve reaches planner pending="+!p.isEmpty()+": "+message);
        }
        for(String message:List.of(
            "只读检查当前模型，只给建议，不修改",
            "只读检查网格并给建议，不修改",
            "仅给网格建议，不修改模型，不运行求解",
            "只读分析当前结果，将只读证据和代码放报告，不修改仿真",
            "只读检查当前模型，告诉我运行30步是否合适，不修改",
            "只读检查当前模型，不修改；请估计运行30步后的结果",
            "只给建议：运行30步是否合适？不修改模型",
            "只读检查当前模型，建议运行30步求解，不执行",
            "只读检查残差，然后不要运行30步",
            "只读建议运行30步是否合适，不修改",
            "只读检查，解释为什么运行30步不收敛，不修改")){
            check(predicate("readOnlyRequest",message),"read-only/advice stays read-only: "+message);
            for(String p:List.of("",pending))check(!(Boolean)invoke(null,"shouldConfigure",new Class<?>[]{String.class,String.class},message,p),"read-only does not reach planner pending="+!p.isEmpty()+": "+message);
        }
    }
    static String plan(Map<String,Object> extra){Map<String,Object> p=new LinkedHashMap<>();p.put("explanation","fixture");p.put("complete",false);for(String k:List.of("calls","actions","changes","unsupported"))p.put(k,List.of());p.putAll(extra);return ConfigurationEngine.json(p);}
    static void repeatedAuthorization()throws Exception{
        String response=plan(Map.of("explanation","当前为SA，目标为SST。替换冲突模型需用户授权。","unsupported",List.of("需要用户明确授权 replace_conflicting_models=true 才能从SA切换到SST。请确认使用configure_physics_profile。")));
        for(String directive:List.of("将外流场物理模型从SA切换为SST，保留0.75Mach、高度10km、+X来流和现有边界，保持网格不变，不运行CFD。","请把湍流模型改为SST并保存","Switch the turbulence model from SA to SST and retain current boundary conditions."))check((Boolean)invoke(null,"repeatsExistingAuthorization",new Class<?>[]{String.class,String.class},response,directive),"explicit model target does not require repeating SDK replacement permission: "+directive);
        for(String directive:List.of("是否应该从SA切换为SST？","如果要切换为SST，需要注意什么？","保持当前SA，不要切换模型","只读检查SA和SST的区别，不修改","允许检查模型，但尚未授权执行修改"))check(!(Boolean)invoke(null,"repeatsExistingAuthorization",new Class<?>[]{String.class,String.class},response,directive),"question/conditional/no-change directive does not grant model replacement: "+directive);
        for(String extra:List.of("请确认是否授权删除源CAD并重新导入，replace_conflicting_models=true后切换SST","需要用户明确授权 replace_conflicting_models=true切换SST，但缺少目标连续体ID","请确认目标入口速度数值后再配置SST")){String clarification=plan(Map.of("unsupported",List.of(extra)));check(!(Boolean)invoke(null,"repeatsExistingAuthorization",new Class<?>[]{String.class,String.class},clarification,"将SA切换为SST"),"additional geometry scope or missing facts remain a real clarification: "+extra);}
        check(!(Boolean)invoke(null,"repeatsExistingAuthorization",new Class<?>[]{String.class,String.class},plan(Map.of("unsupported",List.of("需要用户授权 replace_conflicting_models=true以改为SA"))),"将当前模型切换为SST"),"model request for opposite target is not covered by current target authorization");
    }
    static void completedFeedback()throws Exception{
        String prefix="本轮外流场已完成并通过原生检查，未重复执行相同重建：";
        check(CodexAssistant.completedTransactionFeedback(prefix+REPLAY_PROOF).equals(REPLAY_PROOF),"only native saved-transaction proof selects positive review feedback");
        for(String failure:List.of("ordinary error "+REPLAY_PROOF,"model says "+prefix+REPLAY_PROOF,prefix+"{}",prefix+REPLAY_PROOF.replace("\"ready\":true","\"ready\":false"),prefix+REPLAY_PROOF.replace("\"blocked\":false","\"blocked\":true"),prefix+REPLAY_PROOF.replace("completed_workflow_replay","different_guard")))check(CodexAssistant.completedTransactionFeedback(failure).isEmpty(),"malformed/unverified/model-text or genuine failure stays on normal recovery route");
        String outputPrefix="原生完成读回已通过，未删除本轮刚刚完成的新输出：";
        check(CodexAssistant.completedTransactionFeedback(outputPrefix+OUTPUT_PROOF).equals(OUTPUT_PROOF),"inverse deletion uses the same verified saved-transaction review");
        check(CodexAssistant.completedTransactionFeedback(RC13_OUTPUT_REJECTION).contains("star.common.Region_736581"),"exact RC13 protected new-region rejection receives positive actual proof");
        for(String failure:List.of(prefix+OUTPUT_PROOF,outputPrefix+REPLAY_PROOF,"model says "+outputPrefix+OUTPUT_PROOF,outputPrefix+"{}",outputPrefix+OUTPUT_PROOF.replace("\"ready\":true","\"ready\":false"),outputPrefix+OUTPUT_PROOF.replace("\"blocked\":false","\"blocked\":true"),outputPrefix+OUTPUT_PROOF.replace("prepare_external_aero","different_workflow"),outputPrefix+OUTPUT_PROOF.replace("\"target_scope\":{","\"not_target_scope\":{")))check(CodexAssistant.completedTransactionFeedback(failure).isEmpty(),"inverse deletion positive route requires exact native prefix/guard and complete verified proof");
    }
    static void protocolMisconceptions(){
        check(CodexAssistant.planningProtocolFeedback(RC14_LONG_DEPENDENCY_UNSUPPORTED).contains("verified_controller_stage_protocol"),"exact RC14 refusal is current stage protocol misunderstanding");
        for(String text:List.of("每轮只允许一个操作，需要允许分轮执行","only one action per turn; too many steps","流程过长，需要逐轮执行，建议允许分轮删除"))check(!CodexAssistant.planningProtocolFeedback(plan(Map.of("unsupported",List.of(text)))).isEmpty(),"observed single-action/long-stage permission misconception may be reviewed once");
        for(String text:List.of("缺少入口速度，单action/轮也不能继续","未授权删除源CAD，流程过长需分轮执行","permission denied even with one action per turn","模型接口不支持所需几何修复","尚未提供目标Region，流程过长需分轮","不能确认CAD是否闭合","此处存在安全限制，需要允许分轮执行"))check(CodexAssistant.planningProtocolFeedback(plan(Map.of("unsupported",List.of(text)))).isEmpty(),"true unknown input/permission/capability limitation is not overridden: "+text);
        check(CodexAssistant.planningProtocolFeedback(COMPLETE).isEmpty()&&CodexAssistant.planningProtocolFeedback(EMPTY).isEmpty(),"protocol review applies only to explicit unsupported terminal plans");
    }
    static void emptyPlans()throws Exception{
        check(predicate("emptyProgressPlan",EMPTY),"complete=false with empty lists is empty progress");
        check(predicate("emptyProgressPlan","{\"complete\":false}"),"missing operation lists with complete=false is empty progress");
        for(String key:List.of("calls","actions","changes","unsupported"))check(!predicate("emptyProgressPlan",plan(Map.of(key,List.of(Map.of("fixture",true))))),"nonempty "+key+" is not empty progress");
        for(String value:List.of(COMPLETE,BLOCKED,"not-json","{\"complete\":true}","{}","{\"complete\":\"false\"}"))check(!predicate("emptyProgressPlan",value),"completed/blocked/malformed/unspecified is not empty progress: "+value);
    }
    static void symmetry()throws Exception{
        List<String> negatives=List.of("全模，不裁切，不添加对称面，完整配置气动计算","全模，不使用对称面，完整气动计算","全模，不创建对称边界，完整气动计算","全模，无需对称面，完整气动计算","full aircraft without symmetry boundary, complete 气动计算","full aircraft no symmetry, complete 气动计算");
        for(String goal:negatives){Map<String,Object> p=CaseReadiness.evaluate(List.of(),List.of(),goal,List.of());Map<?,?> requirements=(Map<?,?>)p.get("goal_requirements");check(Boolean.FALSE.equals(requirements.get("nonempty_symmetry")),"negative symmetry requirement: "+goal);check(!String.valueOf(p.get("incomplete_reasons")).contains("目标要求对称面"),"no invented symmetry blocker: "+goal);}
        Map<String,Object> p=CaseReadiness.evaluate(List.of(),List.of(),"半模，创建对称面y=0，完整配置气动计算",List.of());check(Boolean.TRUE.equals(((Map<?,?>)p.get("goal_requirements")).get("nonempty_symmetry")),"explicit positive symmetry requirement remains enforced");
    }
    static void solveAuthorization(){
        check(SimulationActions.solveAuthorized("执行150步，不要创建额外后处理阻挡迭代",150),"postprocessing prohibition does not revoke explicit 150-step solve");
        check(!SimulationActions.solveAuthorized("不要运行150步",150),"direct 150-step solve prohibition revokes solve authorization");
    }
    public static final class FakeSimulation extends Simulation{
        private FakeSimulation(){super((ClientServerObjectKey)null);}
        @Override public boolean isValid(){return true;}@Override public boolean isBusy(){return false;}@Override public boolean isMeshing(){return false;}@Override public boolean isReadOnlyFile(){return false;}
        @Override public String getSessionPath(){return out.resolve("never-written.sim").toString();}@Override public String getPresentationName(){return "Controller059 synthetic";}
        @Override public star.common.SimulationIterator getSimulationIterator(){throw new IllegalStateException("No STAR server exists in fixture");}
    }
    public static final class FakeEngine{
        final AtomicInteger applies=new AtomicInteger(),begins=new AtomicInteger(),duplicateSolverRejects=new AtomicInteger();final List<String> reads=new CopyOnWriteArrayList<>(),plans=new CopyOnWriteArrayList<>();
        volatile String outcome="idle",note="";volatile boolean active;volatile String currentGoal="";volatile int closureMode;
        final List<Map<?,?>> metrics=new CopyOnWriteArrayList<>();
        volatile boolean repeatSolverGuard,solverApplied;volatile int genericPreviewErrors;
        volatile int requiredApplies;volatile boolean permanentlyIncomplete,protectFreshOutput,protectWorkflowReplay,deletedLedger,allowConflict=true;
        final List<String> rejections=new CopyOnWriteArrayList<>();
        final AtomicInteger completionChecks=new AtomicInteger(),planningScans=new AtomicInteger(),fullScans=new AtomicInteger(),geometryReads=new AtomicInteger();
        public String completionStatus(){completionChecks.incrementAndGet();boolean ready=!permanentlyIncomplete&&applies.get()>=requiredApplies;return ConfigurationEngine.json(Map.of("ready",ready,"complete",ready,"blocked",false,"incomplete_reasons",ready?List.of():List.of("native fixture: target has no nonempty symmetry boundary"),"readiness",Map.of("fixture",true)));}
        public void beginTask(String goal,String path){currentGoal=goal;begins.incrementAndGet();outcome="planning";active=true;solverApplied=false;}
        public String automaticPlan(){throw new AssertionError("Forbidden native automaticPlan");}
        public String refreshPlanningCatalog(){planningScans.incrementAndGet();return "fixture:p scalar; fixture:solver accepts run_solver steps=30; no geometry or mesh actions";}
        public String refreshCatalog(){fullScans.incrementAndGet();return "fixture:p scalar; fixture:solver accepts run_solver steps=30; no geometry or mesh actions";}
        public String initialCatalog(String goal){return "fixture:p=2, fixture:solver state=fixture-state";}public String taskHistory(){return solverApplied?"[{\"kind\":\"run_solver\",\"status\":\"saved\",\"steps\":30}]":"[]";}
        public String engineeringPolicy(){return "Synthetic only; selected model must provide plan.";}
        public String readiness(){reads.add("readiness");return "fixture-readiness";}public String geometryContext(){geometryReads.incrementAndGet();return "fixture-geometry";}public String engineeringContext(String goal){return "fixture-engineering";}
        public String actionHelp(String query){reads.add("action_help:"+query);return "help:"+query;}
        public void recordMetric(Map<?,?> metric){metrics.add(new LinkedHashMap<>(metric));}public void recordInspection(String scope,String query,int offset,String result){if(scope.equals("model_response"))plans.add(result);if(scope.equals("preflight_rejection"))rejections.add(result);}
        public String preview(String plan)throws Exception{if(protectFreshOutput&&plan.contains("delete_object"))throw new IOException("原生完成读回已通过，未删除本轮刚刚完成的新输出："+OUTPUT_PROOF);if(protectWorkflowReplay&&plan.contains("repair_external_aero"))throw new IOException("本轮外流场已完成并通过原生检查，未重复执行相同重建："+REPLAY_PROOF);if(repeatSolverGuard&&solverApplied&&plan.contains("run_solver")){duplicateSolverRejects.incrementAndGet();throw new IOException("本次请求已执行求解，不自动追加步数");}if(genericPreviewErrors>0){genericPreviewErrors--;throw new IOException("fixture expected_state changed; reread required");}Map<?,?> p=(Map<?,?>)CodexAssistant.parseJson(plan);return (p.get("actions") instanceof List&&!((List<?>)p.get("actions")).isEmpty())||(p.get("changes") instanceof List&&!((List<?>)p.get("changes")).isEmpty())?"synthetic preview":"";}
        public boolean isLongOperation(String p){return false;}
        public Map<String,Object> apply(String plan){if(!plan.equals(CHANGE)&&!plan.equals(SOLVE)&&!plan.equals(REBUILD)&&!plan.equals(RECREATE)&&!plan.equals(CLOSURE_REPAIR)&&!(repeatSolverGuard&&plan.equals(SOLVE_STAGE)))throw new AssertionError("Unexpected apply: "+plan);applies.incrementAndGet();boolean followup=repeatSolverGuard&&plan.equals(SOLVE_STAGE);if(followup)solverApplied=true;return Map.of("summary",followup?"synthetic solver status=saved steps=30":"synthetic applied","report","fixture report","failed",false,"continue",followup,"progress",true);}
        public Map<String,Object> finish(String value,boolean incomplete){outcome=incomplete||permanentlyIncomplete||applies.get()<requiredApplies?"incomplete":"completed";active=false;note=value;return Map.of("summary",value,"report","fixture report");}
        public String taskStatus(){return outcome;}public String chatSummary(){return outcome+": "+note;}public String latestReport(){return note;}public boolean hasActiveTask(){return active;}public void requestStop(){}
        public String transactionClosure(){if(closureMode==0)return "{\"certified\":false}";Map<String,Object> coverage=ConfigurationEngine.externalClosureGoalCoverage(currentGoal,CLOSURE_RECOMMENDED,CLOSURE_ACTUAL);if(!Boolean.TRUE.equals(coverage.get("covered")))return ConfigurationEngine.json(Map.of("certified",false,"goal_coverage",coverage));return ConfigurationEngine.json(Map.of("certified",true,"kind",closureMode==5?"unverified_model_claim":"verified_external_transaction_closure","goal_coverage",Map.of("covered",closureMode!=2),"current_completion",Map.of("ready",closureMode!=4,"blocked",false),"fresh_native_verification",Map.of("verified",closureMode!=3)));}
        public String planningLedger(){return deletedLedger?DELETION_LEDGER:ConfigurationEngine.json(Map.of("saved_action_count",applies.get(),"confirmed_deletions",List.of()));}
        public String unsupportedFactConflict(String response){return deletedLedger&&allowConflict&&response.contains("star.common.Region_1783")?ConfigurationEngine.json(Map.of("kind","unsupported_references_confirmed_deleted_target","cited_absent_ids",List.of("star.common.Region_1783"),"current_task_ledger",DELETION_LEDGER)):"";}
    }
    static final class Endpoint implements AutoCloseable{
        final HttpServer server;final ExecutorService pool=Executors.newCachedThreadPool();final List<Map<?,?>> requests=new CopyOnWriteArrayList<>();final List<String> paths=new CopyOnWriteArrayList<>();volatile List<String> answers=List.of(COMPLETE);
        Endpoint()throws IOException{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);server.createContext("/",e->{try{String body=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);Map<?,?> data=(Map<?,?>)CodexAssistant.parseJson(body);int i=requests.size();requests.add(data);paths.add(e.getRequestURI().getRawPath());if(!("Bearer "+KEY).equals(e.getRequestHeaders().getFirst("Authorization")))throw new AssertionError("Unexpected key");String answer=answers.get(Math.min(i,answers.size()-1));byte[] bytes=("{\"choices\":[{\"message\":{\"content\":"+ConfigurationEngine.json(answer)+"}}]}").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);}catch(Throwable t){t.printStackTrace();}finally{e.close();}});server.start();}
        String address(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/Selected%2F057/Exact";}
        void reset(String... responses){requests.clear();paths.clear();answers=List.of(responses);}
        String prompt(int index){return String.valueOf(requests.get(index).get("messages"));}
        public void close(){server.stop(0);pool.shutdownNow();}
    }
    static final class Ui implements AutoCloseable{
        CodexAssistant core;FakeEngine engine=new FakeEngine();JFrame frame;
        Ui(Endpoint endpoint)throws Exception{SwingUtilities.invokeAndWait(()->{try{core=new CodexAssistant();frame=new JFrame("Controller059 synthetic fixture");set(core,"simulation",allocate(FakeSimulation.class));set(core,"configurationEngine",engine);set(core,"frame",frame);invoke(core,"buildUi",new Class<?>[0]);((JCheckBox)get(core,"includeSkill")).setSelected(false);((JCheckBox)get(core,"includeContext")).setSelected(false);((JComboBox<?>)get(core,"provider")).setSelectedIndex(4);((JTextField)get(core,"endpoint")).setText(endpoint.address());((JTextField)get(core,"model")).setText(MODEL);@SuppressWarnings("unchecked") Map<String,char[]> keys=(Map<String,char[]>)get(core,"apiKeys");keys.put(String.valueOf(((JComboBox<?>)get(core,"provider")).getSelectedItem()),KEY.toCharArray());frame.setSize(900,650);frame.setVisible(true);}catch(Exception e){throw new IllegalStateException(e);}});}
        void send(String message)throws Exception{SwingUtilities.invokeAndWait(()->{try{set(core,"pendingConfigurationGoal","");((JTextArea)get(core,"prompt")).setText(message);invoke(core,"sendMessage",new Class<?>[0]);}catch(Exception e){throw new IllegalStateException(e);}});long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){boolean[] b={true};SwingUtilities.invokeAndWait(()->b[0]=core.isRequestInProgress());if(!b[0])return;Thread.sleep(25);}throw new AssertionError("Controller timeout "+engine.outcome+" "+engine.note);}
        public void close()throws Exception{SwingUtilities.invokeAndWait(()->{try{invoke(core,"stopProcess",new Class<?>[0]);frame.dispose();}catch(Exception e){throw new IllegalStateException(e);}});}
    }
    static void transport(Endpoint e,String label,int expected){check(e.requests.size()==expected,label+" bounded selected-model calls expected="+expected+" actual="+e.requests.size());check(e.requests.stream().allMatch(r->MODEL.equals(r.get("model")))&&e.paths.stream().allMatch(p->p.equals("/Selected%2F057/Exact")),label+" preserves selected endpoint/model");check(e.requests.stream().allMatch(r->{List<?> m=(List<?>)r.get("messages");return m.size()==2&&"system".equals(((Map<?,?>)m.get(0)).get("role"))&&String.valueOf(((Map<?,?>)m.get(0)).get("content")).contains("calls/actions/changes/unsupported全部为空")&&"user".equals(((Map<?,?>)m.get(1)).get("role"));}),label+" planner protocol is authoritative system message with separate user evidence");check(e.requests.stream().allMatch(r->{List<?> m=(List<?>)r.get("messages");String text=String.valueOf(((Map<?,?>)m.get(m.size()-1)).get("content"));return text.lastIndexOf("<current_task_ledger>")>text.lastIndexOf("</checked_data>")&&text.contains("</current_task_ledger>");}),label+" every actual planning call ends with current native ledger after older checked_data");}
    static void protocol()throws Exception{
        CodexAssistant c=new CodexAssistant();String address="https://selected.example.invalid/v1";
        Map<?,?> normal=(Map<?,?>)CodexAssistant.parseJson(String.valueOf(invoke(c,"apiRequestBody",new Class<?>[]{String.class,String.class,String.class,boolean.class},address,MODEL,"normal chat",false)));
        List<?> normalMessages=(List<?>)normal.get("messages");check(normalMessages.size()==1&&"user".equals(((Map<?,?>)normalMessages.get(0)).get("role")),"ordinary chat/connection payload has no planning system protocol");
        @SuppressWarnings("unchecked") ThreadLocal<Boolean> marker=(ThreadLocal<Boolean>)get(null,"CONFIGURATION_PLANNER_CALL");marker.set(true);
        try{Map<?,?> planning=(Map<?,?>)CodexAssistant.parseJson(String.valueOf(invoke(c,"apiRequestBody",new Class<?>[]{String.class,String.class,String.class,boolean.class},address,MODEL,"real evidence",false)));List<?> messages=(List<?>)planning.get("messages");check(messages.size()==2&&"system".equals(((Map<?,?>)messages.get(0)).get("role")),"planner payload uses standard system/user roles only");String rules=String.valueOf(((Map<?,?>)messages.get(0)).get("content"));check(rules.contains("调用形式")&&rules.contains("结构动作形式")&&rules.contains("数值形式")&&rules.contains("完成形式"),"protocol balances read/action/change/completion forms");check(rules.contains("同一行读回的state")&&rules.contains("scope=operations")&&rules.contains("scope=writable只查"),"protocol distinguishes target state pairs and correct structural inspection scope");check(MODEL.equals(planning.get("model"))&&!planning.containsKey("tools")&&!planning.containsKey("response_format"),"protocol keeps selected model and avoids new provider-specific fields");}finally{marker.remove();}
        check(marker.get()==null,"planner marker removed after scoped request construction");
    }
    static void controller()throws Exception{
        try(Endpoint e=new Endpoint();Ui ui=new Ui(e)){
            e.reset("{bad-json",CHANGE);ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"malformed JSON repair",2);check(ui.engine.applies.get()==1,"only repaired selected response applies once");check(e.requests.size()>1&&e.prompt(1).contains("invalid_response")&&e.prompt(1).contains("单个合法JSON"),"malformed model response gets directed JSON repair prompt");
            e.reset("{bad-one","{bad-two","{bad-three");ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"persistent malformed JSON",3);check(ui.engine.applies.get()==1&&ui.engine.outcome.equals("incomplete"),"three malformed plans stop without applying");
            e.reset(EMPTY,EMPTY,CHANGE);ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"empty progress repair",3);check(ui.engine.applies.get()==2,"two empty progress followups can recover selected plan");check(e.requests.size()>1&&e.prompt(1).contains("没有calls、actions、changes"),"empty progress gets directed followup");
            e.reset(EMPTY,EMPTY,EMPTY);ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"empty progress bound",3);check(ui.engine.applies.get()==2&&ui.engine.outcome.equals("incomplete"),"repeated empty progress stops after two directed followups");
            e.reset(BLOCKED);ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"specific unsupported",1);check(ui.engine.applies.get()==2&&ui.engine.outcome.equals("incomplete"),"specific unsupported is preserved without retry");
            e.reset(COMPLETE);ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"already completed",1);check(ui.engine.applies.get()==2&&ui.engine.outcome.equals("completed"),"complete=true empty plan finishes without retry");
            List<Map<String,Object>> calls=new ArrayList<>();for(int i=0;i<7;i++)calls.add(Map.of("scope","action_help","query","fixture_"+i,"offset",0));e.reset(plan(Map.of("calls",calls)),CHANGE);int reads=ui.engine.reads.size();ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"seven readonly checks batched",2);for(int i=0;i<7;i++){String q="fixture_"+i;check(ui.engine.reads.subList(reads,ui.engine.reads.size()).contains("action_help:"+q),"batch reads "+q);check(e.requests.size()>1&&e.prompt(1).contains("help:"+q),"batch feeds result "+q+" to selected model");}check(ui.engine.applies.get()==3,"seven checks lead to exactly one selected scalar apply");
            e.reset(SOLVE);ui.send("请执行30步求解，不要重新导入，不重划网格，将只读证据和代码放报告");transport(e,"explicit thirty-step solve",1);check(ui.engine.applies.get()==4,"explicit thirty-step selected solver plan applies once in synthetic engine");check(ui.engine.plans.get(ui.engine.plans.size()-1).equals(SOLVE),"solver plan has only run_solver steps=30 and no import/mesh actions");
            ui.engine.repeatSolverGuard=true;int before=ui.engine.applies.get();e.reset(SOLVE_STAGE,SOLVE_STAGE);ui.send("请执行30步求解，并检查当前残差，将检查结果写入报告");transport(e,"duplicate solver after saved action",2);check(ui.engine.applies.get()==before+1,"duplicate solver plan cannot apply after first solver action is saved");check(ui.engine.duplicateSolverRejects.get()==1,"duplicate solver guard is previewed exactly once without precheck retries");check(ui.engine.outcome.equals("incomplete"),"duplicate solver finishes incomplete rather than claiming whole task completed");check(ui.engine.note.contains("已经执行并保存"),"duplicate solver finish preserves saved solve fact");check(ui.engine.note.contains("跳过")&&ui.engine.note.contains("重复"),"duplicate solver finish explains skipped repeated instruction");check(ui.engine.note.contains("未验证")&&ui.engine.note.contains("未自动追加"),"duplicate solver finish preserves unverified checks and no extra iterations");check(!ui.engine.note.contains("配置任务停止")&&!ui.engine.note.contains("失败"),"duplicate solver does not label saved solve as generalized failure");Files.writeString(out.resolve("repeated-solver-finish.txt"),ui.engine.note);
            ui.engine.repeatSolverGuard=false;ui.engine.genericPreviewErrors=1;before=ui.engine.applies.get();e.reset(CHANGE,CHANGE);ui.send("把fixture标量改成3，只修改这一个参数");transport(e,"other preview error retry",2);check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"unrelated precheck error retains retry and applies corrected selected plan");check(e.requests.size()>1&&e.prompt(1).contains("rejected_plan")&&e.prompt(1).contains("expected_state changed"),"unrelated precheck error still receives directed validation repair");
            // An empty answer is just as invalid after a rejected action as on
            // the first pass. The action help here is real fake-engine readback,
            // not a model-authored checked_data claim.
            before=ui.engine.applies.get();ui.engine.genericPreviewErrors=1;e.reset(SOLVE,EMPTY,SOLVE);
            ui.send("执行30步，按照刚才的检查修正配置，然后保存");transport(e,"empty response during validation recovery",3);
            check(ui.engine.applies.get()==before+1,"empty validation-recovery answer cannot silently finish without applying");
            check(e.prompt(1).contains("verified_action_help")&&e.prompt(1).contains("help:run_solver"),"rejected action gets actual action-specific signature");
            check(e.prompt(2).contains("没有calls、actions、changes")&&e.prompt(2).contains("checked_data"),"idle retry after validation gets grounded execution reminder");
            check(e.prompt(1).contains("case_readiness")&&e.prompt(1).contains("engineering_recommendations"),"validation retry retains engineering and actual readiness context");
            before=ui.engine.applies.get();ui.engine.genericPreviewErrors=1;e.reset(SOLVE,"Here is the plan: {bad",SOLVE);
            ui.send("再执行30步，并保存修改");transport(e,"malformed response during validation recovery",3);
            check(ui.engine.applies.get()==before+1,"malformed validation response is repaired by selected model once before apply");
            check(e.prompt(2).contains("invalid_response"),"validation-format repair carries invalid answer to selected model");
            // A claimed no-op completion with a missing native postcondition must
            // return evidence to the selected model, not ask the user to repeat.
            before=ui.engine.applies.get();ui.engine.requiredApplies=before+1;e.reset(COMPLETE,CHANGE);
            ui.send("创建当前半模的对称边界并保存");transport(e,"no-op completion corrected from native evidence",2);
            check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"native missing symmetry yields real selected-model correction before finalization");
            check(e.prompt(1).contains("verified_completion_feedback")&&e.prompt(1).contains("target has no nonempty symmetry"),"postcondition correction sees native failure evidence");
            // Successful writes can still leave an incomplete configuration. A
            // second targeted selected response is allowed without reauthorization.
            before=ui.engine.applies.get();ui.engine.requiredApplies=before+2;e.reset(CHANGE,CHANGE);
            ui.send("修改配置并补齐当前半模要求，完成后保存");transport(e,"saved action plus incomplete postcondition",2);
            check(ui.engine.applies.get()==before+2&&ui.engine.outcome.equals("completed"),"saved partial result can be corrected in same request");
            check(e.prompt(1).contains("verified_completion_feedback")&&e.prompt(1).contains("不要重复已保存"),"postcondition retry asks for missing changes rather than replaying the whole workflow");
            // Bound postcondition recovery independently: repeated unsupported
            // optimistic completion statements may not cause an API runaway.
            before=ui.engine.applies.get();ui.engine.requiredApplies=0;ui.engine.permanentlyIncomplete=true;e.reset(COMPLETE);
            ui.send("完成当前模型配置并保存");transport(e,"persistent native postcondition failure",3);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("incomplete"),"two postcondition repair attempts finish incomplete without writes");
            e.reset(BLOCKED);ui.send("完成当前模型配置并保存");transport(e,"specific unsupported does not trigger speculative postcondition repair",1);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("incomplete"),"concrete unsupported stops without extra API requests");
            ui.engine.permanentlyIncomplete=false;ui.engine.requiredApplies=0;
            // Independent later edits remain usable after incomplete requests.
            for(int turn=0;turn<3;turn++){before=ui.engine.applies.get();e.reset(CHANGE);ui.send("现在修改另一个数值并保存，第"+turn+"次");transport(e,"later independent write "+turn,1);check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"later write remains available after prior incomplete turn "+turn);}
            // Preserve original user constraints across later corrected values
            // and referential delete/rebuild turns, without trusting assistant text.
            e.reset(CHANGE);ui.send("机翼半模，对称面xoz；Ma0.75，高度2km，创建域并保存");
            e.reset(CHANGE);ui.send("改为飞行高度10km，重新创建外流场并保存");
            check(e.prompt(0).contains("prior_user_directives")&&e.prompt(0).contains("机翼半模，对称面xoz")&&e.prompt(0).contains("Ma0.75")&&e.prompt(0).contains("高度10km"),"later altitude change preserves earlier user half-model/symmetry/Mach evidence");
            e.reset(CHANGE);ui.send("删除当前流体域，按照我上一步指令继续");
            check(e.prompt(0).contains("机翼半模，对称面xoz")&&e.prompt(0).contains("改为飞行高度10km")&&e.prompt(0).contains("按照我上一步指令继续"),"referential delete/rebuild retains original geometry and latest flight condition");
            @SuppressWarnings("unchecked") List<String[]> turns=(List<String[]>)get(ui.core,"history");String userOnly=String.valueOf(invoke(null,"configurationUserHistory",new Class<?>[]{List.class},turns));
            check(!userOnly.contains("fixture complete")&&!userOnly.contains("synthetic applied"),"durable constraint context excludes assistant claims and synthetic execution text");
            before=ui.engine.applies.get();ui.engine.protectFreshOutput=true;
            String deleting="{\"explanation\":\"delete stale region\",\"complete\":false,\"actions\":[{\"type\":\"delete_object\",\"id\":\"fixture:new-region\",\"expected_state\":\"fixture-state\",\"reason\":\"rebuild\"}],\"changes\":[],\"unsupported\":[]}";
            int outputGeometryBefore=ui.engine.geometryReads.get(),outputReadsBefore=ui.engine.reads.size();
            e.reset(deleting,COMPLETE);ui.send("删除旧流体域并重建配置，保留已经完成的新对象");transport(e,"native completed-output guard correction",2);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("completed"),"selected model can stop repeated deletion after verified native completion evidence");
            check(e.prompt(1).contains("completed_workflow_output")&&e.prompt(1).contains("verified_completion"),"same selected planner receives native guard receipt rather than a local replacement plan");
            check(e.prompt(1).contains("verified_completed_transaction")&&!e.prompt(1).contains("方案预检失败（未写入）")&&!e.prompt(1).contains("<verified_action_help"),"protected inverse deletion is positive saved-success review, not execution failure or action-help recovery");
            check(ui.engine.geometryReads.get()-outputGeometryBefore==1&&ui.engine.reads.subList(outputReadsBefore,ui.engine.reads.size()).stream().noneMatch(r->r.startsWith("action_help:")),"protected inverse deletion avoids unchanged geometry reread and redundant action help");
            check(ui.engine.rejections.stream().anyMatch(r->r.contains("completed_workflow_output")&&r.contains("rejected_plan=")),"actual protected-output preflight rejection is recorded with bounded submitted plan");
            check(ui.engine.rejections.stream().noneMatch(r->r.contains(KEY)),"preflight rejection details contain no API key");
            before=ui.engine.applies.get();ui.engine.requiredApplies=before+1;
            e.reset(deleting,CHANGE);ui.send("保留已经重建的新流体域，继续修改另一个尚未完成的数值");transport(e,"protected new output with separate remaining goal",2);
            check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"protected new output does not auto-finish another unmet requested modification");
            check(e.prompt(1).contains("remaining goals")&&e.prompt(1).contains("生成对应新动作或必要查询"),"inverse-delete proof still permits selected model to handle genuine remaining goals");
            ui.engine.protectFreshOutput=false;ui.engine.requiredApplies=0;
            before=ui.engine.applies.get();ui.engine.protectWorkflowReplay=true;
            int replayGeometryBefore=ui.engine.geometryReads.get(),replayReadsBefore=ui.engine.reads.size();
            e.reset(plan(Map.of("actions",List.of(Map.of("type","repair_external_aero","id","fixture:simulation","reason","repeat repaired domain")))),COMPLETE);
            ui.send("修正当前半模外流场的对称面，保留已保存的配置");transport(e,"completed native workflow replay review",2);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("completed"),"selected model can end already-completed transaction after replay evidence without rerunning it");
            check(e.prompt(1).contains("verified_completed_transaction")&&e.prompt(1).contains("completed_workflow_replay")&&e.prompt(1).contains("domain_bounds_m"),"same selected model receives actual repaired geometry rather than original complaint as current fact");
            check(!e.prompt(1).contains("方案预检失败（未写入）")&&!e.prompt(1).contains("<verified_action_help"),"native completed-transaction review is not framed as failed execution or missing schema");
            check(ui.engine.geometryReads.get()-replayGeometryBefore==1&&ui.engine.reads.subList(replayReadsBefore,ui.engine.reads.size()).stream().noneMatch(r->r.startsWith("action_help:")),"completed-transaction review avoids unchanged geometry reread and redundant action help");
            before=ui.engine.applies.get();ui.engine.requiredApplies=before+1;
            e.reset(plan(Map.of("actions",List.of(Map.of("type","repair_external_aero","id","fixture:simulation","reason","repeat repaired domain")))),CHANGE);
            ui.send("保留已完成的半模外流场，继续补齐另一个尚未修改的数值");transport(e,"completed transaction with remaining goal",2);
            check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"completed native transaction does not auto-finish a separate remaining requested modification");
            check(e.prompt(1).contains("remaining goals")&&e.prompt(1).contains("生成对应新动作或必要查询"),"same selected planner is explicitly allowed to address remaining unmet user goals");ui.engine.protectWorkflowReplay=false;ui.engine.requiredApplies=0;
            before=ui.engine.applies.get();String permission=plan(Map.of("unsupported",List.of("需要用户明确授权 replace_conflicting_models=true 才能将SA切换为SST，请确认。")));
            e.reset(permission,CHANGE);ui.send("将当前湍流模型从SA切换为SST，保持网格不变，不运行CFD");transport(e,"explicit target-model replacement permission",2);
            check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"authorized target replacement is generated by same selected model after unnecessary confirmation request");
            check(e.prompt(1).contains("用户已明确授权当前目标"),"target-model repermission feedback does not synthesize native action payload");
            ui.engine.deletedLedger=true;ui.engine.allowConflict=true;before=ui.engine.applies.get();ui.engine.requiredApplies=before+1;
            String help=plan(Map.of("calls",List.of(Map.of("scope","action_help","query","repair_external_aero"))));
            e.reset(help,RC12_STALE_DELETE_UNSUPPORTED,REBUILD);
            ui.send("删除当前流体域，按照我上一步指令继续。本次重建先不执行网格和CFD。");transport(e,"exact RC12 stale deleted-Region claim after action_help",3);
            check(ui.engine.applies.get()==before+1&&ui.engine.plans.contains(REBUILD)&&ui.engine.outcome.equals("completed"),"same selected model can submit rebuild after reconciling exact failed RC12 unsupported response");
            check(e.prompt(1).contains("star.common.Region_1783")&&e.prompt(1).contains("\"exists_now\":false")&&e.prompt(1).contains("1.6891245468832494"),"native deleted identity and previous measured reference length persist after help-only turn");
            check(e.prompt(2).contains("verified_fact_reconciliation")&&e.prompt(2).contains("真实的其他权限限制"),"fact review corrects only proven old-target premise and preserves other genuine blockers");
            before=ui.engine.applies.get();ui.engine.requiredApplies=before+1;e.reset(RC12_STALE_DELETE_UNSUPPORTED);
            ui.send("继续完成已经删除旧区域后的外流场重建");transport(e,"persistent contradictory unsupported is bounded",2);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("incomplete"),"one fact review never loops or replaces selected model with local plan");
            e.reset(BLOCKED);ui.send("继续外流场重建，但先解决缺少的必要工况");transport(e,"genuine unrelated missing input remains unsupported",1);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("incomplete"),"unrelated real missing-input limitation is not overridden by saved progress");
            ui.engine.allowConflict=false;e.reset(RC12_STALE_DELETE_UNSUPPORTED);ui.send("继续执行外流场重建；若有真实权限限制就报告");transport(e,"unverified or still-existing cited target is not corrected",1);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("incomplete"),"without native absence conflict proof no contradiction retry is forced");
            ui.engine.deletedLedger=false;ui.engine.allowConflict=true;ui.engine.requiredApplies=0;
            before=ui.engine.applies.get();ui.engine.requiredApplies=before+1;e.reset(RC14_LONG_DEPENDENCY_UNSUPPORTED,RECREATE);
            ui.send("删除当前流体域，按照我上一步指令继续。本次重建先不执行网格和CFD。");transport(e,"native atomic recreation after exact RC14 current dependency refusal",2);
            check(ui.engine.applies.get()==before+1&&ui.engine.plans.contains(RECREATE)&&ui.engine.outcome.equals("completed"),"selected model supplies public recreate flag and native payload, no local orchestration or replacement plan");
            check(e.prompt(1).contains("recreate_region=true")&&e.prompt(1).contains("actual.region_recreation")&&e.prompt(1).contains("流程过长")&&e.prompt(1).contains("verified_controller_stage_protocol"),"one protocol fact review corrects exact RC14 long-dependency refusal without treating it as missing permission");
            check(e.prompt(0).contains("默认false")&&e.prompt(0).contains("普通修复不得自行设true"),"planner keeps ordinary repair non-destructive and limits recreation flag to explicit current delete/rebuild request");
            before=ui.engine.applies.get();e.reset(RC14_LONG_DEPENDENCY_UNSUPPORTED);ui.send("继续删除当前流体域并重建，保持原始CAD");transport(e,"persistent protocol misconception bounded",2);
            check(ui.engine.applies.get()==before&&ui.engine.outcome.equals("incomplete"),"one shared fact review budget cannot run away or force native recreation");
            ui.engine.requiredApplies=0;ui.engine.closureMode=1;
            before=ui.engine.applies.get();e.reset(CLOSURE_REPAIR,RC18_POST_SUCCESS_REPLAN);ui.send(RC18_CORRECTION_GOAL);transport(e,"exact RC18 post-success model drift prevented by certified native closure",1);
            check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("completed"),"recognized saved native transaction closes once without asking model to reinterpret legitimate boundary scheme");
            check(ui.engine.plans.get(ui.engine.plans.size()-1).equals(CLOSURE_REPAIR),"only selected model initial native repair action executed; no local replacement plan or optional reconfiguration");
            before=ui.engine.applies.get();e.reset(CLOSURE_REPAIR,BLOCKED);ui.send("修改半模外流场和对称面xoz，并创建升力报告");transport(e,"unknown additional report blocks native closure",2);
            check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("incomplete"),"extra unknown requested goal remains with selected model and cannot be dropped by native closure");
            for(int invalid:List.of(2,3,4,5)){ui.engine.closureMode=invalid;before=ui.engine.applies.get();e.reset(CLOSURE_REPAIR,BLOCKED);ui.send(RC18_CORRECTION_GOAL);transport(e,"incomplete or unverified closure proof "+invalid,2);check(ui.engine.applies.get()==before+1&&ui.engine.outcome.equals("incomplete"),"coverage/readiness/fresh-proof/kind must all verify before native completion");}
            ui.engine.closureMode=0;
            check(ui.engine.planningScans.get()>0,"all planner stages and invalid-plan retries use lightweight action catalog");
            check(ui.engine.fullScans.get()==0,"controller does not request full scalar discovery implicitly when fast catalog exists");
            Set<String> phases=new HashSet<>();for(Map<?,?> metric:ui.engine.metrics)if("planning_phase".equals(metric.get("event")))phases.add(String.valueOf(metric.get("phase")));
            check(phases.containsAll(List.of("geometry_context","engineering_context","readiness","action_catalog","plan_preview","validation_catalog","validation_context","completion_readback")),"local geometry/recommendations/readiness/catalog/validation/completion have separate timing evidence");
            check(ui.engine.metrics.stream().filter(m->"planning_phase".equals(m.get("event"))).allMatch(m->m.get("elapsed_seconds") instanceof Number&&Double.isFinite(((Number)m.get("elapsed_seconds")).doubleValue())&&((Number)m.get("elapsed_seconds")).doubleValue()>=0&&m.get("stage") instanceof Number),"phase durations and stage indices are finite measured values");
            check(ui.engine.metrics.stream().anyMatch(m->"model_call".equals(m.get("event"))&&"system".equals(m.get("planner_protocol_role"))&&m.get("provider_usage") instanceof Map),"selected-model latency/usage remains separate from local read phase timing");
            check(ui.engine.metrics.size()<1500,"phase timing records do not recurse or trigger extra model calls");
            Files.writeString(out.resolve("model-request-prompts.txt"),e.requests.toString());
        }
    }
    public static void main(String[] args)throws Exception{
        out=Path.of(args[0]);Files.createDirectories(out);System.setProperty("starccm.assistant.preferenceNode","/local/starccm/assistant/controller059-"+System.nanoTime());
        ProxySelector.setDefault(new ProxySelector(){public List<java.net.Proxy> select(URI uri){if(!List.of("127.0.0.1","localhost").contains(uri.getHost())){EXTERNAL.add(uri.toString());throw new AssertionError("No external API permitted: "+uri);}return List.of(java.net.Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress a,IOException e){}});
        try{for(String group:List.of("routing","emptyPlans","repeatedAuthorization","completedFeedback","protocolMisconceptions","symmetry","solveAuthorization","protocol","controller"))try{switch(group){case "routing":routing();break;case "emptyPlans":emptyPlans();break;case "repeatedAuthorization":repeatedAuthorization();break;case "completedFeedback":completedFeedback();break;case "protocolMisconceptions":protocolMisconceptions();break;case "symmetry":symmetry();break;case "solveAuthorization":solveAuthorization();break;case "protocol":protocol();break;default:controller();}}catch(Throwable t){check(false,group+" threw "+t);t.printStackTrace();}check(EXTERNAL.isEmpty(),"zero external API attempts");check(!Files.exists(out.resolve("never-written.sim")),"zero STAR solver calls or .sim writes");}finally{String end=(FAILURES.isEmpty()?"PASS":"FAIL")+" Controller059Regression "+count+" checks; failures="+FAILURES.size()+"; loopback only, fake engine, test-only key\n";LOG.append(end);Files.writeString(out.resolve("result.txt"),LOG);Files.writeString(out.resolve("failures.txt"),String.join("\n",FAILURES));System.out.print(end);}if(!FAILURES.isEmpty())throw new AssertionError(String.join("\n",FAILURES));
    }
}
