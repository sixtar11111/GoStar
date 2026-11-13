package local.starccm.assistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import star.base.neo.NamedObject;
import star.base.neo.NeoClient;
import star.base.report.MaxReport;
import star.base.report.MinReport;
import star.common.Boundary;
import star.common.FieldFunction;
import star.common.FvRepresentation;
import star.common.Region;
import star.common.Simulation;
import star.common.SymmetryBoundary;
import star.common.WallBoundary;
import star.meshing.AutoMeshOperation;
import star.meshing.MeshOperation;
import star.meshing.MeshOperationManager;

/** Measures existing volume-mesh fields using short-lived reports, never regenerating or solving.
 * Returned ranges are an inventory, not a mesh-acceptance certificate or CAD vertex audit.
 */
public final class MeshQualitySnapshot {
    private MeshQualitySnapshot() { }

    public static Map<String,Object> inspect(Simulation sim,String planeAxis,Double planeM) {
        if(sim==null)throw new IllegalArgumentException("simulation is required");
        String axis=validatePlane(planeAxis,planeM);
        Map<String,Object> out=new LinkedHashMap<>();
        List<String> warnings=new ArrayList<>(),geometryIssues=new ArrayList<>(),errors=new ArrayList<>();
        out.put("scope","Existing finite-volume mesh fields only; no remeshing or solving. Boundary Position ranges are face-centre bounds, not CAD/mesh vertex proof. Mesh counts and positive volumes alone do not establish mesh quality or solved y+.");
        out.put("warnings",warnings);out.put("mesh_geometry_issues",geometryIssues);out.put("read_errors",errors);
        if(axis!=null)out.put("expected_plane",Map.of("axis",axis,"position_m",planeM,"tolerance_m",tolerance(planeM),"measurement","face centres, not vertices"));
        NeoClient connection=sim.getServer().getServerProxy();
        boolean previous=connection.isMacroPlaying();
        connection.setMacroPlaying(true);
        try {
            FvRepresentation mesh=null;
            for(FvRepresentation candidate:sim.getRepresentationManager().getObjectsOf(FvRepresentation.class)) {
                try {
                    if(candidate.getCellCount()>0) {
                        if(mesh==null)mesh=candidate;
                        if(candidate.getPresentationName().equals("Volume Mesh")){mesh=candidate;break;}
                    }
                } catch(RuntimeException e) {errors.add("读取网格表示“"+candidate.getPresentationName()+"”单元数失败："+e);}
            }
            if(mesh==null) {
                out.put("status","unavailable");
                warnings.add("没有含体单元的有限体积网格表示；未创建报告，也不能认定网格已完成。");
                return out;
            }
            out.put("representation",mesh.getPresentationName());
            out.put("representation_id",mesh.getObjectKeyAsString());
            out.put("cell_count",mesh.getCellCount());
            List<Region> regions=new ArrayList<>();
            for(Region r:sim.getRegionManager().getRegions())if(mesh.describesRegion(r))regions.add(r);
            if(regions.isEmpty()) {
                out.put("status","unavailable");
                warnings.add("网格表示存在单元，但没有可测量的实际区域；未用空报告冒充读数。");
                return out;
            }
            List<String> dirty=new ArrayList<>();
            if(sim.has(MeshOperationManager.class)!=null)for(MeshOperation operation:sim.get(MeshOperationManager.class).getObjects())
                if(operation instanceof AutoMeshOperation && operation.isDirty())dirty.add(operation.getPresentationName());
            out.put("dirty_mesh_operations",dirty);
            if(!dirty.isEmpty())warnings.add("有网格操作尚未更新："+String.join("、",dirty)+"；本次测量仅描述现有体网格，不能代表待更新几何或设置。");
            Map<String,Object> quality=new LinkedHashMap<>();
            for(String fieldName:List.of("Volume","CellQuality","FaceValidity","VolumeChange","SkewnessAngle")) {
                Map<String,Object> range=fieldRange(sim,mesh,regions,fieldName,errors);
                quality.put(fieldName,range);
                if(!available(range))warnings.add("未取得有效网格指标“"+fieldName+"”范围；没有用零值代替。");
            }
            out.put("quality_ranges",quality);
            Map<?,?> volume=(Map<?,?>)quality.get("Volume");
            if(available(volume)&&value(volume,"min")<=0) {
                String problem="现有网格最小体积非正："+value(volume,"min")+" m^3";
                geometryIssues.add(problem);warnings.add(problem+"；不能用于正常流动求解。");
            }
            attention(quality,"CellQuality","min",1e-3,false,"最小 CellQuality 较低",warnings);
            attention(quality,"FaceValidity","min",.95,false,"最小 FaceValidity 较低",warnings);
            attention(quality,"VolumeChange","min",1e-3,false,"最小 VolumeChange 较低",warnings);
            attention(quality,"SkewnessAngle","max",90,true,"最大 SkewnessAngle 超过90度",warnings);
            out.put("screening_policy","CellQuality<0.001, FaceValidity<0.95, VolumeChange<0.001, and SkewnessAngle>90 deg are attention flags here, not universal acceptance thresholds. No automatic tolerance relaxation or geometry repair is performed.");

            List<Map<String,Object>> walls=new ArrayList<>(),symmetry=new ArrayList<>();
            FieldFunction position=null;
            try {position=sim.getFieldFunctionManager().getFunction("Position").getFunctionInCoordinateSystem(sim.getCoordinateSystemManager().getLabCoordinateSystem());}
            catch(RuntimeException e){errors.add("读取Lab坐标系Position场函数失败："+e);}
            int emptySymmetry=0;
            for(Region region:regions)for(Boundary boundary:region.getBoundaryManager().getBoundaries()) {
                boolean nonempty=!boundary.getPartSurfaceGroup().getObjects().isEmpty();
                if(boundary.getBoundaryType() instanceof WallBoundary && nonempty) {
                    Map<String,Object> wall=identity(boundary);
                    Map<String,Object> range=fieldRange(sim,mesh,List.of(boundary),"FirstPrismLayerHeight",errors);
                    wall.put("first_prism_layer_height_m",range);
                    wall.put("interpretation","Actual STAR FirstPrismLayerHeight mesh field on this boundary. It is not solved wall y+, and does not by itself prove the requested first-layer setting is met everywhere. Zero may indicate absent/truncated prism layers or unavailable local prism data; inspect the mesh.");
                    if(!available(range))warnings.add("壁面“"+boundary.getQualifiedName()+"”的首层网格高度未取得有效范围。");
                    else if(value(range,"min")<=0)warnings.add("壁面“"+boundary.getQualifiedName()+"”的 FirstPrismLayerHeight 含零或负值；需核验局部棱柱层，不能据此声称整面近壁网格满足要求。");
                    walls.add(wall);
                }
                if(boundary.getBoundaryType() instanceof SymmetryBoundary) {
                    if(!nonempty){emptySymmetry++;continue;}
                    Map<String,Object> face=identity(boundary);
                    face.put("coordinate_system","Lab");face.put("position_measurement","finite-volume boundary face-centre extrema; not vertices");
                    Map<String,Object> coordinates=new LinkedHashMap<>();
                    for(int i=0;i<3;i++) {
                        String component=List.of("x","y","z").get(i);
                        Map<String,Object> range=position==null?unavailable("Position function is unavailable"):
                                measureRange(sim,mesh,List.of(boundary),position.getComponentFunction(i),errors);
                        coordinates.put(component,range);
                    }
                    face.put("position_m",coordinates);
                    if(axis!=null) {
                        Map<?,?> range=(Map<?,?>)coordinates.get(axis);
                        if(available(range)) {
                            double deviation=Math.max(Math.abs(value(range,"min")-planeM),Math.abs(value(range,"max")-planeM));
                            face.put("expected_plane_axis",axis);face.put("expected_plane_m",planeM);
                            face.put("maximum_face_centre_deviation_m",deviation);
                            face.put("plane_tolerance_m",tolerance(planeM));
                            face.put("face_centres_within_tolerance",deviation<=tolerance(planeM));
                            if(deviation>tolerance(planeM)) {
                                String issue="对称边界“"+boundary.getQualifiedName()+"”面中心偏离目标 "+axis+"="+planeM+" m，最大偏差 "+deviation+" m（容差 "+tolerance(planeM)+" m）；需检查边界选面、Boolean输出及网格投影，不能仅凭此项断言源CAD损坏。";
                                geometryIssues.add(issue);warnings.add(issue);
                            }
                        } else warnings.add("对称边界“"+boundary.getQualifiedName()+"”坐标未能有效读取，未把缺失值当作平面符合。");
                    }
                    symmetry.add(face);
                }
            }
            out.put("wall_prism_height_ranges",walls);out.put("symmetry_boundaries",symmetry);
            out.put("empty_symmetry_boundary_count",emptySymmetry);
            if(emptySymmetry>0)warnings.add("另有 "+emptySymmetry+" 个空对称边界，未计入已配置或已测量对称面。");
            if(axis!=null&&symmetry.isEmpty())warnings.add("没有非空Symmetry边界可核对给定平面；未把空选择当作符合。");
            out.put("status",!errors.isEmpty()?"partial":warnings.isEmpty()?"inspected":"inspected_with_warnings");
            return out;
        } finally {
            try {sim.getServer().getClientNotifyHandler().processCommandQueue(true);}
            finally {connection.setMacroPlaying(previous);}
        }
    }

