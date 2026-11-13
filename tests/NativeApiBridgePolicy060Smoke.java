package local.starccm.assistant;

import java.lang.reflect.Method;
import java.util.List;
import star.base.neo.ClientServerObject;
import star.base.neo.DoubleVector;
import star.common.*;

/** Installed-runtime policy regression. Native value writes are covered separately. */
public final class NativeApiBridgePolicy060Smoke {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{return type.getMethod(name,args);}
    public static void main(String[]args)throws Exception {
        check(NativeApiBridge.allowRead(method(IntegerValue.class,"getValue")),"Integer property getter must be discoverable");
        check(NativeApiBridge.allowWrite(method(IntegerValue.class,"setValue",int.class),IntegerValue.class),"Integer property setter must be discoverable");
        check(NativeApiBridge.signature(method(IntegerValue.class,"setValue",int.class)).equals("setValue(int)"),"Exact overload signature");
        check(NativeApiBridge.allowRead(method(ScalarPhysicalQuantity.class,"getSIValue")),"Physical SI readback");
        check(NativeApiBridge.allowWrite(method(ScalarPhysicalQuantity.class,"setValueAndUnits",double.class,Units.class),ScalarPhysicalQuantity.class),"Dimensioned scalar uses explicit units");
        check(!NativeApiBridge.allowWrite(method(ScalarPhysicalQuantity.class,"setValue",double.class),ScalarPhysicalQuantity.class),"Do not silently interpret dimensional setValue in display units");
        check(NativeApiBridge.allowRead(method(VectorPhysicalQuantity.class,"getSIVector")),"Physical vector SI readback");
        check(NativeApiBridge.allowWrite(method(VectorPhysicalQuantity.class,"setVectorAndUnits",DoubleVector.class,Units.class),VectorPhysicalQuantity.class),"Dimensioned vector uses explicit units");
        check(!NativeApiBridge.allowWrite(method(VectorPhysicalQuantity.class,"setVector",DoubleVector.class),VectorPhysicalQuantity.class),"No implicit dimensional vector unit");
        check(!NativeApiBridge.allowRead(method(ClientServerObject.class,"getServer")),"No server escape");
        check(!NativeApiBridge.allowRead(method(ClientServerObject.class,"getParent")),"No parent escape");
        check(!NativeApiBridge.allowRead(method(ClientServerObject.class,"getObjectRegistry")),"No global registry escape");
        check(!NativeApiBridge.allowRead(method(Simulation.class,"get",Class.class)),"get(Class) may initialize and is not a safe read");
        check(NativeApiBridge.allowRead(method(Simulation.class,"has",Class.class)),"Existing-only has(Class) remains available");
        check(!NativeApiBridge.allowRead(method(Simulation.class,"getSimulationIterator")),"No solver controller escape");
        check(!NativeApiBridge.allowRead(method(Simulation.class,"getMeshPipelineController")),"No meshing controller escape");
        check(!NativeApiBridge.allowRead(method(Simulation.class,"getSessionDir")),"No session/filesystem access");
        check(!NativeApiBridge.allowRead(method(Simulation.class,"getApplication")),"No application escape");
        check(!NativeApiBridge.allowWrite(method(Simulation.class,"setCollaborationEnabled",boolean.class),Simulation.class),"No global session modification");
        check(!NativeApiBridge.allowWrite(method(Units.class,"setPreferred",boolean.class),Units.class),"No unit-system mutation");
        check(!NativeApiBridge.allowWrite(method(Coordinate.class,"setValue",DoubleVector.class),Coordinate.class),"Coordinate raw values require dedicated unit-aware workflow");
        check(!NativeApiBridge.allowWrite(method(Coordinate.class,"setVector",DoubleVector.class),Coordinate.class),"Coordinate raw vector cannot bypass SI policy");
        check(!NativeApiBridge.allowWrite(method(Coordinate.class,"setUnits0",Units.class),Coordinate.class),"Coordinate per-axis units cannot be changed generically");
        check(!NativeApiBridge.allowWrite(method(Coordinate.class,"setCoordinateSystem",CoordinateSystem.class),Coordinate.class),"Coordinate system mutation retains dedicated workflow");
        check(!NativeApiBridge.allowWrite(method(PhysicalQuantity.class,"setUnits",Units.class),PhysicalQuantity.class),"Generic units mutation has no SI proof");
        check(!NativeApiBridge.allowWrite(method(ValueFunction.class,"setUnits",Units.class),ValueFunction.class),"ValueFunction units mutation has no SI proof");
        check(!NativeApiBridge.allowWrite(method(Boundary.class,"setBoundaryType",BoundaryType.class),Boundary.class),"Boundary type retains typed workflow guard");
        check(!NativeApiBridge.allowWrite(method(ClientServerObject.class,"set",String.class,int.class),IntegerValue.class),"No opaque property-key setter");
        check(!NativeApiBridge.allowWrite(method(ClientServerObject.class,"setSilently",String.class,int.class),IntegerValue.class),"No silent opaque property writes");
        check(NativeApiBridge.equal(List.of(1.0,2.0,3.0),List.of(1,2,3),0),"Canonical numeric vector comparison");
        check(!NativeApiBridge.equal(List.of(1.0,2.0),List.of(1.0),1e-10),"Vector size mismatch cannot verify");
        check(!NativeApiBridge.equal(1.0,1.01,1e-6),"Readback mismatch cannot verify");
        check(!NativeApiBridge.equal(Double.NaN,Double.NaN,1),"Nonfinite values cannot verify");
        check(NativeApiBridge.equal(1.0,1+1e-12,1e-10),"Finite roundoff tolerance");
        System.out.println("NativeApiBridgePolicy060Smoke PASS checks="+checks);
    }
}
