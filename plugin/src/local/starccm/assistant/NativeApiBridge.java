package local.starccm.assistant;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import star.base.neo.ClientServerObject;
import star.base.neo.DoubleVector;
import star.common.*;

/** Task-local, object-rooted access to public STAR configuration properties.
 * Exact public signatures are discovered from the installed runtime. There is no
 * source-code evaluation, class construction, static call or opaque property-key
 * access. Lifecycle operations keep using the dedicated, guarded workflows.
 * The caller owns serialization, backup, final save and task-level reports.
 */
public final class NativeApiBridge {
    private static final int MAX_REFS=4096, MAX_TOKENS=2048, MAX_PATH=8, PAGE=12;
    private static final Set<String> ACTION_KEYS=Set.of("type","id","target_id","path","expected_state","method","arguments","readback","expected_after","tolerance","reason");
    private static final Set<String> BLOCKED_READ_NAMES=Set.of("getClass","getParent","getRoot","getRootObject","getSimulation","getServer","getObjectRegistry","getProperty","getClientServerObject","getClientServerObjectKey","getObjectKey","getJournalKey","getJournal","getInput","getOutput","getInternalValue","getInternalVector","getBeanPropertyDescriptors","getLinkedBeanPropertyDescriptors","getMethod","getElementType","getSelectedInput","getOption","getInputOptions");
    private static final Set<String> BLOCKED_PROPERTIES=Set.of("BoundaryType","PhysicsContinuum","Continuum","Method","MethodType","Model","Models","Enabled","Enable","Active","Query","TagsInput","Parts","InputParts","OutputParts","Objects","Regions","Boundaries","Region","Boundary","Meshers","MeshOperation","Geometry","Source","Target","File","Path","Filename","FileName","Input","InternalValue","InternalVector","Option","SelectedInput","ConnectionMode","CollaborationEnabled","State","Status","Running","Paused","Locked","RequestedState");
    private final Simulation simulation;
    private final Supplier<Map<String,ClientServerObject>> rootSupplier;
    private final Map<String,Ref> refs=new LinkedHashMap<>();
    private final Map<String,Token> tokens=new LinkedHashMap<>();
    private String epoch=UUID.randomUUID().toString();
    private boolean blocked;

    private static final class Step {
        final Method method; final Object[] args; final String selectedId;
        Step(Method method,Object[] args,String selectedId){this.method=method;this.args=args.clone();this.selectedId=selectedId;}
    }
    private static final class Ref {
        final String handle,rootKey,objectId,className; final List<Step> path;
        Ref(String handle,String rootKey,ClientServerObject object,List<Step> path){this.handle=handle;this.rootKey=rootKey;this.objectId=object.getObjectKeyAsString();this.className=object.getClass().getName();this.path=List.copyOf(path);}
    }
    private static final class Token {
        final Ref ref;final Method getter;final Object before;
        Token(Ref ref,Method getter,Object before){this.ref=ref;this.getter=getter;this.before=before;}
    }
    private static final class Planned {
        Ref ref;ClientServerObject target;Method setter,getter;Object[] args;Object before,expected;String token;double tolerance;
    }
    public static final class ApiExecutionException extends IOException {
        private final Map<String,Object> receipt;
        ApiExecutionException(String message,Throwable cause,Map<String,Object> receipt){super(message,cause);this.receipt=new LinkedHashMap<>(receipt);}
        public Map<String,Object> receipt(){return new LinkedHashMap<>(receipt);}
    }

    public NativeApiBridge(Simulation simulation,Supplier<Map<String,ClientServerObject>> roots){this.simulation=Objects.requireNonNull(simulation);this.rootSupplier=Objects.requireNonNull(roots);}
    public synchronized void resetTask(){epoch=UUID.randomUUID().toString();refs.clear();tokens.clear();blocked=false;}

    public Map<String,Object> contract(){return Map.of(
        "action","invoke_api",
        "inspection","api_object: query=* lists current SIM roots (query/name filter optional). Use target_id=live object ID or object_ref, optional path=[exact zero-argument getters or {method:exact safe getObject/has signature,arguments:[typed argument]}]. A manager child can be read with getObject(java.lang.String), arguments=[existing name]; has(java.lang.Class) only checks an already existing installed type. Add readback=getFoo() to capture its current value and expected_state token. api_read uses method plus optional arguments for a documented safe read. Method lists are paged, not invoked en masse.",
        "write","invoke_api id=object_ref,expected_state=inspection token,method=exact discovered setFoo(type),arguments=[typed value],readback=paired getFoo()/isFoo(),expected_after=desired readback,reason=requested change. One property per action. After every write inspect again; tokens are single-use. No independent API authorization confirmation is needed for an already authorized change.",
        "physical_quantities","Physical dimensional numbers/vectors require setValueAndUnits(double,star.common.Units) / setVectorAndUnits(star.base.neo.DoubleVector,star.common.Units) and getSIValue()/getSIVector(). Units argument is {object_ref:actual Units reference}; expected_after is in SI. Bare setValue/setVector on physical quantities is rejected.",
        "argument_types","Finite primitive numbers/booleans, bounded strings, enum constant names, DoubleVector/numeric arrays and {object_ref:live same-SIM reference}. No arbitrary classes, constructors, code, opaque property keys, files, connections or static invocations.",
        "coverage","Public paired configuration setters and bounded getters on live objects reached from the current selected SIM. Object creation/deletion, model enable/disable/type changes, geometry lifecycle, mesh generation and solver execution must use guarded typed actions. This is not universal arbitrary Java execution.");}

