package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import star.base.neo.*;
import star.common.*;
import star.meshing.*;
import star.prismmesher.*;
import star.resurfacer.SurfaceGrowthRate;

/** One bounded native setup transaction for an explicitly selected, orthogonal half-model.
 * The dispatcher owns authorization, checkpointing, final save, and the outer task journal.
 * No source CAD is changed and no solver iteration is performed.
 */
final class ExternalAeroWorkflow {
    private ExternalAeroWorkflow() { }
    private static final Map<Simulation,RunState> ACTIVE=Collections.synchronizedMap(new IdentityHashMap<>());
    public static final class WorkflowException extends IOException {
        private final Map<String,Object> receipt;
        private WorkflowException(String message,Throwable cause,Map<String,Object> receipt){super(message,cause);this.receipt=new LinkedHashMap<>(receipt);}
        public Map<String,Object> receipt(){return new LinkedHashMap<>(receipt);}
    }
    private static final class RunState { volatile boolean cancelled;volatile MeshOperation running; }
    private static final class Plan {
        MeshPart body;SimpleBlockPart block;SubtractPartsOperation subtract;AutoMeshOperation mesh;Region region;
        String mode,flow,sym,side,scheme,lateral,profile,meshType,prefix;
        boolean full,createdRegion,recreateRegion,preserveMesh;PhysicsContinuum preservedPhysics;Map<String,Object> meshBefore;int flowIndex,symIndex;double plane,L,D;double[] lo,hi,direction;
        final List<String> recovery=new ArrayList<>();
        Map<String,Object> bodyBounds,recommendation;List<String> assumptions=new ArrayList<>();List<CustomMeshControl> oldControls=new ArrayList<>();
    }
    static boolean supports(String type){return "prepare_external_aero".equals(type)||"repair_external_aero".equals(type);}
    static String contract(){return "prepare_external_aero: domain_mode=half(默认)或full。full用于完整全机，不切割原CAD、不创建对称边界，六个外域面均为开放边界；full不需symmetry_axis/plane，reference_length_m可明确给定参考弦长，缺省以流向全长作已披露特征长度。id=当前simulation_actions,body_id=明确选中的已有CAD实体部件ID,mode=create或repair（默认create）,flow_axis=+x/-x/+y/-y/+z/-z,symmetry_axis=x/y/z,symmetry_plane_m=明确平面米坐标,mach,altitude_m,target_yplus,execute_mesh=true/false。支持单实体/单流体区域的全模或主流轴平行于对称面的半模；不猜朝向。可显式flow_direction=[x,y,z]保留对称平面内的迎角，必须与主流轴同向；明确velocity_m_s也可提供方向。reference_length_m可选，缺失时实测给定根部截面的流向投影。域默认以D=max(物体流向全长,半模完整翼展)作10D净距，L单独用于翼面/近壁；可显式domain_min_m/domain_max_m覆盖，不能裁穿物体或改变对称面。可选boundary_scheme=velocity_pressure或freestream；velocity_pressure默认上游及自由侧速度入口、下游压力出口，lateral_boundary_type可显式freestream/velocity_inlet/pressure_outlet。可选profile=steady_compressible_sa等PhysicsProfiles公开profile，新建时默认SA；repair未显式指定profile时保留目标现有受支持SA/SST族与壁面处理，不把现有SST改成SA；未知族或不支持壁面处理需明确选择，用户指定模型优先。可选mesh_type=polyhedral/trimmed,base_size_m,wing_target_m,wing_min_m,farfield_target_m,maximum_cell_m,nearfield_size_m,prism_layers,first_layer_m,growth_ratio,surface_growth,volume_growth,curvature_points,CAD绝对chord_tolerance_m/surface_tolerance_m/angle_deg；明确值优先。可选static_temperature_K/static_pressure_Pa/speed_m_s/velocity_m_s/reference_pressure_Pa/turbulent_viscosity_ratio传工况接口。内部一次顺序完成域、CAD布尔、仅外面拆分、真实坐标分面、区域/边界、完整物理工况、尺寸/棱柱及最多一次网格，不逐项请求模型；不运行CFD。create遇已有区域/网格拒绝；repair通过body_id追踪唯一块外域→CAD减运算→流体输出→网格/区域依赖，只修改这一条链，缺失网格或区域会重建。可显式domain_id/boolean_id/mesh_id/region_id消除多链歧义；不删除源CAD或无关对象。没有减运算时可给明确domain_id恢复独立块外域，或在完全空的操作/区域状态重新建立；已有无法归属的生成区域不会猜测覆盖。repair中旧局部网格控制默认保留并拒绝冲突；仅明确整体重配且replace_existing_mesh_controls=true才逐项停用并记收据。生成的*_Outer控制用于禁用外域/对称面棱柱，必须保持启用；这不是翼面加密冲突，不应停用后重复划网格。所有实际子修改在steps/changes中回报；创建成功不代表几何精度、实际y+或收敛合格。"
        +"repair_external_aero: 明确修复/重建现有外流场依赖链的原生事务。id=当前simulation_actions,body_id=所选原始封闭CAD实体ID；mode固定repair，可省略，不接受mode=create。其余必填与可选参数同prepare_external_aero：domain_mode=half/full,flow_axis,半模symmetry_axis/symmetry_plane_m,mach,altitude_m,target_yplus,execute_mesh布尔值；明确用户参数优先。由本动作内部定位和更新目标块外域、CAD布尔、当前流体输出、实际分面与区域/边界、物理工况、网格控制，缺失网格/区域直接补齐；无需先删除这些派生对象或先单独拆分/删除边界。不会删除原CAD或无关对象；多链或共享依赖不明确时仍拒绝并给实际原因。普通修复或更新外域可直接选择本事务并给更新参数，允许复用已有对象，无需为准备本事务先删除边界、区域或网格。若当前用户明确要求删除旧流体区域后重建，优先本事务recreate_region=true：内部安全处理实际引用、删旧Region并创建新ID，返回真实删除/重建证明，无需先手工分解删除。也可用合法的独立delete_object完成删除后普通repair；仅复用更新的repair回执不能充当实际删除证明，始终保留原CAD和无关对象。可选recreate_region=true仅供当前用户明确要求删除旧Region再重建：在唯一归属链内先完整预检、清除已验证静态Region/Boundary引用，实际删旧Region，再创建新ID区域；源CAD、连续体、网格操作和无关选择成员保留。原直接Region选择一对一换为新Region，旧Boundary引用不猜映射；动态查询、受管理或未知消费者拒绝。返回实际region_recreation删除/新建ID和reference_updates证明。默认false仍复用现有Region。execute_mesh=false仅配置并将旧体网格视为待更新；true在全部配置后最多生成一次网格。本动作不运行CFD，也不自动选取用户未给出的流向/对称面。";}
    static void validate(Simulation s,Map<?,?> a,ClientServerObject target)throws Exception {validate(s,a,target,"");}
    static void validate(Simulation s,Map<?,?> a,ClientServerObject target,String goal)throws Exception {
        if(target!=s)throw new IOException("外流场配置事务必须以当前仿真为目标");Plan p=prepare(s,a);
        PhysicsProfiles.requireRequestedFamily(p.profile,goal,p.region);if(p.recreateRegion)ConfigurationEngine.validateExplicitDeletionTarget(goal,p.region.getPresentationName());
    }
    static boolean abort(Simulation s){RunState state=ACTIVE.get(s);if(state==null)return false;state.cancelled=true;MeshOperation op=state.running;if(op!=null)op.abortMeshGeneration();return true;}
    private static void checkpoint(RunState state)throws IOException {if(state.cancelled)throw new IOException("外流场设置已请求停止；部分原生修改可能存在，请查看恢复副本与子步骤记录");}
    private static void flush(Simulation s){s.getServer().getClientNotifyHandler().processCommandQueue(true);}
    private static double positive(Map<?,?> a,String key,double fallback)throws IOException {if(!a.containsKey(key))return fallback;Object o=a.get(key);if(!(o instanceof Number)||!Double.isFinite(((Number)o).doubleValue())||((Number)o).doubleValue()<=0)throw new IOException(key+"必须为有限正数（SI）");return ((Number)o).doubleValue();}
    private static double finite(Map<?,?> a,String key)throws IOException {Object o=a.get(key);if(!(o instanceof Number)||!Double.isFinite(((Number)o).doubleValue()))throw new IOException(key+"必须明确给出有限数值（SI）");return ((Number)o).doubleValue();}
    private static String text(Map<?,?> a,String key,String fallback){return a.containsKey(key)?String.valueOf(a.get(key)):fallback;}
    private static double[] vector(Object raw)throws IOException {return SimulationActions.vector(raw);}
    private static List<Double> vector(double[] a){return List.of(a[0],a[1],a[2]);}
    private static boolean cad(MeshPart p){return p instanceof CadPart||p instanceof star.cadmodeler.SolidModelPart||p instanceof SimpleBlockPart;}
    private static double[] values(Map<String,Object> bounds,String key){List<?> a=(List<?>)bounds.get(key);return new double[]{((Number)a.get(0)).doubleValue(),((Number)a.get(1)).doubleValue(),((Number)a.get(2)).doubleValue()};}

