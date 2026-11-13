package local.starccm.assistant;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit turbulence-family constraints, shared by planning and native execution. */
final class TurbulenceIntent {
    private TurbulenceIntent() {}

    private static final String FAMILY = "(?:@sa@|@sst@)";
    private static final String CHANGE_CN = "(?:切(?!\\s*勿)(?:换)?(?:回(?:到|为)?|为|到|成)?|改(?:回(?:到|为)?|成|为|到|用)|更换(?:回|成|为)|替换(?:回|成|为)|转换(?:回|成|为|到)|恢复(?:回)?(?:为|到|成)?|还原(?:为|到|成)?|返回(?:到|为)?|回到|转(?:回|成|为|到)|变(?:回|成|为)|换(?:回(?:到|为)?|成|为|用))";
    private static final String CHANGE_EN = "(?:switch|change|convert|move|migrate|transition|replace|return|revert|restore)";
    private static final String TARGET_QUALIFIERS_CN = "(?:\\s*(?:重新|再次|先|暂时|原来的?|之前的?|以前的?|先前的?|原先的?|最初的?|原有的?|原始的?|当初的?)\\s*)*";
    private static final String TARGET_QUALIFIERS_EN = "(?:(?:the|original|previous|former|initial|old|earlier|previously\\s+used|originally\\s+used)\\s+)*";
    private static final String WALL_TOPIC = "(?:壁面(?:处理|模型)|(?:near[- ]?)?wall\\s*(?:treatment|model))";
    private static final String WALL_TARGET = "(?:(?<![a-z0-9])(?:low|all)\\s*[- ]?y\\s*(?:\\+|⁺)|(?:低|全)(?:部)?\\s*y\\s*(?:\\+|⁺))";
    private static final Pattern FAMILY_NAME = Pattern.compile(
        "(?i)(?<![a-z0-9_])(?:sst|sa|spalart[\\s_-]*allmaras|spalart|shear[\\s_-]+stress[\\s_-]+transport)(?![a-z0-9_])");
    private static final Pattern FAMILY_TOKEN = Pattern.compile(FAMILY);
    private static final Pattern TOPIC = Pattern.compile(FAMILY + "|湍流|物理模型|turbulence|physics model|" + WALL_TOPIC);
    private static final Pattern WALL_TOPIC_PATTERN = Pattern.compile(WALL_TOPIC);
    private static final Pattern WALL_TARGET_PATTERN = Pattern.compile(WALL_TARGET);
    private static final Pattern WALL_CHANGE = Pattern.compile(
        "(?:" + CHANGE_CN + "|更改为|设置(?:为|成)?|设为|配置|采用|使用|选用|选择)(?:(?!从|由|" + WALL_TARGET + ").){0,24}?" + WALL_TARGET
        + "|\\b(?:use|adopt|select|choose|configure|restore)\\b(?:(?!" + WALL_TARGET + ").){0,30}?" + WALL_TARGET
        + "|\\b(?:set|" + CHANGE_EN + ")\\b.{0,55}?\\b(?:to|into|with|as)\\s+(?:the\\s+)?" + WALL_TARGET);
    private static final Pattern WALL_KEEP = Pattern.compile(
        "(?:保持|保留|沿用|维持|\\b(?:keep|preserve|retain|maintain)\\b)(?:(?!" + FAMILY + ").){0,20}(?:" + WALL_TOPIC + "|" + WALL_TARGET + ")|"
        + WALL_TOPIC + ".{0,25}(?:不变|原样|\\bunchanged\\b|\\bas\\s+is\\b)");
    private static final Pattern WALL_REVIEW = Pattern.compile("(?:只|仅)(?:需|要)?(?:检查|查看|确认|核对|审查)|\\b(?:check|inspect|review|verify)\\b");
    private static final Pattern FAMILY_KEEP_POSTFIX = Pattern.compile(
        "(" + FAMILY + ")(?:(?!" + FAMILY + ").){0,18}(?:保持不变|保持现状|不变|原样|\\bunchanged\\b|\\bas\\s+is\\b)");
    private static final Pattern QUESTION_OR_ADVICE = Pattern.compile(
        "[?？]|如何|怎么|怎样|为什么|是否|能否|可否|要不要|应不应该|应该|建议|推荐|比较|对比|区别|哪个|哪种|"
        + "如果|假如|如需|若要|\\b(?:should|would|could|can)\\s+(?:i|we)\\b|\\b(?:how|why|whether|recommend|recommendation|suggest|suggestion|compare|comparison|consider|if)\\b");
    private static final Pattern HISTORY = Pattern.compile(
        "^(?:\\s*(?:之前|以前|先前|原来|原本|历史|曾经|上次|过去|已(?:经)?|当前|目前|现有|好像|似乎|看起来|还没(?:有)?|尚未|没有|未曾|"
        + "previously\\b|formerly\\b|historically\\b|already\\b|currently\\b|the current\\b|the existing\\b))|"
        + "助手.{0,8}(?:建议|推荐)|\\b(?:assistant|documentation|manual)\\b.{0,20}\\b(?:suggested|recommended|uses?|used)\\b|"
        + "\\b(?:used to|had used|was using|were using)\\b|" + CHANGE_CN + "(?:了|过)");
    private static final Pattern NEGATION = Pattern.compile(
        "不要|不需要|不应|不许|不允许|不必|不得|禁止|无需|无须|不用|切勿|请勿|勿|别|不再|不(?:使用|采用|选择|切|改|换|变|设)|"
        + "(?:不|未)\\s*$|\\b(?:do\\s+not|don't|doesn't|must\\s+not|should\\s+not|shouldn't|cannot|can't|never|not)\\b");
    private static final Pattern PRESERVE_CURRENT = Pattern.compile(
        "(?:保持|保留|沿用|维持).{0,18}(?:当前|现有|原有|原来).{0,18}(?:湍流|物理模型|模型|" + FAMILY + ")|"
        + "(?:保持|保留|沿用|维持).{0,12}(?:湍流|物理模型|模型).{0,8}(?:不变|原样)|"
        + "(?:湍流|物理模型|模型).{0,12}(?:保持不变|不变|不改|不更改|不要改|别改)|"
        + "(?:不要|不需要|不应|不许|不允许|不必|不得|禁止|无需|无须|不用|切勿|请勿|勿|别|不再|不)(?:再|自动|重新|再次|先|暂时)*(?:更改|改变|修改|改动|改|换|" + CHANGE_CN + ").{0,25}(?:湍流|物理模型|模型|" + FAMILY + ")|"
        + "\\b(?:keep|preserve|retain|maintain|leave)\\b.{0,25}\\b(?:current|existing|unchanged|as\\s+is)\\b.{0,25}(?:turbulence|physics|model)|"
        + "\\b(?:keep|preserve|retain|maintain|leave)\\b.{0,25}(?:turbulence|physics|model).{0,15}\\b(?:unchanged|as\\s+is)\\b|"
        + "\\b(?:do\\s+not|don't|must\\s+not|never)\\s+(?:again\\s+|temporarily\\s+)?(?:alter|modify|" + CHANGE_EN + ")\\b.{0,35}(?:turbulence|physics|model|" + FAMILY + ")");
    private static final Pattern DIRECTION = Pattern.compile(
        "(?:从|由|将|把)\\s*(" + FAMILY + ")(?:(?!" + FAMILY + ").){0,32}?(?:" + CHANGE_CN + "|到|→|->)" + TARGET_QUALIFIERS_CN + "\\s*(" + FAMILY + ")|"
        + "\\b" + CHANGE_EN + "\\b(?:(?!" + FAMILY + ").){0,35}?(" + FAMILY + ")(?:(?!" + FAMILY + ").){0,25}?\\b(?:to|into|with|by)\\s+" + TARGET_QUALIFIERS_EN + "(" + FAMILY + ")");
    private static final Pattern TARGET = Pattern.compile(
        "(?:" + CHANGE_CN + "|更改为|采用|使用|选用|选择|配置|设置(?:为|成)?|设为|保持|保留|沿用|维持|用|以)"
        + "(?:(?!" + FAMILY + ").){0,20}?(" + FAMILY + ")|"
        + "\\b(?:use|adopt|select|choose|enable|activate|configure|restore|retain|keep|preserve|maintain)\\b(?:(?!" + FAMILY + ").){0,30}?(" + FAMILY + ")|"
        + "\\b(?:set|" + CHANGE_EN + ")\\b(?:(?!" + FAMILY + ").){0,45}?\\b(?:to|into|with|by|as)\\s+" + TARGET_QUALIFIERS_EN + "(" + FAMILY + ")|"
        + "(?:目标(?:湍流)?模型|湍流模型|物理模型|turbulence\\s+model)\\s*[:：=]\\s*(" + FAMILY + ")");
    private static final Pattern ALTERNATIVES = Pattern.compile(
        FAMILY + "\\s*(?:(?:湍流)?模型|model)?\\s*(?:或(?:者)?|还是|和|与|/|\\bor\\b|\\band\\b)\\s*" + FAMILY);
    private static final Pattern SWITCH_WORD = Pattern.compile(
        CHANGE_CN + "|改变|更改|\\b(?:alter|modify|" + CHANGE_EN + ")\\b");

