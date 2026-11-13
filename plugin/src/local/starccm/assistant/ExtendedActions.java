package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import star.base.neo.*;
import star.base.report.*;
import star.common.*;
import star.meshing.*;
import star.prismmesher.*;
import star.vis.*;

/** Version-checked lifecycle operations. IDs always resolve in the pinned simulation.
 * Factories are typed; report factory choices come from STAR's current manager, not arbitrary Java. */
final class ExtendedActions {
    static final Set<String> TYPES=Set.of("create_boundary","set_boundary_type","create_mesh_control","set_control_enabled","configure_surface_prisms",
        "limit_prisms_to_surfaces","configure_surface_size","configure_volume_size","set_object_selection",
        "create_physics_continuum","create_report","set_report_field","create_report_monitor",
        "create_field_function","create_global_parameter","create_plane_section","create_scene");
    final Simulation sim;
    final Map<String,ClientServerObject> objects;
    ExtendedActions(Simulation s,Map<String,ClientServerObject> o){sim=s;objects=o;}
    void collect(Consumer<ClientServerObject> add){
        if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects())
            if(op instanceof AutoMeshOperation)for(CustomMeshControl c:((AutoMeshOperation)op).getCustomMeshControls().getObjects())add.accept(c);
        for(Continuum c:sim.getContinuumManager().getObjects())add.accept(c);
        for(FieldFunction f:sim.getFieldFunctionManager().getObjects())add.accept(f);
        for(NamedObject p:sim.getGlobalParameterManager().getObjects())add.accept(p);
        for(Representation r:sim.getRepresentationManager().getObjects())add.accept(r);
        for(Part p:sim.getPartManager().getObjects())add.accept(p);
        for(Monitor m:sim.getMonitorManager().getObjects())if(m instanceof ReportMonitor)add.accept(m);
        for(Scene s:sim.getSceneManager().getScenes()){add.accept(s);for(Displayer d:s.getDisplayerManager().getObjects())add.accept(d);}
    }
    List<String> reportTypes(){List<String> result=new ArrayList<>();for(String t:sim.getReportManager().getAvailableTypes())if(sim.getReportManager().canCreate(t))result.add(t);Collections.sort(result);return result;}
    static List<String> ids(Collection<?> values){List<String> out=new ArrayList<>();for(Object v:values)if(v instanceof ClientServerObject)out.add(((ClientServerObject)v).getObjectKeyAsString());Collections.sort(out);return out;}
    void describe(ClientServerObject o,Map<String,Object> out){
        if(o==sim){out.put("report_types",reportTypes());out.put("extra_actions",new TreeSet<>(TYPES));}
        if(o instanceof CustomMeshControl){CustomMeshControl c=(CustomMeshControl)o;out.put("owner_id",((ClientServerObject)c.getAutoMeshBase()).getObjectKeyAsString());out.put("enabled",c.getEnableControl());out.put("selection_ids",ids(c.getGeometryObjects().getObjects()));out.put("query",String.valueOf(c.getGeometryObjects().getQuery()));
            if(c instanceof SurfaceCustomMeshControl && c.getAutoMeshBase() instanceof AutoMeshOperation && ((AutoMeshOperation)c.getAutoMeshBase()).hasPrismMesher())out.put("prism",surfacePrism(c));}
        PartGroup group=partGroup(o);if(group!=null){out.put("selection_ids",ids(group.getObjects()));out.put("selection_query",String.valueOf(group.getQuery()));}
        if(o instanceof FieldFunction){FieldFunction f=(FieldFunction)o;out.put("function_name",f.getFunctionName());out.put("field_type",f.getTypeAsEnum().name());if(o instanceof UserFieldFunction)out.put("definition",((UserFieldFunction)o).getDefinition());}
        if(o instanceof ScalarReport){FieldFunction field=((ScalarReport)o).getFieldFunction();out.put("field_id",field==null?"":field.getObjectKeyAsString());out.put("field_assigned",field!=null);}
        if(o instanceof AnalysisReport){Representation representation=((AnalysisReport)o).getRepresentation();out.put("representation_id",representation==null?"":representation.getObjectKeyAsString());out.put("representation_assigned",representation!=null);}
        if(o instanceof PlaneSection){PlaneSection p=(PlaneSection)o;out.put("origin_m",vec(p.getOriginCoordinate().getSIValue()));out.put("normal",vec(p.getOrientation()));}
        if(o instanceof Scene)out.put("displayer_ids",ids(((Scene)o).getDisplayerManager().getObjects()));
        if(o instanceof Displayer){out.put("scene_id",((Displayer)o).getScene().getObjectKeyAsString());out.put("can_delete",((Displayer)o).canDelete());}
        if(group!=null){out.put("static_selection_editable",group.getQuery()==null);out.put("empty_selection_allowed",group.getQuery()==null);}
        if(o instanceof PhysicsContinuum)out.put("enabled_models",new TreeSet<>(((PhysicsContinuum)o).getEnabledModels().getKeys()));
    }
    static List<Double> vec(DoubleVector v){return List.of(v.get(0),v.get(1),v.get(2));}
    Map<String,Object> surfacePrism(CustomMeshControl c){Map<String,Object> r=new LinkedHashMap<>();try{
        PartsCustomizePrismMesh p=c.getCustomConditions().get(PartsCustomizePrismMesh.class);int mode=p.getCustomPrismOptions().getSelected();r.put("mode",mode==PartsCustomPrismsOption.DISABLE?"disable":mode==PartsCustomPrismsOption.CUSTOMIZE?"custom":"parent");
        if(mode==PartsCustomPrismsOption.CUSTOMIZE){CustomPrismValuesManager v=c.getCustomValues().get(CustomPrismValuesManager.class);r.put("layers",v.getNumPrismLayers().getNumLayers());r.put("total_m",v.getPrismThickness().getAbsoluteSizeValue().getInternalValue());r.put("first_layer_m",v.get(PrismWallThickness.class).getInternalValue());}
    }catch(RuntimeException e){r.put("read_error",e.toString());}return r;}
    static PartGroup partGroup(ClientServerObject o){
        if(o instanceof AnalysisReport)return ((AnalysisReport)o).getParts();
        if(o instanceof Part)return ((Part)o).getInputParts();
        if(o instanceof Displayer)return ((Displayer)o).getInputParts();return null;
    }
    List<ClientServerObject> select(Map<?,?> a,String key)throws IOException{return select(a,key,false);}
    List<ClientServerObject> select(Map<?,?> a,String key,boolean allowEmpty)throws IOException{
        Object raw=a.get(key);if(!(raw instanceof List)||(!allowEmpty&&((List<?>)raw).isEmpty()))throw new IOException(key+(allowEmpty?"需要显式ID列表，可为[]以清空静态引用":"需要非空ID列表"));
        List<ClientServerObject> result=new ArrayList<>();Set<Object> unique=new HashSet<>();for(Object id:(List<?>)raw){ClientServerObject o=objects.get(id);if(o==null||!unique.add(id))throw new IOException("选择ID不属于当前SIM或重复: "+id);result.add(o);}return result;
    }
    static String name(Map<?,?> a)throws IOException{Object x=a.get("name");if(!(x instanceof String)||!x.toString().matches("[\\p{L}_][\\p{L}\\p{N}_ .-]{0,79}"))throw new IOException("名称无效");return x.toString();}
    static void unique(String name,Collection<? extends ClientServerObject> list)throws IOException{for(ClientServerObject o:list)if(o.getPresentationName().equals(name))throw new IOException("同名对象已存在: "+name);}
    static double positive(Object x)throws IOException{if(!(x instanceof Number)||!Double.isFinite(((Number)x).doubleValue())||((Number)x).doubleValue()<=0)throw new IOException("需有限正数");return ((Number)x).doubleValue();}
    static void require(boolean yes,String error)throws IOException{if(!yes)throw new IOException(error);}
    static void formula(Object x)throws IOException{require(x instanceof String&&!x.toString().isBlank()&&x.toString().length()<=8192,"表达式不能为空或超过8192字符");}
    Set<PartSurface> meshSurfaces(AutoMeshOperation op){Set<PartSurface> r=new LinkedHashSet<>();for(GeometryObject g:op.getInputGeometryObjects().getObjects())if(g instanceof GeometryPart)r.addAll(((GeometryPart)g).getPartSurfaces());return r;}
    void validateSelection(List<ClientServerObject> selected,String kind,AutoMeshOperation op)throws IOException{
        for(ClientServerObject o:selected){if(kind.equals("surface"))require(o instanceof PartSurface&&meshSurfaces(op).contains(o),"表面必须属于此网格操作的实际输入，布尔前原CAD面不能代替输出面");
            else require(o instanceof GeometryPart,"体/部件控制需要几何部件");}
    }
    void validate(Map<?,?> a,ClientServerObject o,String goal)throws Exception{
        String t=a.get("type").toString();
        switch(t){
        case "create_boundary":
            require(o instanceof Region,"create_boundary需要区域");Region region=(Region)o;String boundaryName=name(a);boundaryClass(a.get("boundary_type"));List<ClientServerObject> requested=select(a,"selection_ids");Boundary candidate=namedBoundary(region,boundaryName);
            if(candidate!=null)validateBoundaryReuse(candidate,requested,a.get("boundary_type"));
            for(ClientServerObject f:requested){require(f instanceof PartSurface&&region.getPartGroup().getObjects().contains(((PartSurface)f).getPart()),"边界表面须属于目标区域的几何部件");Boundary existing=((PartSurface)f).hasBoundary();if(existing!=null)require(!existing.isInternallyManaged()&&!existing.isOwnedByOther()&&existing.getDependentInterfaces().isEmpty()&&!existing.getPartSurfaceGroup().hasQuery(),"接口、受管理或查询驱动边界不能移动");}break;
        case "set_boundary_type":require(o instanceof Boundary,"需要边界");checkBoundary((Boundary)o,a.get("boundary_type"));break;
        case "create_mesh_control":case "limit_prisms_to_surfaces":
            require(o instanceof AutoMeshOperation,"需要自动网格操作");AutoMeshOperation op=(AutoMeshOperation)o;unique(name(a),op.getCustomMeshControls().getObjects());
            String kind=t.equals("limit_prisms_to_surfaces")?"surface":String.valueOf(a.get("control_type"));require(Set.of("surface","volume","part").contains(kind),"control_type必须surface/volume/part");validateSelection(select(a,"selection_ids"),kind,op);
            if(t.equals("limit_prisms_to_surfaces")){require(op.hasPrismMesher(),"先启用Prism网格器");unique(name(a)+"_others",op.getCustomMeshControls().getObjects());
                for(CustomMeshControl c:op.getCustomMeshControls().getObjects())if(c.getEnableControl()){
                    if(c instanceof SurfaceCustomMeshControl||c instanceof PartCustomMeshControl){PartsCustomizePrismMesh p=c.getCustomConditions().get(PartsCustomizePrismMesh.class);require(p.getCustomPrismOptions().getSelected()==PartsCustomPrismsOption.PARENT,"已有局部棱柱覆盖: "+c.getPresentationName()+"，先检查并按目标修改/停用该控制，不能叠加冲突");}
                    if(c instanceof VolumeCustomMeshControl){VolumeControlPrismsOption p=c.getCustomConditions().get(VolumeControlPrismsOption.class);require(!p.getCustomizeNumLayers()&&!p.getCustomizeTotalThickness()&&!p.getCustomizeStretching(),"已有体棱柱覆盖，先处理冲突: "+c.getPresentationName());}
                }
            }break;
        case "set_control_enabled":require(o instanceof CustomMeshControl&&a.get("enabled") instanceof Boolean,"需要控制对象和enabled布尔值");break;
        case "configure_surface_prisms":
            require(o instanceof SurfaceCustomMeshControl,"需要表面控制");require(((AutoMeshOperation)((CustomMeshControl)o).getAutoMeshBase()).hasPrismMesher(),"先启用Prism网格器");
            require(Set.of("parent","disable","custom").contains(a.get("mode")),"mode=parent/disable/custom");
            if(a.get("mode").equals("custom")){SimulationActions.prismTotal(a.get("layers"),a.get("first_layer_m"),a.get("growth_ratio"));PrismAutoMesher pm=((AutoMeshOperation)((CustomMeshControl)o).getAutoMeshBase()).getMeshers().hasMesher(PrismAutoMesher.class);require(pm.getPrismStretchingOption().getSelected()==PrismStretchingOption.WALL_THICKNESS && pm.getPrismStretchingFunction().getSelected()==PrismStretchingFunction.GEOMETRIC_PROGRESSION,"局部首层厚度需网格器为几何增长/WALL_THICKNESS模式，先用configure_prism_layers设置默认值；不会隐式改变其他面分布模式");}break;
        case "configure_surface_size":require(o instanceof SurfaceCustomMeshControl,"需要表面控制");require(positive(a.get("min_m"))<=positive(a.get("target_m")),"最小尺寸不能大于目标尺寸");break;
        case "configure_volume_size":require(o instanceof VolumeCustomMeshControl,"需要体控制");positive(a.get("size_m"));AutoMeshOperation mo=(AutoMeshOperation)((CustomMeshControl)o).getAutoMeshBase();require(mo.getMeshers().hasMesher(star.dualmesher.DualAutoMesher.class)!=null||mo.getMeshers().hasMesher(star.trimmer.TrimmerAutoMesher.class)!=null,"只支持多面体或切割体尺寸控制");break;
        case "set_object_selection":
            List<ClientServerObject> selected=select(a,"selection_ids",!(o instanceof CustomMeshControl));
            if(o instanceof CustomMeshControl){CustomMeshControl c=(CustomMeshControl)o;require(c.getGeometryObjects().getQuery()==null,"查询选择不能静默改成静态选择");validateSelection(selected,c instanceof SurfaceCustomMeshControl?"surface":"volume",(AutoMeshOperation)c.getAutoMeshBase());}
            else{PartGroup g=partGroup(o);require(g!=null&&g.getQuery()==null,"需要报告/派生部件/显示器的静态部件集合");for(ClientServerObject x:selected)require(x instanceof Part||x instanceof Region||x instanceof Boundary||x instanceof GeometryPart||x instanceof PartSurface,"不是可选几何/区域/边界/派生部件");require(!selected.contains(o),"派生部件不能引用自身");}break;
        case "create_physics_continuum":require(o==sim,"需要simulation_actions");unique(name(a),sim.getContinuumManager().getObjects());break;
        case "create_report":require(o==sim,"需要simulation_actions");unique(name(a),sim.getReportManager().getObjects());require(reportTypes().contains(a.get("report_type")),"report_type必须来自当前report_types目录");break;
        case "set_report_field":require(o instanceof ScalarReport,"需要标量分析报告");require(objects.get(a.get("field_id")) instanceof FieldFunction,"场函数ID不存在");require(((FieldFunction)objects.get(a.get("field_id"))).getTypeAsEnum()==FieldFunctionTypeOption.Type.SCALAR,"报告需要标量场函数");if(a.containsKey("representation_id"))require(objects.get(a.get("representation_id")) instanceof Representation,"表示ID不存在");break;
        case "create_report_monitor":require(o instanceof Report,"需要报告");unique(name(a),sim.getMonitorManager().getObjects());break;
        case "create_field_function":require(o==sim,"需要simulation_actions");unique(name(a),sim.getFieldFunctionManager().getObjects());require(name(a).matches("[A-Za-z_][A-Za-z0-9_]*")&&!sim.getFieldFunctionManager().hasFunction(name(a)),"场函数需唯一ASCII标识符");require(Set.of("SCALAR","VECTOR").contains(a.get("field_type")),"field_type=SCALAR/VECTOR");formula(a.get("definition"));dimensions(a.get("dimensions"));break;
        case "create_global_parameter":require(o==sim,"需要simulation_actions");unique(name(a),sim.getGlobalParameterManager().getObjects());require(Set.of("scalar","vector").contains(a.get("parameter_type")),"parameter_type=scalar/vector");dimensions(a.get("dimensions"));formula(a.get("definition"));break;
        case "create_plane_section":require(o==sim,"需要simulation_actions");unique(name(a),sim.getPartManager().getObjects());for(ClientServerObject x:select(a,"selection_ids"))require(x instanceof Region,"截面输入必须为区域");SimulationActions.vector(a.get("origin_m"));double[] n=SimulationActions.vector(a.get("normal"));require(n[0]*n[0]+n[1]*n[1]+n[2]*n[2]>0,"法向量不可为0");break;
        case "create_scene":require(o==sim,"需要simulation_actions");unique(name(a),sim.getSceneManager().getScenes());require(Set.of("mesh","scalar","vector").contains(a.get("scene_type")),"scene_type=mesh/scalar/vector");break;
        default:throw new IOException("未注册扩展操作");
        }
    }
    static Boundary namedBoundary(Region region,String name){for(Boundary boundary:region.getBoundaryManager().getBoundaries())if(boundary.getPresentationName().equals(name))return boundary;return null;}
    static void validateBoundaryReuse(Boundary boundary,List<ClientServerObject> selected,Object type)throws IOException{
        require(!boundary.isInternallyManaged()&&!boundary.isOwnedByOther()&&boundary.getDependentInterfaces().isEmpty(),"同名边界受管理或参与接口，不能复用");
        require(!boundary.getPartSurfaceGroup().hasQuery(),"同名边界使用动态查询，不能以创建操作覆盖");
        require(boundaryClass(type).isInstance(boundary.getBoundaryType()),"同名边界类型不同，不能用create_boundary隐式切换；请显式选择该边界的set_boundary_type并重读参数");
        List<String> before=ids(boundary.getPartSurfaceGroup().getObjects()),after=ids(selected);
        require(before.isEmpty()||before.equals(after),"同名边界已有不同或额外表面，不能用创建操作丢弃/替换其选择；需显式检查并修改此边界");
    }
    static IntVector dimensions(Object raw)throws IOException{require(raw instanceof List&&((List<?>)raw).size()==7,"dimensions须7个整数[质量,长度,时间,温度,电流,物质的量,光强]，无量纲填全0");int[] x=new int[7];for(int i=0;i<7;i++){Object v=((List<?>)raw).get(i);require(v instanceof Number&&Double.isFinite(((Number)v).doubleValue())&&((Number)v).doubleValue()==Math.rint(((Number)v).doubleValue())&&Math.abs(((Number)v).doubleValue())<=20,"量纲指数须-20..20整数");x[i]=((Number)v).intValue();}return Dimensions.Builder().mass(x[0]).length(x[1]).time(x[2]).temperature(x[3]).current(x[4]).quantity(x[5]).luminosity(x[6]).build().getDimensions();}
    void flush(){sim.getServer().getClientNotifyHandler().processCommandQueue(true);}
    void setSelection(CustomMeshControl c,List<? extends ClientServerObject> selected)throws IOException{
        List<GeometryObject> geo=new ArrayList<>();for(ClientServerObject o:selected)geo.add((GeometryObject)o);
        DynamicQuerySelectorInput q=c instanceof SurfaceCustomMeshControl?((SurfaceCustomMeshControl)c).getGeometryObjectsInput():c instanceof VolumeCustomMeshControl?((VolumeCustomMeshControl)c).getGeometryObjectsInput():((PartCustomMeshControl)c).getGeometryObjectsInput();
        c.setGeometryObjectsInput(DynamicQuerySelectorInput.builder(q).descriptor(q.getModelDescriptor().cloneBuilder().selected(geo).build()).build());flush();require(ids(c.getGeometryObjects().getObjects()).equals(ids(selected)),"控制选择读回不一致");
    }
    void mode(CustomMeshControl c,int mode){c.getCustomConditions().get(PartsCustomizePrismMesh.class).getCustomPrismOptions().setSelected(mode);flush();}
    Map<String,Object> execute(Map<?,?> a,ClientServerObject o)throws Exception{
        String t=a.get("type").toString();Map<String,Object> out=new LinkedHashMap<>();ClientServerObject made=null;
        switch(t){
        case "create_boundary":{
            Region region=(Region)o;List<ClientServerObject> selected=select(a,"selection_ids");Boundary b=namedBoundary(region,name(a));boolean reused=b!=null;
            List<String> beforeSelection=reused?ids(b.getPartSurfaceGroup().getObjects()):List.of();Map<String,Object> before=new LinkedHashMap<>();before.put("existed",reused);before.put("selection_ids",beforeSelection);if(reused){validateBoundaryReuse(b,selected,a.get("boundary_type"));before.put("boundary_id",b.getObjectKeyAsString());before.put("boundary_type",b.getBoundaryType().getClass().getName());}
            out.put("before",before);out.put("reused_existing",reused);
            if(reused&&beforeSelection.equals(ids(selected))){out.put("skipped",true);out.put("boundary_id",b.getObjectKeyAsString());out.put("boundary_type",b.getBoundaryType().getClass().getName());out.put("selection_ids",beforeSelection);out.put("actual",Map.of("boundary_id",b.getObjectKeyAsString(),"boundary_type",b.getBoundaryType().getClass().getName(),"selection_ids",beforeSelection));out.put("validation","同一区域同名同类型边界已包含完全相同的实际表面，幂等跳过；未更改物理参数、类型或选面");return out;}
            if(!reused){b=region.getBoundaryManager().createEmptyBoundary(name(a));b.setBoundaryType(boundaryClass(a.get("boundary_type")));}
            for(ClientServerObject f:selected)((PartSurface)f).setBoundary(b);flush();require(ids(b.getPartSurfaceGroup().getObjects()).equals(ids(selected)),"边界选择读回不一致");for(ClientServerObject f:selected)require(((PartSurface)f).getBoundary().equals(b),"表面归属读回不一致");out.put("boundary_id",b.getObjectKeyAsString());out.put("boundary_type",b.getBoundaryType().getClass().getName());out.put("selection_ids",ids(b.getPartSurfaceGroup().getObjects()));out.put("actual",Map.of("boundary_id",b.getObjectKeyAsString(),"boundary_type",b.getBoundaryType().getClass().getName(),"selection_ids",ids(b.getPartSurfaceGroup().getObjects())));out.put("validation",reused?"复用同一区域现有同名、空、静态、非受管理且同类型边界；仅添加给定表面，保留全部已有物理参数":"已创建边界并按实际选面读回；其他边界表面保留");if(reused)out.put("reused_id",b.getObjectKeyAsString());else made=b;break;}
        case "set_boundary_type":{Boundary b=(Boundary)o;b.setBoundaryType(boundaryClass(a.get("boundary_type")));flush();require(boundaryClass(a.get("boundary_type")).isInstance(b.getBoundaryType()),"边界类型读回不一致");out.put("boundary_type",b.getBoundaryType().getClass().getName());break;}
        case "create_mesh_control":{
            AutoMeshOperation op=(AutoMeshOperation)o;String kind=a.get("control_type").toString();CustomMeshControl c=kind.equals("surface")?op.getCustomMeshControls().createSurfaceControl():kind.equals("volume")?op.getCustomMeshControls().createVolumeControl():op.getCustomMeshControls().createPartControl();c.setPresentationName(name(a));setSelection(c,select(a,"selection_ids"));made=c;break;}
        case "limit_prisms_to_surfaces":{
            AutoMeshOperation op=(AutoMeshOperation)o;List<ClientServerObject> yes=select(a,"selection_ids");List<PartSurface> no=new ArrayList<>(meshSurfaces(op));no.removeAll(yes);
            SurfaceCustomMeshControl c=op.getCustomMeshControls().createSurfaceControl();c.setPresentationName(name(a));setSelection(c,yes);mode(c,PartsCustomPrismsOption.PARENT);c.setEnableControl(true);made=c;
            if(!no.isEmpty()){SurfaceCustomMeshControl other=op.getCustomMeshControls().createSurfaceControl();other.setPresentationName(name(a)+"_others");setSelection(other,no);mode(other,PartsCustomPrismsOption.DISABLE);other.setEnableControl(true);flush();require(other.getEnableControl()&&other.getCustomConditions().get(PartsCustomizePrismMesh.class).getCustomPrismOptions().getSelected()==PartsCustomPrismsOption.DISABLE,"非目标表面禁用读回失败");out.put("disabled_control_id",other.getObjectKeyAsString());}
            out.put("target_surface_ids",ids(yes));out.put("disabled_surface_ids",ids(no));out.put("validation","已限定当前输入面的棱柱层作用范围；几何替换新增面后需重新检查，需重划网格才能生效，实际y+未验证");break;}
        case "set_control_enabled":((CustomMeshControl)o).setEnableControl((Boolean)a.get("enabled"));flush();require(((CustomMeshControl)o).getEnableControl()==(Boolean)a.get("enabled"),"控制启停读回失败");break;
        case "configure_surface_prisms":{
            CustomMeshControl c=(CustomMeshControl)o;String m=a.get("mode").toString();int code=m.equals("custom")?PartsCustomPrismsOption.CUSTOMIZE:m.equals("disable")?PartsCustomPrismsOption.DISABLE:PartsCustomPrismsOption.PARENT;mode(c,code);
            if(m.equals("custom")){PartsCustomizePrismMeshControls flags=c.getCustomConditions().get(PartsCustomizePrismMesh.class).getCustomPrismControls();flags.setCustomizeNumLayers(true);flags.setCustomizeTotalThickness(true);flags.setCustomizeStretching(true);flush();
                CustomPrismValuesManager v=c.getCustomValues().get(CustomPrismValuesManager.class);int n=((Number)a.get("layers")).intValue();double h=positive(a.get("first_layer_m")),total=SimulationActions.prismTotal(a.get("layers"),h,a.get("growth_ratio"));
                v.getNumPrismLayers().setNumLayers(n);v.getPrismThickness().getRelativeOrAbsoluteOption().setToAbsolute();v.getPrismThickness().setAbsoluteSizeValue(total,metres());v.get(PrismWallThickness.class).setValueAndUnits(h,metres());flush();
                require(flags.getCustomizeNumLayers()&&flags.getCustomizeTotalThickness()&&flags.getCustomizeStretching(),"局部覆盖开关未生效");require(v.getPrismThickness().getRelativeOrAbsoluteOption().getSelected()==RelativeOrAbsoluteOption.ABSOLUTE,"局部总厚度不是绝对值");require(v.getNumPrismLayers().getNumLayers()==n,"局部层数不一致");close(v.getPrismThickness().getAbsoluteSizeValue().getInternalValue(),total);close(v.get(PrismWallThickness.class).getInternalValue(),h);
            }require(c.getCustomConditions().get(PartsCustomizePrismMesh.class).getCustomPrismOptions().getSelected()==code,"局部棱柱模式不一致");out.put("prism",surfacePrism(c));break;}
        case "configure_surface_size":{
            CustomMeshControl c=(CustomMeshControl)o;c.getCustomConditions().get(PartsTargetSurfaceSizeOption.class).setSelected(PartsTargetSurfaceSizeOption.CUSTOM);c.getCustomConditions().get(PartsMinimumSurfaceSizeOption.class).setSelected(PartsMinimumSurfaceSizeOption.CUSTOM);flush();
            PartsTargetSurfaceSize target=c.getCustomValues().get(PartsTargetSurfaceSize.class);PartsMinimumSurfaceSize min=c.getCustomValues().get(PartsMinimumSurfaceSize.class);target.getRelativeOrAbsoluteOption().setToAbsolute();min.getRelativeOrAbsoluteOption().setToAbsolute();target.getAbsoluteSizeValue().setValueAndUnits(positive(a.get("target_m")),metres());min.getAbsoluteSizeValue().setValueAndUnits(positive(a.get("min_m")),metres());flush();require(target.getRelativeOrAbsoluteOption().getSelected()==RelativeOrAbsoluteOption.ABSOLUTE&&min.getRelativeOrAbsoluteOption().getSelected()==RelativeOrAbsoluteOption.ABSOLUTE,"尺寸绝对模式未生效");close(target.getAbsoluteSizeValue().getInternalValue(),positive(a.get("target_m")));close(min.getAbsoluteSizeValue().getInternalValue(),positive(a.get("min_m")));out.put("target_m",target.getAbsoluteSizeValue().getInternalValue());out.put("min_m",min.getAbsoluteSizeValue().getInternalValue());break;}
        case "configure_volume_size":{
            CustomMeshControl c=(CustomMeshControl)o;AutoMeshOperation op=(AutoMeshOperation)c.getAutoMeshBase();if(op.getMeshers().hasMesher(star.dualmesher.DualAutoMesher.class)!=null)c.getCustomConditions().get(star.dualmesher.VolumeControlDualMesherSizeOption.class).setVolumeControlDualMesherSizeOption(true);
            if(op.getMeshers().hasMesher(star.trimmer.TrimmerAutoMesher.class)!=null)c.getCustomConditions().get(star.trimmer.VolumeControlTrimmerSizeOption.class).setVolumeSourceTrimmerSizeOption(true);
            flush();VolumeControlSize size=c.getCustomValues().get(VolumeControlSize.class);size.setAbsoluteSize(positive(a.get("size_m")),metres());flush();close(size.getAbsoluteSizeValue().getInternalValue(),positive(a.get("size_m")));out.put("size_m",size.getAbsoluteSizeValue().getInternalValue());break;}
        case "set_object_selection":{List<ClientServerObject> x=select(a,"selection_ids",!(o instanceof CustomMeshControl));if(o instanceof CustomMeshControl)setSelection((CustomMeshControl)o,x);else{PartGroup g=partGroup(o);g.setObjects(named(x));flush();require(ids(g.getObjects()).equals(ids(x)),"选择集合读回不一致");}break;}
        case "create_physics_continuum":{
            Map<Region,PhysicsContinuum> before=new LinkedHashMap<>();for(Region r:sim.getRegionManager().getRegions())before.put(r,r.getPhysicsContinuum());
            PhysicsContinuum c=sim.getContinuumManager().createContinuum(PhysicsContinuum.class);flush();
            for(Map.Entry<Region,PhysicsContinuum> b:before.entrySet())if(!Objects.equals(b.getKey().getPhysicsContinuum(),b.getValue())){
                if(b.getValue()==null)sim.getContinuumManager().eraseFromContinuum(List.of(b.getKey()),c);else sim.getContinuumManager().setContinuum(List.of(b.getKey()),b.getValue());
            }flush();for(Map.Entry<Region,PhysicsContinuum> b:before.entrySet())require(Objects.equals(b.getKey().getPhysicsContinuum(),b.getValue()),"创建连续体时区域绑定发生变化");made=c;break;}
        case "create_report":made=sim.getReportManager().create(a.get("report_type").toString());break;
        case "set_report_field":{ScalarReport r=(ScalarReport)o;FieldFunction f=(FieldFunction)objects.get(a.get("field_id"));r.setFieldFunction(f);if(a.containsKey("representation_id"))r.setRepresentation((Representation)objects.get(a.get("representation_id")));flush();require(r.getFieldFunction().equals(f),"场函数读回不一致");if(a.containsKey("representation_id"))require(r.getRepresentation().equals(objects.get(a.get("representation_id"))),"表示读回不一致");break;}
        case "create_report_monitor":made=((Report)o).createMonitor();break;
        case "create_field_function":{UserFieldFunction f=sim.getFieldFunctionManager().createFieldFunction();f.setFunctionName(name(a));f.getTypeOption().setSelected(FieldFunctionTypeOption.Type.valueOf(a.get("field_type").toString()));f.setDimensionsVector(dimensions(a.get("dimensions")));f.setDefinition(a.get("definition").toString());flush();require(sameDefinition(f.getDefinition(),a.get("definition").toString()),"场函数定义读回失败");made=f;break;}
        case "create_global_parameter":{boolean scalar=a.get("parameter_type").equals("scalar");GlobalParameterBase<?,?> p=scalar?sim.getGlobalParameterManager().createGlobalParameter(ScalarGlobalParameter.class,name(a)):sim.getGlobalParameterManager().createGlobalParameter(VectorGlobalParameter.class,name(a));p.setDimensionsVector(dimensions(a.get("dimensions")));p.getQuantity().setDefinition(a.get("definition").toString());flush();require(sameDefinition(p.getQuantity().getDefinition(),a.get("definition").toString()),"全局参数读回失败 actual="+p.getQuantity().getDefinition());made=p;break;}
        case "create_plane_section":{PlaneSection p=sim.getPartManager().createPlaneSection();p.setCoordinateSystem(sim.getCoordinateSystemManager().getLabCoordinateSystem());p.getOriginCoordinate().setCoordinate(metres(),metres(),metres(),new DoubleVector(SimulationActions.vector(a.get("origin_m"))));p.setOrientation(new DoubleVector(SimulationActions.vector(a.get("normal"))));p.getInputParts().setObjects(named(select(a,"selection_ids")));flush();for(int k=0;k<3;k++)close(p.getOriginCoordinate().getSIValue().get(k),SimulationActions.vector(a.get("origin_m"))[k]);made=p;break;}
        case "create_scene":{Set<String> before=new HashSet<>(ids(sim.getSceneManager().getScenes()));String kind=a.get("scene_type").toString();if(kind.equals("mesh"))sim.getSceneManager().createGeometrySceneAndWait(name(a),"Outline","Mesh",1);else if(kind.equals("scalar"))sim.getSceneManager().createScalarSceneAndWait(name(a),"Outline","Scalar");else sim.getSceneManager().createVectorSceneAndWait(name(a),"Outline","Vector");flush();List<Scene> added=new ArrayList<>();for(Scene scene:sim.getSceneManager().getScenes())if(!before.contains(scene.getObjectKeyAsString()))added.add(scene);require(added.size()==1,"场景创建未返回唯一新对象: "+added.size());made=added.get(0);if(kind.equals("mesh")){for(Displayer d:added.get(0).getDisplayerManager().getObjects())if(d instanceof PartDisplayer&&((PartDisplayer)d).getSurface()){((PartDisplayer)d).setMesh(true);for(Representation rep:sim.getRepresentationManager().getObjects())if(rep instanceof FvRepresentation&&rep.getPresentationName().equals("Volume Mesh"))d.setRepresentation(rep);flush();require(((PartDisplayer)d).getMesh(),"网格场景网格线未启用");}}break;}
        default:throw new IOException("未注册操作");
        }
        if(made!=null){((NamedObject)made).setPresentationName(name(a));flush();require(made.getPresentationName().equals(a.get("name")),"创建名称读回失败");out.put("created_id",made.getObjectKeyAsString());out.put("created_type",made.getClass().getName());describe(made,out);}
        else{flush();describe(o,out);}
        out.putIfAbsent("validation","当前SIM配置已读回；尚未重划网格或运行CFD，场函数/报告实际求值及工程结果需另验");return out;
    }
    static List<NamedObject> named(List<ClientServerObject> values){List<NamedObject> r=new ArrayList<>();for(ClientServerObject v:values)r.add((NamedObject)v);return r;}
    static boolean sameDefinition(String actual,String expected) {
        if(actual.trim().equals(expected.trim()))return true;
        try{Object a=CodexAssistant.parseJson(actual),b=CodexAssistant.parseJson(expected);
            if(a instanceof Number&&b instanceof Number)return ((Number)a).doubleValue()==((Number)b).doubleValue();
            if(a instanceof List&&b instanceof List){List<?> x=(List<?>)a,y=(List<?>)b;if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!(x.get(i) instanceof Number)||!(y.get(i) instanceof Number)||((Number)x.get(i)).doubleValue()!=((Number)y.get(i)).doubleValue())return false;return true;}
        }catch(Exception ignored){}return false;
    }
    static void checkBoundary(Boundary b,Object type)throws IOException {
        require(!b.isInternallyManaged()&&!b.isOwnedByOther()&&b.getDependentInterfaces().isEmpty(),"此边界由接口或其他对象管理");Class<? extends BoundaryType> c=boundaryClass(type);boolean available=false;for(ConditionType option:b.getAvailableTypes())if(c.isInstance(option))available=true;require(available,"当前物理模型不支持此边界类型");
    }
    static Class<? extends BoundaryType> boundaryClass(Object value)throws IOException {
        if("velocity_inlet".equals(value))return InletBoundary.class;if("pressure_outlet".equals(value))return PressureBoundary.class;if("symmetry".equals(value))return SymmetryBoundary.class;if("wall".equals(value))return WallBoundary.class;if("freestream".equals(value))return FreeStreamBoundary.class;
        throw new IOException("boundary_type=velocity_inlet/pressure_outlet/symmetry/wall/freestream");
    }
    Units metres(){return (Units)sim.getUnitsManager().getObject("m");}
    static void close(double actual,double expected)throws IOException{require(Double.isFinite(actual)&&Math.abs(actual-expected)<=Math.max(1e-14,Math.abs(expected)*1e-10),"数值读回不一致 expected="+expected+" actual="+actual);}
    static boolean deletable(ClientServerObject o){return o instanceof CustomMeshControl||o instanceof PhysicsContinuum||o instanceof UserFieldFunction||o instanceof GlobalParameterBase||o instanceof ReportMonitor||o instanceof StarPlot||o instanceof Part||o instanceof Scene||o instanceof Displayer;}
    void delete(ClientServerObject o)throws Exception{
        if(o instanceof CustomMeshControl)((CustomMeshControl)o).getManager().removeObjects(List.of((CustomMeshControl)o));
        else if(o instanceof PhysicsContinuum)sim.getContinuumManager().removeObjects(List.of((PhysicsContinuum)o));
        else if(o instanceof UserFieldFunction)sim.getFieldFunctionManager().removeObjects(List.of((UserFieldFunction)o));
        else if(o instanceof GlobalParameterBase)sim.getGlobalParameterManager().deleteGlobalParameter((NamedObject)o);
        else if(o instanceof ReportMonitor)sim.getMonitorManager().removeObjects(List.of((ReportMonitor)o));
        else if(o instanceof StarPlot)sim.getPlotManager().deletePlots(List.of((StarPlot)o));
        else if(o instanceof Part)sim.getPartManager().deletePart((Part)o);
        else if(o instanceof Scene)sim.getSceneManager().deleteScene((Scene)o);
        else if(o instanceof Displayer)((Displayer)o).getScene().getDisplayerManager().deleteDisplayer((Displayer)o);
        else throw new IOException("不可删除此类型");flush();
    }
    static String contract(){return "\ncreate_boundary: id=Region,name,selection_ids=[该区域几何部件的PartSurface],boundary_type=velocity_inlet/pressure_outlet/symmetry/wall/freestream；将这些面从原边界移动到新边界，不删除区域/网格/布尔，其他面保留。若同一区域已有同名、空、静态、非受管理且同类型边界则复用，仅添加给定面并保留已有物理参数；已有完全相同选面和类型时幂等跳过。同名非空不同选面、类型不一致、查询或接口边界拒绝，不隐式丢面/重置参数/切换类型。set_boundary_type: id=Boundary,boundary_type同上；改变类型后重读新参数目录。拆面后可保留原边界中的一个面，逐个把其余面分到新边界；不能把既有引用视为必须删除重建的理由。\n扩展动作，仍每阶段1项，使用实际ID和expected_state；创建后重读，不猜ID。"
        +"create_mesh_control: id=AutoMeshOperation,name,control_type=surface/volume/part,selection_ids；surface必须是此操作实际输入的PartSurface，volume/part为GeometryPart。"
        +"limit_prisms_to_surfaces: id=AutoMeshOperation,name,selection_ids=[当前输入的目标PartSurface]；用于只对机翼等指定面加层，目标面继承默认，其余输入面建立禁用控制，已有冲突控制须先inspect并修改/停用。此动作不设置首层数值、不划网格；先用configure_prism_layers设默认值，再限定范围。"
        +"configure_surface_prisms: id=SurfaceCustomMeshControl,mode=parent/disable/custom；custom另含layers,first_layer_m,growth_ratio，与默认接口同义，需网格器已采用几何增长/WALL_THICKNESS模式。可修改和恢复父级默认，不需要重建整个网格。"
        +"configure_surface_size: id=SurfaceCustomMeshControl,target_m,min_m；显式覆盖绝对目标/最小表面尺寸。configure_volume_size: id=VolumeCustomMeshControl,size_m；多面体/切割体绝对体网格尺寸。"
        +"set_control_enabled: id=CustomMeshControl,enabled=true/false。delete_object同时支持局部控制、连续体、用户场函数、全局参数、报告监视器、绘图、派生部件、场景及可删除显示器；依赖阻止时先读引用，不能删无关对象。"
        +"set_object_selection: id=CustomMeshControl/AnalysisReport/派生Part/Displayer,selection_ids；替换完整静态集合。报告/派生部件/显示器可显式selection_ids=[]清空引用以解除删除阻塞，不删除对象；局部网格控制仍要求非空选择。报告/显示器选Boundary/Region/Part/GeometryPart/PartSurface，查询集合拒绝覆盖。"
        +"create_physics_continuum: id=simulation_actions,name；创建后从writable目录逐项选物理模型、材料、参考/初始条件，再绑定区域。"
        +"create_report: id=simulation_actions,name,report_type=当前simulation_actions.report_types中的类型；升力/阻力/力矩及系数报告优先查create_aerodynamic_report，一次完成选面/参考量/监视器；本通用工厂保留用于其他报告类型。创建后用set_object_selection和writable设置方向、参考面积/长度/密度/速度等；不能用默认报告参数冒充用户工况。"
        +"set_report_field: id=ScalarReport,field_id=当前标量FieldFunction，可选representation_id=当前Representation。create_report_monitor: id=Report,name。"
        +"create_field_function: id=simulation_actions,name=ASCII标识符,field_type=SCALAR/VECTOR,definition=STAR表达式,dimensions=[质量,长度,时间,温度,电流,物质的量,光强]七整数；create_global_parameter: id=simulation_actions,name,parameter_type=scalar/vector,definition,dimensions；读回定义不保证表达式求值有效。"
        +"create_plane_section: id=simulation_actions,name,selection_ids=[Region],origin_m=[x,y,z],normal=[nx,ny,nz]，Lab坐标。create_scene: id=simulation_actions,name,scene_type=mesh/scalar/vector；随后按目录给各Displayer设置选择和属性。"
        +"接口索引检索命中不等于可调用，必须使用本轮动作/可写目录。不能因旧聊天说不支持就停止，先检查当前能力；若仍缺失，具体记录对象类型和动作。\n";}
}