    private static Plan prepare(Simulation s,Map<?,?> a)throws Exception {
        Plan p=new Plan();boolean explicitRepair="repair_external_aero".equals(a.get("type"));if(explicitRepair&&a.containsKey("mode")&&!"repair".equals(a.get("mode")))throw new IOException("repair_external_aero固定使用mode=repair；不接受mode=create，也不预先删除现有派生对象");p.mode=explicitRepair?"repair":text(a,"mode","create");if(!Set.of("create","repair").contains(p.mode))throw new IOException("mode必须create或repair");
        if(a.containsKey("recreate_region")&&!(a.get("recreate_region") instanceof Boolean))throw new IOException("recreate_region须布尔值");p.recreateRegion=Boolean.TRUE.equals(a.get("recreate_region"));if(p.recreateRegion&&!explicitRepair)throw new IOException("recreate_region=true仅用于明确repair_external_aero删除旧区域后重建事务");
        p.full=text(a,"domain_mode","half").equals("full");if(!Set.of("half","full").contains(text(a,"domain_mode","half")))throw new IOException("domain_mode须half或full");
        p.flow=text(a,"flow_axis","").toLowerCase(Locale.ROOT);p.sym=p.full?"":text(a,"symmetry_axis","").toLowerCase(Locale.ROOT);
        if(!p.flow.matches("[+-][xyz]")||(!p.full&&!p.sym.matches("[xyz]")))throw new IOException("须明确给出flow_axis及symmetry_axis，不能根据包围盒猜朝向");
        p.flowIndex=GeometryBounds.axis(p.flow.substring(1));p.symIndex=p.full?-1:GeometryBounds.axis(p.sym);if(!p.full&&p.flowIndex==p.symIndex)throw new IOException("此半模流程要求来流平行于对称面，不支持侧滑或来流垂直对称面");
        p.direction=new double[]{0,0,0};p.direction[p.flowIndex]=p.flow.startsWith("+")?1:-1;
        if(a.containsKey("flow_direction")||a.containsKey("velocity_m_s")){p.direction=vector(a.get(a.containsKey("flow_direction")?"flow_direction":"velocity_m_s"));double norm=Math.hypot(Math.hypot(p.direction[0],p.direction[1]),p.direction[2]);if(!Double.isFinite(norm)||norm<=0)throw new IOException("flow_direction须非零有限矢量");for(int k=0;k<3;k++)p.direction[k]/=norm;if((!p.full&&Math.abs(p.direction[p.symIndex])>1e-10)||p.direction[p.flowIndex]*(p.flow.startsWith("+")?1:-1)<=0)throw new IOException("明确flow_direction须平行对称面且与所选主流轴同向，未覆盖明确迎角");}
        p.plane=p.full?0:finite(a,"symmetry_plane_m");finite(a,"mach");finite(a,"altitude_m");positive(a,"target_yplus",Double.NaN);
        if(!a.containsKey("target_yplus")||!(a.get("execute_mesh") instanceof Boolean))throw new IOException("须给出target_yplus及execute_mesh布尔值");
        for(GeometryPart part:s.get(SimulationPartManager.class).getLeafParts())if(part.getObjectKeyAsString().equals(a.get("body_id"))&&part instanceof MeshPart)p.body=(MeshPart)part;
        if(p.body==null||!cad(p.body)||!p.body.isClosed()||!p.body.isManifold())throw new IOException("body_id须是当前仿真内封闭流形CAD实体；不对开放或离散面自动修形");
        p.prefix=text(a,"name_prefix","External_Aero");if(!p.prefix.matches("[\\p{L}_][\\p{L}\\p{N}_ .-]{0,45}"))throw new IOException("name_prefix格式无效");
        p.profile=text(a,"profile","steady_compressible_sa");p.meshType=text(a,"mesh_type","polyhedral");if(!Set.of("polyhedral","trimmed").contains(p.meshType))throw new IOException("mesh_type必须polyhedral/trimmed");
        if(a.containsKey("replace_existing_mesh_controls")&&!(a.get("replace_existing_mesh_controls") instanceof Boolean))throw new IOException("replace_existing_mesh_controls须布尔值");
        p.scheme=text(a,"boundary_scheme","velocity_pressure");p.lateral=text(a,"lateral_boundary_type","velocity_inlet");
        if(!Set.of("velocity_pressure","freestream").contains(p.scheme)||!Set.of("velocity_inlet","pressure_outlet","freestream").contains(p.lateral))throw new IOException("边界方案或自由侧边界类型不支持");
        List<MeshOperation> operations=s.has(MeshOperationManager.class)==null?List.of():new ArrayList<>(s.get(MeshOperationManager.class).getObjects());
        List<Region> regions=new ArrayList<>(s.getRegionManager().getRegions());
        if(p.mode.equals("create")) {
            if(!regions.isEmpty()||!operations.isEmpty())throw new IOException("已有区域或网格操作，create不会删除重建；请明确选择目标域repair或独立修改任务");
            for(GeometryPart part:s.get(SimulationPartManager.class).getParts())if(part.getPresentationName().equals(p.prefix+"_Domain"))throw new IOException("域名称已存在，未覆盖或删除已有部件；可读取domain_id后repair");
        }else resolveRepair(s,a,p,operations,regions);
        if(p.mode.equals("repair")&&!a.containsKey("profile")&&p.region!=null&&p.region.getPhysicsContinuum()!=null){
            Map<String,Object> actual=PhysicsProfiles.describe(p.region);if(actual.containsKey("physics_profile_read_error"))throw new IOException("读取目标现有物理模型失败，未用默认SA替代当前设置");boolean sa=Boolean.TRUE.equals(actual.get("sa_equation_enabled"))||Boolean.TRUE.equals(actual.get("sa_family_enabled")),sst=Boolean.TRUE.equals(actual.get("sst_equation_enabled"));
            if(sa&&sst)throw new IOException("现有SA/SST族同时启用，未猜测保留或替换目标；须明确profile并先诊断");
            if(sst){String wall=String.valueOf(actual.getOrDefault("sst_wall_treatment",""));if(!wall.isEmpty()&&!wall.equals("star.kwturb.KwAllYplusWallTreatment"))throw new IOException("修复要求保留现有SST壁面处理，但当前有界profile不支持此处理："+wall+"；未隐式改为SA或其他壁面模型");p.profile="steady_compressible_sst_all_yplus";p.assumptions.add("repair未指定profile，保留目标现有SST族及已支持的原生壁面处理。");}
            else if(sa){p.profile="steady_compressible_sa";p.assumptions.add("repair未指定profile，保留目标现有SA族及原有Low/All y+壁面处理。");}
            else{Object raw=actual.get("enabled_models");if(raw instanceof Collection&&((Collection<?>)raw).stream().map(String::valueOf).anyMatch(x->x.equals("star.turbulence.TurbulentModel")||x.equals("star.flow.LaminarModel")))throw new IOException("现有黏性/湍流族不是可原样保留的SA/SST，repair未指定profile，不按默认SA替换；请明确目标模型。");}
        }
        p.bodyBounds=GeometryBounds.measure(p.body);double[] low=values(p.bodyBounds,"min_m"),high=values(p.bodyBounds,"max_m");
        p.L=positive(a,"reference_length_m",Double.NaN);
        if(!Double.isFinite(p.L)){if(p.full){p.L=high[p.flowIndex]-low[p.flowIndex];p.assumptions.add("全模未指定参考弦长，采用实测流向物体全长作网格/雷诺数特征长度，不称其为平均气动弦；可显式reference_length_m覆盖。");}else{Map<String,Object> section=GeometryBounds.measureSection(p.body,p.sym,p.plane,p.flow.substring(1));p.L=((Number)section.get("projected_extent_m")).doubleValue();p.assumptions.add("参考长度来自明确根部平面的实测流向投影，不是平均气动弦或整个扫掠翼X跨度。");}}
        double tol=Math.max(1e-8,p.L*1e-6);
        if(p.full)p.side="full";
        else if(low[p.symIndex]>=p.plane-tol&&Math.abs(low[p.symIndex]-p.plane)<=tol)p.side="positive";
        else if(high[p.symIndex]<=p.plane+tol&&Math.abs(high[p.symIndex]-p.plane)<=tol)p.side="negative";
        else throw new IOException("所选半模未在给定对称面封口，或跨越该平面；不自动移动、裁剪或投影原CAD");
        p.D=p.full?Math.max(high[0]-low[0],Math.max(high[1]-low[1],high[2]-low[2])):Math.max(high[p.flowIndex]-low[p.flowIndex],2*Math.max(Math.abs(low[p.symIndex]-p.plane),Math.abs(high[p.symIndex]-p.plane)));
        if(!Double.isFinite(p.D)||p.D<=0)throw new IOException("无法取得有限非零物体长度/完整翼展尺度");
        p.lo=new double[3];p.hi=new double[3];for(int k=0;k<3;k++){p.lo[k]=low[k]-10*p.D;p.hi[k]=high[k]+10*p.D;}
        if(!p.full){if(p.side.equals("positive"))p.lo[p.symIndex]=p.plane;else p.hi[p.symIndex]=p.plane;}
        if(a.containsKey("domain_min_m")!=a.containsKey("domain_max_m"))throw new IOException("显式域范围须同时给domain_min_m/domain_max_m");
        if(a.containsKey("domain_min_m")){p.lo=vector(a.get("domain_min_m"));p.hi=vector(a.get("domain_max_m"));p.assumptions.add("遵守明确域范围，未按经验倍率重新扩大。");}
        for(int k=0;k<3;k++){if(!Double.isFinite(p.lo[k])||!Double.isFinite(p.hi[k])||p.lo[k]>=p.hi[k]||p.lo[k]>low[k]+tol||p.hi[k]<high[k]-tol)throw new IOException("外域范围无效或裁穿所选物体");if(k!=p.symIndex&&(low[k]-p.lo[k]<=tol||p.hi[k]-high[k]<=tol))throw new IOException("非对称外域必须与物体有正净距，不能使机翼触及入口/出口/自由侧边界");}
        if(!p.full&&Math.abs((p.side.equals("positive")?p.lo[p.symIndex]:p.hi[p.symIndex])-p.plane)>tol)throw new IOException("显式外域未保持所要求的对称平面");
        Map<String,Object> expected=FlowConditions.validateParameters(flowParameters(a,p,List.of("preflight-placeholder")));
        double temperature=((Number)expected.get("static_temperature_K")).doubleValue(),pressure=((Number)expected.get("absolute_static_pressure_Pa")).doubleValue();
        double mu=positive(a,"dynamic_viscosity_Pa_s",1.716e-5*Math.pow(temperature/273.15,1.5)*(273.15+110.4)/(temperature+110.4));
        p.recommendation=EngineeringDefaults.recommend(p.L,finite(a,"mach"),null,((Number)expected.get("speed_m_s")).doubleValue(),pressure/(287.05287*temperature),mu,positive(a,"target_yplus",1));
        p.assumptions.add("近壁初值使用与原生工况动作一致的明确/ISA静温、绝对静压、速度；黏度为明确值或该温度空气Sutherland估算，实际y+仍需求解。若已有非标准材料法，需另核其实际黏度。");
        if(!(p.recommendation.get("near_wall") instanceof Map))throw new IOException("当前工况无法产生近壁初值；先提供受支持的Mach/高度或明确完整物性");
        for(String key:List.of("base_size_m","wing_target_m","wing_min_m","farfield_target_m","maximum_cell_m","nearfield_size_m","first_layer_m","growth_ratio","surface_growth","volume_growth","curvature_points","prism_layers","chord_tolerance_m","surface_tolerance_m","angle_deg"))if(a.containsKey(key))positive(a,key,Double.NaN);
        if(positive(a,"wing_min_m",p.L/1000)>positive(a,"wing_target_m",p.L/100))throw new IOException("翼面最小尺寸不能大于目标尺寸");
        if(positive(a,"wing_min_m",p.L/1000)>positive(a,"farfield_target_m",p.D))throw new IOException("全局最小尺寸不能大于远场目标尺寸");
        Map<?,?> near=(Map<?,?>)p.recommendation.get("near_wall");
        SimulationActions.prismTotal(a.containsKey("prism_layers")?a.get("prism_layers"):near.get("layers"),positive(a,"first_layer_m",((Number)near.get("first_layer_full_thickness_m")).doubleValue()),positive(a,"growth_ratio",1.2));
        double curvature=positive(a,"curvature_points",30);if(curvature<3||curvature!=Math.rint(curvature))throw new IOException("curvature_points须至少3的整数");
        if(positive(a,"surface_growth",1.15)<1||positive(a,"volume_growth",1.1)<1)throw new IOException("增长率须不小于1");
        if(positive(a,"angle_deg",5)>=180)throw new IOException("CAD离散角须小于180度");
        validateAuxiliaryOwnership(s,p,operations,regions);
        if(p.region!=null&&p.region.getPhysicsContinuum()!=null){
            PhysicsContinuum existing=p.region.getPhysicsContinuum();PhysicsProfiles.validate(physicsParameters(a,p),existing);
            star.material.SingleComponentGasModel gas=existing.getModelManager().hasModel(star.material.SingleComponentGasModel.class);
            if(gas!=null){star.material.Material material=gas.getMaterial();String materialName=material.getPresentationName().toLowerCase(Locale.ROOT);star.material.MolecularWeightProperty mw=material.getMaterialProperties().getMaterialProperty(star.material.MolecularWeightProperty.class);double weight=mw!=null&&mw.getMethod() instanceof star.material.ConstantMaterialPropertyMethod?((star.material.ConstantMaterialPropertyMethod)mw.getMethod()).getQuantity().getSIValue():Double.NaN;
                if(!(materialName.contains("air")||materialName.contains("空气"))||!Double.isFinite(weight)||weight<=28.5||weight>=29.5)throw new IOException("现有材料不符合此空气经验工况入口："+material.getPresentationName()+"；未改域或替换材料");
            }else p.assumptions.add("现有连续体尚无单组分空气材料；需先按明确profile补齐后核验材料，工况动作失败会保留部分步骤收据。");
        }
        if(p.recreateRegion){if(p.region==null)throw new IOException("recreate_region=true未匹配到唯一现有旧Region；已缺失区域应使用普通repair补齐，不伪造删除回执");validateRegionDeletion(s,p.region);p.preservedPhysics=p.region.getPhysicsContinuum();p.preserveMesh=p.mesh!=null;if(p.preserveMesh){if(!p.oldControls.isEmpty())throw new IOException("原子区域重建保留网格策略，不停用已有其他局部控制；请先独立处理这些控制");validatePreservedMesh(s,p,a);p.meshBefore=meshStrategy(p.mesh);}p.recovery.add("按明确删除后重建要求，安全解除旧Region/Boundary的静态引用并实际删除旧区域；随后创建新ID区域");}
        p.assumptions.add(p.full?"全模D取实测三向跨度最大值；六个方向从物体表面取10D净距，无对称边界，特征L单独使用。":"远场尺度D=max(流向物体全长,关于指定平面的完整翼展)，默认各自由方向净距10D，参考官方transonic steady RANS经验；翼面参考L单独使用。");
        p.assumptions.add(p.scheme.equals("freestream")?"所有非对称外边界采用Free Stream。":"保留速度入口/压力出口方案：流向上游为入口、下游为出口；自由侧采用"+p.lateral+"，不把整个外域改为自由流。");
        return p;
    }

