package local.starccm.assistant;
import java.util.Locale;
/** Shared interpretation guards; absence of an explicit setting is never a license to erase one. */
final class EngineeringIntent {
 static int meshDisposition(String goal){
  if(goal==null)return 0;java.util.List<String> directives=CodexAssistant.goalDirectives(goal);
  for(int i=directives.size()-1;i>=0;i--){boolean requested=false,forbidden=false;
   for(String part:directives.get(i).toLowerCase(Locale.ROOT).split("[，,。；;！？!?\\n]")){
    if(part.matches("(?s).*(?:网格|mesh).{0,12}(?:不要|不需要|无需|不用|别|不允许|不|do not|don't|must not|never).{0,10}(?:生成|执行|绘制|划分|重划|划|运行|generated|executed|run|meshed).*")||part.matches("(?s).*(?:别动|不要动|禁止操作|不允许操作).{0,8}(?:网格|mesh).*")||part.matches("(?s).*(?:不要|不需要|无需|不用|别|不|do not|don't|never)\\s*(?:再|重新|自动)?\\s*(?:执行|生成|绘制|重新生成|重新划分|划分|重划|划|运行|run|generate|remesh).{0,20}(?:网格|mesh).*")||part.matches("(?s).*(?:不要|不需要|无需|不用|别|do not|don't|never)\\s*(?:网格|mesh).{0,10}(?:生成|执行|划分|绘制)?\\s*"))forbidden=true;
    else if(!part.matches("(?s).*(?:仅修改|只修改|only modify).{0,20}(?:网格|mesh).*")&&part.matches("(?s).*(?:(?:执行|生成|绘制|重新划分|重新生成|划分|重划|运行|下发|run|generate|remesh).{0,35}(?:网格|mesh)|网格.{0,15}(?:执行|生成|划分|绘制)).*"))requested=true;
   }
   if(forbidden)return -1;if(requested)return 1;
  }return 0;
 }
 static boolean meshForbidden(String goal){return meshDisposition(goal)<0;}
 static boolean unsupportedNativePhysics(String goal){return goal.matches("(?is).*(层流|非定常|瞬态|不可压缩|k.?epsilon|k.?omega|k.?ε|k.?ω|雷诺应力|(?:^|[^a-z])(?:LES|DES|RSM)(?:[^a-z]|$)|理想液体|水流|非空气|氦气|氮气|氢气|helium|nitrogen|hydrogen).*" );}
 static boolean ambiguousPhysicsChoice(String goal){java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?<![a-z0-9])(?:sa|sst)(?![a-z0-9])|spalart(?:.allmaras)?").matcher(goal);boolean sa=false,sst=false;while(m.find()){if(m.group().equalsIgnoreCase("sst"))sst=true;else sa=true;}return sa&&sst;}
 static boolean fullPreparationRequested(String scope){
  if(scope==null)return false;
  if(scope.matches("(?is).*(只|仅|单独|只需|仅需).{0,15}(检查|查看|诊断|模型|物理|尺寸|参数|网格配置).*"))return false;
  if(scope.matches("(?is).*(完整补齐|完整.{0,4}(?:SA|SST|湍流|物理).{0,8}模型|其他.{0,6}(?:不变|保持|保留)).*"))return false;
  return scope.matches("(?is).*(气动计算|CFD|完整外流场|完整气动|准备.{0,12}(求解|迭代)|可以.{0,8}开始.{0,8}(求解|迭代)|(?:完整|全部|全流程).{0,8}(?:配置|修复|重建|重做).{0,12}(?:仿真|案例|流场|机翼|半模)|(?:仿真|案例|外流场).{0,10}(?:完整|全部).{0,5}配置).*" );
 }
 /** Native recipes accept a deliberately narrow fully parsed subset. Other settings go to the model's typed plan. */
 static String withoutParsedScalars(String goal){
  String n=RecommendationContext.NUM;
  String text=goal.replaceAll("(?i)(?:参考长度|参考弦长|翼根弦长|reference_length_m)\\s*(?:为|是|=|:|：)?\\s*"+n+"\\s*(?:mm|cm|m|毫米|米)?"," ");
  text=text.replaceAll("(?i)(?<![.0-9])"+n+"\\s*(?:马赫|mach|Ma\\b)|(?:mach|Ma\\b|马赫数)\\s*(?:为|是|=|:|：)?\\s*"+n," ");
  text=text.replaceAll("(?i)(?:高度|海拔|altitude)\\s*(?:为|是|=|:|：)?\\s*"+n+"\\s*(?:km|千米|m\\b|米)"," ");
  text=text.replaceAll("(?i)y\\s*(?:\\+|⁺)\\s*(?:为|是|=|:|：)?\\s*"+n," ");
  String sym=RecommendationContext.symmetryAxis(goal);if(!sym.isEmpty())text=text.replaceAll("(?i)(?<![a-z0-9_])"+sym+"\\s*=\\s*[+-]?"+n+"\\s*(?:mm|cm|m|毫米|米)?"," ");
  return text.replaceAll("(?i)3[ -]?D(?:[ -]?CAD)?"," ");
 }
 static boolean hasUnparsedRecipeSettings(String goal){
  java.util.regex.Matcher keys=java.util.regex.Pattern.compile("(?i)\\b[a-z]+_[a-z0-9_]+\\b").matcher(goal);while(keys.find())if(!keys.group().equalsIgnoreCase("reference_length_m"))return true;
  if(java.util.regex.Pattern.compile(RecommendationContext.NUM).matcher(withoutParsedScalars(goal)).find())return true;
  return goal.matches("(?is).*(迎角|攻角|alpha|angle.of.attack|保持|保留|不改|不要改|不覆盖|先只|先仅|之后|展向.{0,8}(尺寸|长度|范围|米)|外域.{0,8}(尺寸|长度|倍|范围)|净距|上游.{0,8}[0-9]|下游.{0,8}[0-9]|domain_|base_size|wing_target|wing_min|maximum_cell|nearfield|farfield_target|prism_|first_layer|growth_ratio|基准尺寸|最小尺寸|表面尺寸|最大尺寸|增长率|首层|棱柱|多面体|切割体|曲率|尾流.{0,8}[0-9]|翼梢.{0,8}[0-9]|近翼.{0,8}[0-9]|公差|离散|布尔.{0,8}(模式|方法)|粗糙|滑移|运动壁面|转捩|湍黏|湍粘|湍流强度|viscosity|intensity|temperature|static_pressure|reference_pressure|压力.{0,8}[0-9]|温度|静温|静压|表压|参考压力|密度|黏度|粘度|速度矢量|速度.{0,8}(?:[0-9.]+\\s*m/s|米每秒)|非空气|混合气|液体|水流|氮气|氢气|氦气|helium|nitrogen|hydrogen|介质|流体为|fluid is|gas flow|蒸汽|层流|非定常|瞬态|不可压缩|分离求解|能量|CFL|迭代.{0,8}[0-9]|[0-9].{0,4}(?:步|次迭代)|停止条件|参考面积|参考点|(?:创建|输出|添加|配置).{0,8}(?:报告|监视|场函数)|入口.{0,8}(?:只|仅)|自由侧.{0,8}(?:对称|壁面|入口|出口)).*");
 }
}