    public synchronized Map<String,Object> inspect(Map<?,?> request)throws Exception {
        checkThread();String requested=targetKey(request,false);String query=text(request.get("query"));
        if(requested.isEmpty()||requested.equals("*")||!request.containsKey("id")&&!request.containsKey("target_id")&&!requested.startsWith("api:")&&!knownRoot(requested))return roots(query,number(request.get("offset"),0));
        Ref ref=navigate(resolve(requested),request.get("path"));ClientServerObject target=fresh(ref);
        Map<String,Object> out=descriptor(ref,target);String method=text(request.get("method")),readback=text(request.get("readback"));
        if(!method.isEmpty()){
            Method getter=exact(target.getClass(),method);require(allowRead(getter),"该方法不是开放的只读配置访问；请用专用操作或文档检索。");Object[] args=arguments(getter,request.get("arguments"),target,true);
            Object value=invoke(getter,target,args);out.put("method",signature(getter));out.put("value",encode(value,ref,getter,args));
            if(getter.getParameterCount()==0&&isValueType(getter.getReturnType()))issueToken(out,ref,getter,value);
        }else if(!readback.isEmpty()){
            Method getter=exact(target.getClass(),readback);require(allowRead(getter)&&getter.getParameterCount()==0&&isValueType(getter.getReturnType()),"readback必须是受支持的无参数属性getter");
            Object value=invoke(getter,target,new Object[0]);out.put("readback",signature(getter));out.put("value",encode(value,ref,getter,new Object[0]));issueToken(out,ref,getter,value);
        }
        out.put("methods",methods(target,query.equals(requested)?"":query,number(request.get("offset"),0)));out.put("task_blocked_after_partial_write",blocked);return out;
    }

    public synchronized Map<String,Object> validate(Map<?,?> action,String goal)throws Exception {
        checkThread();Planned p=plan(action,goal);Map<String,Object> out=receipt(p);out.put("status","validated_no_write");out.put("modified",false);out.put("verified",false);return out;
    }

    public synchronized Map<String,Object> execute(Map<?,?> action,String goal)throws Exception {
        checkThread();require(!blocked,"前次API写入未通过核验；当前任务已阻止后续写入，请检查失败收据后开始新任务。");
        // Re-resolve receiver/argument handles and re-read before value here, after
        // the caller's checkpoint. Preview never authorizes a stale later write.
        Planned p=plan(action,goal);require(simulation.isValid()&&!simulation.isBusy()&&!simulation.isMeshing(),"当前SIM已关闭、正在求解或划网格，未调用setter。");
        Map<String,Object> out=receipt(p);out.put("modified",false);out.put("verified",false);boolean attempted=false;
        try{
            fresh(p.ref);Object before=canonical(invoke(p.getter,p.target,new Object[0]));require(equal(before,p.before,0),"写入前属性已变化，请重新读取。");
            CodexAssistant.checkConfigurationReadCancelled();require(!Thread.currentThread().isInterrupted(),"操作已取消，未调用setter。");
            if(equal(before,p.expected,0)){tokens.remove(p.token);out.put("save_required",false);out.put("actual",before);out.put("skipped",true);out.put("modified",false);out.put("verified",true);out.put("complete",true);out.put("status","already_satisfied");out.put("modification_status","unchanged");out.put("validation","当前值已符合目标，跳过重复写入");out.put("summary",targetName(p.target)+"：当前值已符合目标，跳过重复写入");return out;}
            tokens.remove(p.token);out.put("status","setter_attempted");out.put("modified",true);out.put("modification_status","setter_called_outcome_not_yet_verified");attempted=true;
            invoke(p.setter,p.target,p.args);simulation.getServer().getClientNotifyHandler().processCommandQueue(true);
            ClientServerObject same=fresh(p.ref);Object actual=canonical(invoke(p.getter,same,new Object[0]));out.put("actual",actual);
            require(equal(actual,p.expected,p.tolerance),"setter已调用，但属性读回与目标不一致；保留部分结果，不自动重试或回滚。");
            out.put("verified",true);out.put("complete",true);out.put("status","verified_in_memory");out.put("modification_status","readback_verified_save_owned_by_caller");out.put("summary",targetName(same)+"："+p.getter.getName()+" "+simple(p.before)+" → "+simple(actual)+"（已读回，等待统一保存）");
            // All prior expected-state receipts are invalid after a write, even if
            // another property happens to retain the same value.
            tokens.clear();return out;
        }catch(Exception error){
            out.put("complete",false);out.put("verified",false);out.put("error",message(error));out.put("status",attempted?"failed_after_setter_attempt":"failed_before_write");
            if(attempted){blocked=true;tokens.clear();out.put("modified_maybe",true);try{out.put("actual",canonical(invoke(p.getter,fresh(p.ref),new Object[0])));}catch(Exception readError){out.put("readback_error",message(readError));}}
            throw new ApiExecutionException("通用API操作未完成："+message(error),error,out);
        }
    }

