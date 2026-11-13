package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import star.base.neo.*;
import star.base.report.*;
import star.common.*;
import star.flow.*;
import star.meshing.LatestMeshProxyRepresentation;

/** Complete, read-back-verified force/moment report setup. Never evaluates or solves CFD. */
final class AerodynamicReports {
    static final Set<String> TYPES=Set.of("create_aerodynamic_report","configure_aerodynamic_report");
    private static final Set<String> FIELDS=Set.of("report_kind","name","selection_ids","direction","origin_m","coordinate_system_id","representation_id","reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m","reference_pressure_pa","force_component","use_idealization","create_monitor","monitor_name","plot_name");
    private final Simulation sim;
    private final Map<String,ClientServerObject> objects;
    AerodynamicReports(Simulation simulation,Map<String,ClientServerObject> catalog){sim=simulation;objects=catalog;}
    static boolean supports(String type){return TYPES.contains(type);}
    static Set<String> supportedActions(ClientServerObject target){return target instanceof Simulation?Set.of("create_aerodynamic_report"):target instanceof ForceAndMomentBaseReport?Set.of("configure_aerodynamic_report"):Set.of();}
    static String kind(Report report){return report instanceof MomentCoefficientReport?"moment_coefficient":report instanceof ForceCoefficientReport?"force_coefficient":report instanceof MomentReport?"moment":report instanceof ForceReport?"force":"";}
    static boolean coefficient(String kind){return kind.endsWith("_coefficient");}
    static boolean moment(String kind){return kind.startsWith("moment");}
    static Class<? extends ForceAndMomentBaseReport> reportClass(String kind)throws IOException {
        switch(kind){case "force":return ForceReport.class;case "moment":return MomentReport.class;case "force_coefficient":return ForceCoefficientReport.class;case "moment_coefficient":return MomentCoefficientReport.class;default:throw new IOException("report_kind须为force/moment/force_coefficient/moment_coefficient");}
    }
    void validate(Map<?,?> action,ClientServerObject target,String goal)throws Exception {
        String type=String.valueOf(action.get("type"));boolean create=type.equals("create_aerodynamic_report");
        require(supportedActions(target).contains(type),"创建气动力报告需要当前simulation_actions；修改需要现有力/力矩报告");
        CommonActions060.fields(action,FIELDS);require(target==sim||objects.get(target.getObjectKeyAsString())==target,"目标不属于当前SIM目录");
        String kind=create?string(action.get("report_kind")):kind((Report)target);reportClass(kind);
        if(action.containsKey("report_kind"))require(kind.equals(action.get("report_kind")),"不能把现有报告直接改成另一种类型，请明确创建新报告");
        if(create){
            String name=CommonActions060.objectName(action.get("name"));for(Report report:sim.getReportManager().getObjects())require(!name.equals(report.getPresentationName()),"同名报告已存在，请读取并修改该报告，不能重复创建: "+name);
            require(sim.getReportManager().canCreate(reportClass(kind).getName()),"当前流动报告工厂尚未激活；先检查区域的流动物理模型，不创建默认占位报告。类型="+reportClass(kind).getName());
            require(action.containsKey("selection_ids")&&action.containsKey("direction"),"新建气动力报告必须提供实际壁面选择和方向");
            if(moment(kind))require(action.containsKey("origin_m"),"力矩报告必须明确给出力矩原点origin_m，不猜测重心或四分之一弦位置");
            if(coefficient(kind))for(String key:List.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2"))require(action.containsKey(key),"系数报告缺少真实参考量: "+key+"；从当前已验证配置/同工况报告取得或询问缺失信息，禁止默认填1");
            if(kind.equals("moment_coefficient"))require(action.containsKey("reference_length_m"),"力矩系数报告必须明确reference_length_m（STAR Reference Radius）");
        }else{
            require(!action.containsKey("name"),"报告重命名请用rename_object；配置动作不隐式重命名");
            Set<String> parameters=new HashSet<>(FIELDS);parameters.removeAll(Set.of("name","report_kind","monitor_name","plot_name"));CommonActions060.any(action,parameters);
        }
        if(!coefficient(kind))for(String key:List.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m"))require(!action.containsKey(key),"力/力矩报告不使用系数参考量: "+key);
        if(!moment(kind))require(!action.containsKey("origin_m")&&!action.containsKey("reference_length_m"),"只有力矩类报告可设置原点/参考长度");
        if(!kind.equals("moment_coefficient"))require(!action.containsKey("reference_length_m"),"reference_length_m仅用于力矩系数报告");
        for(String key:List.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m"))if(action.containsKey(key))CommonActions060.positive(action.get(key),key);
        if(action.containsKey("reference_pressure_pa"))CommonActions060.finite(action.get("reference_pressure_pa"),"reference_pressure_pa");
        if(action.containsKey("direction"))CommonActions060.unitVector(action.get("direction"),"direction");
        if(action.containsKey("origin_m"))CommonActions060.finiteVector(action.get("origin_m"),"origin_m");
        if(action.containsKey("selection_ids")){
            selection(action.get("selection_ids"));
            if(!create)require(((AnalysisReport)target).getParts().getQuery()==null,"现有报告使用动态查询，不能隐式改成静态选面");
        }
        CoordinateSystem cs=coordinateSystem(action,create?null:(VectorReport)target);
        require(cs instanceof LabCoordinateSystem||cs instanceof CartesianCoordinateSystem,"气动力报告仅支持Lab或Cartesian直角坐标系");
        if(!create&&action.containsKey("coordinate_system_id")&&!cs.equals(((VectorReport)target).getCoordinateSystem()))require(action.containsKey("direction")&&(!moment(kind)||action.containsKey("origin_m")),"更换坐标系必须同时给方向，力矩还须给原点，避免改变未指定项的物理含义");
        representation(action,create?null:(AnalysisReport)target);
        if(action.containsKey("force_component"))component(string(action.get("force_component")));
        for(String key:List.of("use_idealization","create_monitor"))if(action.containsKey(key))require(action.get(key) instanceof Boolean,key+"须布尔值");
        boolean monitoring=Boolean.TRUE.equals(action.get("create_monitor"));
        require(monitoring||!action.containsKey("monitor_name")&&!action.containsKey("plot_name"),"monitor_name/plot_name需要create_monitor=true");
        if(monitoring){
            String monitor=monitorName(action,target);for(Monitor existing:sim.getMonitorManager().getObjects())require(!monitor.equals(existing.getPresentationName()),"同名监视器已存在，不能重复创建: "+monitor);
            if(action.containsKey("plot_name")){String plot=CommonActions060.objectName(action.get("plot_name"));for(StarPlot existing:sim.getPlotManager().getPlots())require(!plot.equals(existing.getPresentationName()),"同名绘图已存在: "+plot);}
        }
    }
    Map<String,Object> execute(Map<?,?> action,ClientServerObject target)throws Exception {
        validate(action,target,"");boolean create=action.get("type").equals("create_aerodynamic_report");String type=create?string(action.get("report_kind")):kind((Report)target);
        Map<String,Object> out=new LinkedHashMap<>();out.put("report_kind",type);out.put("target_name",target.getPresentationName());
        if(!create)out.put("before",snapshot((ForceAndMomentBaseReport)target));
        List<String> created=new ArrayList<>();ForceAndMomentBaseReport report=null;
        try{
            report=create?sim.getReportManager().createReport(reportClass(type)):(ForceAndMomentBaseReport)target;
            if(create){created.add(report.getObjectKeyAsString());report.setPresentationName(string(action.get("name")));}
            if(create||action.containsKey("coordinate_system_id"))report.setCoordinateSystem(coordinateSystem(action,create?null:report));
            if(action.containsKey("selection_ids"))report.getParts().setObjects(named(selection(action.get("selection_ids"))));
            if(action.containsKey("direction"))report.getDirection().setVector(new DoubleVector(CommonActions060.unitVector(action.get("direction"),"direction")));
            if(moment(type)&&action.containsKey("origin_m"))setVector(((MomentReport)report).getOrigin(),CommonActions060.finiteVector(action.get("origin_m"),"origin_m"));
            Representation rep=representation(action,create?null:report);if(create||action.containsKey("representation_id"))report.setRepresentation(rep);
            if(create||action.containsKey("force_component"))report.getForceOption().setSelected(component(string(action.containsKey("force_component")?action.get("force_component"):"pressure_and_shear")));
            if(create||action.containsKey("use_idealization"))report.setUseIdealization(Boolean.TRUE.equals(action.get("use_idealization")));
            if(action.containsKey("reference_pressure_pa"))setScalar(report.getReferencePressure(),((Number)action.get("reference_pressure_pa")).doubleValue());
            if(report instanceof ForceCoefficientReport){ForceCoefficientReport r=(ForceCoefficientReport)report;set(action,"reference_density_kg_m3",r.getReferenceDensity());set(action,"reference_velocity_m_s",r.getReferenceVelocity());set(action,"reference_area_m2",r.getReferenceArea());}
            if(report instanceof MomentCoefficientReport){MomentCoefficientReport r=(MomentCoefficientReport)report;set(action,"reference_density_kg_m3",r.getReferenceDensity());set(action,"reference_velocity_m_s",r.getReferenceVelocity());set(action,"reference_area_m2",r.getReferenceArea());set(action,"reference_length_m",r.getReferenceRadius());}
            flush();Map<String,Object> actual=snapshot(report);verify(action,actual,report,rep);
            if(Boolean.TRUE.equals(action.get("create_monitor"))){
                ReportMonitor monitor=report.createMonitor();created.add(monitor.getObjectKeyAsString());monitor.setPresentationName(monitorName(action,target));flush();
                require(monitor.getPresentationName().equals(monitorName(action,target))&&monitor.getReport().equals(report),"监视器名称或报告绑定读回不一致");out.put("monitor",Map.of("id",monitor.getObjectKeyAsString(),"name",monitor.getPresentationName(),"report_id",report.getObjectKeyAsString()));
                if(action.containsKey("plot_name")){MonitorPlot plot=sim.getPlotManager().createMonitorPlot(List.of(monitor),string(action.get("plot_name")));created.add(plot.getObjectKeyAsString());flush();require(plot.getPresentationName().equals(action.get("plot_name"))&&new HashSet<>(plot.getMonitors().getObjects()).equals(Set.of(monitor)),"绘图名称或监视器绑定读回不一致");out.put("plot",Map.of("id",plot.getObjectKeyAsString(),"name",plot.getPresentationName()));}
            }
            out.put("report",actual);out.put("actual",actual);out.put("created_ids",created);out.put("created_id",create?report.getObjectKeyAsString():"");out.put("created_type",create?report.getClass().getName():"");out.put("target_id",report.getObjectKeyAsString());out.put("target_name",report.getPresentationName());out.put("verified",true);out.put("configured",true);out.put("data_status","not_evaluated");out.put("validation","报告类型、选面、方向、原点、参考量、表示及新增监视器已原生读回。未求值、未运行求解，不把空数据或默认值当成已收敛气动力。新建默认积分实际选面，不自动镜像或倍增半模。");return out;
        }catch(Exception error){out.put("created_ids",created);out.put("verified",false);out.put("configured",false);out.put("data_status","not_evaluated");if(report!=null)try{out.put("actual",snapshot(report));}catch(Exception read){out.put("readback_error",read.toString());}throw new ReportException("气动力报告配置未完成："+error.getMessage(),error,out);}
    }
    private void verify(Map<?,?> action,Map<String,Object> actual,ForceAndMomentBaseReport report,Representation rep)throws Exception {
        if(action.containsKey("name"))require(action.get("name").equals(actual.get("name")),"报告名读回不一致");
        if(action.containsKey("selection_ids"))require(ids(selection(action.get("selection_ids"))).equals(actual.get("selection_ids")),"报告选面读回不一致");
        for(String key:List.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m","reference_pressure_pa"))if(action.containsKey(key))require(NativeApiBridge.equal(action.get(key),actual.get(key),1e-10),key+" SI读回不一致");
        if(action.containsKey("direction"))require(NativeApiBridge.equal(list(CommonActions060.unitVector(action.get("direction"),"direction")),actual.get("direction"),1e-10),"报告方向读回不一致");
        if(action.containsKey("origin_m"))require(NativeApiBridge.equal(action.get("origin_m"),actual.get("origin_m"),1e-10),"报告原点读回不一致");
        if(action.containsKey("coordinate_system_id"))require(action.get("coordinate_system_id").equals(actual.get("coordinate_system_id")),"报告坐标系读回不一致");
        if(createAction(action)||action.containsKey("force_component"))require(component(string(action.containsKey("force_component")?action.get("force_component"):"pressure_and_shear")).name().equals(actual.get("force_component")),"压力/剪切分量读回不一致");
        if(createAction(action)||action.containsKey("use_idealization"))require(Boolean.TRUE.equals(action.get("use_idealization"))==Boolean.TRUE.equals(actual.get("use_idealization")),"理想化映射选项读回不一致");
        require(report.getRepresentation()!=null&&report.getRepresentation().equals(rep),"报告表示未绑定或读回不一致");
    }
    static Map<String,Object> snapshot(ForceAndMomentBaseReport report){
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",report.getObjectKeyAsString());m.put("name",report.getPresentationName());m.put("type",report.getClass().getName());m.put("report_kind",kind(report));m.put("selection_ids",ids(report.getParts().getObjects()));m.put("selection_names",report.getParts().getObjects().stream().map(x->((ClientServerObject)x).getPresentationName()).toList());m.put("direction",list(report.getDirection().getSIVector()));m.put("coordinate_system_id",report.getCoordinateSystem().getObjectKeyAsString());m.put("coordinate_system",report.getCoordinateSystem().getPresentationName());m.put("representation_id",report.getRepresentation()==null?"":report.getRepresentation().getObjectKeyAsString());m.put("representation",report.getRepresentation()==null?"":report.getRepresentation().getPresentationName());m.put("force_component",report.getForceOption().getSelectedElement().name());m.put("use_idealization",report.getUseIdealization());m.put("reference_pressure_pa",report.getReferencePressure().getSIValue());
        if(report instanceof MomentReport)m.put("origin_m",list(((MomentReport)report).getOrigin().getSIVector()));
        if(report instanceof ForceCoefficientReport){ForceCoefficientReport r=(ForceCoefficientReport)report;m.put("reference_density_kg_m3",r.getReferenceDensity().getSIValue());m.put("reference_velocity_m_s",r.getReferenceVelocity().getSIValue());m.put("reference_area_m2",r.getReferenceArea().getSIValue());}
        if(report instanceof MomentCoefficientReport){MomentCoefficientReport r=(MomentCoefficientReport)report;m.put("reference_density_kg_m3",r.getReferenceDensity().getSIValue());m.put("reference_velocity_m_s",r.getReferenceVelocity().getSIValue());m.put("reference_area_m2",r.getReferenceArea().getSIValue());m.put("reference_length_m",r.getReferenceRadius().getSIValue());}
        return m;
    }
    private List<ClientServerObject> selection(Object raw)throws Exception {
        require(raw instanceof List&&!((List<?>)raw).isEmpty(),"selection_ids必须为实际壁面/壁面PartSurface的非空列表");List<ClientServerObject> result=new ArrayList<>();Set<Object> seen=new HashSet<>();
        for(Object id:(List<?>)raw){ClientServerObject o=objects.get(id);require(o!=null&&seen.add(id),"选面不存在、重复或不属于当前SIM");Boundary boundary=o instanceof Boundary?(Boundary)o:o instanceof PartSurface?((PartSurface)o).hasBoundary():null;require(boundary!=null&&boundary.getBoundaryType() instanceof WallBoundary,"气动力选面必须绑定实际WallBoundary，不能选择整个区域、入口或原始未关联CAD");require(!boundary.getPartSurfaceGroup().getObjects().isEmpty(),"所选壁面没有实际几何面，不能创建空气动力报告");result.add(o);}
        // A complete boundary and one of its constituent surfaces overlap physically.
        // Reject the ambiguity rather than risk counting the same face twice.
        Set<Boundary> whole=new HashSet<>();for(ClientServerObject o:result)if(o instanceof Boundary)whole.add((Boundary)o);
        for(ClientServerObject o:result)if(o instanceof PartSurface)require(!whole.contains(((PartSurface)o).hasBoundary()),"同一壁面及其组成表面不能同时选取，避免重复积分");
        return result;
    }
    private CoordinateSystem coordinateSystem(Map<?,?> action,VectorReport existing)throws Exception {if(action.containsKey("coordinate_system_id")){ClientServerObject object=objects.get(action.get("coordinate_system_id"));require(object instanceof CoordinateSystem,"coordinate_system_id不属于当前SIM坐标系目录");return(CoordinateSystem)object;}return existing==null?sim.getCoordinateSystemManager().getLabCoordinateSystem():existing.getCoordinateSystem();}
    private Representation representation(Map<?,?> action,AnalysisReport existing)throws Exception {
        if(action.containsKey("representation_id")){ClientServerObject object=objects.get(action.get("representation_id"));require(flowRepresentation(object),"气动力报告需要有限体积表示或STAR最新网格代理，不能使用纯几何表示");return(Representation)object;}
        if(existing!=null&&existing.getRepresentation()!=null){require(flowRepresentation(existing.getRepresentation()),"现有报告不是流场表示，请明确选择representation_id");return existing.getRepresentation();}
        Representation defaultRep=sim.getRepresentationManager().getDefaultFvRepresentation();
        if(defaultRep instanceof FvRepresentation)return defaultRep;
        for(Representation rep:sim.getRepresentationManager().getObjects())if(rep instanceof LatestMeshProxyRepresentation)return rep;
        throw new IOException("当前SIM没有有限体积表示或最新网格代理，请查询operations并明确representation_id");
    }
    private static boolean flowRepresentation(Object o){return o instanceof FvRepresentation||o instanceof LatestMeshProxyRepresentation;}
    private static ForceReportForceOption.Type component(String value)throws IOException {switch(value){case "pressure":return ForceReportForceOption.Type.PRESSURE;case "shear":return ForceReportForceOption.Type.SHEAR;case "pressure_and_shear":return ForceReportForceOption.Type.PRESSURE_AND_SHEAR;default:throw new IOException("force_component须为pressure/shear/pressure_and_shear");}}
    private String monitorName(Map<?,?> action,ClientServerObject target)throws IOException {return CommonActions060.objectName(action.containsKey("monitor_name")?action.get("monitor_name"):string(action.containsKey("name")?action.get("name"):target.getPresentationName())+" Monitor");}
    private static boolean createAction(Map<?,?> a){return "create_aerodynamic_report".equals(a.get("type"));}
    private static String string(Object value){return value==null?"":value.toString();}
    private static List<Double> list(DoubleVector v){return list(v.toDoubleArray());}
    private static List<Double> list(double[] v){List<Double> out=new ArrayList<>();for(double n:v)out.add(n);return out;}
    private static List<String> ids(Collection<?> values){List<String> out=new ArrayList<>();for(Object v:values)if(v instanceof ClientServerObject)out.add(((ClientServerObject)v).getObjectKeyAsString());Collections.sort(out);return out;}
    private static List<NamedObject> named(List<ClientServerObject> values){List<NamedObject> out=new ArrayList<>();for(ClientServerObject v:values)out.add((NamedObject)v);return out;}
    private void set(Map<?,?> action,String key,ScalarPhysicalQuantity quantity){if(action.containsKey(key))setScalar(quantity,((Number)action.get(key)).doubleValue());}
    private void setScalar(ScalarPhysicalQuantity q,double v){q.setValueAndUnits(v,q.getUnits().getSIUnits());}
    private void setVector(VectorPhysicalQuantity q,double[] v){q.setVectorAndUnits(new DoubleVector(v),q.getUnits().getSIUnits());}
    private void flush(){sim.getServer().getClientNotifyHandler().processCommandQueue(true);}
    private static void require(boolean ok,String message)throws IOException {if(!ok)throw new IOException(message);}
    static final class ReportException extends IOException {private final Map<String,Object> actual;ReportException(String message,Throwable cause,Map<String,Object> receipt){super(message,cause);actual=new LinkedHashMap<>(receipt);}Map<String,Object> receipt(){return new LinkedHashMap<>(actual);}}
    static String contract(){return "\ncreate_aerodynamic_report: id=当前simulation_actions,name=唯一报告名,report_kind=force/moment/force_coefficient/moment_coefficient,selection_ids=[实际非空WallBoundary或其PartSurface],direction=[dx,dy,dz]（归一化）。力矩另必须origin_m=[x,y,z]；系数必须reference_density_kg_m3/reference_velocity_m_s/reference_area_m2，力矩系数另必须reference_length_m（STAR Reference Radius）。参考量须来自用户或当前已验证工况/同工况报告，不用默认1，不自行猜半模面积。可选coordinate_system_id=Lab/Cartesian，默认Lab；representation_id=真实FvRepresentation或LatestMeshProxyRepresentation；默认本SIM的default FvRepresentation，无体网格时绑定STAR最新网格代理（仅配置待求值，不表示已有解）；reference_pressure_pa=报告参考表压；force_component=pressure/shear/pressure_and_shear（新建默认总压力+剪切）；use_idealization=true/false（新建默认false，积分实际选面，不自动倍增半模）。可选create_monitor=true同时创建监视器，可给monitor_name/plot_name；没有create_monitor不能给监视器/绘图名。动作完成所有绑定、参数及读回后统一保存，不运行CFD或把配置当最终数值。\nconfigure_aerodynamic_report: id=现有ForceReport/MomentReport及系数报告，以上参数按需给出，未给保留；不改报告类型/名称。动态选面不隐式覆盖；更换坐标系必须给direction，力矩还给origin_m。\n";}
}
