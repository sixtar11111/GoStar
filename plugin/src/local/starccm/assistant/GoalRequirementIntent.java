package local.starccm.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Data-only text for positive readiness predicates, never an authorization or
 * executable plan. Explicit replacement sources are historical state, not goals.
 * Only known boundary, domain and time-model concepts participate; unclear prose
 * is retained rather than guessed. Keep the original goal for mutation context. */
final class GoalRequirementIntent {
    private GoalRequirementIntent() { }

    private static final String BOUNDARY_DOMAIN = "(?:速度入口|压力出口|自由来流|自由流|对称平面|对称边界|对称面|壁面|半模|全模|(?<![a-z0-9_])(?:velocity[ _-]*inlet|pressure[ _-]*outlet|free[ _-]*stream|symmetry(?:[ _-]+(?:plane|boundar(?:y|ies)))?|wall(?:[ _-]+boundary)?|half[ _-]*model|full[ _-]*model)(?![a-z0-9_]))";
    private static final String TERM = "(?:速度入口|压力出口|自由来流|自由流|对称平面|对称边界|对称面|壁面|半模|全模|非定常|非稳态|瞬态|定常|稳态|"
        + "(?<![a-z0-9_])(?:velocity[ _-]*inlet|pressure[ _-]*outlet|free[ _-]*stream|symmetry(?:[ _-]+(?:plane|boundar(?:y|ies)))?|wall(?:[ _-]+boundary)?|half[ _-]*model|full[ _-]*model|unsteady|transient|steady(?:[ _-]*state)?)(?![a-z0-9_]))";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern KNOWN = Pattern.compile(TERM, FLAGS);
    private static final Pattern KNOWN_BOUNDARY_DOMAIN = Pattern.compile(BOUNDARY_DOMAIN, FLAGS);
    private static final String OPEN_BOUNDARY = "(?:速度入口|压力出口|自由来流|自由流|(?<![a-z0-9_])(?:velocity[ _-]*inlet|pressure[ _-]*outlet|free[ _-]*stream)(?![a-z0-9_]))";
    private static final Pattern OPEN_BOUNDARY_TERM = Pattern.compile(OPEN_BOUNDARY, FLAGS);
    private static final String GOAL_UPDATE = "\n本轮用户补充或变更（优先于旧目标；不重复已完成步骤）：";
    private static final Pattern ALL_OPEN_REPLACEMENT = Pattern.compile(
        "(?:(?:把|将)?(?:所有|全部)(?:的)?(?:开放|开口)边界(?:都|统一|全部)?(?:改成|改为|切换为|切换到|替换为|设置为|设为)\\s*(" + OPEN_BOUNDARY + ")|"
        + "\\b(?:change|replace|switch|convert|set)\\s+all\\s+(?:the\\s+)?open\\s+boundaries\\s+(?:to|with|into|as)\\s+(?:the\\s+)?(" + OPEN_BOUNDARY + "))", FLAGS);
    private static final Pattern LEADING_TARGET = Pattern.compile("\\s*(?:(?:现有|当前|新的|原有|一个|the|an?|new|existing|current)\\s*)*(" + TERM + ")", FLAGS);
    private static final String CHINESE_OPERATION = "改成|改为|切换为|切换到|切换至|替换为|替换成|变为|变成|设置为|设为";
    private static final Pattern CHINESE_REPLACEMENT = Pattern.compile("(?:(?:把|将|从)((?:(?!并|然后|同时|以及)[^，,;；。！？!?\\n]){1,160}?)|(" + TERM + "(?:(?!并|然后|同时|以及)[^，,;；。！？!?\\n]){0,100}?))(" + CHINESE_OPERATION + ")", FLAGS);
    private static final Pattern ENGLISH_REPLACEMENT = Pattern.compile("\\b(change|replace|switch|convert)\\s+(?:from\\s+)?([^,;.!?\\n]{1,160}?)\\s+(to|with|into)\\s+", FLAGS);
    private static final Pattern NEGATIVE_BEFORE = Pattern.compile("(?s).*(?:不要|不需要|无需|不用|禁止|不得|不能|不许|不允许|取消|撤销|别|不)(?:再|自动|擅自)?\\s*$|(?is).*(?:do\\s+not|don't|must\\s+not|never|cancel|not)\\s*$");