    private Planned plan(Map<?,?> a,String goal)throws Exception {
        require(!blocked,"前次API写入失败，当前任务不能继续写入。");require("invoke_api".equals(text(a.get("type"))),"动作类型必须为invoke_api");
        for(Object key:a.keySet())require(ACTION_KEYS.contains(String.valueOf(key)),"不支持的API动作字段: "+key);
        require(!text(goal).isBlank()&&!text(a.get("reason")).isBlank(),"API修改须关联当前用户目标及明确修改原因。");
        require(!CodexAssistant.nativeApiReadOnlyGoal(goal),"当前用户目标限定只读，未执行API修改。");
        Planned p=new Planned();p.ref=navigate(resolve(targetKey(a,true)),a.get("path"));p.target=fresh(p.ref);p.token=text(a.get("expected_state"));Token token=tokens.get(p.token);
        require(token!=null&&token.ref.handle.equals(p.ref.handle),"expected_state缺失、已失效或不属于当前目标；请api_object重新读取readback。");
        p.setter=exact(p.target.getClass(),text(a.get("method")));require(allowWrite(p.setter,p.target.getClass()),"该方法不属于开放的配置setter；结构、生命周期和执行操作必须用专用动作。");
        p.getter=exact(p.target.getClass(),text(a.get("readback")));require(signature(p.getter).equals(signature(token.getter)),"readback与状态令牌记录不一致。");
        require(allowRead(p.getter)&&p.getter.getParameterCount()==0&&isValueType(p.getter.getReturnType()),"写后读回必须是开放的无参数属性getter");
        require(paired(p.target,p.setter,p.getter),"setter与readback不是同一个可验证属性，禁止用无关getter充当成功证据。");
        p.args=arguments(p.setter,a.get("arguments"),p.target,false);p.before=canonical(invoke(p.getter,p.target,new Object[0]));require(equal(p.before,token.before,0),"属性值自检查后已变化，请重新读取。");
        p.expected=expected(p.target,p.setter,p.getter,p.args);require(a.containsKey("expected_after"),"须明确给出expected_after读回目标");
        Object supplied=canonicalExpected(a.get("expected_after"));if(p.args.length==1&&p.args[0] instanceof Float&&supplied instanceof Number)supplied=((Number)supplied).floatValue();require(equal(supplied,p.expected,1e-12),"expected_after与setter参数计算出的实际目标不一致（物理量须使用SI）。");
        p.tolerance=a.containsKey("tolerance")?finite(a.get("tolerance")):1e-10;require(p.tolerance>=0&&p.tolerance<=1e-6,"读回容差必须在0至1e-6之间；不能放宽至无法验证修改。");
        return p;
    }

    private Map<String,Object> receipt(Planned p){Map<String,Object> out=new LinkedHashMap<>();out.put("type","invoke_api");out.put("object_ref",p.ref.handle);out.put("target_id",p.ref.objectId);out.put("target_name",targetName(p.target));out.put("target_class",p.target.getClass().getName());out.put("method",signature(p.setter));out.put("readback",signature(p.getter));out.put("before",p.before);out.put("expected_after",p.expected);out.put("tolerance",p.tolerance);out.put("property",p.getter.getName().replaceFirst("^(get|is)",""));out.put("parameter",targetName(p.target)+" / "+p.getter.getName().replaceFirst("^(get|is)",""));out.put("unit",p.target instanceof ScalarPhysicalQuantity||p.target instanceof VectorPhysicalQuantity?"SI":"");out.put("save_required",true);return out;}
    private void issueToken(Map<String,Object> out,Ref ref,Method getter,Object value)throws Exception {require(tokens.size()<MAX_TOKENS,"本任务属性检查数量达到上限，请开始新任务。");String token="api-state:"+epoch+":"+UUID.randomUUID();tokens.put(token,new Token(ref,getter,canonical(value)));out.put("state_token",token);out.put("expected_state",token);out.put("state_scope","Exact current object + selected readback property; one write only, fresh read before invocation.");}

