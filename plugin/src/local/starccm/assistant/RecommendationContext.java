package local.starccm.assistant;
import java.util.*;import java.util.regex.*;import java.io.*;import star.common.*;import star.meshing.*;
/** Read-only dimensional suggestions; explicit values and current verified choices stay in the planner's control. */
final class RecommendationContext {
 static Double number(Map<?,?> map,String key)throws IOException {if(!map.containsKey(key))return null;Object v=map.get(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IOException(key+"必须是有限数值");return ((Number)v).doubleValue();}
 static Double match(String text,String regex,double factor){Matcher m=Pattern.compile(regex,Pattern.CASE_INSENSITIVE).matcher(text);Double out=null;while(m.find())out=Double.parseDouble(m.group(1))*factor;return out;}
 static final String NUM="(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
 static Double reference(String goal){Matcher m=Pattern.compile("(?:参考长度|参考弦长|翼根弦长|reference_length_m)\\s*(?:为|是|=|:|：)?\\s*("+NUM+")\\s*(mm|cm|m|毫米|米)?",Pattern.CASE_INSENSITIVE).matcher(goal);Double out=null;while(m.find()){String u=m.group(2)==null?"m":m.group(2).toLowerCase(Locale.ROOT);out=Double.parseDouble(m.group(1))*(u.equals("mm")||u.equals("毫米")?.001:u.equals("cm")?.01:1);}return out;}
 static Double mach(String goal){Matcher m=Pattern.compile("(?<![.0-9])("+NUM+")\\s*(?:马赫|mach|Ma\\b)|(?:mach|Ma\\b|马赫数)\\s*(?:为|是|=|:|：)?\\s*("+NUM+")",Pattern.CASE_INSENSITIVE).matcher(goal);Double out=null;while(m.find())out=Double.parseDouble(m.group(1)!=null?m.group(1):m.group(2));return out;}
 static Double altitude(String goal){Matcher m=Pattern.compile("(?:高度|海拔|altitude)\\s*(?:为|是|=|:|：)?\\s*("+NUM+")\\s*(km|千米|m\\b|米)",Pattern.CASE_INSENSITIVE).matcher(goal);Double out=null;while(m.find())out=Double.parseDouble(m.group(1))*(m.group(2).equalsIgnoreCase("km")||m.group(2).equals("千米")?1000:1);return out;}
 static final String YPLUS_MARKER="(?<![a-z0-9_])(?:y\\s*(?:\\+|⁺)|y[ _-]*plus\\b)";
 static final String YPLUS_CONNECTOR="(?:\\s*(?:目标值?|取值|值|target\\b|value\\b|is\\b|of\\b|about\\b|around\\b|approx(?:imately)?\\.?|约为|大约|约|为|是|取|设置为|设为|要求|保持|=|:|：|≈|~))*\\s*";
 static Double yplus(String goal){return match(goal,YPLUS_MARKER+YPLUS_CONNECTOR+"("+NUM+")",1);}
 /** Wall-unit y+ is not the +Y coordinate axis. Mask quantity notation before
  * searching direction tokens, while preserving genuine 'flow y+' and '+y'. */
 static String withoutWallYplus(String goal){
  String text=goal.toLowerCase(Locale.ROOT);Matcher marker=Pattern.compile(YPLUS_MARKER).matcher(text);StringBuffer out=new StringBuffer();
  while(marker.find()){
   String after=text.substring(marker.end(),Math.min(text.length(),marker.end()+80)).stripLeading();String before=text.substring(Math.max(0,marker.start()-45),marker.start());
   boolean quantity=after.matches("(?s)^(?:[=：:≈~≤≥<>0-9.]|目标|取值|值|target\\b|value\\b|is\\b|of\\b|about\\b|around\\b|approx|约|大约|为|是|取|设置为|设为|要求|保持|范围|range\\b|小于|大于|不超过|至少).*" )
    ||before.matches("(?s).*(?:目标值?|壁面|近壁|target|desired|near[ -]?wall|wall[ -]?unit)\\s*$");
   marker.appendReplacement(out,quantity?"wall_yplus_quantity ":Matcher.quoteReplacement(marker.group()));
  }marker.appendTail(out);return out.toString();
 }
 static String flowAxis(String goal){
  String t=withoutWallYplus(goal);
  String direction="([+-])\\s*([xyz])(?![a-z0-9_])|([xyz])\\s*([+-])(?!\\s*(?:[=:.0-9：]|为|是))|([xyz])\\s*(正|负)(?:方向|向)?";
  Matcher m=Pattern.compile(direction).matcher(t);String out="";
  while(m.find()){if(m.group(1)!=null)out=m.group(1)+m.group(2);else if(m.group(3)!=null)out=m.group(4)+m.group(3);else out=(m.group(6).equals("正")?"+":"-")+m.group(5);}return out;
 }
 static String symmetryAxis(String goal){String t=goal.toLowerCase(Locale.ROOT);Matcher m=Pattern.compile("(?:对称面|对称平面|symmetry)\\s*(?:在|是|为|位于|=|:|：)?\\s*([xyz])\\s*=|xoz|xz平面|xoy|xy平面|yoz|yz平面").matcher(t);String out="";while(m.find())out=m.group(1)!=null?m.group(1):Set.of("xoz","xz平面").contains(m.group())?"y":Set.of("xoy","xy平面").contains(m.group())?"z":"x";return out;}
 static double symmetryPlane(String goal,String axis){Matcher m=Pattern.compile("(?<![a-z0-9_])"+axis+"\\s*=\\s*([+-]?"+NUM+")\\s*(mm|cm|m|毫米|米)?",Pattern.CASE_INSENSITIVE).matcher(goal);double out=0;while(m.find()){String unit=m.group(2)==null?"m":m.group(2).toLowerCase(Locale.ROOT);out=Double.parseDouble(m.group(1))*(unit.equals("mm")||unit.equals("毫米")?.001:unit.equals("cm")?.01:1);}return out;}
 static Map<String,Object> existingFlowContext(Simulation s){
  if(s.getRegionManager().getRegions().size()!=1)return Map.of();Region region=s.getRegionManager().getRegions().iterator().next();
  if(region.getPhysicsContinuum()==null)return Map.of();Map<String,Object> actual=FlowConditions.describe(region);List<?> chosen=null;String source="";
  if(actual.get("boundaries") instanceof List)for(Object raw:(List<?>)actual.get("boundaries"))if(raw instanceof Map){Map<?,?> entry=(Map<?,?>)raw;Boundary boundary=null;for(Boundary b:region.getBoundaryManager().getBoundaries())if(b.getObjectKeyAsString().equals(entry.get("id")))boundary=b;if(boundary==null||boundary.getPartSurfaceGroup().getObjects().isEmpty())continue;Object direction=entry.get("flow_direction");if(!(direction instanceof List))direction=entry.get("velocity_m_s");if(direction instanceof List&&((List<?>)direction).size()==3){if(chosen!=null&&!chosen.equals(direction))return Map.of();chosen=(List<?>)direction;source="current nonempty open-boundary direction";}}
  if(chosen==null&&actual.get("initial") instanceof Map){Object v=((Map<?,?>)actual.get("initial")).get("velocity_m_s");if(v instanceof List&&((List<?>)v).size()==3){chosen=(List<?>)v;source="current initial velocity direction (not a measured or converged flow)";}}
  if(chosen==null)return Map.of();double[] v=new double[3];double norm=0;for(int i=0;i<3;i++){if(!(chosen.get(i) instanceof Number))return Map.of();v[i]=((Number)chosen.get(i)).doubleValue();if(!Double.isFinite(v[i]))return Map.of();norm+=v[i]*v[i];}if(norm<=0)return Map.of();norm=Math.sqrt(norm);int dominant=0;for(int i=0;i<3;i++){v[i]/=norm;if(Math.abs(v[i])>Math.abs(v[dominant]))dominant=i;}return Map.of("flow_axis",(v[dominant]>=0?"+":"-")+"xyz".substring(dominant,dominant+1),"flow_direction",List.of(v[0],v[1],v[2]),"source",source);
 }
 static Map<String,Object> inspect(Simulation s,Map<?,?> call,String goal)throws Exception {
  String query=String.valueOf(call.containsKey("query")?call.get("query"):"*");LinkedHashSet<GeometryPart> candidates=new LinkedHashSet<>();
  if(s.has(MeshOperationManager.class)!=null)for(MeshOperation op:s.get(MeshOperationManager.class).getObjects())if(op instanceof SubtractPartsOperation)for(GeometryObject g:op.getInputGeometryObjects().getObjects())if(g instanceof GeometryPart&&!g.equals(((SubtractPartsOperation)op).getTargetPart()))candidates.add((GeometryPart)g);
  if(candidates.isEmpty())for(GeometryPart p:s.get(SimulationPartManager.class).getParts())if(!(p instanceof SimpleBlockPart)&&!(p instanceof MeshOperationPart))candidates.add(p);
  if(!query.equals("*"))candidates.removeIf(p->!p.getPresentationName().equalsIgnoreCase(query)&&!p.getObjectKeyAsString().equals(query));
  Double length=number(call,"reference_length_m");if(length==null)length=reference(goal);String source=length==null?"":"explicit_reference_length_m";Map<String,Object> measurement=new LinkedHashMap<>();GeometryPart body=candidates.size()==1?candidates.iterator().next():null;String flow=flowAxis(goal),sym=symmetryAxis(goal);
  boolean existingSymmetry=false;int nonemptySymmetries=0;for(Region r:s.getRegionManager().getRegions())for(Boundary b:r.getBoundaryManager().getBoundaries())if(b.getBoundaryType() instanceof SymmetryBoundary&&!b.getPartSurfaceGroup().getObjects().isEmpty())nonemptySymmetries++;existingSymmetry=s.getRegionManager().getRegions().size()==1&&nonemptySymmetries==1;
  if(flow.isEmpty()){Map<String,Object> existing=existingFlowContext(s);if(!existing.isEmpty()){flow=String.valueOf(existing.get("flow_axis"));measurement.put("existing_flow_context",existing);}}
  if(body instanceof MeshPart&&(flow.isEmpty()||sym.isEmpty())&&(goal.matches("(?is).*(半模|half[ -]?model).*" )||existingSymmetry)){Map<String,Object> inferred=GeometryBounds.inferHalfModel((MeshPart)body);measurement.put("half_model_inference",inferred);if("inferred_from_unique_measured_cap".equals(inferred.get("status"))){if(flow.isEmpty())flow=String.valueOf(inferred.get("flow_axis"));if(sym.isEmpty())sym=String.valueOf(inferred.get("symmetry_axis"));}}
  if(body instanceof MeshPart){measurement.put("body_id",body.getObjectKeyAsString());measurement.put("body",body.getPresentationName());
   Map<String,Object> bounds=GeometryBounds.measure((MeshPart)body);measurement.put("bounds",bounds);
   if(length==null&&!flow.isEmpty()&&!sym.isEmpty()&&!flow.substring(1).equals(sym))try{Map<String,Object> sec=GeometryBounds.measureSection((MeshPart)body,sym,symmetryPlane(goal,sym),flow.substring(1));length=((Number)sec.get("projected_extent_m")).doubleValue();measurement.put("reference_section",sec);source="measured_projected_section_at_confirmed_symmetry_plane; approximate, not mean aerodynamic chord";}catch(Exception e){measurement.put("section_read_error",e.toString());}
   if(length==null&&!flow.isEmpty()){length=((Number)((List<?>)bounds.get("span_m")).get(GeometryBounds.axis(flow.substring(1)))).doubleValue();source="body_bbox_flow_projection_proxy; not a chord, span, or mean aerodynamic chord; disclose before use";}
  }
  if(length==null)return new LinkedHashMap<>(Map.of("status","reference_length_required","candidate_bodies",candidates.stream().map(GeometryPart::getPresentationName).toList(),"missing","需选择准确物体并测量/指定参考长度；多个物体时不自动猜测","measurements",measurement));
  Map<String,Object> out=EngineeringDefaults.recommend(length,call.containsKey("mach")?number(call,"mach"):mach(goal),call.containsKey("altitude_m")?number(call,"altitude_m"):altitude(goal),number(call,"speed_m_s"),number(call,"density_kg_m3"),number(call,"dynamic_viscosity_Pa_s"),call.containsKey("target_yplus")?number(call,"target_yplus"):yplus(goal));
  out.put("reference_source",source);out.put("measurements",measurement);out.put("flow_direction",flow.isEmpty()?"unconfirmed":flow);out.put("symmetry_axis",sym.isEmpty()?"unconfirmed":sym);if(!sym.isEmpty())out.put("symmetry_plane_m",symmetryPlane(goal,sym));
  if(body instanceof MeshPart&&!flow.isEmpty()){
   Map<?,?> bounds=(Map<?,?>)measurement.get("bounds");List<?> lo=(List<?>)bounds.get("min_m"),hi=(List<?>)bounds.get("max_m");double[] a=new double[3],b=new double[3];for(int i=0;i<3;i++){a[i]=((Number)lo.get(i)).doubleValue()-10*length;b[i]=((Number)hi.get(i)).doubleValue()+10*length;}
   double domainScale=length;double bodyLength=((Number)hi.get(GeometryBounds.axis(flow.substring(1)))).doubleValue()-((Number)lo.get(GeometryBounds.axis(flow.substring(1)))).doubleValue();double fullSpan=0;if(!sym.isEmpty()){int normal=GeometryBounds.axis(sym);double plane=symmetryPlane(goal,sym);fullSpan=2*Math.max(Math.abs(((Number)hi.get(normal)).doubleValue()-plane),Math.abs(((Number)lo.get(normal)).doubleValue()-plane));}domainScale=Math.max(bodyLength,fullSpan);Double explicitDomain=number(call,"domain_reference_length_m");if(explicitDomain!=null){if(explicitDomain<=0)throw new IOException("domain_reference_length_m须为正数");domainScale=explicitDomain;}if(domainScale<=0||!Double.isFinite(domainScale))throw new IOException("无法取得有限外域尺度");
   out.put("domain_reference",Map.of("length_m",domainScale,"body_length_m",bodyLength,"full_span_m",fullSpan,"source",explicitDomain==null?"official external-aerodynamics guidance: max(body length, full span); mirror confirmed half-model span":"explicit domain_reference_length_m","minimum_guidance_multiplier",8,"starting_multiplier",10,"mesh_reference_length_m",length));
   out.put("domain_clearance_m",Map.of("upstream",10*domainScale,"downstream",10*domainScale,"free_side",10*domainScale));out.put("domain_reference_length_m",domainScale);
   out.put("official_guidance","GUID-466C9C08-A9EA-49D0-B7C4-8EA67FD9FF7A: steady external aero M0.5-2.5, farfield 8-10 times max(body length, full span); do not silently use chord for finite-wing span clearance. These recommendations are initial choices, not farfield-independence validation.");
   for(int i=0;i<3;i++){a[i]=((Number)lo.get(i)).doubleValue()-10*domainScale;b[i]=((Number)hi.get(i)).doubleValue()+10*domainScale;}
   int axis=GeometryBounds.axis(flow.substring(1));if(flow.startsWith("+"))b[axis]=((Number)hi.get(axis)).doubleValue()+10*domainScale;else a[axis]=((Number)lo.get(axis)).doubleValue()-10*domainScale;
   if(!sym.isEmpty()){int k=GeometryBounds.axis(sym);double plane=symmetryPlane(goal,sym);double low=((Number)lo.get(k)).doubleValue(),high=((Number)hi.get(k)).doubleValue(),tol=length*1e-6;if(axis==k)out.put("domain_warning","来流垂直于已给对称面，不能自动采用此域建议；先核对物理意图");else if(low>=plane-tol)a[k]=plane;else if(high<=plane+tol)b[k]=plane;else out.put("domain_warning","物体横跨指定对称面，需核对半模/裁剪，不能自动移动CAD");}
   out.put("suggested_domain_bounds_m",Map.of("min_m",List.of(a[0],a[1],a[2]),"max_m",List.of(b[0],b[1],b[2])));
  }
  out.put("write_policy","This is read-only. User-specified domain/mesh/BC/physics values override estimates. Preserve verified existing choices unless the user asks to repair/recommend. Resolve actual face selections before applying. Never remesh merely to make prism layers visible.");return out;
 }
}