    /** Returns only an explicit, unambiguous target; mentions never select a default. */
    static String requestedFamily(String goal) {
        return resolve(goal).target;
    }

    /** A generic no-change instruction requires the live family to be retained. */
    static boolean preserveCurrentFamily(String goal) {
        Result result = resolve(goal);
        return (result.wallMutation && !result.familyReplacement) || (result.target.isEmpty() && result.preserve);
    }

    /** A keep/retain constraint names a family without authorizing replacement. */
    static boolean replacementAuthorized(String goal) {
        Result result = resolve(goal);
        return (!result.target.isEmpty() || result.wallMutation) && result.mutation;
    }

    /** Only an authorized explicit wall-treatment change supplies a target. */
    static String requestedWallTreatment(String goal) {
        Result result = resolve(goal);
        return result.wallMutation && result.mutation ? result.wallTarget : "";
    }

    /** A named target excludes the other family; explicit negative selections also apply. */
    static boolean forbiddenFamily(String goal, String family) {
        String name = canonicalFamily(family);
        if (name.isEmpty()) return false;
        Result result = resolve(goal);
        return (!result.target.isEmpty() && !result.target.equals(name))
            || (name.equals("SA") ? result.forbidSa : result.forbidSst);
    }

    private static Result resolve(String goal) {
        if (goal == null || goal.trim().isEmpty()) return new Result();
        List<String> directives = CodexAssistant.goalDirectives(goal);
        for (int i = directives.size() - 1; i >= 0; i--) {
            Result result = directive(directives.get(i));
            // Bare confirmations and unrelated follow-up settings retain prior constraints.
            if (result.relevant) {
                if (result.wallMutation && result.target.isEmpty()) {
                    for (int earlier = i - 1; earlier >= 0; earlier--) {
                        Result prior = directive(directives.get(earlier));
                        if (!prior.relevant) continue;
                        if (!prior.target.isEmpty()) { result.target = prior.target; result.preserve = false; }
                        break;
                    }
                }
                return result;
            }
        }
        return new Result();
    }