    static String validatePlane(String axis,Double plane) {
        if(axis==null&&plane==null)return null;
        if(axis==null||plane==null||!Double.isFinite(plane))throw new IllegalArgumentException("Expected plane requires both x/y/z axis and a finite SI position");
        String clean=axis.trim().toLowerCase(Locale.ROOT);
        if(!List.of("x","y","z").contains(clean))throw new IllegalArgumentException("Plane axis must be x, y, or z");
        return clean;
    }
    static double tolerance(double plane) {return Math.max(1e-9,Math.abs(plane)*1e-10);}
    private static Map<String,Object> identity(Boundary b) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("name",b.getPresentationName());result.put("id",b.getObjectKeyAsString());
        result.put("region",b.getRegion().getPresentationName());result.put("assigned_surface_count",b.getPartSurfaceGroup().getObjects().size());return result;
    }
    private static boolean available(Map<?,?> range) {return "available".equals(range.get("status"));}
    private static double value(Map<?,?> m,String key) {return ((Number)m.get(key)).doubleValue();}
    private static Map<String,Object> unavailable(String why) {return new LinkedHashMap<>(Map.of("status","unavailable","reason",why));}
    private static Map<String,Object> fieldRange(Simulation sim,FvRepresentation mesh,Collection<? extends NamedObject> parts,String field,List<String> errors) {
        try {
            if(!sim.getFieldFunctionManager().hasFunction(field))return unavailable("Field function is not available: "+field);
            return measureRange(sim,mesh,parts,sim.getFieldFunctionManager().getFunction(field),errors);
        } catch(RuntimeException e) {errors.add("读取网格场函数“"+field+"”失败："+e);return unavailable(e.toString());}
    }
    private static Map<String,Object> measureRange(Simulation sim,FvRepresentation mesh,
            Collection<? extends NamedObject> parts,FieldFunction field,List<String> errors) {
        MinReport min=null;MaxReport max=null;
        try {
            min=sim.getReportManager().createReport(MinReport.class);
            max=sim.getReportManager().createReport(MaxReport.class);
            min.getParts().setObjects(parts);max.getParts().setObjects(parts);
            min.setRepresentation(mesh);max.setRepresentation(mesh);
            min.setSmooth(false);max.setSmooth(false);
            min.setFieldFunction(field);max.setFieldFunction(field);
            sim.getServer().getClientNotifyHandler().processCommandQueue(true);
            double a=min.getReportMonitorValue(),b=max.getReportMonitorValue();
            if(!Double.isFinite(a)||!Double.isFinite(b)||a>b||Math.abs(a)>1e100||Math.abs(b)>1e100)
                throw new IllegalStateException("Non-finite, sentinel, or unordered report range; no valid mesh value returned");
            return new LinkedHashMap<>(Map.of("status","available","min",a,"max",b));
        } catch(RuntimeException e) {errors.add("测量网格场“"+field.getFunctionName()+"”范围失败："+e);return unavailable(e.toString());}
        finally {
            if(min!=null)try {sim.getReportManager().removeObjects(List.of(min));}catch(RuntimeException e){errors.add("清理临时MinReport失败："+e);}
            if(max!=null)try {sim.getReportManager().removeObjects(List.of(max));}catch(RuntimeException e){errors.add("清理临时MaxReport失败："+e);}
        }
    }
    private static void attention(Map<String,Object> quality,String field,String end,double threshold,boolean upper,String label,List<String> warnings) {
        Map<?,?> range=(Map<?,?>)quality.get(field);
        if(available(range)&&(upper?value(range,end)>threshold:value(range,end)<threshold))
            warnings.add(label+"："+value(range,end)+"（经验筛查线 "+threshold+"；这是关注项，不是通用合格判据）。");
    }
}
