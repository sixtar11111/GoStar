package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import star.base.neo.ClientServerObject;
import star.common.*;
import star.meshing.*;
import star.resurfacer.SurfaceGrowthRate;
import star.resurfacer.ResurfacerAutoMesher;

/** Explicit, individually read-back mesh controls. Recommendations never replace supplied values. */
final class MeshingProfiles {
    private static final List<String> DEFAULT_KEYS = List.of("farfield_target_m", "global_min_m", "maximum_cell_m", "surface_growth", "volume_growth", "curvature_points");
    private static final List<String> PRECISION_KEYS = List.of("chord_tolerance_m", "surface_tolerance_m", "angle_deg");
    static boolean supports(String type) { return "configure_mesh_defaults".equals(type) || "configure_boolean".equals(type); }

    private static double number(Map<?,?> a, String key) throws IOException {
        Object value=a.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue())) throw new IOException(key+"必须是有限数值");
        return ((Number)value).doubleValue();
    }
    private static double positive(Map<?,?> a, String key) throws IOException {
        double value=number(a,key); if(value<=0)throw new IOException(key+"须大于0"); return value;
    }
    private static boolean dual(AutoMeshOperation op) {
        for (MesherBase mesher:op.getMeshersCollection()) if (mesher instanceof star.dualmesher.DualAutoMesher) return true;
        return false;
    }
    private static void supportedControl(AutoMeshOperation op,Class<? extends ClientServerObject> type,String key) throws IOException {
        if(op.getDefaultValues().has(type)==null)throw new IOException("当前网格器不支持"+key+"；未修改任何未指定参数");
    }
    static void validate(Map<?,?> a, ClientServerObject o) throws Exception {
        String type=String.valueOf(a.get("type"));
        if("configure_mesh_defaults".equals(type)) {
            if(!(o instanceof AutoMeshOperation))throw new IOException("网格默认配置需要AutoMeshOperation");
            AutoMeshOperation op=(AutoMeshOperation)o; boolean any=false;
            for(String key:DEFAULT_KEYS)if(a.containsKey(key)) { any=true; positive(a,key); }
            if(!any)throw new IOException("至少指定一个网格默认配置参数");
            if(a.containsKey("farfield_target_m"))supportedControl(op,PartsTargetSurfaceSize.class,"远场表面目标尺寸");
            if(a.containsKey("global_min_m"))supportedControl(op,PartsMinimumSurfaceSize.class,"全局最小表面尺寸");
            if(a.containsKey("maximum_cell_m"))supportedControl(op,MaximumCellSize.class,"最大体网格尺寸");
            if(a.containsKey("surface_growth")){supportedControl(op,SurfaceGrowthRate.class,"表面增长率");if(number(a,"surface_growth")<1)throw new IOException("表面增长率须不小于1");}
            if(a.containsKey("volume_growth")){if(!dual(op))throw new IOException("volume_growth当前仅支持多面体网格器；不会向其他网格器写入无效参数");supportedControl(op,PartsTetPolyGrowthRate.class,"体网格增长率");if(number(a,"volume_growth")<1)throw new IOException("体网格增长率须不小于1");}
            if(a.containsKey("curvature_points")){if(op.getDefaultValues().has(SurfaceCurvature.class)==null&&Boolean.FALSE.equals(curvatureRefinementEnabled(op)))throw new IOException("曲率细化当前关闭，曲率点数控制不可用；未改变用户开关或任何未指定参数");supportedControl(op,SurfaceCurvature.class,"曲率每圆点数");double v=number(a,"curvature_points");if(v<3||v!=Math.rint(v))throw new IOException("曲率每圆点数须是至少3的整数");}
            if(a.containsKey("global_min_m")||a.containsKey("farfield_target_m")) {
                // Compare the proposed active pair, even when the other value is not being changed.
                // Inactive absolute slots must not substitute for the current percentage setting.
                supportedControl(op,PartsTargetSurfaceSize.class,"当前表面目标尺寸");
                supportedControl(op,PartsMinimumSurfaceSize.class,"当前最小表面尺寸");
                double base=op.getDefaultValues().get(BaseSize.class).getInternalValue();
                double target=a.containsKey("farfield_target_m")?positive(a,"farfield_target_m"):effectiveSize(op.getDefaultValues().get(PartsTargetSurfaceSize.class),base,"当前表面目标尺寸");
                double min=a.containsKey("global_min_m")?positive(a,"global_min_m"):effectiveSize(op.getDefaultValues().get(PartsMinimumSurfaceSize.class),base,"当前最小表面尺寸");
                if(min>target)throw new IOException("修改后的最小表面尺寸 "+min+" m 大于目标尺寸 "+target+" m；未指定项按当前有效绝对/相对尺寸核验，不会自动覆盖");
            }
        } else if("configure_boolean".equals(type)) {
            if(!(o instanceof SubtractPartsOperation))throw new IOException("布尔配置需要SubtractPartsOperation");
            if(!(a.get("cad_boolean") instanceof Boolean))throw new IOException("必须明确指定cad_boolean布尔值");
            boolean cad=Boolean.TRUE.equals(a.get("cad_boolean"));
            if(cad) {
                for(String key:PRECISION_KEYS)positive(a,key);
                if(number(a,"angle_deg")>=180)throw new IOException("离散角度须大于0且小于180度");
                SubtractPartsOperation op=(SubtractPartsOperation)o;
                for(GeometryObject input:op.getInputGeometryObjects().getObjects())if(!(input instanceof GeometryPart)||!cadCapable((GeometryPart)input))throw new IOException("当前输入包含无CAD实体的部件，不能启用CAD布尔；请检查真实输入类型");
                if(!cadCapable(op.getTargetPart()))throw new IOException("当前目标部件不具备CAD布尔实体");
            } else for(String key:PRECISION_KEYS)if(a.containsKey(key))throw new IOException("cad_boolean=false时不能同时提供CAD离散精度；未静默丢弃参数");
        } else throw new IOException("不支持的网格配置操作");
    }

    private static void close(double actual,double expected,String key)throws IOException {
        if(!Double.isFinite(actual)||Math.abs(actual-expected)>Math.max(1e-14,Math.abs(expected)*1e-9))throw new IOException(key+"读回不一致：expected="+expected+", actual="+actual);
    }
    private static void absolute(Simulation s,PartsRelativeOrAbsoluteSize value,double size,Units m,String key)throws IOException {
        value.setAbsoluteSize(size,m);
        s.getServer().getClientNotifyHandler().processCommandQueue(true);
        if(value.getRelativeOrAbsoluteOption().getSelected()!=RelativeOrAbsoluteOption.ABSOLUTE)throw new IOException(key+"未使用绝对尺寸");
        close(value.getAbsoluteSizeValue().getInternalValue(),size,key);
    }
    static Map<String,Object> execute(Simulation s, Map<?,?> a, ClientServerObject o) throws Exception {
        validate(a,o); Map<String,Object> out=new LinkedHashMap<>();out.put("before",describe(o));
        String type=String.valueOf(a.get("type"));
        if(type.equals("configure_mesh_defaults")) {
            AutoMeshOperation op=(AutoMeshOperation)o;AutoMeshDefaultValuesManager v=op.getDefaultValues();Units m=(Units)s.getUnitsManager().getObject("m");
            double base=v.get(BaseSize.class).getInternalValue();
            if(a.containsKey("farfield_target_m"))absolute(s,v.get(PartsTargetSurfaceSize.class),positive(a,"farfield_target_m"),m,"远场表面目标尺寸");
            if(a.containsKey("global_min_m"))absolute(s,v.get(PartsMinimumSurfaceSize.class),positive(a,"global_min_m"),m,"全局最小表面尺寸");
            if(a.containsKey("maximum_cell_m"))absolute(s,v.get(MaximumCellSize.class),positive(a,"maximum_cell_m"),m,"最大体网格尺寸");
            if(a.containsKey("surface_growth")){SurfaceGrowthRate g=v.get(SurfaceGrowthRate.class);g.setGrowthRateOption(SurfaceGrowthRate.GrowthRateOption.USER_SPECIFIED);s.getServer().getClientNotifyHandler().processCommandQueue(true);g.setGrowthRate(number(a,"surface_growth"));s.getServer().getClientNotifyHandler().processCommandQueue(true);if(g.getGrowthRateOption()!=SurfaceGrowthRate.GrowthRateOption.USER_SPECIFIED)throw new IOException("表面增长率未处于USER_SPECIFIED活动模式");close(g.getGrowthRate(),number(a,"surface_growth"),"表面增长率");}
            if(a.containsKey("volume_growth")){for(MesherBase mesher:op.getMeshersCollection())if(mesher instanceof star.dualmesher.DualAutoMesher){star.dualmesher.DualAutoMesher dm=(star.dualmesher.DualAutoMesher)mesher;dm.setEnableGrowthRate(true);s.getServer().getClientNotifyHandler().processCommandQueue(true);if(!dm.getEnableGrowthRate())throw new IOException("多面体体增长率控制未启用");}v.get(PartsTetPolyGrowthRate.class).setGrowthRate(number(a,"volume_growth"));s.getServer().getClientNotifyHandler().processCommandQueue(true);close(v.get(PartsTetPolyGrowthRate.class).getGrowthRate(),number(a,"volume_growth"),"体增长率");}
            if(a.containsKey("curvature_points")){v.get(SurfaceCurvature.class).setNumPointsAroundCircle(number(a,"curvature_points"));s.getServer().getClientNotifyHandler().processCommandQueue(true);close(v.get(SurfaceCurvature.class).getNumPointsAroundCircle(),number(a,"curvature_points"),"曲率每圆点数");}
            s.getServer().getClientNotifyHandler().processCommandQueue(true);
            close(v.get(BaseSize.class).getInternalValue(),base,"原基础尺寸");
            out.put("specified_keys",DEFAULT_KEYS.stream().filter(a::containsKey).toList());out.put("actual",describe(op));
            List<String> notices=new ArrayList<>();
            if(a.containsKey("curvature_points")) {
                Boolean enabled=curvatureRefinementEnabled(op);
                out.put("curvature_refinement_active_readback",Boolean.TRUE.equals(enabled));
                if(!Boolean.TRUE.equals(enabled))notices.add(enabled==null?
                        "曲率每圆点数已保存，但未找到可读的Surface Remesher曲率细化开关，不能确认该数值是活动控制；未擅自切换网格器或开关。":
                        "曲率每圆点数已保存，但Surface Remesher曲率细化当前关闭，该点数不会启用曲率细化；遵守原关闭设置，未隐式覆盖。");
            }
            out.put("notices",notices);
            out.put("readback_scope","Specified values and directly readable activity flags only; no remeshing or engineering effect has been validated.");
            out.put("validation","指定默认网格数值已逐项读回；基础尺寸未改，未指定项未写入。数值已保存不等于相关细化已启用或网格已生效；活动状态限制见notices，尚未执行网格或验证工程精度");
        } else {
            SubtractPartsOperation op=(SubtractPartsOperation)o;boolean cad=Boolean.TRUE.equals(a.get("cad_boolean"));op.setPerformCADBoolean(cad);
            s.getServer().getClientNotifyHandler().processCommandQueue(true);
            if(op.getPerformCADBoolean()!=cad)throw new IOException("CAD布尔模式读回不一致");
            if(cad) {
                CadTessellationOption opt=op.getBooleanOperationValuesManager().get(CadTessellationOption.class);TessellationDensityOption density=opt.getTessellationDensityOption();TessellationParameters p=density.getDetailedParameters();
                // Detached parameter values obtained through the GUI need their journal owner.
                // Batch macros can mask this missing simulation association.
                p.setSimulation(s);
                p.setCurveChordTolerance(positive(a,"chord_tolerance_m"));p.setIsCurveChordTolerancePercentage(false);p.setCurveChordAngle(positive(a,"angle_deg"));
                p.setSurfacePlaneTolerance(positive(a,"surface_tolerance_m"));p.setIsSurfacePlaneTolerancePercentage(false);p.setSurfacePlaneAngle(positive(a,"angle_deg"));
                density.setSelected(TessellationDensityOption.Type.USER_DEFINED);density.setDetailedParameters(p);
                s.getServer().getClientNotifyHandler().processCommandQueue(true);TessellationParameters read=density.getDetailedParameters();
                if(density.getSelected()!=TessellationDensityOption.USER_DEFINED||read.isCurveChordTolerancePercentage()||read.isSurfacePlaneTolerancePercentage())throw new IOException("CAD离散未使用绝对USER_DEFINED精度");
                close(read.getCurveChordTolerance(),number(a,"chord_tolerance_m"),"曲线弦公差");close(read.getSurfacePlaneTolerance(),number(a,"surface_tolerance_m"),"曲面平面公差");close(read.getCurveChordAngle(),number(a,"angle_deg"),"曲线离散角度");close(read.getSurfacePlaneAngle(),number(a,"angle_deg"),"曲面离散角度");
            }
            out.put("actual",describe(op));out.put("validation","布尔模式和明确指定的精度已读回；原CAD未修改，尚未执行布尔或重划网格，不能据此宣称共面薄片已修复");
        }
        return out;
    }

    private static double effectiveSize(PartsRelativeOrAbsoluteSize p,double base,String label)throws IOException {
        int selected=p.getRelativeOrAbsoluteOption().getSelected();
        double value;
        if(selected==RelativeOrAbsoluteOption.ABSOLUTE)value=p.getAbsoluteSizeValue().getInternalValue();
        else if(selected==RelativeOrAbsoluteOption.RELATIVE)value=base*p.getRelativeSizeScalar().getInternalValue()/100.0;
        else throw new IOException(label+"当前尺寸模式无法识别，未把非活动槽值当实际值");
        if(!Double.isFinite(base)||base<=0||!Double.isFinite(value)||value<=0)throw new IOException(label+"当前有效SI尺寸不是有限正值");
        return value;
    }
    private static Boolean curvatureRefinementEnabled(AutoMeshOperation op) {
        Boolean enabled=null;
        for(MesherBase mesher:op.getMeshersCollection())if(mesher instanceof ResurfacerAutoMesher)
            enabled=enabled==null?((ResurfacerAutoMesher)mesher).getDoCurvatureRefinement():enabled&&((ResurfacerAutoMesher)mesher).getDoCurvatureRefinement();
        return enabled;
    }
    private static Map<String,Object> size(PartsRelativeOrAbsoluteSize p,double base)throws IOException {
        return Map.of("mode",p.getRelativeOrAbsoluteOption().getSelected()==RelativeOrAbsoluteOption.ABSOLUTE?"absolute":"relative_percent","absolute_m",p.getAbsoluteSizeValue().getInternalValue(),"relative_percent",p.getRelativeSizeScalar().getInternalValue(),"effective_m",effectiveSize(p,base,"网格尺寸"));
    }
    static Map<String,Object> describe(ClientServerObject o) {
        Map<String,Object> result=new LinkedHashMap<>();
        if(o instanceof AutoMeshOperation) {
            AutoMeshOperation op=(AutoMeshOperation)o;AutoMeshDefaultValuesManager v=op.getDefaultValues();Map<String,Object> d=new LinkedHashMap<>();
            double base=Double.NaN;
            try {base=v.get(BaseSize.class).getInternalValue();d.put("base_size_m",base);}catch(Exception e){d.put("base_size_read_error",e.getMessage());}
            try {if(v.has(PartsTargetSurfaceSize.class)!=null)d.put("farfield_target",size(v.get(PartsTargetSurfaceSize.class),base));}catch(Exception e){d.put("farfield_target_read_error",e.getMessage());}
            try {if(v.has(PartsMinimumSurfaceSize.class)!=null)d.put("global_min",size(v.get(PartsMinimumSurfaceSize.class),base));}catch(Exception e){d.put("global_min_read_error",e.getMessage());}
            try {if(v.has(MaximumCellSize.class)!=null)d.put("maximum_cell",size(v.get(MaximumCellSize.class),base));}catch(Exception e){d.put("maximum_cell_read_error",e.getMessage());}
            try {if(v.has(SurfaceGrowthRate.class)!=null){d.put("surface_growth",v.get(SurfaceGrowthRate.class).getGrowthRate());d.put("surface_growth_mode",v.get(SurfaceGrowthRate.class).getGrowthRateOption().name());}}catch(Exception e){d.put("surface_growth_read_error",e.getMessage());}
            d.put("volume_growth_supported",dual(op));
            for(MesherBase mesher:op.getMeshersCollection())if(mesher instanceof star.dualmesher.DualAutoMesher)d.put("volume_growth_enabled",((star.dualmesher.DualAutoMesher)mesher).getEnableGrowthRate());
            try {if(dual(op)&&v.has(PartsTetPolyGrowthRate.class)!=null)d.put("volume_growth",v.get(PartsTetPolyGrowthRate.class).getGrowthRate());}catch(Exception e){d.put("volume_growth_read_error",e.getMessage());}
            try {if(v.has(SurfaceCurvature.class)!=null)d.put("curvature_points",v.get(SurfaceCurvature.class).getNumPointsAroundCircle());}catch(Exception e){d.put("curvature_read_error",e.getMessage());}
            try {Boolean enabled=curvatureRefinementEnabled(op);if(enabled!=null)d.put("curvature_refinement_enabled",enabled);else d.put("curvature_refinement_status","not_exposed_by_surface_remesher");}catch(Exception e){d.put("curvature_refinement_read_error",e.getMessage());}
            result.put("mesh_defaults",d);
        } else if(o instanceof SubtractPartsOperation) {
            SubtractPartsOperation op=(SubtractPartsOperation)o;result.put("cad_boolean",op.getPerformCADBoolean());
            if(op.getPerformCADBoolean())try {
                TessellationDensityOption d=op.getBooleanOperationValuesManager().get(CadTessellationOption.class).getTessellationDensityOption();TessellationParameters p=d.getDetailedParameters();Map<String,Object> t=new LinkedHashMap<>();
                t.put("density_mode",d.getSelected());t.put("user_defined",d.getSelected()==TessellationDensityOption.USER_DEFINED);t.put("chord_tolerance_m",p.getCurveChordTolerance());t.put("chord_is_percentage",p.isCurveChordTolerancePercentage());t.put("curve_angle_deg",p.getCurveChordAngle());t.put("surface_tolerance_m",p.getSurfacePlaneTolerance());t.put("surface_is_percentage",p.isSurfacePlaneTolerancePercentage());t.put("surface_angle_deg",p.getSurfacePlaneAngle());result.put("cad_tessellation",t);
            } catch(Exception e){result.put("cad_tessellation_read_error",String.valueOf(e.getMessage()));}
        }
        return result;
    }

    private static boolean cadCapable(GeometryPart part) {return part instanceof SimpleBlockPart||part instanceof CadPart||part instanceof star.cadmodeler.SolidModelPart;}
    static Map<String,Object> configureNewBoolean(Simulation s, SubtractPartsOperation op, List<? extends GeometryPart> inputs, Map<?,?> a) throws Exception {
        if(a.containsKey("cad_boolean")&&!(a.get("cad_boolean") instanceof Boolean))throw new IOException("cad_boolean必须是布尔值");
        boolean capable=!inputs.isEmpty()&&cadCapable(op.getTargetPart());for(GeometryPart p:inputs)capable&=cadCapable(p);
        boolean explicit=a.containsKey("cad_boolean"),cad=explicit?Boolean.TRUE.equals(a.get("cad_boolean")):capable;
        Map<String,Object> c=new LinkedHashMap<>();c.put("type","configure_boolean");c.put("cad_boolean",cad);
        for(String key:PRECISION_KEYS)if(a.containsKey(key))c.put(key,a.get(key));
        String referenceSource="not_required";double scale=Double.NaN;
        if(cad) {
            if(a.containsKey("reference_length_m")){scale=positive(a,"reference_length_m");referenceSource="explicit_reference_length_m";}
            else if(!a.containsKey("chord_tolerance_m")||!a.containsKey("surface_tolerance_m")) {
                double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};int count=0;
                for(GeometryPart p:inputs)if(!p.equals(op.getTargetPart())) {
                    if(!(p instanceof MeshPart))throw new IOException("非目标输入无法测量；请指定reference_length_m或明确离散公差");
                    Map<String,Object> bounds=GeometryBounds.measure((MeshPart)p);List<?> lower=(List<?>)bounds.get("min_m"),upper=(List<?>)bounds.get("max_m");
                    for(int axis=0;axis<3;axis++){lo[axis]=Math.min(lo[axis],((Number)lower.get(axis)).doubleValue());hi[axis]=Math.max(hi[axis],((Number)upper.get(axis)).doubleValue());}count++;
                }
                scale=count==0?Double.NaN:Math.max(hi[0]-lo[0],Math.max(hi[1]-lo[1],hi[2]-lo[2]));referenceSource="measured_non_target_bbox_largest_span";
                if(!Double.isFinite(scale)||scale<=0)throw new IOException("无法从非目标CAD测量尺度；请指定reference_length_m或明确离散公差");
            }
            if(!c.containsKey("chord_tolerance_m"))c.put("chord_tolerance_m",scale*1e-5);
            if(!c.containsKey("surface_tolerance_m"))c.put("surface_tolerance_m",scale*1e-5);
            if(!c.containsKey("angle_deg"))c.put("angle_deg",5.0);
        }
        Map<String,Object> out=execute(s,c,op);out.put("cad_boolean_source",explicit?"explicit_user_action":"CAD_capability_based_default");out.put("cad_inputs_supported",capable);
        if(Double.isFinite(scale))out.put("reference_length_m",scale);out.put("tolerance_reference_source",referenceSource);
        out.put("recommendation",cad?"输入及目标具备CAD实体，采用CAD布尔及绝对离散精度；用户明确值优先，尚需执行并验证共面相交质量":(explicit?"遵守明确离散布尔选择；未静默提供CAD精度":"输入并非全部具备直接CAD实体，保持离散布尔；未套用CAD推荐，需检查共面/相交输出"));
        return out;
    }
}
