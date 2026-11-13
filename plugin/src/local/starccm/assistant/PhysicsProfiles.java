package local.starccm.assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import star.base.neo.ClientServerObject;
import star.base.neo.NeoProperty;
import star.common.PhysicsContinuum;
import star.common.Region;
import star.common.Simulation;

/** Bounded model profiles, applied and verified synchronously in the native STAR API.
 * Human intent and replacement authorization belong to the calling dispatcher.
 * The caller must checkpoint before execute: STAR model replacement can recreate defaults.
 */
final class PhysicsProfiles {
    private PhysicsProfiles() { }
    private static final String SA="star.saturb.SpalartAllmarasTurbulence";
    private static final String SA_EQUATION="star.saturb.SaTurbModel";
    private static final String SA_ALL="star.saturb.SaAllYplusWallTreatment";
    private static final String SA_LOW="star.saturb.SaLowYplusWallTreatment";
    private static final String KW="star.kwturb.KOmegaTurbulence";
    private static final String SST="star.kwturb.SstKwTurbModel";
    private static final String KW_ALL="star.kwturb.KwAllYplusWallTreatment";
    private static final String SPACE_2="star.metrics.TwoDimensionalModel";
    private static final String SPACE_3="star.metrics.ThreeDimensionalModel";
    private static final List<String> PROFILES=List.of("steady_compressible_sa","steady_compressible_sa_all_yplus",
        "steady_compressible_sa_low_yplus","steady_compressible_sst_all_yplus","tutorial_onera_m6","tutorial_rae2822");

    private static final class Profile {
        String name, wall, family;
        int dimension;
        final LinkedHashMap<String,String> provisions=new LinkedHashMap<>();
        List<String> models() { return new ArrayList<>(provisions.values()); }
    }

