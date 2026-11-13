package local.starccm.assistant;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import star.base.neo.ClientServerObject;
import star.base.neo.DoubleVector;
import star.common.*;
import star.meshing.MeshPart;
import star.material.MaterialProperty;
import star.material.MaterialPropertyMethod;

/** Typed workflows and runtime-verified native API property calls; never executes model-supplied Java source. */
public final class ConfigurationEngine {
    private static final int LIMIT = 6000;
    private final Simulation simulation;
    private final CodexAssistant reader;
    private final WorkflowOperations operations;
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Map<String, String> quantityBounds = new HashMap<>();
    private final List<String> omissions = new ArrayList<>();
    private final List<Map<String, Object>> journal = new ArrayList<>();
    private final List<String> backups = new ArrayList<>();
    private final List<Map<String,Object>> inspections = new ArrayList<>();
    private final Set<String> initialDeletionIds=new LinkedHashSet<>();
    private final Map<String,Boolean> deletedExistence=new LinkedHashMap<>();
    private Map<String,Object> latestPlanningAssessment=Map.of(),knownSavedEngineeringFacts=Map.of();
    private Path savePath, reportBase;
    private String instruction = "", outcome = "planning", note = "";
    private boolean blocked;
    private boolean writableDiscovered;

    private static final class Slot {
        ClientServerObject object;
        String id, path, kind, key = "", unit = "", bounds = "";
        Method getter, setter;
        boolean structural;
        Object expected;
        final Map<String,Object> choices = new LinkedHashMap<>();
    }

    private final List<Map<String,Object>> metrics=new ArrayList<>();

    public ConfigurationEngine(Simulation simulation, CodexAssistant reader) {
        this.simulation = simulation;
        this.reader = reader;
        this.operations = new WorkflowOperations(simulation);
    }

    private long taskStartedNano;
    public void beginTask(String goal, String destination) throws IOException {
        instruction = goal;operations.resetStop();operations.resetApiTask();taskStartedNano=System.nanoTime();
        savePath = Paths.get(destination).toAbsolutePath().normalize();
        Path directory = savePath.getParent().resolve(savePath.getFileName() + ".ai-reports");
        Files.createDirectories(directory);
        reportBase = directory.resolve("change-" + UUID.randomUUID());
        journal.clear(); backups.clear(); inspections.clear();metrics.clear(); blocked = false;slots.clear();quantityBounds.clear();writableDiscovered=false;
        deletedExistence.clear();latestPlanningAssessment=Map.of();initialDeletionIds.clear();if(!explicitDeletionDirective(goal).isEmpty()){
            for(Region object:simulation.getRegionManager().getRegions())initialDeletionIds.add(object.getObjectKeyAsString());
            if(simulation.has(star.meshing.MeshOperationManager.class)!=null)for(star.meshing.MeshOperation object:simulation.get(star.meshing.MeshOperationManager.class).getObjects())initialDeletionIds.add(object.getObjectKeyAsString());
            for(ClientServerObject object:simulation.getReportManager().getObjects())initialDeletionIds.add(object.getObjectKeyAsString());
            for(ClientServerObject object:simulation.getSceneManager().getObjects())initialDeletionIds.add(object.getObjectKeyAsString());
        }
        outcome = "planning"; note = "";
        writeReport(); // Prove report storage is available before allowing a write.
    }

    /** Fast action planning does not enumerate thousands of scalar properties.
     * Numeric configuration remains available on an explicit writable read. */
    public String refreshPlanningCatalog(){
        CodexAssistant.checkConfigurationReadCancelled();slots.clear();omissions.clear();quantityBounds.clear();writableDiscovered=false;operations.refresh();
        return "已刷新实际动作对象与状态。数值属性按需读取；需要标量/选项配置时inspect scope=writable query=参数关键词，不把对象ID当可写参数ID。";
    }

    public String refreshCatalog() {
        CodexAssistant.checkConfigurationReadCancelled();writableDiscovered=false;
        slots.clear(); omissions.clear(); quantityBounds.clear();
        operations.refresh();
        Map<String,ClientServerObject> nodes = new LinkedHashMap<>();
        for (String scope : List.of("physics","boundaries","solvers","mesh","fields","reports")) {
            CodexAssistant.checkConfigurationReadCancelled();
            try { nodes.putAll(reader.configurationTree(scope)); }
            catch (Exception error) { omissions.add(scope + ": " + message(error)); }
        }
        Set<ClientServerObject> visited = new HashSet<>(nodes.values());
        for (ClientServerObject root : List.of(simulation.getGlobalParameterManager(),
                simulation.getSolverStoppingCriterionManager(), simulation.getInterfaceManager(),
                simulation.getSceneManager(), simulation.getPlotManager())) {
            CodexAssistant.collectConfiguration(root, root.getPresentationName(), 0, visited, nodes);
        }
        for (Region region : simulation.getRegionManager().getRegions()) nodes.put("区域 / " + region.getPresentationName(), region);
        for (Map.Entry<String,ClientServerObject> entry : nodes.entrySet()) {
            CodexAssistant.checkConfigurationReadCancelled();
            if (slots.size() >= LIMIT) { omissions.add("可写属性达到 " + LIMIT + " 项上限，后续对象未纳入。"); break; }
            try { discover(entry.getKey(), entry.getValue()); }
            catch (Exception error) { omissions.add(entry.getKey() + ": " + message(error)); }
        }
        for(Slot slot:slots.values()) if(quantityBounds.containsKey(slot.object.getObjectKeyAsString()))
            slot.bounds=quantityBounds.get(slot.object.getObjectKeyAsString());
        writableDiscovered=true;
        return "当前发现可写项目=" + slots.size() + "。实际 API 范围有限；文件路径、任意代码、任意对象创建/删除、几何拓扑和运行求解不在数值目录；对象创建/删除、局部网格控制、报告、连续体、场函数及后处理请查actions目录。\n"
            + "结构项每轮只执行一项，随后重读目录继续；其他项每轮最多80项。\n"
            + "发现限制：" + String.join("; ", omissions.subList(0, Math.min(omissions.size(), 20)));
    }

    private Slot slot(String path, ClientServerObject object, String kind, String key, boolean structural) {
        Slot slot = new Slot(); slot.object = object; slot.path = path; slot.kind = kind; slot.key = key;
        slot.id = "a" + object.getObjectId() + "_" + kind + "_" + key;
        slot.structural = structural;
        if (object instanceof ValueFunction) slot.unit = ((ValueFunction)object).getUnits().getPresentationName();
        return slot;
    }

    private void add(Slot slot) throws Exception {
        if (slots.containsKey(slot.id) || slots.size() >= LIMIT) return;
        slot.expected = read(slot);
        if (slot.expected instanceof Number && !Double.isFinite(((Number)slot.expected).doubleValue())) return;
        slots.put(slot.id,slot);
    }

    private void discover(String path, ClientServerObject object) throws Exception {
        if (object instanceof Units) return; // Do not edit the unit system as a side effect of a parameter change.
        if (object instanceof ScalarPhysicalQuantity) { add(slot(path,object,"scalar","",false)); return; }
        if (object instanceof VectorPhysicalQuantity) { add(slot(path,object,"vector","",false)); return; }
        if (object instanceof IntegerValue) { add(slot(path,object,"integer","",false)); return; }
        if(object instanceof StepStoppingCriterion)add(slot(path+" / 最大迭代步数",((StepStoppingCriterion)object).getMaximumNumberStepsObject(),"integer","",false));
        if(object instanceof FixedStepsStoppingCriterion)add(slot(path+" / 固定步数",((FixedStepsStoppingCriterion)object).getFixedStepsObject(),"integer","",false));
        if (object instanceof EnumeratedOption) { addOption(path,(EnumeratedOption<?>)object); return; }
        if (object instanceof UserFieldFunction) add(slot(path + " / 定义",object,"field","",false));
        if (object instanceof FunctionProfileMethod) {
            FunctionProfileMethod method=(FunctionProfileMethod)object;
            FieldFunction current=method.getFieldFunction();
            Slot reference=slot(path+" / 场函数",object,"field_reference","",false);
            for(FieldFunction candidate:simulation.getFieldFunctionManager().getObjects())
                if(candidate.getTypeAsEnum()==current.getTypeAsEnum()
                        && candidate.getDimensionsVector().equals(current.getDimensionsVector()))
                    reference.choices.put(candidate.getObjectKeyAsString(),candidate);
            if(!reference.choices.isEmpty())add(reference);
        }
        if (object instanceof star.vis.DisplayQuantity) {
            star.vis.DisplayQuantity quantity=(star.vis.DisplayQuantity)object;
            Slot reference=slot(path+" / 显示场函数",object,"display_field_reference","",false);
            for(FieldFunction candidate:simulation.getFieldFunctionManager().getObjects())
                if(quantity.getAvailableFieldFunctionTypes().contains(candidate.getTypeAsEnum()))reference.choices.put(candidate.getObjectKeyAsString(),candidate);
            if(!reference.choices.isEmpty())add(reference);
        }
        if (object instanceof Boundary) {
            Boundary boundary = (Boundary)object;
            if (!boundary.isInternallyManaged() && !boundary.isOwnedByOther() && boundary.getDependentInterfaces().isEmpty()) {
                Slot type = slot(path + " / 边界类型",object,"boundary","",true);
                for (ConditionType value : boundary.getAvailableTypes())
                    if (value instanceof BoundaryType) type.choices.put(value.getClass().getName(),value);
                if (!type.choices.isEmpty()) add(type);
            } else omissions.add(path + " 的类型由接口或其他对象管理，不开放独立切换。");
        }
        if (object instanceof Region) {
            Slot continuum = slot(path + " / 物理连续体",object,"continuum","",true);
            for (Continuum c : simulation.getContinuumManager().getObjects())
                if (c instanceof PhysicsContinuum) continuum.choices.put(c.getObjectKeyAsString(),c);
            if (!continuum.choices.isEmpty()) add(continuum);
        }
        if (object instanceof PhysicsContinuum) {
            PhysicsContinuum continuum = (PhysicsContinuum)object;
            Set<String> candidates = new TreeSet<>();
            candidates.addAll(continuum.getEnabledModels().getKeys());
            for (star.base.neo.NeoProperty available : List.of(continuum.getRequiredModels(),continuum.getOptionalModels()))
                for (String key : available.getKeys()) {
                    Object values = available.get(key);
                    if (values instanceof Collection) for (Object value : (Collection<?>)values)
                        if (value instanceof String && ((String)value).startsWith("star.")) candidates.add((String)value);
                }
            for (String model : candidates) add(slot(path + " / 物理模型 / " + model,object,"model",model,true));
        }
        if (object instanceof Profile) {
            Slot method = slot(path + " / 取值方法",object,"profile","",true);
            for (ProfileMethod choice : ((Profile)object).getMethods()) method.choices.put(choice.getClass().getName(),choice);
            if (!method.choices.isEmpty()) add(method);
        }
        if (object instanceof MaterialProperty) {
            Slot method = slot(path + " / 材料方法",object,"material","",true);
            // getObjects() deliberately exposes only the active method. The
            // STAR property editor uses getMethods() for its actual choices.
            Method available=MaterialProperty.class.getDeclaredMethod("getMethods");
            available.setAccessible(true);
            for (Object item : (Collection<?>)available.invoke(object)) {
                MaterialPropertyMethod choice=(MaterialPropertyMethod)item;
                method.choices.put(choice.getClass().getName(),choice);
            }
            if (!method.choices.isEmpty()) add(method);
        }
        for (PropertyDescriptor property : object.getBeanPropertyDescriptors()) {
            CodexAssistant.checkConfigurationReadCancelled();
            Method get = property.getReadMethod(), set = property.getWriteMethod();
            if (get == null || set == null || property.isHidden() || get.getParameterCount()!=0 || set.getParameterCount()!=1) continue;
            String name = property.getName();
            if (name.matches("(?i).*(executeonok|internal|selected|selection|editing|beingedited|file|path|command|script|license|journal|tag|presentationname|functionname|syncname).*$")
                    || name.matches("(?i)(id|index|objectid|objectkey)")) continue;
            Class<?> type = property.getPropertyType();
            try {
                if(CompiledValueFunctionInput.class.isAssignableFrom(type)) {
                    Object range=property.getValue("StarBeanBounded.Range");
                    if(range!=null) {
                        CompiledValueFunctionInput<?> input=(CompiledValueFunctionInput<?>)get.invoke(object);
                        if(input!=null)quantityBounds.put(input.getQuantity().getObjectKeyAsString(),range.toString());
                    }
                    continue;
                }
                if (EnumeratedOptionInput.class.isAssignableFrom(type)) {
                    EnumeratedOptionInput<?> option = (EnumeratedOptionInput<?>)get.invoke(object);
                    if (option!=null) addOption(path + " / " + property.getDisplayName(),option.getEnumeratedOption());
                    continue;
                }
                if (!(type==boolean.class || type==int.class || type==long.class || type==double.class || type==float.class || type.isEnum())) continue;
                Slot bean = slot(path + " / " + property.getDisplayName(),object,"bean",name,type==boolean.class || type.isEnum());
                bean.getter = get; bean.setter = set;
                Object range = property.getValue("StarBeanBounded.Range");
                if (range!=null) bean.bounds=range.toString();
                if (type.isEnum()) for (Object choice : type.getEnumConstants()) bean.choices.put(((Enum<?>)choice).name(),choice);
                add(bean);
            } catch (Exception error) { omissions.add(path + " / " + name + ": " + message(error)); }
        }
    }

    private void addOption(String path, EnumeratedOption<?> option) throws Exception {
        Slot slot = slot(path,option,"option","",true);
        for (int code : option.getOptions()) slot.choices.put(option.getSymbol(code),code);
        if (!slot.choices.isEmpty()) add(slot);
    }

