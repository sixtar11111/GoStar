package local.starccm.assistant;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import star.base.neo.*;
import star.common.*;
import star.meshing.*;
import star.prismmesher.*;

/** Explicit typed operations against the panel's pinned simulation. No model-supplied code. */
final class SimulationActions {
    private final Simulation sim;
    private final Map<String,ClientServerObject> objects=new LinkedHashMap<>();
    private final ExtendedActions extended;
    private final CommonActions060 common;
    private final AerodynamicReports aerodynamic;
    private final Map<String,String> states=new LinkedHashMap<>();
    private volatile boolean stopped;
    private volatile boolean solving;
    private boolean solverUsed;
    private Map<String,Object> solverMeshCheck=Map.of();
    private volatile MeshOperation running;
    static final Set<String> TYPES=new HashSet<>(Set.of("create_block","import_cad","create_volume_mesh","create_subtract","create_regions","execute_boolean","set_part_selection","run_solver","delete_object","update_block","rename_object","split_surfaces","set_prism_mesher","configure_prism_layers"));
    static { TYPES.addAll(ExtendedActions.TYPES);TYPES.addAll(CommonActions060.TYPES);TYPES.addAll(AerodynamicReports.TYPES);TYPES.addAll(NativeGeometryRepair.TYPES);TYPES.add("configure_mesh_defaults");TYPES.add("configure_boolean");TYPES.addAll(Set.of("configure_physics_profile","configure_external_physics","configure_flow_conditions","prepare_external_aero","repair_external_aero","create_parts_from_cad")); }
    SimulationActions(Simulation sim){this.sim=sim;this.extended=new ExtendedActions(sim,objects);this.common=new CommonActions060(sim,objects);this.aerodynamic=new AerodynamicReports(sim,objects);}
    void refresh() {
        objects.clear();states.clear();add(sim);
        List<GeometryPart> geometry=new ArrayList<>(sim.get(SimulationPartManager.class).getParts());for(GeometryPart leaf:sim.get(SimulationPartManager.class).getLeafParts())if(!geometry.contains(leaf))geometry.add(leaf);
        for(GeometryPart p:geometry) {
            add(p);if(p instanceof MeshPart)for(PartSurface f:((MeshPart)p).getPartSurfaceManager().getObjects())add(f);
        }
        if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation p:sim.get(MeshOperationManager.class).getObjects())add(p);
        for(star.base.report.Report r:sim.getReportManager().getObjects())add(r);
        for(Region r:sim.getRegionManager().getRegions()) {add(r);for(Boundary b:r.getBoundaryManager().getBoundaries())add(b);}
        extended.collect(this::add);common.collect(this::add);for(ClientServerObject cad:CadPartCreation.objects(sim).values())add(cad);
    }
    Map<String,ClientServerObject> rootObjects(){return new LinkedHashMap<>(objects);}
    private void add(ClientServerObject o){objects.put(o.getObjectKeyAsString(),o);states.put(o.getObjectKeyAsString(),ConfigurationEngine.stateToken(describe(o)));}
    Map<String,Object> describe(ClientServerObject o){
        Map<String,Object> m=new LinkedHashMap<>();m.put("id",o.getObjectKeyAsString());m.put("name",o.getPresentationName());m.put("type",o.getClass().getName());
        if(o==sim){m.put("kind","simulation_actions");m.put("part_count",sim.get(SimulationPartManager.class).getParts().size());m.put("leaf_part_count",sim.get(SimulationPartManager.class).getLeafParts().size());m.put("leaf_parts",ids(sim.get(SimulationPartManager.class).getLeafParts()));m.put("region_count",sim.getRegionManager().getRegions().size());m.put("mesh_operation_count",sim.has(MeshOperationManager.class)==null?0:sim.get(MeshOperationManager.class).getObjects().size());m.put("iteration",sim.getSimulationIterator().getCurrentIteration());m.put("parts",ids(sim.get(SimulationPartManager.class).getParts()));m.put("operations",sim.has(MeshOperationManager.class)==null?List.of():ids(sim.get(MeshOperationManager.class).getObjects()));}
        if(o instanceof MeshOperation){MeshOperation p=(MeshOperation)o;m.put("input_ids",ids(p.getInputGeometryObjects().getObjects()));m.put("query",String.valueOf(p.getInputGeometryObjects().getQuery()));m.put("dirty",p.isDirty());}
        if(o instanceof AutoMeshOperation){m.put("prism_enabled",((AutoMeshOperation)o).hasPrismMesher());m.put("meshers",mesherNames((AutoMeshOperation)o));m.put("prism_settings",prismSettings((AutoMeshOperation)o));}
        if(o instanceof MeshPart){MeshPart part=(MeshPart)o;m.put("surface_ids",ids(part.getPartSurfaceManager().getObjects()));m.put("closed",part.isClosed());m.put("manifold",part.isManifold());}
        if(o instanceof PartSurface){m.put("part_id",((PartSurface)o).getPart().getObjectKeyAsString());Boundary b=((PartSurface)o).hasBoundary();m.put("boundary_id",b==null?null:b.getObjectKeyAsString());}
        if(o instanceof Boundary){m.put("boundary_type",((Boundary)o).getBoundaryType().getClass().getName());m.put("region_id",((Boundary)o).getRegion().getObjectKeyAsString());m.put("surface_ids",ids(((Boundary)o).getPartSurfaceGroup().getObjects()));m.put("query",String.valueOf(((Boundary)o).getPartSurfaceGroup().getQuery()));}
        if(o instanceof SimpleBlockPart){SimpleBlockPart b=(SimpleBlockPart)o;m.put("min_m",Arrays.asList(b.getCorner1().getSIValue().get(0),b.getCorner1().getSIValue().get(1),b.getCorner1().getSIValue().get(2)));m.put("max_m",Arrays.asList(b.getCorner2().getSIValue().get(0),b.getCorner2().getSIValue().get(1),b.getCorner2().getSIValue().get(2)));}
        if(o instanceof Region){Region region=(Region)o;m.put("part_ids",ids(region.getPartGroup().getObjects()));
            m.put("owned_boundary_ids",ids(region.getBoundaryManager().getBoundaries()));
            m.put("region_delete_includes",List.of("this region","its owned boundary children","its volume mesh"));
            m.put("region_delete_preserves",Map.of("geometry_input_ids",ids(region.getPartGroup().getObjects()),"physics_continuum_id",region.getPhysicsContinuum()==null?"":region.getPhysicsContinuum().getObjectKeyAsString()));
            m.put("delete_object_options",Map.of("detach_static_references",false,"optional_true_semantics","Before deleting this Region, detach only verified static PartDisplayer or AnalysisReport selections to this Region and its owned boundaries; preserve all other selected objects and consumers; derived parts, dynamic/managed/unknown references reject. Source CAD, geometry inputs and PhysicsContinuum remain."));
        }
        if(o instanceof GeometryPart)m.putAll(geometryProductionState((GeometryPart)o));
        if(o instanceof PartSurface)m.putAll(geometryProductionState(((PartSurface)o).getPart()));
        if(o==sim||o instanceof star.cadmodeler.CadModel||o instanceof star.cadmodeler.Body)m.putAll(CadPartCreation.describe(sim,o));extended.describe(o,m);if(o instanceof star.flow.ForceAndMomentBaseReport)m.put("aerodynamic_report",AerodynamicReports.snapshot((star.flow.ForceAndMomentBaseReport)o));m.putAll(common.describe(o));m.putAll(NativeGeometryRepair.describe(sim,o));m.putAll(MeshingProfiles.describe(o));if(o instanceof PhysicsContinuum||o instanceof Region){m.put("physics_profile",PhysicsProfiles.describe(o));m.put("flow_conditions",FlowConditions.describe(o));}
        try{m.putAll(WorkflowOperations.descriptorFields(o));}catch(Exception e){m.put("relation_read_error",e.toString());}
        m.put("supported_actions",supportedActions(o));
        if(o instanceof GeometryPart&&!(o instanceof SimpleBlockPart)&&!(o instanceof MeshOperationPart))m.put("delete_policy","源几何仅用户明确点名删除才允许；修正/重建外域不得删除源CAD");
        if(o instanceof MeshOperationPart)m.put("delete_policy","生成部件需先解除引用并删除其所属布尔/生成操作，不直接删除所有者仍在的输出");
        List<String> deletionBlockers=blockers(o);m.put("deletion_blockers",deletionBlockers);
        List<Map<String,Object>> references=new ArrayList<>();for(String entry:deletionBlockers){String[] fields=entry.split(" ",2);Map<String,Object> reference=new LinkedHashMap<>();reference.put("id",fields[0]);reference.put("detail",fields.length>1?fields[1]:entry);reference.put("direction",entry.contains("owns output")?"generated_output_owner":"references_target_or_its_outputs");reference.put("not_permission_to_delete",true);reference.put("next_step","inspect scope=operations query="+fields[0]+"；按该对象的supported_actions显式清除静态引用或删除用户目标内消费者，不级联删除");references.add(reference);}m.put("deletion_blocker_details",references);
        return m;
    }
    /** Local executable capabilities only, independent of a model's prior claims. */
    private List<String> supportedActions(ClientServerObject o){
        Set<String> actions=new TreeSet<>();actions.addAll(CommonActions060.supportedActions(o));actions.addAll(AerodynamicReports.supportedActions(o));
        if(o==sim)actions.addAll(Set.of("create_block","import_cad","import_cad_model","create_volume_mesh","create_subtract","create_regions","run_solver","create_physics_continuum","create_report","create_field_function","create_global_parameter","create_plane_section","create_scene","prepare_external_aero","repair_external_aero"));
        if(o instanceof NamedObject&&o!=sim&&((NamedObject)o).canRename())actions.add("rename_object");
        if(o instanceof GeometryPart||o instanceof MeshOperation||o instanceof Region||o instanceof star.base.report.Report||ExtendedActions.deletable(o))actions.add("delete_object");
        if(o instanceof star.vis.Displayer&&!((star.vis.Displayer)o).canDelete())actions.remove("delete_object");
        if(o instanceof MeshPart)actions.add("split_surfaces");
        if(o instanceof SimpleBlockPart)actions.add("update_block");
        if(o instanceof AutoMeshOperation)actions.addAll(Set.of("execute_mesh","set_part_selection","set_prism_mesher","configure_prism_layers","configure_mesh_defaults","create_mesh_control","limit_prisms_to_surfaces"));
        if(o instanceof SubtractPartsOperation)actions.addAll(Set.of("execute_boolean","set_part_selection","configure_boolean"));
        if(o instanceof Boundary)actions.addAll(Set.of("set_boundary_type","set_part_selection"));
        if(o instanceof Region)actions.addAll(Set.of("create_boundary","configure_external_physics","configure_physics_profile","configure_flow_conditions"));
        if(o instanceof PhysicsContinuum)actions.addAll(Set.of("configure_external_physics","configure_physics_profile","configure_flow_conditions"));
        if(o instanceof CustomMeshControl){actions.addAll(Set.of("set_object_selection","set_control_enabled"));if(o instanceof SurfaceCustomMeshControl)actions.addAll(Set.of("configure_surface_size","configure_surface_prisms"));if(o instanceof VolumeCustomMeshControl)actions.add("configure_volume_size");}
        if(ExtendedActions.partGroup(o)!=null)actions.add("set_object_selection");
        if(o instanceof star.base.report.Report)actions.add("create_report_monitor");
        if(o instanceof star.base.report.ScalarReport)actions.add("set_report_field");
        if(o instanceof star.cadmodeler.CadModel)actions.addAll(Set.of("create_parts_from_cad","repair_cad_model"));
        return new ArrayList<>(actions);
    }
    String stateMismatch(String id,Object submitted){ClientServerObject o=objects.get(id);Map<String,Object> detail=new LinkedHashMap<>();detail.put("submitted_id",id);detail.put("submitted_expected_state",submitted);detail.put("id_and_state_are_distinct",true);if(o==null){detail.put("error","target_not_in_current_sim_catalog");detail.put("simulation_id",sim.getObjectKeyAsString());detail.put("guidance","id必须是当前对象的id，不可填state:开头的状态令牌；读取operations中目标的id与expected_state成对复制");}else{detail.put("error","target_state_mismatch");detail.put("target_name",o.getPresentationName());detail.put("target_type",o.getClass().getName());detail.put("live_expected_state",ConfigurationEngine.stateToken(describe(o)));detail.put("supported_actions",supportedActions(o));detail.put("guidance","重新inspect此id，确认目标与最新定义后再提交；其他对象的expected_state不可复用");}return ConfigurationEngine.json(detail);}
    // Never recursively delete consumers or imported source CAD. Expose blockers for replanning.
    private List<String> blockers(ClientServerObject target) {
        Set<ClientServerObject> affected=new HashSet<>();affected.add(target);
        if(target instanceof MeshOperation)affected.addAll(((MeshOperation)target).getOutputPartObjects());
        for(ClientServerObject o:new ArrayList<>(affected))if(o instanceof GeometryPart)affected.addAll(((GeometryPart)o).getPartSurfaces());
        if(target instanceof star.vis.Scene)affected.addAll(((star.vis.Scene)target).getDisplayerManager().getObjects());
        if(target instanceof Region)affected.addAll(((Region)target).getBoundaryManager().getBoundaries());
        List<String> blocked=new ArrayList<>();
        if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects()) {
            if(op==target)continue;
            if(!Collections.disjoint(op.getInputGeometryObjects().getObjects(),affected))blocked.add(op.getObjectKeyAsString()+" "+op.getPresentationName());
            if(target instanceof GeometryPart && op.getOutputPartObjects().contains(target))blocked.add(op.getObjectKeyAsString()+" owns output; delete operation instead");
        }
        for(Region r:sim.getRegionManager().getRegions())if(r!=target){
            if(!Collections.disjoint(r.getPartGroup().getObjects(),affected))blocked.add(r.getObjectKeyAsString()+" "+r.getPresentationName());
            for(Boundary b:r.getBoundaryManager().getBoundaries())if(!Collections.disjoint(b.getPartSurfaceGroup().getObjects(),affected))blocked.add(b.getObjectKeyAsString()+" "+b.getPresentationName());
        }
        // Some display/derived-part references are not included in getReferences().
        if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects())if(op instanceof AutoMeshOperation && op!=target)
            for(CustomMeshControl c:((AutoMeshOperation)op).getCustomMeshControls().getObjects())if(c!=target&&!Collections.disjoint(c.getGeometryObjects().getObjects(),affected))blocked.add(c.getObjectKeyAsString()+" "+c.getPresentationName());
        List<ClientServerObject> consumers=new ArrayList<>();consumers.addAll(sim.getPartManager().getObjects());consumers.addAll(sim.getReportManager().getObjects());
        for(star.vis.Scene scene:sim.getSceneManager().getScenes())if(scene!=target)consumers.addAll(scene.getDisplayerManager().getObjects());
        for(ClientServerObject consumer:consumers)if(!affected.contains(consumer)){
            PartGroup group=ExtendedActions.partGroup(consumer);
            if(group!=null&&!Collections.disjoint(group.getObjects(),affected))blocked.add(consumer.getObjectKeyAsString()+" "+consumer.getPresentationName());
            if(consumer instanceof star.base.report.ScalarReport&&affected.contains(((star.base.report.ScalarReport)consumer).getFieldFunction()))blocked.add(consumer.getObjectKeyAsString()+" field reference");
        }
        // STAR tracks report/scene/monitor and other references not covered by geometric input groups.
        for(ClientServerObject item:affected)for(ClientServerObject ref:item.getReferences())if(!affected.contains(ref))blocked.add(ref.getObjectKeyAsString()+" "+ref.getPresentationName());
        return new ArrayList<>(new TreeSet<>(blocked));
    }
    private static List<String> ids(Collection<?> c){List<String> r=new ArrayList<>();for(Object o:c)if(o instanceof ClientServerObject)r.add(((ClientServerObject)o).getObjectKeyAsString());Collections.sort(r);return r;}
    Map<String,Set<String>> completionTargets(Collection<String> targetIds){
        Set<String> regionIds=new LinkedHashSet<>(),meshIds=new LinkedHashSet<>();Set<GeometryPart> parts=new HashSet<>();
        // Scope lookup follows live managers, including newly created objects and
        // excluding deleted handles, without a complete bean/property discovery.
        Map<String,ClientServerObject> live=new LinkedHashMap<>();List<ClientServerObject> available=new ArrayList<>();List<MeshOperation> scopeOperations=new ArrayList<>();
        for(Region r:sim.getRegionManager().getRegions()){available.add(r);available.addAll(r.getBoundaryManager().getBoundaries());}
        available.addAll(sim.getContinuumManager().getObjects());available.addAll(sim.get(SimulationPartManager.class).getLeafParts());
        if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects()){scopeOperations.add(op);available.add(op);if(op instanceof AutoMeshOperation)available.addAll(((AutoMeshOperation)op).getCustomMeshControls().getObjects());}
        available.addAll(sim.getReportManager().getObjects());available.addAll(sim.getPartManager().getObjects());for(star.vis.Scene scene:sim.getSceneManager().getScenes()){available.add(scene);available.addAll(scene.getDisplayerManager().getObjects());}
        for(ClientServerObject o:available)live.put(o.getObjectKeyAsString(),o);
        for(String id:targetIds){ClientServerObject o=live.get(id);if(o instanceof Region)regionIds.add(id);if(o instanceof Boundary)regionIds.add(((Boundary)o).getRegion().getObjectKeyAsString());if(o instanceof GeometryPart)parts.add((GeometryPart)o);PartGroup selectedGroup=ExtendedActions.partGroup(o);if(selectedGroup!=null)for(NamedObject selected:selectedGroup.getObjects()){if(selected instanceof Region)regionIds.add(selected.getObjectKeyAsString());if(selected instanceof Boundary)regionIds.add(((Boundary)selected).getRegion().getObjectKeyAsString());if(selected instanceof GeometryPart)parts.add((GeometryPart)selected);}if(o instanceof CustomMeshControl)o=(ClientServerObject)((CustomMeshControl)o).getAutoMeshBase();if(o instanceof MeshOperation)for(Object output:((MeshOperation)o).getOutputPartObjects())if(output instanceof GeometryPart)parts.add((GeometryPart)output);if(o instanceof AutoMeshOperation){meshIds.add(o.getObjectKeyAsString());for(GeometryObject part:((AutoMeshOperation)o).getInputGeometryObjects().getObjects())if(part instanceof GeometryPart)parts.add((GeometryPart)part);}if(o instanceof PhysicsContinuum)for(Region r:sim.getRegionManager().getRegions())if(Objects.equals(r.getPhysicsContinuum(),o))regionIds.add(r.getObjectKeyAsString());}
        for(Region r:sim.getRegionManager().getRegions())if(regionIds.contains(r.getObjectKeyAsString()))for(GeometryPart part:r.getPartGroup().getObjects())parts.add(part);
        // Trace only real typed dependencies: Block/CAD -> Boolean output ->
        // downstream operation/region. Each operation is visited at most once,
        // so cyclic or repeated references cannot turn scope discovery into a loop.
        Set<String> visitedOperations=new HashSet<>();boolean advanced=true;int rounds=0;
        while(advanced&&rounds++<=scopeOperations.size()){advanced=false;for(MeshOperation op:scopeOperations)if(!visitedOperations.contains(op.getObjectKeyAsString())&&!Collections.disjoint(op.getInputGeometryObjects().getObjects(),parts)){
            visitedOperations.add(op.getObjectKeyAsString());advanced=true;if(op instanceof AutoMeshOperation)meshIds.add(op.getObjectKeyAsString());for(Object output:op.getOutputPartObjects())if(output instanceof GeometryPart)parts.add((GeometryPart)output);
        }}
        for(Region r:sim.getRegionManager().getRegions())if(!Collections.disjoint(r.getPartGroup().getObjects(),parts))regionIds.add(r.getObjectKeyAsString());
        if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects())if(op instanceof AutoMeshOperation&&!Collections.disjoint(op.getInputGeometryObjects().getObjects(),parts))meshIds.add(op.getObjectKeyAsString());
        return Map.of("region_ids",regionIds,"mesh_ids",meshIds);
    }
    String actionIndex(){
        List<ClientServerObject> selected=new ArrayList<>();for(ClientServerObject o:objects.values())if(o==sim||o instanceof GeometryPart||o instanceof MeshOperation||o instanceof Region||o instanceof Boundary||o instanceof CustomMeshControl||o instanceof PhysicsContinuum||o instanceof star.vis.Scene||o instanceof star.vis.Displayer||o instanceof star.base.report.Report||o instanceof Part||o instanceof star.cadmodeler.CadModel)selected.add(o);
        selected.sort(Comparator.comparingInt(this::indexRank).thenComparing(ClientServerObject::getObjectKeyAsString));
        StringBuilder out=new StringBuilder("动作目标简表（id与expected_state必须取自同一行；state不是id）。详情用operations query=真实id；此简表只提供定位，不替代执行前校验。\n"+repairEntry()+"\n");
        int emitted=0;for(ClientServerObject o:selected){Map<String,Object> row=new LinkedHashMap<>();row.put("id",o.getObjectKeyAsString());row.put("expected_state",states.get(o.getObjectKeyAsString()));row.put("name",o.getPresentationName());row.put("type",o.getClass().getSimpleName());String text=ConfigurationEngine.json(row);if(out.length()+text.length()>8500)break;out.append(text).append('\n');emitted++;}
        if(emitted<selected.size())out.append("简表其余").append(selected.size()-emitted).append("项省略；按类型/名称inspect operations分页读取。\n");return out.toString();
    }
    static String repairEntry(){return "repair_external_aero：显式修复已有外流场或全模改半模的原生入口；目标是当前simulation_actions行，参数同prepare_external_aero，强制mode=repair。body_id/工况/域范围由模型明确选择；内部按真实依赖修复布尔、区域、边界及网格，不要求先在外部手动删除整链；仅支持有明确归属的链，不删除源CAD或无关对象。完整参数用action_help query=repair_external_aero。";}
    private int indexRank(ClientServerObject o){if(o==sim)return 0;if(o instanceof Region)return 1;if(o instanceof MeshOperation)return 2;if(o instanceof GeometryPart)return 3;if(o instanceof CustomMeshControl)return 4;if(o instanceof star.vis.Displayer)return 5;if(o instanceof PhysicsContinuum)return 6;if(o instanceof star.vis.Scene)return 7;if(o instanceof Boundary)return 8;return 9;}
    Set<String> exactMatchIds(String query){
        String needle=query.trim();if(objects.containsKey(needle))return Set.of(needle);Set<String> ids=new LinkedHashSet<>();for(ClientServerObject o:objects.values())if(o.getPresentationName().equalsIgnoreCase(needle)||o.getQualifiedName().equalsIgnoreCase(needle))ids.add(o.getObjectKeyAsString());return ids;
    }
    String inspect(String query,int offset){return inspect(query,offset,true);}
    String inspect(String query,int offset,boolean includeState){
        List<Map<String,Object>> found=new ArrayList<>();Set<String> exact=exactMatchIds(query);
        Collection<ClientServerObject> candidates=objects.values();if(!exact.isEmpty()){List<ClientServerObject> direct=new ArrayList<>();for(String id:exact)direct.add(objects.get(id));candidates=direct;}
        for(ClientServerObject o:candidates){Map<String,Object> m=describe(o);String text=ConfigurationEngine.json(m);if(!exact.isEmpty()||inspectionMatches(text,query)){String snapshot=ConfigurationEngine.stateToken(m);states.put(o.getObjectKeyAsString(),snapshot);Map<String,Object> stamped=new LinkedHashMap<>();stamped.put("id",o.getObjectKeyAsString());if(includeState){stamped.put("expected_state",snapshot);stamped.put("action_target",Map.of("id",o.getObjectKeyAsString(),"expected_state",snapshot));}stamped.putAll(m);found.add(stamped);}}
        StringBuilder b=new StringBuilder("真实对象与选择/引用关系，共"+found.size()+"项（包含Simulation和表面，不是网格操作数量）；每个ID只有一个状态，offset="+offset+"\n");
        int pageSize=includeState?20:60;for(int i=offset;i<Math.min(found.size(),offset+pageSize);i++)b.append(ConfigurationEngine.json(found.get(i))).append('\n');
        if(offset+pageSize<found.size())b.append("继续 inspect scope=operations query="+query+" offset="+(offset+pageSize)+"\n");return b.toString();
    }
    static boolean inspectionMatches(String text,String query){
        if(query.equals("*")||text.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))return true;
        List<String> terms=new ArrayList<>(Arrays.asList(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+",40)));
        java.util.regex.Matcher identifiers=java.util.regex.Pattern.compile("[a-z][a-z0-9_.-]+",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query);
        while(identifiers.find())terms.add(identifiers.group().toLowerCase(Locale.ROOT));
        return terms.stream().anyMatch(t->t.length()>1&&text.toLowerCase(Locale.ROOT).contains(t));
    }
    static double prismTotal(Object layers,Object height,Object ratio)throws IOException {
        if(!(layers instanceof Number)||!(height instanceof Number)||!(ratio instanceof Number))throw new IOException("棱柱层需layers、first_layer_m、growth_ratio数值");
        double n=((Number)layers).doubleValue(),h=((Number)height).doubleValue(),r=((Number)ratio).doubleValue();
        if(!Double.isFinite(n)||n!=Math.rint(n)||n<1||n>1000||!Double.isFinite(h)||h<=0||!Double.isFinite(r)||r<1||r>10)throw new IOException("棱柱层参数超出有限正值范围");
        double total=0,layer=h;for(int i=0;i<(int)n;i++){total+=layer;if(!Double.isFinite(total))throw new IOException("棱柱层总厚度溢出");layer*=r;}return total;
    }
    private Map<String,Object> prismSettings(AutoMeshOperation op){
        Map<String,Object> data=new LinkedHashMap<>();if(!op.hasPrismMesher())return data;
        try{AutoMeshDefaultValuesManager v=op.getDefaultValues();PrismAutoMesher m=op.getMeshers().hasMesher(PrismAutoMesher.class);
            data.put("layers",v.get(NumPrismLayers.class).getNumLayers());
            data.put("distribution_mode",m.getPrismStretchingOption().getSelected());
            data.put("stretching_function",m.getPrismStretchingFunction().getSelected());
            PrismThickness t=v.get(PrismThickness.class);data.put("total_thickness_mode",t.getRelativeOrAbsoluteOption().getSelected());
            data.put("total_thickness_m",t.getAbsoluteSizeValue().getInternalValue());
            if(m.getPrismStretchingOption().getSelected()==PrismStretchingOption.WALL_THICKNESS)data.put("first_layer_m",v.get(PrismWallThickness.class).getInternalValue());
            else if(m.getPrismStretchingOption().getSelected()==PrismStretchingOption.STRETCHING)data.put("growth_ratio",v.get(PrismLayerStretching.class).getStretching());
        }catch(RuntimeException e){data.put("read_error",e.toString());}return data;
    }
    private static List<String> mesherNames(AutoMeshOperation op){List<String> names=new ArrayList<>();for(MesherBase m:op.getMeshersCollection())names.add(m.getClass().getName());return names;}
    boolean isObjectId(String id){return objects.containsKey(id);}
    boolean objectExists(String id){refresh();return objects.containsKey(id);}
    Set<String> existingObjectIds(Collection<String> ids){refresh();Set<String> found=new LinkedHashSet<>();for(String id:ids)if(objects.containsKey(id))found.add(id);return found;}
    String objectName(Object id){ClientServerObject o=objects.get(id);return o==null?"未命名对象":o.getPresentationName();}
    String expectedState(String id){return states.get(id);}
    boolean currentState(String id,Object expected){ClientServerObject o=objects.get(id);return o!=null&&Objects.equals(expected,ConfigurationEngine.stateToken(describe(o)));}
    static String contract(){return NativeGeometryRepair.contract()+CadPartCreation.contract()+PhysicsProfiles.contract()+FlowConditions.contract()+ExternalAeroWorkflow.contract()+ExtendedActions.contract()+CommonActions060.contract()+AerodynamicReports.contract()+" 通用actions（每阶段一个，与changes分开）：所有操作包含type,id,expected_state,reason；id为上述当前SIM目录对象ID，expected_state原样复制。"
        +"create_block: id=simulation_actions的ID,name,min_m=[x,y,z],max_m=[x,y,z]；在Lab坐标系创建长方体外域，角点单位m，每轴min<max。先inspect geometry读取模型尺寸，按用户工况/流向/对称面要求选尺寸并在reason披露依据；未知对称面/流向须询问，不猜。创建后需create_subtract、execute_boolean、create_regions、create_volume_mesh与execute_mesh分阶段继续，不能把创建块当流体域已完成。"
        +"update_block: id=SimpleBlockPart,min_m,max_m；直接修改现有块的Lab米坐标，保留对象引用，后续布尔/网格需重新执行；不得将角点改好当网格已更新。"
        +"delete_object: id=GeometryPart/MeshOperation/Region/Report/Scene/Displayer及目录supported_actions列出的对象；必须用户要求删除或重建/重做/修正。读取deletion_blocker_details中的真实id，先inspect消费者；报告/派生部件/显示器可set_object_selection selection_ids=[]清空静态引用，或显式删除目标范围内可删除消费者，再删除目标；不级联删除无关对象，原始CAD除非用户明确点名删除否则保留。删除布尔操作同时移除其无引用输出部件，保留所有输入部件；生成部件应删除其所属布尔操作；删除Region会包含其边界及网格，保留输入几何和PhysicsContinuum。可选detach_static_references为布尔值、默认false；仅Region允许true：通过与repair相同的原生安全预检，仅解除PartDisplayer或AnalysisReport已验证静态选择中对此Region及其Boundary子对象的引用，保留消费者和其它选择，动态查询/managed/未知引用拒绝；不能用于其它对象或删除源CAD。返回deleted_id/name及reference_updates原生读回；无需模型逐个手动清空安全静态引用。"
        +"rename_object: id=部件/表面/区域/边界/网格操作/报告,name=新名称；不改变几何。"
        +"split_surfaces: id=MeshPart,angle_deg=(0,180)；按角度拆分该部件表面，可选selection_ids仅拆分指定PartSurface。允许已更新的布尔输出及已有区域/网格引用，归属关系不是删除阻塞；若geometry_current=false，须先按dirty_producer_operation_ids执行上游布尔并重读新面，不能拆分旧输出；STAR保留并更新引用，不需要删除布尔、区域或网格。仅对外域当前面拆分，避免把机翼也拆碎。随后inspect geometry query=surfaces:部件ID核实面坐标，并用create_boundary分配拆出的面；不根据面名猜方向。"
        +"import_cad: id=simulation_actions的ID,path=用户明确给出的本机绝对STEP/IGES文件路径；只导入，不自动替换已有几何或划网格。"
        +"configure_mesh_defaults: id=AutoMeshOperation，可逐项给farfield_target_m/global_min_m/maximum_cell_m/surface_growth/volume_growth(仅多面体)/curvature_points；只改指定项、不改BaseSize。configure_boolean: id=SubtractPartsOperation,cad_boolean=true/false；true另给绝对chord_tolerance_m/surface_tolerance_m/angle_deg，改变后必须execute_boolean、核验边界与网格输入再重划；不改原CAD。"
        +"create_volume_mesh: id=simulation_actions的ID,name,part_ids=[真实GeometryPart ID],mesh_type=polyhedral或trimmed,prism=true/false,base_size_m=正数；参数必须根据用户需求确定，无显式尺寸时可先inspect recommendations取有依据初值；提供reference_length_m会补默认表面尺寸/过渡/曲率/体上限，仍保留显式base_size_m。use_recommended_defaults=false关闭此档，已有网格不得反复重置。仅创建不运行。"
        +"configure_prism_layers: id=已启用Prism的AutoMeshOperation, layers=整数1..1000,first_layer_m=首层单元完整厚度(m)>0,growth_ratio=1..10。一次配置几何增长、首层厚度模式、层数及绝对总厚度T=h1*sum(r^i,i=0..n-1)，读回全部参数。增长率由这三个量隐式确定，不能在WALL_THICKNESS模式另写不活跃的stretching。可直接使用此接口，不必等待writable目录暴露数值。仅设置默认棱柱层；局部控制覆盖和边界类型仍须检查，按用户授权再execute_mesh。y+目标是估算不是网格质量证明；h1是单元全厚度，壁面到首层中心距离通常约h1/2，必须披露长度/物性/摩擦估算依据，不能声称保证y+=1。"
        +"set_prism_mesher: id=已有AutoMeshOperation的ID,enabled=true/false；在原操作上添加/移除Prism Layer Mesher，保留其他网格器、输入和现有控制，不重建网格操作。即使Prism不存在也可启用。启用后重新inspect writable query=Prism设置层数/厚度等；此操作不执行网格，也不能保证实际y+。移除会删除该网格器的参数，只有用户明确要求移除时使用。"
        +"create_regions: id=simulation_actions的ID,part_ids=[GeometryPart ID...]；仅无现有Region时从这些部件建立一个区域和逐表面边界，物理连续体随后由配置目录绑定；不能在已有区域的SIM中自动重建区域。"
        +"create_subtract: 可选cad_boolean、reference_length_m及绝对chord_tolerance_m/surface_tolerance_m/angle_deg；未指定模式时仅可用CAD实体输入默认CAD布尔，显式false保留；不能用离散共面重叠冒充几何正确。id=simulation_actions的ID,name,part_ids=[GeometryPart ID...],target_id=保留的外域/目标部件ID；创建减运算，不立即执行；不得猜目标。"
        +"execute_boolean: id=已有SubtractPartsOperation的ID；执行后重读目录，用户要求替换几何时继续显式重绑网格输入及边界，不能把导入成功当替换成功。"
        +"set_part_selection: id=已有AutoMeshOperation/SubtractPartsOperation或Boundary的ID,selection_ids=[真实ID...]；替换完整静态选择集合，拒绝查询驱动集合；网格/减运算只选GeometryPart，Boundary只选PartSurface。"
        +"run_solver: 必填type=run_solver,id=simulation_actions真实ID,expected_state=该ID最新状态,steps=用户明确授权的1..100000整数,reason=理由。字面结构示例（其中ID/状态/步数必须替换为当前真实值）：{\"type\":\"run_solver\",\"id\":\"真实simulation_actions ID\",\"expected_state\":\"最新状态\",\"steps\":30,\"reason\":\"用户授权30步烟测\"}；仅用户明确指定运行步数时调用，保留当前解，不清场不改变迎角或收敛参数；使用现有物理设置分块step，既有停止条件可能提前结束。每个用户请求只执行一次此操作，不能循环追加步数；流动区域须有当前实际有限体积单元，关联几何的上游操作须更新，三维关联几何范围须与体网格范围一致。有效导入体网格不要求有AutoMeshOperation；范围一致仅为必要检查，不证明内部几何完全相同或网格收敛。完成步数不等于收敛。"
        +"以上操作使用当前插件选定SIM，执行前恢复副本、执行后保存及报告。不得迁移到其他SIM，不运行命令/外部宏；缺少工况/几何归属/单位必须说明或询问。";}
    private ClientServerObject target(Map<?,?> a)throws IOException {ClientServerObject o=objects.get(a.get("id"));if(o==null)throw new IOException("目标不属于当前SIM目录："+stateMismatch(String.valueOf(a.get("id")),a.get("expected_state")));return o;}
    private List<ClientServerObject> selected(Map<?,?> a,String key)throws IOException {
        Object raw=a.get(key);if(!(raw instanceof List)||((List<?>)raw).isEmpty())throw new IOException(key+"必须为非空ID列表");List<ClientServerObject> out=new ArrayList<>();Set<Object> seen=new HashSet<>();
        for(Object id:(List<?>)raw){ClientServerObject o=objects.get(id);if(o==null||!seen.add(id))throw new IOException("选择对象不存在或重复");out.add(o);}return out;
    }
    private String name(Map<?,?> a)throws IOException {Object n=a.get("name");if(!(n instanceof String)||!n.toString().matches("[\\p{L}_][\\p{L}\\p{N}_ .-]{0,79}"))throw new IOException("操作名称无效");if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects())if(op.getPresentationName().equals(n))throw new IOException("同名操作已存在");return n.toString();}
    static double[] vector(Object raw)throws IOException {
        if(!(raw instanceof List)||((List<?>)raw).size()!=3)throw new IOException("需要三维有限坐标数组，单位m");double[] a=new double[3];
        for(int k=0;k<3;k++){Object v=((List<?>)raw).get(k);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IOException("坐标必须有限数值");a[k]=((Number)v).doubleValue();}return a;
    }
    private List<MeshOperation> geometryProducers(GeometryPart part){
        List<MeshOperation> result=new ArrayList<>();if(sim.has(MeshOperationManager.class)==null)return result;
        Deque<GeometryPart> queue=new ArrayDeque<>();Set<String> visited=new HashSet<>();queue.add(part);
        while(!queue.isEmpty()){GeometryPart current=queue.removeFirst();if(!visited.add(current.getObjectKeyAsString()))continue;
            for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects())if(op.getOutputPartObjects().contains(current)&&!result.contains(op)){result.add(op);for(GeometryObject input:op.getInputGeometryObjects().getObjects())if(input instanceof GeometryPart)queue.add((GeometryPart)input);}
        }return result;
    }
    private Map<String,Object> geometryProductionState(GeometryPart part){
        List<String> producers=new ArrayList<>(),dirty=new ArrayList<>();for(MeshOperation op:geometryProducers(part)){producers.add(op.getObjectKeyAsString());if(op.isDirty())dirty.add(op.getObjectKeyAsString());}
        return Map.of("geometry_current",dirty.isEmpty(),"producer_operation_ids",producers,"dirty_producer_operation_ids",dirty);
    }
    private void requireCurrentGeometry(GeometryPart part)throws IOException {
        List<Map<String,Object>> dirty=new ArrayList<>();for(MeshOperation op:geometryProducers(part))if(op.isDirty()){
            Map<String,Object> producer=new LinkedHashMap<>();producer.put("id",op.getObjectKeyAsString());producer.put("name",op.getPresentationName());producer.put("type",op.getClass().getName());producer.put("expected_state",ConfigurationEngine.stateToken(describe(op)));producer.put("dirty",true);if(op instanceof SubtractPartsOperation)producer.put("required_action","execute_boolean");dirty.add(producer);
        }
        if(!dirty.isEmpty()){Map<String,Object> detail=new LinkedHashMap<>();detail.put("geometry_part_id",part.getObjectKeyAsString());detail.put("geometry_part_name",part.getPresentationName());detail.put("geometry_current",false);detail.put("dirty_producers",dirty);detail.put("guidance","上游几何操作尚未更新；先使用此生产操作的真实id和同一行expected_state执行对应动作，再重读输出的实际面ID/坐标。旧面不能用于拆分、边界分配或建立网格/区域。不会隐式执行或删除操作。");detail.put("action_help","execute_boolean: id=实际SubtractPartsOperation ID,expected_state=该id最新状态,type=execute_boolean,reason=原因；执行后重新inspect geometry/surfaces/operations，再继续下游操作。");throw new IOException("上游几何操作尚未更新："+ConfigurationEngine.json(detail));}
    }
    private void requireCurrentSelections(Map<?,?> action,String key)throws IOException{
        Object raw=action.get(key);if(!(raw instanceof List))return;for(Object id:(List<?>)raw){ClientServerObject selected=objects.get(id);if(selected instanceof PartSurface)requireCurrentGeometry(((PartSurface)selected).getPart());else if(selected instanceof GeometryPart)requireCurrentGeometry((GeometryPart)selected);}
    }

    private List<ClientServerObject> resolveGeometry(String query,boolean surfaces){
        String q=query.trim();List<ClientServerObject> matches=new ArrayList<>();ClientServerObject direct=objects.get(q);
        if(direct!=null)matches.add(direct);
        else for(ClientServerObject o:objects.values())if((o instanceof GeometryPart||o instanceof PartSurface)&&(q.equals("*")||o.getPresentationName().equalsIgnoreCase(q)||o.getQualifiedName().equalsIgnoreCase(q)))matches.add(o);
        LinkedHashSet<ClientServerObject> result=new LinkedHashSet<>();
        for(ClientServerObject o:matches)if(surfaces){if(o instanceof GeometryPart)result.addAll(((GeometryPart)o).getPartSurfaces());else if(o instanceof PartSurface)result.add(o);}else if(o instanceof GeometryPart)result.add(o);
        return new ArrayList<>(result);
    }
    String geometryContext(){
        StringBuilder b=new StringBuilder("自动只读几何实测；下列坐标已实际读取，不需要另行授权。单位m，Lab坐标；面范围为面单元中心范围，不是CAD顶点包围盒。\n");
        b.append(inspectGeometry("*",0));
        List<GeometryPart> parts=new ArrayList<>();for(ClientServerObject o:objects.values())if(o instanceof GeometryPart)parts.add((GeometryPart)o);
        parts.sort(Comparator.comparingInt(p->p instanceof MeshOperationPart?0:1));
        int remaining=12;for(GeometryPart p:parts){if(remaining<=0)break;
          String page=inspectGeometry("surfaces:"+p.getObjectKeyAsString(),0,remaining);b.append(page);remaining-=Math.min(remaining,p.getPartSurfaces().size());}
        b.append("更多表面用 inspect scope=surfaces,query=准确部件名/ID/表面名/ID,offset=返回的下一页。截面测量用scope=section，指定部件query、plane_axis、plane_m、projection_axis；不把整体x跨度当翼根弦长。\n");return b.toString();
    }
    String inspectGeometry(String query,int offset){return inspectGeometry(query,offset,12);}
    private String inspectGeometry(String query,int offset,int limit){
        boolean surfaceQuery=query.startsWith("surfaces:")||objects.get(query) instanceof PartSurface;
        if(!surfaceQuery)for(ClientServerObject o:objects.values())if(o instanceof PartSurface&&(o.getPresentationName().equalsIgnoreCase(query)||o.getQualifiedName().equalsIgnoreCase(query)))surfaceQuery=true;
        List<ClientServerObject> found=resolveGeometry(query.startsWith("surfaces:")?query.substring(9):query,surfaceQuery);
        if(found.isEmpty())return "查询无匹配；这不是接口不支持。请使用当前对象的准确名称或ID，或*列出部件；读面坐标用scope=surfaces，query=部件名/ID或表面名/ID。\n";
        int page=surfaceQuery?limit:5;StringBuilder b=new StringBuilder((surfaceQuery?"表面坐标范围（面单元中心，非CAD顶点）":"几何三角化顶点范围（近似CAD包围盒）")+"；Lab/m；共"+found.size()+"项；offset="+offset+"\n");
        int end=Math.min(found.size(),Math.max(0,offset)+page);
        for(int i=Math.max(0,offset);i<end;i++){ClientServerObject o=found.get(i);Map<String,Object> row=new LinkedHashMap<>();row.put("id",o.getObjectKeyAsString());row.put("name",o.getPresentationName());
          try{requireCurrentGeometry(o instanceof PartSurface?((PartSurface)o).getPart():(GeometryPart)o);if(o instanceof PartSurface){row.put("part",((PartSurface)o).getPart().getPresentationName());row.putAll(GeometryBounds.measureSurface(sim,(PartSurface)o));}
          else {if(!(o instanceof MeshPart))throw new IOException("此类型不能导出三角化表面");row.putAll(GeometryBounds.measure((MeshPart)o));}}
          catch(Exception e){row.put("read_error",e.toString());}b.append(ConfigurationEngine.json(row)).append('\n');}
        if(end<found.size())b.append("继续 inspect scope=").append(surfaceQuery?"surfaces":"geometry").append(" query=").append(query.startsWith("surfaces:")?query.substring(9):query).append(" offset=").append(end).append('\n');return b.toString();
    }
    String inspectSection(String query,String axis,double plane,String projection){
        try{List<ClientServerObject> found=resolveGeometry(query,false);if(found.size()!=1||!(found.get(0) instanceof MeshPart))throw new IOException("截面测量须准确匹配一个MeshPart，不能用所有部件或含糊名称");MeshPart p=(MeshPart)found.get(0);requireCurrentGeometry(p);Map<String,Object> row=new LinkedHashMap<>(GeometryBounds.measureSection(p,axis,plane,projection));row.put("id",p.getObjectKeyAsString());row.put("name",p.getPresentationName());return ConfigurationEngine.json(row);}
        catch(Exception e){return ConfigurationEngine.json(Map.of("query",query,"read_error",e.toString()));}
    }
    static int steps(Object raw)throws IOException {if(!(raw instanceof Number))throw new IOException("run_solver缺少或误填必填字段steps：必须在动作顶层添加steps整数（使用当前用户明确授权步数），不能用iterations替代；例如用户授权30步时steps=30");double v=((Number)raw).doubleValue();if(!Double.isFinite(v)||v<1||v>100000||v!=Math.rint(v))throw new IOException("steps必须在1..100000");return (int)v;}
    // Match the requested count independently of natural-language quantity/modifier words.
    // This is an authorization parser only: it never chooses or increases a solver budget.
    static final java.util.regex.Pattern SOLVER_STEP_COUNT=java.util.regex.Pattern.compile("(?<![0-9.eE+\\-−－负点零〇一二两三四五六七八九十百千万亿兆])([0-9]+|[零〇一二两三四五六七八九十百千万亿兆]+)\\s*(?:(?:个|次)\\s*)?(?:(?:非稳态|稳态|瞬态|求解器?|计算|迭代|(?:steady[ -]?state|unsteady|transient|solver|solution|cfd)[ -]*)\\s*)*(?:步|次?迭代(?:步)?|iterations?\\b|steps?\\b)");
    static int explicitSolverCount(String raw){
        if(raw.matches("[0-9]+"))try{int n=Integer.parseInt(raw);return n>=1&&n<=100000?n:-1;}catch(NumberFormatException invalid){return -1;}
        String word=raw.replace('〇','零').replace('两','二');int total=0,section=0,digit=0;
        for(int i=0;i<word.length();i++){
            char ch=word.charAt(i);int d="零一二三四五六七八九".indexOf(ch);if(d>=0){digit=d;continue;}
            int unit=ch=='十'?10:ch=='百'?100:ch=='千'?1000:ch=='万'?10000:0;if(unit==0)return -1;
            if(unit==10000){total+=(section+digit)*10000;section=0;digit=0;}
            else {if(digit==0){if(unit==10&&section==0)digit=1;else return -1;}section+=digit*unit;digit=0;}
            if(total+section>100000)return -1;
        }
        int n=total+section+digit;if(n<1||n>100000)return -1;
        // Require an unambiguous full numeral: e.g. 一百二 could mean 102 or 120.
        String canonical=n>=10000?smallChineseCount(n/10000,true)+"万"+(n%10000==0?"":(n%10000<1000?"零":"")+smallChineseCount(n%10000,false)):smallChineseCount(n,true);
        return word.equals(canonical)||(n>=10&&n<20&&word.equals("一"+canonical))||word.equals(canonical.replace("零一十","零十"))?n:-1;
    }
    private static String smallChineseCount(int n,boolean omitInitialOne){
        StringBuilder b=new StringBuilder();boolean zero=false;
        for(int unit:new int[]{1000,100,10,1}){int d=n/unit;n%=unit;if(d==0){if(b.length()>0&&n>0)zero=true;continue;}if(zero){b.append('零');zero=false;}if(!(omitInitialOne&&unit==10&&d==1&&b.length()==0))b.append("零一二三四五六七八九".charAt(d));if(unit!=1)b.append(unit==1000?'千':unit==100?'百':'十');}return b.toString();
    }
    static boolean solveAuthorized(String goal,int count){
        List<String> directives=CodexAssistant.goalDirectives(goal);String g="";
        for(int i=directives.size()-1;i>=0;i--){String d=directives.get(i).trim();if(d.matches("(?i)(确认|执行|继续|同意|好的|好|可以|ok|授权(?:执行|设置|配置)?)[。！!]*"))continue;g=d.toLowerCase(Locale.ROOT);break;}
        if(g.isBlank())return false;
        java.util.regex.Matcher numbers=SOLVER_STEP_COUNT.matcher(g);int last=-1;while(numbers.find()){last=explicitSolverCount(numbers.group(1));if(last<0)return false;}
        if(last!=count)return false;
        boolean permit=false;
        for(String clause:g.split("[,，;；。！？!?\\n]+")){
            String c=clause.trim();if(c.isEmpty())continue;
            boolean deny=c.matches("(?s).*(?:不要|禁止|不允许|暂不|别|不需要|无需|不执行|不运行|不求解|不计算|不迭代|do not|don't|never).*" );
            boolean solver=c.matches("(?s).*(?:求解|迭代|solve|solver|iterate|cfd).*" );
            boolean other=c.matches("(?s).*(?:导入|几何|重建|网格|迎角|参数|import|geometry|rebuild|mesh|angle|parameter).*" );
            boolean directDeny=c.matches("(?s).*(?:不要|禁止|不允许|暂不|别|不需要|无需|不)(?:再|继续|立即|开始|任何|额外|本次|当前|本轮|马上|自动|进行|执行|启动|做|\\s)*(?:运行|求解|计算|迭代|solve|solver|iterate|run).*" )
                ||c.matches("(?s).*(?:do not|don't|never)\\s*(?:run|solve|execute|iterate).*" );
            if(directDeny){permit=false;continue;}
            if(deny)continue;
            if(c.matches("(?s).*(?:已经|曾经|上次|之前|已运行|已执行|是否|有没有|了吗|多少|already|previous|did you|whether).*"))continue;
            java.util.regex.Matcher clauseNumbers=SOLVER_STEP_COUNT.matcher(c);boolean n=false;while(clauseNumbers.find()){int parsed=explicitSolverCount(clauseNumbers.group(1));if(parsed<0)return false;if(parsed==count&&c.substring(0,clauseNumbers.start()).matches("(?s).*(?:求解|计算|迭代|运行|执行|继续|改为|算|solve|run|iterate|execute|continue).*"))n=true;}
            if(n)permit=true;
        }
        return permit;
    }
    void validate(Map<?,?> a,String goal)throws Exception {
        goal=CodexAssistant.completionScope(goal);
        ClientServerObject o=target(a);String expected=String.valueOf(a.get("expected_state"));if(!currentState(String.valueOf(a.get("id")),expected))throw new IOException("对象状态已变化或状态令牌属于其他对象："+stateMismatch(String.valueOf(a.get("id")),a.get("expected_state")));
        String t=a.get("type").toString();
        if(NativeGeometryRepair.supports(t)){if(t.equals("import_cad_model")&&!goal.contains(String.valueOf(a.get("path"))))throw new IOException("仅允许导入当前用户明确提供的CAD文件路径");NativeGeometryRepair.validate(sim,a,o);return;}
        if(CadPartCreation.supports(t)){CadPartCreation.validate(a,o);return;}
        if(PhysicsProfiles.supports(t)){PhysicsProfiles.requireRequestedFamily(a,goal,o);if(Boolean.TRUE.equals(a.get("replace_conflicting_models"))&&!TurbulenceIntent.replacementAuthorized(goal))throw new IOException("替换已有互斥模型需要当前请求明确选择目标模型");PhysicsProfiles.validate(a,o);return;}
        if(FlowConditions.supports(t)){FlowConditions.validate(a,o);return;}
        if(ExternalAeroWorkflow.supports(t)){if(Boolean.TRUE.equals(a.get("execute_mesh"))&&EngineeringIntent.meshForbidden(goal))throw new IOException("当前请求明确禁止执行网格，完整配置授权不覆盖此限制");if(Boolean.TRUE.equals(a.get("execute_mesh"))&&!WorkflowOperations.meshRequested(goal)&&!EngineeringIntent.fullPreparationRequested(CodexAssistant.completionScope(goal)))throw new IOException("完整流程执行网格需要划网格或完整求解准备授权");if(Boolean.TRUE.equals(a.get("replace_existing_mesh_controls"))&&!EngineeringIntent.fullPreparationRequested(CodexAssistant.completionScope(goal)))throw new IOException("重配已有局部控制需要完整重配的当前授权");ExternalAeroWorkflow.validate(sim,a,o,goal);return;}
        if(MeshingProfiles.supports(t)){MeshingProfiles.validate(a,o);return;}
        if(Set.of("create_boundary","create_mesh_control","limit_prisms_to_surfaces").contains(t))requireCurrentSelections(a,"selection_ids");
        if(AerodynamicReports.supports(t)){aerodynamic.validate(a,o,goal);return;}
        if(CommonActions060.supports(t)){common.validate(a,o,goal);return;}
        if(ExtendedActions.TYPES.contains(t)){extended.validate(a,o,goal);return;}
        if(Set.of("create_block","import_cad","create_volume_mesh","create_subtract","create_regions","run_solver").contains(t)&&o!=sim)throw new IOException("需要当前simulation_actions目标");
        if(t.equals("configure_prism_layers")){if(!(o instanceof AutoMeshOperation)||!((AutoMeshOperation)o).hasPrismMesher())throw new IOException("先启用目标操作的Prism Layer Mesher");prismTotal(a.get("layers"),a.get("first_layer_m"),a.get("growth_ratio"));}
        if(t.equals("set_prism_mesher")){if(!(o instanceof AutoMeshOperation)||!(a.get("enabled") instanceof Boolean))throw new IOException("需要AutoMeshOperation及enabled布尔值");if(!Boolean.TRUE.equals(a.get("enabled"))&&!goal.matches("(?is).*(删除|移除|禁用|关闭|取消|remove|disable).*"))throw new IOException("移除棱柱层须明确要求");}
        if(t.equals("delete_object")){
            ConfigurationEngine.validateRegionDeletionAuthorization(a,goal,o instanceof Region);
            if(!(o instanceof GeometryPart || o instanceof MeshOperation || o instanceof Region || o instanceof star.base.report.Report || ExtendedActions.deletable(o)))throw new IOException("此类型不支持删除；不单独删除边界/表面");
            if(!goal.matches("(?is).*(删除|移除|删掉|删去|重做|重建|重新创建|修正|修复|delete|remove|rebuild).*"))throw new IOException("当前目标未授权删除或重建");
            if(o instanceof GeometryPart && !(o instanceof SimpleBlockPart) && !(o instanceof MeshOperationPart))ConfigurationEngine.validateOriginalCadDeletionAuthorization(goal,o.getPresentationName());
            if(o instanceof star.vis.Displayer&&!((star.vis.Displayer)o).canDelete())throw new IOException("此显示器由STAR管理，不能删除；可显式清空其静态selection_ids解除引用");
            if(detachStaticReferences(a,o instanceof Region))ExternalAeroWorkflow.validateRegionDeletion(sim,(Region)o);
            else if(!blockers(o).isEmpty())throw new IOException("删除受依赖阻止："+ConfigurationEngine.json(describe(o)));
        }
        if(t.equals("rename_object")){if(o==sim || !(o instanceof NamedObject) || !((NamedObject)o).canRename())throw new IOException("此对象不可重命名");name(a);for(ClientServerObject v:objects.values())if(v!=o && v.getClass()==o.getClass() && v.getPresentationName().equals(a.get("name")))throw new IOException("同类型对象名称重复");}
        if(t.equals("split_surfaces")&&o instanceof GeometryPart)requireCurrentGeometry((GeometryPart)o);
        if(Set.of("create_regions","create_volume_mesh").contains(t))requireCurrentSelections(a,"part_ids");
        if(t.equals("set_part_selection")&&o instanceof Boundary)requireCurrentSelections(a,"selection_ids");
        if(t.equals("split_surfaces")){if(!(o instanceof MeshPart))throw new IOException("需要MeshPart");Object v=a.get("angle_deg");if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()<=0||((Number)v).doubleValue()>=180)throw new IOException("角度须在0到180之间");if(a.containsKey("selection_ids"))for(ClientServerObject f:selected(a,"selection_ids"))if(!(f instanceof PartSurface)||!((MeshPart)o).getPartSurfaces().contains(f))throw new IOException("只能拆分目标部件的当前表面");}
        if(t.equals("update_block")){if(!(o instanceof SimpleBlockPart))throw new IOException("需要已有块部件");SimpleBlockPart b=(SimpleBlockPart)o;if(!b.getCoordinateSystem().equals(sim.getCoordinateSystemManager().getLabCoordinateSystem()))throw new IOException("只支持Lab坐标块");double[] lo=vector(a.get("min_m")),hi=vector(a.get("max_m"));for(int i=0;i<3;i++)if(lo[i]>=hi[i])throw new IOException("每轴min须小于max");}
        if(t.equals("create_block")){name(a);double[] lo=vector(a.get("min_m")),hi=vector(a.get("max_m"));for(int i=0;i<3;i++)if(lo[i]>=hi[i])throw new IOException("包围盒每轴min必须小于max");for(GeometryPart p:sim.get(SimulationPartManager.class).getParts())if(p.getPresentationName().equals(a.get("name")))throw new IOException("同名几何部件已存在");}
        if(t.equals("import_cad")){Path p=Path.of(String.valueOf(a.get("path")));String lower=p.toString().toLowerCase(Locale.ROOT);if(!p.isAbsolute()||!Files.isRegularFile(p)||!Files.isReadable(p)||!(lower.endsWith(".step")||lower.endsWith(".stp")||lower.endsWith(".iges")||lower.endsWith(".igs")))throw new IOException("需要可读取的绝对STEP/IGES路径");if(!goal.contains(p.toString()))throw new IOException("导入路径必须由当前用户请求明确提供");}
        if(t.equals("create_volume_mesh")||t.equals("create_subtract")){
            name(a);for(ClientServerObject p:selected(a,"part_ids"))if(!(p instanceof GeometryPart))throw new IOException("part_ids只允许GeometryPart");
            if(t.equals("create_volume_mesh")){if(a.containsKey("reference_length_m")&&(!(a.get("reference_length_m") instanceof Number)||!Double.isFinite(((Number)a.get("reference_length_m")).doubleValue())||((Number)a.get("reference_length_m")).doubleValue()<=0))throw new IOException("reference_length_m须为正数");if(a.containsKey("use_recommended_defaults")&&!(a.get("use_recommended_defaults") instanceof Boolean))throw new IOException("use_recommended_defaults须布尔值");if(!Set.of("polyhedral","trimmed").contains(a.get("mesh_type"))||!(a.get("prism") instanceof Boolean))throw new IOException("需明确网格类型和prism开关");Object raw=a.get("base_size_m");if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue())||((Number)raw).doubleValue()<=0)throw new IOException("base_size_m必须为正数，单位m");}
            else if(!(objects.get(a.get("target_id")) instanceof MeshPart)||selected(a,"part_ids").size()<2||!((List<?>)a.get("part_ids")).contains(a.get("target_id")))throw new IOException("减运算至少两个部件且target_id必须在输入中");
        }
        if(t.equals("create_subtract")){
            if(a.containsKey("cad_boolean")&&!(a.get("cad_boolean") instanceof Boolean))throw new IOException("cad_boolean必须布尔值");
            if(Boolean.FALSE.equals(a.get("cad_boolean"))&&(a.containsKey("chord_tolerance_m")||a.containsKey("surface_tolerance_m")||a.containsKey("angle_deg")))throw new IOException("离散布尔不能同时指定CAD精度");
            for(String key:List.of("reference_length_m","chord_tolerance_m","surface_tolerance_m","angle_deg"))if(a.containsKey(key)&&(!(a.get(key) instanceof Number)||!Double.isFinite(((Number)a.get(key)).doubleValue())||((Number)a.get(key)).doubleValue()<=0))throw new IOException(key+"必须正数");
            if(a.containsKey("angle_deg")&&((Number)a.get("angle_deg")).doubleValue()>=180)throw new IOException("CAD离散角度必须小于180");
        }
        if(t.equals("create_regions")){if(!sim.getRegionManager().getRegions().isEmpty())throw new IOException("已有区域不能自动重建");for(ClientServerObject p:selected(a,"part_ids"))if(!(p instanceof GeometryPart))throw new IOException("必须选择GeometryPart");}
        if(t.equals("execute_boolean")){if(!(o instanceof SubtractPartsOperation)||((MeshOperation)o).hasEmptyInput()||!((MeshOperation)o).isExecuteEnabled(false))throw new IOException("减运算不可执行");}
        if(t.equals("set_part_selection")){
            List<ClientServerObject> selection=selected(a,"selection_ids");
            if(o instanceof Boundary){if(((Boundary)o).getPartSurfaceGroup().hasQuery())throw new IOException("请先人工处理动态查询，不能静默覆盖");for(ClientServerObject p:selection)if(!(p instanceof PartSurface))throw new IOException("边界必须选择PartSurface");}
            else if(o instanceof AutoMeshOperation||o instanceof SubtractPartsOperation){if(((MeshOperation)o).getInputGeometryObjects().getQuery()!=null)throw new IOException("查询驱动输入不能静默替换");for(ClientServerObject p:selection)if(!(p instanceof GeometryPart))throw new IOException("网格输入必须为GeometryPart");if(o instanceof SubtractPartsOperation&&!selection.contains(((SubtractPartsOperation)o).getTargetPart()))throw new IOException("新输入必须保留减运算目标部件");}
            else throw new IOException("目标不支持部件选择修改");
        }
        if(t.equals("run_solver")){
            if(solverUsed)throw new IOException("本次请求已执行求解，不自动追加步数");int n=steps(a.get("steps"));if(!solveAuthorized(goal,n))throw new IOException("用户须明确要求运行且指定相同步数");if(sim.getRegionManager().getRegions().isEmpty())throw new IOException("没有可求解区域");
            Map<String,Object> flowCheck=CaseReadiness.inspect(sim,goal);if(!Boolean.TRUE.equals(flowCheck.get("flow_solver_basics_ready"))&&goal.matches("(?is).*(气动|流动|空气|马赫|mach|SA|flow).*"))throw new IOException("尚不具备流动求解基本条件："+flowCheck.get("flow_solver_incomplete_reasons"));
            for(Continuum c:sim.getContinuumManager().getObjects())if(c instanceof PhysicsContinuum&&!c.getRequiredModels().isEmpty())throw new IOException("物理模型尚不完整");
            for(MeshOperation op:sim.get(MeshOperationManager.class).getObjects())if(op instanceof AutoMeshOperation&&op.isDirty())throw new IOException("网格尚未更新，先完成网格划分");
            Set<String> flowRegions=FlowMeshGate.flowRegionIds(flowCheck);for(Region region:sim.getRegionManager().getRegions())if(flowRegions.contains(region.getObjectKeyAsString()))for(GeometryPart part:region.getPartGroup().getObjects())requireCurrentGeometry(part);
            solverMeshCheck=FlowMeshGate.validate(sim,flowCheck);
        }
    }
    static boolean detachStaticReferences(Map<?,?> action,boolean region)throws IOException {
        if(!action.containsKey("detach_static_references"))return false;
        Object value=action.get("detach_static_references");
        if(!(value instanceof Boolean))throw new IOException("detach_static_references必须是布尔值");
        if(Boolean.TRUE.equals(value)&&!region)throw new IOException("detach_static_references=true仅支持Region；不能扩展到几何、网格操作或源CAD");
        return Boolean.TRUE.equals(value);
    }
    String preview(Map<?,?> a){return "当前SIM "+sim.getPresentationName()+"："+a.get("type")+"\n"+ConfigurationEngine.json(a);}
    @SuppressWarnings("unchecked")
    Map<String,Object> execute(Map<?,?> a)throws Exception {
        if(stopped)throw new IOException("已取消");String t=a.get("type").toString();ClientServerObject o=target(a);Map<String,Object> out=new LinkedHashMap<>();
        if(NativeGeometryRepair.supports(t))return NativeGeometryRepair.execute(sim,a,o);if(CadPartCreation.supports(t))return CadPartCreation.execute(sim,a,o);if(PhysicsProfiles.supports(t))return PhysicsProfiles.execute(sim,a,o);if(FlowConditions.supports(t))return FlowConditions.execute(sim,a,o);if(ExternalAeroWorkflow.supports(t))return ExternalAeroWorkflow.execute(sim,a,o);if(MeshingProfiles.supports(t))return MeshingProfiles.execute(sim,a,o);
        if(AerodynamicReports.supports(t))return aerodynamic.execute(a,o);
        if(CommonActions060.supports(t))return common.execute(a,o);
        if(ExtendedActions.TYPES.contains(t))return extended.execute(a,o);
        if(t.equals("configure_prism_layers")){
            AutoMeshOperation op=(AutoMeshOperation)o;double total=prismTotal(a.get("layers"),a.get("first_layer_m"),a.get("growth_ratio"));int n=((Number)a.get("layers")).intValue();double h=((Number)a.get("first_layer_m")).doubleValue();
            out.put("before",prismSettings(op));PrismAutoMesher m=op.getMeshers().hasMesher(PrismAutoMesher.class);
            m.getPrismStretchingFunction().setSelected(PrismStretchingFunction.GEOMETRIC_PROGRESSION);m.getPrismStretchingOption().setSelected(PrismStretchingOption.WALL_THICKNESS);
            sim.getServer().getClientNotifyHandler().processCommandQueue(true);
            AutoMeshDefaultValuesManager v=op.getDefaultValues();v.get(NumPrismLayers.class).setNumLayers(n);
            Units metres=(Units)sim.getUnitsManager().getObject("m");v.get(PrismWallThickness.class).setValueAndUnits(h,metres);
            PrismThickness thickness=v.get(PrismThickness.class);thickness.getRelativeOrAbsoluteOption().setToAbsolute();thickness.setAbsoluteSizeValue(total,metres);
            sim.getServer().getClientNotifyHandler().processCommandQueue(true);
            if(v.get(NumPrismLayers.class).getNumLayers()!=n||Math.abs(v.get(PrismWallThickness.class).getInternalValue()-h)>Math.abs(h)*1e-10||Math.abs(thickness.getAbsoluteSizeValue().getInternalValue()-total)>Math.abs(total)*1e-10||thickness.getRelativeOrAbsoluteOption().getSelected()!=RelativeOrAbsoluteOption.ABSOLUTE||m.getPrismStretchingOption().getSelected()!=PrismStretchingOption.WALL_THICKNESS||m.getPrismStretchingFunction().getSelected()!=PrismStretchingFunction.GEOMETRIC_PROGRESSION)throw new IOException("棱柱层参数读回不一致");
            out.put("actual",prismSettings(op));out.put("growth_ratio_requested",a.get("growth_ratio"));out.put("validation","默认棱柱层数、完整首层厚度及总厚度已读回；尚需检查局部覆盖、划网格与求解后的y+");
        }else if(t.equals("set_prism_mesher")){
            AutoMeshOperation op=(AutoMeshOperation)o;List<String> before=mesherNames(op);List<String> desired=new ArrayList<>(before);String prism="star.prismmesher.PrismAutoMesher";boolean enabled=Boolean.TRUE.equals(a.get("enabled"));
            if(enabled&&!desired.contains(prism))desired.add(prism);if(!enabled)desired.remove(prism);
            if(!before.equals(desired))op.getMeshers().setMeshersByNames(desired);
            sim.getServer().getClientNotifyHandler().processCommandQueue(true);
            if(!new HashSet<>(mesherNames(op)).equals(new HashSet<>(desired))||op.hasPrismMesher()!=enabled)throw new IOException("网格器集合读回不一致: expected="+desired+", actual="+mesherNames(op)+", prism="+op.hasPrismMesher());
            out.put("meshers_before",before);out.put("updated",describe(op));out.put("validation","原操作网格器集合已读回；需配置棱柱层参数并按要求重新划网格，实际y+需求解验证");
        }else if(t.equals("delete_object")){
            String removed=o.getObjectKeyAsString();out.put("deleted_name",o.getPresentationName());
            if(o instanceof GeometryPart)sim.get(SimulationPartManager.class).removeParts(List.of((GeometryPart)o));
            else if(o instanceof MeshOperation){
                List<GeometryPart> outputs=new ArrayList<>();for(Object v:((MeshOperation)o).getOutputPartObjects())if(v instanceof GeometryPart)outputs.add((GeometryPart)v);
                sim.get(MeshOperationManager.class).removeObjects(List.of((MeshOperation)o));
                Collection<GeometryPart> remaining=sim.get(SimulationPartManager.class).getParts();for(GeometryPart v:outputs)if(remaining.contains(v))sim.get(SimulationPartManager.class).removeParts(List.of(v));
                out.put("removed_output_ids",ids(outputs));
            }
            else if(o instanceof Region){if(detachStaticReferences(a,true))out.putAll(ExternalAeroWorkflow.deleteRegionDetachingStaticReferences(sim,(Region)o));else sim.getRegionManager().removeRegions(List.of((Region)o));}
            else if(ExtendedActions.deletable(o))extended.delete(o);
            else sim.getReportManager().removeObjects(List.of((star.base.report.Report)o));
            sim.getServer().getClientNotifyHandler().processCommandQueue(true);refresh();if(objects.containsKey(removed))throw new IOException("删除后对象仍存在");out.put("deleted_id",removed);out.put("validation","已从当前目录确认移除，恢复副本见报告");
        }else if(t.equals("rename_object")){((NamedObject)o).setPresentationName(a.get("name").toString());if(!o.getPresentationName().equals(a.get("name")))throw new IOException("名称读回失败");out.put("renamed",describe(o));
        }else if(t.equals("split_surfaces")){MeshPart p=(MeshPart)o;p.getPartSurfaceManager().splitPartSurfacesByAngle(a.containsKey("selection_ids")?new ArrayList<>((List<PartSurface>)(List<?>)selected(a,"selection_ids")):new ArrayList<>(p.getPartSurfaceManager().getObjects()),((Number)a.get("angle_deg")).doubleValue());out.put("surface_ids",ids(p.getPartSurfaceManager().getObjects()));out.put("validation","表面已拆分，原区域/网格/控制引用由STAR更新并保留；原整体边界可能仍含多面，可create_boundary逐面分配，无需删除重建布尔。已有体网格需重新生成。");
        }else if(t.equals("update_block")){SimpleBlockPart b=(SimpleBlockPart)o;double[] lo=vector(a.get("min_m")),hi=vector(a.get("max_m"));Units m=(Units)sim.getUnitsManager().getObject("m");b.getCorner1().setCoordinate(m,m,m,new DoubleVector(lo));b.getCorner2().setCoordinate(m,m,m,new DoubleVector(hi));b.rebuildSimpleShapePart();for(int k=0;k<3;k++)if(Math.abs(b.getCorner1().getSIValue().get(k)-lo[k])>1e-10||Math.abs(b.getCorner2().getSIValue().get(k)-hi[k])>1e-10)throw new IOException("块角点读回失败");out.put("updated",describe(b));out.put("validation","块坐标已更新，下游布尔和网格需要重新执行");
        }else if(t.equals("create_block")){
            double[] lo=vector(a.get("min_m")),hi=vector(a.get("max_m"));
            SimpleBlockPart block=sim.get(MeshPartFactory.class).createNewBlockPart(sim.get(SimulationPartManager.class));
            block.setPresentationName(a.get("name").toString());block.setCoordinateSystem(sim.getCoordinateSystemManager().getLabCoordinateSystem());
            Units m=(Units)sim.getUnitsManager().getObject("m");
            block.getCorner1().setCoordinate(m,m,m,new DoubleVector(lo));block.getCorner2().setCoordinate(m,m,m,new DoubleVector(hi));block.rebuildSimpleShapePart();
            for(int k=0;k<3;k++)if(Math.abs(block.getCorner1().getSIValue().get(k)-lo[k])>1e-10||Math.abs(block.getCorner2().getSIValue().get(k)-hi[k])>1e-10)throw new IOException("外域角点读回不一致");
            out.put("created",describe(block));out.put("min_m",List.of(lo[0],lo[1],lo[2]));out.put("max_m",List.of(hi[0],hi[1],hi[2]));out.put("validation","长方体几何已创建并读回；尚未布尔减去机翼、分配边界或划分网格");
        }else if(t.equals("import_cad")){
            Set<String> before=new HashSet<>(ids(sim.get(SimulationPartManager.class).getParts()));sim.get(PartImportManager.class).importCadPart(a.get("path").toString(),"SharpEdges",30.0,5);
            List<String> added=ids(sim.get(SimulationPartManager.class).getParts());added.removeAll(before);if(added.isEmpty())throw new IOException("未读回新增部件");out.put("added_part_ids",added);out.put("validation","导入部件已读回；未替换布尔/网格输入及边界，未验证形状精度");
        }else if(t.equals("create_volume_mesh")){
            List<GeometryPart> parts=new ArrayList<>();for(ClientServerObject p:selected(a,"part_ids"))parts.add((GeometryPart)p);
            List<String> meshers=new ArrayList<>(List.of("star.resurfacer.ResurfacerAutoMesher",a.get("mesh_type").equals("polyhedral")?"star.dualmesher.DualAutoMesher":"star.trimmer.TrimmerAutoMesher"));if(Boolean.TRUE.equals(a.get("prism")))meshers.add("star.prismmesher.PrismAutoMesher");
            AutoMeshOperation op=sim.get(MeshOperationManager.class).createAutoMeshOperation(meshers,parts);op.setPresentationName(a.get("name").toString());op.getDefaultValues().get(BaseSize.class).setValueAndUnits(((Number)a.get("base_size_m")).doubleValue(),(Units)sim.getUnitsManager().getObject("m"));
            if(a.containsKey("reference_length_m")&&!Boolean.FALSE.equals(a.get("use_recommended_defaults"))){
                double L=((Number)a.get("reference_length_m")).doubleValue();Map<String,Object> profile=new LinkedHashMap<>();profile.put("type","configure_mesh_defaults");profile.put("farfield_target_m",L);profile.put("global_min_m",L/1000);profile.put("maximum_cell_m",2*L);profile.put("surface_growth",1.15);profile.put("curvature_points",30);
                if(a.get("mesh_type").equals("polyhedral"))profile.put("volume_growth",1.10);
                out.put("recommended_defaults",MeshingProfiles.execute(sim,profile,op));
            }else out.put("defaults_notice","未采用经验档：仅基准尺寸有显式配置，执行前仍需核对表面尺寸、过渡、曲率、近壁和边界。旧接口兼容不代表默认网格足够精细。");
            out.put("created",describe(op));out.put("base_size_m",op.getDefaultValues().get(BaseSize.class).getInternalValue());if(Math.abs(op.getDefaultValues().get(BaseSize.class).getInternalValue()-((Number)a.get("base_size_m")).doubleValue())>1e-12)throw new IOException("基础尺寸单位/读回不一致");out.put("validation","仅创建操作，需配置局部控制和棱柱层后显式执行网格");
        }else if(t.equals("create_subtract")){
            List<GeometryPart> parts=new ArrayList<>();for(ClientServerObject p:selected(a,"part_ids"))parts.add((GeometryPart)p);
            SubtractPartsOperation op=(SubtractPartsOperation)sim.get(MeshOperationManager.class).createSubtractPartsOperation(parts);op.setPresentationName(a.get("name").toString());op.setTargetPart((MeshPart)objects.get(a.get("target_id")));out.putAll(MeshingProfiles.configureNewBoolean(sim,op,parts,a));out.put("created",describe(op));out.put("target_id",op.getTargetPart().getObjectKeyAsString());
        }else if(t.equals("create_regions")){
            List<GeometryPart> parts=new ArrayList<>();for(ClientServerObject p:selected(a,"part_ids"))parts.add((GeometryPart)p);sim.getRegionManager().newRegionsFromParts(parts,"OneRegion",null,"OneBoundaryPerPartSurface",null,RegionManager.CreateInterfaceMode.BOUNDARY);out.put("regions",ids(sim.getRegionManager().getRegions()));if(sim.getRegionManager().getRegions().isEmpty())throw new IOException("未创建区域");out.put("validation","区域和边界已创建；尚需绑定物理连续体及边界条件");
        }else if(t.equals("execute_boolean")){
            MeshOperation op=(MeshOperation)o;if(!op.isDirty()){out.put("skipped",true);out.put("validation","已有当前布尔输出，跳过重复执行；未改变几何和网格。");return out;}running=op;try{op.execute();sim.getServer().getClientNotifyHandler().processCommandQueue(true);if(stopped||op.isDirty())throw new IOException("减运算停止或仍未更新");out.put("output_ids",ids(op.getOutputPartObjects()));out.put("validation","布尔输出已更新，需重读并检查网格输入与边界归属");}finally{running=null;}
        }else if(t.equals("set_part_selection")){
            List<ClientServerObject> selection=selected(a,"selection_ids");Collection<?> actual;
            if(o instanceof Boundary){List<PartSurface> surfaces=new ArrayList<>();for(ClientServerObject p:selection)surfaces.add((PartSurface)p);((Boundary)o).getPartSurfaceGroup().setObjects(surfaces);actual=((Boundary)o).getPartSurfaceGroup().getObjects();}
            else {List<GeometryPart> parts=new ArrayList<>();for(ClientServerObject p:selection)parts.add((GeometryPart)p);((MeshOperation)o).getInputGeometryObjects().setObjects(parts);actual=((MeshOperation)o).getInputGeometryObjects().getObjects();}
            if(!ids(actual).equals(ids(selection)))throw new IOException("选择集合读回不一致");out.put("selection_ids",ids(actual));out.put("validation","仅重绑指定集合；其他引用须逐项检查，已有解场未清除");
        }else if(t.equals("run_solver")){
            solverUsed=true;int n=steps(a.get("steps"));int initial=sim.getSimulationIterator().getCurrentIteration();int consumed=0;solving=true;
            try{while(consumed<n&&!stopped){int chunk=Math.min(25,n-consumed);int before=sim.getSimulationIterator().getCurrentIteration();sim.getSimulationIterator().step(chunk);int after=sim.getSimulationIterator().getCurrentIteration();if(after<=before)break;consumed+=after-before;if(after-before<chunk)break;}}finally{solving=false;}
            out.put("mesh_consistency_check",solverMeshCheck);out.put("iteration_before",initial);out.put("iteration_after",sim.getSimulationIterator().getCurrentIteration());out.put("requested_steps",n);out.put("stop_requested",stopped);out.put("validation","已读回实际迭代数；不代表收敛或物理解正确，未自动调整迎角");if(stopped)throw new IOException("求解已请求停止，已执行部分步数；可从当前状态保存恢复");
        }
        return out;
    }
    void reset(){stopped=false;solverUsed=false;solverMeshCheck=Map.of();}
    boolean abort(){stopped=true;if(ExternalAeroWorkflow.abort(sim))return true;if(solving){sim.getSimulationIterator().stop();return true;}if(running!=null){running.abortMeshGeneration();return true;}return false;}
}
