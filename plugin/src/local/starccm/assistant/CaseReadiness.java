package local.starccm.assistant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import star.common.Boundary;
import star.common.GeometryObject;
import star.common.GeometryObjectProxy;
import star.common.GeometryPart;
import star.common.PartSurface;
import star.common.PhysicsContinuum;
import star.common.Region;
import star.common.Simulation;
import star.meshing.SimpleBlockPart;

/** Cheap read-only inventory and scope-aware completeness checks.
 * A clean result is not engineering validation, convergence, or authorization to run.
 * No reports, tessellations, meshes, saved state, or model changes are created here.
 */
public final class CaseReadiness {
    private CaseReadiness() { }

    public static Map<String,Object> inspect(Simulation sim, String goal) {return inspect(sim,goal,Set.of());}

    /** Explicit evaluator scope; unrelated regions are neither completion blockers
     * nor evidence that this requested chain has the required boundaries. */
    public static Map<String,Object> inspect(Simulation sim, String goal, Set<String> regionIds) {
        if (sim == null) throw new IllegalArgumentException("simulation is required");
        List<Map<String,Object>> regions = new ArrayList<>();
        List<Map<String,Object>> boundaries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> foundIds=new HashSet<>();
        for (Region region : sim.getRegionManager().getRegions()) {
            if(!regionIds.isEmpty()&&!regionIds.contains(region.getObjectKeyAsString()))continue;
            foundIds.add(region.getObjectKeyAsString());
            Map<String,Object> r = new LinkedHashMap<>();
            r.put("id",region.getObjectKeyAsString());
            r.put("name",region.getPresentationName());
            try {
                PhysicsContinuum c = region.getPhysicsContinuum();
                r.put("continuum_bound",c != null);
                r.put("continuum_name",c == null ? "" : c.getPresentationName());
                r.put("continuum_id",c == null ? "" : c.getObjectKeyAsString());
                r.put("enabled_models",c == null ? List.of() : new ArrayList<>(new TreeSet<>(c.getEnabledModels().getKeys())));
                if(c!=null) {
                    Map<String,Object> metadata=PhysicsProfiles.describe(c);
                    r.put("required_models",metadata.getOrDefault("required_models",Map.of()));
                    r.put("required_models_complete",metadata.getOrDefault("required_models_complete",false));
                    for(String key:List.of("sa_wall_treatment","sa_chain_complete","sst_wall_treatment","sst_chain_complete","ideal_gas_incompressible"))if(metadata.containsKey(key))r.put(key,metadata.get(key));
                    if(metadata.containsKey("physics_profile_read_error")) {
                        r.put("physics_profile_read_error",metadata.get("physics_profile_read_error"));
                        errors.add("读取区域“"+region.getPresentationName()+"”当前必需模型候选失败");
                    }
                } else {r.put("required_models",Map.of());r.put("required_models_complete",false);}
                requiredMetadata(r);
            } catch (RuntimeException e) {
                r.put("read_error",e.toString());
                errors.add("读取区域“"+region.getPresentationName()+"”绑定连续体或模型失败");
            }
            regions.add(r);
            for (Boundary boundary : region.getBoundaryManager().getBoundaries()) {
                Map<String,Object> b = new LinkedHashMap<>();
                b.put("id",boundary.getObjectKeyAsString());
                b.put("name",boundary.getPresentationName());
                b.put("region_id",region.getObjectKeyAsString());
                b.put("region_name",region.getPresentationName());
                try {
                    b.put("boundary_type",boundary.getBoundaryType().getClass().getName());
                    b.put("boundary_type_label",boundary.getBoundaryType().getPresentationName());
                    Collection<PartSurface> assigned = boundary.getPartSurfaceGroup().getObjects();
                    b.put("assigned_surface_count",assigned.size());
                    b.put("nonempty",!assigned.isEmpty());
                    List<Map<String,Object>> faces = new ArrayList<>();
                    boolean blockSource = false;
                    for (PartSurface surface : assigned) {
                        Map<String,Object> f = new LinkedHashMap<>();
                        f.put("id",surface.getObjectKeyAsString());
                        f.put("name",surface.getQualifiedName());
                        f.put("part_id",surface.getPart().getObjectKeyAsString());
                        f.put("part_name",surface.getPart().getPresentationName());
                        f.put("part_type",surface.getPart().getClass().getName());
                        try {
                            GeometryPart source = sourcePart(surface);
                            if (source != null) {
                                f.put("source_part_id",source.getObjectKeyAsString());
                                f.put("source_part_name",source.getPresentationName());
                                f.put("source_part_type",source.getClass().getName());
                                if (source instanceof SimpleBlockPart) blockSource = true;
                            }
                        } catch (RuntimeException e) {
                            // Provenance is optional evidence, never a reason to erase an inventory row.
                            f.put("source_read_error",e.toString());
                        }
                        faces.add(f);
                    }
                    b.put("assigned_surfaces",faces);
                    b.put("suspected_outer_wall",blockSource && type(b,"WallBoundary"));
                    if (blockSource) b.put("outer_wall_evidence","Assigned surface provenance reaches a SimpleBlockPart. It may be an outer enclosure or a physical block; no geometric classification has been performed.");
                } catch (RuntimeException e) {
                    b.put("read_error",e.toString());
                    errors.add("读取边界“"+boundary.getPresentationName()+"”类型或所属表面失败");
                }
                boundaries.add(b);
            }
        }
        for(String expected:regionIds)if(!foundIds.contains(expected))errors.add("目标区域已不在当前SIM："+expected);
        Map<String,Object> result=new LinkedHashMap<>();result.put("finite_volume_mesh",FlowMeshGate.observe(sim,regionIds));result.putAll(evaluate(regions,boundaries,goal,errors,!regionIds.isEmpty()));
        result.put("evaluated_region_ids",new ArrayList<>(new TreeSet<>(foundIds)));
        result.put("explicit_region_scope",!regionIds.isEmpty());return result;
    }

