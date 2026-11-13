package local.starccm.assistant;
import java.nio.file.*;
import java.util.*;

/** Human-facing receipts must identify every requested change without exposing protocol data. */
public final class ChangeSummary060Smoke {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Map<String,Object> row(String kind,Map<String,Object> requested,Map<String,Object> actual){Map<String,Object> row=new LinkedHashMap<>();row.put("kind",kind);row.put("status","saved");row.put("target_name","测试目标");row.put("recommended",requested);row.put("actual",actual);row.put("selection_names",List.of("机翼","壁面"));row.put("reference_names",Map.of("coordinate_system_id","Lab","field_id","压力系数","representation_id","Volume Mesh"));return row;}
    public static void main(String[] args)throws Exception{
        Map<String,Object> requested=new LinkedHashMap<>();requested.put("reference_density_kg_m3",1.2);requested.put("reference_velocity_m_s",200);requested.put("reference_area_m2",3.4);requested.put("reference_length_m",5.6);requested.put("direction",List.of(1,0,0));requested.put("origin_m",List.of(0.1,0.2,0.3));requested.put("coordinate_system_id","private:coordinate");
        String coefficients=ChangeSummary.item(row("configure_coefficient_report",requested,Map.of("actual",requested)));
        for(String expected:List.of("参考密度=1.2 kg/m³","参考面积=3.4 m²","参考长度=5.6 m","方向=(1, 0, 0)","原点=(0.1, 0.2, 0.3) m","坐标系=Lab"))check(coefficients.contains(expected),"coefficient requested change missing: "+expected+" in "+coefficients);
        check(coefficients.contains("参考速度=200 m/s")||coefficients.contains("参考速度=2E+2 m/s"),"coefficient velocity value/unit missing");
        check(!coefficients.contains("private:"),"coefficient summary leaked internal ID");
        String one=ChangeSummary.item(row("configure_coefficient_report",Map.of("reference_area_m2",3.4),Map.of("actual",requested)));
        check(one.contains("参考面积=3.4 m²"),"requested area absent");for(String unexpected:List.of("参考密度","参考速度","参考长度","方向=","原点=","坐标系="))check(!one.contains(unexpected),"unrequested coefficient field falsely listed: "+unexpected);
        Map<String,Object> planeRequested=Map.of("origin_m",List.of(1,2,3),"normal",List.of(0,0,5),"selection_ids",List.of("private:region"),"coordinate_system_id","private:coordinate");
        String plane=ChangeSummary.item(row("configure_plane_section",planeRequested,Map.of("actual",Map.of("origin_m",List.of(1,2,3),"normal",List.of(0,0,1)))));
        for(String expected:List.of("原点=(1, 2, 3) m","法向=(0, 0, 1)","坐标系=Lab","作用对象=机翼、壁面"))check(plane.contains(expected),"plane requested/readback setting missing: "+expected);
        check(!plane.contains("private:"),"plane summary leaked internal ID");
        Map<String,Object> scalarRequested=Map.of("field_id","private:field","representation_id","private:rep","selection_ids",List.of("private:region"),"range_si",List.of(-1,2),"automatic_range",false,"show_mesh",true);
        String scalar=ChangeSummary.item(row("configure_scalar_display",scalarRequested,Map.of("actual",Map.of("range_si",List.of(-1,2)))));
        for(String expected:List.of("场函数=压力系数","表示=Volume Mesh","作用对象=机翼、壁面","色标范围=(-1, 2)","自动色标=关闭","网格线=启用"))check(scalar.contains(expected),"scalar requested setting missing: "+expected);
        String onlyMesh=ChangeSummary.item(row("configure_scalar_display",Map.of("show_mesh",false),Map.of("actual",Map.of("field_id","private:field","range_si",List.of(-1,2)))));
        check(onlyMesh.contains("网格线=关闭")&&!onlyMesh.contains("场函数=")&&!onlyMesh.contains("色标范围="),"unrequested scalar settings falsely listed");
        String generic=ChangeSummary.item(row("invoke_api",Map.of("method","setValue(int)","arguments",List.of(222)),Map.of("target_name","最大步数","property","Value","before",111,"actual",222,"method","setValue(int)","readback","getValue()","verified",true)));
        check(generic.contains("最大步数")&&generic.contains("111 → 222"),"generic before/after target missing");
        check(generic.contains("Value")||generic.contains("数值"),"generic changed property name missing: "+generic);
        check(!generic.contains("setValue(")&&!generic.contains("getValue(")&&!generic.contains("arguments")&&!generic.contains("expected_state"),"generic chat leaked protocol/method code");
        String result="PASS ChangeSummary060Smoke "+checks+" checks; pure receipt formatting only.\n";if(args.length>0){Path output=Path.of(args[0]);Files.createDirectories(output);Files.writeString(output.resolve("result.txt"),result);}System.out.print(result);
    }
}
