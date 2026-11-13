package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import star.base.neo.*;
import star.base.report.*;
import star.common.*;
import star.meshing.*;

/** Small version-tested operation registry; model output never selects Java methods. */
final class WorkflowOperations {
    private final Simulation sim;
    private final SimulationActions general;
    private final NativeApiBridge nativeApi;
    private final Map<String,ClientServerObject> targets=new LinkedHashMap<>();
    private final Map<String,String> snapshots=new LinkedHashMap<>();
    private volatile MeshOperation runningMesh;
    private String currentGoal="";
    private volatile boolean stopRequested;
    WorkflowOperations(Simulation sim){this.sim=sim;this.general=new SimulationActions(sim);this.nativeApi=new NativeApiBridge(sim,general::rootObjects);}

    static boolean meshRequested(String goal) { return EngineeringIntent.meshDisposition(goal)>0; }
    void refresh() {
        targets.clear();snapshots.clear();general.refresh();
        MeshOperationManager manager=sim.has(MeshOperationManager.class);
        if(manager!=null)for(MeshOperation op:manager.getObjects()) {
            add(op,"mesh_operation");
            if(op instanceof AutoMeshOperation)for(CustomMeshControl control:((AutoMeshOperation)op).getCustomMeshControls().getObjects())add(control,"mesh_control");
        }
        for(Report report:sim.getReportManager().getObjects())add(report,"report");
    }
    private void add(ClientServerObject object,String kind){
        String id=object.getObjectKeyAsString();targets.put(id,object);
        snapshots.put(id,general.expectedState(id));
    }
    private Map<String,Object> describe(ClientServerObject object,String kind)throws Exception { return general.describe(object); }
    /** Additional facts belong to the same canonical object/state, never a second action target. */
    static Map<String,Object> descriptorFields(ClientServerObject object)throws Exception {
        Map<String,Object> row=new LinkedHashMap<>();
        if(object instanceof MeshOperation)row.put("kind","mesh_operation");
        else if(object instanceof CustomMeshControl)row.put("kind","mesh_control");
        else if(object instanceof Report)row.put("kind","report");
        if(object instanceof MeshOperation) {
            MeshOperation op=(MeshOperation)object;
            row.put("inputs",objects(op.getInputGeometryObjects().getObjects()));
            row.put("input_query",String.valueOf(op.getInputGeometryObjects().getQuery()));
            row.put("outputs",objects(op.getOutputPartObjects()));
            row.put("dirty",op.isDirty());row.put("executable",op.isExecuteEnabled(false));
            row.put("native_dependent_operations",objects(op.getDependentOperations()));
            row.put("input_direction","upstream geometry consumed by this operation");
            row.put("output_direction","derived geometry produced by this operation");
            List<MeshOperation> upstream=new ArrayList<>(),downstream=new ArrayList<>();
            MeshOperationManager manager=op.getSimulation().has(MeshOperationManager.class);
            if(manager!=null)for(MeshOperation other:manager.getObjects())if(other!=op){
                if(!Collections.disjoint(op.getInputGeometryObjects().getObjects(),other.getOutputPartObjects()))upstream.add(other);
                if(!Collections.disjoint(other.getInputGeometryObjects().getObjects(),op.getOutputPartObjects()))downstream.add(other);
            }
            row.put("upstream_producer_operations",objects(upstream));row.put("downstream_consumer_operations",objects(downstream));
            row.put("execute_boolean_supported",op instanceof SubtractPartsOperation);
            row.put("execute_mesh_supported",op instanceof AutoMeshOperation);
            row.put("execute_supported",op instanceof AutoMeshOperation||op instanceof SubtractPartsOperation);
            row.put("execution_action",op instanceof AutoMeshOperation?"execute_mesh":op instanceof SubtractPartsOperation?"execute_boolean":"");
        }
        if(object instanceof CustomMeshControl){CustomMeshControl control=(CustomMeshControl)object;
            row.put("operation",control.getAutoMeshBase().toString());row.put("enabled",control.getEnableControl());
            row.put("selection",objects(control.getGeometryObjects().getObjects()));row.put("selection_query",String.valueOf(control.getGeometryObjects().getQuery()));}
        if(object instanceof Report){Report report=(Report)object;
            row.put("units",report.getUnits().getPresentationName());row.put("dimensions",report.getDimensions().toString());
            try{Object parts=object.getClass().getMethod("getParts").invoke(object);
                if(parts instanceof PartGroup){row.put("selection",objects(((PartGroup)parts).getObjects()));row.put("selection_query",String.valueOf(((PartGroup)parts).getQuery()));}}
            catch(NoSuchMethodException ignored){}
            if(report instanceof ExpressionReport)row.put("definition",((ExpressionReport)report).getDefinition());
            if(report instanceof VectorReport){VectorReport vector=(VectorReport)report;
                row.put("direction",vector.getDirection().getDefinition());row.put("coordinate_system",vector.getCoordinateSystem().getPresentationName());}
            if(report instanceof star.flow.MomentReport)row.put("origin",((star.flow.MomentReport)report).getOrigin().getDefinition());
        }
        return row;
    }
    private static List<Object> objects(Collection<?> objects){
        List<Object> result=new ArrayList<>();int count=0;
        for(Object raw:objects){if(++count>1000){result.add("选择集合超过1000项，已截断，不能认定为完整关联");break;}
            if(raw instanceof ClientServerObject){ClientServerObject obj=(ClientServerObject)raw;
                Map<String,Object> item=new LinkedHashMap<>();item.put("id",obj.getObjectKeyAsString());item.put("name",obj.getQualifiedName());
                if(obj instanceof Boundary) {
                    BoundaryPartSurfaceGroup group=((Boundary)obj).getPartSurfaceGroup();
                    item.put("part_surfaces",objects(group.getObjects()));
                    item.put("surface_query",String.valueOf(group.getQuery()));
                }
                result.add(item);
            }
        }return result;
    }
    String inspectGeometry(String query,int offset){return general.inspectGeometry(query,offset);}
    String geometryContext(){return general.geometryContext();}
    String inspectSection(String query,String axis,double plane,String projection){return general.inspectSection(query,axis,plane,projection);}
    String inspect(String query,int offset,boolean includeState){
        // One native ID has one descriptor and one state. Relation facts are
        // included in that descriptor, rather than appended as a conflicting view.
        return general.inspect(query,offset,includeState);
    }
    Map<String,Set<String>> completionTargets(Collection<String> ids){return general.completionTargets(ids);}
    boolean isObjectId(String id){return general.isObjectId(id);}
    boolean objectExists(String id){return general.objectExists(id);}
    Set<String> existingObjectIds(Collection<String> ids){return general.existingObjectIds(ids);}
    String actionIndex(){return general.actionIndex();}
    String actionHelp(String query){if("invoke_api".equals(query==null?"":query.trim()))return ConfigurationEngine.json(nativeApi.contract());return exactActionHelp(contract(),query);}
    String inspectApi(Map<?,?> request)throws Exception{general.refresh();return ConfigurationEngine.json(nativeApi.inspect(request));}
    void resetApiTask(){nativeApi.resetTask();}
    static String exactActionHelp(String contract,String query){
        String name=query==null?"":query.trim();if(name.equals("*"))return contract;
        Set<String> names=new TreeSet<>(SimulationActions.TYPES);names.addAll(Set.of("execute_mesh","create_sum_report","invoke_api"));
        if(!names.contains(name))return "没有此公开动作；请使用完整动作名，支持="+names;
        if(name.equals("invoke_api"))return "invoke_api: inspect api_object/api_read discovers real object_ref, matching readback and expected_state; invoke exact setter signature with arguments and expected_after. Full schema is available through action_help query=invoke_api. No lifecycle/mesh/solver bypass.";
        if(name.equals("repair_external_aero"))return SimulationActions.repairEntry()+"\n"+ExternalAeroWorkflow.contract();
        if(AerodynamicReports.supports(name))return AerodynamicReports.contract();
        if(PhysicsProfiles.supports(name))return PhysicsProfiles.contract();
        if(CadPartCreation.supports(name))return CadPartCreation.contract();
        if(FlowConditions.supports(name))return FlowConditions.contract();
        if(NativeGeometryRepair.supports(name))return NativeGeometryRepair.contract();
        if(ExternalAeroWorkflow.supports(name))return ExternalAeroWorkflow.contract();
        if(name.equals("execute_mesh"))return "execute_mesh: id=实际AutoMeshOperation ID,expected_state=该ID的最新状态,reason=原因；只在用户明确要求生成网格时调用，干净操作默认跳过，仅明确重新/强制要求可force=true。不是simulation_actions目标，不运行求解。";
        if(name.equals("create_sum_report"))return "create_sum_report: name=新报告名,reports=[2..32个现有同量纲标量报告ID],expected_states=[对应各报告最新状态],reason=原因；不包含id或expected_state。";
        java.util.regex.Pattern start=java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_])"+java.util.regex.Pattern.quote(name)+"\\s*[：:]");
        java.util.regex.Matcher match=start.matcher(contract);if(!match.find())return "此动作已注册，完整签名如下：\n"+contract;
        int begin=match.start(),end=contract.length();
        for(String other:names)if(!other.equals(name)){java.util.regex.Matcher next=java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_])"+java.util.regex.Pattern.quote(other)+"\\s*[：:]").matcher(contract);if(next.find(match.end())&&next.start()<end)end=next.start();}
        return "公共字段：type=动作名,id=真实对象id,expected_state=该id最新状态,reason=原因；id与state令牌不可互换。\n"+contract.substring(begin,end).trim();
    }
    String contract(){return SimulationActions.contract()+"扩展动作invoke_api：常用动作未覆盖的属性配置，先inspect api_search/api_class查询本机文档及运行时签名，再api_object/api_read读取当前对象和匹配getter旧值。完整签名inspect action_help query=invoke_api；不得编写或运行任意Java，不绕过专用的创建/删除/网格/求解流程。"+"新增 actions 与 changes 不可混用；每轮只执行1个 action。可用 inspect scope=operations 查看报告定义、网格输入/局部控制和 expected_state。"
        +"actions:[{type:create_sum_report,name:新报告名,reports:[实际报告ID...],expected_states:[对应expected_state字符串...],reason:原因}] 创建已有同量纲标量报告的有符号总和；不同轴/参考点的力矩求和只代表各分报告标量之和，不是同一全局轴力矩，必须说明。"
        +"actions:[{type:execute_mesh,id:实际网格操作ID,expected_state:原样字符串,reason:原因}] 运行一个已存在的自动网格或包面操作。只在用户明确要求执行/生成网格时使用；目标不明确时结合上一轮实际修改对象，仍不明确则询问，不运行全部操作。可隐含执行STAR依赖，不自动运行求解器。干净网格默认跳过；只有用户明确强制/重新/重划时可给force=true。";}
    Map<?,?> action(String response,String goal)throws Exception {
        currentGoal=goal;
        Object parsed=CodexAssistant.parseJson(CodexAssistant.planJson(response));
        if(!(parsed instanceof Map))throw new IOException("方案必须是对象");Map<?,?> plan=(Map<?,?>)parsed;
        Object raw=plan.get("actions");if(raw==null || raw instanceof List && ((List<?>)raw).isEmpty())return null;
        if(!(raw instanceof List) || ((List<?>)raw).size()!=1 || !(((List<?>)raw).get(0) instanceof Map))throw new IOException("actions每轮必须只有一个操作");
        if(plan.get("changes") instanceof List && !((List<?>)plan.get("changes")).isEmpty())throw new IOException("配置修改和操作分阶段执行");
        Map<?,?> action=(Map<?,?>)((List<?>)raw).get(0);String type=String.valueOf(action.get("type"));
        if(!(action.get("reason") instanceof String) || action.get("reason").toString().isBlank())throw new IOException("需要操作原因");
        if(type.equals("invoke_api")){general.refresh();nativeApi.validate(action,goal);return action;}
        if(SimulationActions.TYPES.contains(type)){
            // Registry metadata and executable targets share one canonical descriptor.
            // Validate the submitted snapshot against live native state before normalization.
            ClientServerObject obj=targets.get(action.get("id"));
            if(obj!=null && Objects.equals(snapshots.get(action.get("id")), action.get("expected_state"))){
                String kind=obj instanceof MeshOperation?"mesh_operation":obj instanceof Report?"report":"mesh_control";
                verify(obj,action.get("expected_state"),kind);
                Map<String,Object> normalized=new LinkedHashMap<>();for(Map.Entry<?,?> e:action.entrySet())normalized.put(String.valueOf(e.getKey()),e.getValue());
                normalized.put("expected_state",general.expectedState(String.valueOf(action.get("id"))));
                general.validate(normalized,goal);return normalized;
            }
            general.validate(action,goal);return action;
        }
        if(type.equals("execute_mesh")){
            if(!meshRequested(goal))throw new IOException("当前用户需求未明确要求执行网格，不能由模型自行启动");
            ClientServerObject obj=targets.get(action.get("id"));
            if(!(obj instanceof AutoMeshOperation))throw new IOException("只能执行目录内已存在的自动网格/包面操作");
            if(Boolean.TRUE.equals(action.get("force"))&&!goal.matches("(?is).*(强制|重新|重划|force|rebuild).*"))throw new IOException("force重划需要用户明确要求");
            verify(obj,action.get("expected_state"),"mesh_operation");MeshOperation op=(MeshOperation)obj;
            if(op.hasEmptyInput() || (op.isDirty()&&!op.isExecuteEnabled(false)))throw new IOException("网格操作输入为空或当前不可执行");
        }else if(type.equals("create_sum_report")){
            if(!(action.get("name") instanceof String) || !action.get("name").toString().matches("[\\p{L}_][\\p{L}\\p{N}_ .-]{0,79}"))throw new IOException("报告名无效");
            for(Report r:sim.getReportManager().getObjects())if(r.getPresentationName().equals(action.get("name")))throw new IOException("同名报告已存在，不覆盖");
            Object ids=action.get("reports"),states=action.get("expected_states");
            if(!(ids instanceof List) || ((List<?>)ids).size()<2 || ((List<?>)ids).size()>32 || !(states instanceof List) || ((List<?>)states).size()!=((List<?>)ids).size())throw new IOException("需要2至32个分报告及其状态");
            Set<Object> unique=new HashSet<>();Dimensions dimensions=null;
            for(int i=0;i<((List<?>)ids).size();i++){Object id=((List<?>)ids).get(i);ClientServerObject obj=targets.get(id);
                if(!(obj instanceof Report) || !unique.add(id))throw new IOException("分报告不存在或重复");
                Report r=(Report)obj;verify(r,((List<?>)states).get(i),"report");
                if(r.getTypeAsEnum()!=FieldFunctionTypeOption.Type.SCALAR)throw new IOException("只能合计标量报告");
                if(dimensions!=null && !dimensions.equals(r.getDimensions()))throw new IOException("分报告量纲不一致，不能相加");dimensions=r.getDimensions();
            }
        }else throw new IOException("不支持的操作："+type);
        return action;
    }
    private void verify(ClientServerObject obj,Object expected,String kind)throws Exception {
        if(general.currentState(obj.getObjectKeyAsString(),expected))return;
        String fresh=ConfigurationEngine.stateToken(describe(obj,kind));
        if(!(expected instanceof String) || !expected.equals(snapshots.get(obj.getObjectKeyAsString())) || !expected.equals(fresh))throw new IOException("对象定义/选择已变化，或状态来自其他对象："+general.stateMismatch(obj.getObjectKeyAsString(),expected)+"；workflow_live_expected_state="+fresh+"；重新读取此对象后选择同一目录视图的状态");
    }
    String objectName(Object id){if(String.valueOf(id).startsWith("api:"))return "当前仿真扩展对象";ClientServerObject o=targets.get(id);return o==null?general.objectName(id):o.getPresentationName();}
    String preview(Map<?,?> action){
        if("invoke_api".equals(action.get("type")))return "扩展接口修改当前仿真对象："+action.get("method")+"；修改依据："+action.get("reason");
        if(SimulationActions.TYPES.contains(action.get("type")))return general.preview(action);
        if(action.get("type").equals("execute_mesh"))return "执行网格："+targets.get(action.get("id")).getPresentationName()+"\n"+action.get("reason")+"\n将重新生成网格，可能执行依赖操作；不会启动求解。";
        List<String> names=new ArrayList<>();for(Object id:(List<?>)action.get("reports"))names.add(targets.get(id).getPresentationName());
        return "创建报告 "+action.get("name")+" = "+String.join(" + ",names)+"\n"+action.get("reason");
    }
    Map<String,Object> execute(Map<?,?> action)throws Exception {
        if(stopRequested)throw new IOException("操作已取消，未派发新的写入。");
        if("invoke_api".equals(action.get("type"))){general.refresh();return nativeApi.execute(action,currentGoal);}
        if(SimulationActions.TYPES.contains(action.get("type")))return general.execute(action);
        if(action.get("type").equals("execute_mesh")){
            MeshOperation op=(MeshOperation)targets.get(action.get("id"));if(stopRequested)throw new IOException("操作已取消");runningMesh=op;
            try{if(!op.isDirty()&&!Boolean.TRUE.equals(action.get("force")))return Map.of("skipped",true,"operation",op.getPresentationName(),"validation","网格已是最新，跳过重复重划；如需强制重新计算请明确要求");op.execute();sim.getServer().getClientNotifyHandler().processCommandQueue(true);
                if(stopRequested)throw new IOException("网格操作已请求停止，请检查当前网格状态");
                if(op.isDirty())throw new IOException("执行后网格操作仍需更新，未确认生成完成");
                Map<String,Object> receipt=new LinkedHashMap<>(Map.of("operation",op.getPresentationName(),"dirty",op.isDirty(),"validation","STAR操作完成且已非过期；未验证工程网格质量"));
                try{Object rep=sim.getRepresentationManager().getObject("Volume Mesh");if(rep instanceof FvRepresentation)receipt.put("cell_count",((FvRepresentation)rep).getCellCount());}catch(RuntimeException ignored){}
                String axis=RecommendationContext.symmetryAxis(currentGoal);Double plane=axis.isEmpty()?null:RecommendationContext.symmetryPlane(currentGoal,axis);
                receipt.put("quality_snapshot",MeshQualitySnapshot.inspect(sim,axis.isEmpty()?null:axis,plane));
                return receipt;
            }finally{runningMesh=null;}
        }
        List<?> ids=(List<?>)action.get("reports");Report first=(Report)targets.get(ids.get(0));
        List<String> terms=new ArrayList<>();for(Object id:ids)terms.add(((Report)targets.get(id)).getReferenceString());
        String definition=String.join(" + ",terms);
        ExpressionReport report=sim.getReportManager().createReport(ExpressionReport.class);
        report.setPresentationName(action.get("name").toString());report.setDimensions(first.getDimensions());report.setUnits(first.getUnits());report.setDefinition(definition);
        if(!definition.equals(report.getDefinition()) || !report.getPresentationName().equals(action.get("name")))throw new IOException("新报告读回不一致");
        return new LinkedHashMap<>(Map.of("report",report.getPresentationName(),"definition",report.getDefinition(),"unit",report.getUnits().getPresentationName(),"validation","定义已读回；未运行求解，不代表已有有效数值"));
    }
    void resetStop(){stopRequested=false;general.reset();}
    boolean abort(){stopRequested=true;if(general.abort())return true;MeshOperation op=runningMesh;if(op==null)return false;op.abortMeshGeneration();return true;}
}
