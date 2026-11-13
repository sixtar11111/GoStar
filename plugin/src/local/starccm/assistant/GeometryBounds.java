package local.starccm.assistant;
import star.meshing.MeshPart;
import java.nio.file.*;import java.nio.*;import java.io.*;import java.util.*;
/** Measures the existing tessellation; never edits or re-tessellates source geometry. */
final class GeometryBounds {
 static Map<String,Object> measureSurface(star.common.Simulation sim,star.common.PartSurface surface)throws IOException {
  star.base.neo.NeoClient connection=sim.getServer().getServerProxy();boolean previous=connection.isMacroPlaying();connection.setMacroPlaying(true);
  try {List<Double> lo=new ArrayList<>(),hi=new ArrayList<>();boolean volumeFallback=false;star.common.FieldFunction pos=sim.getFieldFunctionManager().getFunction("Position");
  for(int i=0;i<3;i++){
   star.base.report.MinReport min=null;star.base.report.MaxReport max=null;
   try{min=sim.getReportManager().createReport(star.base.report.MinReport.class);max=sim.getReportManager().createReport(star.base.report.MaxReport.class);min.getParts().setObjects(surface);max.getParts().setObjects(surface);min.setRepresentation(sim.getRepresentationManager().getObject("Geometry"));max.setRepresentation(sim.getRepresentationManager().getObject("Geometry"));min.setFieldFunction(pos.getComponentFunction(i));max.setFieldFunction(pos.getComponentFunction(i));sim.getServer().getClientNotifyHandler().processCommandQueue(true);double a=min.getReportMonitorValue(),b=max.getReportMonitorValue();
    if(!Double.isFinite(a)||!Double.isFinite(b)||a>b){star.common.Boundary boundary=surface.hasBoundary();boolean current=false;if(sim.has(star.meshing.MeshOperationManager.class)!=null)for(star.meshing.MeshOperation op:sim.get(star.meshing.MeshOperationManager.class).getObjects())if(op instanceof star.meshing.AutoMeshOperation&&op.getInputGeometryObjects().getObjects().contains(surface.getPart())&&!op.isDirty())current=true;
     if(boundary!=null&&boundary.getPartSurfaceGroup().getObjects().size()==1&&current){min.getParts().setObjects(boundary);max.getParts().setObjects(boundary);min.setRepresentation(sim.getRepresentationManager().getObject("Volume Mesh"));max.setRepresentation(sim.getRepresentationManager().getObject("Volume Mesh"));sim.getServer().getClientNotifyHandler().processCommandQueue(true);a=min.getReportMonitorValue();b=max.getReportMonitorValue();volumeFallback=true;}
    }if(!Double.isFinite(a)||!Double.isFinite(b)||a>b)throw new IOException("表面坐标求值无效；未把空值或其他面的范围当结果");lo.add(a);hi.add(b);}
   finally{if(min!=null)sim.getReportManager().removeObjects(min);if(max!=null)sim.getReportManager().removeObjects(max);sim.getServer().getClientNotifyHandler().processCommandQueue(true);}
  }return Map.of("centroid_min_m",lo,"centroid_max_m",hi,"axes_m",Map.of("x",List.of(lo.get(0),hi.get(0)),"y",List.of(lo.get(1),hi.get(1)),"z",List.of(lo.get(2),hi.get(2))),"coordinate_system","Lab","unit","m","source",volumeFallback?"current Volume Mesh face centroids on a uniquely assigned boundary":"Geometry face centroids; not CAD vertex bounds");
  }finally{connection.setMacroPlaying(previous);}
 }
 static Map<String,Object> measure(MeshPart part)throws IOException {
  Path dir=Files.createTempDirectory("star-assistant-bounds-");Path file=dir.resolve("surface.stl");
  try {part.exportStlPart(file.toString(),true);return read(file);}
  finally {Files.deleteIfExists(file);Files.deleteIfExists(dir);}
 }
 static Map<String,Object> measureSection(MeshPart part,String axis,double plane,String projection)throws IOException {
  int normal=axis(axis),along=axis(projection);if(normal==along||!Double.isFinite(plane))throw new IOException("截面轴与投影轴必须不同，坐标必须有限");
  Path dir=Files.createTempDirectory("star-assistant-section-");Path file=dir.resolve("surface.stl");
  try{part.exportStlPart(file.toString(),true);return section(file,normal,plane,along);}
  finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
 }
 static int axis(String name)throws IOException {int n=List.of("x","y","z").indexOf(name.toLowerCase(Locale.ROOT));if(n<0)throw new IOException("坐标轴须为x/y/z");return n;}
 private interface TriangleReader {void accept(double[][] vertices)throws IOException;}
 private static void triangles(Path file,TriangleReader reader)throws IOException {
  long size=Files.size(file);if(size>128L*1024*1024||size<15)throw new IOException("三角化文件过大或为空，未取得尺寸");
  byte[] data=Files.readAllBytes(file);long count=size>=84?Integer.toUnsignedLong(ByteBuffer.wrap(data,80,4).order(ByteOrder.LITTLE_ENDIAN).getInt()):-1;
  if(count>=0&&84L+50L*count==size){ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);for(int n=0;n<count;n++){b.position(84+50*n+12);double[][] v=new double[3][3];for(int j=0;j<3;j++)for(int k=0;k<3;k++)v[j][k]=b.getFloat();validate(v);reader.accept(v);}}
  else {try(BufferedReader in=Files.newBufferedReader(file,java.nio.charset.StandardCharsets.US_ASCII)){String line;List<double[]> group=new ArrayList<>();while((line=in.readLine())!=null){String[] fields=line.trim().split("\\s+");if(fields.length>0&&fields[0].equals("vertex")){if(fields.length!=4)throw new IOException("STL顶点格式错误");try{group.add(new double[]{Double.parseDouble(fields[1]),Double.parseDouble(fields[2]),Double.parseDouble(fields[3])});}catch(NumberFormatException e){throw new IOException("STL坐标无效",e);}if(group.size()==3){double[][] v=group.toArray(new double[3][]);validate(v);reader.accept(v);group.clear();}}}if(!group.isEmpty())throw new IOException("STL三角形不完整");}}
 }
 private static void validate(double[][] vertices)throws IOException {for(double[] v:vertices)for(double n:v)if(!Double.isFinite(n))throw new IOException("STL包含非有限坐标");}
 static Map<String,Object> read(Path file)throws IOException {
  double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};long[] vertices={0};
  triangles(file,v->{for(double[] p:v){include(lo,hi,p[0],p[1],p[2]);vertices[0]++;}});
  if(vertices[0]==0)throw new IOException("没有三角化顶点，不能推断尺寸");
  return Map.of("min_m",List.of(lo[0],lo[1],lo[2]),"max_m",List.of(hi[0],hi[1],hi[2]),"span_m",List.of(hi[0]-lo[0],hi[1]-lo[1],hi[2]-lo[2]),"axes_m",Map.of("x",List.of(lo[0],hi[0]),"y",List.of(lo[1],hi[1]),"z",List.of(lo[2],hi[2])),"vertex_samples",vertices[0],"source","STAR exported surface tessellation in Lab/SI; approximate CAD bounds");
 }
 static Map<String,Object> section(Path file,int normal,double plane,int along)throws IOException {
  if(normal<0||normal>2||along<0||along>2||normal==along||!Double.isFinite(plane))throw new IOException("截面参数无效");
  Map<String,Object> bounds=read(file);List<?> span=(List<?>)bounds.get("span_m");double scale=1;for(Object v:span)scale=Math.max(scale,((Number)v).doubleValue());double tol=scale*1e-7;
  double[] lo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},hi={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};long[] samples={0},hits={0};
  triangles(file,v->{List<double[]> points=new ArrayList<>();for(int i=0;i<3;i++){
   double[] a=v[i],b=v[(i+1)%3];double da=a[normal]-plane,db=b[normal]-plane;
   if(Math.abs(da)<=tol)points.add(a.clone());
   if((da < -tol && db > tol)||(db < -tol && da > tol)){double t=da/(da-db);double[] q=new double[3];for(int k=0;k<3;k++)q[k]=a[k]+t*(b[k]-a[k]);points.add(q);}
  }if(points.size()>=2){hits[0]++;for(double[] q:points){q[normal]=plane;include(lo,hi,q[0],q[1],q[2]);samples[0]++;}}});
  if(samples[0]==0||hi[along]-lo[along]<=tol)throw new IOException("指定平面与三角化几何没有可测量的非零截面跨度；不能使用整体包围盒替代");
  Map<String,Object> out=new LinkedHashMap<>();out.put("plane_axis",List.of("x","y","z").get(normal));out.put("plane_m",plane);out.put("projection_axis",List.of("x","y","z").get(along));out.put("projected_extent_m",hi[along]-lo[along]);out.put("min_m",List.of(lo[0],lo[1],lo[2]));out.put("max_m",List.of(hi[0],hi[1],hi[2]));out.put("coordinate_system","Lab");out.put("unit","m");out.put("tolerance_m",tol);out.put("intersection_triangles",hits[0]);out.put("point_samples",samples[0]);out.put("source","existing surface tessellation / plane intersection; approximate projected section extent, not exact CAD chord or mean aerodynamic chord");return out;
 }
 /** Only for an explicitly declared half model. A unique measured flat cap is evidence;
  * the positive chord-axis inflow remains a disclosed zero-incidence convention. */
 static Map<String,Object> inferHalfModel(MeshPart part)throws IOException {
  Path dir=Files.createTempDirectory("star-assistant-half-");Path file=dir.resolve("surface.stl");
  try{part.exportStlPart(file.toString(),true);Map<String,Object> b=read(file);List<?> lower=(List<?>)b.get("min_m"),upper=(List<?>)b.get("max_m");double scale=0;for(int i=0;i<3;i++)scale=Math.max(scale,((Number)upper.get(i)).doubleValue()-((Number)lower.get(i)).doubleValue());final double tol=Math.max(1e-9,scale*1e-7);List<Map<String,Object>> candidates=new ArrayList<>();
   for(int normal=0;normal<3;normal++)for(int side=0;side<2;side++){final int k=normal;final double plane=((Number)(side==0?lower:upper).get(k)).doubleValue();if(Math.abs(plane)>10*tol)continue;double[] capLo={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY},capHi={-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE};double[] area={0};int[] faces={0};
    triangles(file,v->{if(Math.abs(v[0][k]-plane)>tol||Math.abs(v[1][k]-plane)>tol||Math.abs(v[2][k]-plane)>tol)return;double[] a=new double[3],c=new double[3];for(int j=0;j<3;j++){a[j]=v[1][j]-v[0][j];c[j]=v[2][j]-v[0][j];}double nx=a[1]*c[2]-a[2]*c[1],ny=a[2]*c[0]-a[0]*c[2],nz=a[0]*c[1]-a[1]*c[0],ar=.5*Math.sqrt(nx*nx+ny*ny+nz*nz);if(ar<=tol*tol)return;area[0]+=ar;faces[0]++;for(double[] q:v)include(capLo,capHi,q[0],q[1],q[2]);});
    if(faces[0]<2||area[0]<scale*scale*1e-7)continue;int along=-1;double wide=0,thin=Double.POSITIVE_INFINITY;for(int j=0;j<3;j++)if(j!=k){double span=capHi[j]-capLo[j];if(span>wide){wide=span;along=j;}thin=Math.min(thin,span);}if(along<0||wide<3*Math.max(thin,tol))continue;
    candidates.add(new LinkedHashMap<>(Map.of("symmetry_axis",List.of("x","y","z").get(k),"symmetry_plane_m",plane,"flow_axis","+"+List.of("x","y","z").get(along),"cap_area_m2",area[0],"cap_triangles",faces[0],"projected_chord_m",wide,"plane_tolerance_m",tol)));
   }
   if(candidates.size()!=1)return new LinkedHashMap<>(Map.of("status","ambiguous","candidates",candidates,"reason","Half-model declaration alone is insufficient: require one measured near-origin planar cap with a distinct chord direction. Do not invent a plane or flow."));
   Map<String,Object> result=candidates.get(0);result.put("status","inferred_from_unique_measured_cap");result.put("basis","User-declared half model; unique triangulated planar cap near Lab origin. Positive chord-axis, zero incidence is an explicit tutorial convention/default, not measured flow or a CAD modification.");return result;
  }finally{Files.deleteIfExists(file);Files.deleteIfExists(dir);}
 }
 private static void include(double[] lo,double[] hi,double x,double y,double z)throws IOException{double[] v={x,y,z};for(int i=0;i<3;i++){if(!Double.isFinite(v[i]))throw new IOException("STL包含非有限坐标");lo[i]=Math.min(lo[i],v[i]);hi[i]=Math.max(hi[i],v[i]);}}
}
