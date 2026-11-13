package local.starccm.assistant;

import java.io.IOException;
import java.util.*;
import star.common.*;
import star.meshing.MeshPart;

/** Necessary read-only consistency checks for flow regions. Matching bounds do
 * not prove identical interior geometry, mesh quality or numerical convergence.
 * Current upstream geometry is checked by the caller before these FV reads. */
final class FlowMeshGate {
    private FlowMeshGate(){}
    static Set<String> flowRegionIds(Map<String,Object> readiness){
        Set<String> ids=new LinkedHashSet<>();Object raw=readiness.get("regions");if(raw instanceof List)for(Object value:(List<?>)raw)if(value instanceof Map){Map<?,?> row=(Map<?,?>)value;if(Boolean.TRUE.equals(row.get("flow_model_enabled"))&&row.get("id") instanceof String)ids.add(row.get("id").toString());}return ids;
    }
    /** Cheap evidence only: no CAD exports, initialization, mesh execution or saved changes.
     * A positive count does not establish that this volume matches current geometry. */
    static Map<String,Object> observe(Simulation sim,Set<String> regionIds){
        Map<String,Object> out=new LinkedHashMap<>();out.put("policy","Read-only observed FV existence/count only; positive cells do not prove current geometry, mesh quality, y+ or convergence. Do not remesh merely because an operation is absent.");
        List<Map<String,Object>> rows=new ArrayList<>();List<String> errors=new ArrayList<>();out.put("regions",rows);FvRepresentation mesh=null;
        try{Representation current=sim.getRepresentationManager().getDefaultFvRepresentation();if(current!=null)current=current.resolve();if(current instanceof FvRepresentation)mesh=(FvRepresentation)current;if(mesh==null)out.put("representation_status","no_current_fv_representation");else{out.put("representation_id",mesh.getObjectKeyAsString());out.put("representation_name",mesh.getPresentationName());out.put("representation_status","resolved_current_fv");try{out.put("under_construction",mesh.getUnderConstruction());}catch(RuntimeException failure){errors.add("读取体网格构建状态失败："+failure);}}}
        catch(RuntimeException failure){out.put("representation_status","unknown_read_error");errors.add("读取当前有限体积表示失败："+failure);}
        Set<String> found=new HashSet<>();
        try{for(Region region:sim.getRegionManager().getRegions()){
            String id=region.getObjectKeyAsString();if(!regionIds.isEmpty()&&!regionIds.contains(id))continue;found.add(id);Map<String,Object> row=new LinkedHashMap<>();rows.add(row);row.put("region_id",id);row.put("region_name",region.getPresentationName());
            if(mesh==null){row.put("observation_status","unknown_no_current_fv_representation");continue;}
            try{boolean described=mesh.describesRegion(region);row.put("described_by_current_fv",described);if(!described){row.put("observation_status","not_described_by_current_fv");continue;}FvRegion cells=mesh.getFvRegionManager().getFvRegion(region);if(cells==null)throw new IllegalStateException("区域已关联当前FV但未返回FvRegion");row.put("cell_count",cells.getCellCount());row.put("empty",cells.isEmpty());row.put("observation_status","observed");}
            catch(RuntimeException failure){row.put("observation_status","unknown_read_error");row.put("read_error",failure.toString());errors.add("读取区域“"+region.getPresentationName()+"”实际体网格失败");}
        }}catch(RuntimeException failure){errors.add("读取区域目录失败："+failure);}
        for(String id:regionIds)if(!found.contains(id)){rows.add(new LinkedHashMap<>(Map.of("region_id",id,"observation_status","target_region_absent")));errors.add("目标区域已不在当前SIM："+id);}
        return observedSummary(out,rows,errors);
    }
    static Map<String,Object> observedSummary(Map<String,Object> out,List<Map<String,Object>> rows,List<String> errors){
        boolean known=errors.isEmpty();long total=0;for(Map<String,Object> row:rows){Object count=row.get("cell_count");if(!"observed".equals(row.get("observation_status"))||!(count instanceof Number)){known=false;continue;}total+=((Number)count).longValue();}
        out.put("all_region_counts_observed",known);if(known)out.put("observed_cell_count",total);out.put("read_errors",errors);out.put("observation_status",known?"observed":"partial_or_unknown");return out;
    }
    static Map<String,Object> validate(Simulation sim,Map<String,Object> readiness)throws IOException{
        Set<String> requested=flowRegionIds(readiness);Map<String,Object> out=new LinkedHashMap<>();out.put("policy","Necessary flow-mesh existence and associated CAD bounding-box consistency only; not universal geometry provenance, mesh quality, y+ or convergence verification. Imported volume meshes do not require an AutoMeshOperation.");
        List<Map<String,Object>> rows=new ArrayList<>();List<String> errors=new ArrayList<>();out.put("region_checks",rows);
        if(requested.isEmpty()){out.put("status","not_applicable_no_flow_regions");return out;}
        FvRepresentation mesh=null;
        try{Representation current=sim.getRepresentationManager().getDefaultFvRepresentation();if(current!=null)current=current.resolve();if(current instanceof FvRepresentation)mesh=(FvRepresentation)current;}
        catch(RuntimeException failure){errors.add("读取当前有限体积网格表示失败");out.put("read_error",failure.toString());}
        if(mesh==null)errors.add("没有当前有限体积体网格表示");
        else{out.put("representation_id",mesh.getObjectKeyAsString());out.put("representation_name",mesh.getPresentationName());if(mesh.getUnderConstruction())errors.add("当前体网格仍在生成");
            for(Region region:sim.getRegionManager().getRegions())if(requested.contains(region.getObjectKeyAsString())){
                Map<String,Object> row=new LinkedHashMap<>();rows.add(row);row.put("region_id",region.getObjectKeyAsString());row.put("region_name",region.getPresentationName());
                try{
                    boolean described=mesh.describesRegion(region);row.put("described_by_current_fv",described);
                    FvRegion cells=described?mesh.getFvRegionManager().getFvRegion(region):null;long count=cells==null?0:cells.getCellCount();row.put("cell_count",count);boolean empty=cells==null||cells.isEmpty();row.put("empty",empty);
                    if(!described||empty||count<=0){errors.add("流动区域“"+region.getPresentationName()+"”没有实际体单元，需先生成或导入体网格");continue;}
                    if(!mesh.is3D(region)){row.put("cad_bbox_check","not_applicable_2d; FV cell existence checked");continue;}
                    List<GeometryPart> assigned=new ArrayList<>(region.getPartGroup().getObjects());row.put("associated_geometry_part_ids",ids(assigned));
                    if(assigned.isEmpty()){row.put("cad_bbox_check","no_associated_CAD; existing imported FV volume allowed; CAD provenance unverified");continue;}
                    double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};List<String> checked=new ArrayList<>();
                    for(GeometryPart part:assigned){if(!(part instanceof MeshPart))throw new IOException("关联几何类型不支持当前包围盒读回："+part.getObjectKeyAsString());Map<String,Object> bounds=GeometryBounds.measure((MeshPart)part);List<?> minimum=(List<?>)bounds.get("min_m"),maximum=(List<?>)bounds.get("max_m");for(int axis=0;axis<3;axis++){lo[axis]=Math.min(lo[axis],((Number)minimum.get(axis)).doubleValue());hi[axis]=Math.max(hi[axis],((Number)maximum.get(axis)).doubleValue());}checked.add(part.getObjectKeyAsString());}
                    double[] extents=mesh.getRegionExtents(region);if(extents==null||extents.length!=6)throw new IOException("体网格区域范围不是六个有限坐标");double[] fvLo={extents[0],extents[2],extents[4]},fvHi={extents[1],extents[3],extents[5]};double scale=0;for(int axis=0;axis<3;axis++){if(!Double.isFinite(fvLo[axis])||!Double.isFinite(fvHi[axis])||fvLo[axis]>fvHi[axis])throw new IOException("体网格区域范围无效");scale=Math.max(scale,Math.max(hi[axis]-lo[axis],fvHi[axis]-fvLo[axis]));}double tolerance=Math.max(1e-6,1e-5*scale);boolean same=true;for(int axis=0;axis<3;axis++)if(Math.abs(lo[axis]-fvLo[axis])>tolerance||Math.abs(hi[axis]-fvHi[axis])>tolerance)same=false;
                    row.put("checked_geometry_part_ids",checked);row.put("cad_bounds_m",bounds(lo,hi));row.put("fv_bounds_m",bounds(fvLo,fvHi));row.put("tolerance_m",tolerance);row.put("cad_bbox_check",same?"consistent_necessary_check_only":"mismatch");
                    if(!same)errors.add("流动区域“"+region.getPresentationName()+"”的现有体网格范围与当前关联几何不一致；可能保留了修改前的旧网格，需重划或导入对应体网格");
                }catch(Exception failure){row.put("read_error",failure.toString());errors.add("无法确认流动区域“"+region.getPresentationName()+"”的体网格与关联几何一致性");}
            }
        }
        out.put("errors",errors);out.put("status",errors.isEmpty()?"passed_necessary_checks":"rejected");
        if(!errors.isEmpty())throw new IOException("体网格一致性检查未通过："+String.join("；",errors)+"。实际读数："+ConfigurationEngine.json(out));return out;
    }
    static List<String> ids(Collection<? extends GeometryPart> values){List<String> ids=new ArrayList<>();for(GeometryPart value:values)ids.add(value.getObjectKeyAsString());return ids;}
    static Map<String,Object> bounds(double[] lo,double[] hi){return Map.of("min_m",List.of(lo[0],lo[1],lo[2]),"max_m",List.of(hi[0],hi[1],hi[2]));}
}