    /** Resolve only the selected body's actual dependency chain. Counts elsewhere in the
     * simulation do not imply ownership, and an interrupted deletion may leave no mesher. */
    private static void resolveRepair(Simulation s,Map<?,?> a,Plan p,List<MeshOperation> operations,List<Region> regions)throws Exception {
        List<SubtractPartsOperation> candidates=new ArrayList<>();
        for(MeshOperation operation:operations)if(operation instanceof SubtractPartsOperation){SubtractPartsOperation subtraction=(SubtractPartsOperation)operation;
            if(subtraction.getTargetPart() instanceof SimpleBlockPart&&subtraction.getInputGeometryObjects().getObjects().size()==2&&subtraction.getInputGeometryObjects().getObjects().contains(p.body)&&matches(a,"boolean_id",subtraction)&&matches(a,"domain_id",subtraction.getTargetPart()))candidates.add(subtraction);}
        if(candidates.size()>1)throw new IOException("所选CAD有多条外流场依赖链；须明确boolean_id或domain_id，未猜测修改对象");
        if(candidates.size()==1){p.subtract=candidates.get(0);p.block=(SimpleBlockPart)p.subtract.getTargetPart();p.recovery.add("复用已匹配CAD减运算与块外域");}
        else {
            if(a.containsKey("boolean_id"))throw new IOException("明确boolean_id未匹配所选CAD和块外域，可能已删除；请刷新实际目录后规划");
            if(a.containsKey("domain_id")){for(GeometryPart part:s.get(SimulationPartManager.class).getLeafParts())if(matches(a,"domain_id",part)&&part instanceof SimpleBlockPart)p.block=(SimpleBlockPart)part;
                if(p.block==null||p.block==p.body)throw new IOException("明确domain_id不是独立块外域，未修改源CAD");
            }else if(!operations.isEmpty()||!regions.isEmpty())throw new IOException("所选CAD没有可归属的外流场减运算；请明确独立domain_id，或先处理旧链，未覆盖已有操作/区域");
            p.recovery.add(p.block==null?"重建缺失的块外域与CAD减运算":"在明确独立块外域上重建缺失的CAD减运算");
        }
        if(p.block!=null&&!p.block.getCoordinateSystem().equals(s.getCoordinateSystemManager().getLabCoordinateSystem()))throw new IOException("repair只支持Lab坐标外域，未改变局部坐标系");
        Set<ClientServerObject> fluid=new HashSet<>();if(p.subtract!=null)fluid.addAll(p.subtract.getOutputPartObjects());
        List<AutoMeshOperation> meshes=new ArrayList<>();
        for(MeshOperation operation:operations){if(operation==p.subtract)continue;
            boolean touches=!Collections.disjoint(operation.getInputGeometryObjects().getObjects(),fluid)||(p.block!=null&&operation.getInputGeometryObjects().getObjects().contains(p.block));
            if(!touches)continue;
            if(!(operation instanceof AutoMeshOperation)||operation.getInputGeometryObjects().hasQuery()||operation.getInputGeometryObjects().getObjects().isEmpty()||!fluid.containsAll(operation.getInputGeometryObjects().getObjects()))throw new IOException("目标外域/流体还被其他操作共享："+operation.getPresentationName()+"；须明确拆分依赖，未修改无关对象");
            meshes.add((AutoMeshOperation)operation);
        }
        if(meshes.size()>1)throw new IOException("目标流体有多个网格消费者；修复会共同影响，需明确拆分，未擅自选择");
        if(!meshes.isEmpty())p.mesh=meshes.get(0);
        for(MeshOperation operation:operations)if(operation instanceof AutoMeshOperation&&operation!=p.mesh)for(CustomMeshControl control:((AutoMeshOperation)operation).getCustomMeshControls().getObjects())for(GeometryObject selected:control.getGeometryObjects().getObjects())if(fluid.contains(selected)||selected==p.block||(selected instanceof PartSurface&&(fluid.contains(((PartSurface)selected).getPart())||((PartSurface)selected).getPart()==p.block)))throw new IOException("目标外域/流体被其他网格局部控制共享："+control.getPresentationName()+"；未改变无关操作的几何");
        if(a.containsKey("mesh_id")&&(p.mesh==null||!matches(a,"mesh_id",p.mesh)))throw new IOException("明确mesh_id与目标依赖链不符，可能已删除；请刷新后移除过期ID");
        p.recovery.add(p.mesh==null?"重建缺失的自动网格操作":"复用唯一关联自动网格操作");
        List<Region> linked=new ArrayList<>();
        for(Region region:regions){boolean touches=!Collections.disjoint(region.getPartGroup().getObjects(),fluid)||(p.block!=null&&region.getPartGroup().getObjects().contains(p.block));
            if(!touches)for(Boundary boundary:region.getBoundaryManager().getBoundaries())for(PartSurface face:boundary.getPartSurfaceGroup().getObjects())if(fluid.contains(face.getPart())||face.getPart()==p.block)touches=true;
            if(!touches)continue;
            if(region.getPartGroup().hasQuery()||region.getPartGroup().getObjects().isEmpty()||!fluid.containsAll(region.getPartGroup().getObjects()))throw new IOException("目标外域/流体所在区域还含其他部件或动态查询："+region.getPresentationName()+"；未改绑无关几何");
            linked.add(region);
        }
        if(linked.size()>1)throw new IOException("目标流体归属多个区域，未猜测重绑");
        if(!linked.isEmpty())p.region=linked.get(0);
        if(a.containsKey("region_id")&&(p.region==null||!matches(a,"region_id",p.region)))throw new IOException("明确region_id与目标依赖链不符，可能已删除；请刷新后移除过期ID");
        if(p.region!=null)for(Boundary boundary:p.region.getBoundaryManager().getBoundaries())if(boundary.isInternallyManaged()||boundary.isOwnedByOther()||!boundary.getDependentInterfaces().isEmpty()||boundary.getPartSurfaceGroup().hasQuery())throw new IOException("目标区域包含接口或受查询管理边界："+boundary.getPresentationName()+"；未改变受管理边界");
        p.recovery.add(p.region==null?"重建缺失的流体区域与边界":"复用唯一关联流体区域并按真实面重绑边界");
        if(p.mesh!=null){boolean poly=p.mesh.getMeshersCollection().stream().anyMatch(x->x instanceof star.dualmesher.DualAutoMesher),trimmed=p.mesh.getMeshersCollection().stream().anyMatch(x->x instanceof star.trimmer.TrimmerAutoMesher);
            if(!a.containsKey("mesh_type")&&poly!=trimmed){p.meshType=poly?"polyhedral":"trimmed";p.assumptions.add("repair未显式改变mesh_type，保留目标操作当前体网格器。 ");}
            if((p.meshType.equals("polyhedral")&&!poly)||(p.meshType.equals("trimmed")&&!trimmed))throw new IOException("所选mesh_type与现有体网格器不一致；repair不静默替换策略，请保留原类型或另行明确重建");
            for(CustomMeshControl control:p.mesh.getCustomMeshControls().getObjects())if(control.getEnableControl()&&!Set.of(p.prefix+"_Wall",p.prefix+"_Outer",p.prefix+"_Nearfield").contains(control.getPresentationName()))p.oldControls.add(control);
            if(!p.oldControls.isEmpty()&&!Boolean.TRUE.equals(a.get("replace_existing_mesh_controls")))throw new IOException("现有局部控制可能覆盖推荐设置："+p.oldControls.stream().map(CustomMeshControl::getPresentationName).toList()+"；明确整体重配才可给replace_existing_mesh_controls=true");
        }
    }
    /** Helper boxes and continua are mutable too: verify ownership before touching the
     * domain, rather than finding an attractive global name halfway through a repair. */
    private static void validateAuxiliaryOwnership(Simulation s,Plan p,List<MeshOperation> operations,List<Region> regions)throws Exception {
        if(p.region!=null&&p.region.getPhysicsContinuum()!=null)for(Region region:regions)if(region!=p.region&&Objects.equals(region.getPhysicsContinuum(),p.region.getPhysicsContinuum()))throw new IOException("目标物理连续体还由其他区域共享："+region.getPresentationName()+"；先明确分离连续体，未改变其他区域的模型或工况");
        SimpleBlockPart near=null;String name=p.prefix+"_Nearfield_Box";
        for(GeometryPart part:s.get(SimulationPartManager.class).getParts())if(part.getPresentationName().equals(name)){if(!(part instanceof SimpleBlockPart)||near!=null)throw new IOException("近翼辅助域同名或类型不唯一，须选择唯一name_prefix，未覆盖其他对象");near=(SimpleBlockPart)part;}
        if(p.mesh!=null)for(CustomMeshControl control:p.mesh.getCustomMeshControls().getObjects()){
            String controlName=control.getPresentationName();if((controlName.equals(p.prefix+"_Wall")||controlName.equals(p.prefix+"_Outer"))&&!(control instanceof SurfaceCustomMeshControl))throw new IOException("同名翼面/外域控制类型不符，未覆盖");
            if(controlName.equals(p.prefix+"_Nearfield")&&!(control instanceof VolumeCustomMeshControl))throw new IOException("同名近翼控制类型不符，未覆盖");
        }
        if(near==null)return;
        if(near==p.body||near==p.block||!near.getCoordinateSystem().equals(s.getCoordinateSystemManager().getLabCoordinateSystem()))throw new IOException("近翼辅助域与源CAD/外域冲突或不在Lab坐标，未修改该对象");
        for(MeshOperation operation:operations){if(operation.getInputGeometryObjects().getObjects().contains(near))throw new IOException("同名近翼块被网格/布尔操作当作输入："+operation.getPresentationName()+"；须选择唯一name_prefix");
            if(operation instanceof AutoMeshOperation)for(CustomMeshControl control:((AutoMeshOperation)operation).getCustomMeshControls().getObjects())if(control.getGeometryObjects().getObjects().contains(near)&&(operation!=p.mesh||!control.getPresentationName().equals(p.prefix+"_Nearfield")))throw new IOException("同名近翼块被其他控制共享："+control.getPresentationName()+"；未更新共享辅助几何");
        }
        for(Region region:regions)if(region.getPartGroup().getObjects().contains(near))throw new IOException("同名近翼块已分配到其他区域，未覆盖其几何");
    }
    private static boolean matches(Map<?,?> a,String key,ClientServerObject object){return !a.containsKey(key)||(object!=null&&Objects.equals(String.valueOf(a.get(key)),object.getObjectKeyAsString()));}

    private static double activeSize(PartsRelativeOrAbsoluteSize value,double base){return value.getRelativeOrAbsoluteOption().getSelected()==RelativeOrAbsoluteOption.ABSOLUTE?value.getAbsoluteSizeValue().getInternalValue():base*value.getRelativeSizeScalar().getInternalValue()/100.;}
    private static void matchingOverride(Map<?,?> action,String key,double actual)throws IOException {if(!action.containsKey(key))return;double requested=positive(action,key,Double.NaN);if(!Double.isFinite(actual)||Math.abs(requested-actual)>Math.max(1e-12,Math.abs(actual)*1e-8))throw new IOException("recreate_region保留当前网格策略，明确参数"+key+"与实际当前值不一致：requested="+requested+", actual="+actual+"；请独立明确修改网格参数，不在区域重建中替换策略");}
    private static Map<String,Object> meshStrategy(AutoMeshOperation mesh)throws Exception {Map<String,Object> state=new LinkedHashMap<>();state.put("mesh_id",mesh.getObjectKeyAsString());state.put("meshers",mesh.getMeshersCollection().stream().map(o->o.getClass().getName()).sorted().toList());state.put("defaults",MeshingProfiles.describe(mesh));state.put("prisms",mesh.hasPrismMesher()?actualPrisms(mesh):Map.of("enabled",false));List<Object> controls=new ArrayList<>();for(CustomMeshControl control:mesh.getCustomMeshControls().getObjects())controls.add(Map.of("id",control.getObjectKeyAsString(),"type",control.getClass().getName(),"name",control.getPresentationName(),"enabled",control.getEnableControl()));state.put("controls",controls);return state;}
    private static void validatePreservedMesh(Simulation s,Plan p,Map<?,?> a)throws Exception {
        AutoMeshDefaultValuesManager values=p.mesh.getDefaultValues();double base=values.get(BaseSize.class).getInternalValue();matchingOverride(a,"base_size_m",base);matchingOverride(a,"farfield_target_m",activeSize(values.get(PartsTargetSurfaceSize.class),base));matchingOverride(a,"maximum_cell_m",activeSize(values.get(MaximumCellSize.class),base));matchingOverride(a,"surface_growth",values.get(SurfaceGrowthRate.class).getGrowthRate());matchingOverride(a,"curvature_points",values.get(SurfaceCurvature.class).getNumPointsAroundCircle());if(a.containsKey("volume_growth"))matchingOverride(a,"volume_growth",values.get(PartsTetPolyGrowthRate.class).getGrowthRate());
        if(a.containsKey("prism_layers")||a.containsKey("first_layer_m")||a.containsKey("growth_ratio")){if(!p.mesh.hasPrismMesher())throw new IOException("区域重建不隐式添加棱柱网格器");Map<String,Object> prism=actualPrisms(p.mesh);matchingOverride(a,"prism_layers",((Number)prism.get("layers")).doubleValue());matchingOverride(a,"first_layer_m",((Number)prism.get("first_layer_m")).doubleValue());if(a.containsKey("growth_ratio")){int layers=((Number)prism.get("layers")).intValue();double h=((Number)prism.get("first_layer_m")).doubleValue(),total=SimulationActions.prismTotal(layers,h,a.get("growth_ratio"));matchingOverride(Map.of("total",total),"total",((Number)prism.get("total_thickness_m")).doubleValue());}}
        boolean wall=false,near=false;for(CustomMeshControl control:p.mesh.getCustomMeshControls().getObjects()){if(control.getGeometryObjects().hasQuery()&&(control.getPresentationName().equals(p.prefix+"_Wall")||control.getPresentationName().equals(p.prefix+"_Outer")))throw new IOException("当前表面控制使用动态查询，未覆盖原策略："+control.getObjectKeyAsString());if(control instanceof SurfaceCustomMeshControl&&control.getPresentationName().equals(p.prefix+"_Wall")){wall=true;matchingOverride(a,"wing_target_m",activeSize(control.getCustomValues().get(PartsTargetSurfaceSize.class),base));matchingOverride(a,"wing_min_m",activeSize(control.getCustomValues().get(PartsMinimumSurfaceSize.class),base));}if(control instanceof VolumeCustomMeshControl&&control.getPresentationName().equals(p.prefix+"_Nearfield")){near=true;matchingOverride(a,"nearfield_size_m",activeSize(control.getCustomValues().get(VolumeControlSize.class),base));}}
        if(!wall&&(a.containsKey("wing_target_m")||a.containsKey("wing_min_m")))throw new IOException("未找到当前命名翼面控制，无法核对并保留明确网格尺寸");if(!near&&a.containsKey("nearfield_size_m"))throw new IOException("未找到当前命名近翼控制，无法核对并保留明确网格尺寸");
    }
    private static void rebindPreservedMeshControls(Simulation s,Plan p,Map<String,List<PartSurface>> roles)throws Exception {List<PartSurface> outer=new ArrayList<>();for(Map.Entry<String,List<PartSurface>> role:roles.entrySet())if(!role.getKey().equals("wall"))outer.addAll(role.getValue());for(CustomMeshControl control:p.mesh.getCustomMeshControls().getObjects())if(control instanceof SurfaceCustomMeshControl){List<PartSurface> faces=control.getPresentationName().equals(p.prefix+"_Wall")?roles.get("wall"):control.getPresentationName().equals(p.prefix+"_Outer")?outer:null;if(faces!=null){if(control.getGeometryObjects().hasQuery())throw new IOException("表面控制执行期间变为查询，未覆盖");control.getGeometryObjects().setObjects(faces);flush(s);if(!ids(control.getGeometryObjects().getObjects()).equals(ids(faces)))throw new IOException("保留策略的表面控制新面重绑读回不一致");}}if(!meshStrategy(p.mesh).equals(p.meshBefore))throw new IOException("区域重建后网格器/默认值/棱柱/控制身份或开关发生变化，未确认策略保留");}