    private static Result directive(String raw) {
        Result result = new Result();
        String text = tokenize(raw);
        result.relevant = TOPIC.matcher(text).find();
        if (!result.relevant) return result;
        for (String clause : text.split("(?<=[。；;!！?？\\n])|[，,]|\\b(?:but|then|now)\\b|但是|而是|然后|现在")) {
            if (clause.trim().isEmpty() || QUESTION_OR_ADVICE.matcher(clause).find()) continue;
            if (!FAMILY_TOKEN.matcher(clause).find()
                && clause.matches("(?s).*(?:其他|其余|别的|\\b(?:other|remaining)\\b).{0,12}(?:模型|models?).*")
                && PRESERVE_CURRENT.matcher(clause).find()) continue;
            if (HISTORY.matcher(clause).find()
                && !clause.matches("(?s).*(?:请|务必|必须|需要|应当|希望|想要|please|must|want to).*")
                && !PRESERVE_CURRENT.matcher(clause).find()) continue;
            boolean alternative = ALTERNATIVES.matcher(clause).find();
            if (alternative) { result.target = ""; result.mutation = false; result.familyReplacement = false; }
            if (PRESERVE_CURRENT.matcher(clause).find()) {
                result.preserve = true;
                result.target = "";
                result.mutation = false;
                result.familyReplacement = false;
            }
            Matcher postfixKeeps = FAMILY_KEEP_POSTFIX.matcher(clause);
            while (postfixKeeps.find()) {
                result.target = canonicalFamily(postfixKeeps.group(1));
                result.preserve = false;
                result.mutation = false;
                result.familyReplacement = false;
            }
            Matcher directions = DIRECTION.matcher(clause);
            while (directions.find()) {
                String target = firstNonNull(directions.group(2), directions.group(4));
                if (negated(clause, directions.start())) {
                    result.forbid(target);
                    result.preserve = true;
                    result.target = "";
                    result.mutation = false;
                    result.familyReplacement = false;
                } else if (!alternative) {
                    result.target = canonicalFamily(target);
                    result.preserve = false;
                    result.mutation = true;
                    result.familyReplacement = true;
                }
            }
            Matcher targets = TARGET.matcher(clause);
            while (targets.find()) {
                String target = firstNonNull(targets.group(1), targets.group(2), targets.group(3), targets.group(4));
                // A direction's source can follow an English verb ("change SA to SST").
                // The directional match above is authoritative for the whole operation.
                int familyStart = -1;
                for (int group = 1; group <= 4; group++) if (targets.group(group) != null) { familyStart = targets.start(group); break; }
                if (insideDirection(clause, familyStart, targets.end())) continue;
                if (negated(clause, targets.start())) {
                    result.forbid(target);
                    if (SWITCH_WORD.matcher(targets.group()).find()) {
                        result.preserve = true;
                        result.target = "";
                        result.mutation = false;
                        result.familyReplacement = false;
                    }
                } else if (!alternative) {
                    result.target = canonicalFamily(target);
                    result.preserve = false;
                    result.mutation = targets.group(4) == null
                        && !targets.group().matches("(?s)^(?:保持|保留|沿用|维持|retain\\b|keep\\b|preserve\\b|maintain\\b).*");
                    result.familyReplacement = result.mutation && !WALL_TOPIC_PATTERN.matcher(clause).find();
                }
            }
            // A coordinated prohibited list applies to both listed families.
            if (alternative && NEGATION.matcher(clause).find()) {
                Matcher families = FAMILY_TOKEN.matcher(clause);
                while (families.find()) result.forbid(families.group());
            }
            if (WALL_TOPIC_PATTERN.matcher(clause).find()) {
                if (WALL_KEEP.matcher(clause).find() || WALL_REVIEW.matcher(clause).find()) {
                    result.wallMutation = false;
                    result.wallTarget = "";
                    result.mutation = false;
                    if (result.target.isEmpty()) result.preserve = true;
                    continue;
                }
                Matcher wallChanges = WALL_CHANGE.matcher(clause);
                while (wallChanges.find()) {
                    if (negated(clause, wallChanges.start())) {
                        result.wallMutation = false;
                        result.wallTarget = "";
                        result.mutation = false;
                        if (result.target.isEmpty()) result.preserve = true;
                    } else {
                        String family = onlyFamily(clause);
                        if (family.isEmpty() && FAMILY_TOKEN.matcher(clause).find()) continue;
                        if (result.target.isEmpty()) result.target = family;
                        result.wallMutation = true;
                        Matcher treatmentNames = WALL_TARGET_PATTERN.matcher(wallChanges.group());
                        while (treatmentNames.find()) {
                            String name = treatmentNames.group();
                            result.wallTarget = name.startsWith("low") || name.startsWith("低") ? "Low" : "All";
                        }
                        result.mutation = true;
                        result.preserve = result.target.isEmpty();
                    }
                }
            }
        }
        if ((result.target.equals("SA") && result.forbidSa)
            || (result.target.equals("SST") && result.forbidSst)) { result.target = ""; result.mutation = false; result.wallMutation = false; result.familyReplacement = false; }
        return result;
    }

