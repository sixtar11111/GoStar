package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import star.base.neo.*;
import star.base.report.*;
import star.common.*;
import star.flow.*;
import star.vis.*;

/** Small, version-checked operations for common reporting and postprocessing tasks.
 * No file export, solver iteration, mesh execution or arbitrary Java is performed here.
 * All references resolve in the controller's current pinned-simulation object map.
 */
final class CommonActions060 {
    static final Set<String> TYPES=Set.of("configure_coefficient_report","configure_plane_section","create_monitor_plot","configure_scalar_display");
    private static final Set<String> ENVELOPE=Set.of("type","id","expected_state","expected_states","reason");
    private final Simulation sim;
    private final Map<String,ClientServerObject> objects;

    CommonActions060(Simulation simulation,Map<String,ClientServerObject> catalog){sim=simulation;objects=catalog;}
    static boolean supports(String type){return TYPES.contains(type);}
    static Set<String> supportedActions(ClientServerObject target){
        Set<String> result=new LinkedHashSet<>();
        if(target instanceof Simulation)result.add("create_monitor_plot");
        if(target instanceof ForceCoefficientReport||target instanceof MomentCoefficientReport)result.add("configure_coefficient_report");
        if(target instanceof PlaneSection)result.add("configure_plane_section");
        if(target instanceof ScalarDisplayer)result.add("configure_scalar_display");
        return result;
    }
    void collect(Consumer<ClientServerObject> add){
        for(StarPlot plot:sim.getPlotManager().getPlots())add.accept(plot);
        for(Monitor monitor:sim.getMonitorManager().getObjects())if(monitor instanceof ReportMonitor)add.accept(monitor);
        for(CoordinateSystem system:sim.getCoordinateSystemManager().getAllObjects())add.accept(system);
    }
    Map<String,Object> describe(ClientServerObject target){
        Map<String,Object> out=new LinkedHashMap<>();
        if(target instanceof ForceCoefficientReport||target instanceof MomentCoefficientReport){
            VectorReport report=(VectorReport)target;
            out.put("coefficient_reference",coefficientSnapshot(report));
        }
        if(target instanceof PlaneSection){PlaneSection plane=(PlaneSection)target;
            out.put("plane_definition",planeSnapshot(plane));
        }
        if(target instanceof ScalarDisplayer)out.put("scalar_display",scalarSnapshot((ScalarDisplayer)target));
        if(target instanceof MonitorPlot)out.put("monitor_ids",ids(((MonitorPlot)target).getMonitors().getObjects()));
        if(target instanceof CoordinateSystem)out.put("coordinate_system_id",target.getObjectKeyAsString());
        return out;
    }
    private static Map<String,Object> coefficientSnapshot(VectorReport report){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("coordinate_system_id",report.getCoordinateSystem().getObjectKeyAsString());
        out.put("direction",vector(report.getDirection().getSIVector()));
        if(report instanceof ForceCoefficientReport){ForceCoefficientReport force=(ForceCoefficientReport)report;
            out.put("reference_density_kg_m3",force.getReferenceDensity().getSIValue());
            out.put("reference_velocity_m_s",force.getReferenceVelocity().getSIValue());
            out.put("reference_area_m2",force.getReferenceArea().getSIValue());
        }else{MomentCoefficientReport moment=(MomentCoefficientReport)report;
            out.put("reference_density_kg_m3",moment.getReferenceDensity().getSIValue());
            out.put("reference_velocity_m_s",moment.getReferenceVelocity().getSIValue());
            out.put("reference_area_m2",moment.getReferenceArea().getSIValue());
            // STAR names this normalization length Reference Radius, including pitching coefficients.
            out.put("reference_length_m",moment.getReferenceRadius().getSIValue());
            out.put("origin_m",vector(moment.getOrigin().getSIVector()));
        }
        return out;
    }
    private static Map<String,Object> planeSnapshot(PlaneSection plane){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("coordinate_system_id",plane.getCoordinateSystem().getObjectKeyAsString());
        out.put("origin_m",vector(plane.getOriginCoordinate().getSIValue()));
        out.put("normal",vector(plane.getOrientation()));
        out.put("selection_ids",ids(plane.getInputParts().getObjects()));
        out.put("static_selection",plane.getInputParts().getQuery()==null);return out;
    }
    private static Map<String,Object> scalarSnapshot(ScalarDisplayer displayer){
        ScalarDisplayQuantity quantity=displayer.getScalarDisplayQuantity();Map<String,Object> out=new LinkedHashMap<>();
        out.put("field_id",quantity.getFieldFunction()==null?"":quantity.getFieldFunction().getObjectKeyAsString());
        out.put("representation_id",displayer.getRepresentation()==null?"":displayer.getRepresentation().getObjectKeyAsString());
        out.put("selection_ids",ids(displayer.getInputParts().getObjects()));
        out.put("static_selection",displayer.getInputParts().getQuery()==null);
        out.put("auto_range",quantity.getAutoRangeEnum().name());
        out.put("range_si",List.of(quantity.getMinimumValue().getSIValue(),quantity.getMaximumValue().getSIValue()));
        out.put("range_units",quantity.getUnits()==null?"":quantity.getUnits().getPresentationName());
        out.put("show_mesh",displayer.getDisplayMeshBoolean());return out;
    }
    void validate(Map<?,?> action,ClientServerObject target,String goal)throws Exception{
        String type=String.valueOf(action.get("type"));
        require(supportedActions(target).contains(type),"此对象不支持 "+type);
        require(target==sim||objects.get(target.getObjectKeyAsString())==target,"目标必须来自当前SIM目录");
        switch(type){
        case "configure_coefficient_report":{
            fields(action,Set.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m","direction","origin_m","coordinate_system_id"));
            any(action,Set.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m","direction","origin_m","coordinate_system_id"));
            for(String key:List.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m"))if(action.containsKey(key))positive(action.get(key),key);
            boolean moment=target instanceof MomentCoefficientReport;
            require(moment||!action.containsKey("reference_length_m")&&!action.containsKey("origin_m"),"参考长度和力矩原点只适用于MomentCoefficientReport");
            if(action.containsKey("direction"))unitVector(action.get("direction"),"direction");
            if(action.containsKey("origin_m"))finiteVector(action.get("origin_m"),"origin_m");
            if(action.containsKey("direction")||action.containsKey("origin_m")||action.containsKey("coordinate_system_id"))requireCartesian(action.containsKey("coordinate_system_id")?system(action):((VectorReport)target).getCoordinateSystem());
            if(action.containsKey("coordinate_system_id")){
                CoordinateSystem system=system(action);VectorReport report=(VectorReport)target;
                if(!system.equals(report.getCoordinateSystem()))require(action.containsKey("direction")&&(!moment||action.containsKey("origin_m")),"更换坐标系必须同时显式给direction，力矩报告还必须给origin_m，避免隐式改变原参数的物理含义");
            }break;}
        case "configure_plane_section":{
            fields(action,Set.of("origin_m","normal","selection_ids","coordinate_system_id"));any(action,Set.of("origin_m","normal","selection_ids","coordinate_system_id"));
            PlaneSection plane=(PlaneSection)target;
            if(action.containsKey("origin_m"))finiteVector(action.get("origin_m"),"origin_m");
            if(action.containsKey("normal"))unitVector(action.get("normal"),"normal");
            if(action.containsKey("origin_m")||action.containsKey("normal")||action.containsKey("coordinate_system_id"))requireCartesian(action.containsKey("coordinate_system_id")?system(action):plane.getCoordinateSystem());
            if(action.containsKey("selection_ids")){require(plane.getInputParts().getQuery()==null,"截面由动态查询选择，不能隐式改为静态选择");for(ClientServerObject object:selection(action,"selection_ids"))require(object instanceof Region,"截面选择仅支持当前SIM的Region");}
            if(action.containsKey("coordinate_system_id")){CoordinateSystem system=system(action);if(!system.equals(plane.getCoordinateSystem()))require(action.containsKey("origin_m")&&action.containsKey("normal"),"更换截面坐标系必须同时显式给origin_m和normal");}
            break;}
        case "create_monitor_plot":{
            fields(action,Set.of("name","monitor_ids"));require(target==sim,"create_monitor_plot需要当前simulation_actions");
            String name=objectName(action.get("name"));for(StarPlot plot:sim.getPlotManager().getPlots())require(!name.equals(plot.getPresentationName()),"同名绘图已存在；请选择现有对象修改，不能重复创建: "+name);
            for(ClientServerObject monitor:selection(action,"monitor_ids"))require(monitor instanceof ReportMonitor,"monitor_ids仅支持当前SIM已有ReportMonitor；先create_report_monitor");
            break;}
        case "configure_scalar_display":{
            fields(action,Set.of("field_id","selection_ids","representation_id","range_si","automatic_range","show_mesh"));any(action,Set.of("field_id","selection_ids","representation_id","range_si","automatic_range","show_mesh"));
            ScalarDisplayer displayer=(ScalarDisplayer)target;
            if(action.containsKey("field_id")){ClientServerObject field=reference(action.get("field_id"));require(field instanceof FieldFunction&&((FieldFunction)field).getTypeAsEnum()==FieldFunctionTypeOption.Type.SCALAR,"field_id须为当前SIM标量场函数；矢量请先选择其标量分量/模");}
            if(action.containsKey("representation_id"))require(reference(action.get("representation_id")) instanceof Representation,"representation_id不是当前SIM表示");
            if(action.containsKey("selection_ids")){require(displayer.getInputParts().getQuery()==null,"显示器由动态查询选择，不能隐式改为静态选择");for(ClientServerObject object:selection(action,"selection_ids"))require(object instanceof Region||object instanceof Boundary||object instanceof Part||object instanceof GeometryPart||object instanceof PartSurface,"标量显示选择仅支持区域、边界、派生部件、几何部件或表面");}
            if(action.containsKey("range_si")){range(action.get("range_si"));require(!action.containsKey("automatic_range")||Boolean.FALSE.equals(action.get("automatic_range")),"range_si与automatic_range=true冲突");}
            for(String key:List.of("automatic_range","show_mesh"))if(action.containsKey(key))require(action.get(key) instanceof Boolean,key+"须布尔值");
            break;}
        default:throw new IOException("未注册常用动作: "+type);
        }
    }
    Map<String,Object> execute(Map<?,?> action,ClientServerObject target)throws Exception{
        // Validate again immediately before the first write, including every supplied parameter.
        validate(action,target,"");String type=action.get("type").toString();
        Map<String,Object> out=new LinkedHashMap<>();out.put("before",describe(target));
        switch(type){
        case "configure_coefficient_report":{
            VectorReport report=(VectorReport)target;
            if(action.containsKey("coordinate_system_id"))report.setCoordinateSystem(system(action));
            if(action.containsKey("direction"))report.getDirection().setVector(new DoubleVector(unitVector(action.get("direction"),"direction")));
            if(report instanceof ForceCoefficientReport){ForceCoefficientReport force=(ForceCoefficientReport)report;
                setQuantity(action,"reference_density_kg_m3",force.getReferenceDensity());setQuantity(action,"reference_velocity_m_s",force.getReferenceVelocity());setQuantity(action,"reference_area_m2",force.getReferenceArea());
            }else{MomentCoefficientReport moment=(MomentCoefficientReport)report;
                setQuantity(action,"reference_density_kg_m3",moment.getReferenceDensity());setQuantity(action,"reference_velocity_m_s",moment.getReferenceVelocity());setQuantity(action,"reference_area_m2",moment.getReferenceArea());setQuantity(action,"reference_length_m",moment.getReferenceRadius());
                if(action.containsKey("origin_m"))moment.getOrigin().setVectorAndUnits(new DoubleVector(finiteVector(action.get("origin_m"),"origin_m")),si(moment.getOrigin()));
            }
            flush();Map<String,Object> actual=coefficientSnapshot(report);
            for(String key:List.of("reference_density_kg_m3","reference_velocity_m_s","reference_area_m2","reference_length_m"))if(action.containsKey(key))close(((Number)actual.get(key)).doubleValue(),positive(action.get(key),key),key);
            if(action.containsKey("direction"))vectorClose((List<?>)actual.get("direction"),unitVector(action.get("direction"),"direction"),"direction");
            if(action.containsKey("origin_m"))vectorClose((List<?>)actual.get("origin_m"),finiteVector(action.get("origin_m"),"origin_m"),"origin_m");
            if(action.containsKey("coordinate_system_id"))require(Objects.equals(actual.get("coordinate_system_id"),action.get("coordinate_system_id")),"报告坐标系读回不一致");
            out.put("actual",actual);out.put("validation","已按SI值读回系数参考量及方向/原点；保留选面、表示、参考压力与力分量；未运行求解或证明气动力收敛。direction按单位向量设置，origin_m在所示坐标系中。");break;}
        case "configure_plane_section":{
            PlaneSection plane=(PlaneSection)target;
            if(action.containsKey("coordinate_system_id"))plane.setCoordinateSystem(system(action));
            if(action.containsKey("origin_m")){Units metre=sim.getUnitsManager().getUnits("m");plane.getOriginCoordinate().setCoordinate(metre,metre,metre,new DoubleVector(finiteVector(action.get("origin_m"),"origin_m")));}
            if(action.containsKey("normal"))plane.setOrientation(new DoubleVector(unitVector(action.get("normal"),"normal")));
            if(action.containsKey("selection_ids"))plane.getInputParts().setObjects(named(selection(action,"selection_ids")));
            flush();Map<String,Object> actual=planeSnapshot(plane);
            if(action.containsKey("origin_m"))vectorClose((List<?>)actual.get("origin_m"),finiteVector(action.get("origin_m"),"origin_m"),"origin_m");
            if(action.containsKey("normal"))vectorClose((List<?>)actual.get("normal"),unitVector(action.get("normal"),"normal"),"normal");
            if(action.containsKey("selection_ids"))require(actual.get("selection_ids").equals(ids(selection(action,"selection_ids"))),"截面选区读回不一致");
            if(action.containsKey("coordinate_system_id"))require(Objects.equals(actual.get("coordinate_system_id"),action.get("coordinate_system_id")),"截面坐标系读回不一致");
            out.put("actual",actual);out.put("validation","现有截面定义已读回；法向量归一化，未更改几何、网格或求解。origin_m与normal均在所示坐标系中。");break;}
        case "create_monitor_plot":{
            List<Monitor> monitors=new ArrayList<>();for(ClientServerObject object:selection(action,"monitor_ids"))monitors.add((Monitor)object);
            MonitorPlot plot=sim.getPlotManager().createMonitorPlot(monitors,objectName(action.get("name")));flush();
            require(plot.getPresentationName().equals(action.get("name")),"监视器绘图名称读回不一致");require(ids(plot.getMonitors().getObjects()).equals(ids(monitors)),"监视器绘图选择读回不一致");
            out.put("created_id",plot.getObjectKeyAsString());out.put("created_type",plot.getClass().getName());out.put("actual",Map.of("name",plot.getPresentationName(),"monitor_ids",ids(plot.getMonitors().getObjects())));out.put("validation","已创建并读回现有报告监视器绘图；未生成新的监视器、未清除历史、未运行CFD。");break;}
        case "configure_scalar_display":{
            ScalarDisplayer displayer=(ScalarDisplayer)target;ScalarDisplayQuantity quantity=displayer.getScalarDisplayQuantity();
            Units previousDisplayUnits=quantity.getUnits();
            boolean fieldChanged=action.containsKey("field_id")&&!Objects.equals(quantity.getFieldFunction(),reference(action.get("field_id")));
            if(fieldChanged)quantity.setFieldFunction((FieldFunction)reference(action.get("field_id")));
            if(action.containsKey("representation_id"))displayer.setRepresentation((Representation)reference(action.get("representation_id")));
            if(action.containsKey("selection_ids"))displayer.getInputParts().setObjects(named(selection(action,"selection_ids")));
            if(action.containsKey("show_mesh"))displayer.setDisplayMeshBoolean((Boolean)action.get("show_mesh"));
            flush();
            if(fieldChanged&&previousDisplayUnits!=null&&quantity.getUnits()!=null&&previousDisplayUnits.compatible(quantity.getUnits())&&!previousDisplayUnits.equals(quantity.getUnits()))quantity.setUnits(previousDisplayUnits);
            if(action.containsKey("range_si")){
                double[] bounds=range(action.get("range_si"));Units displayUnits=quantity.getUnits();require(displayUnits!=null,"标量场尚无可用显示单位，未写入色标范围");
                ScalarPhysicalQuantity minimum=quantity.getMinimumValue(),maximum=quantity.getMaximumValue();
                double low=minimum.getIsDeltaValue()?displayUnits.convertDeltaOutput(bounds[0]):displayUnits.convertOutput(bounds[0]);
                double high=maximum.getIsDeltaValue()?displayUnits.convertDeltaOutput(bounds[1]):displayUnits.convertOutput(bounds[1]);
                finite(low,"range_si.min converted display value");finite(high,"range_si.max converted display value");
                quantity.setAutoRange(AutoRangeMode.NONE);minimum.setValueAndUnits(low,displayUnits);maximum.setValueAndUnits(high,displayUnits);quantity.setUnits(displayUnits);
                flush();require(quantity.getUnits().equals(displayUnits),"SI色标范围写入改变了显示单位");out.put("range_units_preserved",true);
            }
            else if(action.containsKey("automatic_range"))quantity.setAutoRange(Boolean.TRUE.equals(action.get("automatic_range"))?AutoRangeMode.BOTH:AutoRangeMode.NONE);
            flush();Map<String,Object> actual=scalarSnapshot(displayer);
            if(action.containsKey("field_id"))require(Objects.equals(actual.get("field_id"),action.get("field_id")),"标量场函数读回不一致");
            if(action.containsKey("representation_id"))require(Objects.equals(actual.get("representation_id"),action.get("representation_id")),"标量表示读回不一致");
            if(action.containsKey("selection_ids"))require(actual.get("selection_ids").equals(ids(selection(action,"selection_ids"))),"标量选区读回不一致");
            if(action.containsKey("show_mesh"))require(Objects.equals(actual.get("show_mesh"),action.get("show_mesh")),"标量网格线读回不一致");
            if(action.containsKey("range_si")){double[] expected=range(action.get("range_si"));List<?> actualRange=(List<?>)actual.get("range_si");close(((Number)actualRange.get(0)).doubleValue(),expected[0],"range_si.min");close(((Number)actualRange.get(1)).doubleValue(),expected[1],"range_si.max");require(actual.get("auto_range").equals("NONE"),"手动范围未生效");}
            else if(action.containsKey("automatic_range"))require(actual.get("auto_range").equals(Boolean.TRUE.equals(action.get("automatic_range"))?"BOTH":"NONE"),"自动范围读回不一致");
            out.put("actual",actual);out.put("validation","已读回标量显示器场函数、表示、选区及显式指定的色标/网格线；色标使用该场函数的SI单位。配置读回不证明场数据存在或解已收敛。");break;}
        default:throw new IOException("未注册常用动作");
        }
        out.put("target_id",target.getObjectKeyAsString());out.put("target_name",target.getPresentationName());return out;
    }
    private void setQuantity(Map<?,?> action,String key,ScalarPhysicalQuantity quantity)throws IOException{if(action.containsKey(key))quantity.setValueAndUnits(positive(action.get(key),key),si(quantity));}
    private static Units si(ScalarPhysicalQuantity quantity){return quantity.getUnits().getSIUnits();}
    private static Units si(VectorPhysicalQuantity quantity){return quantity.getUnits().getSIUnits();}
    private CoordinateSystem system(Map<?,?> action)throws IOException{ClientServerObject result=reference(action.get("coordinate_system_id"));require(result instanceof CoordinateSystem,"coordinate_system_id须当前SIM坐标系");return (CoordinateSystem)result;}
    private static void requireCartesian(CoordinateSystem system)throws IOException{require(system instanceof LabCoordinateSystem||system instanceof CartesianCoordinateSystem,"此常用接口的米坐标/方向仅支持Lab或Cartesian坐标系；柱坐标/球坐标需专用参数接口，不能将角坐标当作米");}
    private ClientServerObject reference(Object id)throws IOException{require(id instanceof String,"引用须为当前SIM真实对象ID");ClientServerObject result=objects.get(id);require(result!=null,"引用对象不属于当前SIM目录: "+id);return result;}
    private List<ClientServerObject> selection(Map<?,?> action,String key)throws IOException{
        Object value=action.get(key);require(value instanceof List&&!((List<?>)value).isEmpty(),key+"需要非空真实对象ID列表");Set<Object> seen=new HashSet<>();List<ClientServerObject> result=new ArrayList<>();
        for(Object id:(List<?>)value){require(seen.add(id),key+"中对象ID重复");result.add(reference(id));}return result;
    }
    static void fields(Map<?,?> action,Set<String> accepted)throws IOException{for(Object key:action.keySet())require(ENVELOPE.contains(key)||accepted.contains(key),"未支持参数: "+key+"；请读取该动作action_help，不能静默忽略参数");}
    static void any(Map<?,?> action,Set<String> fields)throws IOException{for(String field:fields)if(action.containsKey(field))return;throw new IOException("必须至少给出一个明确修改参数");}
    static double positive(Object value,String key)throws IOException{double result=finite(value,key);require(result>0,key+"须有限正数");return result;}
    static double finite(Object value,String key)throws IOException{require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()),key+"须有限数值");return ((Number)value).doubleValue();}
    static double[] finiteVector(Object value,String key)throws IOException{require(value instanceof List&&((List<?>)value).size()==3,key+"须三维有限数组");double[] result=new double[3];for(int i=0;i<3;i++)result[i]=finite(((List<?>)value).get(i),key);return result;}
    static double[] unitVector(Object value,String key)throws IOException{double[] result=finiteVector(value,key);double scale=Math.max(Math.abs(result[0]),Math.max(Math.abs(result[1]),Math.abs(result[2])));require(scale>0,key+"不可为零向量");double length=Math.hypot(Math.hypot(result[0]/scale,result[1]/scale),result[2]/scale);for(int i=0;i<3;i++)result[i]=(result[i]/scale)/length;return result;}
    static double[] range(Object value)throws IOException{require(value instanceof List&&((List<?>)value).size()==2,"range_si须[min,max]有限数值");double low=finite(((List<?>)value).get(0),"range_si.min"),high=finite(((List<?>)value).get(1),"range_si.max");require(low<high,"range_si.min必须小于max");return new double[]{low,high};}
    static String objectName(Object value)throws IOException{require(value instanceof String&&value.toString().matches("[\\p{L}_][\\p{L}\\p{N}_ .-]{0,79}"),"名称须1..80个合法字符");return value.toString();}
    private static List<String> ids(Collection<?> values){List<String> result=new ArrayList<>();for(Object value:values)if(value instanceof ClientServerObject)result.add(((ClientServerObject)value).getObjectKeyAsString());Collections.sort(result);return result;}
    private static List<NamedObject> named(List<ClientServerObject> values){List<NamedObject> result=new ArrayList<>();for(ClientServerObject value:values)result.add((NamedObject)value);return result;}
    private static List<Double> vector(DoubleVector value){return List.of(value.get(0),value.get(1),value.get(2));}
    private static void vectorClose(List<?> actual,double[] expected,String key)throws IOException{require(actual.size()==expected.length,key+"维数读回不一致");for(int i=0;i<expected.length;i++)close(((Number)actual.get(i)).doubleValue(),expected[i],key+"["+i+"]");}
    private static void close(double actual,double expected,String key)throws IOException{require(Double.isFinite(actual)&&Math.abs(actual-expected)<=Math.max(1e-12,Math.abs(expected)*1e-10),key+"读回不一致 expected="+expected+" actual="+actual);}
    private static void require(boolean condition,String message)throws IOException{if(!condition)throw new IOException(message);}
    private void flush(){sim.getServer().getClientNotifyHandler().processCommandQueue(true);}

    static String contract(String type){
        switch(type){
        case "configure_coefficient_report":return "configure_coefficient_report: id=当前ForceCoefficientReport或MomentCoefficientReport；可逐项提供reference_density_kg_m3、reference_velocity_m_s、reference_area_m2（有限正数），力矩另可reference_length_m（STAR Reference Radius）和origin_m=[x,y,z]；direction=[dx,dy,dz]非零向量会归一化。可选coordinate_system_id=当前真实Lab/Cartesian坐标系ID；方向/原点按指定或保留的直角坐标系解释，不自动计算半模面积或重设选面。更换坐标系必须给direction，力矩还给origin_m。只改明确给出的参数、以SI值读回；不会运行CFD或声称系数已收敛。";
        case "configure_plane_section":return "configure_plane_section: id=现有PlaneSection；可逐项origin_m=[x,y,z]、normal=[nx,ny,nz]、selection_ids=[当前Region ID]、coordinate_system_id=当前Lab/Cartesian坐标系。坐标/法向按指定或保留的直角坐标系解释，法向归一化；更换坐标系必须同时给origin_m和normal。仅覆盖明确给出的字段，动态查询选区不改写，不重建截面或划网格。";
        case "create_monitor_plot":return "create_monitor_plot: id=当前simulation_actions，name=唯一名称，monitor_ids=[当前已有ReportMonitor真实ID]；报告尚无监视器时先create_report_monitor并重读目录。创建监视器历史绘图并读回绑定；不自动创建报告、不清历史、不运行求解。";
        case "configure_scalar_display":return "configure_scalar_display: id=现有ScalarDisplayer；可逐项field_id=当前标量FieldFunction、selection_ids=[Region/Boundary/Part/GeometryPart/PartSurface]、representation_id、show_mesh=true/false、automatic_range=true/false或range_si=[min,max]（场函数SI单位，min<max）。range_si会关闭自动色标，与automatic_range=true冲突；不提供则保留。向量需先选择其标量分量/模，查询驱动选区拒绝隐式覆盖。配置读回不证明数据存在或求解收敛。";
        default:return "";
        }
    }
    static String contract(){StringBuilder out=new StringBuilder("\n常用报告与后处理动作：每项使用type,id,expected_state,reason，参数缺省保留，未知参数拒绝而不忽略。\n");for(String type:new TreeSet<>(TYPES))out.append(contract(type)).append('\n');return out.toString();}
}
