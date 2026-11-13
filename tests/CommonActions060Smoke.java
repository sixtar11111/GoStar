package local.starccm.assistant;

import java.io.IOException;
import java.util.*;

/** Pure validation checks; native mutation coverage lives in CommonActions060NativeSmoke. */
public final class CommonActions060Smoke {
    interface Checked {void run()throws Exception;}
    static void rejects(Checked action)throws Exception{try{action.run();throw new AssertionError("invalid input accepted");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception{
        if(CommonActions060.TYPES.size()!=4)throw new AssertionError("actions missing");
        for(String name:CommonActions060.TYPES)if(!CommonActions060.supports(name)||!CommonActions060.contract(name).startsWith(name+":"))throw new AssertionError(name);
        for(String name:List.of("execute_java","run_solver","execute_mesh","unknown"))if(CommonActions060.supports(name))throw new AssertionError(name);
        for(Object value:Arrays.asList(0,-1,"1",true,null,Double.NaN,Double.POSITIVE_INFINITY))rejects(()->CommonActions060.positive(value,"x"));
        for(Object value:Arrays.asList(List.of(0,0,0),List.of(1,2),List.of(1,2,Double.NaN),List.of("1",2,3),true,null))rejects(()->CommonActions060.unitVector(value,"direction"));
        for(List<Double> input:List.of(List.of(3.,4.,0.),List.of(1e308,1e308,1e308),List.of(1e-300,0.,0.))){double[] value=CommonActions060.unitVector(input,"direction");double length=Math.hypot(Math.hypot(value[0],value[1]),value[2]);if(Math.abs(length-1)>1e-14)throw new AssertionError(Arrays.toString(value));}
        for(Object input:Arrays.asList(List.of(1,1),List.of(2,1),List.of(Double.NaN,1),List.of(1,Double.POSITIVE_INFINITY),List.of(1),true,null))rejects(()->CommonActions060.range(input));
        if(!Arrays.equals(CommonActions060.range(List.of(-1,1)),new double[]{-1,1}))throw new AssertionError("signed range rejected");
        rejects(()->CommonActions060.fields(Map.of("field_id","123","unknown",3),Set.of("field_id")));
        CommonActions060.fields(Map.of("type","configure_scalar_display","id","123","expected_state","state:abc","reason","explicit","field_id","456"),Set.of("field_id"));
        rejects(()->CommonActions060.any(Map.of("type","configure_scalar_display"),Set.of("field_id","range_si")));
        for(Object name:Arrays.asList("../file","",List.of("x"),null,"A".repeat(81)))rejects(()->CommonActions060.objectName(name));
        if(!CommonActions060.objectName("升阻力系数").equals("升阻力系数"))throw new AssertionError("unicode names");
        System.out.println("PASS four typed contracts, no arbitrary/solver/mesh methods, finite SI/vector/range inputs, stable normalization, unknown/empty action and name rejection");
    }
}