    private static final class ReferenceRemoval {
        ClientServerObject consumer;PartGroup group;List<NamedObject> before,after;List<String> removed;
    }
    private static final class RegionDeletionPlan {
        Region region;String id,name;Set<ClientServerObject> affected=new LinkedHashSet<>();List<ReferenceRemoval> removals=new ArrayList<>();
    }
    private static boolean managedReference(ClientServerObject object)throws Exception {
        for(String name:List.of("isInternallyManaged","isOwnedByOther","isReadOnly"))try{Object value=object.getClass().getMethod(name).invoke(object);if(Boolean.TRUE.equals(value))return true;}catch(NoSuchMethodException absent){}
        return object instanceof star.vis.Displayer&&!((star.vis.Displayer)object).canDelete();
    }
    private static List<String> referenceIds(Collection<?> values){List<String> result=new ArrayList<>();for(Object value:values)if(value instanceof ClientServerObject)result.add(((ClientServerObject)value).getObjectKeyAsString());Collections.sort(result);return result;}
    private static Set<ClientServerObject> referenceConsumers(Simulation s){Set<ClientServerObject> result=new LinkedHashSet<>();result.addAll(s.getPartManager().getObjects());result.addAll(s.getReportManager().getObjects());for(star.vis.Scene scene:s.getSceneManager().getScenes())result.addAll(scene.getDisplayerManager().getObjects());return result;}
    private static RegionDeletionPlan regionDeletionPlan(Simulation s,Region region)throws Exception {
        if(region==null||!s.getRegionManager().getRegions().contains(region))throw new IOException("安全区域删除目标不在当前SIM");
        if(region.getPartGroup().hasQuery()||managedReference(region))throw new IOException("目标Region受查询或其他对象管理，未解除其引用或删除："+region.getObjectKeyAsString());
        RegionDeletionPlan plan=new RegionDeletionPlan();plan.region=region;plan.id=region.getObjectKeyAsString();plan.name=region.getPresentationName();plan.affected.add(region);
        for(Boundary boundary:region.getBoundaryManager().getBoundaries()){if(boundary.isInternallyManaged()||boundary.isOwnedByOther()||!boundary.getDependentInterfaces().isEmpty()||boundary.getPartSurfaceGroup().hasQuery())throw new IOException("旧区域含受管理/接口/查询边界，未修改："+boundary.getObjectKeyAsString()+" "+boundary.getPresentationName());plan.affected.add(boundary);}
        Set<ClientServerObject> consumers=referenceConsumers(s),nativeReferences=new LinkedHashSet<>();
        for(ClientServerObject affected:plan.affected)for(ClientServerObject ref:affected.getReferences())if(!plan.affected.contains(ref)){consumers.add(ref);nativeReferences.add(ref);}
        if(s.has(MeshOperationManager.class)!=null)for(MeshOperation op:s.get(MeshOperationManager.class).getObjects()){
            if(!Collections.disjoint(op.getInputGeometryObjects().getObjects(),plan.affected))throw new IOException("旧Region或Boundary被计算操作引用，安全删除不改其输入："+op.getObjectKeyAsString());
            if(op instanceof AutoMeshOperation)for(CustomMeshControl control:((AutoMeshOperation)op).getCustomMeshControls().getObjects())if(!Collections.disjoint(control.getGeometryObjects().getObjects(),plan.affected))throw new IOException("旧Region或Boundary被网格控制引用，未清除计算选择："+control.getObjectKeyAsString());
        }
        for(Region other:s.getRegionManager().getRegions())if(other!=region){if(!Collections.disjoint(other.getPartGroup().getObjects(),plan.affected))throw new IOException("旧区域被其他Region共享引用："+other.getObjectKeyAsString());for(Boundary boundary:other.getBoundaryManager().getBoundaries())if(!Collections.disjoint(boundary.getPartSurfaceGroup().getObjects(),plan.affected))throw new IOException("旧区域被其他Boundary共享引用："+boundary.getObjectKeyAsString());}
        Set<ClientServerObject> covered=new HashSet<>();
        for(ClientServerObject consumer:consumers){if(plan.affected.contains(consumer))continue;PartGroup group=ExtendedActions.partGroup(consumer);boolean selects=group!=null&&!Collections.disjoint(group.getObjects(),plan.affected);if(!selects)continue;
            if(!(consumer instanceof star.vis.PartDisplayer||consumer instanceof star.base.report.AnalysisReport))throw new IOException("旧区域被不支持安全自动清除的消费者引用："+consumer.getObjectKeyAsString()+" "+consumer.getPresentationName()+"；未删除或改写该消费者");
            if(group.hasQuery()||managedReference(consumer))throw new IOException("旧区域消费者使用动态查询或受管理，未覆盖其选择："+consumer.getObjectKeyAsString()+" "+consumer.getPresentationName());
            ReferenceRemoval update=new ReferenceRemoval();update.consumer=consumer;update.group=group;update.before=new ArrayList<>(group.getObjects());update.after=new ArrayList<>();update.removed=new ArrayList<>();for(NamedObject selected:update.before)if(plan.affected.contains(selected))update.removed.add(selected.getObjectKeyAsString());else update.after.add(selected);plan.removals.add(update);covered.add(consumer);
        }
        nativeReferences.removeAll(covered);if(!nativeReferences.isEmpty())throw new IOException("旧Region/Boundary还有未知或非静态集合引用，未猜测解除："+nativeReferences.stream().map(o->o.getObjectKeyAsString()+" "+o.getPresentationName()).toList());
        return plan;
    }
    /** Shared by explicit atomic recreation and Region-only delete_object detachment. No authorization parser here. */
    static void validateRegionDeletion(Simulation s,Region region)throws Exception {regionDeletionPlan(s,region);}
    static Map<String,Object> deleteRegionDetachingStaticReferences(Simulation s,Region region)throws Exception {
        RegionDeletionPlan plan=regionDeletionPlan(s,region);Map<String,Object> proof=new LinkedHashMap<>();List<Map<String,Object>> updates=new ArrayList<>();proof.put("deleted_id",plan.id);proof.put("deleted_name",plan.name);proof.put("reference_updates",updates);
        try{
            for(ReferenceRemoval update:plan.removals){if(update.group.hasQuery()||managedReference(update.consumer)||!referenceIds(update.group.getObjects()).equals(referenceIds(update.before)))throw new IOException("安全删除执行前消费者选择已变化："+update.consumer.getObjectKeyAsString());update.group.setObjects(update.after);flush(s);if(update.group.hasQuery()||!referenceIds(update.group.getObjects()).equals(referenceIds(update.after)))throw new IOException("安全删除静态引用清除读回失败："+update.consumer.getObjectKeyAsString());Map<String,Object> actual=new LinkedHashMap<>();actual.put("consumer_id",update.consumer.getObjectKeyAsString());actual.put("consumer_name",update.consumer.getPresentationName());actual.put("consumer_type",update.consumer.getClass().getName());actual.put("selection_before_ids",referenceIds(update.before));actual.put("removed_ids",update.removed);actual.put("selection_after_ids",referenceIds(update.group.getObjects()));actual.put("static_selection_readback",true);actual.put("direct_region_selection_removed",update.removed.contains(plan.id));updates.add(actual);}
            RegionDeletionPlan current=regionDeletionPlan(s,region);if(!current.removals.isEmpty())throw new IOException("删除前出现新的旧区域引用，未继续删除");
            s.getRegionManager().removeRegions(List.of(region));flush(s);boolean absent=s.getRegionManager().getRegions().stream().noneMatch(r->r.getObjectKeyAsString().equals(plan.id));if(!absent)throw new IOException("旧Region删除后仍在当前管理器");proof.put("deleted_absent",true);proof.put("validation","仅解除已验证的静态Region/Boundary成员引用并实际删除Region及其子边界；保留其他选择成员、消费者、CAD、连续体和网格操作。");return proof;
        }catch(Exception failure){proof.put("status","incomplete");proof.put("failure_reason",String.valueOf(failure.getMessage()));proof.put("deleted_absent",s.getRegionManager().getRegions().stream().noneMatch(r->r.getObjectKeyAsString().equals(plan.id)));throw new WorkflowException("安全区域删除未完成："+failure.getMessage(),failure,proof);}
    }
    @SuppressWarnings("unchecked")
    private static void rebindRecreatedRegionReferences(Simulation s,Map<String,Object> proof,Region replacement)throws Exception {
        String old=String.valueOf(proof.get("deleted_id"));Map<String,ClientServerObject> live=new LinkedHashMap<>();for(ClientServerObject consumer:referenceConsumers(s))live.put(consumer.getObjectKeyAsString(),consumer);
        for(Object raw:(Collection<?>)proof.get("reference_updates")){Map<String,Object> update=(Map<String,Object>)raw;if(!Boolean.TRUE.equals(update.get("direct_region_selection_removed")))continue;ClientServerObject consumer=live.get(update.get("consumer_id"));PartGroup group=consumer==null?null:ExtendedActions.partGroup(consumer);
            if(group==null||group.hasQuery()||managedReference(consumer)||!referenceIds(group.getObjects()).equals(update.get("selection_after_ids"))){update.put("region_rebind_status","not_applied_current_selection_changed_or_unavailable");continue;}
            List<NamedObject> selected=new ArrayList<>(group.getObjects());if(!selected.contains(replacement))selected.add(replacement);group.setObjects(selected);flush(s);if(group.hasQuery()||!referenceIds(group.getObjects()).equals(referenceIds(selected)))throw new IOException("新Region显示/报告引用一对一恢复读回不一致："+consumer.getObjectKeyAsString());update.put("replacement_region_id",replacement.getObjectKeyAsString());update.put("selection_after_recreation_ids",referenceIds(group.getObjects()));update.put("region_rebind_status","old_region_replaced_with_new_region");update.put("boundary_rebind_policy","Old Boundary members were cleared; no role mapping guessed");
        }
    }