    static boolean supports(String type) {
        return "configure_physics_profile".equals(type)||"configure_external_physics".equals(type);
    }
    static Map<String,Object> metadata() {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("actions",List.of("configure_physics_profile","configure_external_physics"));
        out.put("target_types",List.of("star.common.PhysicsContinuum","star.common.Region"));
        out.put("profiles",PROFILES);
        out.put("optional",Map.of("space_dimension","2 or 3; preserve existing space model when omitted",
            "target_yplus","positive requested near-wall target; does not modify mesh or prove solved y+",
            "replace_conflicting_models","boolean, false by default; an explicit natural-language request to change to a specified model already authorizes its necessary conflicting-model replacement. The planner sets this API flag itself; do not ask the user to confirm the flag. Advice, questions, unspecified model choice or preserving the current family do not authorize replacement."));
        out.put("default_sa_wall_treatment","preserve an existing SA treatment; otherwise tutorial All y+ (also supports target y+ approximately 1)");
        out.put("native_postcondition","all requested model classes enabled and current getRequiredModels() empty");
        return out;
    }
    static String contract() {
        return "configure_physics_profile（别名configure_external_physics）：id=已有PhysicsContinuum或已绑定连续体的Region，profile="
            +String.join("|",PROFILES)+"。一次原生同步补全稳态、气体、耦合流/能量、可压缩理想气体、湍流、RANS、SA方程与壁面处理完整模型链，按当前requiredModels核验；SST profile使用完整K-Omega/SST链。"
            +"steady_compressible_sa保留已有SA壁面处理，否则选官方教程All y+；All y+兼容y+≈1，不因target_yplus=1擅自改Low y+。明确低y+才用sa_low_yplus。"
            +"space_dimension可选2或3，未指定保留现有空间维度，无现有维度则3；教程onera_m6固定3、rae2822固定2。"
            +"target_yplus可选有限正数，仅核对目标，不能代替网格设置或求解y+读回。"
            +"默认不移除现有互斥模型。用户以自然语言明确要求将现有模型切换为指定目标模型，就已授权完成该切换必需的互斥模型替换；规划模型应自行设置replace_conflicting_models=true，无需让用户追加确认或逐字输入这个API字段。此授权只限所选连续体中完成该目标切换必需的相关模型，不扩大到其他连续体、CAD、网格或额外计算。仅询问、建议、未指定目标或要求保持现有模型时，不产生替换授权；不得反向切换、把源模型当目标，或只因推荐而覆盖用户明确选择。"
            +"此动作只配置模型链，不设置材料/参考/初值/边界数值、不绑定其他区域、不划网格、不启动求解；完成这些仍需对应动作和读回。";
    }
    private static PhysicsContinuum continuum(ClientServerObject o) throws IOException {
        if(o instanceof PhysicsContinuum)return (PhysicsContinuum)o;
        if(o instanceof Region) {
            PhysicsContinuum c=((Region)o).getPhysicsContinuum();
            if(c!=null)return c;
            throw new IOException("区域未绑定物理连续体；先创建并明确绑定连续体，再配置物理模型");
        }
        throw new IOException("物理profile需要已有PhysicsContinuum或Region目标");
    }
    private static String canonical(String name) {
        switch(name) {
        case "sa":case "steady_compressible_rans_sa":return "steady_compressible_sa";
        case "sa_all_yplus":return "steady_compressible_sa_all_yplus";
        case "sa_low_yplus":return "steady_compressible_sa_low_yplus";
        case "sst":case "sst_all_yplus":return "steady_compressible_sst_all_yplus";
        case "onera_m6":case "onera_m6_sa_all_yplus":return "tutorial_onera_m6";
        case "rae2822":case "rae2822_sa_all_yplus":return "tutorial_rae2822";
        default:return name;
        }
    }
    static String profileFamily(String name){String n=canonical(name);return n.equals("steady_compressible_sst_all_yplus")?"SST":PROFILES.contains(n)?"SA":"";}
    static void requireRequestedFamily(Map<?,?> action,String goal,ClientServerObject target)throws IOException{
        if(!action.containsKey("profile"))return;requireRequestedFamily(String.valueOf(action.get("profile")),goal,target);
    }
    static void requireRequestedFamily(String profileName,String goal,ClientServerObject target)throws IOException{
        String chosen=profileFamily(profileName);
        if(chosen.isEmpty())return;String requested=TurbulenceIntent.requestedFamily(goal);
        if(!requested.isEmpty()&&!requested.equals(chosen))throw new IOException("当前用户明确目标为 "+requested+"，请求profile却为 "+chosen+"；源模型不能当作切换目标，请保留用户目标并选择对应profile");
        if(TurbulenceIntent.forbiddenFamily(goal,chosen))throw new IOException("当前用户明确禁止使用 "+chosen+"，不能配置该族profile");
        String requestedWall=TurbulenceIntent.requestedWallTreatment(goal);if(!requestedWall.isEmpty()){
            String currentWall="";if(target instanceof Region||target instanceof PhysicsContinuum)currentWall=String.valueOf(describe(target).getOrDefault("sa_wall_treatment",""));
            String selectedWall=profileWallTreatment(profileName,currentWall);if(!requestedWall.equals(selectedWall))throw new IOException("当前用户明确要求 "+requestedWall+" y+ 壁面处理，但profile选择 "+selectedWall+"；请保持目标壁面处理");
        }
        if(TurbulenceIntent.preserveCurrentFamily(goal)&&(target instanceof Region||target instanceof PhysicsContinuum)){
            Map<String,Object> current=describe(target);String family=Boolean.TRUE.equals(current.get("sst_equation_enabled"))?"SST":Boolean.TRUE.equals(current.get("sa_equation_enabled"))?"SA":"";
            String conflict=preservedFamilyConflict(chosen,goal,family);if(!conflict.isEmpty())throw new IOException(conflict);
        }
    }
    static String wallTreatment(String model){return model!=null&&model.endsWith("LowYplusWallTreatment")?"Low":model!=null&&model.endsWith("AllYplusWallTreatment")?"All":"";}
    static String profileWallTreatment(String name,String currentSaWall){String n=canonical(name);if(n.equals("steady_compressible_sa_low_yplus"))return "Low";if(n.equals("steady_compressible_sa"))return "Low".equals(wallTreatment(currentSaWall))?"Low":"All";return PROFILES.contains(n)?"All":"";}
    static String preservedFamilyConflict(String chosen,String goal,String liveFamily){
        if(!TurbulenceIntent.preserveCurrentFamily(goal))return "";
        if(liveFamily==null||liveFamily.isEmpty())return "当前用户要求保留现有湍流族，但实际 SA/SST 族未识别（也可能为层流或读取不完整）；不能因壁面处理或保留请求猜选 SA/SST，请先明确当前模型";
        return liveFamily.equals(chosen)?"":"当前用户要求保留现有湍流模型 "+liveFamily+"，不能切换为 "+chosen;
    }
    static String modelChangeConflict(String model,boolean enable,String goal,String liveFamily){
        String family=model.startsWith("star.saturb.")?"SA":model.startsWith("star.kwturb.")?"SST":"";if(family.isEmpty())return "";
        boolean core=model.equals(SA)||model.equals(SA_EQUATION)||model.equals(KW)||model.equals(SST);
        if(TurbulenceIntent.preserveCurrentFamily(goal)){
            if(liveFamily==null||liveFamily.isEmpty()||enable&&!family.equals(liveFamily))return preservedFamilyConflict(family,goal,liveFamily);
            if(!enable&&core&&family.equals(liveFamily))return "当前用户要求保留现有湍流模型 "+liveFamily+"，不能禁用其核心模型："+model;
        }
        String requested=TurbulenceIntent.requestedFamily(goal);if(requested.isEmpty()&&TurbulenceIntent.preserveCurrentFamily(goal))requested=liveFamily;
        if(enable&&((!requested.isEmpty()&&!requested.equals(family))||TurbulenceIntent.forbiddenFamily(goal,family)))return "当前用户目标不允许启用 "+family+" 族模型："+model;
        String targetWall=TurbulenceIntent.requestedWallTreatment(goal),selectedWall=wallTreatment(model);if(!targetWall.isEmpty()&&!selectedWall.isEmpty()){
            if(enable&&!targetWall.equals(selectedWall))return "当前用户要求 "+targetWall+" y+ 壁面处理，不能启用相反的 "+selectedWall+" y+ 模型："+model;
            if(!enable&&targetWall.equals(selectedWall)&&family.equals(requested))return "不能禁用当前明确目标 "+targetWall+" y+ 壁面模型："+model;
        }
        if(!enable&&core&&!requested.isEmpty()&&requested.equals(family))return "当前用户要求保留目标 "+requested+"，不能禁用其核心模型："+model;
        return "";
    }
    static void requireRequestedModelChange(String model,boolean enable,String goal,PhysicsContinuum c)throws IOException{
        String live="";if(TurbulenceIntent.preserveCurrentFamily(goal)){Map<String,Object> current=describe(c);live=Boolean.TRUE.equals(current.get("sst_equation_enabled"))?"SST":Boolean.TRUE.equals(current.get("sa_equation_enabled"))?"SA":"";}
        String conflict=modelChangeConflict(model,enable,goal,live);if(!conflict.isEmpty())throw new IOException(conflict);
    }
    private static Profile profile(Map<?,?> a,PhysicsContinuum c) throws IOException {
        Object raw=a.get("profile");
        if(!(raw instanceof String)||((String)raw).isBlank())throw new IOException("必须明确指定有界physics profile；用actions目录查询可选profile");
        String name=canonical((String)raw);
        if(!PROFILES.contains(name))throw new IOException("不支持的physics profile: "+raw+"；可选 "+String.join(", ",PROFILES));
        if(a.containsKey("replace_conflicting_models")&&!(a.get("replace_conflicting_models") instanceof Boolean))throw new IOException("replace_conflicting_models必须是布尔值");
        Set<String> enabled=enabled(c);
        int dimension=enabled.contains(SPACE_2)?2:3;
        if(name.equals("tutorial_onera_m6"))dimension=3;
        if(name.equals("tutorial_rae2822"))dimension=2;
        if(a.containsKey("space_dimension")) {
            Object v=a.get("space_dimension");
            if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())
                    ||(((Number)v).doubleValue()!=2&&((Number)v).doubleValue()!=3))throw new IOException("space_dimension必须是数字2或3");
            int n=((Number)v).intValue();
            if((name.equals("tutorial_onera_m6")&&n!=3)||(name.equals("tutorial_rae2822")&&n!=2))throw new IOException("空间维度与所选官方教程profile不一致");
            dimension=n;
        }
        boolean sst=name.equals("steady_compressible_sst_all_yplus");
        String wall=sst?KW_ALL:SA_ALL;
        if(name.equals("steady_compressible_sa_low_yplus")||(name.equals("steady_compressible_sa")&&enabled.contains(SA_LOW)))wall=SA_LOW;
        if(a.containsKey("target_yplus")) {
            Object v=a.get("target_yplus");
            if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()<=0)throw new IOException("target_yplus必须是有限正数");
            if(wall.equals(SA_LOW)&&((Number)v).doubleValue()>1.0)throw new IOException("SA Low y+ profile需要target_yplus≤1；较高目标应明确选择All y+，并核验实际近壁网格");
        }
        Profile p=new Profile();p.name=name;p.wall=wall;p.family=sst?"SST K-Omega":"Spalart-Allmaras";p.dimension=dimension;
        p.provisions.put("Space",dimension==2?SPACE_2:SPACE_3);
        p.provisions.put("Time","star.common.SteadyModel");
        p.provisions.put("Material","star.material.SingleComponentGasModel");
        p.provisions.put("Flow","star.coupledflow.CoupledFlowModel");
        p.provisions.put("Equation of State","star.flow.IdealGasModel");
        p.provisions.put("Energy","star.coupledflow.CoupledEnergyModel");
        p.provisions.put("Viscous Regime","star.turbulence.TurbulentModel");
        p.provisions.put("Turbulence","star.turbulence.RansTurbulenceModel");
        p.provisions.put("Reynolds-Averaged Turbulence",sst?KW:SA);
        p.provisions.put(sst?"Rans K-Omega Turbulence":"Rans Spalart-Allmaras Turbulence",sst?SST:SA_EQUATION);
        p.provisions.put(sst?"K-Omega Wall Treatment":"SA Wall Treatment",wall);
        return p;
    }
    private static Set<String> enabled(PhysicsContinuum c) {return new TreeSet<>(c.getEnabledModels().getKeys());}
    private static Map<String,List<String>> required(PhysicsContinuum c) {
        Map<String,List<String>> out=new LinkedHashMap<>();NeoProperty p=c.getRequiredModels();
        for(String group:new TreeSet<>(p.getKeys())) {
            List<String> choices=new ArrayList<>();Object v=p.get(group);
            if(v instanceof Collection)for(Object x:(Collection<?>)v)choices.add(String.valueOf(x));
            else if(v!=null)choices.add(String.valueOf(v));
            out.put(group,choices);
        }
        return out;
    }
    private static Set<String> provisionNames(NeoProperty enabled,String model) {
        Set<String> out=new LinkedHashSet<>();
        if(enabled.containsKey(model)) {
            Object raw=enabled.getNeoProperty(model).get("provisions");
            if(raw!=null)for(String n:raw.toString().split(","))out.add(n.trim().replaceFirst("^\\*",""));
        }
        return out;
    }
    private static long seniority(NeoProperty enabled,String model) {
        if(enabled.containsKey(model)) {
            Object n=enabled.getNeoProperty(model).get("seniority");if(n instanceof Number)return ((Number)n).longValue();
        }
        return 0;
    }
    private static List<String> conflicts(PhysicsContinuum c,Profile p) {
        NeoProperty state=c.getEnabledModels();Set<String> result=new LinkedHashSet<>();Set<String> desired=new LinkedHashSet<>(p.models());
        for(String model:state.getKeys())if(!desired.contains(model)) {
            Set<String> provides=provisionNames(state,model);
            for(String group:p.provisions.keySet())if(provides.contains(group))result.add(model);
            if((p.family.equals("Spalart-Allmaras")&&(model.equals(SA_ALL)||model.equals(SA_LOW)))
                    ||(p.family.equals("SST K-Omega")&&model.startsWith("star.kwturb.Kw")&&model.endsWith("WallTreatment")))result.add(model);
            // Old releases may omit provision metadata for subtype models. Protect known
            // alternative families too; remove their dependent leaf/wall models before the parent.
            if(p.family.equals("Spalart-Allmaras")&&(model.startsWith("star.kwturb.")||model.startsWith("star.keturb.")||model.startsWith("star.rsturb.")||model.startsWith("star.lesturb.")))result.add(model);
            if(p.family.equals("SST K-Omega")&&(model.startsWith("star.saturb.")||model.startsWith("star.keturb.")||model.startsWith("star.rsturb.")||model.startsWith("star.lesturb.")))result.add(model);
        }
        List<String> sorted=new ArrayList<>(result);
        sorted.sort(Comparator.comparingLong((String m)->seniority(state,m)).reversed().thenComparing(m->m));
        return sorted;
    }
    static void validate(Map<?,?> a,ClientServerObject o) throws Exception {
        if(!supports(String.valueOf(a.get("type"))))throw new IOException("不支持的physics profile操作");
        PhysicsContinuum c=continuum(o);Profile p=profile(a,c);List<String> conflicts=conflicts(c,p);
        if(!conflicts.isEmpty()&&!Boolean.TRUE.equals(a.get("replace_conflicting_models")))
            throw new IOException("当前模型与请求profile互斥："+String.join(", ",conflicts)+"。已保留现有选择；仅当用户明确要求该模型切换时，才可使用replace_conflicting_models=true");
        if(c.isModelSelectionStaged())throw new IOException("当前物理模型选择处于staged状态；先完成当前阶段选择再修改profile");
    }
    private static void flush(Simulation s) {s.getServer().getClientNotifyHandler().processCommandQueue(true);}
    static Map<String,Object> execute(Simulation s,Map<?,?> a,ClientServerObject o) throws Exception {
        validate(a,o);PhysicsContinuum c=continuum(o);Profile p=profile(a,c);
        Map<String,Object> out=new LinkedHashMap<>();out.put("before",describe(o));out.put("profile",p.name);
        out.put("continuum_id",c.getObjectKeyAsString());out.put("continuum_name",c.getPresentationName());
        List<String> removed=new ArrayList<>(),added=new ArrayList<>();List<Map<String,Object>> steps=new ArrayList<>();
        for(String model:conflicts(c,p))if(enabled(c).contains(model)) {
            c.disable(model);flush(s);
            if(enabled(c).contains(model))throw new IOException("互斥模型禁用读回失败："+model);
            removed.add(model);
        }
        for(String model:p.models())if(!enabled(c).contains(model)) {
            c.enable(model);flush(s);
            if(!enabled(c).contains(model))throw new IOException("物理模型启用读回失败："+model+"；当前必需模型="+required(c));
            added.add(model);steps.add(Map.of("enabled_model",model,"required_models_after",required(c)));
        }
        flush(s);List<String> missing=new ArrayList<>();Set<String> actual=enabled(c);
        for(String model:p.models())if(!actual.contains(model))missing.add(model);
        Map<String,List<String>> needed=required(c);
        if(!missing.isEmpty()||!needed.isEmpty())throw new IOException("物理profile尚未完整：未启用="+missing+"，当前requiredModels="+needed+"；不能宣称已可求解");
        // Ideal gas can remain in its incompressible variant even with the correct class.
        star.flow.IdealGasModel gas=c.getModelManager().getModel(star.flow.IdealGasModel.class);
        gas.setIncompressible(false);flush(s);
        if(gas.getIncompressible())throw new IOException("理想气体可压缩选项读回失败");
        out.put("enabled_models_added",added);out.put("disabled_conflicting_models",removed);out.put("model_steps",steps);
        out.put("actual",describe(o));out.put("required_models",needed);out.put("model_chain_complete",true);
        if(a.containsKey("target_yplus"))out.put("requested_target_yplus",a.get("target_yplus"));
        out.put("wall_treatment",p.wall);out.put("space_dimension",p.dimension);
        out.put("not_validated",List.of("Material, reference, initial and boundary values must be configured and checked separately.","No mesh was generated or solver iteration run; achieved wall y+ and solution accuracy were not validated."));
        out.put("validation","完整模型链已原生同步写入、逐项读回，当前requiredModels为空，理想气体为可压缩；近壁网格、实际y+、工况数值和迭代收敛需另行核验");
        return out;
    }
    static Map<String,Object> describe(ClientServerObject o) {
        Map<String,Object> out=new LinkedHashMap<>();
        try {
            PhysicsContinuum c=continuum(o);Set<String> models=enabled(c);
            out.put("continuum_id",c.getObjectKeyAsString());out.put("continuum_name",c.getPresentationName());
            out.put("enabled_models",new ArrayList<>(models));out.put("required_models",required(c));
            out.put("required_models_complete",c.getRequiredModels().isEmpty());
            out.put("sa_family_enabled",models.contains(SA));out.put("sa_equation_enabled",models.contains(SA_EQUATION));
            out.put("sa_wall_treatment",models.contains(SA_LOW)?SA_LOW:models.contains(SA_ALL)?SA_ALL:"");
            out.put("sa_chain_complete",models.contains(SA)&&models.contains(SA_EQUATION)&&(models.contains(SA_LOW)||models.contains(SA_ALL)));
            out.put("sst_equation_enabled",models.contains(SST));
            out.put("sst_family_enabled",models.contains(KW));
            String sstWall=models.stream().filter(m->m.startsWith("star.kwturb.Kw")&&m.endsWith("WallTreatment")).findFirst().orElse("");out.put("sst_wall_treatment",sstWall);
            out.put("sst_chain_complete",models.contains(KW)&&models.contains(SST)&&!sstWall.isEmpty());
            if(models.contains("star.flow.IdealGasModel"))out.put("ideal_gas_incompressible",c.getModelManager().getModel(star.flow.IdealGasModel.class).getIncompressible());
        } catch(Exception e) {out.put("physics_profile_read_error",String.valueOf(e.getMessage()));}
        return out;
    }
}
