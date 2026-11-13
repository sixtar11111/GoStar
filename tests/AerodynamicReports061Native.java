package local.starccm.assistant;
import java.nio.file.*;
import java.util.*;
import star.common.*;
import star.meshing.*;
import star.base.neo.*;
import star.base.report.*;
import star.flow.*;

/** Isolated native report acceptance; creates a synthetic box, never opens user input or solves. */
public final class AerodynamicReports061Native {
 static int checks;static final List<Map<String,Object>> results=new ArrayList<>();
 static void ck(boolean v,String reason){checks++;if(!v)throw new AssertionError(reason);}
 static Map<String,Object> parameters(String kind,String name,Boundary wall){
  Map<String,Object> p=new LinkedHashMap<>();p.put("name",name);p.put("report_kind",kind);p.put("selection_ids",List.of(wall.getObjectKeyAsString()));p.put("direction",List.of(3,0,4));
  if(kind.startsWith("moment"))p.put("origin_m",List.of(.2,-.1,.04));
  if(kind.endsWith("coefficient")){p.put("reference_density_kg_m3",1.18);p.put("reference_velocity_m_s",210.);p.put("reference_area_m2",2.5);}
  if(kind.equals("moment_coefficient"))p.put("reference_length_m",.75);
  p.put("create_monitor",true);p.put("plot_name",name+" History");return p;
 }
 static Map<?,?> current(ConfigurationEngine e,ClientServerObject object)throws Exception{
  String text=e.inspectOperations(object.getObjectKeyAsString(),0);for(String line:text.split("\\R"))if(line.startsWith("{")){Object p=CodexAssistant.parseJson(line);if(p instanceof Map&&object.getObjectKeyAsString().equals(((Map<?,?>)p).get("id")))return(Map<?,?>)p;}
  throw new AssertionError("No exact native catalog row for "+object.getPresentationName()+" in "+text);
 }
 static Map<String,Object> apply(Simulation s,Path dir,String type,ClientServerObject target,Map<String,Object> params)throws Exception{
  ConfigurationEngine e=new ConfigurationEngine(s,new CodexAssistant());e.beginTask("配置气动力报告，按指定选面、方向、参考量创建或修改；不运行网格或求解。",dir.resolve("reports061.sim").toString());
  Map<?,?> state=current(e,target);Map<String,Object> action=new LinkedHashMap<>(params);action.put("type",type);action.put("id",target.getObjectKeyAsString());action.put("expected_state",state.get("expected_state"));action.put("reason","Native GoStar report acceptance");
  String plan=ConfigurationEngine.json(Map.of("changes",List.of(),"actions",List.of(action)));ck(!e.preview(plan).isBlank(),"Native preflight missing");Map<String,Object> receipt=e.apply(plan);
  ck(!Boolean.TRUE.equals(receipt.get("failed"))&&Boolean.TRUE.equals(receipt.get("progress")),"Native transaction failed: "+receipt);
  e.finish("报告配置已读回保存；未运行求解。",false);String summary=e.chatSummary();ck(!summary.contains("expected_state")&&!summary.contains("create_aerodynamic_report"),"Chat leaked protocol");
  results.add(Map.of("action",action,"receipt",receipt,"summary",summary));return receipt;
 }
 static void reject(AerodynamicReports api,Simulation s,Map<String,Object> p,String why)throws Exception{
  int before=s.getReportManager().getObjects().size();Map<String,Object> action=new LinkedHashMap<>(p);action.put("type","create_aerodynamic_report");
  try{api.validate(action,s,"test");throw new AssertionError("Invalid input accepted: "+why);}catch(java.io.IOException expected){ck(s.getReportManager().getObjects().size()==before,"Rejection created a report");results.add(Map.of("rejected",why,"reason",expected.getMessage()));}
 }
 public static void run(Simulation s,Path dir)throws Exception{
  Files.createDirectories(dir);ck(s.getRegionManager().getRegions().isEmpty(),"Requires a new isolated SIM");
  SimulationPartManager pm=s.get(SimulationPartManager.class);SimpleBlockPart box=s.get(MeshPartFactory.class).createNewBlockPart(pm);box.setPresentationName("GoStar061 Synthetic Box");box.setCorners(new DoubleVector(new double[]{0,0,0}),new DoubleVector(new double[]{1,.8,.2}));box.rebuildSimpleShapePart();
  s.getRegionManager().newRegionsFromParts(List.of(box),"OneRegion",null,"OneBoundaryPerPartSurface",null,RegionManager.CreateInterfaceMode.BOUNDARY);
  Region region=s.getRegionManager().getRegions().iterator().next();PhysicsContinuum phys=s.getContinuumManager().createContinuum(PhysicsContinuum.class);
  PhysicsProfiles.execute(s,Map.of("type","configure_physics_profile","profile","steady_compressible_sa","space_dimension",3),phys);s.getContinuumManager().setContinuum(List.of(region),phys);s.getServer().getClientNotifyHandler().processCommandQueue(true);
  Boundary wall=region.getBoundaryManager().getBoundaries().stream().filter(b->!b.getPartSurfaceGroup().getObjects().isEmpty()).findFirst().orElseThrow();wall.setBoundaryType(WallBoundary.class);wall.setPresentationName("GoStar061 Wall");
  Files.writeString(dir.resolve("fixture-representations.json"),ConfigurationEngine.json(s.getRepresentationManager().getObjects().stream().map(r->Map.of("id",r.getObjectKeyAsString(),"name",r.getPresentationName(),"type",r.getClass().getName())).toList()));
  for(String kind:List.of("force","moment","force_coefficient","moment_coefficient")){
   String name="GoStar061 "+kind;apply(s,dir,"create_aerodynamic_report",s,parameters(kind,name,wall));Report report=s.getReportManager().getReport(name);ck(AerodynamicReports.kind(report).equals(kind),"Wrong report class");
   Map<String,Object> actual=AerodynamicReports.snapshot((ForceAndMomentBaseReport)report);ck(actual.get("use_idealization").equals(false),"Half model integration unexpectedly doubled");ck(actual.get("force_component").equals("PRESSURE_AND_SHEAR"),"Default force component wrong");
   ck(NativeApiBridge.equal(actual.get("direction"),List.of(.6,0,.8),1e-10),"Normalized direction not saved");
  }
  ForceCoefficientReport cd=(ForceCoefficientReport)s.getReportManager().getReport("GoStar061 force_coefficient");
  apply(s,dir,"configure_aerodynamic_report",cd,Map.of("reference_area_m2",3.25,"direction",List.of(1,0,0),"force_component","pressure","reference_pressure_pa",123.));
  ck(Math.abs(cd.getReferenceVelocity().getSIValue()-210)<1e-10,"Partial update reset velocity");ck(Math.abs(cd.getReferenceArea().getSIValue()-3.25)<1e-10,"Area not updated");
  // A second independent natural-language task updates another kind without a new plugin session.
  MomentReport moment=(MomentReport)s.getReportManager().getReport("GoStar061 moment");apply(s,dir,"configure_aerodynamic_report",moment,Map.of("origin_m",List.of(.3,.2,.1),"direction",List.of(0,1,0)));
  Map<String,ClientServerObject> objects=new LinkedHashMap<>();objects.put(s.getObjectKeyAsString(),s);objects.put(wall.getObjectKeyAsString(),wall);objects.put(region.getObjectKeyAsString(),region);for(PartSurface face:wall.getPartSurfaceGroup().getObjects())objects.put(face.getObjectKeyAsString(),face);for(Representation rep:s.getRepresentationManager().getObjects())objects.put(rep.getObjectKeyAsString(),rep);
  AerodynamicReports api=new AerodynamicReports(s,objects);Map<String,Object> valid=parameters("force_coefficient","GoStar061 Invalid",wall);
  for(String key:List.of("reference_area_m2","reference_density_kg_m3","reference_velocity_m_s")){Map<String,Object> p=new LinkedHashMap<>(valid);p.remove(key);reject(api,s,p,"missing "+key);}
  for(var field:Map.of("direction",List.of(0,0,0),"reference_area_m2",-1,"selection_ids",List.of(region.getObjectKeyAsString()),"unknown_parameter",1,"name","GoStar061 force").entrySet()){Map<String,Object> p=new LinkedHashMap<>(valid);p.put(field.getKey(),field.getValue());reject(api,s,p,"invalid "+field.getKey());}
  PartSurface face=wall.getPartSurfaceGroup().getObjects().iterator().next();Map<String,Object> overlapping=new LinkedHashMap<>(valid);overlapping.put("selection_ids",List.of(wall.getObjectKeyAsString(),face.getObjectKeyAsString()));reject(api,s,overlapping,"overlapping boundary and face");
  Map<String,Object> faceOnly=new LinkedHashMap<>(valid);faceOnly.put("selection_ids",List.of(face.getObjectKeyAsString()));faceOnly.put("type","create_aerodynamic_report");api.validate(faceOnly,s,"test");ck(true,"Attached wall face accepted");
  Map<String,Object> missingOrigin=parameters("moment","GoStar061 InvalidMoment",wall);missingOrigin.remove("origin_m");reject(api,s,missingOrigin,"missing moment origin");
  Map<String,Object> monitorCollision=new LinkedHashMap<>(valid);monitorCollision.put("monitor_name","GoStar061 force Monitor");reject(api,s,monitorCollision,"monitor name collision before creation");
  ck(s.getReportManager().getObjects().size()==4,"Unexpected placeholder report");ck(s.getSimulationIterator().getCurrentIteration()==0,"Unexpected CFD iterations");s.saveState(dir.resolve("reports061.sim").toString());
  List<Map<String,Object>> reports=new ArrayList<>();for(Report r:s.getReportManager().getObjects())reports.add(AerodynamicReports.snapshot((ForceAndMomentBaseReport)r));
  Files.writeString(dir.resolve("result.json"),ConfigurationEngine.json(Map.of("passed",true,"checks",checks,"reports",reports,"actions_and_rejections",results,"iteration",0,"scope","actual ConfigurationEngine creation/update/save; no mesh or CFD; no external model")));
 }
}