    static String requirementText(String rawGoal) {
        if (rawGoal == null || rawGoal.isEmpty()) return rawGoal == null ? "" : rawGoal;
        String text = stripCancelledAllOpenChanges(overrideEarlierOpenBoundaryRequirements(rawGoal));
        text = stripReplacementSources(text, CHINESE_REPLACEMENT, false);
        text = stripReplacementSources(text, ENGLISH_REPLACEMENT, true);
        String negative = "(?:(?:不要|不需要|无需|不用|禁止|不得|不能|不许|不允许|取消|别|不)(?:再|额外|自动|擅自)?\\s*(?:对|将)?\\s*(?:使用|添加|创建|需要|设置|修改|更改|改变|调整|改动|改|动|启用|采用|是)?\\s*(?:现有|当前|任何)?\\s*|"
            + "(?:do\\s+not|don't|must\\s+not|never|no\\s+longer|without|no|not)\\s*(?:modify|change|alter|adjust|touch|create|add|enable|use|be)?\\s*(?:the|any|existing|current)?\\s*)";
        text = Pattern.compile(negative + TERM, FLAGS).matcher(text).replaceAll(" ");
        String preserve = "(?:保持|保留|preserve|keep|leave)\\s*(?:现有|当前|原有|the|existing|current)?\\s*";
        text = Pattern.compile(preserve + TERM, FLAGS).matcher(text).replaceAll(" ");
        // Preservation belongs to this concept, not an earlier affirmative
        // requirement joined to it ("half model and keep symmetry unchanged").
        text = Pattern.compile(TERM + "(?:(?!并|且|同时|以及|\\band\\b|\\bwhile\\b)[^，,;；。！？!?\\n]){0,20}?(?:保持不变|保持现状|不变|不要修改|不修改|不改|unchanged|unmodified|as\\s+is)", FLAGS).matcher(text).replaceAll(" ");
        return text;
    }

    /** Only an explicit ALL-open directive supersedes earlier open-boundary types.
     * Single-boundary replacements must retain requirements on other boundaries. */
    private static String overrideEarlierOpenBoundaryRequirements(String rawGoal) {
        String[] directives = rawGoal.split(Pattern.quote(GOAL_UPDATE), -1);
        if (directives.length < 2) return rawGoal;
        StringBuilder out = new StringBuilder(directives[0]);
        for (int i = 1; i < directives.length; i++) {
            Matcher replacement = ALL_OPEN_REPLACEMENT.matcher(directives[i]);
            boolean replacesAll = false;
            while (replacement.find()) {
                String before = directives[i].substring(Math.max(0, replacement.start() - 45), replacement.start());
                if (!NEGATIVE_BEFORE.matcher(before).matches()) replacesAll = true;
            }
            if (replacesAll) {
                String prior = OPEN_BOUNDARY_TERM.matcher(out).replaceAll(" ");
                out.setLength(0); out.append(prior);
            }
            out.append(GOAL_UPDATE).append(directives[i]);
        }
        return out.toString();
    }

    private static String stripCancelledAllOpenChanges(String text) {
        Matcher replacement = ALL_OPEN_REPLACEMENT.matcher(text);
        StringBuilder out = new StringBuilder(); int copied = 0;
        while (replacement.find()) {
            String before = text.substring(Math.max(0, replacement.start() - 45), replacement.start());
            if (!NEGATIVE_BEFORE.matcher(before).matches()) continue;
            out.append(text, copied, replacement.start()).append(' '); copied = replacement.end();
        }
        return out.append(text, copied, text.length()).toString();
    }

    /** A half/full transition is a boundary/domain change even without a noun
     * such as 'boundary' in the original sentence. No execution is authorized. */
    static boolean changesBoundaryOrDomain(String rawGoal) {
        if (rawGoal == null) return false;
        for (Pattern pattern : new Pattern[] {CHINESE_REPLACEMENT, ENGLISH_REPLACEMENT}) {
            boolean english = pattern == ENGLISH_REPLACEMENT;
            Matcher changes = pattern.matcher(rawGoal);
            while (changes.find()) {
                String source = english ? changes.group(2) : changes.group(1) != null ? changes.group(1) : changes.group(2);
                Matcher target = LEADING_TARGET.matcher(rawGoal); target.region(changes.end(), rawGoal.length());
                String before = rawGoal.substring(Math.max(0, changes.start() - 45), changes.start());
                if (target.lookingAt() && !NEGATIVE_BEFORE.matcher(before).matches()
                    && KNOWN_BOUNDARY_DOMAIN.matcher(source).find() && KNOWN_BOUNDARY_DOMAIN.matcher(target.group()).find()) return true;
            }
        }
        return false;
    }

    private static String stripReplacementSources(String text, Pattern pattern, boolean english) {
        Matcher changes = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        while (changes.find()) {
            if (changes.start() < copied) continue;
            String source = english ? changes.group(2) : changes.group(1) != null ? changes.group(1) : changes.group(2);
            if (!KNOWN.matcher(source).find()) continue;
            Matcher target = LEADING_TARGET.matcher(text);
            target.region(changes.end(), text.length());
            if (!target.lookingAt()) continue;
            String before = text.substring(Math.max(0, changes.start() - 45), changes.start()).toLowerCase(Locale.ROOT);
            boolean denied = NEGATIVE_BEFORE.matcher(before).matches();
            out.append(text, copied, changes.start());
            if (denied) {
                // A cancelled replacement requests neither its source nor target.
                out.append(' ');
                copied = target.end();
            } else {
                // Preserve the mutation verb and the entire target/unrelated suffix.
                out.append(english ? changes.group(1) + " " + changes.group(3) + " " : changes.group(3));
                copied = changes.end();
            }
        }
        return out.append(text, copied, text.length()).toString();
    }
}
