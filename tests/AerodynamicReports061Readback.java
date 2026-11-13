package local.starccm.assistant;
import java.nio.file.*;
import java.util.*;
import star.common.*;
import star.base.report.*;
import star.flow.*;
public final class AerodynamicReports061Readback {
 static int checks;static void ck(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 public static void run(Simulation s,Path dir)throws Exception{
  List<Map<String,Object>> reports=new ArrayList<>();ck(s.getReportManager().getObjects().size()==4,"Four persisted aerodynamic reports required");
  for(String kind:List.of("force","moment","force_coefficient","moment_coefficient")){
   String name="GoStar061 "+kind;Report r=s.getReportManager().getReport(name);ck(r!=null&&AerodynamicReports.kind(r).equals(kind),"Persisted type mismatch");
   ForceAndMomentBaseReport f=(ForceAndMomentBaseReport)r;Map<String,Object> a=AerodynamicReports.snapshot(f);reports.add(a);
   ck(f.getParts().getObjects().size()==1&&f.getParts().getObjects().iterator().next() instanceof Boundary,"Wall selection missing");
   Boundary wall=(Boundary)f.getParts().getObjects().iterator().next();ck(wall.getBoundaryType() instanceof WallBoundary&&!wall.getPartSurfaceGroup().getObjects().isEmpty(),"Persisted wall geometry missing");
   ck(f.getRepresentation() instanceof star.meshing.LatestMeshProxyRepresentation||f.getRepresentation() instanceof FvRepresentation,"Persisted volume representation missing");ck(!f.getUseIdealization(),"Unexpected idealization");
   ReportMonitor monitor=(ReportMonitor)s.getMonitorManager().getMonitor(name+" Monitor");ck(monitor!=null&&monitor.getReport().equals(r),"Persisted monitor binding wrong");
   MonitorPlot plot=(MonitorPlot)s.getPlotManager().getPlot(name+" History");ck(plot!=null&&new HashSet<>(plot.getMonitors().getObjects()).equals(Set.of(monitor)),"Persisted plot binding wrong");
   if(kind.endsWith("coefficient")){ck(Math.abs(((Number)a.get("reference_density_kg_m3")).doubleValue()-1.18)<1e-10,"Density wrong");ck(Math.abs(((Number)a.get("reference_velocity_m_s")).doubleValue()-210)<1e-10,"Velocity wrong");ck(Math.abs(((Number)a.get("reference_area_m2")).doubleValue()-(kind.equals("force_coefficient")?3.25:2.5))<1e-10,"Area wrong");}
   if(kind.equals("moment_coefficient"))ck(Math.abs(((Number)a.get("reference_length_m")).doubleValue()-.75)<1e-10,"Moment reference length wrong");
   ck(NativeApiBridge.equal(a.get("direction"),kind.equals("force_coefficient")?List.of(1,0,0):kind.equals("moment")?List.of(0,1,0):List.of(.6,0,.8),1e-10),"Persisted direction wrong");
   ck(a.get("force_component").equals(kind.equals("force_coefficient")?"PRESSURE":"PRESSURE_AND_SHEAR"),"Persisted force component wrong");
   if(kind.equals("force_coefficient"))ck(Math.abs(((Number)a.get("reference_pressure_pa")).doubleValue()-123)<1e-10,"Persisted reference pressure wrong");
   if(kind.equals("moment_coefficient"))ck(NativeApiBridge.equal(a.get("origin_m"),List.of(.2,-.1,.04),1e-10),"Persisted coefficient moment origin wrong");
   if(kind.equals("moment"))ck(NativeApiBridge.equal(a.get("origin_m"),List.of(.3,.2,.1),1e-10),"Persisted modified moment origin wrong");
  }
  ck(s.getSimulationIterator().getCurrentIteration()==0,"Unexpected CFD");Files.writeString(dir.resolve("result.json"),ConfigurationEngine.json(Map.of("passed",true,"checks",checks,"reports",reports,"iteration",0,"independent_reopen",true)));
 }
}
