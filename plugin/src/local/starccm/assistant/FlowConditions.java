package local.starccm.assistant;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import star.base.neo.ClientServerObject;
import star.common.Boundary;
import star.common.ConditionManager;
import star.common.ConstantScalarProfileMethod;
import star.common.ConstantVectorProfileMethod;
import star.common.FreeStreamBoundary;
import star.common.InletBoundary;
import star.common.PhysicsContinuum;
import star.common.PressureBoundary;
import star.common.Profile;
import star.common.Region;
import star.common.ScalarPhysicalQuantity;
import star.common.Simulation;
import star.common.Units;
import star.common.VectorProfile;
import star.energy.FreeStreamOption;
import star.energy.StaticTemperatureProfile;
import star.energy.SutherlandLaw;
import star.flow.DynamicViscosityProperty;
import star.flow.FlowDirectionOption;
import star.flow.FlowDirectionProfile;
import star.flow.InitialPressureProfile;
import star.flow.InletVelocityOption;
import star.flow.MachNumberProfile;
import star.flow.ReferencePressure;
import star.flow.StaticPressureProfile;
import star.flow.VelocityProfile;
import star.kwturb.KwTurbSpecOption;
import star.material.ConstantMaterialPropertyMethod;
import star.material.Material;
import star.material.MaterialProperty;
import star.material.MolecularWeightProperty;
import star.material.SingleComponentGasModel;
import star.saturb.SaTurbSpecOption;
import star.turbulence.TurbulenceIntensityProfile;
import star.turbulence.TurbulentViscosityRatioProfile;

/** Applies one explicit SI operating condition to a continuum and selected open boundaries.
 * Boundary selection is supplied by the caller; this helper does not infer face ownership.
 */