    private Map<String,Object> roots(String query,int offset)throws Exception {
        Map<String,ClientServerObject> roots=currentRoots();List<Map<String,Object>> rows=new ArrayList<>();String filter=query==null?"":query.toLowerCase(Locale.ROOT);if(filter.equals("*"))filter="";int matched=0;
        for(Map.Entry<String,ClientServerObject> e:roots.entrySet()){ClientServerObject object=e.getValue();String hay=(e.getKey()+" "+targetName(object)+" "+object.getClass().getName()).toLowerCase(Locale.ROOT);if(!filter.isEmpty()&&!hay.contains(filter))continue;int index=matched++;if(index<offset||index>=offset+PAGE)continue;Ref ref=rootRef(e.getKey(),object);Map<String,Object> row=descriptor(ref,object);row.put("root_key",e.getKey());rows.add(row);}
        Map<String,Object> out=new LinkedHashMap<>();out.put("matched",matched);out.put("total_matches",matched);out.put("offset",offset);out.put("nextOffset",offset+PAGE<matched?offset+PAGE:null);out.put("more",offset+PAGE<matched);out.put("roots",rows);out.put("contract",contract());return out;
    }
    private Map<String,ClientServerObject> currentRoots()throws Exception {
        Map<String,ClientServerObject> supplied=rootSupplier.get();require(supplied!=null,"当前SIM对象目录不可用");Map<String,ClientServerObject> out=new LinkedHashMap<>();out.put("simulation",simulation);Set<String> seen=new HashSet<>();seen.add(simulation.getObjectKeyAsString());
        for(Map.Entry<String,ClientServerObject> e:supplied.entrySet()){ClientServerObject object=e.getValue();if(object==null||!safeObjectClass(object.getClass()))continue;assertSameSimulation(object);if(seen.add(object.getObjectKeyAsString()))out.put(e.getKey(),object);}return out;
    }
    private boolean knownRoot(String key)throws Exception {for(Map.Entry<String,ClientServerObject> e:currentRoots().entrySet())if(key.equals(e.getKey())||key.equals(e.getValue().getObjectKeyAsString()))return true;return false;}
    private Ref rootRef(String key,ClientServerObject object)throws Exception {for(Ref ref:refs.values())if(ref.rootKey.equals(key)&&ref.path.isEmpty()&&same(ref,object))return ref;return addRef(key,object,List.of());}
    private Ref addRef(String root,ClientServerObject object,List<Step> path)throws Exception {require(path.size()<=32,"对象路径深度超过32层，请选择更近的当前SIM根对象。");assertSameSimulation(object);require(safeObjectClass(object.getClass()),"该对象属于连接、文件、界面或执行控制器，不能作为通用API目标。");for(Ref r:refs.values())if(r.rootKey.equals(root)&&r.path.size()==path.size()&&same(r,object)&&samePath(r.path,path))return r;require(refs.size()<MAX_REFS,"本任务对象引用达到上限，请开始新任务。");Ref ref=new Ref("api:"+epoch+":"+UUID.randomUUID(),root,object,path);refs.put(ref.handle,ref);return ref;}
    private static boolean samePath(List<Step>a,List<Step>b){for(int i=0;i<a.size();i++)if(!signature(a.get(i).method).equals(signature(b.get(i).method))||!Objects.equals(a.get(i).selectedId,b.get(i).selectedId)||!Arrays.deepEquals(a.get(i).args,b.get(i).args))return false;return true;}
    private Ref resolve(String key)throws Exception {
        require(!key.isBlank(),"必须给出当前SIM对象ID或object_ref");if(key.startsWith("api:")){Ref r=refs.get(key);require(r!=null,"对象引用不属于当前任务或已过期");fresh(r);return r;}
        for(Map.Entry<String,ClientServerObject> e:currentRoots().entrySet())if(key.equals(e.getKey())||key.equals(e.getValue().getObjectKeyAsString()))return rootRef(e.getKey(),e.getValue());
        throw new IOException("当前选中SIM目录中没有该对象: "+key);
    }
    private Ref navigate(Ref base,Object path)throws Exception {
        if(path==null)return base;require(path instanceof List&&((List<?>)path).size()<=MAX_PATH,"path须为最多8项的getter列表");Ref ref=base;
        for(Object raw:(List<?>)path){String name;Object rawArgs=null;if(raw instanceof String)name=(String)raw;else{require(raw instanceof Map,"path每项须为精确getter签名或method/arguments对象");Map<?,?> item=(Map<?,?>)raw;require(item.keySet().stream().allMatch(x->Set.of("method","arguments").contains(x)),"path不接受其他操作字段");name=text(item.get("method"));rawArgs=item.get("arguments");}ClientServerObject current=fresh(ref);Method getter=exact(current.getClass(),name);require(allowRead(getter),"path仅允许开放的配置getter");Object[]args=arguments(getter,rawArgs,current,true);Object result=invoke(getter,current,args);require(result instanceof ClientServerObject,"path getter未返回单个当前SIM对象；集合请先api_read获取具体object_ref");List<Step> steps=new ArrayList<>(ref.path);steps.add(new Step(getter,args,null));ref=addRef(ref.rootKey,(ClientServerObject)result,steps);}return ref;
    }
    private ClientServerObject fresh(Ref ref)throws Exception {
        Map<String,ClientServerObject> roots=currentRoots();ClientServerObject current=roots.get(ref.rootKey);require(current!=null,"引用的根对象已不存在，请刷新目录。");
        for(Step step:ref.path){require(allowRead(step.method),"引用路径不再允许读取");Object value=invoke(step.method,current,step.args);if(step.selectedId!=null){require(value instanceof Collection||value!=null&&value.getClass().isArray(),"集合引用的返回类型已变化");ClientServerObject selected=null;for(Object item:elements(value))if(item instanceof ClientServerObject&&((ClientServerObject)item).getObjectKeyAsString().equals(step.selectedId))selected=(ClientServerObject)item;require(selected!=null,"引用对象已离开原集合，请重新检查");current=selected;}else{require(value instanceof ClientServerObject,"引用路径不再返回对象");current=(ClientServerObject)value;}assertSameSimulation(current);}
        require(same(ref,current),"对象已重建或类型变化，旧引用已失效，请重新检查。");assertSameSimulation(current);return current;
    }
    private void assertSameSimulation(ClientServerObject object)throws Exception {require(object==simulation||object.isFromSameSimulation(simulation),"对象不属于当前面板选中的SIM，未执行。");}
    private static boolean same(Ref ref,ClientServerObject object){return ref.objectId.equals(object.getObjectKeyAsString())&&ref.className.equals(object.getClass().getName());}

