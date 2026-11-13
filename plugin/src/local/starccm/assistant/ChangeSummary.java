package local.starccm.assistant;
import java.util.*;
/** Human-readable receipts are derived from verified writes, never from the model's completion claim. */
final class ChangeSummary {
 static String render(List<Map<String,Object>> journal,String note,String outcome){
  List<Map<String,Object>> saved=new ArrayList<>();for(Map<String,Object> r:journal)if("saved".equals(r.get("status")))saved.add(r);
  StringBuilder out=new StringBuilder(saved.isEmpty()?"failed".equals(outcome)?"本轮操作未完成，未新增已保存修改。":"本轮没有新增已保存修改。":"已保存 "+saved.size()+" 项修改"+("completed".equals(outcome)?"，处理完成。":"，任务尚未全部完成。"));
  int i=0;for(Map<String,Object> row:saved)out.append('\n').append(++i).append(". ").append(item(row));
  LinkedHashSet<String> notices=new LinkedHashSet<>();for(Map<String,Object> row:saved){Map<?,?> actual=map(row.get("actual"));for(Map<?,?> receipt:List.of(actual,map(actual.get("recommended_defaults"))))if(receipt.get("notices") instanceof List)for(Object n:(List<?>)receipt.get("notices"))notices.add(String.valueOf(n));}for(String n:notices)out.append("\n注意：").append(n);
  String issue=CodexAssistant.failureReason(note);
  if(issue==null&&(!"completed".equals(outcome)||saved.isEmpty())){
   issue=CodexAssistant.userFacingMessage(note==null?"":note.replace("本任务未执行任何写入。\n",""));
   issue=issue.replaceAll("(?i)[^。\\n]*(?:y\\s*\\+|y⁺)[^。\\n]*[。]?","").trim();
   if(issue.length()>400)issue=issue.substring(0,400)+"…（剩余说明见详情）";
  }
  if(issue!=null&&!issue.isBlank())out.append("\n").append(issue);
  if(saved.stream().anyMatch(row->Set.of("configure_prism_layers","configure_surface_prisms","limit_prisms_to_surfaces").contains(String.valueOf(row.get("kind")))))out.append("\n棱柱参数已设置；实际 y⁺ 需求解后验证。");
  return out.append("\n原始诊断、全部参数及恢复文件见“详情 / 任务报告”。").toString();
 }
 static String name(Map<String,Object> row){return String.valueOf(row.getOrDefault("target_name","当前仿真"));}
 static Map<?,?> map(Object o){return o instanceof Map?(Map<?,?>)o:Map.of();}
 static Object value(Map<?,?> m,String key,Object fallback){return m.containsKey(key)?m.get(key):fallback;}
 static String item(Map<String,Object> r){
  String kind=String.valueOf(r.get("kind")),target=name(r);Map<?,?> a=map(r.get("recommended")),actual=map(r.get("actual"));String n=String.valueOf(value(a,"name",target));
  switch(kind){
  case "create_aerodynamic_report":case "configure_aerodynamic_report":return aerodynamicSummary(a,actual);
  case "invoke_api":return "“"+String.valueOf(value(actual,"target_name",target))+"”的"+apiProperty(String.valueOf(value(actual,"property","扩展属性")))+"："+fmt(actual.get("before"))+" → "+fmt(actual.get("actual"))+(String.valueOf(value(actual,"unit","")).isBlank()?"":" "+actual.get("unit"))+"（已读回并保存）。";
  case "configure_coefficient_report":case "configure_plane_section":case "create_monitor_plot":case "configure_scalar_display":return commonSummary(r,a,actual);
  case "create_parts_from_cad":return "从3D-CAD创建关联CAD部件，已验证实体来源；创建/跳过详情见报告，源CAD保留。";
  case "configure_physics_profile":case "configure_external_physics":return "完整物理模型链已配置并读回："+(String.valueOf(actual.get("profile")).contains("sst")?"稳态可压缩理想气体、耦合流/能量、RANS-SST":"稳态可压缩理想气体、耦合流/能量、RANS-SA")+"，壁面处理“"+human(String.valueOf(actual.get("wall_treatment")))+"”；新增："+(actual.get("enabled_models_added") instanceof List?String.join("、",((List<?>)actual.get("enabled_models_added")).stream().map(x->human(String.valueOf(x))).toList()):"详见报告")+"。";
  case "configure_flow_conditions":return flowSummary(actual)+"。";
  case "prepare_external_aero":case "repair_external_aero":return externalAeroSummary(a,actual);
  case "create_block":return "创建外域“"+n+"”：角点 "+fmt(a.get("min_m"))+" → "+fmt(a.get("max_m"))+" m。";
  case "update_block":return "外域“"+target+"”尺寸：角点 "+fmt(a.get("min_m"))+" → "+fmt(a.get("max_m"))+" m。";
  case "import_cad":return "导入CAD文件（文件名及完整路径见详情）。";
  case "create_subtract":return "创建减运算“"+n+"”，输入："+selections(r)+"；"+booleanMode(actual)+"。";
  case "execute_boolean":return "更新减运算“"+target+"”的流体域输出。";
  case "create_regions":return "创建区域，输入："+selections(r)+"。";
  case "create_volume_mesh":return "创建网格操作“"+n+"”："+("trimmed".equals(a.get("mesh_type"))?"切割体":"多面体")+"，基准尺寸 "+fmt(a.get("base_size_m"))+" m，棱柱层"+(Boolean.TRUE.equals(a.get("prism"))?"启用":"关闭")+"。"+(actual.containsKey("recommended_defaults")?" 已按参考长度 "+fmt(a.get("reference_length_m"))+" m 补充远场目标 "+fmt(((Number)a.get("reference_length_m")).doubleValue())+" m、全局最小 "+fmt(((Number)a.get("reference_length_m")).doubleValue()/1000)+" m、最大体尺寸 "+fmt(2*((Number)a.get("reference_length_m")).doubleValue())+" m、表面增长1.15、曲率30点"+("polyhedral".equals(a.get("mesh_type"))?"、体增长1.10":"")+"。":"");
  case "execute_mesh":return "重新生成网格“"+target+"”"+(actual.get("cell_count") instanceof Number?"，"+actual.get("cell_count")+" 个单元":"")+meshNotice(actual)+"。";
  case "run_solver":return "求解“"+target+"”：迭代 "+fmt(actual.get("iteration_before"))+" → "+fmt(actual.get("iteration_after"))+"，请求 "+fmt(a.get("steps"))+" 步；不代表收敛。";
  case "delete_object":return "删除“"+String.valueOf(value(actual,"deleted_name",target))+"”。";
  case "rename_object":return "重命名：“"+target+"” → “"+n+"”。";
  case "split_surfaces":return "拆分“"+target+"”的指定表面，角度 "+fmt(a.get("angle_deg"))+"°，当前共 "+size(actual.get("surface_ids"))+" 个表面。";
  case "set_prism_mesher":return "网格“"+target+"”的棱柱层网格器："+on(a.get("enabled"))+"。";
  case "configure_mesh_defaults":return "网格“"+target+"”默认配置："+meshDefaults(a)+"。";
  case "configure_boolean":return "减运算“"+target+"”："+(Boolean.TRUE.equals(a.get("cad_boolean"))?"CAD布尔，弦差 "+fmt(a.get("chord_tolerance_m"))+" m、表面公差 "+fmt(a.get("surface_tolerance_m"))+" m、角度 "+fmt(a.get("angle_deg"))+"°":"离散布尔")+"；输出需更新并重新核对引用。";
  case "configure_prism_layers":return "网格“"+target+"”棱柱层："+prism(a)+"。";
  case "configure_surface_prisms":return "局部控制“"+target+"”棱柱层："+("custom".equals(a.get("mode"))?prism(a):"disable".equals(a.get("mode"))?"禁用":"继承默认设置")+"。";
  case "limit_prisms_to_surfaces":return "棱柱作用范围：仅 "+selections(r)+" 加层，其余当前输入面禁用。";
  case "create_mesh_control":return "创建"+("surface".equals(a.get("control_type"))?"表面":"volume".equals(a.get("control_type"))?"体":"部件")+"控制“"+n+"”，选择："+selections(r)+"。";
  case "configure_surface_size":return "表面控制“"+target+"”：目标尺寸 "+fmt(a.get("target_m"))+" m，最小尺寸 "+fmt(a.get("min_m"))+" m。";
  case "configure_volume_size":return "体控制“"+target+"”：单元尺寸 "+fmt(a.get("size_m"))+" m。";
  case "set_control_enabled":return "局部控制“"+target+"”："+on(a.get("enabled"))+"。";
  case "set_object_selection":case "set_part_selection":return "“"+target+"”的作用对象改为："+selections(r)+"。";
  case "create_boundary":return (Boolean.TRUE.equals(actual.get("reused_existing"))?"复用现有空边界“":"创建边界“")+n+"”："+type(a.get("boundary_type"))+"，表面："+selections(r)+(Boolean.TRUE.equals(actual.get("reused_existing"))?"；已有边界参数保留。":"。");
  case "set_boundary_type":return "边界“"+target+"”改为"+type(a.get("boundary_type"))+"。";
  case "create_physics_continuum":return "创建物理连续体“"+n+"”。";
  case "create_report":return "创建报告“"+n+"”（"+human(String.valueOf(a.get("report_type")))+"）。";
  case "create_report_monitor":return "为“"+target+"”创建监视器“"+n+"”。";
  case "set_report_field":Map<?,?> refs=map(r.get("reference_names"));return "报告“"+target+"”：场函数改为“"+value(refs,"field_id","已记录场函数")+"”"+(refs.containsKey("representation_id")?"，表示改为“"+refs.get("representation_id")+"”":"")+"。";
  case "create_field_function":return "创建"+("VECTOR".equals(a.get("field_type"))?"矢量":"标量")+"场函数“"+n+"”，定义和量纲已读回。";
  case "create_global_parameter":return "创建"+("vector".equals(a.get("parameter_type"))?"矢量":"标量")+"参数“"+n+"”，数值/表达式和量纲已读回。";
  case "create_plane_section":return "创建截面“"+n+"”：原点 "+fmt(a.get("origin_m"))+" m，法向 "+fmt(a.get("normal"))+"。";
  case "create_scene":return "创建“"+n+"”"+("mesh".equals(a.get("scene_type"))?"网格":"scalar".equals(a.get("scene_type"))?"标量":"矢量")+"场景。";
  case "create_sum_report":return "创建求和报告“"+n+"”，引用报告："+selections(r)+"。";
  case "field":return path(String.valueOf(r.get("parameter")))+"：已更新表达式，定义见详情。";
  default:
   return path(String.valueOf(r.get("parameter")))+"："+choice(r,r.get("before"))+" → "+choice(r,r.get("actual"))+(String.valueOf(r.getOrDefault("unit","")).isBlank()?"":" "+r.get("unit"))+"。";
  }
 }
 static String aerodynamicSummary(Map<?,?> requested,Map<?,?> receipt){
  Map<?,?> actual=map(receipt.get("report"));if(actual.isEmpty())actual=map(receipt.get("actual"));
  boolean create="create_aerodynamic_report".equals(requested.get("type"));List<String> items=new ArrayList<>();
  String kind=String.valueOf(value(actual,"report_kind",requested.get("report_kind")));
  String label=Map.of("force","力","moment","力矩","force_coefficient","力系数","moment_coefficient","力矩系数").getOrDefault(kind,"气动力");
  for(String[] f:new String[][]{{"direction","方向",""},{"origin_m","原点"," m"},{"reference_density_kg_m3","参考密度"," kg/m³"},{"reference_velocity_m_s","参考速度"," m/s"},{"reference_area_m2","参考面积"," m²"},{"reference_length_m","参考长度"," m"},{"reference_pressure_pa","参考压力"," Pa"}})
   if(requested.containsKey(f[0]))items.add(f[1]+"="+brief(actual.get(f[0]))+f[2]);
  if(requested.containsKey("selection_ids"))items.add("选面="+String.join("、",list(actual.get("selection_names")).stream().map(String::valueOf).toList()));
  if(create||requested.containsKey("coordinate_system_id"))items.add("坐标系="+value(actual,"coordinate_system","已读回"));
  if(create||requested.containsKey("representation_id"))items.add("表示="+value(actual,"representation","已读回"));
  if(create||requested.containsKey("force_component"))items.add("积分分量="+Map.of("PRESSURE","压力","SHEAR","剪切","PRESSURE_AND_SHEAR","压力+剪切").getOrDefault(String.valueOf(actual.get("force_component")),"已读回"));
  if(create||requested.containsKey("use_idealization"))items.add("理想化映射="+on(actual.get("use_idealization")));
  if(receipt.containsKey("monitor"))items.add("监视器="+map(receipt.get("monitor")).get("name"));
  if(receipt.containsKey("plot"))items.add("绘图="+map(receipt.get("plot")).get("name"));
  return (create?"创建":"修改")+label+"报告“"+value(actual,"name",requested.get("name"))+"”："+String.join("；",items)+"（已读回并保存；未计算新的气动力数值）。";
 }
 static String apiProperty(String property){return Map.ofEntries(Map.entry("Value","数值"),Map.entry("SIValue","SI数值"),Map.entry("SIVector","SI向量"),Map.entry("Definition","表达式"),Map.entry("Selected","选项"),Map.entry("MaximumNumberSteps","最大步数"),Map.entry("DisplayMeshBoolean","网格线显示")).getOrDefault(property,property.replaceAll("([a-z])([A-Z])","$1 $2"));}
 static String commonSummary(Map<String,Object> row,Map<?,?> requested,Map<?,?> receipt){
  Map<?,?> actual=map(receipt.get("actual"));List<String> items=new ArrayList<>();
  String[][] fields={{"reference_density_kg_m3","参考密度"," kg/m³"},{"reference_velocity_m_s","参考速度"," m/s"},{"reference_area_m2","参考面积"," m²"},{"reference_length_m","参考长度"," m"},{"direction","方向","（单位向量）"},{"origin_m","原点"," m"},{"normal","法向","（单位向量）"},{"range_si","色标范围","（SI）"}};
  for(String[] f:fields)if(requested.containsKey(f[0]))items.add(f[1]+"="+fmt(value(actual,f[0],requested.get(f[0])))+f[2]);
  Map<?,?> refs=map(row.get("reference_names"));for(String[] f:new String[][]{{"coordinate_system_id","坐标系"},{"field_id","场函数"},{"representation_id","表示"}})if(requested.containsKey(f[0]))items.add(f[1]+"="+value(refs,f[0],"已指定并读回"));
  if(requested.containsKey("selection_ids"))items.add("作用对象="+selections(row));
  if(requested.containsKey("monitor_ids"))items.add("监视器="+selections(row));
  if(requested.containsKey("automatic_range"))items.add("自动色标="+on(requested.get("automatic_range")));
  if(requested.containsKey("show_mesh"))items.add("网格线="+on(requested.get("show_mesh")));
  String target=String.valueOf(value(requested,"name",name(row)));
  return "“"+target+"”："+String.join("；",items)+"（已读回并保存）。";
 }
 /** Compress the known recipe, not the journal. Independent/unknown writes stay individually visible. */
 static String externalAeroSummary(Map<?,?> action,Map<?,?> receipt){
  List<String> lines=new ArrayList<>();Set<String> covered=new HashSet<>();
  Map<?,?> cad=step(receipt,"cad_boolean_settings"),boundaries=step(receipt,"geometry_boundaries"),physics=step(receipt,"physics_profile"),flow=step(receipt,"flow_conditions"),defaults=map(map(step(receipt,"mesh_defaults").get("actual")).get("mesh_defaults"));
  Object length=receipt.get("reference_length_m"),scale=receipt.get("domain_scale_m");
  if(length instanceof Number||scale instanceof Number||!map(receipt.get("domain_bounds_m")).isEmpty()){
   List<String> domain=new ArrayList<>();if(length instanceof Number)domain.add("翼面参考L="+brief(length)+" m"+(list(receipt.get("assumptions")).stream().anyMatch(x->String.valueOf(x).startsWith("参考长度来自明确根部平面"))?"（根部弦向投影）":""));
   if(scale instanceof Number)domain.add("远场尺度D="+brief(scale)+" m（物长/全翼展较大值）");
   if(action.containsKey("domain_min_m")||action.containsKey("domain_max_m"))domain.add("采用明确域范围");
   else if(scale instanceof Number&&list(receipt.get("assumptions")).stream().anyMatch(x->String.valueOf(x).contains("净距10D")))domain.add("自由方向净距10D≈"+brief(((Number)scale).doubleValue()*10)+" m");
   if(action.containsKey("symmetry_axis")&&action.containsKey("symmetry_plane_m"))domain.add("对称面"+action.get("symmetry_axis")+"="+brief(action.get("symmetry_plane_m"))+" m");
   lines.add("外域："+String.join("；",domain)+"。");covered.add("domain");
  }
  if(!cad.isEmpty()){Map<?,?> settings=map(cad.get("actual"));if(settings.isEmpty())settings=cad;lines.add("几何："+(Boolean.TRUE.equals(settings.get("cad_boolean"))?"CAD布尔及绝对离散精度已设置":"离散布尔，共面接触质量须核验")+"；流体域/区域已更新。");covered.add("cad");}
  if(!boundaries.isEmpty()){
   LinkedHashMap<String,Integer> counts=new LinkedHashMap<>();for(Map.Entry<?,?> b:boundaries.entrySet())counts.merge(type(b.getKey()),size(map(b.getValue()).get("surfaces")),Integer::sum);
   List<String> labels=new ArrayList<>();counts.forEach((label,count)->labels.add(label+count+"面"));lines.add("边界："+String.join("、",labels)+"（按实际选面计数）。");covered.add("boundary");
  }
  if(!physics.isEmpty()){
   String profile=String.valueOf(physics.get("profile")),wall=human(String.valueOf(value(physics,"wall_treatment","壁面处理见详情")));
   lines.add("物理：区域已绑定连续体；三维稳态可压缩理想气体、耦合流/能量、"+(profile.contains("sst")?"RANS-SST":"RANS-SA")+"，"+wall+"；模型链"+(Boolean.TRUE.equals(physics.get("model_chain_complete"))?"完整并已读回":"状态见详情")+"。");covered.add("physics");
  }
  if(!flow.isEmpty()){
   Map<?,?> si=map(flow.get("requested_SI")),actual=map(flow.get("actual")),initial=map(actual.get("initial"));List<String> fields=new ArrayList<>();
   add(fields,"马赫数=",si,"mach","");add(fields,"静温=",si,"static_temperature_K"," K");add(fields,"绝对静压=",si,"absolute_static_pressure_Pa"," Pa");add(fields,"流向=",si,"flow_direction","");add(fields,"速度=",si,"speed_m_s"," m/s");
   if(!si.containsKey("flow_direction"))add(fields,"速度矢量=",si,"velocity_m_s"," m/s");
   add(fields,"参考压力=",actual,"reference_pressure_Pa"," Pa");add(fields,"表压=",flow,"gauge_pressure_Pa"," Pa");add(fields,"湍黏比=",initial,"turbulent_viscosity_ratio","");add(fields,"湍流强度=",initial,"turbulence_intensity","");
   lines.add("工况："+String.join("，",fields)+"；材料、初始值及开放边界逐项读回。");covered.add("flow");
  }
  // These two native change rows contain verified local-control values absent from steps.
  for(Object raw:list(receipt.get("changes"))){String change=String.valueOf(raw);
   if(change.startsWith("网格Base=")){
    String text=briefNumbers(change).replace("网格Base=","基准=").replace("；仅翼面目标=","；翼面目标=").replace("、最小=","/最小=");
    String growth=defaults.containsKey("volume_growth")?"、体增长="+brief(defaults.get("volume_growth")):"";
    String mesher="repair".equals(value(receipt,"mode",action.get("mode")))?"复用现有网格器":"trimmed".equals(action.get("mesh_type"))?"切割体":"polyhedral".equals(action.get("mesh_type"))||!action.containsKey("mesh_type")?"多面体":"网格器见详情";
    lines.add("网格："+mesher+"；"+text.replace("；翼面",growth+"；翼面"));covered.add("mesh");
   }else if(change.startsWith("仅真实机翼面添加棱柱：")){
    lines.add("棱柱："+briefNumbers(change).replace("仅真实机翼面添加棱柱：","仅翼面")+(action.containsKey("target_yplus")?"目标y⁺="+brief(action.get("target_yplus"))+"（初估）。":""));covered.add("prism");
   }
  }
  // No fixed-length truncation: e.g. every separately disabled old control is retained.
  for(Object raw:list(receipt.get("changes"))){String change=String.valueOf(raw);String group=change.startsWith("外域角点：")?"domain":change.startsWith("CAD布尔及")?"cad":change.startsWith("边界“")?"boundary":change.startsWith("区域已绑定连续体")?"physics":change.startsWith("来流/材料/参考压力/")?"flow":change.startsWith("网格Base=")?"mesh":change.startsWith("仅真实机翼面添加棱柱：")?"prism":change.startsWith("已在全部几何/边界/物理/数值设置后执行一次网格")?"execution":"";
   if(!covered.contains(group)&&!group.equals("execution"))lines.add(CodexAssistant.userFacingMessage(change));
  }
  LinkedHashSet<String> notices=new LinkedHashSet<>();for(Object raw:list(receipt.get("steps")))for(Object notice:list(map(map(raw).get("actual")).get("notices")))notices.add(CodexAssistant.userFacingMessage(String.valueOf(notice)));for(String notice:notices)lines.add("设置提示："+notice);
  lines.add(externalExecution(receipt));
  return "完整外流场配置已保存：\n    · "+String.join("\n    · ",lines);
 }
 static List<?> list(Object value){return value instanceof List?(List<?>)value:List.of();}
 static Map<?,?> step(Map<?,?> receipt,String name){for(Object raw:list(receipt.get("steps"))){Map<?,?> s=map(raw);if(name.equals(s.get("step")))return map(s.get("actual"));}return Map.of();}
 static void add(List<String> fields,String label,Map<?,?> values,String key,String unit){if(values.containsKey(key))fields.add(label+brief(values.get(key))+unit);}
 static String brief(Object value){
  if(value instanceof Number){double d=((Number)value).doubleValue();return Double.isFinite(d)?java.math.BigDecimal.valueOf(d).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString():"无有效读数";}
  if(value instanceof List)return "("+String.join(", ",((List<?>)value).stream().map(ChangeSummary::brief).toList())+")";
  return fmt(value);
 }
 static String briefNumbers(String text){
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?<![\\p{L}_])[-+]?(?:\\d+\\.\\d*|\\d+)(?:[eE][-+]?\\d+)?").matcher(text);StringBuffer out=new StringBuffer();
  while(m.find()){String number=m.group();try{number=brief(Double.valueOf(number));}catch(NumberFormatException ignored){}m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(number));}m.appendTail(out);return out.toString();
 }
 static String externalExecution(Map<?,?> receipt){
  if(Boolean.FALSE.equals(receipt.get("meshing_executed")))return "执行：本次未执行网格，当前配置的网格质量尚未验证；未运行CFD，实际y⁺需求解后验证。";
  if(!Boolean.TRUE.equals(receipt.get("meshing_executed")))return "执行：网格执行状态见详情，网格质量未确认；未运行CFD，实际y⁺需求解后验证。";
  Map<?,?> q=map(receipt.get("quality_snapshot"));String cells=q.get("cell_count") instanceof Number?"（"+q.get("cell_count")+"单元）":"";
  if("unavailable".equals(q.get("status"))||!list(q.get("read_errors")).isEmpty())return "执行：已执行一次网格"+cells+(!list(q.get("mesh_geometry_issues")).isEmpty()?meshNotice(receipt):"")+"；网格质量读回不完整（见详情）；未运行CFD，实际y⁺需求解后验证。";
  return "执行：已执行一次网格"+cells+meshNotice(receipt)+"；未运行CFD，实际y⁺需求解后验证。";
 }
 static String flowSummary(Map<?,?> receipt){Map<?,?> values=map(receipt.get("requested_SI")),actual=map(receipt.get("actual")),initial=map(actual.get("initial"));List<String> fields=new ArrayList<>();Map<String,String> labels=Map.of("mach","马赫数","static_temperature_K","静温/K","absolute_static_pressure_Pa","绝对静压/Pa","velocity_m_s","初始速度/(m/s)");for(String key:List.of("mach","static_temperature_K","absolute_static_pressure_Pa","velocity_m_s"))if(values.containsKey(key))fields.add(labels.get(key)+"="+fmt(values.get(key)));if(actual.containsKey("reference_pressure_Pa"))fields.add("参考压力/Pa="+fmt(actual.get("reference_pressure_Pa")));if(initial.containsKey("turbulent_viscosity_ratio"))fields.add("湍黏比="+fmt(initial.get("turbulent_viscosity_ratio")));if(initial.containsKey("turbulence_intensity"))fields.add("湍流强度="+fmt(initial.get("turbulence_intensity")));if(receipt.containsKey("gauge_pressure_Pa"))fields.add("表压="+fmt(receipt.get("gauge_pressure_Pa"))+" Pa");return "初始值与实际开放边界已配置并按SI读回："+String.join("、",fields)+"；黏度及各边界逐项值见详情";}
 static String nativeFlowDetails(Map<?,?> receipt){if(receipt.get("steps") instanceof List)for(Object raw:(List<?>)receipt.get("steps")){Map<?,?> step=map(raw);if("flow_conditions".equals(step.get("step")))return "\n    "+flowSummary(map(step.get("actual")));}return "";}
 static String selections(Map<String,Object> row){Object v=row.get("selection_names");return v instanceof List&&!((List<?>)v).isEmpty()?String.join("、",((List<?>)v).stream().map(String::valueOf).toList()):"已记录的选择对象（见详情）";}
 static int size(Object o){return o instanceof Collection?((Collection<?>)o).size():0;}
 static String booleanMode(Map<?,?> receipt){Map<?,?> actual=map(receipt.get("actual"));if(!actual.containsKey("cad_boolean"))actual=receipt;if(!Boolean.TRUE.equals(actual.get("cad_boolean")))return "采用离散布尔，共面接触质量须核验";Map<?,?> t=map(actual.get("cad_tessellation"));return "CAD布尔，弦差 "+fmt(t.get("chord_tolerance_m"))+" m、表面公差 "+fmt(t.get("surface_tolerance_m"))+" m、离散角度 "+fmt(t.get("curve_angle_deg"))+"°";}
 static String meshNotice(Map<?,?> receipt){Map<?,?> q=map(receipt.get("quality_snapshot"));if(q.isEmpty())return Boolean.FALSE.equals(receipt.get("meshing_executed"))?"；本次未执行网格，尚未验证当前配置的网格结果":"；本次未取得网格质量读数，不能确认网格合格";Object issues=q.get("mesh_geometry_issues"),warnings=q.get("warnings");if(issues instanceof List&&!((List<?>)issues).isEmpty())return "；网格/几何检查发现问题："+CodexAssistant.userFacingMessage(String.valueOf(((List<?>)issues).get(0)))+"（见详情）";if(warnings instanceof List&&!((List<?>)warnings).isEmpty())return "；网格检查有 "+((List<?>)warnings).size()+" 项关注（见详情）";return "；已读回基本网格指标，工程精度和y⁺仍需验证";}
 static String shortNum(double v){return String.format(Locale.ROOT,"%.6g",v);}
 static String meshDefaults(Map<?,?> a){Map<String,String> labels=Map.of("farfield_target_m","远场表面目标","global_min_m","全局最小表面","maximum_cell_m","最大体尺寸","surface_growth","表面增长率","volume_growth","体增长率","curvature_points","曲率每圆点数");List<String> values=new ArrayList<>();for(String key:List.of("farfield_target_m","global_min_m","maximum_cell_m","surface_growth","volume_growth","curvature_points"))if(a.containsKey(key))values.add(labels.get(key)+" "+fmt(a.get(key))+(key.endsWith("_m")?" m":""));return String.join("、",values);}
 static String prism(Map<?,?> a){String result=fmt(a.get("layers"))+" 层，首层全厚 "+fmt(a.get("first_layer_m"))+" m，增长率 "+fmt(a.get("growth_ratio"));try{double n=((Number)a.get("layers")).doubleValue(),h=((Number)a.get("first_layer_m")).doubleValue(),r=((Number)a.get("growth_ratio")).doubleValue();double total=r==1?h*n:h*Math.expm1(n*Math.log(r))/(r-1),last=h*Math.pow(r,n-1);result+="，总厚 "+shortNum(total)+" m、末层厚 "+shortNum(last)+" m";}catch(Exception ignored){}return result;}
 static String type(Object v){return Map.of("wall","壁面","symmetry","对称面","velocity_inlet","速度入口","pressure_outlet","压力出口","freestream","自由流").getOrDefault(String.valueOf(v),"边界类型（见详情）");}
 static String on(Object v){return Boolean.TRUE.equals(v)?"启用":"关闭";}
 static String fmt(Object v){if(v==null)return "未记录";if(v instanceof Boolean)return on(v);if(v instanceof Number){double d=((Number)v).doubleValue();return Double.isFinite(d)?java.math.BigDecimal.valueOf(d).stripTrailingZeros().toString():"无有效读数";}if(v instanceof List)return "("+String.join(", ",((List<?>)v).stream().map(ChangeSummary::fmt).toList())+")";return human(String.valueOf(v));}
 static String choice(Map<String,Object> row,Object v){Map<?,?> choices=map(row.get("choices"));return fmt(value(choices,String.valueOf(v),v));}
 static String path(String p){String[] parts=p.split(" / ");List<String> out=new ArrayList<>();for(String x:parts)if(!Set.of("Models","Default Controls","Physics Values","Physics Conditions","Constant","Value","Meshers").contains(x))out.add(human(x));return String.join(" › ",out);}
 static String human(String v){
  Map<String,String> words=Map.ofEntries(Map.entry("Reference Pressure","参考压力"),Map.entry("Reference Values","参考值"),Map.entry("Initial Conditions","初始值"),Map.entry("Velocity","速度"),Map.entry("Velocity Magnitude","速度大小"),Map.entry("Static Temperature","静温"),Map.entry("Pressure","表压"),Map.entry("Constant","常数"),Map.entry("Field Function","场函数"),Map.entry("Composite","分量组合"),Map.entry("Base Size","基准尺寸"),Map.entry("Target Surface Size","目标表面尺寸"),Map.entry("Minimum Surface Size","最小表面尺寸"),Map.entry("Flow Direction Specification","流向指定方式"),Map.entry("FlowDirectionOption.COMPONENTS","矢量分量"),Map.entry("FlowDirectionOption.BOUNDARY_NORMAL","边界法向"),Map.entry("FlowDirectionOption.ANGLES","角度"),Map.entry("InletVelocityOption.COMPONENTS","速度分量"),Map.entry("InletVelocityOption.MAGNITUDE_DIRECTION","速度大小与方向"));
  if(words.containsKey(v))return words.get(v);
  if(v.startsWith("star.")){String name=v.substring(v.lastIndexOf('.')+1);return Map.ofEntries(Map.entry("SteadyModel","稳态"),Map.entry("TurbulentModel","湍流"),Map.entry("SaTurbModel","SA湍流方程"),Map.entry("SpalartAllmarasTurbulence","Spalart–Allmaras模型"),Map.entry("SaLowYplusWallTreatment","SA低y⁺壁面处理"),Map.entry("SaAllYplusWallTreatment","SA全y⁺壁面处理"),Map.entry("KwAllYplusWallTreatment","SST全y⁺壁面处理"),Map.entry("SstKwTurbModel","SST湍流方程"),Map.entry("RansTurbulenceModel","RANS模型"),Map.entry("SingleComponentGasModel","单组分气体"),Map.entry("IdealGasModel","理想气体"),Map.entry("ThreeDimensionalModel","三维"),Map.entry("CoupledFlowModel","耦合流"),Map.entry("CoupledEnergyModel","耦合能量"),Map.entry("AreaAverageReport","面积平均"),Map.entry("ForceCoefficientReport","力系数"),Map.entry("PressureBoundary","压力出口"),Map.entry("InletBoundary","速度入口"),Map.entry("SymmetryBoundary","对称面"),Map.entry("WallBoundary","壁面")).getOrDefault(name,name.replaceAll("(Model|Report)$","").replaceAll("([a-z])([A-Z])","$1 $2"));}
  if(v.matches("[A-Za-z]+Option\\.[A-Z_]+"))return v.substring(v.indexOf('.')+1).toLowerCase(Locale.ROOT).replace('_',' ');
  return v;
 }
}