    private static GeometryPart sourcePart(PartSurface surface) {
        GeometryObject current=surface;
        GeometryPart found=surface.getPart();
        Set<String> seen=new HashSet<>();
        for(int depth=0;depth<5 && current!=null;depth++) {
            if(!seen.add(current.getObjectKeyAsString())) break;
            if(current instanceof PartSurface) found=((PartSurface)current).getPart();
            if(current instanceof GeometryPart) found=(GeometryPart)current;
            if(current instanceof GeometryObjectProxy) {
                GeometryPart source=((GeometryObjectProxy)current).getPartUltimatelyDerivedFrom();
                if(source!=null) found=source;
            }
            if(found instanceof SimpleBlockPart) return found;
            current=current.hasDerivedFrom();
        }
        return found;
    }

    /** Data-only part, kept separate so goal scope and misleading names can be tested without STAR. */
    static Map<String,Object> evaluate(List<Map<String,Object>> regions,
            List<Map<String,Object>> boundaries,String goal,List<String> inspectionErrors) {
        return evaluate(regions,boundaries,goal,inspectionErrors,false);
    }
    static Map<String,Object> evaluate(List<Map<String,Object>> regions,
            List<Map<String,Object>> boundaries,String goal,List<String> inspectionErrors,boolean explicitRegionScope) {
        String rawGoal=goal == null ? "" : goal;String g=GoalRequirementIntent.requirementText(rawGoal);
        String lower=g.toLowerCase(Locale.ROOT);
        boolean onlyMesh=g.matches("(?is).*(?:只|仅|先只|only).{0,12}(?:划分|生成|绘制|重划|检查)?网格.*")
                || lower.matches("(?s).*only.{0,20}(?:mesh|meshing).*" );
        boolean runRequested=g.matches("(?is).*(?:(?:运行|启动|执行|继续|开始).{0,12}(?:求解|计算)|run.{0,12}(?:solver|simulation)|solve.{0,12}(?:flow|case)).*")
                && !g.matches("(?is).*(?:不|不要|无需|禁止).{0,8}(?:求解|计算|solver).*" );
        boolean explicitNonFlow=g.matches("(?is).*(?:固体导热|热传导|导热计算|固体力学|结构力学|电磁|solid[ -]?(?:conduction|mechanics)|heat[ -]?conduction|electromagnetic).*" )
                && !g.matches("(?is).*(?:气动|流动|空气|流体|马赫|mach|fluid|aerodynamic|(?<![a-z])flow(?![a-z])).*" );
        boolean scopedNumeric=g.matches("(?is).*(?:只|仅|only).{0,10}(?:修改|调整|设置|更改|change|set).{0,15}(?:尺寸|层数|增长|厚度|参数|网格|size|thickness|growth|layers).*" );
        boolean fullPhysics=!onlyMesh && !scopedNumeric && !explicitNonFlow && (runRequested || g.matches("(?is).*(?:气动计算|空气动力计算|流动计算|完整.{0,8}(?:物理|工况|气动|配置)|(?:完成|补齐|配置|设置).{0,12}(?:工况|来流条件)|写入.{0,18}(?:相应|工况).{0,6}参数).*"));
        String targetFamily=TurbulenceIntent.requestedFamily(rawGoal);
        String targetWall=TurbulenceIntent.requestedWallTreatment(rawGoal);boolean needWall=!targetWall.isEmpty();
        boolean explicitSa=targetFamily.equals("SA"),explicitSst=targetFamily.equals("SST");
        boolean modelSetup=rawGoal.matches("(?is).*(?:物理模型|湍流模型|采用|启用|改为|配置模型|enable.{0,8}model|model.{0,8}(?:use|set)).*" )||!targetFamily.isEmpty();
        boolean needSa=!onlyMesh&&!scopedNumeric&&explicitSa&&(fullPhysics||modelSetup);
        boolean needSst=!onlyMesh&&!scopedNumeric&&explicitSst&&(fullPhysics||modelSetup);
        boolean steadyMention=g.matches("(?is).*(?:定常|稳态|steady).*" )
                && !g.matches("(?is).*(?:非定常|非稳态|瞬态|unsteady|transient).*" );
        boolean needSteady=!onlyMesh&&!scopedNumeric&&steadyMention&&(fullPhysics||modelSetup);
        boolean needUnsteady=!onlyMesh&&!scopedNumeric&&(fullPhysics||modelSetup)&&g.matches("(?is).*(?:非定常|非稳态|瞬态|unsteady|transient).*")&&!g.matches("(?is).*(?:不要|无需|禁止|不改为|do not|don't).{0,16}(?:非定常|非稳态|瞬态|unsteady|transient).*");
        boolean boundarySetup=fullPhysics || (!onlyMesh&&!scopedNumeric&&(GoalRequirementIntent.changesBoundaryOrDomain(rawGoal)||rawGoal.matches("(?is).*(?:边界条件|(?:边界|出口|入口|对称面).{0,20}(?:设为|设置|指定|改为|修改|调整|修正|修复|重配|重建)|(?:设置|指定|创建|添加|修改|调整|修正|修复|重配|重建|重新配置|启用).{0,20}(?:边界|出口|入口|对称面|流体域|外域|流场)|(?:modify|change|fix|repair|rebuild|configure|enable).{0,25}(?:boundary|inlet|outlet|symmetry|domain)).*")));
        boolean fullModelTarget=boundarySetup&&g.matches("(?is).*(?:全模|完整全机|full[ -]?(?:model|aircraft)).*")&&!g.matches("(?is).*(?:不要|无需|禁止|不使用|不是|do not|don't).{0,10}(?:全模|完整全机|full[ -]?(?:model|aircraft)).*");
        boolean needPressure=boundarySetup&&g.matches("(?is).*(?:压力出口|pressure[ _-]*outlet).*" );
        boolean needInlet=boundarySetup&&g.matches("(?is).*(?:速度入口|velocity[ _-]*inlet).*" );
        boolean needFreestream=boundarySetup&&g.matches("(?is).*(?:自由来流|自由流|free[ _-]*stream).*" );
        String symWords="(?:对称面|对称平面|对称边界|symmetry(?:[ _-]+(?:plane|boundar(?:y|ies)))?)";
        String symmetryIntent=g
            .replaceAll("(?is)(?:不要|不|别|无需|禁止|无)\\s*(?:对|将)?\\s*(?:使用|添加|创建|需要|设置|加|修改|更改|改变|调整|改动|改|动|启用)?\\s*(?:现有|当前|任何)?\\s*"+symWords, "")
            .replaceAll("(?is)(?:do\\s+not|don't|never|no|without|not)\\s*(?:modify|change|alter|adjust|touch|create|add|enable|use)?\\s*(?:the|any|existing|current)?\\s*"+symWords, "")
            .replaceAll("(?is)(?:保持|保留|preserve|keep|leave)\\s*(?:现有|当前|原有|the|existing|current)?\\s*"+symWords, "")
            .replaceAll("(?is)"+symWords+"[^，,;；。\\n]{0,20}?(?:保持不变|保持现状|不变|不要修改|不修改|不改|unchanged|unmodified|as\\s+is)", "")
            .replaceAll("(?is)(?:不是|并非|非)\\s*半模|(?:not|no)\\s+(?:a\\s+)?half[ -]?model", "");
        boolean needSymmetry=boundarySetup&&symmetryIntent.matches("(?is).*(?:对称面|对称边界|半模|half[ -]?model|symmetry).*" );
        boolean needGas=fullPhysics&&(explicitSa||explicitSst||g.matches("(?is).*(?:气动|空气|air|gas|马赫|mach).*" ));
        boolean modelCompletion=fullPhysics||needSa||needSst||needSteady||needUnsteady||needWall||(!onlyMesh&&!scopedNumeric&&modelSetup);
        List<String> warnings=new ArrayList<>();
        List<String> incomplete=new ArrayList<>();
        List<String> solverReasons=new ArrayList<>();
        int nonemptyInlet=0,nonemptyPressure=0,nonemptySymmetry=0,nonemptyFreestream=0,nonemptyOpen=0;
        List<String> emptyInlet=new ArrayList<>(),emptySymmetry=new ArrayList<>(),emptyPressure=new ArrayList<>();
        List<String> emptyOtherOpen=new ArrayList<>();
        List<String> walls=new ArrayList<>(),suspectedOuter=new ArrayList<>();
        for(Map<String,Object> b:boundaries) {
            boolean nonempty=Boolean.TRUE.equals(b.get("nonempty"));
            String name=label(b);
            if(type(b,"InletBoundary")) { if(nonempty)nonemptyInlet++;else emptyInlet.add(name); }
            if(type(b,"PressureBoundary")) { if(nonempty)nonemptyPressure++;else emptyPressure.add(name); }
            if(type(b,"SymmetryBoundary")) { if(nonempty)nonemptySymmetry++;else emptySymmetry.add(name); }
            if(nonempty&&type(b,"FreeStreamBoundary"))nonemptyFreestream++;
            if(nonempty&&open(b))nonemptyOpen++;
            if(!nonempty&&open(b)&&!type(b,"InletBoundary")&&!type(b,"PressureBoundary"))emptyOtherOpen.add(name);
            if(nonempty&&type(b,"WallBoundary")) {
                walls.add(name);
                if(Boolean.TRUE.equals(b.get("suspected_outer_wall")))suspectedOuter.add(name);
            }
        }
        if(!emptyInlet.isEmpty())warnings.add("以下速度入口没有所属面，不能算作入口已配置："+String.join("、",emptyInlet));
        if(!emptySymmetry.isEmpty())warnings.add("以下对称边界没有所属面，不能算作对称面已配置："+String.join("、",emptySymmetry));
        if(!emptyPressure.isEmpty())warnings.add("以下压力出口没有所属面，不能算作出口已配置："+String.join("、",emptyPressure));
        if(!emptyOtherOpen.isEmpty())warnings.add("以下开放边界没有所属面，保存的流动参数尚无实际作用面："+String.join("、",emptyOtherOpen));
        if(!suspectedOuter.isEmpty())warnings.add("疑似外域壁面仍为 Wall："+String.join("、",suspectedOuter)+"。依据为表面上游部件包含 SimpleBlockPart；尚未做几何分类，不能据此自动改边界。");
        else if((fullPhysics||boundarySetup)&&!walls.isEmpty())warnings.add("当前非空壁面："+String.join("、",walls)+"。请核验其仅包含实际固体表面；未测量几何，不能仅凭名称排除外域面遗留为 Wall。");
        if(needPressure&&nonemptyPressure==0)incomplete.add("目标要求压力出口，但当前没有非空 Pressure Outlet 边界");
        if(needInlet&&nonemptyInlet==0)incomplete.add("目标要求速度入口，但当前没有非空 Velocity Inlet 边界");
        if(needSymmetry&&nonemptySymmetry==0)incomplete.add("目标要求对称面，但当前没有非空 Symmetry 边界");
        if(needFreestream&&nonemptyFreestream==0)incomplete.add("目标要求自由来流，但当前没有非空 Free Stream 边界");
        if(fullModelTarget&&nonemptySymmetry>0){if(explicitRegionScope)incomplete.add("目标明确为全模，但本次关联外流区域仍存在非空 Symmetry 边界");else warnings.add("目标为全模，库存仍有 Symmetry；尚无明确关联区域范围，不能据此改动其他外流链或认定目标全模已完成");}
        if(regions.isEmpty()) {
            if(fullPhysics||needSa||needSst||needSteady||needUnsteady||needWall)incomplete.add("没有实际区域，尚不能完成流动工况配置");
            solverReasons.add("没有实际区域");
        }
        int fluidCandidates=0;
        List<Map<String,Object>> perRegionCounts=new ArrayList<>();
        for(Map<String,Object> r:regions) {
            requiredMetadata(r);
            Map<String,Integer> counts=countsForRegion(r,boundaries);r.put("boundary_counts",counts);
            Map<String,Object> countRow=new LinkedHashMap<>();if(r.containsKey("id"))countRow.put("region_id",r.get("id"));countRow.put("region_name",r.getOrDefault("name","未命名区域"));countRow.putAll(counts);perRegionCounts.add(countRow);
            List<String> models=strings(r.get("enabled_models"));
            boolean bound=Boolean.TRUE.equals(r.get("continuum_bound"));
            boolean solid=containsModel(models,"SolidModel")||containsModel(models,"SolidStressModel");
            boolean flow=hasFlow(models);
            boolean gas=containsModel(models,"SingleComponentGasModel")||containsModel(models,"MultiComponentGasModel")||containsModel(models,"GasModel");
            boolean sa=containsModel(models,"SaTurbModel");
            boolean saChain=sa&&containsModel(models,"SpalartAllmarasTurbulence")&&(containsModel(models,"SaAllYplusWallTreatment")||containsModel(models,"SaLowYplusWallTreatment"));
            boolean sst=containsModel(models,"SstKwTurbModel"),sstChain=sst&&containsModel(models,"KOmegaTurbulence")&&models.stream().anyMatch(m->m.startsWith("star.kwturb.Kw")&&m.endsWith("WallTreatment"));
            boolean steady=containsModel(models,"SteadyModel");
            boolean unsteady=containsModel(models,"ImplicitUnsteadyModel")||containsModel(models,"ExplicitUnsteadyModel")||containsModel(models,"PisoUnsteadyModel");
            r.put("flow_model_enabled",flow); r.put("gas_model_enabled",gas);
            r.put("sa_model_enabled",sa);r.put("sst_model_enabled",sst);r.put("sst_chain_complete",sstChain);r.put("steady_model_enabled",steady);r.put("unsteady_model_enabled",unsteady);
            r.put("known_solid_region",solid);
            if(solid) continue;
            fluidCandidates++;
            String name=String.valueOf(r.getOrDefault("name","未命名区域"));
            if(counts.get("nonempty_open")==0) {
                String reason="区域“"+name+"”没有非空开放边界（Free Stream、入口或出口）；其他区域的边界不能代替本区域";
                solverReasons.add(reason);if(fullPhysics)incomplete.add(reason);
                else warnings.add(reason+"。网格或局部参数任务可继续，这不代表该区域可以运行外流求解。");
            }
            if(!bound) {
                if(fullPhysics||needSa||needSst||needSteady||needUnsteady||needWall)incomplete.add("区域“"+name+"”未绑定物理连续体");
                solverReasons.add("区域“"+name+"”未绑定流动物理连续体");
                continue;
            }
            Map<String,List<String>> required=requiredModels(r.get("required_models"));
            if(!required.isEmpty()) {
                String reason="区域“"+name+"”绑定连续体仍缺少必需模型："+String.join("、",required.keySet());
                solverReasons.add(reason);if(modelCompletion)incomplete.add(reason);else warnings.add(reason+"；当前网格或局部参数任务无需补全无关物理设置。候选模型见required_model_candidates。");
            }
            if(!flow) {
                if(fullPhysics)incomplete.add("区域“"+name+"”绑定连续体未启用流动求解模型");
                solverReasons.add("区域“"+name+"”未启用 Coupled/Segregated Flow 模型");
            }
            if(needSa&&!sa)incomplete.add("区域“"+name+"”未启用目标 SA 湍流模型（SaTurbModel）");
            if(needSst&&!sstChain)incomplete.add("区域“"+name+"”未完成目标 SST 湍流模型链（K-Omega/SST及壁面处理）");
            if(needGas&&!gas)incomplete.add("区域“"+name+"”未启用气体材料模型");
            if(needSteady&&!steady)incomplete.add("区域“"+name+"”未启用目标 Steady 模型");
            if(needUnsteady&&!unsteady)incomplete.add("区域“"+name+"”未启用目标 Unsteady 模型");
            if(needWall){boolean saMatch=saChain&&(targetFamily.isEmpty()||targetFamily.equals("SA"))&&containsModel(models,targetWall.equals("Low")?"SaLowYplusWallTreatment":"SaAllYplusWallTreatment");boolean sstMatch=sstChain&&(targetFamily.isEmpty()||targetFamily.equals("SST"))&&models.stream().anyMatch(m->m.startsWith("star.kwturb.")&&targetWall.equals(PhysicsProfiles.wallTreatment(m)));if(!saMatch&&!sstMatch)incomplete.add("区域“"+name+"”尚未具有完整目标湍流族链及 "+targetWall+" y+ 壁面处理；不能用残留壁面类或相反处理视为完成");}
            if(!flow && !fullPhysics)warnings.add("区域“"+name+"”当前没有流动求解模型；网格或局部参数任务可继续，这不代表已配置好流动求解。");
        }
        if(fluidCandidates==0&&!regions.isEmpty()) {
            if(fullPhysics)incomplete.add("当前没有可用于目标流动计算的区域（仅识别到固体区域）");
            solverReasons.add("没有可用于流动计算的区域");
        }
        if(!inspectionErrors.isEmpty()) {
            warnings.addAll(inspectionErrors);
            solverReasons.add("部分区域或边界未能完整读取，尚不能确认流动求解基础配置");
            if(fullPhysics||needSa||needSst||needSteady||needUnsteady||needWall||boundarySetup)incomplete.add("工况库存读取不完整，不能将未读到的配置视为完成");
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("scope","lightweight read-only inventory; not convergence, geometry, mesh quality, operating-value validation, or permission to solve");
        result.put("goal_requires_full_physics",fullPhysics);
        result.put("goal_requires_physics_model_completion",modelCompletion);
        result.put("requested_turbulence_family",targetFamily);Map<String,Object> requested=new LinkedHashMap<>(Map.of("sa",needSa,"sst",needSst,"steady",needSteady,"unsteady",needUnsteady,"gas",needGas,"nonempty_velocity_inlet",needInlet,"nonempty_pressure_outlet",needPressure,"nonempty_symmetry",needSymmetry,"nonempty_freestream",needFreestream,"full_model_no_symmetry",fullModelTarget&&explicitRegionScope));
        requested.put("wall_treatment",targetWall);result.put("goal_requirements",requested);
        result.put("regions",regions); result.put("boundaries",boundaries);
        result.put("boundary_counts",Map.of("nonempty_velocity_inlet",nonemptyInlet,"nonempty_pressure_outlet",nonemptyPressure,"nonempty_symmetry",nonemptySymmetry,"nonempty_freestream",nonemptyFreestream,"nonempty_open",nonemptyOpen));
        result.put("per_region_counts",perRegionCounts);
        result.put("warnings",warnings); result.put("incomplete_reasons",dedupe(incomplete));
        result.put("requested_configuration_items_present",incomplete.isEmpty());
        result.put("not_validated",List.of("Operating scalar/vector values, material/initial/boundary thermodynamic consistency, convergence and solved y+ require separate readback.","Open-boundary presence is checked separately for each non-solid region, not merely counted across the SIM. This necessary external-flow check does not prove compatible boundary placement or completeness; interconnected closed regions may require an explicitly different workflow.","Specific requested boundary-type totals remain inventory-wide for compatibility; per_region_counts exposes actual distribution. A boundary-type replacement is verified by the native write readback of that exact Boundary ID, never by a global Wall count. Without object identity this inventory is necessary evidence only."));
        result.put("flow_solver_basics_ready",solverReasons.isEmpty());
        result.put("flow_solver_incomplete_reasons",dedupe(solverReasons));
        result.put("inspection_errors",inspectionErrors);
        return result;
    }
    private static List<String> dedupe(List<String> v) { return new ArrayList<>(new java.util.LinkedHashSet<>(v)); }
    private static Map<String,List<String>> requiredModels(Object raw) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        if(raw instanceof Map)for(Map.Entry<?,?> e:((Map<?,?>)raw).entrySet()) {
            List<String> values=strings(e.getValue());if(values.isEmpty()&&e.getValue()!=null&&!(e.getValue() instanceof Collection))values=List.of(String.valueOf(e.getValue()));result.put(String.valueOf(e.getKey()),values);
        }
        return result;
    }
    private static void requiredMetadata(Map<String,Object> r) {
        Map<String,List<String>> required=requiredModels(r.get("required_models"));r.put("required_models",required);r.put("required_model_groups",new ArrayList<>(required.keySet()));
        List<Map<String,Object>> candidates=new ArrayList<>();for(Map.Entry<String,List<String>> e:required.entrySet())for(String type:e.getValue())candidates.add(Map.of("group",e.getKey(),"model_class",type));
        r.put("required_model_candidates",candidates);r.put("missing_model_metadata",candidates);
        if(Boolean.TRUE.equals(r.get("continuum_bound")))r.put("required_models_complete",required.isEmpty()&&!r.containsKey("physics_profile_read_error")&&!r.containsKey("read_error"));
    }
    private static Map<String,Integer> countsForRegion(Map<String,Object> region,List<Map<String,Object>> boundaries) {
        Map<String,Integer> result=new LinkedHashMap<>();for(String key:List.of("nonempty_velocity_inlet","nonempty_pressure_outlet","nonempty_symmetry","nonempty_open","nonempty_wall","assigned_boundary_count","empty_boundary_count"))result.put(key,0);
        for(Map<String,Object> b:boundaries) {
            boolean same;
            if(region.containsKey("id")&&b.containsKey("region_id"))same=java.util.Objects.equals(region.get("id"),b.get("region_id"));
            else same=region.containsKey("name")&&b.containsKey("region_name")&&java.util.Objects.equals(region.get("name"),b.get("region_name"));
            if(!same)continue;
            if(!Boolean.TRUE.equals(b.get("nonempty"))){result.put("empty_boundary_count",result.get("empty_boundary_count")+1);continue;}
            result.put("assigned_boundary_count",result.get("assigned_boundary_count")+1);
            if(type(b,"InletBoundary"))result.put("nonempty_velocity_inlet",result.get("nonempty_velocity_inlet")+1);
            if(type(b,"PressureBoundary"))result.put("nonempty_pressure_outlet",result.get("nonempty_pressure_outlet")+1);
            if(type(b,"SymmetryBoundary"))result.put("nonempty_symmetry",result.get("nonempty_symmetry")+1);
            if(type(b,"WallBoundary"))result.put("nonempty_wall",result.get("nonempty_wall")+1);
            if(open(b))result.put("nonempty_open",result.get("nonempty_open")+1);
        }
        return result;
    }
    private static boolean open(Map<String,Object> b) {return type(b,"FreeStreamBoundary")||type(b,"InletBoundary")||type(b,"PressureBoundary")||type(b,"StagnationBoundary")||type(b,"MassFlowBoundary")||type(b,"OutletBoundary");}
    private static String label(Map<String,Object> b) { return b.getOrDefault("region_name","")+" / "+b.getOrDefault("name","未命名边界"); }
    private static boolean type(Map<String,Object> b,String simple) { return String.valueOf(b.get("boundary_type")).equals("star.common."+simple); }
    private static List<String> strings(Object raw) { List<String> r=new ArrayList<>(); if(raw instanceof Collection)for(Object x:(Collection<?>)raw)r.add(String.valueOf(x));return r; }
    private static boolean containsModel(List<String> models,String simple) { for(String m:models)if(m.endsWith("."+simple)||m.equals(simple))return true;return false; }
    private static boolean hasFlow(List<String> models) { return containsModel(models,"CoupledFlowModel")||containsModel(models,"SegregatedFlowModel"); }
}
