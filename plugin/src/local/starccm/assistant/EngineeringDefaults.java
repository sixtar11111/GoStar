package local.starccm.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dimensional engineering starting estimates, independent of STAR state or write APIs.
 * This class never changes geometry, simulation settings, or user-specified values.
 * The caller must resolve a justified reference length and explicit user priorities.
 */
public final class EngineeringDefaults {
    private EngineeringDefaults() { }

    /** SI inputs. Null means unknown; a missing quantity is never silently fabricated.
     * Explicit U/rho/mu override corresponding ISA estimates, when available.
     * ISA is limited here to the troposphere, with altitude interpreted as geopotential.
     */
    public static Map<String,Object> recommend(double L, Double mach, Double altitudeM,
            Double speed, Double rho, Double mu, Double targetYplus) {
        positive("reference_length_m", L);
        optionalNonnegative("mach", mach);
        optionalNonnegative("altitude_m", altitudeM);
        optionalPositive("speed_m_s", speed);
        optionalPositive("density_kg_m3", rho);
        optionalPositive("dynamic_viscosity_Pa_s", mu);
        optionalPositive("target_yplus", targetYplus);

        Map<String,Object> out = new LinkedHashMap<>();
        List<String> assumptions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        out.put("source", "engineering_initial_estimate; not an accuracy or mesh-quality guarantee");
        out.put("reference_length_m", L);
        out.put("precedence", "Explicit user settings take priority. Apply only authorized missing settings; do not overwrite an existing verified setup or repeatedly reapply defaults.");
        out.put("domain_reference_required_for_finite_body", "Scalar L below is a mesh/chord reference only. Before building an external finite-wing domain, measure max(body length, full span) independently and use official 8–10 times that distance; user-specified clearances override. RecommendationContext supplies measured domain_reference.");
        out.put("domain_clearance_status", "not_estimated_from_mesh_length_alone; measure finite-body length and full span first");
        assumptions.add("Domain values are clearances from the body, not total box lengths. Use the confirmed flow axis, body bounds, and symmetry plane; do not move the symmetry plane or infer it from bounds.");
        assumptions.add("Reference length must be identified and disclosed (for example measured projected chord or user-specified characteristic length), not silently equated to span or mean aerodynamic chord.");
        out.put("mesh", numbers("base_size_m", L/4, "wing_target_m", L/100,
                "wing_min_m", L/1000, "farfield_target_m", L,
                "maximum_cell_m", 2*L, "surface_growth", 1.15,
                "volume_growth", 1.10, "curvature_points_per_circle", 30));
        out.put("cad_discretization", numbers("absolute_chord_tolerance_m", L*1e-5,
                "absolute_surface_tolerance_m", L*1e-5, "angle_degrees", 5));
        out.put("cad_discretization_policy", "Inspect source CAD, Boolean output, root/symmetry intersection, and existing tolerances first. These are tessellation starting estimates, not permission to alter CAD, repair topology, or replace an explicit tolerance. Surface/volume refinement does not repair upstream geometry errors.");
        out.put("local_refinement", numbers("nearfield_size_m", L/20,
                "tip_size_m", L/40, "wake_size_m", L/12));
        out.put("local_refinement_policy", "Sizes only; no boxes are created here. Identify actual wing, tip, wake, and body extents before proposing their selections and placement. Do not assume all bodies are wings.");
        out.put("boundary_condition_starting_point",Map.of("priority","Explicit user boundary choices always override this suggestion; inspect real face coordinates and supported continuum types before writing.","body","viscous stationary solid surface: no-slip wall; prisms only on intended viscous surfaces", "symmetry","Only the physically confirmed half-model plane; preserve its coordinate", "open_field",mach!=null&&mach>=.3?"Compressible external flow: suggest supported free-stream farfield on open faces if the user has not specified velocity-inlet/pressure-outlet; explicitly requested pair is retained and side faces assigned consistently.":"Subsonic external-flow starting layout: upstream velocity inlet, downstream pressure outlet; side faces use compatible open farfield/inflow conditions. Do not leave outer faces as no-slip walls or assume side symmetry when flow has a normal component.","pressure","ISA pressure is absolute. Read STAR reference pressure and convert gauge pressure = absolute - reference; reconcile material/initial/boundary state before applying."));

        Map<String,Object> flow = new LinkedHashMap<>();
        Map<String,Object> valueSources = new LinkedHashMap<>();
        if (mach != null) flow.put("mach", mach);
        if (altitudeM != null) flow.put("altitude_m", altitudeM);
        Double usedSpeed = speed, usedRho = rho, usedMu = mu;
        boolean isa = mach != null && altitudeM != null && altitudeM <= 11000;
        if (isa) {
            double temperature = 288.15 - 0.0065 * altitudeM;
            double pressure = 101325 * Math.pow(temperature/288.15,
                    9.80665/(287.05287*0.0065));
            double isaRho = pressure/(287.05287*temperature);
            double isaMu = 1.716e-5 * Math.pow(temperature/273.15, 1.5)
                    * (273.15+110.4)/(temperature+110.4);
            double isaSpeed = mach*Math.sqrt(1.4*287.05287*temperature);
            flow.put("temperature_K", finite("temperature_K", temperature));
            flow.put("absolute_static_pressure_Pa", finite("pressure_Pa", pressure));
            valueSources.put("temperature_K", "ISA troposphere assumption");
            valueSources.put("absolute_static_pressure_Pa", "ISA troposphere assumption");
            if (usedSpeed == null && isaSpeed > 0) usedSpeed = isaSpeed;
            if (usedRho == null) usedRho = isaRho;
            if (usedMu == null) usedMu = isaMu;
            assumptions.add("ISA troposphere: T=288.15-0.0065h K; p=101325(T/288.15)^[g/(R*0.0065)]; rho=p/(RT), R=287.05287 J/(kg K), gamma=1.4. Altitude is geopotential metres in 0..11000. Sutherland: mu=1.716e-5(T/273.15)^1.5(273.15+110.4)/(T+110.4) Pa s. U=M sqrt(gamma RT).");
            if (speed != null && isaSpeed > 0 && Math.abs(speed-isaSpeed)/isaSpeed > .02)
                warnings.add("Explicit speed differs from the Mach/ISA-derived speed by more than 2%; explicit speed is retained. Reconcile intended Mach and temperature before writing a full operating condition.");
            if (rho != null && Math.abs(rho-isaRho)/isaRho > .02)
                warnings.add("Explicit density differs by more than 2% from the density implied by ISA p/T and the air gas law p=rho*R*T. Explicit density is retained for the Re and near-wall estimates; ISA pressure/temperature remain separate assumptions. Do not blindly apply these inconsistent rho, p, and T values as one ideal-gas operating state: reconcile the intended material and thermodynamic conditions first.");
        } else if (mach != null && altitudeM != null) {
            warnings.add("This implementation does not extrapolate ISA above 11000 m. Provide temperature/pressure and fluid properties or explicit U/rho/mu.");
        }
        if (usedSpeed != null) {
            flow.put("speed_m_s", finite("speed_m_s", usedSpeed));
            valueSources.put("speed_m_s", speed != null ? "explicit input" : "Mach and ISA estimate");
        } else missing.add("positive speed_m_s, or Mach plus supported ISA altitude");
        if (usedRho != null) {
            flow.put("density_kg_m3", finite("density_kg_m3", usedRho));
            valueSources.put("density_kg_m3", rho != null ? "explicit input" : "ISA estimate");
        } else missing.add("density_kg_m3, or Mach plus supported ISA altitude");
        if (usedMu != null) {
            flow.put("dynamic_viscosity_Pa_s", finite("dynamic_viscosity_Pa_s", usedMu));
            valueSources.put("dynamic_viscosity_Pa_s", mu != null ? "explicit input" : "Sutherland and ISA estimate");
        } else missing.add("dynamic_viscosity_Pa_s, or Mach plus supported ISA altitude");
        flow.put("value_sources", valueSources);
        if (!flow.isEmpty()) out.put("flow", flow);

        if (targetYplus == null) missing.add("target_yplus (must match the selected wall treatment)");
        if (usedSpeed != null && usedRho != null && usedMu != null && targetYplus != null) {
            double reynolds = positive("Re_L", usedRho*usedSpeed*L/usedMu);
            double reFifth = Math.pow(reynolds, .2);
            double cf = positive("Cf_estimate", .0592/reFifth);
            double frictionVelocity = positive("friction_velocity_m_s", usedSpeed*Math.sqrt(cf/2));
            double center = positive("first_cell_center_m", targetYplus*usedMu/(usedRho*frictionVelocity));
            double full = positive("first_layer_full_thickness_m", 2*center);
            double delta = positive("delta99_estimate_m", .37*L/reFifth);
            double growth = 1.2;
            int layers = layerCount(full, growth, delta);
            double total = totalThickness(full, growth, layers);
            double last = positive("last_layer_thickness_m", full*Math.pow(growth,layers-1));
            if (last > L/20) {
                growth = 1.18;
                layers = layerCount(full, growth, delta);
                total = totalThickness(full, growth, layers);
                last = positive("last_layer_thickness_m", full*Math.pow(growth,layers-1));
            }
            Map<String,Object> wall = numbers("target_yplus", targetYplus,
                    "Re_L", reynolds, "Cf_estimate", cf,
                    "friction_velocity_m_s", frictionVelocity,
                    "wall_to_first_cell_center_m", center,
                    "first_layer_full_thickness_m", full,
                    "delta99_estimate_m", delta,
                    "growth_ratio", growth,
                    "total_thickness_m", total,
                    "last_layer_thickness_m", last,
                    "last_to_nearfield_size_ratio", last/(L/20));
            wall.put("layers", layers);
            wall.put("estimated_delta_covered", total >= delta*(1-1e-12));
            wall.put("core_transition_check", last <= L/20
                    ? "last layer no larger than proposed nearfield core size; inspect actual transition after meshing"
                    : "review required: first/last layer is too thick for proposed nearfield core; do not claim this is an acceptable near-wall mesh");
            wall.put("formula", "Re=rho*U*L/mu; Cf=0.0592/Re^0.2; u_tau=U*sqrt(Cf/2); centre_distance=yplus*mu/(rho*u_tau); full_first_layer=2*centre_distance; delta99=0.37L/Re^0.2; total=h1*(r^n-1)/(r-1). n is derived to cover delta99, not chosen for visual appearance.");
            out.put("near_wall", wall);
            if (reynolds < 5e5)
                warnings.add("Re_L<500000: a fully turbulent flat-plate estimate may be unsuitable for laminar/transitional flow; this is not transition prediction.");
            if (mach != null && mach > .3)
                warnings.add("The flat-plate Cf/delta estimate has no compressibility, pressure-gradient, separation, sweep, or curvature correction; use it only to initialise a mesh and verify actual wall y+ after solving.");
            if (full >= delta)
                warnings.add("Requested first-layer full thickness is not smaller than estimated boundary-layer thickness; the resulting one-layer estimate is unsuitable as a resolved boundary layer. Review wall treatment and y+ target.");
            if (last > L/20)
                warnings.add("Proposed near-wall stack cannot match the proposed nearfield core at r>=1.18; inspect and explicitly revise the sizing choice before applying this estimate.");
        }
        assumptions.add("Near-wall correlation is an approximately incompressible, zero-pressure-gradient estimate for a smooth, fully turbulent, attached flat plate at representative distance L, not a hard Reynolds-number applicability guarantee. High Mach, strong pressure gradients, sweep, separation, and transitional flow can invalidate the estimate. The centroid distance is approximately half the complete first-cell thickness; actual mesh geometry and solved wall shear change y+. Actual y+ must be checked after solving.");
        assumptions.add("Mesh sizes and growth are initial engineering choices. They do not establish geometric fidelity, grid independence, convergence, actual y+, or valid boundary conditions. Apply wall prisms only to intended viscous body surfaces and disable them on symmetry/open boundaries.");
        out.put("missing_for_near_wall", missing);
        out.put("assumptions", assumptions);
        out.put("warnings", warnings);
        return out;
    }

    private static int layerCount(double first, double growth, double thickness) {
        double n = Math.ceil(Math.log1p((growth-1)*thickness/first)/Math.log(growth));
        if (!Double.isFinite(n) || n > 1000)
            throw new IllegalArgumentException("Derived prism layer count exceeds the supported finite range; review dimensional inputs.");
        return Math.max(1,(int)n);
    }
    private static double totalThickness(double first, double growth, int layers) {
        return positive("total_thickness_m", first*Math.expm1(layers*Math.log(growth))/(growth-1));
    }
    private static Map<String,Object> numbers(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) {
            String key=(String)pairs[i]; double n=((Number)pairs[i+1]).doubleValue();
            result.put(key, positive(key,n));
        }
        return result;
    }
    private static double finite(String label,double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(label+" must be finite");
        return value;
    }
    private static double positive(String label,double value) {
        finite(label,value);
        if (value<=0) throw new IllegalArgumentException(label+" must be positive");
        return value;
    }
    private static void optionalPositive(String label,Double value) {
        if (value!=null) positive(label,value);
    }
    private static void optionalNonnegative(String label,Double value) {
        if(value!=null && (finite(label,value)<0))
            throw new IllegalArgumentException(label+" must be nonnegative");
    }
}
