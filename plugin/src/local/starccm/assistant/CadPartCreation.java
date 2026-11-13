package local.starccm.assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import star.base.neo.ClientServerObject;
import star.base.neo.NeoProperty;
import star.cadmodeler.Body;
import star.cadmodeler.CadModel;
import star.cadmodeler.SolidModelManager;
import star.cadmodeler.SolidModelPart;
import star.common.CadModelBase;
import star.common.GeometryPart;
import star.common.Simulation;
import star.common.SimulationPartManager;

/** Converts explicitly selected native CAD solids to associated CAD parts without editing CAD.
 * createParts itself is NOT idempotent in STAR 2406: provenance is stored on new parts.
 * The caller owns checkpointing, state fingerprints, task persistence and final simulation save.
 */
public final class CadPartCreation {
    private CadPartCreation() { }
    private static final String SOURCE="starccm_assistant_cad_part_source_v1";
    private static final Set<String> KEYS=Set.of("type","id","model_id","body_ids","expected_state","reason");

    public static boolean supports(String type) {return "create_parts_from_cad".equals(type);}
    public static Map<String,Object> metadata() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("actions",List.of("create_parts_from_cad"));
        out.put("target_types",List.of("star.cadmodeler.CadModel","star.common.Simulation"));
        out.put("required",Map.of("body_ids","nonempty list of actual CAD Body IDs from this model; no names or implicit all bodies"));
        out.put("model_id","required with Simulation target; optional and must match with CadModel target");
        out.put("fixed_options",Map.of("part_type","CAD Part with 3D-CAD Association","solid_bodies_only",true,
            "feature_edges","SharpEdges","sharp_edge_angle_deg",30,"tessellation_density","Medium (2)",
            "create_contacts",true,"coincidence_tolerance_m",1e-5));
        out.put("idempotence","native model association plus persisted source provenance; single-solid legacy model may reuse its associated parts; ambiguous legacy multi-body association is rejected");
        out.put("source_cad_modified",false);
        return out;
    }
    public static String contract() {
        return "create_parts_from_cad：id=当前真实CadModel ID，body_ids=[该模型明确选择的实心CAD Body ID...]；"
            +"或id=simulation_actions的ID且model_id=真实CadModel ID。用cad_models目录取得model/body IDs，不能猜名称或默认选择所有几何。"
            +"用于3D-CAD已有实体但Geometry Parts尚无对应部件；保留源CAD和3D-CAD关联，创建CAD Part而非仅三角化Mesh Part。"
            +"固定SharpEdges30°、Medium、创建重合接触1e-5m；此容差不是CAD Boolean离散输出公差。"
            +"已验证关联的部件幂等跳过；单实心体模型复用已有同模型关联部件；多body已有来源不明部件时明确拒绝并列现有part ID和未解析body ID，不按名称猜归属。"
            +"新部件来源记录于部件持久元数据，不改CAD，不创建区域/网格，不启动求解。执行后重新读取parts和geometry目录继续建外域/区域。";
    }
    public static Map<String,ClientServerObject> objects(Simulation s) {
        Map<String,ClientServerObject> out=new LinkedHashMap<>();
        SolidModelManager manager=s.has(SolidModelManager.class);
        if(manager!=null)for(CadModelBase base:manager.getObjects())if(base instanceof CadModel) {
            CadModel model=(CadModel)base;out.put(model.getObjectKeyAsString(),model);
            for(Body body:model.getBodyManager().getBodies())out.put(body.getObjectKeyAsString(),body);
        }
        return out;
    }
    public static List<Map<String,Object>> inventory(Simulation s) {
        List<Map<String,Object>> out=new ArrayList<>();SolidModelManager manager=s.has(SolidModelManager.class);
        if(manager!=null)for(CadModelBase base:manager.getObjects())if(base instanceof CadModel)out.add(describe(s,base));
        return out;
    }
    private static Map<String,Object> identity(ClientServerObject o) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("id",o.getObjectKeyAsString());
        row.put("name",o.getPresentationName());row.put("type",o.getClass().getName());return row;
    }
    private static List<SolidModelPart> associated(Simulation s,CadModel model) throws IOException {
        List<SolidModelPart> out=new ArrayList<>();SimulationPartManager manager=s.has(SimulationPartManager.class);
        if(manager!=null)for(GeometryPart part:manager.getLeafParts())if(part instanceof SolidModelPart) {
            try {if(model.equals(((SolidModelPart)part).getCadModel()))out.add((SolidModelPart)part);}
            catch(RuntimeException error) {throw new IOException("无法读取现有关联CAD部件 "+part.getObjectKeyAsString()+" 的源模型；未继续创建",error);}
        }
        return out;
    }
    private static NeoProperty source(SolidModelPart part) throws IOException {
        try {
            NeoProperty data=part.getMetaData().getDataTable();
            return data.containsKey(SOURCE)?data.getNeoProperty(SOURCE):null;
        } catch(RuntimeException error) {throw new IOException("无法读取CAD部件来源元数据："+part.getObjectKeyAsString(),error);}
    }
    private static boolean matches(SolidModelPart part,CadModel model,Body body) throws IOException {
        NeoProperty source=source(part);if(source==null)return false;
        return model.getObjectKeyAsString().equals(source.get("model_id"))
            && body.getObjectKeyAsString().equals(source.get("body_id"))
            && body.getPersistentName().equals(source.get("body_persistent_name"))
            && source.get("body_entity_id") instanceof Number
            && ((Number)source.get("body_entity_id")).intValue()==body.getEntityId();
    }
    private static List<String> ids(Collection<? extends ClientServerObject> objects) {
        List<String> ids=new ArrayList<>();for(ClientServerObject o:objects)ids.add(o.getObjectKeyAsString());return ids;
    }
    public static Map<String,Object> describe(Simulation s,ClientServerObject o) {
        if(o instanceof Simulation) return Map.of("cad_models",inventory(s));
        Map<String,Object> out=identity(o);
        try {
            if(o instanceof Body) {
                Body b=(Body)o;out.put("model_id",b.getModel().getObjectKeyAsString());
                out.put("solid",b.isSolid());out.put("mesh_body",b.isMeshBody());out.put("empty",b.isEmpty());
                out.put("entity_id",b.getEntityId());out.put("persistent_name",b.getPersistentName());
            } else if(o instanceof CadModel) {
                CadModel model=(CadModel)o;out.put("loaded",model.isModelLoaded());out.put("up_to_date",model.isUptoDate());
                out.put("feature_in_edit",model.getFeatureManager().isAnyFeatureInEditMode());
                List<SolidModelPart> parts=associated(s,model);out.put("associated_part_ids",ids(parts));
                List<Map<String,Object>> partRows=new ArrayList<>();for(SolidModelPart p:parts) {Map<String,Object> row=identity(p);row.put("dirty",p.isDirty());row.put("needs_update",p.needsUpdate());partRows.add(row);}
                out.put("associated_parts",partRows);
                List<Map<String,Object>> bodyRows=new ArrayList<>();
                for(Body body:model.getBodyManager().getBodies()) {
                    Map<String,Object> row=describe(s,body);List<String> known=new ArrayList<>();
                    for(SolidModelPart p:parts)if(matches(p,model,body))known.add(p.getObjectKeyAsString());
                    row.put("verified_associated_part_ids",known);bodyRows.add(row);
                }
                out.put("bodies",bodyRows);out.put("body_count",bodyRows.size());
                out.put("create_parts_action","create_parts_from_cad");
            }
        } catch(Exception error) {out.put("read_error",String.valueOf(error.getMessage()));}
        return out;
    }
    private static Simulation simulation(ClientServerObject target) throws IOException {
        if(target instanceof Simulation)return (Simulation)target;
        if(target instanceof CadModel)return ((CadModel)target).getSimulation();
        throw new IOException("create_parts_from_cad需要当前Simulation或CadModel目标");
    }
    private static CadModel model(Simulation s,Map<?,?> a,ClientServerObject target) throws IOException {
        Map<String,ClientServerObject> current=objects(s);CadModel model;
        if(target instanceof CadModel) {
            model=(CadModel)target;
            if(current.get(model.getObjectKeyAsString())!=model)throw new IOException("CAD模型不属于当前SIM");
            if(a.containsKey("model_id")&&!model.getObjectKeyAsString().equals(a.get("model_id")))throw new IOException("model_id与CadModel目标不一致");
        } else if(target==s) {
            Object id=a.get("model_id");ClientServerObject found=id instanceof String?current.get(id):null;
            if(!(found instanceof CadModel))throw new IOException("Simulation目标必须明确model_id=当前真实CadModel ID");model=(CadModel)found;
        } else throw new IOException("CAD模型不属于当前SIM");
        return model;
    }
    private static List<Body> bodies(Map<?,?> a,CadModel model) throws IOException {
        Object raw=a.get("body_ids");
        if(!(raw instanceof List)||((List<?>)raw).isEmpty())throw new IOException("body_ids必须明确列出非空实际CAD Body ID列表");
        Set<String> seen=new LinkedHashSet<>();Map<String,Body> current=new LinkedHashMap<>();
        for(Body b:model.getBodyManager().getBodies())current.put(b.getObjectKeyAsString(),b);
        List<Body> out=new ArrayList<>();
        for(Object id:(List<?>)raw) {
            if(!(id instanceof String)||!seen.add((String)id)||!current.containsKey(id))throw new IOException("body_ids存在重复、过期、非ID或不属于所选模型的实体："+id);
            Body b=current.get(id);
            if(!b.isSolid()||b.isMeshBody()||b.isEmpty())throw new IOException("仅支持非空实心CAD实体，不能将sheet或faceted body冒充CAD solid："+id);
            out.add(b);
        }
        return out;
    }
    private static List<Body> missing(Simulation s,CadModel model,List<Body> bodies) throws IOException {
        List<SolidModelPart> parts=associated(s,model);List<Body> out=new ArrayList<>();
        for(Body body:bodies) {
            boolean found=false;for(SolidModelPart p:parts)if(matches(p,model,body))found=true;
            if(!found)out.add(body);
        }
        if(out.isEmpty()||parts.isEmpty())return out;
        int solidCount=0;for(Body body:model.getBodyManager().getBodies())if(body.isSolid()&&!body.isMeshBody()&&!body.isEmpty())solidCount++;
        if(solidCount==1)return List.of(); // native model association uniquely identifies the only eligible body
        List<SolidModelPart> unknown=new ArrayList<>();
        for(SolidModelPart p:parts) {
            boolean identified=false;for(Body body:model.getBodyManager().getBodies())if(matches(p,model,body))identified=true;
            if(!identified)unknown.add(p);
        }
        if(!unknown.isEmpty())throw new IOException("多body模型已有无法验证源body的关联CAD部件；未重复创建。existing_part_ids="+ids(unknown)+" unresolved_body_ids="+ids(out));
        return out;
    }
    public static void validate(Map<?,?> a,ClientServerObject target) throws Exception {
        if(!supports(String.valueOf(a.get("type"))))throw new IOException("不支持的CAD部件动作");
        for(Object key:a.keySet())if(!KEYS.contains(key))throw new IOException("create_parts_from_cad不接受参数："+key);
        Simulation s=simulation(target);CadModel model=model(s,a,target);
        if(!model.isUptoDate())throw new IOException("源3D-CAD模型尚未更新；先完成CAD特征更新，不能创建过期几何部件");
        if(model.getFeatureManager().isAnyFeatureInEditMode())throw new IOException("源CAD特征正在编辑；先完成编辑后创建关联部件");
        missing(s,model,bodies(a,model));
    }
    private static void flush(Simulation s) {s.getServer().getClientNotifyHandler().processCommandQueue(true);}
    /** Native partial failure receipt is retained even if a later body fails. */
    public static final class CreationException extends IOException {
        private final Map<String,Object> receipt;
        private CreationException(String message,Throwable cause,Map<String,Object> receipt) {super(message,cause);this.receipt=new LinkedHashMap<>(receipt);}
        public Map<String,Object> receipt() {return new LinkedHashMap<>(receipt);}
    }
    public static Map<String,Object> execute(Simulation s,Map<?,?> a,ClientServerObject target) throws Exception {
        if(simulation(target)!=s)throw new IOException("CAD动作目标不属于执行中的当前SIM");
        validate(a,target);CadModel model=model(s,a,target);List<Body> selected=bodies(a,model),pending=missing(s,model,selected);
        Map<String,Object> out=new LinkedHashMap<>();out.put("before",describe(s,model));out.put("model_id",model.getObjectKeyAsString());out.put("selected_body_ids",ids(selected));
        List<String> created=new ArrayList<>(),skipped=new ArrayList<>();List<Map<String,Object>> steps=new ArrayList<>();
        out.put("created_part_ids",created);out.put("skipped_body_ids",skipped);out.put("body_receipts",steps);
        Set<String> originalPartIds=new LinkedHashSet<>(ids(s.get(SimulationPartManager.class).getLeafParts()));
        for(Body body:selected)if(!pending.contains(body))skipped.add(body.getObjectKeyAsString());
        try {
            for(Body body:pending) {
                Set<String> before=new LinkedHashSet<>(ids(associated(s,model)));
                // Same CAD-preserving options as official M6; the final explicit false retains association.
                model.createParts(List.of(body),List.of(),true,false,false,false,"SharpEdges",30.0,2,true,1e-5,false,false,false);
                flush(s);List<SolidModelPart> added=new ArrayList<>();
                for(SolidModelPart p:associated(s,model))if(!before.contains(p.getObjectKeyAsString())) {added.add(p);created.add(p.getObjectKeyAsString());}
                Map<String,Object> step=new LinkedHashMap<>();step.put("body_id",body.getObjectKeyAsString());step.put("created_part_ids",ids(added));steps.add(step);
                if(added.size()!=1)throw new IOException("单body创建预期一个关联CAD部件，实际新增"+added.size()+"；已创建ID已保留在收据");
                SolidModelPart part=added.get(0);NeoProperty data=part.getMetaData().getDataTable();NeoProperty origin=new NeoProperty();
                origin.put("model_id",model.getObjectKeyAsString());origin.put("body_id",body.getObjectKeyAsString());origin.put("body_entity_id",body.getEntityId());origin.put("body_persistent_name",body.getPersistentName());
                data.put(SOURCE,origin);part.getMetaData().setDataTable(data);flush(s);
                if(!model.equals(part.getCadModel()))throw new IOException("新CAD部件源模型关联读回不一致："+part.getObjectKeyAsString());
                if(!matches(part,model,body))throw new IOException("新CAD部件来源元数据读回不一致："+part.getObjectKeyAsString()+" source="+source(part)+" expected_entity="+body.getEntityId()+" expected_persistent="+body.getPersistentName());
                if(part.isDirty())throw new IOException("新CAD部件更新状态读回为dirty："+part.getObjectKeyAsString());
                step.put("verified",true);
            }
            out.put("actual",describe(s,model));out.put("source_cad_modified",false);
            out.put("modified",!created.isEmpty());out.put("idempotent_skip",created.isEmpty());out.put("complete",true);
            List<String> needsUpdate=new ArrayList<>();for(SolidModelPart p:associated(s,model))if(p.isDirty()||p.needsUpdate())needsUpdate.add(p.getObjectKeyAsString());
            out.put("associated_parts_needing_update",needsUpdate);
            out.put("validation","已按实际CAD模型关联核对部件；仅创建/复用CAD Parts，尚未生成区域、体网格或求解");
            return out;
        } catch(Exception error) {
            // Native creation can throw after server-side mutation; recover every new object ID.
            try {for(GeometryPart p:s.get(SimulationPartManager.class).getLeafParts())
                if(!originalPartIds.contains(p.getObjectKeyAsString())&&!created.contains(p.getObjectKeyAsString()))created.add(p.getObjectKeyAsString());}
            catch(Exception receiptError) {out.put("receipt_read_error",String.valueOf(receiptError.getMessage()));}
            out.put("complete",false);out.put("actual",describe(s,model));out.put("error",String.valueOf(error.getMessage()));
            throw new CreationException("CAD部件创建未完成；已新增part_ids="+created+"。保留收据并由调用方检查点恢复："+error.getMessage(),error,out);
        }
    }
}