    public String describe(String query, int offset) {
        if(query.startsWith("state:")||operations.isObjectId(query))return "此query是动作对象ID或状态，不是writable参数ID。请inspect scope=operations query="+query+"读取该对象真实动作和id/expected_state；如需数值属性，请用属性名称/类型关键词查询writable（参数ID形如a..._scalar_...）。尚未为错误路由启动完整数值扫描。";
        if(!writableDiscovered)refreshCatalog();
        List<Slot> selected = new ArrayList<>();
        String[] terms = query.toLowerCase(Locale.ROOT).split("[\\s,，;/]+",20);
        for (Slot slot : slots.values()) {
            String text = (slot.path + " " + slot.id + " " + slot.object.getClass().getName()).toLowerCase(Locale.ROOT);
            if (query.equals("*") || Arrays.stream(slot.path.split(" / ")).anyMatch(p -> p.length()>2 && query.toLowerCase(Locale.ROOT).contains(p.toLowerCase(Locale.ROOT))) || Arrays.stream(terms).anyMatch(t -> !t.isBlank() && text.contains(t))) selected.add(slot);
        }
        // Specific requested paths/IDs come before broad parent-name matches.
        if(!query.equals("*"))selected.sort(java.util.Comparator.comparingInt(slot ->
            (slot.path+" "+slot.id+" "+slot.object.getClass().getName()).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))?0:1));
        StringBuilder out = new StringBuilder("可写目录 matched=" + selected.size() + " offset=" + offset + "\n");
        for (int i=offset; i<Math.min(selected.size(),offset+60);i++) out.append(json(metadata(selected.get(i)))).append('\n');
        if (offset+60<selected.size()) out.append("继续 inspect scope=writable query=").append(query).append(" offset=").append(offset+60);
        return out.toString();
    }

    public String initialCatalog(String query) {
        // Include an index of all paths/IDs, followed by paged values. Tools supply omitted details.
        StringBuilder out = new StringBuilder();
        int characters = 0;
        for (Slot slot : slots.values()) {
            String line = slot.id + " " + slot.path + "\n";
            if (characters + line.length() > 6000) { out.append("索引截断；使用 writable 工具按关键词查找。\n"); break; }
            out.append(line); characters+=line.length();
        }
        return operations.actionIndex()+"\n实际动作对象优先列出；支持动作="+new TreeSet<>(SimulationActions.TYPES)+"。完整签名可inspect scope=action_help query=动作名；可写参数用writable按关键词分页读取。\n"+operations.inspect("*",0,true)+"\n"+out+"\n"+(writableDiscovered?describe(query,0):"数值属性尚未扫描；需要时inspect scope=writable query=参数关键词。动作对象用operations，不使用writable查对象ID。")+"\n"+operations.contract();
    }

    public String actionHelp(String query){return operations.actionHelp(query);}

    private Map<String,Object> metadata(Slot slot) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id",slot.id); out.put("parameter",slot.path); out.put("kind",slot.kind);
        out.put("object_type",slot.object.getClass().getName());
        out.put("value",slot.expected); out.put("unit",slot.unit); out.put("structural",slot.structural);
        if (!slot.bounds.isEmpty()) out.put("range",slot.bounds);
        if (!slot.choices.isEmpty()) {
            Map<String,String> choices=new LinkedHashMap<>();
            slot.choices.forEach((key,value)->choices.put(key,value instanceof ClientServerObject ? ((ClientServerObject)value).getPresentationName() : key));
            out.put("choices",choices);
        }
        return out;
    }

    private Object read(Slot slot) throws Exception {
        ClientServerObject target=slot.object;
        switch(slot.kind) {
            case "scalar":
                ScalarPhysicalQuantity scalar=(ScalarPhysicalQuantity)target;
                return scalar.getIsExpression() ? scalar.getDefinition() : scalar.getValue();
            case "vector":
                VectorPhysicalQuantity vector=(VectorPhysicalQuantity)target;
                if(vector.getIsExpression()) return vector.getDefinition();
                List<Double> values=new ArrayList<>();
                for(int i=0;i<vector.getVector().size();i++) values.add(vector.getVector().getComponent(i));
                return values;
            case "integer":
                IntegerValue integer=(IntegerValue)target;
                return integer.getQuantity().getIsExpression() ? integer.getDefinition() : integer.getValue();
            case "field": return ((UserFieldFunction)target).getDefinition();
            case "display_field_reference": return ((star.vis.DisplayQuantity)target).getFieldFunction().getObjectKeyAsString();
            case "field_reference": return ((FunctionProfileMethod)target).getFieldFunction().getObjectKeyAsString();
            case "option": return ((EnumeratedOption<?>)target).getSymbol(((EnumeratedOption<?>)target).getSelected());
            case "boundary": return ((Boundary)target).getBoundaryType().getClass().getName();
            case "continuum": return ((Region)target).getPhysicsContinuum()==null ? "" : ((Region)target).getPhysicsContinuum().getObjectKeyAsString();
            case "profile": return ((Profile)target).getMethod().getClass().getName();
            case "material": return ((MaterialProperty)target).getMethod().getClass().getName();
            case "model": return ((PhysicsContinuum)target).getEnabledModels().containsKey(slot.key);
            case "bean": Object value=slot.getter.invoke(target); return value instanceof Enum ? ((Enum<?>)value).name() : value;
            default: throw new IOException("未知操作类型");
        }
    }

    private Object readFresh(Slot slot) throws Exception {
        simulation.getServer().getClientNotifyHandler().processCommandQueue(true);
        // Native GUI property notifications may still be queued on the event
        // thread. Invalidate this proxy through STAR's public notification API
        // before read-back, rather than treating a cached value as server state.
        star.base.neo.NeoProperty changed=new star.base.neo.NeoProperty();
        changed.put("property", "");
        slot.object.firePropertyChange(changed);
        return read(slot);
    }

    private void validateValue(Slot slot,Object value) throws Exception {
        if(!slot.choices.isEmpty() && (!(value instanceof String) || !slot.choices.containsKey(value)))
            throw new IOException("只能选择目录给出的选项："+slot.path);
        if(slot.kind.equals("scalar") || slot.kind.equals("vector")) {
            if(value instanceof String) {
                if(((String)value).isBlank() || ((String)value).length()>8000) throw new IOException("表达式为空或过长");
                String error=((CompiledValueFunction)slot.object).validatePreviewExpression((String)value);
                if(error!=null && !error.isBlank()) throw new IOException("表达式预检失败："+error);
            } else if(slot.kind.equals("scalar")) { finite(value); if(!slot.bounds.isBlank())validateBounds(slot,finite(value)); }
            else {
                if(!(value instanceof List) || ((List<?>)value).size()!=((VectorPhysicalQuantity)slot.object).getVector().size())
                    throw new IOException("矢量长度必须与当前对象一致");
                for(Object component:(List<?>)value) finite(component);
            }
        } else if(slot.kind.equals("integer")) {
            if(value instanceof String) {
                if(((String)value).isBlank() || ((String)value).length()>8000)throw new IOException("整数表达式为空或过长");
                String error=((IntegerValue)slot.object).getQuantity().validatePreviewExpression((String)value);
                if(error!=null && !error.isBlank())throw new IOException("整数表达式预检失败："+error);
            } else integral(value,1,Integer.MAX_VALUE);
        }
        else if(slot.kind.equals("field")) {
            if(!(value instanceof String) || ((String)value).isBlank() || ((String)value).length()>8000) throw new IOException("场函数定义必须是非空字符串（最多8000字）");
        } else if(slot.kind.equals("model")) {
            if(!(value instanceof Boolean)) throw new IOException("模型启停值必须为布尔值");
            PhysicsProfiles.requireRequestedModelChange(slot.key,Boolean.TRUE.equals(value),instruction,(PhysicsContinuum)slot.object);
            if(Boolean.TRUE.equals(value) && !((PhysicsContinuum)slot.object).isModelCompatible(slot.key))
                throw new IOException("当前模型依赖或兼容性不满足："+slot.key+"。先规划依赖变更，再重新读取。");
        } else if(slot.kind.equals("bean")) {
            Class<?> type=slot.setter.getParameterTypes()[0];
            if(type==boolean.class && !(value instanceof Boolean)) throw new IOException("开关必须是布尔值");
            else if(type==int.class) integral(value,Integer.MIN_VALUE,Integer.MAX_VALUE);
            else if(type==long.class) integral(value,-9007199254740991L,9007199254740991L);
            else if(type==double.class || type==float.class) { double x=finite(value); if(type==float.class && !Float.isFinite((float)x)) throw new IOException("超出浮点范围"); }
            if(slot.object.getClass().getName().equals("star.coupledflow.AutomaticCourantNumberControl") && slot.key.matches("CFL(Initial|Minimum|Maximum)Value")) {
                double x=finite(value);if(x<=0 || x>1e12)throw new IOException("CFL必须在(0,1e12]内");
            }
            if(slot.object instanceof AMGLinearSolver && slot.key.equals("ConvergeTol")) {
                double x=finite(value);if(x<=0 || x>1)throw new IOException("AMG收敛容差必须在(0,1]内");
            }
            if(value instanceof Number && !slot.bounds.isBlank()) validateBounds(slot,finite(value));
        }
    }

    private void write(Slot slot,Object value) throws Exception {
        ClientServerObject target=slot.object;
        switch(slot.kind) {
            case "scalar":
                ScalarPhysicalQuantity scalar=(ScalarPhysicalQuantity)target;
                if(value instanceof String) scalar.setDefinitionAndUnits((String)value,scalar.getUnits());
                else scalar.setValueAndUnits(((Number)value).doubleValue(),scalar.getUnits()); return;
            case "vector":
                VectorPhysicalQuantity vector=(VectorPhysicalQuantity)target;
                if(value instanceof String) vector.setDefinitionAndUnits((String)value,vector.getUnits());
                else {
                    List<?> components=(List<?>)value; double[] a=new double[components.size()];
                    for(int i=0;i<a.length;i++) a[i]=((Number)components.get(i)).doubleValue();
                    vector.setVectorAndUnits(new DoubleVector(a),vector.getUnits());
                } return;
            case "integer":
                if(value instanceof String)((IntegerValue)target).setDefinition((String)value);
                else ((IntegerValue)target).setValue(((Number)value).intValue()); return;
            case "field": ((UserFieldFunction)target).setDefinition((String)value); return;
            case "display_field_reference": ((star.vis.DisplayQuantity)target).setFieldFunction((FieldFunction)slot.choices.get(value)); return;
            case "field_reference": ((FunctionProfileMethod)target).setFieldFunction((FieldFunction)slot.choices.get(value)); return;
            case "option": ((EnumeratedOption<?>)target).setSelected((Integer)slot.choices.get(value)); return;
            case "boundary": ((Boundary)target).setBoundaryType((BoundaryType)slot.choices.get(value)); return;
            case "continuum": ((Region)target).setPhysicsContinuum((PhysicsContinuum)slot.choices.get(value)); return;
            case "profile": ((Profile)target).setMethod((ProfileMethod)slot.choices.get(value)); return;
            case "material": ((MaterialProperty)target).setMethodType((MaterialPropertyMethod)slot.choices.get(value)); return;
            case "model":
                PhysicsContinuum continuum=(PhysicsContinuum)target;
                if(Boolean.TRUE.equals(value)) continuum.enable(slot.key); else continuum.disable(slot.key); return;
            case "bean":
                Class<?> type=slot.setter.getParameterTypes()[0]; Object converted=value;
                if(type==int.class) converted=((Number)value).intValue();
                else if(type==long.class) converted=((Number)value).longValue();
                else if(type==float.class) converted=((Number)value).floatValue();
                else if(type==double.class) converted=((Number)value).doubleValue();
                else if(type.isEnum()) converted=slot.choices.get(value);
                slot.setter.invoke(target,converted); return;
            default: throw new IOException("未知操作类型");
        }
    }

    private List<Map<?,?>> changes(String response) throws Exception {
        Object parsed=CodexAssistant.parseJson(CodexAssistant.planJson(response));
        if(!(parsed instanceof Map)) throw new IOException("方案必须是JSON对象");
        Map<?,?> plan=(Map<?,?>)parsed;
        Object raw=plan.get("changes");
        if(!(raw instanceof List) || ((List<?>)raw).size()>80) throw new IOException("changes必须是最多80项的数组");
        if(((List<?>)raw).isEmpty())return List.of();
        if(!writableDiscovered)refreshCatalog();
        List<Map<?,?>> result=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(Object item:(List<?>)raw) {
            if(!(item instanceof Map)) throw new IOException("修改项目必须是对象");
            Map<?,?> change=(Map<?,?>)item; Object id=change.get("id");
            if(!(id instanceof String) || !slots.containsKey(id) || !seen.add((String)id)) throw new IOException("未知或重复参数ID："+id);
            Slot slot=slots.get(id);
            if(!(change.get("reason") instanceof String) || ((String)change.get("reason")).isBlank()) throw new IOException("每项必须包含修改原因");
            if(!slot.unit.equals(change.get("unit"))) throw new IOException("单位必须与当前目录一致："+slot.path);
            Object liveValue=readFresh(slot);
            if(!equal(slot.expected,change.get("expected_value")) || !equal(liveValue,slot.expected)){
                Map<String,Object> mismatch=new LinkedHashMap<>();mismatch.put("id",slot.id);mismatch.put("parameter",slot.path);mismatch.put("submitted_expected_value",change.get("expected_value"));mismatch.put("catalog_value",slot.expected);mismatch.put("live_value",liveValue);mismatch.put("unit",slot.unit);mismatch.put("guidance","重新读取该writable参数，确认当前值与目标后再规划；不能复用其他参数的旧值或对象state令牌");throw new IOException("旧值不匹配或已变化："+json(mismatch));
            }
            if(slot.object instanceof ValueFunction && !slot.unit.equals(((ValueFunction)slot.object).getUnits().getPresentationName())) throw new IOException("单位已变化："+slot.path);
            validateValue(slot,change.get("value"));
            for(Map<String,Object> prior:journal)if("saved".equals(prior.get("status"))&&slot.id.equals(prior.get("id"))&&!equal(slot.expected,change.get("value"))&&equal(prior.get("actual"),change.get("value")))throw new IOException("重复试探：同一任务已写入过此参数值；先说明新诊断依据，不能反复切换。");
            if(!equal(slot.expected,change.get("value"))) result.add(change);
        }
        return result;
    }

    public String preview(String response) throws Exception {
        // A malformed repeat may omit required native arguments. Recognize only
        // an already-saved identical/subset transaction before schema validation,
        // returning its real completion proof rather than inviting re-execution.
        Object planned=CodexAssistant.parseJson(CodexAssistant.planJson(response));
        if(planned instanceof Map&&((Map<?,?>)planned).get("actions") instanceof List){List<?> actions=(List<?>)((Map<?,?>)planned).get("actions");if(actions.size()==1&&actions.get(0) instanceof Map)validateWorkflowReplay((Map<?,?>)actions.get(0));}
        Map<?,?> action=operations.action(response,instruction);
        if(action!=null){validateJournalAction(action);return operations.preview(action);}
        StringBuilder out=new StringBuilder();
        for(Map<?,?> change:changes(response)) {
            Slot slot=slots.get(change.get("id"));
            out.append(slot.path).append("\n当前值：").append(json(slot.expected)).append("\n建议值：")
                .append(json(change.get("value"))).append(' ').append(slot.unit).append("\n原因：").append(change.get("reason"))
                .append(slot.structural ? "\n结构变更：执行后刷新目录继续规划。" : "").append("\n\n");
        }
        return out.toString();
    }

    public Map<String,Object> apply(String response) throws Exception {
        if(blocked) throw new IOException("上轮失败，需要处理恢复状态后重新开始任务。");
        if(!simulation.isValid() || simulation.isBusy()
                || (simulation.isMeshing() && simulation.get(star.meshing.MeshPipelineController.class).isGeneratingMesh())) throw new IOException("仿真正在计算/划网格或已关闭，未修改。valid="+simulation.isValid()+", busy="+simulation.isBusy());
        // STAR 19.04's setters use executeAndWait
        // in this synchronous command scope. No macro file is generated or played.
        // Restore the per-connection flag even when validation or saving fails.
        star.base.neo.NeoClient connection=simulation.getServer().getServerProxy();
        boolean previous=connection.isMacroPlaying();
        connection.setMacroPlaying(true);
        try { return applySynchronous(response); }
        finally { connection.setMacroPlaying(previous); }
    }

    private Map<String,Object> applySynchronous(String response) throws Exception {
        Map<?,?> action=operations.action(response,instruction);
        if(action!=null)return applyAction(action);
        List<Map<?,?>> planned=changes(response);
        if(planned.isEmpty()) return finish("没有新的可执行修改。",false);
        // Structural setters can recreate children. Execute one, then replan from fresh objects.
        boolean structural=false;
        for(Map<?,?> change:planned) if(slots.get(change.get("id")).structural) {
            planned=List.of(change); structural=true; break;
        }
        Path backup=checkpoint();
        List<Map<String,Object>> applied=new ArrayList<>();
        List<Slot> attempted=new ArrayList<>();
        boolean saved=false;
        try {
            for(Map<?,?> change:planned) {
                Slot slot=slots.get(change.get("id"));
                if(!equal(readFresh(slot),slot.expected)) throw new IOException("写前值变化："+slot.path);
                Map<String,Object> row=new LinkedHashMap<>(metadata(slot));
                row.put("before",slot.expected); row.put("recommended",change.get("value")); row.put("reason",change.get("reason"));
                row.put("status","attempted"); journal.add(row); applied.add(row); attempted.add(slot);
                write(slot,change.get("value"));
                Object actual=readFresh(slot); row.put("actual",actual);
                boolean matches=equal(actual,change.get("value"));
                if(change.get("value") instanceof String && slot.object instanceof CompiledValueFunction)
                    matches=((CompiledValueFunction)slot.object).getDefinition().trim().equals(((String)change.get("value")).trim());
                if(change.get("value") instanceof String && slot.object instanceof IntegerValue)
                    matches=((IntegerValue)slot.object).getDefinition().trim().equals(((String)change.get("value")).trim());
                if(!matches) throw new IOException("写后读回不一致："+slot.path+"，读回="+json(actual));
                row.put("status","verified_in_memory");
                if(slot.kind.equals("field")) row.put("validation","仅校验定义读回；场函数在实际模型中的求值未验证");
            }
            simulation.saveState(savePath.toString());
            saved=true;
            for(Map<String,Object> row:applied) row.put("status","saved");
            latestPlanningAssessment=Map.of();deletedExistence.clear();
            outcome=structural ? "continuing" : "applied";
            note=structural ? "已更新并保存设置，继续处理剩余目标。" : "已执行并保存"+applied.size()+"项修改。";
            writeReport();
            StringBuilder details=new StringBuilder(note);
            for(Map<String,Object> row:applied)details.append("\n").append(row.get("parameter")).append("：")
                .append(json(row.get("before"))).append(" → ").append(json(row.get("actual"))).append(' ').append(row.get("unit")).append("（已读回并保存）");
            return Map.of("continue",structural,"summary",details.toString(),"progress",true,"report",reportBase+".md");
        } catch(Exception error) {
            blocked=true; outcome="failed";
            if(saved) {
                note="配置已保存，但报告写入失败："+message(error)+"。恢复副本："+backup;
                return Map.of("continue",false,"failed",true,"summary",note,"report",reportBase+".md");
            }
            if(!structural) {
                for(int i=attempted.size()-1;i>=0;i--) {
                    try {
                        write(attempted.get(i),attempted.get(i).expected);
                        Object restored=readFresh(attempted.get(i));
                        applied.get(i).put("attempted_readback",applied.get(i).get("actual"));
                        applied.get(i).put("actual",restored);
                        if(!equal(restored,attempted.get(i).expected)) throw new IOException("回滚读回不一致");
                        applied.get(i).put("status","rolled_back");
                    } catch(Exception rollback) { applied.get(i).put("status","rollback_failed"); applied.get(i).put("rollback_error",message(rollback)); }
                }
            } else for(Map<String,Object> row:applied) row.put("status","requires_restore");
            note="执行失败："+message(error)+"。恢复副本："+backup
                +"。停止后续自动操作；结构变更或保存失败可能影响当前状态，请关闭且不保存，再打开恢复副本核对。";
            try { writeReport(); } catch(Exception reportError) { note+="；报告写入失败："+message(reportError); }
            return Map.of("continue",false,"failed",true,"summary",note,"report",reportBase+".md");
        }
    }

    public String inspectApi(String request)throws Exception{return operations.inspectApi((Map<?,?>)CodexAssistant.parseJson(request));}
    public String searchApi(String query,int offset,int limit){return json(ApiDocumentation.search(query,offset,limit));}
    public String inspectApiClass(String className,String query,int offset,int limit){return json(ApiDocumentation.classInfo(className,query,offset,limit));}
    public String inspectGeometry(String query,int offset) { operations.refresh(); return operations.inspectGeometry(query,offset); }
    public String geometryContext(){operations.refresh();return operations.geometryContext();}
    public String cadModels(){return json(CadPartCreation.inventory(simulation));}
    public String engineeringPolicy(){return EngineeringPolicy.TEXT;}
    public String inspectionJson(Map<?,?> value){return json(value);}
    public String engineeringContext(String goal){try{return json(RecommendationContext.inspect(simulation,Map.of("query","*"),goal));}catch(Exception e){return json(Map.of("read_error",e.toString(),"policy","经验建议读取失败，不得把未知值当用户指定；可用recommendations明确输入"));}}
    public String inspectRecommendations(String request)throws Exception{return json(RecommendationContext.inspect(simulation,(Map<?,?>)CodexAssistant.parseJson(request),instruction));}
    public String readiness(){latestPlanningAssessment=deletionOnlyGoal(instruction)||!explicitDeletionDirective(instruction).isEmpty()?assessCompletion():currentReadiness(completionTargets());return json(latestPlanningAssessment);}
    public void recordMetric(Map<?,?> data)throws IOException{if(reportBase==null||metrics.size()>=300)return;Map<String,Object> row=new LinkedHashMap<>();data.forEach((k,v)->row.put(String.valueOf(k),v));metrics.add(row);writeReport();}
    public String inspectMeshQuality(String axis,Double plane){return json(MeshQualitySnapshot.inspect(simulation,axis,plane));}
    public String inspectSection(String query,String axis,double plane,String projection){operations.refresh();return operations.inspectSection(query,axis,plane,projection);}
    public void recordInspection(String scope,String query,int offset,String result)throws IOException {
        if(!hasActiveTask()||inspections.size()>=200)return;
        inspections.add(new LinkedHashMap<>(Map.of("scope",scope,"query",query,"offset",offset,"result",result)));writeReport();
    }

    public String inspectRelations(String query,int offset) {
        operations.refresh();String result=operations.inspect(query,offset,false);
        return result.contains("共0项") ? operations.inspect("*",offset,false) : result;
    }
    public String inspectOperations(String query,int offset) {
        operations.refresh();return operations.inspect(query,offset,true);
    }
    public boolean isLongOperation(String response)throws Exception {
        Map<?,?> action=operations.action(response,instruction);
        return action!=null && (ExtendedActions.TYPES.contains(action.get("type")) || CommonActions060.TYPES.contains(action.get("type")) || AerodynamicReports.TYPES.contains(action.get("type")) || "invoke_api".equals(action.get("type")) || NativeGeometryRepair.TYPES.contains(action.get("type")) || Set.of("execute_mesh","create_block","import_cad","create_volume_mesh","create_subtract","create_regions","execute_boolean","set_part_selection","run_solver","delete_object","update_block","rename_object","split_surfaces","set_prism_mesher","configure_prism_layers","configure_mesh_defaults","configure_boolean","configure_physics_profile","configure_external_physics","configure_flow_conditions","prepare_external_aero","repair_external_aero","create_parts_from_cad").contains(action.get("type")));
    }
    public boolean abortOperation() { return operations.abort(); }
    private Path checkpoint()throws IOException{if(!backups.isEmpty())return Path.of(backups.get(0));Path backup=reportBase.resolveSibling(reportBase.getFileName()+"-before-1.sim");simulation.saveState(savePath.toString());Files.copy(savePath,backup);backups.add(backup.toString());return backup;}
    private static boolean externalWorkflow(Object kind){return "prepare_external_aero".equals(kind)||"repair_external_aero".equals(kind);}

    static boolean completedWorkflowReplayCandidate(Map<?,?> action,Map<?,?> row){
        if(!externalWorkflow(action.get("type"))||!"saved".equals(row.get("status"))||!externalWorkflow(row.get("kind"))||!(action.get("id") instanceof String)||!Objects.equals(action.get("id"),row.get("id"))||!(row.get("actual") instanceof Map)||!(row.get("recommended") instanceof Map))return false;
        Map<?,?> previous=(Map<?,?>)row.get("recommended"),actual=(Map<?,?>)row.get("actual");
        for(Map.Entry<?,?> entry:action.entrySet()){
            String key=String.valueOf(entry.getKey());if(Set.of("type","id","expected_state","expected_states","reason","mode").contains(key))continue;
            Object before=previous.containsKey(key)?previous.get(key):actual.get(key);
            if(!previous.containsKey(key)&&!actual.containsKey(key)||!equal(before,entry.getValue()))return false;
        }
        return true;
    }

    static Map<String,Object> completedWorkflowReplayConflict(Map<?,?> action,Map<?,?> row,Map<?,?> completion){
        if(!completedWorkflowReplayCandidate(action,row)||!Boolean.TRUE.equals(completion.get("ready"))||Boolean.TRUE.equals(completion.get("blocked")))return Map.of();
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("guard","completed_workflow_replay");evidence.put("saved_workflow",row.get("kind"));evidence.put("target_id",action.get("id"));evidence.put("actual",historyActual((Map<?,?>)row.get("actual")));
        Map<String,Object> verified=new LinkedHashMap<>();for(String key:List.of("ready","complete","blocked","incomplete_reasons","target_scope"))if(completion.containsKey(key))verified.put(key,completion.get(key));
        if(completion.get("readiness") instanceof Map){Map<?,?> readiness=(Map<?,?>)completion.get("readiness");Map<String,Object> current=new LinkedHashMap<>();for(String key:List.of("requested_configuration_items_present","boundary_counts","inspection_errors","evaluated_region_ids","goal_requirements"))if(readiness.containsKey(key))current.put(key,readiness.get(key));verified.put("readiness",current);}evidence.put("verified_completion",verified);
        evidence.put("guidance","这是本轮已经执行并保存的原生外流场事务，当前目标链再次原生检查通过；回执的domain_mode、domain_bounds_m和实际对称边界是修改后的现状，用户原始问题描述的是修改前状态。不得把相同请求或仅id/reason的空参数动作再次执行；原因文字不能替代动作参数。请依据本回执仅处理其余尚未执行的用户要求；全部已完成时返回complete=true且空操作。若确有新诊断须更改，先读当前目标并明确提供不同的实际参数。新用户任务不受本轮保护限制。");return evidence;
    }

    static Map<?,?> completedWorkflowReplaySource(Map<?,?> action,List<? extends Map<?,?>> entries){
        // A later saved mutation may have intentionally changed this result.
        // Never use an older receipt to veto such a correction or a reversal.
        for(int index=entries.size()-1;index>=0;index--){Map<?,?> row=entries.get(index);if("saved".equals(row.get("status")))return completedWorkflowReplayCandidate(action,row)?row:Map.of();}return Map.of();
    }

    private void validateWorkflowReplay(Map<?,?> action)throws IOException{
        if(!externalWorkflow(action.get("type")))return;
        Map<?,?> prior=completedWorkflowReplaySource(action,journal);if(prior.isEmpty())return;
        Map<String,Set<String>> targets=receiptTargets((Map<?,?>)prior.get("actual"));if(targets.get("region_ids").isEmpty()&&targets.get("mesh_ids").isEmpty())return;
        Map<String,Object> conflict=completedWorkflowReplayConflict(action,prior,assessCompletion(targets));
        if(!conflict.isEmpty()){String evidence=json(conflict);recordInspection("protected_completed_workflow_replay",String.valueOf(action.get("id")),0,evidence);throw new IOException("本轮外流场已完成并通过原生检查，未重复执行相同重建："+evidence);}
    }

    /** Only outputs actually created by a successful native workflow in this
     * request are protected. A new user request clears this journal; preexisting
     * objects and outputs whose current postconditions fail remain editable. */
    static Map<String,Object> completedWorkflowDeletionConflict(Map<?,?> action,List<? extends Map<?,?>> entries,Map<?,?> completion){
        if(!"delete_object".equals(action.get("type"))||!(action.get("id") instanceof String)||!Boolean.TRUE.equals(completion.get("ready"))||Boolean.TRUE.equals(completion.get("blocked")))return Map.of();
        String id=action.get("id").toString();
        for(int index=entries.size()-1;index>=0;index--){Map<?,?> row=entries.get(index);
            if(!"saved".equals(row.get("status"))||!externalWorkflow(row.get("kind"))||!(row.get("created_output_ids") instanceof Collection)||!((Collection<?>)row.get("created_output_ids")).contains(id)||!(row.get("actual") instanceof Map))continue;
            Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("guard","completed_workflow_output");evidence.put("target_id",id);evidence.put("saved_workflow",row.get("kind"));
            evidence.put("actual",historyActual((Map<?,?>)row.get("actual")));
            Map<String,Object> verified=new LinkedHashMap<>();for(String key:List.of("ready","complete","blocked","incomplete_reasons","target_scope"))if(completion.containsKey(key))verified.put(key,completion.get(key));evidence.put("verified_completion",verified);
            evidence.put("guidance","该对象是本轮原生创建/重建后已保存的新输出，不是尚待删除的旧对象；当前原生完成项已满足。不要重复删除并重做已经完成的事务。请依据回执仅处理其余尚未执行的用户要求；全部要求已完成时返回complete=true且空操作。这不是接口缺失或权限问题。后续新的用户删除指令会建立新任务，不受本轮保护限制。");return evidence;
        }return Map.of();
    }

    private Set<String> workflowOutputInventory(){
        Set<String> ids=new LinkedHashSet<>();
        List<GeometryPart> parts=new ArrayList<>(simulation.get(SimulationPartManager.class).getParts());for(GeometryPart leaf:simulation.get(SimulationPartManager.class).getLeafParts())if(!parts.contains(leaf))parts.add(leaf);
        for(GeometryPart part:parts){ids.add(part.getObjectKeyAsString());if(part instanceof MeshPart)for(PartSurface surface:((MeshPart)part).getPartSurfaceManager().getObjects())ids.add(surface.getObjectKeyAsString());}
        for(Region region:simulation.getRegionManager().getRegions()){ids.add(region.getObjectKeyAsString());for(Boundary boundary:region.getBoundaryManager().getBoundaries())ids.add(boundary.getObjectKeyAsString());}
        for(Continuum continuum:simulation.getContinuumManager().getObjects())ids.add(continuum.getObjectKeyAsString());
        if(simulation.has(star.meshing.MeshOperationManager.class)!=null)for(star.meshing.MeshOperation operation:simulation.get(star.meshing.MeshOperationManager.class).getObjects()){ids.add(operation.getObjectKeyAsString());if(operation instanceof star.meshing.AutoMeshOperation)for(star.meshing.CustomMeshControl control:((star.meshing.AutoMeshOperation)operation).getCustomMeshControls().getObjects())ids.add(control.getObjectKeyAsString());}
        return ids;
    }

    static boolean originalCadDeletionAuthorized(String goal,String targetName){
        if(goal==null||targetName==null||targetName.isBlank())return false;
        String latest=CodexAssistant.currentUserDirective(goal);
        if(latest.matches("(?is).*[?？].*")||latest.matches("(?is).*(?:如果|假如|若要|如需|是否|能否|可否|\\b(?:if|unless|whether|should)\\b).*"))return false;
        String named=java.util.regex.Pattern.quote(targetName);
        java.util.regex.Pattern reference=java.util.regex.Pattern.compile(named+"(?![A-Za-z0-9_])");
        String prefix="(?i:(?:(?:请你|请|帮我|麻烦你|现在|本次|本轮|直接|先|同时|再|please|now)\\s*)*(?:删除|删掉|删去|移除|清除|delete|remove|erase)\\s*(?:(?:当前|这个|该|原始\\s*CAD|原\\s*CAD|源\\s*CAD|CAD\\s*部件|CAD|几何部件|部件|实体|几何|模型|名为|名称为|名称是|叫做|the|source\\s+CAD|original\\s+CAD|CAD\\s+part|geometry|part|named|called)\\s*)*)";
        String exact="(?:"+named+"|\""+named+"\"|'"+named+"'|“"+named+"”|‘"+named+"’)";
        // No owner/genitive fluid-domain or mesh suffix is accepted: Body 1
        // must be the object removed, not the owner of another deletion target.
        String suffix="(?i:\\s*(?:的\\s*)?(?:原始\\s*CAD|原\\s*CAD|源\\s*CAD|CAD\\s*部件|CAD|几何部件|部件|实体|几何|模型|source\\s+CAD|original\\s+CAD|CAD\\s+part|geometry|part)?\\s*[.]?)";
        boolean positive=false;
        for(String raw:deletionClauses(goal)){String part=raw.trim();if(part.isEmpty())continue;
            boolean protectedClause=negativeDeletionClause(part)&&mentionsDeletion(part)||part.matches("(?is).*(?:保留|保持.{0,80}不变|保护|勿动|不要动|不得删除|不要删除|不删除|\\bpreserve\\b|\\bkeep\\b|\\bretain\\b).*" );
            if(protectedClause){boolean sourceCad=part.matches("(?is).*(?:原始\\s*CAD|原\\s*CAD|源\\s*CAD|\\bCAD\\b|\\b(?:source|original)\\s+(?:CAD|geometry)\\b).*" );boolean broad=part.matches("(?is).*(?:任何|所有|全部|一切|\\b(?:any|all|anything|everything)\\b).*" )||part.matches("(?is)^(?:(?:本次|这次|本轮|先|同时|也)\\s*)*(?:不要|不再|不需要|无需|不用|不|禁止|do not|don't|must not|never|without|no\\s+)\\s*(?:删除|删掉|删去|移除|清除|delete|remove|erase)\\s*$");if(sourceCad||broad||reference.matcher(part).find())return false;continue;}
            if(part.matches("(?s)"+prefix+exact+suffix))positive=true;
        }return positive;
    }
    static void validateOriginalCadDeletionAuthorization(String goal,String targetName)throws IOException{
        if(!originalCadDeletionAuthorized(goal,targetName))throw new IOException("original_cad_deletion_not_authorized：删除原始CAD须当前无条件正向指令明确点名该CAD本身；删除流体域、网格或修复外流场不授权删除其源CAD。当前目标名为“"+targetName+"”。保留/否定条款、历史、条件、问句或模糊所属关系不作为删除授权，未执行删除。");
    }
    static boolean regionDeletionForbidden(String goal){
        if(goal==null)return false;
        for(String raw:deletionClauses(goal)){String part=raw.trim();if(!negativeDeletionClause(part)||!mentionsDeletion(part))continue;
            if(part.matches("(?is).*(?:流体域|区域|外流场|\\bregions?\\b|\\bdomains?\\b).*"))return true;
            // A prohibition naming a different protected object (most commonly
            // original CAD) does not revoke an explicit Region deletion. A
            // prohibition with no such target, or an ALL/ANY scope, is broad.
            boolean otherTarget=part.matches("(?is).*(?:CAD|几何|网格|报告|场景|显示器|连续体|物理模型|\\b(?:geometry|mesh(?:es)?|reports?|scenes?|displayers?|continuums?|physics)\\b).*" );
            if(!otherTarget||part.matches("(?is).*(?:任何|所有|全部|一切|\\b(?:any|all|anything|everything)\\b).*"))return true;
        }return false;
    }
    static void validateRegionDeletionAuthorization(Map<?,?> action,String goal,boolean region)throws IOException{
        if(!region||!"delete_object".equals(action.get("type")))return;
        if(regionDeletionForbidden(goal))throw new IOException("region_deletion_forbidden：当前用户明确禁止删除流体区域或给出广义删除禁令；未解除引用、未删除区域。保护原始CAD等其它对象的条款不会撤销另行明确授权的区域删除。");
        if(Boolean.TRUE.equals(action.get("detach_static_references"))){String deletion=explicitDeletionDirective(goal);if(deletion.isEmpty()||!deletionRequiredKinds(deletion).contains("region"))throw new IOException("region_deletion_not_authorized：detach_static_references=true的区域删除须当前用户明确要求删除该流体域/区域；普通修复、历史、条件、问句或只读请求不授权自动解除引用并删除。请保留现有区域或按实际目标选择非删除修复。");}
    }
    static boolean regionRecreationAuthorized(String goal){
        String deletion=explicitDeletionDirective(goal);if(deletion.isEmpty()||deletionOnlyGoal(goal)||!deletionRequiredKinds(deletion).contains("region")||regionDeletionForbidden(goal))return false;
        boolean rebuilding=false;
        for(String raw:deletionClauses(goal)){String part=raw.trim();if(part.isEmpty())continue;
            if(negativeDeletionClause(part)){if(part.matches("(?is).*(?:重建|重做|重新创建|\\brebuild\\b|\\brecreat[a-z]*\\b).*"))return false;continue;}
            if(part.matches("(?is).*(?:重建|重做|重新创建|修复|恢复|继续|上一步|\\b(?:rebuild|recreat[a-z]*|repair|restore|continue|previous)\\b).*"))rebuilding=true;
        }return rebuilding;
    }
    static void validateRegionRecreationAuthorization(Map<?,?> action,String goal)throws IOException{
        if(!Boolean.TRUE.equals(action.get("recreate_region")))return;
        if(!"repair_external_aero".equals(action.get("type"))||!regionRecreationAuthorized(goal))throw new IOException("region_recreation_not_authorized：recreate_region=true仅用于当前用户明确要求删除现有流体区域并重建或按上一步继续；普通修复、仅删除、只读、否定或条件请求不能自动扩大为删除重建。默认修复仍保留现有区域。请按实际请求选择公开动作。");
    }
    private void validateJournalAction(Map<?,?> action)throws IOException{
        validateRegionRecreationAuthorization(action,instruction);
        validateWorkflowReplay(action);
        if("delete_object".equals(action.get("type"))){validateRegionDeletionAuthorization(action,instruction,String.valueOf(action.get("id")).startsWith("star.common.Region_"));validateExplicitDeletionTarget(instruction,operations.objectName(action.get("id")));}
        String type=String.valueOf(action.get("type"));if(deletionOnlyGoal(instruction)&&(externalWorkflow(type)||type.startsWith("create_")||type.startsWith("import_")||type.startsWith("repair_")))throw new IOException("delete_only_scope：当前用户要求仅删除，没有新建或重建授权；已保存删除不能用重新创建来抵消。请检查实际删除回执与readiness，仅处理尚未完成的删除目标，满足后按协议结束。");
        if("delete_object".equals(action.get("type")))for(int index=journal.size()-1;index>=0;index--){Map<String,Object> prior=journal.get(index);
            if(!"saved".equals(prior.get("status"))||!externalWorkflow(prior.get("kind"))||!(prior.get("created_output_ids") instanceof Collection)||!((Collection<?>)prior.get("created_output_ids")).contains(action.get("id"))||!(prior.get("actual") instanceof Map))continue;
            Map<String,Set<String>> targets=receiptTargets((Map<?,?>)prior.get("actual"));if(targets.get("region_ids").isEmpty()&&targets.get("mesh_ids").isEmpty())continue;
            // Evaluate this output's own chain, not a later unrelated workflow.
            Map<String,Object> conflict=completedWorkflowDeletionConflict(action,List.of(prior),assessCompletion(targets));
            if(!conflict.isEmpty()){String evidence=json(conflict);recordInspection("protected_completed_workflow_output",String.valueOf(action.get("id")),0,evidence);throw new IOException("原生完成读回已通过，未删除本轮刚刚完成的新输出："+evidence);}
        }
        if("update_block".equals(action.get("type")))for(Map<String,Object> prior:journal)if("saved".equals(prior.get("status"))&&"update_block".equals(prior.get("kind"))&&Objects.equals(prior.get("id"),action.get("id")))throw new IOException("本轮已更新此外域一次；没有新的用户尺寸或实测约束，不再反复扩大，请验证当前净距并完成下游配置。");
    }
    private Map<String,Object> applyAction(Map<?,?> action)throws Exception {
        validateJournalAction(action);
        Path backup=checkpoint();
        Set<String> previousOutputs=externalWorkflow(action.get("type"))?workflowOutputInventory():Set.of();
        Map<String,Object> row=new LinkedHashMap<>();row.put("parameter",operations.preview(action));
        row.put("kind",action.get("type"));row.put("id",action.get("id"));row.put("target_name",operations.objectName(action.get("id")));
        row.put("target_scope",operations.completionTargets(List.of(String.valueOf(action.get("id")))));
        List<String> names=new ArrayList<>();for(String key:List.of("selection_ids","part_ids","reports","monitor_ids"))if(action.get(key) instanceof List)for(Object id:(List<?>)action.get(key))names.add(operations.objectName(id));row.put("selection_names",names);Map<String,String> referenceNames=new LinkedHashMap<>();for(String key:List.of("field_id","representation_id","coordinate_system_id"))if(action.containsKey(key))referenceNames.put(key,operations.objectName(action.get(key)));row.put("reference_names",referenceNames);
        row.put("before",action.containsKey("expected_state")?action.get("expected_state"):action.get("expected_states"));row.put("recommended",action);
        row.put("reason",action.get("reason"));row.put("unit","");row.put("status","attempted");journal.add(row);writeReport();
        boolean saved=false;
        try {
            Map<String,Object> operationResult=operations.execute(action);row.put("actual",operationResult);
            if(externalWorkflow(action.get("type"))){Set<String> created=workflowOutputInventory();created.removeAll(previousOutputs);row.put("created_output_ids",new ArrayList<>(created));}
            if(Boolean.TRUE.equals(operationResult.get("skipped"))){row.put("status","skipped");outcome="applied";note=String.valueOf(operationResult.get("validation"));writeReport();return Map.of("continue",true,"skipped",true,"summary",note,"report",reportBase+".md");}
            simulation.saveState(savePath.toString());saved=true;row.put("status","saved");
            latestPlanningAssessment=Map.of();deletedExistence.clear();
            if(externalWorkflow(action.get("type"))){Map<String,Object> remembered=new LinkedHashMap<>();for(String key:List.of("body_id","reference_length_m","domain_scale_m","domain_mode","domain_bounds_m"))if(operationResult.containsKey(key))remembered.put(key,operationResult.get(key));remembered.put("source","Previous saved native workflow measurement for this body; retain only when the selected body identity matches. This is not evidence that a new region or mesh has been created.");knownSavedEngineeringFacts=remembered;}
            outcome="applied";note=operations.preview(action)+"\n已执行并保存。";writeReport();
            return Map.of("continue",!Set.of("run_solver","prepare_external_aero","repair_external_aero","configure_physics_profile","configure_external_physics","configure_flow_conditions").contains(action.get("type")),"summary",note,"progress",true,"report",reportBase+".md");
        }catch(Exception error){blocked=true;outcome="failed";
            if(error instanceof AerodynamicReports.ReportException)row.put("actual",((AerodynamicReports.ReportException)error).receipt());
            if(error instanceof NativeApiBridge.ApiExecutionException)row.put("actual",((NativeApiBridge.ApiExecutionException)error).receipt());
            if(error instanceof NativeGeometryRepair.GeometryException)row.put("actual",((NativeGeometryRepair.GeometryException)error).receipt());
            if(error instanceof CadPartCreation.CreationException)row.put("actual",((CadPartCreation.CreationException)error).receipt());
            if(error instanceof ExternalAeroWorkflow.WorkflowException)row.put("actual",((ExternalAeroWorkflow.WorkflowException)error).receipt());
            if(!saved)row.put("status","requires_restore");
            note=(saved?"操作已完成并保存，但报告写入失败：":"操作未完成，当前内存可能已有部分变化：")+message(error)+"。恢复副本："+backup;
            try{writeReport();}catch(IOException reportError){note+="；报告写入失败："+reportError.getMessage();}
            return Map.of("continue",false,"failed",true,"summary",note,"report",reportBase+".md");
        }
    }

    /** Legacy entry point deliberately disabled: configuration decisions must come
     * from the user's selected model, followed by normal validation and readback. */
    @Deprecated
    public String automaticPlan() throws IOException {
        throw new IOException("本地自动规划已停用；配置方案必须由用户当前选择的模型生成，禁止以规则方案替代。");
    }

    /** This only changes the native postcondition of an explicit deletion-only
     * request; it never selects an action or supplies model configuration. */
    private static List<String> deletionClauses(String goal){
        String latest=CodexAssistant.currentUserDirective(goal).replace("但是","，").replace("但","，").replaceAll("(?i)\\bbut\\b",",");
        return Arrays.asList(latest.split("[，,。；;！？!?\\n]+|并且|并|且|然后|之后|\\band\\b|\\bthen\\b|(?=\\bwithout\\b)"));
    }
    private static boolean negativeDeletionClause(String part){return part.matches("(?is)^(?:(?:本次|这次|本轮|先|同时|也)\\s*)*(?:不要|不再|不需要|无需|不用|不|禁止|do not|don't|must not|never|without|no\\s+).*" );}
    private static boolean mentionsDeletion(String part){return part.matches("(?is).*(?:删除|删掉|删去|移除|清除|\\bdelete\\b|\\bremove\\b|\\berase\\b).*" );}
    static boolean deletionOnlyGoal(String goal){
        if(goal==null)return false;String latest=String.join("，",deletionClauses(goal)).toLowerCase(Locale.ROOT);if(CodexAssistant.readOnlyRequest(latest))return false;boolean deletion=false;
        String verbs="创建|新建|重建|重做|修复|恢复|生成|修改|调整|配置|设置|绘制|划分|替换|切换|运行|执行|计算|求解|迭代|导入|导出|\\b(?:creat|rebuild|recreat|repair|restor|generat|modify|configur|set|replac|switch|run|solv|iterat|import|export|comput|calculat)[a-z]*";
        for(String raw:deletionClauses(goal)){String part=raw.trim();if(part.isEmpty())continue;boolean deletes=mentionsDeletion(part);
            if(negativeDeletionClause(part)){if(deletes)return false;continue;}
            if(part.matches("(?is).*(?:继续|上一步|下一步|上述|\\bcontinue\\b|\\bprevious\\b|\\bnext\\b).*"))return false;
            // Known object nouns and past construction descriptions do not request
            // a future mutation. Any remaining positive mutation makes this gate
            // conservative: preserve normal configuration/rebuild postconditions.
            String intent=part.replaceAll("(?:已(?:经)?|刚(?:刚|才)?|之前|先前)(?:创建|生成|配置|设置)","").replaceAll("(?i)(?:already|previously|newly|just)\\s+(?:created|generated|configured|set)","").replace("计算域","域").replace("网格配置","网格").replace("求解器","对象");
            if(java.util.regex.Pattern.compile("(?is)"+verbs).matcher(intent).find())return false;
            if(deletes)deletion=true;
        }return deletion;
    }
    private static Set<String> deletionRequiredKinds(String goal){
        StringBuilder request=new StringBuilder();for(String raw:deletionClauses(goal)){String part=raw.trim();if(!negativeDeletionClause(part)&&mentionsDeletion(part))request.append(part).append(' ');}
        String text=request.toString();Set<String> kinds=new LinkedHashSet<>();
        if(text.matches("(?is).*(?:域|区域|\\bregions?\\b|\\bdomains?\\b).*"))kinds.add("region");
        if(text.matches("(?is).*(?:网格|\\bmesh(?:es)?\\b).*"))kinds.add("mesh");
        if(text.matches("(?is).*(?:报告|\\breports?\\b).*"))kinds.add("report");
        if(text.matches("(?is).*(?:场景|\\bscenes?\\b).*"))kinds.add("scene");return kinds;
    }
    private static boolean deletionIdHasKind(String id,String kind){return switch(kind){case "region"->id.startsWith("star.common.Region_");case "mesh"->id.startsWith("star.meshing.AutoMeshOperation_");case "report"->id.matches(".*Report_[0-9]+$");case "scene"->id.startsWith("star.vis.Scene_");default->false;};}

    private static int simpleDeletionCount(String text){
        try{int value=Integer.parseInt(text);return value>0&&value<=10000?value:-1;}catch(NumberFormatException ignored){}
        return switch(text){case "一"->1;case "二","两"->2;case "三"->3;case "四"->4;case "五"->5;case "六"->6;case "七"->7;case "八"->8;case "九"->9;case "十"->10;default->-1;};
    }
    private static Set<String> explicitDeletionNames(String goal){
        Set<String> names=new LinkedHashSet<>();if(goal==null)return names;
        String rawGoal=CodexAssistant.currentUserDirective(goal).replace("但是","，").replace("但","，").replaceAll("(?i)\\bbut\\b",",");
        for(String raw:rawGoal.split("(?i)[，,。；;！？!?\\n]+|并且|并|且|然后|之后|\\band\\b|\\bthen\\b|(?=\\bwithout\\b)")){String part=raw.trim();if(negativeDeletionClause(part)||!mentionsDeletion(part))continue;
            java.util.regex.Matcher marked=java.util.regex.Pattern.compile("(?i)(?:名为|名称为|名称是|叫做|named|called)\\s*(?:\\\"([^\\\"]+)\\\"|'([^']+)'|“([^”]+)”|‘([^’]+)’|([^\\s，,。；;]+))").matcher(part);
            while(marked.find()){String name=null;for(int group=1;group<=5;group++)if(marked.group(group)!=null){name=marked.group(group);if(group==5)name=name.replaceFirst("(?:的)?(?:区域|流体域|报告|网格|场景)$","");break;}if(name!=null&&!name.isBlank())names.add(name.trim());}
            java.util.regex.Matcher quoted=java.util.regex.Pattern.compile("\\\"([^\\\"]+)\\\"|'([^']+)'|“([^”]+)”|‘([^’]+)’").matcher(part);
            while(quoted.find())for(int group=1;group<=4;group++)if(quoted.group(group)!=null&&!quoted.group(group).isBlank()){names.add(quoted.group(group).trim());break;}
        }return names;
    }
    static void validateExplicitDeletionTarget(String goal,String targetName)throws IOException{
        Set<String> requested=explicitDeletionNames(goal);if(!requested.isEmpty()&&(targetName==null||!requested.contains(targetName.trim())))throw new IOException("explicit_delete_target_mismatch：用户明确点名的删除目标为"+requested+"，当前方案目标为"+targetName+"；目标不匹配，未执行删除。仅可选择点名对象；若必须处理其他依赖，请先读回其实际引用和允许的解除方法。");
    }

    private static List<String> deletionScopeReasons(String goal,List<? extends Map<?,?>> entries,Set<String> deleted,Collection<String> nativeDeletedNames){
        List<String> reasons=new ArrayList<>();StringBuilder text=new StringBuilder();Set<String> deletedNames=new LinkedHashSet<>(nativeDeletedNames);
        for(String raw:deletionClauses(goal))if(!negativeDeletionClause(raw.trim())&&mentionsDeletion(raw))text.append(raw).append(' ');
        for(Map<?,?> row:entries)if("saved".equals(row.get("status"))&&"delete_object".equals(row.get("kind"))){Object name=row.get("target_name");if(!(name instanceof String)&&row.get("actual") instanceof Map)name=((Map<?,?>)row.get("actual")).get("deleted_name");if(name instanceof String)deletedNames.add((String)name);}
        String scope=text.toString();
        if(scope.matches("(?is).*(?:所有|全部|全体|每个|\\ball\\b|\\bevery\\b).*"))reasons.add("用户要求全部范围，但当前只有逐对象删除回执，尚未独立核验完整原始目标集合");
        for(String name:explicitDeletionNames(goal))if(!deletedNames.contains(name))reasons.add("用户点名的对象尚无匹配删除回执："+name);
        java.util.regex.Matcher counts=java.util.regex.Pattern.compile("(?i)([0-9]+|[一二两三四五六七八九十百]+)\\s*(?:个|份|项)?\\s*(流体域|区域|报告|网格|场景|reports?|regions?|meshes?|scenes?)").matcher(scope);
        while(counts.find()){int wanted=simpleDeletionCount(counts.group(1));String noun=counts.group(2);String kind=noun.matches("(?i)(?:流体域|区域|regions?)")?"region":noun.matches("(?i)(?:报告|reports?)")?"report":noun.matches("(?i)(?:网格|meshes?)")?"mesh":"scene";long actual=deleted.stream().filter(id->deletionIdHasKind(id,kind)).count();if(wanted<0||actual!=wanted)reasons.add("明确删除数量尚未匹配："+counts.group()+"，实际匹配回执数="+actual);}
        return reasons;
    }

    static Map<String,Object> deletionAssessment(String goal,List<? extends Map<?,?>> entries,boolean blocked,Set<String> presentIds){
        if(!deletionOnlyGoal(goal))return Map.of();return deletionProof(goal,entries,blocked,presentIds,true);
    }
    static String explicitDeletionDirective(String goal){
        if(goal==null)return "";String latest=String.join("，",deletionClauses(goal));if(CodexAssistant.readOnlyRequest(latest)||latest.matches("(?is).*[?？].*"))return "";
        List<String> clauses=new ArrayList<>();for(String raw:deletionClauses(goal)){String part=raw.trim();if(part.isEmpty()||negativeDeletionClause(part)||!mentionsDeletion(part))continue;
            if(part.matches("(?is).*(?:如果|假如|若要|如需|是否|能否|可否|建议|如何|\\bif\\b|\\bwhether\\b|\\bshould\\b).*" )||part.matches("(?is)^(?:之前|以前|先前|曾经|历史|上次|已删除|已经删除|previously\\b|already\\b|formerly\\b).*"))continue;
            clauses.add(part);
        }return String.join("；",clauses);
    }
    /** Typed proof produced by the native atomic region recreation. It is not
     * a synthetic delete_object journal entry and never accepts model claims. */
    static Map<?,?> nativeRegionRecreation(Map<?,?> row,Set<String> initialIds){
        if(!"saved".equals(row.get("status"))||!"repair_external_aero".equals(row.get("kind"))||!(row.get("recommended") instanceof Map)||!Boolean.TRUE.equals(((Map<?,?>)row.get("recommended")).get("recreate_region"))||!(row.get("actual") instanceof Map))return Map.of();
        Map<?,?> actual=(Map<?,?>)row.get("actual");if(!"prepare_external_aero".equals(actual.get("workflow"))||!"repair_external_aero".equals(actual.get("requested_action"))||!"repair".equals(actual.get("mode"))||!(actual.get("region_recreation") instanceof Map))return Map.of();
        Map<?,?> proof=(Map<?,?>)actual.get("region_recreation");Object old=proof.get("deleted_id"),created=proof.get("created_region_id");
        if(!(old instanceof String)||!(created instanceof String)||!deletionIdHasKind(old.toString(),"region")||!deletionIdHasKind(created.toString(),"region")||old.equals(created)||!initialIds.contains(old)||!created.equals(actual.get("region_id"))||!Boolean.TRUE.equals(proof.get("deleted_absent"))||!(proof.get("deleted_name") instanceof String)||!(proof.get("reference_updates") instanceof Collection))return Map.of();
        return proof;
    }
    private static Map<String,String> nativeRecreationDeletions(List<? extends Map<?,?>> entries,Set<String> initialIds){
        Map<String,String> result=new LinkedHashMap<>();for(Map<?,?> row:entries){Map<?,?> proof=nativeRegionRecreation(row,initialIds);if(!proof.isEmpty())result.put(proof.get("deleted_id").toString(),proof.get("deleted_name").toString());}return result;
    }
    private static Set<String> savedDeletionIds(List<? extends Map<?,?>> entries,Set<String> initialIds){
        Set<String> ids=new LinkedHashSet<>(nativeRecreationDeletions(entries,initialIds).keySet());for(Map<?,?> row:entries)if("saved".equals(row.get("status"))&&"delete_object".equals(row.get("kind"))&&row.get("actual") instanceof Map&&((Map<?,?>)row.get("actual")).get("deleted_id") instanceof String)ids.add(((Map<?,?>)row.get("actual")).get("deleted_id").toString());return ids;
    }
    static Map<String,Object> compoundDeletionAssessment(String goal,List<? extends Map<?,?>> entries,boolean blocked,Set<String> presentIds,Set<String> initialIds){
        String required=explicitDeletionDirective(goal);if(required.isEmpty()||deletionOnlyGoal(goal))return Map.of();
        List<Map<?,?>> relevant=new ArrayList<>();for(Map<?,?> row:entries){if("delete_object".equals(row.get("kind"))&&row.get("actual") instanceof Map&&((Map<?,?>)row.get("actual")).get("deleted_id") instanceof String&&!initialIds.contains(((Map<?,?>)row.get("actual")).get("deleted_id")))continue;relevant.add(row);}
        Map<String,Object> result=deletionProof(required,relevant,blocked,presentIds,false,nativeRecreationDeletions(relevant,initialIds));result.put("scope","Explicit current unconditional deletion is required in addition to the rebuilt live chain. Repair reuse is not deletion evidence.");return result;
    }
    private static Map<String,Object> deletionProof(String goal,List<? extends Map<?,?>> entries,boolean blocked,Set<String> presentIds,boolean onlyDeletion){return deletionProof(goal,entries,blocked,presentIds,onlyDeletion,Map.of());}
    private static Map<String,Object> deletionProof(String goal,List<? extends Map<?,?>> entries,boolean blocked,Set<String> presentIds,boolean onlyDeletion,Map<String,String> nativeDeleted){
        Set<String> deleted=new LinkedHashSet<>(nativeDeleted.keySet());List<String> reasons=new ArrayList<>();
        if(blocked)reasons.add("删除过程中发生失败，恢复状态尚未处理，不能认定完成");
        for(Map<?,?> row:entries){Object status=row.get("status"),kind=row.get("kind");
            if("requires_restore".equals(status)||"attempted".equals(status)||"failed".equals(status)){reasons.add("存在未完成或需恢复的写入记录");continue;}
            if(!"saved".equals(status))continue;
            if("delete_object".equals(kind)&&row.get("actual") instanceof Map&&((Map<?,?>)row.get("actual")).get("deleted_id") instanceof String)deleted.add(((Map<?,?>)row.get("actual")).get("deleted_id").toString());
            else if(onlyDeletion&&!(("set_object_selection".equals(kind)||"set_part_selection".equals(kind))&&row.get("recommended") instanceof Map&&((Map<?,?>)row.get("recommended")).get("selection_ids") instanceof List&&((List<?>)((Map<?,?>)row.get("recommended")).get("selection_ids")).isEmpty()))reasons.add("删除任务存在其他已写入操作，仍需核验其请求范围及最终状态");
        }
        if(deleted.isEmpty())reasons.add("尚无已保存的目标删除回执，不能把无匹配或未读取当成删除成功");
        Set<String> required=deletionRequiredKinds(goal);for(String kind:required)if(deleted.stream().noneMatch(id->deletionIdHasKind(id,kind)))reasons.add("已删除对象尚未覆盖用户要求的目标类型："+kind+"；删除网格不能代替删除流体区域");
        if(required.isEmpty()){
            String latest=CodexAssistant.currentUserDirective(goal).toLowerCase(Locale.ROOT);boolean named=false;
            for(Map<?,?> row:entries)if("saved".equals(row.get("status"))&&"delete_object".equals(row.get("kind"))){Object name=row.get("target_name");if(!(name instanceof String)&&row.get("actual") instanceof Map)name=((Map<?,?>)row.get("actual")).get("deleted_name");if(name instanceof String&&!((String)name).isBlank()&&latest.contains(((String)name).toLowerCase(Locale.ROOT)))named=true;}
            if(!named)reasons.add("尚未将用户要求的删除对象与实际对象名称或类型对应，不能用其他对象的删除代替");
        }
        for(String id:deleted)if(presentIds.contains(id))reasons.add("删除回执中的目标仍存在："+id);
        reasons.addAll(deletionScopeReasons(goal,entries,deleted,nativeDeleted.values()));
        boolean ready=reasons.isEmpty();Map<String,Object> result=new LinkedHashMap<>();result.put("ready",ready);result.put("complete",ready);result.put("blocked",blocked);result.put("incomplete_reasons",reasons);result.put("target_scope",Map.of("deleted_ids",deleted));
        result.put("readiness",Map.of("scope","Exact saved deletion targets; their absence is expected. Unrelated SIM inventory is neither required nor completion evidence.","requested_configuration_items_present",ready,"deleted_targets_absent",ready,"deleted_ids",deleted,"incomplete_reasons",reasons));return result;
    }

    private Map<String,Object> currentReadiness(Map<String,Set<String>> targets){
        Set<String> regions=targets.get("region_ids");boolean scoped=!regions.isEmpty()||!targets.get("mesh_ids").isEmpty();
        Map<String,Object> full=CaseReadiness.inspect(simulation,CodexAssistant.completionScope(instruction),regions.isEmpty()&&scoped?Set.of("<no_region_associated_with_target>"):regions);
        Map<String,Object> result=new LinkedHashMap<>();result.put("target_scope",targets);result.put("scope_policy",scoped?"Only this task's affected chain is evaluated; unrelated objects remain outside the requested modification scope":"SIM inventory for planning; missing items do not authorize modifying objects outside the user's request");
        for(String key:List.of("requested_configuration_items_present","incomplete_reasons","flow_solver_basics_ready","flow_solver_incomplete_reasons","boundary_counts","inspection_errors","goal_requirements"))if(full.containsKey(key))result.put(key,full.get(key));
        result.putAll(full);return result;
    }

    static Map<String,Set<String>> receiptTargets(Map<?,?> actual){
        Set<String> regions=new LinkedHashSet<>(),meshes=new LinkedHashSet<>();
        if(actual.get("region_id") instanceof String)regions.add(actual.get("region_id").toString());if(actual.get("mesh_id") instanceof String)meshes.add(actual.get("mesh_id").toString());
        for(String key:List.of("regions","region_ids"))if(actual.get(key) instanceof Collection)for(Object id:(Collection<?>)actual.get(key))if(id instanceof String)regions.add(id.toString());
        Object created=actual.get("created");Map<?,?> description=created instanceof Map?(Map<?,?>)created:actual;
        Object id=description.containsKey("id")?description.get("id"):description.get("created_id");Object type=description.containsKey("type")?description.get("type"):description.get("created_type");
        if(id instanceof String&&type instanceof String){if(type.toString().equals("star.common.Region"))regions.add(id.toString());if(type.toString().equals("star.meshing.AutoMeshOperation"))meshes.add(id.toString());}
        return Map.of("region_ids",regions,"mesh_ids",meshes);
    }

    private Map<String,Set<String>> completionTargets(){
        Set<String> regions=new LinkedHashSet<>(),meshes=new LinkedHashSet<>(),ids=new LinkedHashSet<>();
        for(int index=journal.size()-1;index>=0;index--){Map<String,Object> row=journal.get(index);if(!"saved".equals(row.get("status")))continue;
            Object raw=row.get("actual");if(raw instanceof Map){Map<String,Set<String>> receipt=receiptTargets((Map<?,?>)raw);regions.addAll(receipt.get("region_ids"));meshes.addAll(receipt.get("mesh_ids"));if(!regions.isEmpty()||!meshes.isEmpty())break;}
            // This scope was captured before mutation, so a deleted target cannot
            // disappear from the scope and be replaced by unrelated live inventory.
            if(row.get("target_scope") instanceof Map){Map<?,?> saved=(Map<?,?>)row.get("target_scope");for(String key:List.of("region_ids","mesh_ids"))if(saved.get(key) instanceof Collection)for(Object value:(Collection<?>)saved.get(key))if(value instanceof String)(key.equals("region_ids")?regions:meshes).add(value.toString());if(!regions.isEmpty()||!meshes.isEmpty())break;}
            Object id=row.get("id");if(id instanceof String){Slot slot=slots.get(id);ids.add(slot==null?id.toString():slot.object.getObjectKeyAsString());}
        }
        ids.addAll(regions);ids.addAll(meshes);Map<String,Set<String>> found=operations.completionTargets(ids);regions.addAll(found.get("region_ids"));meshes.addAll(found.get("mesh_ids"));
        return Map.of("region_ids",regions,"mesh_ids",meshes);
    }

    private static void closureNumber(List<String> reasons,String label,Double requested,Map<?,?> recommended,String key){
        if(requested==null)return;Object raw=recommended.get(key);if(!(raw instanceof Number)||!Double.isFinite(((Number)raw).doubleValue())||Math.abs(((Number)raw).doubleValue()-requested)>Math.max(1e-10,Math.abs(requested)*1e-8))reasons.add("明确"+label+"与已保存事务参数不匹配");
    }
    /** Deliberately closed vocabulary for native external-domain/BC completion.
     * Unknown original text is retained and prevents certification. This neither
     * selects an action nor treats the positive-readiness projection as the goal. */
    static Map<String,Object> externalClosureGoalCoverage(String goal,Map<?,?> recommended,Map<?,?> actual){
        List<String> reasons=new ArrayList<>();String original=goal==null?"":goal;
        if(original.isBlank()||CodexAssistant.readOnlyRequest(original))reasons.add("当前并非明确的外流场修改任务");
        if(!original.matches("(?is).*(?:外流场|流体域|外域|对称面|对称边界|边界条件|external.{0,12}(?:flow|domain)|fluid.{0,8}domain|symmetry|boundar(?:y|ies)).*" )||!original.matches("(?is).*(?:修改|修正|修复|纠正|调整|创建|新建|建立|重建|重做|配置|设置|完成|恢复|delete|repair|fix|correct|create|rebuild|configure|modify|change).*"))reasons.add("任务范围不是完整识别的外流域/边界修改");
        if(original.matches("(?is).*(?:报告|监视|场函数|停止|迭代|收敛|残差|导出|截图|命名|命名为|名为|名称|(?<![a-z])(?:report|monitor|field[ _-]*function|stopping|iteration|convergence|residual|export|screenshot|named|rename)(?![a-z])).*"))reasons.add("存在事务外的命名、输出、监视或求解目标");
        if(original.matches("(?is).*(?:实际|测量|验证|检查|核验|质量|精度|实测|验证|actual|measur|validat|quality|accuracy).*"))reasons.add("存在不能由配置事务代替的测量/验证目标");
        if(original.matches("(?is).*(?:(?:所有|全部)(?:的)?(?:开放|开口)?(?:边界|入口|出口|面)|(?:all|every).{0,12}(?:boundar|inlet|outlet|face)|(?:边界|开放面|入口|出口)\\s*(?:全部|所有)).*"))reasons.add("完整逐面范围要求须另行核验，不能由类型数量覆盖");
        if(original.matches("(?is).*(?:速度入口|压力出口|自由来流|自由流|壁面|velocity[ _-]*inlet|pressure[ _-]*outlet|free[ _-]*stream|wall[ _-]*boundary).{0,45}(?:改成|改为|切换为|切换到|替换为|替换成|变为|变成|设置为|设为|\\bto\\b|\\binto\\b).*"))reasons.add("逐边界类型替换不能由完整事务的边界方案数量代替");

        if(original.matches("(?is).*(?:上一步|下一步|上述|前述|继续|previous|next|continue).*"))reasons.add("存在本句未完整展开的指代或继续目标");
        for(String raw:deletionClauses(original)){String part=raw.trim();if(!negativeDeletionClause(part)&&mentionsDeletion(part)&&part.matches("(?is).*(?:CAD|原始几何|源几何|original.{0,8}geometry|source.{0,8}geometry).*"))reasons.add("源CAD删除不能由外流链修复事务覆盖");}
        if(!explicitDeletionDirective(original).isEmpty()){
            if(!regionRecreationAuthorized(original))reasons.add("仅支持明确先删除旧流体域再重建的事务删除目标");
            java.util.regex.Matcher deletion=java.util.regex.Pattern.compile("(?i)删除|删掉|移除|\\bdelete\\b|\\bremove\\b").matcher(original),creation=java.util.regex.Pattern.compile("(?i)创建|新建|建立|重建|重做|恢复|\\bcreate\\b|\\brebuild\\b|\\brestore\\b").matcher(original);
            if(!deletion.find()||!creation.find()||creation.start()<deletion.start())reasons.add("删除与重建的顺序或后续目标不能由该事务证明");
        }
        closureNumber(reasons,"马赫数",RecommendationContext.mach(original),recommended,"mach");
        closureNumber(reasons,"飞行高度",RecommendationContext.altitude(original),recommended,"altitude_m");
        closureNumber(reasons,"目标y+",RecommendationContext.yplus(original),recommended,"target_yplus");
        closureNumber(reasons,"参考长度",RecommendationContext.reference(original),recommended,"reference_length_m");
        String flow=RecommendationContext.flowAxis(original),sym=RecommendationContext.symmetryAxis(original);
        if(!flow.isEmpty()&&!flow.equals(recommended.get("flow_axis")))reasons.add("明确来流方向与已保存事务不匹配");
        if(!sym.isEmpty()){
            if(!sym.equals(recommended.get("symmetry_axis")))reasons.add("明确对称轴与已保存事务不匹配");
            double plane=RecommendationContext.symmetryPlane(original,sym);Object saved=recommended.get("symmetry_plane_m");if(!(saved instanceof Number)||Math.abs(((Number)saved).doubleValue()-plane)>1e-9)reasons.add("明确对称面位置与已保存事务不匹配");
            if(!(actual.get("domain_bounds_m") instanceof Map))reasons.add("缺少实际外域范围证明");else{Map<?,?> bounds=(Map<?,?>)actual.get("domain_bounds_m");int axis="xyz".indexOf(sym);Object low=bounds.get("min_m"),high=bounds.get("max_m");if(!(low instanceof List)||!(high instanceof List)||((List<?>)low).size()!=3||((List<?>)high).size()!=3||axis<0||!(((List<?>)low).get(axis) instanceof Number)||!(((List<?>)high).get(axis) instanceof Number)||Math.min(Math.abs(((Number)((List<?>)low).get(axis)).doubleValue()-plane),Math.abs(((Number)((List<?>)high).get(axis)).doubleValue()-plane))>1e-8)reasons.add("实际外域范围未证明其边界位于要求的对称面");}
        }
        String intent=GoalRequirementIntent.requirementText(original);
        if(intent.matches("(?is).*(?:半模|half[ -]?model).*" )&&(!"half".equals(recommended.get("domain_mode"))||!"half".equals(actual.get("domain_mode"))))reasons.add("半模要求与已保存事务不匹配");
        if(intent.matches("(?is).*(?:全模|full[ -]?model).*" )&&(!"full".equals(recommended.get("domain_mode"))||!"full".equals(actual.get("domain_mode"))))reasons.add("全模要求与已保存事务不匹配");
        String scheme=String.valueOf(recommended.containsKey("boundary_scheme")?recommended.get("boundary_scheme"):"velocity_pressure");
        if(intent.matches("(?is).*(?:自由来流|自由流|free[ _-]*stream).*" )&&!"freestream".equals(scheme))reasons.add("明确自由流方案未由已保存事务覆盖");
        if(intent.matches("(?is).*(?:速度入口|压力出口|velocity[ _-]*inlet|pressure[ _-]*outlet).*" )&&!"velocity_pressure".equals(scheme))reasons.add("明确入口/出口方案未由已保存事务覆盖");
        String text=original.replace("本轮用户补充或变更（优先于旧目标；不重复已完成步骤）："," ");
        String n=RecommendationContext.NUM;
        if(RecommendationContext.mach(original)!=null)text=text.replace("飞行速度"," ");
        text=text.replaceAll("(?i)(?:参考长度|参考弦长|翼根弦长|reference_length_m)\\s*(?:为|是|=|:|：)?\\s*"+n+"\\s*(?:mm|cm|m|毫米|米)?"," ");
        text=text.replaceAll("(?i)(?<![.0-9])"+n+"\\s*(?:马赫|mach|Ma\\b)|(?:mach|Ma\\b|马赫数)\\s*(?:为|是|=|:|：)?\\s*"+n," ");
        text=text.replaceAll("(?i)(?:高度|海拔|altitude)\\s*(?:为|是|=|:|：)?\\s*"+n+"\\s*(?:km|千米|m\\b|米)"," ");
        text=text.replaceAll("(?i)"+RecommendationContext.YPLUS_MARKER+RecommendationContext.YPLUS_CONNECTOR+n," ");
        text=text.replaceAll("(?i)(?<![a-z0-9_])[xyz]\\s*=\\s*[+-]?"+n+"\\s*(?:mm|cm|m|毫米|米)?"," ");
        text=text.replaceAll("(?i)(?:先|暂时)?(?:不要|不需要|无需|不用|禁止|不|do\\s+not|don't|must\\s+not|never)\\s*(?:再|自动)?\\s*(?:执行|运行|生成|绘制|划分|求解|run|execute|generate)?\\s*(?:网格|CFD|求解|mesh|solver)(?:\\s*(?:和|及|与|以及|and)\\s*(?:网格|CFD|求解|mesh|solver))*"," ");
        text=text.replaceAll("(?i)(?:保留|保持|保护|不修改|不要修改|不要删除|不删除|preserve|keep|do not delete|do not modify)\\s*(?:原始|原|源|original|source)?\\s*CAD(?:不变)?"," ");
        text=text.replaceAll("(?i)(?:速度入口|压力出口|自由来流|自由流|边界层|机翼表面|翼面|对称平面|对称边界|对称面|边界条件|外流场|流体域|计算域|外域|远场|半模型|半模|全模型|全模|来流条件|来流|工况|原始CAD|飞机|机翼|边界|流场|飞行|网格|xoz|xoy|yoz|xz平面|xy平面|yz平面)"," ");
        text=text.replaceAll("(?i)\\b(?:external|fluid|flow|domain|symmetry|plane|half|full|model|velocity|inlet|pressure|outlet|free[ _-]*stream|aircraft|wing|conditions?)\\b"," ");
        text=text.replaceAll("(?i)(?:修改|修正|修复|纠正|调整|创建|新建|建立|重建|重做|配置|设置|完成|恢复|删除|绘制|划分|生成|对应|以及|并且|然后|之后|本轮|本次|当前|现在|需要|要求|希望|请你|请|帮我|只|仅|先|仍|按照|采用|使用|保留|保持|位于|现有|原有|该|此|旧|并|再|注意|要|给|有|全部|包围|显然|不正确|不对|错误|已经|是|的|在|与|和|及|而|你|我|了|这|按|为|到)"," ");
        text=text.replaceAll("(?i)\\b(?:please|now|current|this|only|still|the|a|an|is|at|on|and|or|to|with|for|as|repair|fix|correct|create|rebuild|configure|modify|change|delete|restore)\\b"," ");
        String unmatched=text.replaceAll("[\\s，,。；;:：、（）()！!?？.]+","");if(!unmatched.isBlank())reasons.add("原始任务仍含未识别要求："+unmatched);
        Map<String,Object> coverage=new LinkedHashMap<>();coverage.put("covered",reasons.isEmpty());coverage.put("unmatched_text",unmatched);coverage.put("incomplete_reasons",reasons);coverage.put("scope","Recognized external-domain and boundary configuration only; never mesh/CFD accuracy or unknown extra tasks");return coverage;
    }

    static Map<String,Object> externalClosureReceiptEligibility(String goal,List<? extends Map<?,?>> entries,boolean blocked){
        List<String> reasons=new ArrayList<>();Map<?,?> row=entries.isEmpty()?Map.of():entries.get(entries.size()-1),actual=Map.of(),recommended=Map.of();
        if(blocked)reasons.add("本轮存在失败或恢复阻断");
        if(!"saved".equals(row.get("status"))||!externalWorkflow(row.get("kind"))||!(row.get("actual") instanceof Map)||!(row.get("recommended") instanceof Map))reasons.add("最终日志条目不是本轮成功保存的完整外流场事务");
        else{actual=(Map<?,?>)row.get("actual");recommended=(Map<?,?>)row.get("recommended");
            if(!"prepare_external_aero".equals(actual.get("workflow"))||!"complete".equals(actual.get("stage"))||!(actual.get("region_id") instanceof String)||!(actual.get("mesh_id") instanceof String))reasons.add("缺少完整原生事务阶段及实际目标ID");
            if(!(actual.get("completion_snapshot") instanceof Map))reasons.add("缺少该事务保存时的受检快照");
            boolean flow=false;if(actual.get("steps") instanceof Collection)for(Object raw:(Collection<?>)actual.get("steps"))if(raw instanceof Map&&"flow_conditions".equals(((Map<?,?>)raw).get("step"))&&((Map<?,?>)raw).get("actual") instanceof Map)flow=Boolean.TRUE.equals(((Map<?,?>)((Map<?,?>)raw).get("actual")).get("conditions_verified"));if(!flow)reasons.add("来流、材料、初始值和实际开放边界没有完整SI读回证明");
            Object before=actual.get("iteration_before"),after=actual.get("iteration_after");if(!(before instanceof Number)||!(after instanceof Number)||((Number)before).longValue()!=((Number)after).longValue())reasons.add("配置事务不能覆盖求解步数或发生的CFD迭代");
            if(EngineeringIntent.meshForbidden(goal)&&(Boolean.TRUE.equals(recommended.get("execute_mesh"))||!Boolean.FALSE.equals(actual.get("meshing_executed"))))reasons.add("用户禁止网格执行，但事务执行状态不匹配");
            if(EngineeringIntent.meshDisposition(goal)>0){
                if(!Boolean.TRUE.equals(recommended.get("execute_mesh"))||!Boolean.TRUE.equals(actual.get("meshing_executed")))reasons.add("用户要求生成网格，但没有实际执行证明");
                if(!(actual.get("mesh_validity") instanceof Map)||!Boolean.TRUE.equals(((Map<?,?>)actual.get("mesh_validity")).get("current_geometry_meshed"))||!Boolean.FALSE.equals(((Map<?,?>)actual.get("mesh_validity")).get("requires_regeneration")))reasons.add("当前几何网格尚需重新生成");
                if(!(actual.get("quality_snapshot") instanceof Map))reasons.add("网格执行后没有基本独立读回");else{Map<?,?> q=(Map<?,?>)actual.get("quality_snapshot");if(!(q.get("cell_count") instanceof Number)||((Number)q.get("cell_count")).longValue()<=0||!(q.get("read_errors") instanceof Collection)||!((Collection<?>)q.get("read_errors")).isEmpty()||!(q.get("mesh_geometry_issues") instanceof Collection)||!((Collection<?>)q.get("mesh_geometry_issues")).isEmpty()||!(q.get("dirty_mesh_operations") instanceof Collection)||!((Collection<?>)q.get("dirty_mesh_operations")).isEmpty())reasons.add("网格独立读回缺失、过期或与几何不一致");}
            }
            if(goal.matches("(?is).*(?:边界层|棱柱|prism|boundary[ -]*layer).*" )&&!Boolean.TRUE.equals(actual.get("prism_enabled")))reasons.add("用户要求的边界层未在原生读回中启用");
        }
        Map<String,Object> coverage=externalClosureGoalCoverage(goal,recommended,actual);if(!Boolean.TRUE.equals(coverage.get("covered"))&&coverage.get("incomplete_reasons") instanceof Collection)for(Object reason:(Collection<?>)coverage.get("incomplete_reasons"))reasons.add(String.valueOf(reason));
        return new LinkedHashMap<>(Map.of("eligible",reasons.isEmpty(),"incomplete_reasons",reasons,"goal_coverage",coverage));
    }
    /** Completion of a fully recognized task is a native postcondition, not a
     * second model configuration decision. No action or parameter is generated. */
    public String transactionClosure()throws Exception{
        Map<String,Object> eligibility=externalClosureReceiptEligibility(instruction,journal,blocked);
        if(!Boolean.TRUE.equals(eligibility.get("eligible")))return json(Map.of("certified",false,"eligibility",eligibility));
        Map<String,Object> row=journal.get(journal.size()-1);Map<?,?> actual=(Map<?,?>)row.get("actual"),recommended=(Map<?,?>)row.get("recommended");
        Map<String,Set<String>> targets=receiptTargets(actual);Map<String,Object> completion=assessCompletion(targets);latestPlanningAssessment=completion;
        if(!Boolean.TRUE.equals(completion.get("ready"))||Boolean.TRUE.equals(completion.get("blocked")))return json(Map.of("certified",false,"eligibility",eligibility,"current_completion",completion));
        Map<String,Object> current=ExternalAeroWorkflow.currentCompletionEvidence(simulation,recommended,actual);
        if(!Boolean.TRUE.equals(current.get("verified")))return json(Map.of("certified",false,"eligibility",eligibility,"current_completion",completion,"fresh_native_verification",current));
        Map<String,Object> proof=new LinkedHashMap<>();proof.put("certified",true);proof.put("kind","verified_external_transaction_closure");proof.put("saved_workflow",row.get("kind"));proof.put("journal_index",journal.size()-1);proof.put("goal_coverage",eligibility.get("goal_coverage"));proof.put("target_scope",targets);proof.put("current_completion",completion);proof.put("fresh_native_verification",current);proof.put("actual",historyActual(actual));proof.put("scope","本轮已保存外流场配置及明确要求的网格执行，逐项当前原生读回一致；不是CFD收敛、实际y+或未知额外目标的证明");
        recordInspection("certified_transaction_closure",String.valueOf(actual.get("region_id")),0,json(proof));return json(proof);
    }

    /** Native postconditions without finalizing or writing this task. The selected
     * model can use the concrete findings to correct a plan within its normal limits. */
    public String completionStatus(){latestPlanningAssessment=assessCompletion();return json(latestPlanningAssessment);}

    private Set<String> observeDeletedIds(Set<String> ids){Set<String> present=ids.isEmpty()?Set.of():operations.existingObjectIds(ids);for(String id:ids)deletedExistence.put(id,present.contains(id));return present;}

    /** Small native journal repeated at the end of every selected-model call.
     * Receipts are facts; they never choose the next configuration action. */
    public String planningLedger(){
        List<Object> deletions=new ArrayList<>(),saved=new ArrayList<>();int savedCount=0;
        for(Map<String,Object> row:journal){if(!"saved".equals(row.get("status")))continue;savedCount++;Map<String,Object> fact=new LinkedHashMap<>();for(String key:List.of("kind","id","target_name"))if(row.containsKey(key))fact.put(key,row.get(key));
            if(row.get("actual") instanceof Map){Map<?,?> actual=(Map<?,?>)row.get("actual");Map<String,Object> values=new LinkedHashMap<>();for(String key:List.of("deleted_id","deleted_name","selection_ids","body_id","region_id","mesh_id","domain_mode","reference_length_m","region_recreation","reference_updates","validation","method","readback","before","actual","report","monitor","plot","configured","data_status","verified","target_name","target_id","property","reference_area_m2","reference_length_m","reference_density_kg_m3","reference_velocity_m_s","direction","origin_m","normal","monitor_ids","field_id","selection_ids","range"))if(actual.containsKey(key))values.put(key,actual.get(key));fact.put("actual",values);
                if("delete_object".equals(row.get("kind"))&&actual.get("deleted_id") instanceof String){String id=actual.get("deleted_id").toString();Map<String,Object> removed=new LinkedHashMap<>();removed.put("id",id);removed.put("name",actual.getOrDefault("deleted_name",null));removed.put("saved_native_delete",true);removed.put("exists_at_saved_delete_readback",false);removed.put("exists_now",deletedExistence.containsKey(id)?deletedExistence.get(id):"not_rechecked_after_latest_write");deletions.add(removed);}
                Map<?,?> recreated=nativeRegionRecreation(row,initialDeletionIds);if(!recreated.isEmpty()){String id=recreated.get("deleted_id").toString();Map<String,Object> removed=new LinkedHashMap<>();removed.put("id",id);removed.put("name",recreated.get("deleted_name"));removed.put("saved_native_region_recreation",true);removed.put("exists_at_saved_delete_readback",false);removed.put("exists_now",deletedExistence.containsKey(id)?deletedExistence.get(id):"not_rechecked_after_latest_write");removed.put("replacement_region_id",recreated.get("created_region_id"));deletions.add(removed);}
            }saved.add(fact);if(saved.size()>16)saved.remove(0);
        }
        Map<String,Object> ledger=new LinkedHashMap<>();ledger.put("saved_action_count",savedCount);ledger.put("confirmed_deletions",deletions);ledger.put("latest_saved_actions",saved);if(!knownSavedEngineeringFacts.isEmpty())ledger.put("known_body_measurements",knownSavedEngineeringFacts);
        if(!latestPlanningAssessment.isEmpty()){Map<String,Object> assessment=new LinkedHashMap<>();for(String key:List.of("ready","complete","blocked","incomplete_reasons","target_scope","requested_configuration_items_present","required_deletion"))if(latestPlanningAssessment.containsKey(key))assessment.put(key,latestPlanningAssessment.get(key));if(latestPlanningAssessment.get("readiness") instanceof Map){Map<?,?> nativeFacts=(Map<?,?>)latestPlanningAssessment.get("readiness");for(String key:List.of("required_deletion","evaluated_region_ids","inspection_errors"))if(nativeFacts.containsKey(key))assessment.put(key,nativeFacts.get(key));}ledger.put("latest_native_readback",assessment);}
        ledger.put("dependency_direction","A record's input_ids are objects it consumes. Its deletion_blockers are consumers/references preventing deletion of that record itself; they do not prove that the record blocks deleting the listed consumer. A confirmed absent old ID cannot still require its own deletion; query a different live target if there is a real remaining dependency.");
        ledger.put("priority","This current native ledger supersedes older checked_data for the same IDs. Saved deletion is already executed; prior measurements are only reusable facts, not proof of rebuilding. Remaining user requirements still need real native actions and readback.");return json(ledger);
    }

    public String unsupportedFactConflict(String response)throws Exception{
        Object parsed=CodexAssistant.parseJson(CodexAssistant.planJson(response));if(!(parsed instanceof Map)||!(((Map<?,?>)parsed).get("unsupported") instanceof List)||((List<?>)((Map<?,?>)parsed).get("unsupported")).isEmpty())return "";
        String claims=String.valueOf(((Map<?,?>)parsed).get("unsupported"));Set<String> cited=new LinkedHashSet<>();
        for(String id:savedDeletionIds(journal,initialDeletionIds))if(claims.contains(id))cited.add(id);
        if(cited.isEmpty())return "";Set<String> present=observeDeletedIds(cited);cited.removeAll(present);if(cited.isEmpty())return "";
        Map<String,Object> current=assessCompletion();latestPlanningAssessment=current;if(Boolean.TRUE.equals(current.get("ready"))||Boolean.TRUE.equals(current.get("blocked")))return "";
        return json(Map.of("kind","unsupported_references_confirmed_deleted_target","cited_absent_ids",cited,"unsupported_claim",claims,"current_task_ledger",CodexAssistant.parseJson(planningLedger()),"guidance","核对unsupported是否把已实际删除的旧ID当成仍待删除或仍受阻对象。只纠正与当前原生事实矛盾的前提；真实权限限制、缺少必要输入或其他对象的真实能力缺口仍须如实保留。不要扩大删除范围，不要自动选择或执行未经请求的动作。"));
    }

    /** Missing scoped inventory cannot become success just because the goal's
     * lightweight requirement projection produced no individual missing items. */
    static List<String> requestedConfigurationFailures(Map<?,?> readiness,boolean scoped){
        Set<String> reasons=new LinkedHashSet<>();
        if(!Boolean.TRUE.equals(readiness.get("requested_configuration_items_present")))reasons.add("尚未确认本轮要求的配置项已完整存在");
        if(scoped&&readiness.get("inspection_errors") instanceof Collection)for(Object error:(Collection<?>)readiness.get("inspection_errors"))if(error!=null&&!String.valueOf(error).isBlank())reasons.add("目标配置读回不完整："+error);
        return new ArrayList<>(reasons);
    }

    private Map<String,Object> assessCompletion(){return assessCompletion(completionTargets());}
    private Map<String,Object> assessCompletion(Map<String,Set<String>> targets){
        if(deletionOnlyGoal(instruction)){
            Set<String> ids=new LinkedHashSet<>();for(Map<?,?> row:journal)if("saved".equals(row.get("status"))&&"delete_object".equals(row.get("kind"))&&row.get("actual") instanceof Map&&((Map<?,?>)row.get("actual")).get("deleted_id") instanceof String)ids.add(((Map<?,?>)row.get("actual")).get("deleted_id").toString());
            return deletionAssessment(instruction,journal,blocked,observeDeletedIds(ids));
        }
        String note="";
        boolean missing=false;
        Set<String> scopedRegions=targets.get("region_ids"),scopedMeshes=targets.get("mesh_ids");boolean scoped=!scopedRegions.isEmpty()||!scopedMeshes.isEmpty();
        Map<String,Object> readiness=currentReadiness(targets);
        if(!explicitDeletionDirective(instruction).isEmpty()){
            Set<String> deletedIds=savedDeletionIds(journal,initialDeletionIds);
            Map<String,Object> deletion=compoundDeletionAssessment(instruction,journal,blocked,observeDeletedIds(deletedIds),initialDeletionIds);
            if(!deletion.isEmpty()){readiness.put("required_deletion",deletion);if(!Boolean.TRUE.equals(deletion.get("ready"))){missing=true;note+="\n配置检查：用户要求的真实删除尚未完成；已有repair仅更新或复用配置，不能作为删除回执。";if(deletion.get("incomplete_reasons") instanceof Collection)for(Object reason:(Collection<?>)deletion.get("incomplete_reasons"))note+="\n删除检查："+reason;}}
        }
        for(String reason:requestedConfigurationFailures(readiness,scoped)){missing=true;note+="\n配置检查："+reason;}
        for(Continuum c:simulation.getContinuumManager().getObjects()) if(Boolean.TRUE.equals(readiness.get("goal_requires_physics_model_completion"))&&c instanceof PhysicsContinuum && simulation.getRegionManager().getRegions().stream().anyMatch(r->(!scoped||scopedRegions.contains(r.getObjectKeyAsString()))&&r.getPhysicsContinuum()==c) && !c.getRequiredModels().isEmpty()) {
            missing=true; note+="\n连续体 "+c.getPresentationName()+" 仍缺少必需模型："+String.join("、",c.getRequiredModels().getKeys());
        }
        Set<String> latestMeshes=new HashSet<>(),liveMeshIds=new HashSet<>();
        if(simulation.has(star.meshing.MeshOperationManager.class)!=null)for(star.meshing.MeshOperation mesh:simulation.get(star.meshing.MeshOperationManager.class).getObjects())liveMeshIds.add(mesh.getObjectKeyAsString());
        for(int index=journal.size()-1;index>=0;index--){Map<String,Object> row=journal.get(index);if(!"saved".equals(row.get("status"))||!(row.get("actual") instanceof Map))continue;Map<?,?> actual=(Map<?,?>)row.get("actual");Object quality=actual.get("quality_snapshot");String meshId=String.valueOf(actual.containsKey("mesh_id")?actual.get("mesh_id"):row.get("id"));if(!(quality instanceof Map)||!liveMeshIds.contains(meshId)||(scoped&&!scopedMeshes.contains(meshId))||!latestMeshes.add(meshId))continue;Object qstatus=((Map<?,?>)quality).get("status"),qerrors=((Map<?,?>)quality).get("read_errors");if("unavailable".equals(qstatus)||(qerrors instanceof List&&!((List<?>)qerrors).isEmpty())){missing=true;note+="\n配置检查：本次执行网格的质量读回不完整，未将网格返回成功当作质量验证通过。";}Object issues=((Map<?,?>)quality).get("mesh_geometry_issues");if(issues instanceof List&&!((List<?>)issues).isEmpty()){missing=true;note+="\n配置检查："+String.valueOf(((List<?>)issues).get(0))+"；当前网格尚不能确认合格，数值见详情。";}}
        String scope=CodexAssistant.completionScope(instruction);if(Boolean.TRUE.equals(readiness.get("goal_requires_full_physics"))&&scope.matches("(?is).*(气动计算|cfd|准备.{0,12}(迭代|求解)|开始.{0,12}迭代).*" )&&!EngineeringIntent.meshForbidden(scope)){
            boolean mesh=false;if(simulation.has(star.meshing.MeshOperationManager.class)!=null)for(star.meshing.MeshOperation op:simulation.get(star.meshing.MeshOperationManager.class).getObjects())if(op instanceof star.meshing.AutoMeshOperation&&(!scoped||scopedMeshes.contains(op.getObjectKeyAsString()))){mesh=true;if(op.isDirty()){missing=true;note+="\n配置检查：网格“"+op.getPresentationName()+"”在几何或设置变更后尚未更新，不能声称可开始迭代。";}}
            if(!mesh){missing=true;note+="\n配置检查：完整气动准备尚没有体网格操作。";}
        }
        if(readiness.get("incomplete_reasons") instanceof List&&!((List<?>)readiness.get("incomplete_reasons")).isEmpty()){missing=true;note+="\n配置检查："+String.join("；",((List<?>)readiness.get("incomplete_reasons")).stream().map(String::valueOf).toList());}
        List<String> reasons=new ArrayList<>();for(String line:note.split("\n"))if(!line.isBlank())reasons.add(line.trim());
        Map<String,Object> result=new LinkedHashMap<>();boolean ready=!missing&&!blocked;
        result.put("ready",ready);result.put("complete",ready);result.put("blocked",blocked);
        result.put("incomplete_reasons",reasons);result.put("target_scope",targets);result.put("readiness",readiness);return result;
    }

    public Map<String,Object> finish(String explanation,boolean incomplete) throws IOException {
        Map<String,Object> checked=assessCompletion();boolean missing=!Boolean.TRUE.equals(checked.get("ready"));
        @SuppressWarnings("unchecked") List<String> reasons=(List<String>)checked.get("incomplete_reasons");
        note=reasons.isEmpty()?"":"\n"+String.join("\n",reasons);
        if(missing){recordInspection("model_completion_statement","*",0,explanation==null?"":explanation);String stopped=incomplete&&explanation!=null?plainFailure(explanation):"";if(stopped!=null&&stopped.length()>400)stopped=stopped.substring(0,400)+"…（完整原因见详情）";note="尚未满足本轮配置要求。"+(stopped==null||stopped.isBlank()?"":"\n停止原因："+stopped)+note;}else note=explanation;
        if(!blocked) outcome=incomplete || missing ? "incomplete" : journal.isEmpty() ? "no_changes" : "completed";
        if(journal.isEmpty())note="本任务未执行任何写入。\n"+note;
        writeReport();
        return Map.of("continue",false,"summary",note,"report",reportBase+".md");
    }

    public String chatSummary() { return ChangeSummary.render(journal,note,outcome); }
    static String plainFailure(String text) { String reason=CodexAssistant.failureReason(text);return reason==null?CodexAssistant.userFacingMessage(text):reason; }

    public String taskStatus() { return outcome; }
    public String taskHistory() {
        List<Map<String,Object>> compact=new ArrayList<>();for(Map<String,Object> row:journal){Map<String,Object> item=new LinkedHashMap<>();for(String key:List.of("kind","status","id","target_scope"))if(row.containsKey(key))item.put(key,row.get(key));
            item.put("parameter",String.valueOf(row.get("parameter")).split("\\n",2)[0]);
            Object actual=row.get("actual");item.put("actual",actual instanceof Map?historyActual((Map<?,?>)actual):actual);
            if(row.get("created_output_ids") instanceof Collection){Collection<?> created=(Collection<?>)row.get("created_output_ids");item.put("created_output_count",created.size());List<Object> exampleIds=new ArrayList<>();for(Object id:created){if(exampleIds.size()>=16)break;exampleIds.add(id);}item.put("created_output_ids",exampleIds);if(created.size()>exampleIds.size())item.put("created_output_note","完整新建依赖ID见任务报告，实际事务目标以actual中的region_id/mesh_id/domain_id等为准");}
            Object recommended=row.get("recommended");if(recommended instanceof Map){Map<String,Object> v=new LinkedHashMap<>();for(Map.Entry<?,?> entry:((Map<?,?>)recommended).entrySet())if(!Set.of("expected_state","expected_states","reason").contains(entry.getKey()))v.put(String.valueOf(entry.getKey()),entry.getValue());item.put("requested",v);}else item.put("requested",recommended);compact.add(item);
        }return json(compact);
    }
    /** Keep decisive receipts ahead of detailed readiness: controller tool messages
     * have a budget, so a large boundary inventory must not hide saved control roles. */
    static Map<String,Object> historyActual(Map<?,?> actual){
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("workflow","requested_action","mode","domain_mode","status","meshing_executed","body_id","mesh_id","domain_id","boolean_id","fluid_id","region_id","region_recreation","reference_updates","created_id","created_type","deleted_id","boundary_type","name","cell_count","validation","domain_bounds_m","reference_length_m","domain_scale_m","boundary_roles","postconditions","accepted_postconditions","prism_settings","prismSettings","mesh_validity","prism_enabled","boundary_id","reused_existing","reused_id","selection_ids","mesh_consistency_check","method","readback","before","actual","report","monitor","plot","configured","data_status","verified","target_name","target_id","property","reference_area_m2","reference_density_kg_m3","reference_velocity_m_s","direction","origin_m","normal","monitor_ids","field_id","range"))if(actual.containsKey(key))result.put(key,actual.get(key));
        for(String key:List.of("created","updated"))if(actual.get(key) instanceof Map){Map<?,?> descriptor=(Map<?,?>)actual.get(key);Map<String,Object> facts=new LinkedHashMap<>();for(String field:List.of("id","name","type","prism_enabled","prism_settings","meshers","dirty","geometry_current","producer_operation_ids","dirty_producer_operation_ids"))if(descriptor.containsKey(field))facts.put(field,descriptor.get(field));if(!facts.isEmpty())result.put(key,facts);}
        Object rawSteps=actual.get("steps");if(rawSteps instanceof List){List<Object> roles=new ArrayList<>(),steps=new ArrayList<>();for(Object raw:(List<?>)rawSteps){if(steps.size()>=32)break;if(!(raw instanceof Map))continue;Map<?,?> source=(Map<?,?>)raw;Map<String,Object> step=new LinkedHashMap<>();for(String key:List.of("step","operation","kind","type","action","control","control_id","enabled","purpose","name","id","status","validation","role","created_id","updated_id","cell_count","boundary_type"))if(source.containsKey(key))step.put(key,source.get(key));if(source.containsKey("purpose")||source.containsKey("control"))roles.add(new LinkedHashMap<>(step));
            if(source.get("actual") instanceof Map){Map<?,?> receipt=(Map<?,?>)source.get("actual");Map<String,Object> values=new LinkedHashMap<>();for(String key:List.of("profile","model_chain_complete","required_models","wall_treatment","space_dimension","enabled_models_added","disabled_conflicting_models","boundary_ids","mach","static_temperature_K","static_pressure_Pa","speed_m_s","velocity_m_s","validation"))if(receipt.containsKey(key))values.put(key,receipt.get(key));if(!values.isEmpty())step.put("actual",values);}
            if(step.isEmpty())step.put("summary","详见任务报告的原始收据");steps.add(step);
        }if(!roles.isEmpty())result.put("control_roles",roles);result.put("steps",steps);}
        if(actual.get("readiness") instanceof Map){Map<?,?> source=(Map<?,?>)actual.get("readiness");Map<String,Object> readiness=new LinkedHashMap<>();for(String key:List.of("requested_configuration_items_present","flow_solver_basics_ready","incomplete_reasons","flow_solver_incomplete_reasons","boundary_counts","goal_requirements","inspection_errors"))if(source.containsKey(key))readiness.put(key,source.get(key));result.put("readiness",readiness);}
        for(String key:List.of("surface_ids","target_surface_ids","disabled_surface_ids","configured_control_ids"))if(actual.containsKey(key))result.put(key,actual.get(key));
        return result;
    }
    public boolean hasActiveTask() { return reportBase!=null && (outcome.equals("planning") || outcome.equals("continuing") || outcome.equals("applied")); }
    public String latestReport() throws IOException {
        return reportBase==null ? "尚未生成修改报告。" : "报告文件："+reportBase+".md\n\n"+Files.readString(Path.of(reportBase+".md"),StandardCharsets.UTF_8);
    }

    private void writeReport() throws IOException {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("time",Instant.now().toString()); report.put("simulation",savePath.toString());
        report.put("request",instruction);report.put("completion_scope",CodexAssistant.completionScope(instruction));report.put("elapsed_seconds",(System.nanoTime()-taskStartedNano)/1e9);report.put("performance",metrics); report.put("status",outcome); report.put("note",note);
        report.put("inspections",inspections); report.put("changes",journal); report.put("recovery_files",backups); report.put("discovery_limits",omissions);
        atomicWrite(Path.of(reportBase+".json"),json(report));
        StringBuilder md=new StringBuilder("# 仿真修改报告\n\n状态："+outcome+"\n\n仿真："+savePath+"\n\n需求："+instruction+"\n\n"+note+"\n\n");
        for(Map<String,Object> row:journal) {
            if(List.of("create_sum_report","execute_mesh").contains(row.get("kind"))) {
                md.append("## ").append(row.get("parameter").toString().split("\n",2)[0]).append("\n\n");
                if(row.get("actual") instanceof Map) {
                    Map<?,?> actual=(Map<?,?>)row.get("actual");
                    if(actual.containsKey("definition"))md.append("- 报告定义：").append(actual.get("definition")).append('\n')
                        .append("- 单位：").append(actual.get("unit")).append('\n');
                    if(actual.containsKey("dirty"))md.append("- 执行后是否仍需更新：").append(actual.get("dirty")).append('\n');
                    md.append("- 校验结果：").append(actual.get("validation")).append('\n');
                }
                md.append("- 状态：").append(row.get("status")).append('\n')
                    .append("- 原因：").append(row.get("reason")).append("\n\n");
            } else md.append("## ").append(row.get("parameter")).append("\n\n")
                .append("- 原值：").append(json(row.get("before"))).append('\n')
                .append("- 建议值：").append(json(row.get("recommended"))).append(' ').append(row.get("unit")).append('\n')
                .append("- 实际读回：").append(json(row.get("actual"))).append('\n')
                .append("- 状态：").append(row.get("status")).append('\n')
                .append("- 原因：").append(row.get("reason")).append("\n\n");
        }
        if(!metrics.isEmpty())md.append("## 执行与模型调用统计\n\n```json\n").append(json(metrics)).append("\n```\n\n");
        if(!inspections.isEmpty()){md.append("## 实际读取记录\n\n");for(Map<String,Object> entry:inspections)md.append("### ").append(entry.get("scope")).append(" / ").append(entry.get("query")).append(" / offset=").append(entry.get("offset")).append("\n\n```text\n").append(entry.get("result")).append("\n```\n\n");}
        md.append("## 恢复副本\n\n").append(String.join("\n\n",backups))
            .append("\n\n读回校验不代表工程设置合理或求解收敛。网格执行仅在明确请求时进行，其操作完成不代表网格质量已验证。是否执行求解以本任务run_solver记录为准。场函数只验证定义写入；其实际求值仍需检查。\n");
        if(!omissions.isEmpty())md.append("\n## 目录发现限制\n\n").append(String.join("\n\n",omissions));
        atomicWrite(Path.of(reportBase+".md"),md.toString());
    }

    private static void atomicWrite(Path destination,String text) throws IOException {
        Path temporary=Files.createTempFile(destination.getParent(),"report-",".tmp");
        try {
            Files.writeString(temporary,text,StandardCharsets.UTF_8);
            try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static double finite(Object value) throws IOException {
        if(!(value instanceof Number) || !Double.isFinite(((Number)value).doubleValue())) throw new IOException("数值必须是有限数字");
        return ((Number)value).doubleValue();
    }
    private static void integral(Object value,long min,long max) throws IOException {
        double v=finite(value); if(v<min || v>max || Math.rint(v)!=v) throw new IOException("整数越界或包含小数");
    }
    private static void validateBounds(Slot slot,double value) throws IOException {
        String range=slot.bounds.trim();
        if(range.length()<5)return;
        String[] endpoints=range.substring(1,range.length()-1).split("\\s*(?:,|\\.\\.)\\s*");
        if(endpoints.length!=2)return;
        try {
            double low=Double.parseDouble(endpoints[0].trim()),high=Double.parseDouble(endpoints[1].trim());
            if(value<low || value>high || (range.startsWith("(") && value==low) || (range.endsWith(")") && value==high))
                throw new IOException("参数超出STAR声明范围 "+range+"："+slot.path);
        } catch(NumberFormatException ignored) { /* Unrecognized STAR range stays descriptive only. */ }
    }
    static String stateToken(Object state) {
        try { return "state:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json(state).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private static boolean equal(Object a,Object b) {
        if(a instanceof Number && b instanceof Number) { double x=((Number)a).doubleValue(),y=((Number)b).doubleValue(); return Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x-y)<=Math.max(1e-14,1e-9*Math.max(Math.abs(x),Math.abs(y))); }
        if(a instanceof List && b instanceof List) {
            List<?> x=(List<?>)a,y=(List<?>)b;if(x.size()!=y.size())return false;
            for(int i=0;i<x.size();i++)if(!equal(x.get(i),y.get(i)))return false;return true;
        }
        return Objects.equals(a,b);
    }
    static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof String)return CodexAssistant.jsonString((String)value);
        if(value instanceof Boolean || value instanceof Number)return value.toString();
        if(value instanceof Map) { List<String> fields=new ArrayList<>();((Map<?,?>)value).forEach((k,v)->fields.add(json(k.toString())+":"+json(v)));return "{"+String.join(",",fields)+"}"; }
        if(value instanceof Collection) { List<String> items=new ArrayList<>();for(Object item:(Collection<?>)value)items.add(json(item));return "["+String.join(",",items)+"]"; }
        return json(value.toString());
    }
    private static String message(Throwable error) { while(error.getCause()!=null)error=error.getCause();return error.getMessage()==null?error.toString():error.getMessage(); }
}