final class FlowConditions {
    private FlowConditions() { }
    private static final double R=287.05287, GAMMA=1.4;
    private static final Set<String> KEYS=Set.of("type","id","expected_state","reason","boundary_ids","mach","altitude_m",
        "static_temperature_K","static_pressure_Pa","speed_m_s","velocity_m_s","flow_direction",
        "reference_pressure_Pa","turbulent_viscosity_ratio","turbulence_intensity","dynamic_viscosity_Pa_s");
    private static final class Plan {
        PhysicsContinuum continuum; final List<Boundary> boundaries=new ArrayList<>();
        double temperature,absolutePressure,referencePressure,mach,speed,viscosity;
        double[] direction,velocity; Double tvr,intensity,explicitMu;
        boolean isa,sa,sst; DynamicViscosityProperty viscosityProperty;
        final List<String> assumptions=new ArrayList<>();
    }
    static boolean supports(String type) { return "configure_flow_conditions".equals(type); }
    static String contract() {
        return "configure_flow_conditions：id=已有PhysicsContinuum或其Region，boundary_ids=该连续体所属区域中明确选择的非空边界ID列表，支持现有FreeStreamBoundary、InletBoundary、PressureBoundary，不改变边界类型。"
            +"mach+altitude_m(0..11000m)可按ISA对空气推导静温/绝对静压/速度；或明确static_temperature_K、static_pressure_Pa和speed_m_s/velocity_m_s。flow_direction为Lab系三分量，默认+x并归一化；velocity_m_s明确Lab系三分量。"
            +"reference_pressure_Pa可选，省略保留当前参考压力，边界与初值表压=绝对静压-参考压力。SA的turbulent_viscosity_ratio省略采用教程0.2105；SST省略时保留原生强度/TVR，可明确turbulence_intensity(0..1)和TVR。"
            +"同步设置并按SI读回初始P/T/U及所选边界P/T/Mach/方向/湍流。只有显式dynamic_viscosity_Pa_s才强制常黏度；原有常黏度按空气Sutherland在本工况温度估算更新，已有Sutherland/其他材料方法保留并记录。"
            +"仅作用于完整可压缩理想空气SA或SST模型链，不划网格、不初始化解、不启动求解；显式数值优先于ISA，冲突的Mach/速度/方向会拒绝。";
    }
    static Map<String,Object> metadata() {
        return Map.of("actions",List.of("configure_flow_conditions"),
            "target_types",List.of("star.common.PhysicsContinuum","star.common.Region"),
            "required",List.of("id","boundary_ids","mach+altitude_m OR static_temperature_K+static_pressure_Pa+(speed_m_s|velocity_m_s|mach)"),
            "boundary_types",List.of(FreeStreamBoundary.class.getName(),InletBoundary.class.getName(),PressureBoundary.class.getName()),
            "units","All numeric inputs and verified readback are SI; static_pressure_Pa is absolute; turbulence_intensity is a fraction",
            "reference_pressure","preserve existing unless explicitly supplied",
            "sa_default_turbulent_viscosity_ratio",0.2105,
            "contract",contract());
    }
    private static PhysicsContinuum continuum(ClientServerObject o)throws IOException {
        if(o instanceof PhysicsContinuum)return (PhysicsContinuum)o;
        if(o instanceof Region&&((Region)o).getPhysicsContinuum()!=null)return ((Region)o).getPhysicsContinuum();
        throw new IOException("工况需要已有PhysicsContinuum或已绑定物理连续体的Region");
    }
    private static double number(Map<?,?> a,String key,boolean positive)throws IOException {
        Object raw=a.get(key);
        if(!(raw instanceof Number))throw new IOException(key+"必须是数值");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||(positive?value<=0:value<0))throw new IOException(key+"必须是有限"+(positive?"正数":"非负数"));
        return value;
    }
    private static double[] vector(Map<?,?> a,String key)throws IOException {
        Object raw=a.get(key);if(!(raw instanceof List)||((List<?>)raw).size()!=3)throw new IOException(key+"必须是Lab坐标系三数值数组");
        double[] result=new double[3];
        for(int i=0;i<3;i++) {
            Object v=((List<?>)raw).get(i);
            if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue()))throw new IOException(key+"必须含三个有限数值");
            result[i]=((Number)v).doubleValue();
        }
        if(!Double.isFinite(norm(result))||norm(result)<=0)throw new IOException(key+"不能是零向量或溢出向量");
        return result;
    }
    private static double norm(double[] v) {return Math.hypot(Math.hypot(v[0],v[1]),v[2]);}
    private static double[] unit(double[] v) {double n=norm(v);return new double[]{v[0]/n,v[1]/n,v[2]/n};}
    private static double sutherland(double t) {return 1.716e-5*Math.pow(t/273.15,1.5)*(273.15+110.4)/(t+110.4);}
    /** Pure input validation, independently usable without a live STAR simulation. */
    static Map<String,Object> validateParameters(Map<?,?> a)throws IOException {
        Plan p=parameters(a);return expected(p);
    }
    private static Plan parameters(Map<?,?> a)throws IOException {
        if(!supports(String.valueOf(a.get("type"))))throw new IOException("不支持的工况动作");
        for(Object k:a.keySet())if(!(k instanceof String)||!KEYS.contains(k))throw new IOException("不支持的工况参数: "+k);
        Object ids=a.get("boundary_ids");
        if(!(ids instanceof List)||((List<?>)ids).isEmpty())throw new IOException("boundary_ids必须明确提供非空边界ID列表");
        Set<String> unique=new LinkedHashSet<>();
        for(Object id:(List<?>)ids)if(!(id instanceof String)||((String)id).isBlank()||!unique.add((String)id))throw new IOException("boundary_ids必须是唯一非空字符串ID");
        Plan p=new Plan();
        Double m=a.containsKey("mach")?number(a,"mach",true):null;
        Double h=a.containsKey("altitude_m")?number(a,"altitude_m",false):null;
        if(h!=null&&(m==null||h>11000))throw new IOException("altitude_m仅支持0..11000m且必须同时提供mach；更高高度请明确静温/静压");
        p.isa=h!=null;
        double isaT=p.isa?288.15-.0065*h:Double.NaN;
        double isaP=p.isa?101325*Math.pow(isaT/288.15,9.80665/(R*.0065)):Double.NaN;
        p.temperature=a.containsKey("static_temperature_K")?number(a,"static_temperature_K",true):isaT;
        p.absolutePressure=a.containsKey("static_pressure_Pa")?number(a,"static_pressure_Pa",true):isaP;
        if(!Double.isFinite(p.temperature)||!Double.isFinite(p.absolutePressure))throw new IOException("需要mach+altitude_m，或明确static_temperature_K和static_pressure_Pa");
        double acoustic=Math.sqrt(GAMMA*R*p.temperature);
        p.direction=a.containsKey("flow_direction")?unit(vector(a,"flow_direction")):new double[]{1,0,0};
        Double speed=a.containsKey("speed_m_s")?number(a,"speed_m_s",true):null;
        if(a.containsKey("velocity_m_s")) {
            double[] v=vector(a,"velocity_m_s");double n=norm(v);
            if(speed!=null&&!close(speed,n))throw new IOException("speed_m_s与velocity_m_s大小不一致");
            double[] d=unit(v);
            if(a.containsKey("flow_direction"))for(int i=0;i<3;i++)if(Math.abs(d[i]-p.direction[i])>1e-8)throw new IOException("flow_direction与velocity_m_s方向不一致");
            p.direction=d;speed=n;
        }
        if(speed==null&&m==null)throw new IOException("必须提供mach、speed_m_s或velocity_m_s以确定速度");
        p.speed=speed==null?m*acoustic:speed;
        p.mach=m==null?p.speed/acoustic:m;
        if(Math.abs(p.speed-p.mach*acoustic)/p.speed>.001)throw new IOException("mach与显式速度在当前静温下相差超过0.1%，请明确一致工况");
        p.velocity=new double[]{p.speed*p.direction[0],p.speed*p.direction[1],p.speed*p.direction[2]};
        if(a.containsKey("reference_pressure_Pa"))number(a,"reference_pressure_Pa",false);
        p.tvr=a.containsKey("turbulent_viscosity_ratio")?number(a,"turbulent_viscosity_ratio",true):null;
        p.intensity=a.containsKey("turbulence_intensity")?number(a,"turbulence_intensity",true):null;
        if(p.intensity!=null&&p.intensity>1)throw new IOException("turbulence_intensity必须是0..1范围内的正强度比例，例如0.01为1%");
        p.explicitMu=a.containsKey("dynamic_viscosity_Pa_s")?number(a,"dynamic_viscosity_Pa_s",true):null;
        p.viscosity=p.explicitMu==null?sutherland(p.temperature):p.explicitMu;
        for(double derived:new double[]{p.temperature,p.absolutePressure,acoustic,p.speed,p.mach,p.viscosity})
            if(!Double.isFinite(derived)||derived<=0)throw new IOException("工况推导得到非有限或非正数，输入数值超出支持的有限范围");
        for(double v:p.velocity)if(!Double.isFinite(v))throw new IOException("velocity_m_s推导溢出");
        if(p.isa)p.assumptions.add("ISA troposphere 0..11000 geopotential m, R=287.05287 J/(kg K), gamma=1.4; explicit static T/P override ISA separately.");
        p.assumptions.add("Mach/speed relation assumes ideal air, gamma=1.4 and R=287.05287 J/(kg K); direction and velocity are in the Lab frame.");
        return p;
    }
    private static boolean close(double a,double b) {return Math.abs(a-b)<=Math.max(1e-12,1e-8*Math.max(Math.abs(a),Math.abs(b)));}
    private static <T extends ClientServerObject> T require(ConditionManager manager,Class<T> cls,String where)throws IOException {
        T o=manager.has(cls);if(o==null)throw new IOException(where+"缺少可用原生属性 "+cls.getName()+"；请先补全物理模型/边界选项");return o;
    }
    private static void canSelect(star.common.EnumeratedOption<?> option,Enum<?> desired,String label)throws IOException {
        if(!option.getElements().contains(desired))throw new IOException(label+"原生选项不支持 "+desired);
    }
    private static Double molecularWeight(Material material) {
        MolecularWeightProperty property=material.getMaterialProperties().getMaterialProperty(MolecularWeightProperty.class);
        if(property==null||!(property.getMethod() instanceof ConstantMaterialPropertyMethod))return null;
        return ((ConstantMaterialPropertyMethod)property.getMethod()).getQuantity().getSIValue();
    }
    private static Plan plan(Map<?,?> a,ClientServerObject o)throws Exception {
        Plan p=parameters(a);p.continuum=continuum(o);PhysicsContinuum c=p.continuum;
        Set<String> models=new LinkedHashSet<>(c.getEnabledModels().getKeys());
        p.sa=models.contains("star.saturb.SaTurbModel");p.sst=models.contains("star.kwturb.SstKwTurbModel");
        if(!c.getRequiredModels().isEmpty()||!models.contains("star.flow.IdealGasModel")||!models.contains("star.coupledflow.CoupledEnergyModel")||(!p.sa&&!p.sst))throw new IOException("工况动作需要完整可压缩理想气体耦合能量与SA或SST模型链");
        if(c.getModelManager().getModel(star.flow.IdealGasModel.class).getIncompressible())throw new IOException("工况动作需要可压缩理想气体，当前Incompressible为true");
        if(c.isModelSelectionStaged())throw new IOException("当前模型选择处于staged状态");
        Material material=c.getModelManager().getModel(SingleComponentGasModel.class).getMaterial();
        Double mw=molecularWeight(material);String name=material.getPresentationName().toLowerCase(Locale.ROOT);
        boolean airName=name.contains("air")||name.contains("空气");
        // STAR19.04 uses kmol as its molecular-weight base: native Air reads 28.9664 kg/kmol.
        // Accepting 0.0289664 here would accidentally accept a 1000-fold wrong gas constant.
        boolean airMw=mw!=null&&mw>28.5&&mw<29.5;
        if(!airName||!airMw)throw new IOException("工况换算仅支持核验过的空气；材料="+material.getPresentationName()+"，molecular_weight_kg_kmol="+mw+"。其他气体需专用状态方程参数");
        ReferencePressure ref=require(c.getReferenceValues(),ReferencePressure.class,"参考值");
        p.referencePressure=a.containsKey("reference_pressure_Pa")?number(a,"reference_pressure_Pa",false):ref.getSIValue();
        if(!Double.isFinite(p.referencePressure)||p.referencePressure<0)throw new IOException("现有ReferencePressure不是有限非负SI值");
        if(!Double.isFinite(p.absolutePressure-p.referencePressure))throw new IOException("绝对静压转换表压后溢出");
        require(c.getInitialConditions(),InitialPressureProfile.class,"初始条件");
        require(c.getInitialConditions(),StaticTemperatureProfile.class,"初始条件");
        require(c.getInitialConditions(),VelocityProfile.class,"初始条件");
        if(p.sa) {
            if(p.intensity!=null)throw new IOException("SA工况使用turbulent_viscosity_ratio；turbulence_intensity仅用于SST");
            if(p.tvr==null)p.tvr=.2105;
            require(c.getInitialConditions(),TurbulentViscosityRatioProfile.class,"SA初始条件");
        } else if(p.tvr!=null||p.intensity!=null) {
            KwTurbSpecOption spec=c.getInitialConditions().has(KwTurbSpecOption.class);
            if(spec!=null)canSelect(spec,KwTurbSpecOption.Type.INTENSITY_VISCOSITY_RATIO,"SST初值");
            else {
                require(c.getInitialConditions(),TurbulentViscosityRatioProfile.class,"SST初始条件");
                require(c.getInitialConditions(),TurbulenceIntensityProfile.class,"SST初始条件");
            }
        }
        Map<String,Boundary> available=new LinkedHashMap<>();
        for(Region region:c.getRegions())for(Boundary b:region.getBoundaryManager().getBoundaries())available.put(b.getObjectKeyAsString(),b);
        for(Object id:(List<?>)a.get("boundary_ids")) {
            Boundary b=available.get(id);if(b==null)throw new IOException("boundary_ids包含不属于目标连续体的边界ID: "+id);
            if(b.isStaged())throw new IOException("边界处于staged状态: "+b.getPresentationName());
            boolean fs=b.getBoundaryType() instanceof FreeStreamBoundary, inlet=b.getBoundaryType() instanceof InletBoundary, outlet=b.getBoundaryType() instanceof PressureBoundary;
            if(!fs&&!inlet&&!outlet)throw new IOException("不支持的工况边界类型 "+b.getBoundaryType().getClass().getName()+"；不会自动改变边界类型");
            if(fs) {
                FreeStreamOption spec=require(b.getConditions(),FreeStreamOption.class,b.getPresentationName());
                canSelect(spec,FreeStreamOption.Type.MA_P_T,"自由流");
                if(spec.getSelectedElement()==FreeStreamOption.Type.MA_P_T) {
                    require(b.getValues(),StaticPressureProfile.class,b.getPresentationName());
                    require(b.getValues(),StaticTemperatureProfile.class,b.getPresentationName());
                    require(b.getValues(),MachNumberProfile.class,b.getPresentationName());
                }
                FlowDirectionOption dir=require(b.getConditions(),FlowDirectionOption.class,b.getPresentationName());
                canSelect(dir,FlowDirectionOption.Type.COMPONENTS,"自由流流向");
                if(dir.getSelectedElement()==FlowDirectionOption.Type.COMPONENTS)require(b.getValues(),FlowDirectionProfile.class,b.getPresentationName());
            } else {
                if(outlet)require(b.getValues(),StaticPressureProfile.class,b.getPresentationName());
                require(b.getValues(),StaticTemperatureProfile.class,b.getPresentationName());
            }
            if(inlet) {
                InletVelocityOption spec=require(b.getConditions(),InletVelocityOption.class,b.getPresentationName());
                canSelect(spec,InletVelocityOption.Type.COMPONENTS,"入口速度");
                if(spec.getSelectedElement()==InletVelocityOption.Type.COMPONENTS)require(b.getValues(),VelocityProfile.class,b.getPresentationName());
            }
            if(p.sa)canSelect(require(b.getConditions(),SaTurbSpecOption.class,b.getPresentationName()),SaTurbSpecOption.Type.TURBULENT_VISCOSITY_RATIO,"SA边界");
            else if(p.tvr!=null||p.intensity!=null)canSelect(require(b.getConditions(),KwTurbSpecOption.class,b.getPresentationName()),KwTurbSpecOption.Type.INTENSITY_VISCOSITY_RATIO,"SST边界");
            // Scalar profiles can become active only after the validated native option switches.
            p.boundaries.add(b);
        }
        p.viscosityProperty=material.getMaterialProperties().getMaterialProperty(DynamicViscosityProperty.class);
        if(p.viscosityProperty==null)throw new IOException("空气材料缺少DynamicViscosityProperty");
        return p;
    }
    static void validate(Map<?,?> a,ClientServerObject o)throws Exception {plan(a,o);}
    private static void flush(Simulation s) {s.getServer().getClientNotifyHandler().processCommandQueue(true);}
    private static Units units(Simulation s,String name)throws IOException {Units u=s.getUnitsManager().getUnits(name);if(u==null)throw new IOException("缺少单位 "+name);return u;}
    private static void set(Profile p,double value,Units unit)throws IOException {
        p.setMethod(ConstantScalarProfileMethod.class);
        p.getMethod(ConstantScalarProfileMethod.class).getQuantity().setValueAndUnits(value,unit);
    }
    private static void set(VectorProfile p,double[] value,Units unit,Simulation s)throws IOException {
        p.setCoordinateSystem(s.getCoordinateSystemManager().getLabCoordinateSystem());
        p.setMethod(ConstantVectorProfileMethod.class);
        p.getMethod(ConstantVectorProfileMethod.class).getQuantity().setComponentsAndUnits(value[0],value[1],value[2],unit);
    }
    private static double scalar(Profile p)throws IOException {
        if(!(p.getMethod() instanceof ConstantScalarProfileMethod))throw new IOException("读回属性不是常数标量: "+p.getClass().getName());
        return p.getMethod(ConstantScalarProfileMethod.class).getQuantity().getSIValue();
    }
    private static List<Double> vector(VectorProfile p)throws IOException {
        if(!(p.getMethod() instanceof ConstantVectorProfileMethod))throw new IOException("读回属性不是常数向量: "+p.getClass().getName());
        star.base.neo.DoubleVector v=p.getMethod(ConstantVectorProfileMethod.class).getQuantity().getSIVector();
        return List.of(v.get(0),v.get(1),v.get(2));
    }
    private static void verify(double expected,double actual,String label)throws IOException {if(!Double.isFinite(actual)||!close(expected,actual))throw new IOException(label+" SI读回失败: expected="+expected+", actual="+actual);}
    private static void verify(double[] expected,List<Double> actual,String label)throws IOException {for(int i=0;i<3;i++)verify(expected[i],actual.get(i),label+"["+i+"]");}
    private static Map<String,Object> expected(Plan p) {
        Map<String,Object> r=new LinkedHashMap<>();r.put("static_temperature_K",p.temperature);r.put("absolute_static_pressure_Pa",p.absolutePressure);
        r.put("mach",p.mach);r.put("speed_m_s",p.speed);r.put("velocity_m_s",List.of(p.velocity[0],p.velocity[1],p.velocity[2]));r.put("flow_direction",List.of(p.direction[0],p.direction[1],p.direction[2]));
        return r;
    }
    private static void configureTurbulence(ConditionManager values,ConditionManager conditions,Plan p,Simulation s,Units dimensionless)throws Exception {
        if(p.sa) {
            SaTurbSpecOption spec=conditions.has(SaTurbSpecOption.class);if(spec!=null)spec.setSelected(SaTurbSpecOption.Type.TURBULENT_VISCOSITY_RATIO);
        } else if(p.tvr!=null||p.intensity!=null) {
            KwTurbSpecOption spec=conditions.has(KwTurbSpecOption.class);if(spec!=null)spec.setSelected(KwTurbSpecOption.Type.INTENSITY_VISCOSITY_RATIO);
        }
        flush(s);
        if(p.tvr!=null)set(require(values,TurbulentViscosityRatioProfile.class,"湍流"),p.tvr,dimensionless);
        if(p.intensity!=null)set(require(values,TurbulenceIntensityProfile.class,"湍流"),p.intensity,dimensionless);
    }
    static Map<String,Object> execute(Simulation s,Map<?,?> a,ClientServerObject o)throws Exception {
        Plan p=plan(a,o);PhysicsContinuum c=p.continuum;
        Units pa=units(s,"Pa"),k=units(s,"K"),ms=units(s,"m/s"),one=units(s,"");
        Units mu=units(s,"Pa-s");
        Map<String,Object> result=new LinkedHashMap<>();result.put("before",describe(o));result.put("requested_SI",expected(p));
        double gauge=p.absolutePressure-p.referencePressure;
        ReferencePressure ref=c.getReferenceValues().get(ReferencePressure.class);ref.setValueAndUnits(p.referencePressure,pa);
        set(c.getInitialConditions().get(InitialPressureProfile.class),gauge,pa);
        set(c.getInitialConditions().get(StaticTemperatureProfile.class),p.temperature,k);
        set(c.getInitialConditions().get(VelocityProfile.class),p.velocity,ms,s);
        configureTurbulence(c.getInitialConditions(),c.getInitialConditions(),p,s,one);
        List<String> limitations=new ArrayList<>();
        if(p.explicitMu!=null) {
            p.viscosityProperty.setMethod(ConstantMaterialPropertyMethod.class);
            ((ConstantMaterialPropertyMethod)p.viscosityProperty.getMethod()).getQuantity().setValueAndUnits(p.viscosity,mu);
            result.put("viscosity_policy","explicit dynamic_viscosity_Pa_s applied as constant");
        } else if(p.viscosityProperty.getMethod() instanceof ConstantMaterialPropertyMethod) {
            ((ConstantMaterialPropertyMethod)p.viscosityProperty.getMethod()).getQuantity().setValueAndUnits(p.viscosity,mu);
            result.put("viscosity_policy","existing constant air viscosity updated using Sutherland at configured static temperature");
            p.assumptions.add("Constant air viscosity estimated with mu0=1.716e-5 Pa s, T0=273.15 K and S=110.4 K; material method remains constant.");
        } else {
            result.put("viscosity_policy","preserved existing "+p.viscosityProperty.getMethod().getClass().getName());
            limitations.add("Existing temperature-dependent/custom viscosity method was preserved; its effective viscosity was not evaluated by this action.");
        }
        for(Boundary b:p.boundaries) {
            if(b.getBoundaryType() instanceof FreeStreamBoundary) {
                b.getConditions().get(FreeStreamOption.class).setSelected(FreeStreamOption.Type.MA_P_T);
                b.getConditions().get(FlowDirectionOption.class).setSelected(FlowDirectionOption.Type.COMPONENTS);flush(s);
                set(b.getValues().get(MachNumberProfile.class),p.mach,one);
                set(b.getValues().get(FlowDirectionProfile.class),p.direction,one,s);
            } else if(b.getBoundaryType() instanceof InletBoundary) {
                b.getConditions().get(InletVelocityOption.class).setSelected(InletVelocityOption.Type.COMPONENTS);flush(s);
                set(b.getValues().get(VelocityProfile.class),p.velocity,ms,s);
            }
            // Velocity inlet has no active static-pressure input in coupled flow.
            if(!(b.getBoundaryType() instanceof InletBoundary))set(b.getValues().get(StaticPressureProfile.class),gauge,pa);
            else limitations.add("Velocity inlet "+b.getPresentationName()+" specifies velocity and temperature; static pressure is solved rather than prescribed on this boundary.");
            set(b.getValues().get(StaticTemperatureProfile.class),p.temperature,k);
            configureTurbulence(b.getValues(),b.getConditions(),p,s,one);
        }
        flush(s);
        verify(p.referencePressure,ref.getSIValue(),"ReferencePressure");
        verify(gauge,scalar(c.getInitialConditions().get(InitialPressureProfile.class)),"初始表压");
        verify(p.temperature,scalar(c.getInitialConditions().get(StaticTemperatureProfile.class)),"初始静温");
        verify(p.velocity,vector(c.getInitialConditions().get(VelocityProfile.class)),"初始速度");
        verifyTurbulence(c.getInitialConditions(),p,"初始");
        if(p.viscosityProperty.getMethod() instanceof ConstantMaterialPropertyMethod)verify(p.viscosity,((ConstantMaterialPropertyMethod)p.viscosityProperty.getMethod()).getQuantity().getSIValue(),"常黏度");
        for(Boundary b:p.boundaries) {
            if(!(b.getBoundaryType() instanceof InletBoundary))verify(gauge,scalar(b.getValues().get(StaticPressureProfile.class)),b.getPresentationName()+"表压");
            verify(p.temperature,scalar(b.getValues().get(StaticTemperatureProfile.class)),b.getPresentationName()+"静温");
            if(b.getBoundaryType() instanceof FreeStreamBoundary) {
                if(b.getConditions().get(FreeStreamOption.class).getSelectedElement()!=FreeStreamOption.Type.MA_P_T||b.getConditions().get(FlowDirectionOption.class).getSelectedElement()!=FlowDirectionOption.Type.COMPONENTS)throw new IOException("自由流输入选项读回失败");
                verify(p.mach,scalar(b.getValues().get(MachNumberProfile.class)),"边界Mach");
                verify(p.direction,vector(b.getValues().get(FlowDirectionProfile.class)),"边界流向");
            } else if(b.getBoundaryType() instanceof InletBoundary) {
                if(b.getConditions().get(InletVelocityOption.class).getSelectedElement()!=InletVelocityOption.Type.COMPONENTS)throw new IOException("入口速度选项读回失败");
                verify(p.velocity,vector(b.getValues().get(VelocityProfile.class)),"入口速度");
            }
            verifyTurbulence(b.getValues(),p,b.getPresentationName());
            if(p.sa&&b.getConditions().get(SaTurbSpecOption.class).getSelectedElement()!=SaTurbSpecOption.Type.TURBULENT_VISCOSITY_RATIO)throw new IOException("SA边界湍流指定方式读回失败");
            if(p.sst&&(p.tvr!=null||p.intensity!=null)&&b.getConditions().get(KwTurbSpecOption.class).getSelectedElement()!=KwTurbSpecOption.Type.INTENSITY_VISCOSITY_RATIO)throw new IOException("SST边界湍流指定方式读回失败");
        }
        result.put("actual",describe(o));result.put("selected_boundary_ids",a.get("boundary_ids"));result.put("gauge_pressure_Pa",gauge);
        result.put("assumptions",p.assumptions);result.put("limitations",limitations);
        result.put("validation","初始值和明确选中的边界工况已写入并按SI逐项读回；未划网格、未初始化解、未进行求解");
        result.put("conditions_verified",true);return result;
    }
    private static void verifyTurbulence(ConditionManager values,Plan p,String label)throws IOException {
        if(p.tvr!=null)verify(p.tvr,scalar(require(values,TurbulentViscosityRatioProfile.class,label)),label+" TVR");
        if(p.intensity!=null)verify(p.intensity,scalar(require(values,TurbulenceIntensityProfile.class,label)),label+" turbulence intensity");
    }
    private static void readScalar(ConditionManager values,Class<? extends Profile> type,String key,Map<String,Object> r) {
        try {
            Profile p=values.has(type);if(p!=null) {r.put(key+"_method",p.getMethod().getClass().getName());r.put(key,scalar(p));}
        } catch(Exception e) {r.put(key+"_read_error",String.valueOf(e.getMessage()));}
    }
    private static void readVector(ConditionManager values,Class<? extends VectorProfile> type,String key,Map<String,Object> r) {
        try {
            VectorProfile p=values.has(type);if(p!=null) {r.put(key+"_method",p.getMethod().getClass().getName());r.put(key+"_coordinate_system",p.getCoordinateSystem().getPresentationName());r.put(key,vector(p));}
        } catch(Exception e) {r.put(key+"_read_error",String.valueOf(e.getMessage()));}
    }
    static Map<String,Object> describe(ClientServerObject o) {
        Map<String,Object> r=new LinkedHashMap<>();
        try {
            PhysicsContinuum c=continuum(o);r.put("continuum_id",c.getObjectKeyAsString());r.put("continuum_name",c.getPresentationName());
            ReferencePressure ref=c.getReferenceValues().has(ReferencePressure.class);if(ref!=null)r.put("reference_pressure_Pa",ref.getSIValue());
            Map<String,Object> init=new LinkedHashMap<>();readScalar(c.getInitialConditions(),InitialPressureProfile.class,"gauge_pressure_Pa",init);
            readScalar(c.getInitialConditions(),StaticTemperatureProfile.class,"static_temperature_K",init);
            readScalar(c.getInitialConditions(),TurbulentViscosityRatioProfile.class,"turbulent_viscosity_ratio",init);
            readScalar(c.getInitialConditions(),TurbulenceIntensityProfile.class,"turbulence_intensity",init);
            readVector(c.getInitialConditions(),VelocityProfile.class,"velocity_m_s",init);r.put("initial",init);
            SingleComponentGasModel gas=c.getModelManager().getModel(SingleComponentGasModel.class);
            if(gas!=null) {
                Material mat=gas.getMaterial();Map<String,Object> material=new LinkedHashMap<>();material.put("name",mat.getPresentationName());
                material.put("molecular_weight_kg_kmol",molecularWeight(mat));material.put("molecular_weight_unit","kg/kmol (STAR19.04 native base)");MaterialProperty vis=mat.getMaterialProperties().getMaterialProperty(DynamicViscosityProperty.class);
                if(vis!=null) {
                    material.put("dynamic_viscosity_method",vis.getMethod().getClass().getName());
                    if(vis.getMethod() instanceof ConstantMaterialPropertyMethod)material.put("dynamic_viscosity_Pa_s",((ConstantMaterialPropertyMethod)vis.getMethod()).getQuantity().getSIValue());
                    if(vis.getMethod() instanceof SutherlandLaw) {SutherlandLaw law=(SutherlandLaw)vis.getMethod();material.put("sutherland_reference_viscosity_Pa_s",law.getReferenceValue().getSIValue());material.put("sutherland_reference_temperature_K",law.getReferenceTemperature().getSIValue());material.put("sutherland_constant_K",law.getSutherlandConstant().getSIValue());}
                }
                r.put("material",material);
            }
            List<Map<String,Object>> boundaries=new ArrayList<>();
            for(Region region:c.getRegions())for(Boundary b:region.getBoundaryManager().getBoundaries()) {
                Map<String,Object> v=new LinkedHashMap<>();v.put("id",b.getObjectKeyAsString());v.put("name",b.getPresentationName());v.put("type",b.getBoundaryType().getClass().getName());
                try {
                    readScalar(b.getValues(),StaticPressureProfile.class,"gauge_pressure_Pa",v);readScalar(b.getValues(),StaticTemperatureProfile.class,"static_temperature_K",v);
                    readScalar(b.getValues(),MachNumberProfile.class,"mach",v);readScalar(b.getValues(),TurbulentViscosityRatioProfile.class,"turbulent_viscosity_ratio",v);
                    readScalar(b.getValues(),TurbulenceIntensityProfile.class,"turbulence_intensity",v);readVector(b.getValues(),FlowDirectionProfile.class,"flow_direction",v);readVector(b.getValues(),VelocityProfile.class,"velocity_m_s",v);
                    FreeStreamOption fs=b.getConditions().has(FreeStreamOption.class);if(fs!=null)v.put("free_stream_specification",fs.getSelectedElement().name());
                    SaTurbSpecOption sa=b.getConditions().has(SaTurbSpecOption.class);if(sa!=null)v.put("turbulence_specification",sa.getSelectedElement().name());
                    KwTurbSpecOption kw=b.getConditions().has(KwTurbSpecOption.class);if(kw!=null)v.put("turbulence_specification",kw.getSelectedElement().name());
                } catch(Exception e) {v.put("read_error",String.valueOf(e.getMessage()));}
                boundaries.add(v);
            }
            r.put("boundaries",boundaries);
        } catch(Exception e) {r.put("flow_condition_read_error",String.valueOf(e.getMessage()));}
        return r;
    }
}