    private Map<String,Object> descriptor(Ref ref,ClientServerObject target){Map<String,Object> row=new LinkedHashMap<>();row.put("object_ref",ref.handle);row.put("object_id",ref.objectId);row.put("class",ref.className);row.put("name",targetName(target));return row;}
    private Object encode(Object value,Ref parent,Method getter,Object[]args)throws Exception {
        if(value instanceof ClientServerObject){List<Step> path=new ArrayList<>(parent.path);path.add(new Step(getter,args,null));Ref ref=addRef(parent.rootKey,(ClientServerObject)value,path);return descriptor(ref,(ClientServerObject)value);}
        if(value instanceof Collection||value!=null&&value.getClass().isArray()){List<Object> list=new ArrayList<>();List<Object> values=elements(value);require(values.size()<=1000,"getter返回集合超过1000项；请通过现有分页查询缩小对象范围。");for(Object x:values){if(x instanceof ClientServerObject){List<Step> path=new ArrayList<>(parent.path);path.add(new Step(getter,args,((ClientServerObject)x).getObjectKeyAsString()));Ref ref=addRef(parent.rootKey,(ClientServerObject)x,path);list.add(descriptor(ref,(ClientServerObject)x));}else list.add(canonical(x));}return list;}return canonical(value);
    }
    private Map<String,Object> methods(ClientServerObject target,String query,int offset){List<Map<String,Object>> list=new ArrayList<>();String filter=text(query).toLowerCase(Locale.ROOT);if(filter.equals("*")||filter.startsWith("api:")||filter.equals(target.getObjectKeyAsString().toLowerCase(Locale.ROOT)))filter="";Set<String> seen=new HashSet<>();Method[] all=target.getClass().getMethods();Arrays.sort(all,Comparator.comparing(NativeApiBridge::signature));
        for(Method m:all){boolean read=allowRead(m),write=allowWrite(m,target.getClass());String sig=signature(m);if((!read&&!write)||!seen.add(sig)||!filter.isEmpty()&&!sig.toLowerCase(Locale.ROOT).contains(filter))continue;Map<String,Object> row=new LinkedHashMap<>();row.put("signature",sig);row.put("declaring_class",m.getDeclaringClass().getName());row.put("return_type",type(m.getReturnType()));row.put("access",read?"read":"paired_write");if(write)row.put("requires","fresh readback token; paired getter; typed args; expected_after; backup/save by caller");list.add(row);}
        Map<String,Object> out=new LinkedHashMap<>();out.put("items",list.subList(Math.min(offset,list.size()),Math.min(offset+PAGE,list.size())));out.put("matched",list.size());out.put("offset",offset);out.put("nextOffset",offset+PAGE<list.size()?offset+PAGE:null);out.put("more",offset+PAGE<list.size());if(target instanceof EnumeratedOption){List<String> choices=new ArrayList<>();for(Object e:((EnumeratedOption<?>)target).getElements())choices.add(((Enum<?>)e).name());out.put("enum_choices",choices);}if(target instanceof ScalarPhysicalQuantity||target instanceof VectorPhysicalQuantity)out.put("units_policy","Use explicit actual Units reference and SI readback; bare dimensional setter is not exposed for invocation.");return out;
    }