    /** Fresh read-only evidence for a completed native transaction. This does not plan,
     * mutate configuration, create reports, rebuild geometry, mesh, or run the solver. */
    static Map<String,Object> currentCompletionEvidence(Simulation s,Map<?,?> recommended,Map<?,?> actual){
        Map<String,Object> result=new LinkedHashMap<>();List<String> incomplete=new ArrayList<>();result.put("verified",false);result.put("incomplete_reasons",incomplete);result.put("scope","Fresh current-state readback immediately after a serialized saved external-aero transaction; not arbitrary simultaneous external GUI edits, CFD convergence, solved y+ or mesh-quality acceptance");
        try{
            if(!"prepare_external_aero".equals(actual.get("workflow"))||!"complete".equals(actual.get("stage"))||!supports(String.valueOf(actual.get("requested_action"))))throw new IOException("缺少完整外流场原生事务回执");
            if(!(actual.get("completion_snapshot") instanceof Map))throw new IOException("该保存事务没有当前状态验证快照，不能仅凭旧readiness提前结束");
            if(!Objects.equals(recommended.get("body_id"),actual.get("body_id")))throw new IOException("所选CAD与保存事务不一致");
            Map<?,?> flow=completionStep(actual,"flow_conditions");if(!Boolean.TRUE.equals(flow.get("conditions_verified"))||!(flow.get("actual") instanceof Map))throw new IOException("缺少逐项SI工况读回证明");
            Map<String,Object> current=completionSnapshot(s,actual);result.put("current",current);verifyCompletionParameters(recommended,actual,current,flow,incomplete);
            compareCompletion(actual.get("completion_snapshot"),current,"current",incomplete);
            compareCompletion(flow.get("actual"),current.get("flow_conditions"),"flow_conditions",incomplete);compareCompletion(actual.get("domain_bounds_m"),current.get("domain_bounds_m"),"domain_bounds_m",incomplete);
            Map<?,?> physics=completionStep(actual,"physics_profile");if(!(physics.get("actual") instanceof Map))incomplete.add("缺少完整物理模型原生读回");else compareCompletion(physics.get("actual"),current.get("physics_profile"),"physics_profile",incomplete);
            if(Boolean.TRUE.equals(actual.get("meshing_executed"))){if(!Boolean.FALSE.equals(current.get("mesh_dirty")))incomplete.add("已执行网格的事务其当前网格已过期");Map<?,?> fv=(Map<?,?>)current.get("finite_volume_mesh");if(fv==null||!Boolean.TRUE.equals(fv.get("all_region_counts_observed"))||!(fv.get("observed_cell_count") instanceof Number)||((Number)fv.get("observed_cell_count")).longValue()<=0||!Boolean.FALSE.equals(fv.get("under_construction")))incomplete.add("当前目标区域没有读到已完成的正体网格单元");}
            if(Boolean.TRUE.equals(actual.get("meshing_executed"))!=Boolean.TRUE.equals(recommended.get("execute_mesh")))incomplete.add("当前事务网格执行证明与模型选择参数不一致");
            if(!Boolean.FALSE.equals(current.get("boolean_dirty")))incomplete.add("当前CAD布尔仍过期");if(Boolean.TRUE.equals(current.get("body_needs_update")))incomplete.add("当前源CAD仍待更新");if(!Boolean.TRUE.equals(current.get("fluid_closed"))||!Boolean.TRUE.equals(current.get("fluid_manifold")))incomplete.add("当前流体几何不是封闭流形");
            if(!Boolean.TRUE.equals(((Map<?,?>)current.get("physics_profile")).get("required_models_complete")))incomplete.add("当前物理模型链尚有未满足依赖");
            if(hasCompletionReadError(current))incomplete.add("当前原生读回含错误，不能证明配置完成");
            result.put("verified",incomplete.isEmpty());result.put("no_configuration_writes",true);
        }catch(Exception error){incomplete.add(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
        return result;
    }
    private static void verifyCompletionParameters(Map<?,?> selected,Map<?,?> actual,Map<String,Object> current,Map<?,?> flow,List<String> incomplete)throws Exception {
        String mode=text(selected,"domain_mode","half"),scheme=text(selected,"boundary_scheme","velocity_pressure");if(!Objects.equals(mode,actual.get("domain_mode")))incomplete.add("保存事务半模/全模与选定参数不一致");
        Map<?,?> roles=(Map<?,?>)current.get("boundary_roles"),bounds=(Map<?,?>)current.get("domain_bounds_m");
        if(mode.equals("half")){String axis=text(selected,"symmetry_axis","");int k=GeometryBounds.axis(axis);double plane=finite(selected,"symmetry_plane_m");double lo=((Number)((List<?>)bounds.get("min_m")).get(k)).doubleValue(),hi=((Number)((List<?>)bounds.get("max_m")).get(k)).doubleValue();if(!roles.containsKey("symmetry")||Math.min(Math.abs(lo-plane),Math.abs(hi-plane))>1e-9)incomplete.add("当前外域未保持选定半模平面");}else if(roles.containsKey("symmetry"))incomplete.add("当前全模仍包含事务对称面");
        if(scheme.equals("freestream")&&(!roles.containsKey("freestream")||roles.containsKey("velocity_inlet")||roles.containsKey("pressure_outlet")))incomplete.add("当前边界角色不符合选定Free Stream方案");
        if(scheme.equals("velocity_pressure")&&(!roles.containsKey("velocity_inlet")||!roles.containsKey("pressure_outlet")))incomplete.add("当前边界角色缺少选定速度入口/压力出口");
        if(selected.containsKey("domain_min_m"))compareCompletion(selected.get("domain_min_m"),bounds.get("min_m"),"selected.domain_min_m",incomplete);if(selected.containsKey("domain_max_m"))compareCompletion(selected.get("domain_max_m"),bounds.get("max_m"),"selected.domain_max_m",incomplete);
        Map<String,Object> conditions=new LinkedHashMap<>();conditions.put("type","configure_flow_conditions");conditions.put("boundary_ids",flow.get("selected_boundary_ids"));for(String key:List.of("mach","altitude_m","static_temperature_K","static_pressure_Pa","speed_m_s","velocity_m_s","flow_direction","reference_pressure_Pa","turbulent_viscosity_ratio","turbulence_intensity","dynamic_viscosity_Pa_s"))if(selected.containsKey(key))conditions.put(key,selected.get(key));
        if(!conditions.containsKey("velocity_m_s")&&!conditions.containsKey("flow_direction")){String axis=text(selected,"flow_axis","");if(!axis.matches("[+-][xyz]"))throw new IOException("选定流向轴缺失");double[] direction={0,0,0};direction[GeometryBounds.axis(axis.substring(1))]=axis.startsWith("-")?-1:1;conditions.put("flow_direction",vector(direction));}
        compareCompletion(FlowConditions.validateParameters(conditions),flow.get("requested_SI"),"selected.flow_requested_SI",incomplete);
    }
    private static Map<?,?> completionStep(Map<?,?> actual,String name)throws IOException {
        Object raw=actual.get("steps");if(raw instanceof Collection)for(Object item:(Collection<?>)raw)if(item instanceof Map&&name.equals(((Map<?,?>)item).get("step"))&&((Map<?,?>)item).get("actual") instanceof Map)return (Map<?,?>)((Map<?,?>)item).get("actual");throw new IOException("缺少原生子步骤："+name);
    }
    private static ClientServerObject completionObject(Simulation s,Object id)throws IOException {
        if(!(id instanceof String))throw new IOException("当前事务对象ID缺失");
        for(GeometryPart part:s.get(SimulationPartManager.class).getLeafParts())if(id.equals(part.getObjectKeyAsString()))return part;
        for(MeshOperation op:s.get(MeshOperationManager.class).getObjects())if(id.equals(op.getObjectKeyAsString()))return op;
        for(Region region:s.getRegionManager().getRegions())if(id.equals(region.getObjectKeyAsString()))return region;
        throw new IOException("保存事务对象当前不存在："+id);
    }
    private static Map<String,Object> completionSnapshot(Simulation s,Map<?,?> actual)throws Exception {
        ClientServerObject source=completionObject(s,actual.get("body_id")),domain=completionObject(s,actual.get("domain_id")),producer=completionObject(s,actual.get("boolean_id")),output=completionObject(s,actual.get("fluid_id")),assigned=completionObject(s,actual.get("region_id")),mesher=completionObject(s,actual.get("mesh_id"));
        if(!(source instanceof MeshPart)||!(domain instanceof SimpleBlockPart)||!(producer instanceof SubtractPartsOperation)||!(output instanceof MeshPart)||!(assigned instanceof Region)||!(mesher instanceof AutoMeshOperation))throw new IOException("保存事务对象的当前原生类型不一致");
        MeshPart body=(MeshPart)source,fluid=(MeshPart)output;SimpleBlockPart block=(SimpleBlockPart)domain;SubtractPartsOperation subtract=(SubtractPartsOperation)producer;Region region=(Region)assigned;AutoMeshOperation mesh=(AutoMeshOperation)mesher;
        if(subtract.getTargetPart()!=block||!ids(subtract.getInputGeometryObjects().getObjects()).equals(Set.of(body.getObjectKeyAsString(),block.getObjectKeyAsString()))||!ids(subtract.getOutputPartObjects()).equals(Set.of(fluid.getObjectKeyAsString())))throw new IOException("当前布尔输入/目标/输出不再是保存事务唯一链");
        if(region.getPartGroup().hasQuery()||mesh.getInputGeometryObjects().hasQuery()||!ids(region.getPartGroup().getObjects()).equals(Set.of(fluid.getObjectKeyAsString()))||!ids(mesh.getInputGeometryObjects().getObjects()).equals(Set.of(fluid.getObjectKeyAsString())))throw new IOException("当前Region或AutoMesh不再唯一引用所选流体");
        if(!block.getCoordinateSystem().equals(s.getCoordinateSystemManager().getLabCoordinateSystem()))throw new IOException("当前外域不再处于Lab坐标");
        Map<String,Object> out=new LinkedHashMap<>();out.put("body_id",body.getObjectKeyAsString());out.put("body_surface_ids",new TreeSet<>(ids(body.getPartSurfaces())));out.put("body_triangle_count",body.getTriangleCount());out.put("body_needs_update",body.needsUpdate());out.put("domain_id",block.getObjectKeyAsString());out.put("domain_bounds_m",Map.of("min_m",vector(block.getCorner1().getSIValue().toDoubleArray()),"max_m",vector(block.getCorner2().getSIValue().toDoubleArray())));out.put("boolean_id",subtract.getObjectKeyAsString());out.put("boolean_dirty",subtract.isDirty());out.put("boolean_settings",MeshingProfiles.describe(subtract));out.put("fluid_id",fluid.getObjectKeyAsString());out.put("fluid_surface_ids",new TreeSet<>(ids(fluid.getPartSurfaces())));out.put("fluid_closed",fluid.isClosed());out.put("fluid_manifold",fluid.isManifold());out.put("region_id",region.getObjectKeyAsString());
        Map<String,Object> roles=new LinkedHashMap<>();Map<?,?> savedRoles=completionStep(actual,"geometry_boundaries");Set<String> covered=new HashSet<>();
        for(Map.Entry<?,?> entry:savedRoles.entrySet()){
            String role=String.valueOf(entry.getKey());if(!(entry.getValue() instanceof Map))throw new IOException("保存边界角色读回缺失");Map<?,?> saved=(Map<?,?>)entry.getValue();Boundary chosen=null;for(Boundary b:region.getBoundaryManager().getBoundaries())if(Objects.equals(saved.get("id"),b.getObjectKeyAsString()))chosen=b;
            if(chosen==null||chosen.getPartSurfaceGroup().hasQuery()||chosen.isInternallyManaged()||chosen.isOwnedByOther()||!chosen.getDependentInterfaces().isEmpty()||!boundaryType(role).isInstance(chosen.getBoundaryType()))throw new IOException("当前边界角色/类型/静态归属与保存事务不符："+role);
            List<String> faces=new ArrayList<>();Map<String,Object> patches=new TreeMap<>();for(PartSurface face:chosen.getPartSurfaceGroup().getObjects()){if(face.getPart()!=fluid||face.hasBoundary()!=chosen||!covered.add(face.getObjectKeyAsString()))throw new IOException("当前边界面所属区域或唯一归属不一致："+role);faces.add(face.getObjectKeyAsString());patches.put(face.getObjectKeyAsString(),new TreeSet<>(fluid.getPartSurfacePatches(face)));}Collections.sort(faces);if(faces.isEmpty())throw new IOException("当前保存角色没有实际表面："+role);roles.put(role,Map.of("id",chosen.getObjectKeyAsString(),"type",chosen.getBoundaryType().getClass().getName(),"surface_ids",faces,"surface_patches",patches));
        }
        if(!covered.equals(ids(fluid.getPartSurfaces())))throw new IOException("当前保存角色未完整且唯一覆盖所有流体面");out.put("boundary_roles",roles);out.put("continuum_region_ids",region.getPhysicsContinuum()==null?List.of():new TreeSet<>(ids(region.getPhysicsContinuum().getRegions())));out.put("physics_profile",PhysicsProfiles.describe(region));out.put("flow_conditions",FlowConditions.describe(region));out.put("mesh_id",mesh.getObjectKeyAsString());out.put("mesh_dirty",mesh.isDirty());out.put("mesh_strategy",completionMeshStrategy(mesh));if(Boolean.TRUE.equals(actual.get("meshing_executed")))out.put("finite_volume_mesh",FlowMeshGate.observe(s,Set.of(region.getObjectKeyAsString())));out.put("iteration",s.getSimulationIterator().getCurrentIteration());return out;
    }
    private static Map<String,Object> completionMeshStrategy(AutoMeshOperation mesh)throws Exception {
        Map<String,Object> out=meshStrategy(mesh);List<Map<String,Object>> controls=new ArrayList<>();
        for(CustomMeshControl control:mesh.getCustomMeshControls().getObjects()){
            Map<String,Object> state=new LinkedHashMap<>();state.put("id",control.getObjectKeyAsString());state.put("enabled",control.getEnableControl());state.put("type",control.getClass().getName());state.put("selection_query",control.getGeometryObjects().hasQuery());state.put("selection_ids",new TreeSet<>(ids(control.getGeometryObjects().getObjects())));
            Map<String,Object> boxes=new TreeMap<>();for(GeometryObject selected:control.getGeometryObjects().getObjects())if(selected instanceof SimpleBlockPart){SimpleBlockPart box=(SimpleBlockPart)selected;boxes.put(box.getObjectKeyAsString(),Map.of("min_m",vector(box.getCorner1().getSIValue().toDoubleArray()),"max_m",vector(box.getCorner2().getSIValue().toDoubleArray())));}state.put("selected_block_bounds_m",boxes);
            if(control.getCustomConditions().has(PartsCustomizePrismMesh.class)!=null)state.put("prism_option",control.getCustomConditions().get(PartsCustomizePrismMesh.class).getCustomPrismOptions().getSelected());
            if(control.getCustomConditions().has(PartsTargetSurfaceSizeOption.class)!=null)state.put("target_option",control.getCustomConditions().get(PartsTargetSurfaceSizeOption.class).getSelected());
            if(control.getCustomConditions().has(PartsMinimumSurfaceSizeOption.class)!=null)state.put("minimum_option",control.getCustomConditions().get(PartsMinimumSurfaceSizeOption.class).getSelected());
            if(control.getCustomValues().has(PartsTargetSurfaceSize.class)!=null)state.put("target",completionSize(control.getCustomValues().get(PartsTargetSurfaceSize.class)));
            if(control.getCustomValues().has(PartsMinimumSurfaceSize.class)!=null)state.put("minimum",completionSize(control.getCustomValues().get(PartsMinimumSurfaceSize.class)));
            if(control.getCustomValues().has(VolumeControlSize.class)!=null)state.put("volume_size",completionSize(control.getCustomValues().get(VolumeControlSize.class)));
            if(control.getCustomConditions().has(star.dualmesher.VolumeControlDualMesherSizeOption.class)!=null)state.put("poly_volume_size_enabled",control.getCustomConditions().get(star.dualmesher.VolumeControlDualMesherSizeOption.class).getVolumeControlDualMesherSizeOption());
            if(control.getCustomConditions().has(star.trimmer.VolumeControlTrimmerSizeOption.class)!=null)state.put("trimmed_volume_size_enabled",control.getCustomConditions().get(star.trimmer.VolumeControlTrimmerSizeOption.class).getVolumeSourceTrimmerSizeOption());
            controls.add(state);
        }controls.sort(Comparator.comparing(x->String.valueOf(x.get("id"))));out.put("control_readback",controls);return out;
    }
    private static Map<String,Object> completionSize(PartsRelativeOrAbsoluteSize value){return Map.of("mode",value.getRelativeOrAbsoluteOption().getSelected(),"absolute_m",value.getAbsoluteSizeValue().getInternalValue(),"relative_percent",value.getRelativeSizeScalar().getInternalValue());}
    private static boolean hasCompletionReadError(Object object){if(object instanceof Map)for(Map.Entry<?,?> entry:((Map<?,?>)object).entrySet()){if(String.valueOf(entry.getKey()).contains("read_error")&&entry.getValue()!=null&&!(entry.getValue() instanceof Collection&&((Collection<?>)entry.getValue()).isEmpty())&&!String.valueOf(entry.getValue()).isEmpty())return true;if(hasCompletionReadError(entry.getValue()))return true;}else if(object instanceof Collection)for(Object item:(Collection<?>)object)if(hasCompletionReadError(item))return true;return false;}
    private static void compareCompletion(Object saved,Object current,String path,List<String> reasons){
        if(reasons.size()>=12)return;
        if(saved instanceof Number&&current instanceof Number){double a=((Number)saved).doubleValue(),b=((Number)current).doubleValue();if(!Double.isFinite(a)||!Double.isFinite(b)||Math.abs(a-b)>Math.max(1e-10,Math.max(Math.abs(a),Math.abs(b))*1e-9))reasons.add(path+"当前SI值与已保存读回不一致");return;}
        if(saved instanceof Map&&current instanceof Map){Map<?,?> a=(Map<?,?>)saved,b=(Map<?,?>)current;if(!a.keySet().equals(b.keySet())){reasons.add(path+"当前读回字段与快照不一致");return;}for(Object key:a.keySet())compareCompletion(a.get(key),b.get(key),path+"."+key,reasons);return;}
        if(saved instanceof Collection&&current instanceof Collection){List<?> a=new ArrayList<>((Collection<?>)saved),b=new ArrayList<>((Collection<?>)current);if(a.size()!=b.size()){reasons.add(path+"当前集合成员数已改变");return;}boolean keyed=!a.isEmpty()&&a.stream().allMatch(x->x instanceof Map&&((Map<?,?>)x).get("id") instanceof String)&&b.stream().allMatch(x->x instanceof Map&&((Map<?,?>)x).get("id") instanceof String);if(keyed){Map<String,Object> left=new TreeMap<>(),right=new TreeMap<>();for(Object x:a)left.put(String.valueOf(((Map<?,?>)x).get("id")),x);for(Object x:b)right.put(String.valueOf(((Map<?,?>)x).get("id")),x);if(left.size()==a.size()&&right.size()==b.size()){compareCompletion(left,right,path,reasons);return;}}for(int i=0;i<a.size();i++)compareCompletion(a.get(i),b.get(i),path+"["+i+"]",reasons);return;}
        if(!Objects.equals(saved,current))reasons.add(path+"当前值与已保存读回不一致");
    }

    static Map<String,Object> execute(Simulation s,Map<?,?> a,ClientServerObject target)throws Exception {
        if(target!=s)throw new IOException("prepare_external_aero目标不是当前仿真");Plan p=prepare(s,a);RunState state=new RunState();
        synchronized(ACTIVE){if(ACTIVE.containsKey(s))throw new IOException("当前仿真已有外流场流程执行中");ACTIVE.put(s,state);}
        List<Map<String,Object>> steps=new ArrayList<>();List<String> changes=new ArrayList<>();Map<String,Object> out=new LinkedHashMap<>();
        int iteration=s.getSimulationIterator().getCurrentIteration();out.put("workflow","prepare_external_aero");out.put("requested_action",text(a,"type","prepare_external_aero"));out.put("mode",p.mode);out.put("iteration_before",iteration);out.put("steps",steps);out.put("changes",changes);out.put("assumptions",p.assumptions);out.put("recovery_plan",p.recovery);out.put("stage","domain");
        try {
            Units metre=(Units)s.getUnitsManager().getObject("m");SimulationPartManager manager=s.get(SimulationPartManager.class);
            checkpoint(state);
            if(p.recreateRegion){out.put("stage","region_recreation");Map<String,Object> deletion=deleteRegionDetachingStaticReferences(s,p.region);out.put("region_recreation",deletion);steps.add(Map.of("step","delete_old_region","actual",deletion));changes.add("已实际删除旧Region“"+deletion.get("deleted_name")+"”（"+deletion.get("deleted_id")+"），仅处理其已验证静态引用；原CAD、连续体及网格策略保留。");p.region=null;}
            if(!p.recovery.isEmpty())changes.add("目标依赖链恢复："+String.join("；",p.recovery)+"；原始CAD及无关对象保留。");
            if(p.block==null){p.block=s.get(MeshPartFactory.class).createNewBlockPart(manager);p.block.setPresentationName(p.prefix+"_Domain");p.block.setCoordinateSystem(s.getCoordinateSystemManager().getLabCoordinateSystem());}
            p.block.getCorner1().setCoordinate(metre,metre,metre,new DoubleVector(p.lo));p.block.getCorner2().setCoordinate(metre,metre,metre,new DoubleVector(p.hi));p.block.rebuildSimpleShapePart();flush(s);
            for(int k=0;k<3;k++)if(Math.abs(p.block.getCorner1().getSIValue().get(k)-p.lo[k])>1e-10||Math.abs(p.block.getCorner2().getSIValue().get(k)-p.hi[k])>1e-10)throw new IOException("外域角点读回不一致");
            changes.add("外域角点："+vector(p.lo)+" → "+vector(p.hi)+" m；翼面参考L="+p.L+" m，远场尺度D="+p.D+" m。");
            out.put("stage","cad_boolean");if(p.subtract==null){p.subtract=(SubtractPartsOperation)s.get(MeshOperationManager.class).createSubtractPartsOperation(List.of(p.block,p.body));p.subtract.setPresentationName(p.prefix+"_Subtract");p.subtract.setTargetPart(p.block);}
            Map<String,Object> precision=new LinkedHashMap<>();precision.put("cad_boolean",true);precision.put("reference_length_m",p.L);for(String key:List.of("chord_tolerance_m","surface_tolerance_m","angle_deg"))if(a.containsKey(key))precision.put(key,a.get(key));
            steps.add(Map.of("step","cad_boolean_settings","actual",MeshingProfiles.configureNewBoolean(s,p.subtract,List.of(p.block,p.body),precision)));
            checkpoint(state);state.running=p.subtract;try{p.subtract.execute();flush(s);}finally{state.running=null;}
            if(p.subtract.isDirty()||p.subtract.getOutputPartObjects().size()!=1)throw new IOException("CAD减运算未生成唯一当前流体输出");
            MeshPart fluid=(MeshPart)p.subtract.getOutputPartObjects().iterator().next();if(!fluid.isClosed()||!fluid.isManifold())throw new IOException("CAD减运算输出不是封闭流形，未继续划网格");
            changes.add("CAD布尔及明确绝对离散精度已配置，输出已更新；原始CAD未移动或改形。");
            out.put("stage","classify_faces");Map<String,List<PartSurface>> roles=classifyFaces(s,fluid,p.body,p.block,p.lo,p.hi,p.flow,p.sym,p.plane,p.scheme,p.lateral);
            checkpoint(state);out.put("stage","regions_boundaries");
            if(p.region==null){Set<String> before=ids(s.getRegionManager().getRegions());s.getRegionManager().newRegionsFromParts(List.of(fluid),"OneRegion",null,"OneBoundaryPerPartSurface",null,RegionManager.CreateInterfaceMode.BOUNDARY);flush(s);List<Region> added=new ArrayList<>();for(Region region:s.getRegionManager().getRegions())if(!before.contains(region.getObjectKeyAsString()))added.add(region);if(added.size()!=1)throw new IOException("创建流体区域后未读到唯一新增区域，未选择或覆盖已有区域");p.region=added.get(0);p.createdRegion=true;p.region.setPresentationName(p.prefix+"_Fluid");if(p.preservedPhysics!=null){p.region.setPhysicsContinuum(p.preservedPhysics);flush(s);}if(p.recreateRegion){Map<String,Object> proof=(Map<String,Object>)out.get("region_recreation");String newId=p.region.getObjectKeyAsString();if(newId.equals(proof.get("deleted_id")))throw new IOException("新旧Region ID相同，未确认真实重建");proof.put("created_region_id",newId);if(p.preservedPhysics!=null)proof.put("preserved_continuum_id",p.preservedPhysics.getObjectKeyAsString());}}
            else {p.region.getPartGroup().setObjects(List.of(fluid));flush(s);}
            Map<String,Boundary> boundaries=bindRoles(s,p.region,roles,p.prefix);
            for(Map.Entry<String,Boundary> b:boundaries.entrySet())changes.add("边界“"+b.getValue().getPresentationName()+"”："+b.getValue().getBoundaryType().getPresentationName()+"，实际表面 "+b.getValue().getPartSurfaceGroup().getObjects().stream().map(PartSurface::getQualifiedName).toList()+"。");
            steps.add(Map.of("step","geometry_boundaries","actual",boundaryReceipt(boundaries)));
            if(p.recreateRegion)rebindRecreatedRegionReferences(s,(Map<String,Object>)out.get("region_recreation"),p.region);
            checkpoint(state);
            out.put("stage","physics_profile");PhysicsContinuum physics=p.region.getPhysicsContinuum();boolean shared=false;if(physics!=null)for(Region region:s.getRegionManager().getRegions())if(region!=p.region&&Objects.equals(region.getPhysicsContinuum(),physics))shared=true;
            if(shared&&!p.createdRegion)throw new IOException("执行期间目标连续体被其他区域共享，未继续写入物理模型或工况");
            if(physics==null||shared){physics=s.getContinuumManager().createContinuum(PhysicsContinuum.class);physics.setPresentationName(p.prefix+"_Physics");p.region.setPhysicsContinuum(physics);if(shared)changes.add("为新建流体区域创建独立物理连续体；未使用STAR自动选择的其他区域共享连续体。");}
            Map<String,Object> profile=physicsParameters(a,p);
            steps.add(Map.of("step","physics_profile","actual",PhysicsProfiles.execute(s,profile,physics)));
            changes.add("区域已绑定连续体“"+physics.getPresentationName()+"”，完整物理模型profile="+p.profile+"。");
            out.put("stage","flow_conditions");List<String> openIds=new ArrayList<>();for(Map.Entry<String,Boundary> b:boundaries.entrySet())if(!Set.of("wall","symmetry").contains(b.getKey()))openIds.add(b.getValue().getObjectKeyAsString());Map<String,Object> conditions=flowParameters(a,p,openIds);
            steps.add(Map.of("step","flow_conditions","actual",FlowConditions.execute(s,conditions,physics)));
            changes.add("来流/材料/参考压力/初始值及全部非空开放边界工况已按给定Mach、高度与显式覆盖写入并读回。");
            checkpoint(state);
            out.put("stage","mesh_controls");if(p.mesh==null){List<String> meshers=new ArrayList<>(List.of("star.resurfacer.ResurfacerAutoMesher",p.meshType.equals("polyhedral")?"star.dualmesher.DualAutoMesher":"star.trimmer.TrimmerAutoMesher","star.prismmesher.PrismAutoMesher"));p.mesh=s.get(MeshOperationManager.class).createAutoMeshOperation(meshers,List.of(fluid));p.mesh.setPresentationName(p.prefix+"_Mesh");}
            else {if(!p.preserveMesh&&!p.mesh.hasPrismMesher()){List<String> meshers=new ArrayList<>();for(MesherBase mesher:p.mesh.getMeshersCollection())meshers.add(mesher.getClass().getName());meshers.add("star.prismmesher.PrismAutoMesher");p.mesh.getMeshers().setMeshersByNames(meshers);flush(s);if(!p.mesh.hasPrismMesher())throw new IOException("目标网格操作启用棱柱网格器后读回不一致");changes.add("按完整近壁配置要求为目标网格操作补齐棱柱网格器，保留原表面/体网格器。");}p.mesh.getInputGeometryObjects().setObjects(List.of(fluid));}
            if(p.preserveMesh){rebindPreservedMeshControls(s,p,roles);steps.add(Map.of("step","preserve_mesh_strategy","actual",meshStrategy(p.mesh)));changes.add("保留原自动网格操作、全部数值/网格器/棱柱/局部控制及开关，仅将既有翼面/外域控制绑定到当前真实面；旧体网格需重新生成。");}
            else {
            for(CustomMeshControl previous:p.oldControls){previous.setEnableControl(false);flush(s);if(previous.getEnableControl())throw new IOException("旧局部控制停用读回不一致："+previous.getPresentationName());steps.add(Map.of("step","disable_previous_mesh_control","control_id",previous.getObjectKeyAsString(),"name",previous.getPresentationName(),"enabled",false));changes.add("按明确整体重配授权停用目标网格操作旧局部控制“"+previous.getPresentationName()+"”；保留控制及原几何对象。");}
            double base=positive(a,"base_size_m",p.L/4);p.mesh.getDefaultValues().get(BaseSize.class).setValueAndUnits(base,metre);
            Map<String,Object> defaults=new LinkedHashMap<>();defaults.put("type","configure_mesh_defaults");defaults.put("farfield_target_m",positive(a,"farfield_target_m",p.D));defaults.put("global_min_m",positive(a,"wing_min_m",p.L/1000));defaults.put("maximum_cell_m",positive(a,"maximum_cell_m",2*p.D));defaults.put("surface_growth",positive(a,"surface_growth",1.15));defaults.put("curvature_points",positive(a,"curvature_points",30));
            if(p.mesh.getMeshersCollection().stream().anyMatch(x->x instanceof star.dualmesher.DualAutoMesher))defaults.put("volume_growth",positive(a,"volume_growth",1.1));else if(a.containsKey("volume_growth"))throw new IOException("现有网格器不支持明确volume_growth，未静默忽略");
            steps.add(Map.of("step","mesh_defaults","actual",MeshingProfiles.execute(s,defaults,p.mesh)));
            Map<?,?> wallRecommendation=(Map<?,?>)p.recommendation.get("near_wall");int layers=(int)positive(a,"prism_layers",((Number)wallRecommendation.get("layers")).doubleValue());
            if(a.containsKey("prism_layers")&&((Number)a.get("prism_layers")).doubleValue()!=layers)throw new IOException("prism_layers须整数");
            double h=positive(a,"first_layer_m",((Number)wallRecommendation.get("first_layer_full_thickness_m")).doubleValue()),r=positive(a,"growth_ratio",1.2);double total=SimulationActions.prismTotal(layers,h,r);
            configurePrisms(s,p.mesh,layers,h,total);
            List<PartSurface> wallFaces=roles.get("wall");List<PartSurface> outerFaces=new ArrayList<>();for(Map.Entry<String,List<PartSurface>> role:roles.entrySet())if(!role.getKey().equals("wall"))outerFaces.addAll(role.getValue());
            surfaceControl(s,p.mesh,p.prefix+"_Wall",wallFaces,positive(a,"wing_target_m",p.L/100),positive(a,"wing_min_m",p.L/1000),false);
            surfaceControl(s,p.mesh,p.prefix+"_Outer",outerFaces,Double.NaN,Double.NaN,true);
            steps.add(Map.of("step","outer_prism_exclusion","control",p.prefix+"_Outer","enabled",true,"purpose","Exclude outer and symmetry faces from prism layers; keep this control enabled","surface_ids",new ArrayList<>(ids(outerFaces))));
            nearfield(s,p.mesh,p.prefix,p.bodyBounds,p.sym,p.plane,p.side,p.L,positive(a,"nearfield_size_m",p.L/20));
            changes.add("网格Base="+base+" m；远场目标="+defaults.get("farfield_target_m")+" m、最大体尺寸="+defaults.get("maximum_cell_m")+" m、表面增长="+defaults.get("surface_growth")+"、曲率="+defaults.get("curvature_points")+"；仅翼面目标="+positive(a,"wing_target_m",p.L/100)+" m、最小="+positive(a,"wing_min_m",p.L/1000)+" m。");
            changes.add("仅真实机翼面添加棱柱："+layers+"层，首层全厚="+h+" m、增长="+r+"、总厚="+total+" m；外域及对称面禁用；近翼体尺寸="+positive(a,"nearfield_size_m",p.L/20)+" m。");
            }
            flush(s);checkpoint(state);
            boolean meshed=Boolean.TRUE.equals(a.get("execute_mesh"));
            if(meshed){out.put("stage","execute_mesh");state.running=p.mesh;try{p.mesh.execute();flush(s);}finally{state.running=null;}if(p.mesh.isDirty())throw new IOException("原生网格执行后仍过期，未确认完成");awaitVolumeReadback(s,state);out.put("quality_snapshot",MeshQualitySnapshot.inspect(s,p.full?null:p.sym,p.full?null:p.plane));changes.add("已在全部几何/边界/物理/数值设置后执行一次网格；质量及真实对称面位置见独立读数。");}
            int after=s.getSimulationIterator().getCurrentIteration();if(after!=iteration)throw new IOException("外流场设置流程意外改变求解迭代数");
            out.put("prism_enabled",p.mesh.hasPrismMesher());out.put("prism_settings",p.mesh.hasPrismMesher()?actualPrisms(p.mesh):Map.of("enabled",false));
            out.put("stage","complete");out.put("workflow","prepare_external_aero");out.put("mode",p.mode);out.put("steps",steps);out.put("changes",changes);out.put("assumptions",p.assumptions);out.put("recommendation",p.recommendation);out.put("domain_mode",p.full?"full":"half");out.put("body_id",p.body.getObjectKeyAsString());out.put("domain_id",p.block.getObjectKeyAsString());out.put("boolean_id",p.subtract.getObjectKeyAsString());out.put("fluid_id",fluid.getObjectKeyAsString());out.put("region_id",p.region.getObjectKeyAsString());out.put("mesh_id",p.mesh.getObjectKeyAsString());out.put("operation",p.mesh.getPresentationName());out.put("meshing_executed",meshed);out.put("mesh_validity",Map.of("current_geometry_meshed",meshed&&!p.mesh.isDirty(),"requires_regeneration",!meshed||p.mesh.isDirty(),"previous_volume_representation_is_not_acceptance",true));out.put("iteration_after",after);out.put("reference_length_m",p.L);out.put("domain_scale_m",p.D);out.put("domain_bounds_m",Map.of("min_m",vector(p.lo),"max_m",vector(p.hi)));out.put("readiness",CaseReadiness.inspect(s,"完整气动计算工况，物理模型采用定常"+(Boolean.TRUE.equals(PhysicsProfiles.describe(p.region).get("sa_equation_enabled"))?"SA":"SST")+(p.scheme.equals("velocity_pressure")?"，速度入口和压力出口":"")+(p.full?"":"，对称面"),Set.of(p.region.getObjectKeyAsString())));out.put("validation","原生顺序配置和读回完成；未运行CFD。网格执行状态与质量分开，实际y+和数值精度仍须求解验证。");try{out.put("completion_snapshot",completionSnapshot(s,out));}catch(Exception observation){out.put("completion_evidence_capture_error",observation.getMessage()==null?observation.getClass().getSimpleName():observation.getMessage());}
            return out;
        }catch(Exception failure){
            if(failure instanceof WorkflowException&&p.recreateRegion&&!out.containsKey("region_recreation"))out.put("region_recreation",((WorkflowException)failure).receipt());
            out.put("status","incomplete");out.put("stopped_stage",out.get("stage"));out.put("recovery_required",true);out.put("failure_reason",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());out.put("iteration_after",s.getSimulationIterator().getCurrentIteration());
            Map<String,Object> partial=new LinkedHashMap<>();
            try{if(p.block!=null)partial.put("domain_id",p.block.getObjectKeyAsString());if(p.subtract!=null){partial.put("boolean_id",p.subtract.getObjectKeyAsString());partial.put("boolean",MeshingProfiles.describe(p.subtract));}if(p.mesh!=null){partial.put("mesh_id",p.mesh.getObjectKeyAsString());partial.put("mesh",MeshingProfiles.describe(p.mesh));}if(p.region!=null){partial.put("region_id",p.region.getObjectKeyAsString());if(p.region.getPhysicsContinuum()!=null)partial.put("physics",PhysicsProfiles.describe(p.region.getPhysicsContinuum()));}}catch(Exception readFailure){partial.put("readback_error",readFailure.getMessage()==null?readFailure.getClass().getName():readFailure.getMessage());}
            out.put("partial_actual_readback",partial);throw new WorkflowException("外流场流程未完成（阶段："+out.get("stage")+"）："+out.get("failure_reason"),failure,out);
        }finally{ACTIVE.remove(s);}
    }

    /** GUI mesh notifications arrive on a separate stream after the synchronous mesh response.
     * Wait briefly for a real volume representation; dirty=false alone is insufficient. */
    private static void awaitVolumeReadback(Simulation s,RunState state)throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        do {checkpoint(state);flush(s);for(FvRepresentation representation:s.getRepresentationManager().getObjectsOf(FvRepresentation.class))if(representation.getCellCount()>0)return;
            Thread.sleep(25);
        }while(System.nanoTime()<deadline);
        throw new IOException("网格原生操作返回后5秒仍未读到非空体网格；未用dirty=false冒充完成，请检查服务端日志与实际网格");
    }

    /** Reusable for an explicitly authorized single-domain repair, before any volume meshing. */
    static Map<String,List<PartSurface>> classifyFaces(Simulation s,MeshPart fluid,MeshPart body,SimpleBlockPart domain,double[] low,double[] high,String flow,String symmetry,double plane,String scheme,String lateral)throws Exception {
        List<PartSurface> wall=new ArrayList<>(),outer=new ArrayList<>();
        for(PartSurface face:fluid.getPartSurfaces()){GeometryPart source=sourcePart(face);if(source==body)wall.add(face);else if(source==domain)outer.add(face);else throw new IOException("布尔输出面的真实上游来源不唯一；未根据名称猜测机翼/外域归属："+face.getPresentationName());}
        if(wall.isEmpty()||outer.isEmpty())throw new IOException("未取得实际机翼面和外域面，未继续分配边界");
        Set<String> walls=new HashSet<>();for(PartSurface face:wall)walls.add(face.getObjectKeyAsString());fluid.getPartSurfaceManager().splitPartSurfacesByAngle(outer,89);flush(s);
        Map<String,List<PartSurface>> roles=new LinkedHashMap<>();roles.put("wall",wall);roles.put("symmetry",new ArrayList<>());roles.put("velocity_inlet",new ArrayList<>());roles.put("pressure_outlet",new ArrayList<>());roles.put("freestream",new ArrayList<>());
        int f=GeometryBounds.axis(flow.substring(1)),sy=(symmetry==null||symmetry.isBlank())?-1:GeometryBounds.axis(symmetry);Set<String> planes=new HashSet<>();double scale=0;for(int k=0;k<3;k++)scale=Math.max(scale,high[k]-low[k]);double tolerance=Math.max(1e-8,scale*1e-10);
        Map<String,Object> bodyBounds=GeometryBounds.measure(body);double[] bodyLow=values(bodyBounds,"min_m"),bodyHigh=values(bodyBounds,"max_m");
        int[][] expectedPatches=new int[3][2];Set<Integer> uniquePatches=new HashSet<>();
        for(int k=0;k<3;k++)for(int end=0;end<2;end++){
            double[] sample=new double[3];for(int j=0;j<3;j++)sample[j]=bodyLow[j]-low[j]>tolerance?(low[j]+bodyLow[j])/2:high[j]-bodyHigh[j]>tolerance?(high[j]+bodyHigh[j])/2:(low[j]+high[j])/2;
            sample[k]=end==0?low[k]:high[k];expectedPatches[k][end]=fluid.getPatchIdBasedOnLabXYZ(sample[0],sample[1],sample[2]);
            if(!uniquePatches.add(expectedPatches[k][end]))throw new IOException("外域六平面的实际patch未能唯一识别；未按序号猜测边界");
        }
        for(PartSurface face:fluid.getPartSurfaces())if(!walls.contains(face.getObjectKeyAsString())) {
            int axis=-1;boolean maximum=false;Set<Integer> patches=new HashSet<>(fluid.getPartSurfacePatches(face));
            for(int k=0;k<3;k++)for(int end=0;end<2;end++)if(patches.contains(expectedPatches[k][end])){
                if(axis!=-1)throw new IOException("外域面匹配多个真实平面patch，未猜测边界类型");axis=k;maximum=end==1;
            }
            if(axis<0)throw new IOException("外域面未落在所设六个平面上，未以表面序号代替坐标判别");planes.add(axis+":"+maximum);
            String role;if(axis==sy&&Math.abs((maximum?high[axis]:low[axis])-plane)<=tolerance)role="symmetry";else if(scheme.equals("freestream"))role="freestream";else if(axis==f)role=maximum==flow.startsWith("+")?"pressure_outlet":"velocity_inlet";else role=lateral;
            roles.get(role).add(face);
        }
        if(planes.size()!=6||(sy>=0&&roles.get("symmetry").isEmpty())||(sy<0&&!roles.get("symmetry").isEmpty()))throw new IOException("实际外域未覆盖唯一六平面和指定对称面，拒绝部分边界配置");
        if(scheme.equals("velocity_pressure")&&(roles.get("velocity_inlet").isEmpty()||roles.get("pressure_outlet").isEmpty()))throw new IOException("实际输入没有非空入口/压力出口面");
        return roles;
    }
    private static GeometryPart sourcePart(PartSurface face){GeometryObject current=face;Set<String> seen=new HashSet<>();for(int i=0;i<6&&current!=null;i++){if(!seen.add(current.getObjectKeyAsString()))break;GeometryObject next=current.hasDerivedFrom();if(next==null)break;current=next;if(current instanceof PartSurface)return ((PartSurface)current).getPart();if(current instanceof GeometryObjectProxy){GeometryPart source=((GeometryObjectProxy)current).getPartUltimatelyDerivedFrom();if(source!=null)return source;}}return null;}
    static Map<String,Boundary> bindRoles(Simulation s,Region region,Map<String,List<PartSurface>> roles,String prefix)throws IOException {
        Map<String,Boundary> result=new LinkedHashMap<>();Set<Boundary> used=new HashSet<>();
        for(Map.Entry<String,List<PartSurface>> role:roles.entrySet())if(!role.getValue().isEmpty()){
            Class<? extends BoundaryType> type=boundaryType(role.getKey());Boundary chosen=null;String name=prefix+"_"+role.getKey();
            for(Boundary existing:region.getBoundaryManager().getBoundaries())if(existing.getPresentationName().equals(name)){chosen=existing;break;}
            if(chosen==null){List<Boundary> candidates=new ArrayList<>();for(Boundary existing:region.getBoundaryManager().getBoundaries())if(!used.contains(existing)&&type.isInstance(existing.getBoundaryType())&&!Collections.disjoint(existing.getPartSurfaceGroup().getObjects(),role.getValue()))candidates.add(existing);if(candidates.size()==1)chosen=candidates.get(0);}
            if(chosen==null)chosen=region.getBoundaryManager().createEmptyBoundary(name);if(chosen.isInternallyManaged()||chosen.isOwnedByOther()||!chosen.getDependentInterfaces().isEmpty()||chosen.getPartSurfaceGroup().hasQuery())throw new IOException("目标边界受接口或动态查询管理，未静默覆盖");
            chosen.setBoundaryType(type);chosen.getPartSurfaceGroup().setObjects(role.getValue());flush(s);
            used.add(chosen);result.put(role.getKey(),chosen);
        }
        // Boolean regeneration may temporarily expand a previous selection to all split faces.
        // Verify the complete assignment after every role has been rebuilt, not mid-transfer.
        for(Map.Entry<String,List<PartSurface>> role:roles.entrySet())if(!role.getValue().isEmpty()){
            Boundary boundary=result.get(role.getKey());
            if(!boundaryType(role.getKey()).isInstance(boundary.getBoundaryType())||!ids(boundary.getPartSurfaceGroup().getObjects()).equals(ids(role.getValue())))throw new IOException("边界真实类型/所属面读回不一致："+boundary.getPresentationName());
            for(PartSurface face:role.getValue())if(face.hasBoundary()==null||!Objects.equals(face.hasBoundary().getObjectKeyAsString(),boundary.getObjectKeyAsString()))throw new IOException("输出面未完整归属所要求边界："+face.getQualifiedName());
        }
        return result;
    }
    private static Class<? extends BoundaryType> boundaryType(String role)throws IOException {switch(role){case"wall":return WallBoundary.class;case"symmetry":return SymmetryBoundary.class;case"velocity_inlet":return InletBoundary.class;case"pressure_outlet":return PressureBoundary.class;case"freestream":return FreeStreamBoundary.class;default:throw new IOException("未知边界角色");}}
    private static Map<String,Object> physicsParameters(Map<?,?> a,Plan p){Map<String,Object> profile=new LinkedHashMap<>();profile.put("type","configure_physics_profile");profile.put("profile",p.profile);profile.put("space_dimension",3);profile.put("target_yplus",a.get("target_yplus"));if(a.containsKey("replace_conflicting_models"))profile.put("replace_conflicting_models",a.get("replace_conflicting_models"));return profile;}
    private static Map<String,Object> flowParameters(Map<?,?> a,Plan p,List<String> ids){Map<String,Object> conditions=new LinkedHashMap<>();conditions.put("type","configure_flow_conditions");conditions.put("mach",a.get("mach"));conditions.put("altitude_m",a.get("altitude_m"));conditions.put("flow_direction",vector(p.direction));conditions.put("boundary_ids",ids);for(String key:List.of("static_temperature_K","static_pressure_Pa","speed_m_s","velocity_m_s","reference_pressure_Pa","turbulent_viscosity_ratio","turbulence_intensity","dynamic_viscosity_Pa_s"))if(a.containsKey(key))conditions.put(key,a.get(key));return conditions;}
    private static Map<String,Object> boundaryReceipt(Map<String,Boundary> boundaries){Map<String,Object> result=new LinkedHashMap<>();for(Map.Entry<String,Boundary>b:boundaries.entrySet())result.put(b.getKey(),Map.of("id",b.getValue().getObjectKeyAsString(),"type",b.getValue().getBoundaryType().getClass().getName(),"surfaces",b.getValue().getPartSurfaceGroup().getObjects().stream().map(PartSurface::getQualifiedName).toList()));return result;}
    /** Read actual native values so subsequent turns need not infer prism configuration
     * from requested defaults or rerun a completed mesh to discover it. */
    private static Map<String,Object> actualPrisms(AutoMeshOperation mesh){
        Map<String,Object> result=new LinkedHashMap<>();if(!mesh.hasPrismMesher())return result;PrismAutoMesher mesher=mesh.getMeshers().hasMesher(PrismAutoMesher.class);result.put("layers",mesh.getDefaultValues().get(NumPrismLayers.class).getNumLayers());result.put("first_layer_m",mesh.getDefaultValues().get(PrismWallThickness.class).getInternalValue());result.put("total_thickness_m",mesh.getDefaultValues().get(PrismThickness.class).getAbsoluteSizeValue().getInternalValue());result.put("distribution_mode",mesher.getPrismStretchingOption().getSelected());result.put("stretching_function",mesher.getPrismStretchingFunction().getSelected());result.put("total_thickness_mode",mesh.getDefaultValues().get(PrismThickness.class).getRelativeOrAbsoluteOption().getSelected());result.put("readback_source","current native AutoMeshOperation default values; custom surface exclusions listed separately");return result;
    }
    private static void configurePrisms(Simulation s,AutoMeshOperation mesh,int layers,double first,double total)throws IOException {PrismAutoMesher mesher=mesh.getMeshers().hasMesher(PrismAutoMesher.class);mesher.getPrismStretchingFunction().setSelected(PrismStretchingFunction.GEOMETRIC_PROGRESSION);mesher.getPrismStretchingOption().setSelected(PrismStretchingOption.WALL_THICKNESS);Units m=(Units)s.getUnitsManager().getObject("m");mesh.getDefaultValues().get(NumPrismLayers.class).setNumLayers(layers);mesh.getDefaultValues().get(PrismThickness.class).setAbsoluteSize(total,m);mesh.getDefaultValues().get(PrismWallThickness.class).setValueAndUnits(first,m);flush(s);if(mesh.getDefaultValues().get(NumPrismLayers.class).getNumLayers()!=layers||Math.abs(mesh.getDefaultValues().get(PrismThickness.class).getAbsoluteSizeValue().getInternalValue()-total)>Math.max(1e-14,total*1e-9)||Math.abs(mesh.getDefaultValues().get(PrismWallThickness.class).getInternalValue()-first)>Math.max(1e-14,first*1e-9))throw new IOException("棱柱层数/完整首层/总厚读回不一致");}
    private static Set<String> ids(Collection<? extends ClientServerObject> objects){Set<String> keys=new HashSet<>();for(ClientServerObject object:objects)keys.add(object.getObjectKeyAsString());return keys;}
    private static void select(Simulation s,CustomMeshControl c,Collection<? extends GeometryObject> pick)throws IOException {DynamicQuerySelectorInput old=c instanceof SurfaceCustomMeshControl?((SurfaceCustomMeshControl)c).getGeometryObjectsInput():((VolumeCustomMeshControl)c).getGeometryObjectsInput();c.setGeometryObjectsInput(DynamicQuerySelectorInput.builder(old).descriptor(old.getModelDescriptor().cloneBuilder().selected(pick).build()).build());flush(s);if(!ids(c.getGeometryObjects().getObjects()).equals(ids(pick)))throw new IOException("局部网格控制选面读回不一致："+c.getPresentationName()+", requested="+ids(pick)+", actual="+ids(c.getGeometryObjects().getObjects()));}
    private static void surfaceControl(Simulation s,AutoMeshOperation mesh,String name,List<PartSurface> faces,double target,double min,boolean disable)throws IOException {SurfaceCustomMeshControl c=null;for(CustomMeshControl old:mesh.getCustomMeshControls().getObjects())if(old.getPresentationName().equals(name)){if(!(old instanceof SurfaceCustomMeshControl))throw new IOException("同名局部控制类型不匹配");c=(SurfaceCustomMeshControl)old;}if(c==null){c=mesh.getCustomMeshControls().createSurfaceControl();c.setPresentationName(name);}select(s,c,faces);c.setEnableControl(true);c.getCustomConditions().get(PartsCustomizePrismMesh.class).getCustomPrismOptions().setSelected(disable?PartsCustomPrismsOption.DISABLE:PartsCustomPrismsOption.PARENT);if(!disable){c.getCustomConditions().get(PartsTargetSurfaceSizeOption.class).setSelected(PartsTargetSurfaceSizeOption.CUSTOM);c.getCustomConditions().get(PartsMinimumSurfaceSizeOption.class).setSelected(PartsMinimumSurfaceSizeOption.CUSTOM);Units m=(Units)s.getUnitsManager().getObject("m");c.getCustomValues().get(PartsTargetSurfaceSize.class).setAbsoluteSize(target,m);c.getCustomValues().get(PartsMinimumSurfaceSize.class).setAbsoluteSize(min,m);}flush(s);}
    private static void nearfield(Simulation s,AutoMeshOperation mesh,String prefix,Map<String,Object> bounds,String sym,double plane,String side,double L,double size)throws IOException {String boxName=prefix+"_Nearfield_Box";SimpleBlockPart box=null;for(GeometryPart part:s.get(SimulationPartManager.class).getParts())if(part.getPresentationName().equals(boxName)){if(!(part instanceof SimpleBlockPart))throw new IOException("同名近翼域不是块部件");box=(SimpleBlockPart)part;}if(box==null){box=s.get(MeshPartFactory.class).createNewBlockPart(s.get(SimulationPartManager.class));box.setPresentationName(boxName);}double[] lo=values(bounds,"min_m"),hi=values(bounds,"max_m");for(int k=0;k<3;k++){lo[k]-=.25*L;hi[k]+=.25*L;}if(sym!=null&&!sym.isBlank()){int axis=GeometryBounds.axis(sym);if(side.equals("positive"))lo[axis]=plane;else hi[axis]=plane;}Units m=(Units)s.getUnitsManager().getObject("m");box.getCorner1().setCoordinate(m,m,m,new DoubleVector(lo));box.getCorner2().setCoordinate(m,m,m,new DoubleVector(hi));box.rebuildSimpleShapePart();VolumeCustomMeshControl c=null;for(CustomMeshControl old:mesh.getCustomMeshControls().getObjects())if(old.getPresentationName().equals(prefix+"_Nearfield")){if(!(old instanceof VolumeCustomMeshControl))throw new IOException("同名近翼控制不是体控制");c=(VolumeCustomMeshControl)old;}if(c==null){c=mesh.getCustomMeshControls().createVolumeControl();c.setPresentationName(prefix+"_Nearfield");}select(s,c,List.of(box));c.setEnableControl(true);if(mesh.getMeshersCollection().stream().anyMatch(x->x instanceof star.dualmesher.DualAutoMesher))c.getCustomConditions().get(star.dualmesher.VolumeControlDualMesherSizeOption.class).setVolumeControlDualMesherSizeOption(true);else c.getCustomConditions().get(star.trimmer.VolumeControlTrimmerSizeOption.class).setVolumeSourceTrimmerSizeOption(true);c.getCustomValues().get(VolumeControlSize.class).setAbsoluteSize(size,m);flush(s);}
}