    private static boolean insideDirection(String text, int start, int end) {
        Matcher directions = DIRECTION.matcher(text);
        while (directions.find()) if (start >= directions.start() && end <= directions.end()) return true;
        return false;
    }

    private static boolean negated(String clause, int actionStart) {
        String prefix = clause.substring(0, actionStart);
        // Negation does not cross a new affirmative conjunction/correction.
        String[] pieces = prefix.split("\\b(?:and|instead)\\b|但|而|不过", -1);
        String scope = pieces[pieces.length - 1];
        // A completed selection is a separate clause even without punctuation:
        // "do not use SA, use SST" / "不要用SA采用SST".
        Matcher completed = TARGET.matcher(scope);
        int completedEnd = -1;
        while (completed.find()) completedEnd = completed.end();
        if (completedEnd >= 0) scope = scope.substring(completedEnd);
        return NEGATION.matcher(scope).find();
    }

    private static String tokenize(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT).replace('’', '\'');
        Matcher matcher = FAMILY_NAME.matcher(normalized);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String family = matcher.group().startsWith("sst") || matcher.group().startsWith("shear") ? "@sst@" : "@sa@";
            matcher.appendReplacement(out, family);
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String canonicalFamily(String family) {
        if (family == null) return "";
        String normalized = family.toLowerCase(Locale.ROOT).trim();
        if (normalized.equals("@sa@") || normalized.equals("sa") || normalized.startsWith("spalart")) return "SA";
        if (normalized.equals("@sst@") || normalized.equals("sst") || normalized.startsWith("shear stress transport")) return "SST";
        return "";
    }

    private static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        return "";
    }

    private static String onlyFamily(String clause) {
        String family = "";
        Matcher names = FAMILY_TOKEN.matcher(clause);
        while (names.find()) {
            String next = canonicalFamily(names.group());
            if (!family.isEmpty() && !family.equals(next)) return "";
            family = next;
        }
        return family;
    }

    private static final class Result {
        boolean relevant;
        boolean preserve;
        boolean forbidSa;
        boolean forbidSst;
        boolean mutation;
        boolean wallMutation;
        boolean familyReplacement;
        String wallTarget = "";
        String target = "";
        void forbid(String family) {
            String name = canonicalFamily(family);
            if (name.equals("SA")) forbidSa = true;
            if (name.equals("SST")) forbidSst = true;
        }
    }
}