    static String signature(Method method){StringJoiner p=new StringJoiner(",");for(Class<?> c:method.getParameterTypes())p.add(type(c));return method.getName()+"("+p+")";}
    private static String type(Class<?> c){return c.isArray()?type(c.getComponentType())+"[]":c.getName();}
    private static Method exact(Class<?> c,String signature)throws Exception {require(signature!=null&&signature.length()<=400&&!signature.contains(" "),"method须为精确公开方法签名，例如setValue(double)");for(Method m:c.getMethods())if(!m.isBridge()&&!m.isSynthetic()&&signature(m).equals(signature))return m;throw new IOException("当前安装版本/实际对象不存在该公开签名: "+signature);}
    private static boolean basic(Method m){return Modifier.isPublic(m.getModifiers())&&!Modifier.isStatic(m.getModifiers())&&!m.isBridge()&&!m.isSynthetic()&&m.getDeclaringClass().getName().startsWith("star.")&&safeObjectClass(m.getDeclaringClass());}
    static boolean safeObjectClass(Class<?> c){String s=c.getName().toLowerCase(Locale.ROOT);return s.startsWith("star.")&&!s.matches(".*(serverconnection|neoclient|objectregistry|rootprocess|application|license|[.]ui[.]|[.]actions[.]|[.]scenegraph[.]|[.]swing[.]|[.]net[.]|[.]io[.]|executor|iterator|pipelinecontroller|taskmanager|managerManager|rootobjecttools|accesscontrol|command|journal|observable|observer|proxy|javamacro|script|userfunction|externalprocess|filesystem).*".toLowerCase(Locale.ROOT));}
    static boolean allowRead(Method m){
        if(!basic(m)||BLOCKED_READ_NAMES.contains(m.getName()))return false;String n=m.getName(),lower=n.toLowerCase(Locale.ROOT);
        if(!(n.startsWith("get")||n.startsWith("is")||n.startsWith("has"))||n.equals("get")||lower.matches(".*(create|load|initialize|update|execute|generate|compute|evaluate|preview|clone|new|refresh|reset|file|path|session|server|socket|license|application|process|registry|journal|observable|observer|command|connection|internal|debug|stress|host|worker|gpgpu|segmentation|overflow|dividebyzero|cache|select).*"))return n.equals("getSelected")&&m.getParameterCount()==0&&m.getReturnType()==int.class||n.equals("getSelectedElement")&&m.getParameterCount()==0&&Enum.class.isAssignableFrom(m.getReturnType());
        if(m.getParameterCount()==0)return supportedReadReturn(m.getReturnType());
        return n.equals("getObject")&&m.getParameterCount()==1&&m.getParameterTypes()[0]==String.class&&ClientServerObject.class.isAssignableFrom(m.getReturnType())||n.equals("has")&&m.getParameterCount()==1&&m.getParameterTypes()[0]==Class.class&&ClientServerObject.class.isAssignableFrom(m.getReturnType());
    }
    static boolean allowWrite(Method m,Class<?> targetClass){
        if(!basic(m)||m.getReturnType()!=void.class||!m.getName().startsWith("set")||m.getName().length()<=3)return false;
        if(Simulation.class.isAssignableFrom(targetClass)||Units.class.isAssignableFrom(targetClass)||Coordinate.class.isAssignableFrom(targetClass))return false;String property=m.getName().substring(3),p=property.toLowerCase(Locale.ROOT);
        if(property.startsWith("Units"))return false; // Per-axis/display-unit mutation has no generic SI proof.
        if(BLOCKED_PROPERTIES.contains(property)||p.matches(".*(silently|internal|server|session|file|path|input|output|model|methodtype|boundarytype|meshers|connection|query|trigger|callback|listener|enabled|disabled|autostart|autosave|execution|expressionenabled).*"))return false;
        if(ScalarPhysicalQuantity.class.isAssignableFrom(targetClass))return m.getName().equals("setValueAndUnits")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{double.class,Units.class})||m.getName().equals("setDefinition")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class});
        if(VectorPhysicalQuantity.class.isAssignableFrom(targetClass))return m.getName().equals("setVectorAndUnits")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{DoubleVector.class,Units.class})||m.getName().equals("setDefinition")&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class});
        if(m.getParameterCount()!=1)return false;Class<?> arg=m.getParameterTypes()[0];return supportedArgument(arg,false)&&!arg.equals(Class.class);
    }
    private static boolean supportedReadReturn(Class<?> c){return isValueType(c)||ClientServerObject.class.isAssignableFrom(c)&&safeObjectClass(c)||Collection.class.isAssignableFrom(c)||c.isArray()&&(isValueType(c.getComponentType())||ClientServerObject.class.isAssignableFrom(c.getComponentType()));}
    private static boolean isValueType(Class<?> c){return c.isPrimitive()&&c!=void.class||c==String.class||Number.class.isAssignableFrom(c)||c==Boolean.class||c==Character.class||c.isEnum()||c==Enum.class||c==DoubleVector.class||c.isArray()&&(c.getComponentType().isPrimitive()||c.getComponentType()==String.class)||ClientServerObject.class.isAssignableFrom(c)&&safeObjectClass(c);}
    private static boolean supportedArgument(Class<?> c,boolean read){return c.isPrimitive()&&c!=void.class&&c!=char.class||c==String.class||c==Boolean.class||Number.class.isAssignableFrom(c)||c.isEnum()||c==Enum.class||c==DoubleVector.class||c==double[].class||ClientServerObject.class.isAssignableFrom(c)&&safeObjectClass(c)||read&&c==Class.class;}
    private static boolean paired(ClientServerObject target,Method setter,Method getter){String property=setter.getName().substring(3);if(target instanceof ScalarPhysicalQuantity&&property.equals("ValueAndUnits"))return getter.getName().equals("getSIValue");if(target instanceof VectorPhysicalQuantity&&property.equals("VectorAndUnits"))return getter.getName().equals("getSIVector");if(target instanceof EnumeratedOption&&property.equals("Selected"))return setter.getParameterTypes()[0]==int.class?getter.getName().equals("getSelected"):getter.getName().equals("getSelectedElement");return getter.getName().equals("get"+property)||getter.getName().equals("is"+property);}

    private Object[] arguments(Method m,Object raw,ClientServerObject target,boolean read)throws Exception {
        List<?> values=raw==null?List.of():raw instanceof List?(List<?>)raw:null;require(values!=null&&values.size()==m.getParameterCount(),"arguments数量必须与精确签名一致");Object[] out=new Object[values.size()];for(int i=0;i<out.length;i++)out[i]=argument(m.getParameterTypes()[i],values.get(i),target,read);return out;
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private Object argument(Class<?> type,Object value,ClientServerObject target,boolean read)throws Exception {
        require(supportedArgument(type,read),"不开放该参数类型: "+type.getName());require(value!=null,"不接受隐式null参数");
        if(type==boolean.class||type==Boolean.class){require(value instanceof Boolean,"布尔参数必须为true/false");return value;}
        if(type==String.class){require(value instanceof String&&((String)value).length()<=4096&&!((String)value).contains("\u0000"),"字符串参数须不超过4096字符且不含NUL");return value;}
        if(type==Class.class){require(read&&value instanceof String&&((String)value).startsWith("star."),"has(Class)只接受当前安装的STAR对象类型");Class<?> cls=Class.forName((String)value,false,target.getClass().getClassLoader());require(ClientServerObject.class.isAssignableFrom(cls)&&safeObjectClass(cls)&&Modifier.isPublic(cls.getModifiers()),"该类型不能用于只读has查询");return cls;}
        if(type.isEnum()||type==Enum.class){Class enumType=type;if(type==Enum.class){require(target instanceof EnumeratedOption,"泛型枚举仅可依据当前EnumeratedOption实际元素类型解析");enumType=((EnumeratedOption<?>)target).getElementType();}require(value instanceof String,"枚举须为实际公开常量名称");return Enum.valueOf(enumType,(String)value);}
        if(type==DoubleVector.class||type==double[].class){require(value instanceof List&&!((List<?>)value).isEmpty()&&((List<?>)value).size()<=16,"向量须为1至16个有限数字");double[] arr=new double[((List<?>)value).size()];for(int i=0;i<arr.length;i++)arr[i]=finite(((List<?>)value).get(i));return type==DoubleVector.class?new DoubleVector(arr):arr;}
        if(ClientServerObject.class.isAssignableFrom(type)){require(value instanceof Map&&((Map<?,?>)value).size()==1&&((Map<?,?>)value).get("object_ref") instanceof String,"对象参数只能为{object_ref:当前SIM实时引用}");ClientServerObject object=fresh(resolve((String)((Map<?,?>)value).get("object_ref")));require(type.isInstance(object),"对象参数类型与方法签名不符");return object;}
        double number=finite(value);if(type==double.class||type==Double.class)return number;if(type==float.class||type==Float.class){require(Math.abs(number)<=Float.MAX_VALUE,"float溢出");return(float)number;}
        require(number==Math.rint(number),"整数参数不能使用小数");if(type==int.class||type==Integer.class){require(number>=Integer.MIN_VALUE&&number<=Integer.MAX_VALUE,"int溢出");return(int)number;}if(type==long.class||type==Long.class){require(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||Math.abs(number)<=9007199254740991d,"long浮点输入超过精确整数范围");require(number>=Long.MIN_VALUE&&number<Long.MAX_VALUE,"long溢出");return((Number)value).longValue();}if(type==short.class||type==Short.class){require(number>=Short.MIN_VALUE&&number<=Short.MAX_VALUE,"short溢出");return(short)number;}if(type==byte.class||type==Byte.class){require(number>=Byte.MIN_VALUE&&number<=Byte.MAX_VALUE,"byte溢出");return(byte)number;}throw new IOException("不支持的参数类型: "+type.getName());
    }
    private Object expected(ClientServerObject target,Method setter,Method getter,Object[] args)throws Exception {
        if(target instanceof ScalarPhysicalQuantity&&setter.getName().equals("setValueAndUnits")){Units u=(Units)args[1];Units current=((ScalarPhysicalQuantity)target).getUnits();require(current.compatible(u),"所选Units与该物理量维度不兼容");return ((ScalarPhysicalQuantity)target).getIsDeltaValue()?u.convertDeltaInput(((Number)args[0]).doubleValue()):u.convertInput(((Number)args[0]).doubleValue());}
        if(target instanceof VectorPhysicalQuantity&&setter.getName().equals("setVectorAndUnits")){Units u=(Units)args[1];Units current=((VectorPhysicalQuantity)target).getUnits();require(current.compatible(u),"所选Units与该物理向量维度不兼容");List<Double> out=new ArrayList<>();for(double x:((DoubleVector)args[0]).toDoubleArray())out.add(u.convertInput(x));return out;}
        if(target instanceof EnumeratedOption&&setter.getName().equals("setSelected")&&args[0] instanceof Number){int n=((Number)args[0]).intValue();boolean found=false;for(int available:((EnumeratedOption<?>)target).getOptions())found|=available==n;require(found,"枚举整数不属于当前实际选项");}
        return canonical(args[0]);
    }
    private Object canonicalExpected(Object value)throws Exception {if(value instanceof Map&&((Map<?,?>)value).get("object_ref") instanceof String)return canonical(fresh(resolve((String)((Map<?,?>)value).get("object_ref"))));return canonical(value);}
    private static Object canonical(Object value)throws Exception {
        if(value==null)return null;if(value instanceof Boolean||value instanceof String)return value;if(value instanceof Character)return String.valueOf(value);if(value instanceof Number){require(Double.isFinite(((Number)value).doubleValue()),"读回包含非有限数值");return value;}if(value instanceof Enum)return((Enum<?>)value).name();
        if(value instanceof DoubleVector){List<Double> out=new ArrayList<>();for(double x:((DoubleVector)value).toDoubleArray()){require(Double.isFinite(x),"向量读回包含非有限数值");out.add(x);}return out;}
        if(value instanceof ClientServerObject)return Map.of("object_id",((ClientServerObject)value).getObjectKeyAsString(),"class",value.getClass().getName());
        if(value instanceof Collection||value.getClass().isArray()){List<Object> out=new ArrayList<>();List<Object> list=elements(value);require(list.size()<=1000,"读回集合超过1000项");for(Object x:list)out.add(canonical(x));return out;}
        throw new IOException("该返回值不是可核验的简单属性: "+value.getClass().getName());
    }
    private static List<Object> elements(Object raw)throws IOException {List<Object> out=new ArrayList<>();if(raw instanceof Collection){require(((Collection<?>)raw).size()<=1000,"读取集合超过1000项，请缩小对象范围。");out.addAll((Collection<?>)raw);}else if(raw!=null&&raw.getClass().isArray()){require(Array.getLength(raw)<=1000,"读取数组超过1000项，请缩小对象范围。");for(int i=0;i<Array.getLength(raw);i++)out.add(Array.get(raw,i));}return out;}
    static boolean equal(Object a,Object b,double tolerance){if(a instanceof Number&&b instanceof Number){if(a instanceof Long&&b instanceof Long||a instanceof Integer&&b instanceof Integer)return a.equals(b);double x=((Number)a).doubleValue(),y=((Number)b).doubleValue();return Double.isFinite(x)&&Double.isFinite(y)&&Math.abs(x-y)<=tolerance+tolerance*Math.max(Math.abs(x),Math.abs(y));}if(a instanceof List&&b instanceof List){List<?>x=(List<?>)a,y=(List<?>)b;if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!equal(x.get(i),y.get(i),tolerance))return false;return true;}return Objects.equals(a,b);}
    private static Object invoke(Method method,Object target,Object[] args)throws Exception {try{return method.invoke(target,args);}catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception)throw(Exception)cause;if(cause instanceof Error)throw(Error)cause;throw e;}}
    private static String targetKey(Map<?,?> r,boolean required)throws Exception {String id=text(r.get("id")),target=text(r.get("target_id"));require(id.isEmpty()||target.isEmpty()||id.equals(target),"id与target_id冲突");String result=!target.isEmpty()?target:!id.isEmpty()?id:text(r.get("query"));if(required)require(!result.isBlank()&&!result.equals("*"),"API修改须指定真实目标");return result;}
    private static String targetName(ClientServerObject object){try{return object.getPresentationName();}catch(Exception ignored){return object.getClass().getSimpleName();}}
    private static String text(Object value){return value==null?"":String.valueOf(value).trim();}
    private static double finite(Object value)throws Exception {require(value instanceof Number&&Double.isFinite(((Number)value).doubleValue()),"数值必须为有限数字");return((Number)value).doubleValue();}
    private static int number(Object raw,int fallback){return raw instanceof Number?Math.max(0,Math.min(100000,((Number)raw).intValue())):fallback;}
    private static String simple(Object value){String s=String.valueOf(value);return s.length()>160?s.substring(0,157)+"...":s;}
    private static String message(Throwable error){while(error instanceof InvocationTargetException&&error.getCause()!=null)error=error.getCause();return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
    private static void checkThread()throws IOException {require(!Thread.currentThread().isInterrupted(),"操作已取消，未执行API。");CodexAssistant.checkConfigurationReadCancelled();require(!javax.swing.SwingUtilities.isEventDispatchThread(),"通用API读取/修改不能在Swing界面线程执行。");}
    private static void require(boolean condition,String message)throws IOException {if(!condition)throw new IOException(message);}
}
