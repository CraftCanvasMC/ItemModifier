package io.canvasmc.itemmodifer.util;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class JsonSuggestionProvider {

    private static final Pattern P_USED_KEY = Pattern.compile("\"([^\"]+)\"\\s*:");
    private static final Pattern P_AFTER_COLON = Pattern.compile("\"([^\"]+)\"\\s*:\\s*$");
    private static final Pattern P_INNER_OBJ = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\{[^{}]*)$");
    private static final Pattern P_INSIDE_OBJ_LIST = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\[]*)$");
    private static final Pattern P_INSIDE_LIST = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]\\[]*)$");
    private static final Pattern P_COMPLETED_ID = Pattern.compile("\"([^\"]+)\"\\s*:\\s*[a-z0-9_.-]+:[a-z0-9_./-]+\\s*$");
    private static final Pattern P_MID_ID = Pattern.compile("\"([^\"]+)\"\\s*:\\s*([a-z0-9_.-]*:?[a-z0-9_./-]*)$");
    private static final Pattern P_MID_BOOL = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(t|tr|tru|f|fa|fal|fals)$");
    private static final Pattern P_MID_NUM = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(-?[0-9]*\\.?[0-9]+)$");
    private static final Pattern P_MID_QUOTED = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)$");
    private static final Pattern P_MID_KEY = Pattern.compile("[{,]\\s*\"([^\"]*)$");
    private static final Pattern P_AFTER_SEP = Pattern.compile("[{,]\\s*$");
    private static final Pattern P_COMPLETED_QUOTED_VAL = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"[^\"]+\"\\s*$");
    private static final Pattern P_COMPLETED_NESTED_OBJ = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\{.*\\})\\s*$", Pattern.DOTALL);
    private static final Pattern P_COMPLETED_PRIM = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(true|false|-?[0-9]*\\.?[0-9]+)\\s+$");
    private static final Pattern P_LAST_KEY_TYPED = Pattern.compile(".*\"[^\"]+\"\\s*$");
    private static final Pattern P_TOP_OBJ_LIST = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[(.*)$", Pattern.DOTALL);
    private static final Pattern P_TOP_LIST = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[([^\\]\\[]*)$");
    private static final Pattern P_TOP_NESTED = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\\{.*)$", Pattern.DOTALL);
    private static final Pattern P_CLOSED_LIST = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\\[[^]]*]\\s*$");

    private static int indexOfSplitter(@NonNull String str, int fromIndex) {
        for (int i = fromIndex; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == ':' || c == '_' || c == '.' || c == '-' || c == '/') return i;
        }
        return -1;
    }

    protected static String @NonNull [] parseFromEnum(@NonNull Class<?> enumClazz) {
        return Arrays.stream(enumClazz.getEnumConstants())
            .map(Object::toString)
            .map(String::toLowerCase)
            .toList()
            .toArray(new String[0]);
    }

    private @NonNull List<String> quotedStrings(@NonNull List<String> examples) {
        List<String> out = new ArrayList<>(examples.stream()
            .map(e -> e.startsWith("\"") ? (e.endsWith("\"") ? e : e + "\"") : "\"" + e + "\"")
            .toList());
        if (out.isEmpty()) out.add("\"\"");
        return out;
    }

    private CompletableFuture<Suggestions> suggestForMode(
        @NonNull FieldInfo fi, CommandContext<CommandSourceStack> context, SuggestionsBuilder offset
    ) {
        return switch (fi.valueMode()) {
            case BOOL_VALUE -> SharedSuggestionProvider.suggest(List.of("true", "false"), offset);
            case INT_VALUE,
                 FLOAT_VALUE -> SharedSuggestionProvider.suggest(fi.resolveExamples(context), offset);
            case STRING_VALUE -> SharedSuggestionProvider.suggest(quotedStrings(fi.resolveExamples(context)), offset);
            case IDENTIFIER_VALUE -> suggestIdentifiers(
                fi.identifierSupplier() != null ? fi.identifierSupplier().apply(context) : List.of(), offset);
            case OBJECT_VALUE -> SharedSuggestionProvider.suggest(List.of("{"), offset);
            default -> offset.buildFuture();
        };
    }

    private @Nullable String[] lastMatch(@NonNull Pattern p, @NonNull String s, int groups) {
        Matcher m = p.matcher(s);
        String[] result = null;
        while (m.find()) {
            result = new String[groups];
            for (int i = 0; i < groups; i++) result[i] = m.group(i + 1);
        }
        return result;
    }

    private int depthAt(@NonNull String s) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return depth;
    }

    private boolean isFullyClosed(@NonNull String s) {
        if (!s.endsWith("}")) return false;
        int depth = 0;
        for (char c : s.toCharArray()) {
            if (c == '{') depth++;
            else if (c == '}') depth--;
        }
        return depth == 0;
    }

    private boolean isInsideNestedValue(@NonNull String s) {
        int lastColon = s.lastIndexOf(':');
        if (lastColon < 0) return false;
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i <= lastColon; i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return depth > 1;
    }

    private boolean lastKeyHasNoColon(@NonNull String s) {
        int lastClose = s.lastIndexOf('"');
        if (lastClose < 0) return false;
        int lastOpen = s.lastIndexOf('"', lastClose - 1);
        if (lastOpen < 0) return false;
        return !s.substring(lastClose + 1).trim().startsWith(":");
    }

    private int valueTokenStart(@NonNull String s) {
        int colon = s.lastIndexOf(':');
        if (colon < 0) return s.length();
        int i = colon + 1;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private int firstUnclosedBrace(@NonNull String s) {
        int depth = 0, first = -1;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{') {
                if (depth == 0) first = i;
                depth++;
            }
            else if (c == '}') depth--;
        }
        return depth > 0 ? first : -1;
    }

    private boolean endsWithArrayLevelComma(@NonNull String s) {
        int depth = 0, lastTopComma = -1;
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) inString = !inString;
            if (inString) continue;
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
            else if (c == ',' && depth == 0) lastTopComma = i;
        }
        return lastTopComma >= 0 && s.substring(lastTopComma + 1).isBlank();
    }

    @Contract("_, _, _, _ -> new")
    private @NonNull JsonState bare(
        JsonState.Mode mode, int start, List<String> remaining, Set<String> used
    ) {
        return new JsonState(mode, start, remaining, used, Set.of(), null, null);
    }

    private @NonNull JsonState analyzeNestedObject(
        String trimmed, @NonNull String nestedInput, @NonNull FieldInfo info,
        List<String> remainingKeys, Set<String> usedKeys
    ) {
        if (isFullyClosed(nestedInput))
            return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);

        Map<String, FieldInfo> nestedFields = info.entryFields();
        int base = trimmed.length() - nestedInput.length();
        String nt = nestedInput.stripTrailing();

        Set<String> nestedUsed = new HashSet<>();
        {
            Matcher m = P_USED_KEY.matcher(nestedInput);
            while (m.find()) {
                int depth = 0;
                boolean inStr = false;
                for (int i = 0; i < m.start(); i++) {
                    char c = nestedInput.charAt(i);
                    if (c == '"' && (i == 0 || nestedInput.charAt(i - 1) != '\\')) inStr = !inStr;
                    if (!inStr) {
                        if (c == '{') depth++;
                        else if (c == '}') depth--;
                    }
                }
                if (depth == 1) nestedUsed.add(m.group(1));
            }
        }
        List<String> nestedRemaining = nestedFields.keySet().stream().filter(k -> !nestedUsed.contains(k)).toList();

        if (nt.equals("{") || (nt.endsWith(",") && depthAt(nt) == 1))
            return new JsonState(JsonState.Mode.KEY, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, info);

        Matcher ac = P_AFTER_COLON.matcher(nt);
        if (ac.find()) {
            FieldInfo fi = nestedFields.get(ac.group(1));
            if (fi != null) {
                JsonState.Mode m = fi.valueMode() == JsonState.Mode.OBJECT_VALUE ? JsonState.Mode.OPEN_BRACE : fi.valueMode();
                return new JsonState(m, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, fi);
            }
        }

        String[] iom = lastMatch(P_INNER_OBJ, nt, 2);
        if (iom != null) {
            FieldInfo fi = nestedFields.get(iom[0]);
            if (fi != null && fi.valueMode() == JsonState.Mode.OBJECT_VALUE)
                return analyzeNestedObject(trimmed, iom[1], fi, nestedRemaining, nestedUsed);
        }

        String[] iol = lastMatch(P_INSIDE_OBJ_LIST, nt, 2);
        if (iol != null) {
            FieldInfo fi = nestedFields.get(iol[0]);
            if (fi != null && fi.valueMode() == JsonState.Mode.LIST_OBJECT)
                return analyzeObjectListArray(trimmed, iol[1], fi, nestedRemaining, nestedUsed);
        }

        String[] il = lastMatch(P_INSIDE_LIST, nt, 2);
        if (il != null) {
            FieldInfo fi = nestedFields.get(il[0]);
            if (fi != null && fi.valueMode() == JsonState.Mode.LIST_VALUE)
                return analyzeSimpleListArray(trimmed, il[1], fi, nestedRemaining, nestedUsed);
        }

        Matcher cid = P_COMPLETED_ID.matcher(nt);
        if (cid.find()) {
            FieldInfo fi = nestedFields.get(cid.group(1));
            if (fi != null && fi.valueMode() == JsonState.Mode.IDENTIFIER_VALUE)
                return new JsonState(JsonState.Mode.COMMA_OR_CLOSE, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, fi);
        }

        Matcher mid = P_MID_ID.matcher(nt);
        if (mid.find() && !mid.group(2).isEmpty()) {
            FieldInfo fi = nestedFields.get(mid.group(1));
            if (fi != null && fi.valueMode() == JsonState.Mode.IDENTIFIER_VALUE)
                return new JsonState(JsonState.Mode.IDENTIFIER_VALUE, trimmed.length() - mid.group(2).length(), nestedRemaining, nestedUsed, Set.of(), null, fi);
        }

        Matcher mb = P_MID_BOOL.matcher(nt);
        if (mb.find()) {
            FieldInfo fi = nestedFields.get(mb.group(1));
            if (fi != null && fi.valueMode() == JsonState.Mode.BOOL_VALUE)
                return new JsonState(JsonState.Mode.BOOL_VALUE, trimmed.length() - mb.group(2).length(), nestedRemaining, nestedUsed, Set.of(), null, fi);
        }

        Matcher mn = P_MID_NUM.matcher(nt);
        if (mn.find()) {
            FieldInfo fi = nestedFields.get(mn.group(1));
            if (fi != null && (fi.valueMode() == JsonState.Mode.FLOAT_VALUE || fi.valueMode() == JsonState.Mode.INT_VALUE))
                return new JsonState(fi.valueMode(), trimmed.length() - mn.group(2).length(), nestedRemaining, nestedUsed, Set.of(), null, fi);
        }

        Matcher mq = P_MID_QUOTED.matcher(nt);
        if (mq.find()) {
            FieldInfo fi = nestedFields.get(mq.group(1));
            if (fi != null)
                return new JsonState(fi.valueMode(), trimmed.lastIndexOf('"'), nestedRemaining, nestedUsed, Set.of(), null, fi);
        }

        if (nt.matches(".*:\\s*(true|false|[0-9]*\\.?[0-9]+)\\s*$") && depthAt(nt) == 1)
            return new JsonState(JsonState.Mode.COMMA_OR_CLOSE, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, info);
        if (nt.matches(".*:\\s*\"[^\"]+\"\\s*$") && depthAt(nt) == 1)
            return new JsonState(JsonState.Mode.COMMA_OR_CLOSE, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, info);
        if (P_LAST_KEY_TYPED.matcher(nt).matches() && lastKeyHasNoColon(nt))
            return new JsonState(JsonState.Mode.COLON, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, info);

        Matcher mk = P_MID_KEY.matcher(nt);
        if (mk.find())
            return new JsonState(JsonState.Mode.KEY, base + nt.lastIndexOf('"'), nestedRemaining, nestedUsed, Set.of(), null, info);
        if (P_AFTER_SEP.matcher(nt).find())
            return new JsonState(JsonState.Mode.KEY, base + nt.length(), nestedRemaining, nestedUsed, Set.of(), null, info);

        return bare(JsonState.Mode.NONE, trimmed.length(), remainingKeys, usedKeys);
    }

    private @NonNull JsonState analyzeObjectListArray(
        String trimmed, @NonNull String arrayContent, FieldInfo info,
        List<String> remainingKeys, Set<String> usedKeys
    ) {
        int lastClose = arrayContent.lastIndexOf(']');
        if (lastClose >= 0 && !arrayContent.substring(lastClose + 1).contains("{"))
            return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);

        String arr = arrayContent.stripTrailing();
        if (arr.isEmpty() || endsWithArrayLevelComma(arr))
            return new JsonState(JsonState.Mode.LIST_OBJECT_OPEN, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
        if (arr.endsWith("}") && depthAt(arr) == 0)
            return new JsonState(JsonState.Mode.ARRAY_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);

        int firstOpen = firstUnclosedBrace(arrayContent);
        if (firstOpen < 0)
            return new JsonState(JsonState.Mode.LIST_OBJECT_OPEN, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
        return analyzeEntryObject(trimmed, arrayContent.substring(firstOpen), info, remainingKeys, usedKeys);
    }

    private @NonNull JsonState analyzeEntryObject(
        String trimmed, String currentObj, @NonNull FieldInfo info,
        List<String> remainingKeys, Set<String> usedKeys
    ) {
        Map<String, FieldInfo> entryFields = info.entryFields();
        int depth = depthAt(currentObj);

        Set<String> entryUsed = new HashSet<>();
        Matcher ekm = P_USED_KEY.matcher(currentObj);
        while (ekm.find()) entryUsed.add(ekm.group(1));

        if ((currentObj.endsWith("{") || currentObj.endsWith(",")) && depth == 1)
            return new JsonState(JsonState.Mode.LIST_OBJECT_KEY, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);

        String[] eno = lastMatch(P_INNER_OBJ, currentObj, 2);
        if (eno != null) {
            FieldInfo fi = entryFields.get(eno[0]);
            if (fi != null && fi.valueMode() == JsonState.Mode.OBJECT_VALUE)
                return analyzeNestedObject(trimmed, eno[1], fi, remainingKeys, usedKeys);
        }

        Matcher ac = P_AFTER_COLON.matcher(currentObj);
        if (ac.find() && depth == 1)
            return new JsonState(JsonState.Mode.LIST_OBJECT_VALUE, trimmed.length(), remainingKeys, usedKeys, entryUsed, ac.group(1), info);

        if (depth == 0 && isFullyClosed(currentObj))
            return new JsonState(JsonState.Mode.LIST_OBJECT_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);

        if (depth == 1) {
            String[] cno = lastMatch(P_COMPLETED_NESTED_OBJ, currentObj, 2);
            if (cno != null && entryFields.containsKey(cno[0])
                && entryFields.get(cno[0]).valueMode() == JsonState.Mode.OBJECT_VALUE
                && isFullyClosed(cno[1]))
                return new JsonState(JsonState.Mode.LIST_OBJECT_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);

            if (P_COMPLETED_QUOTED_VAL.matcher(currentObj).find()
                || P_COMPLETED_PRIM.matcher(currentObj).find())
                return new JsonState(JsonState.Mode.LIST_OBJECT_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);

            Matcher ci = P_COMPLETED_ID.matcher(currentObj);
            if (ci.find() && entryFields.containsKey(ci.group(1)))
                return new JsonState(JsonState.Mode.LIST_OBJECT_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);

            Matcher mq = P_MID_QUOTED.matcher(currentObj);
            if (mq.find())
                return new JsonState(JsonState.Mode.LIST_OBJECT_VALUE, trimmed.lastIndexOf('"'), remainingKeys, usedKeys, entryUsed, mq.group(1), info);

            Matcher mi = P_MID_ID.matcher(currentObj);
            if (mi.find() && !mi.group(2).isEmpty())
                return new JsonState(JsonState.Mode.LIST_OBJECT_VALUE, trimmed.length() - mi.group(2).length(), remainingKeys, usedKeys, entryUsed, mi.group(1), info);

            Matcher mn = P_MID_NUM.matcher(currentObj);
            if (mn.find())
                return new JsonState(JsonState.Mode.LIST_OBJECT_VALUE, trimmed.length() - mn.group(2).length(), remainingKeys, usedKeys, entryUsed, mn.group(1), info);

            Matcher mb = P_MID_BOOL.matcher(currentObj);
            if (mb.find())
                return new JsonState(JsonState.Mode.LIST_OBJECT_VALUE, trimmed.length() - mb.group(2).length(), remainingKeys, usedKeys, entryUsed, mb.group(1), info);

            Matcher mk = P_MID_KEY.matcher(currentObj);
            if (mk.find())
                return new JsonState(JsonState.Mode.LIST_OBJECT_KEY, trimmed.lastIndexOf('"'), remainingKeys, usedKeys, entryUsed, null, info);

            if (P_LAST_KEY_TYPED.matcher(currentObj).matches() && lastKeyHasNoColon(currentObj))
                return new JsonState(JsonState.Mode.COLON, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);

            if (!entryUsed.isEmpty() && entryUsed.containsAll(entryFields.keySet()))
                return new JsonState(JsonState.Mode.LIST_OBJECT_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, entryUsed, null, info);
        }

        return bare(JsonState.Mode.NONE, trimmed.length(), remainingKeys, usedKeys);
    }

    @Contract("_, _, _, _, _ -> new")
    private @NonNull JsonState analyzeSimpleListArray(
        String trimmed, @NonNull String listContent, @NonNull FieldInfo info,
        List<String> remainingKeys, Set<String> usedKeys
    ) {
        String tl = listContent.stripTrailing();

        if (info.elementType() != null && info.elementType().valueMode() == JsonState.Mode.OBJECT_VALUE) {
            if (tl.isEmpty() || endsWithArrayLevelComma(tl))
                return new JsonState(JsonState.Mode.LIST_ELEMENT, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
            int firstOpen = firstUnclosedBrace(tl);
            if (firstOpen >= 0)
                return analyzeNestedObject(trimmed,
                    trimmed.substring(trimmed.length() - tl.length() + firstOpen),
                    info.elementType(), remainingKeys, usedKeys);
            return new JsonState(JsonState.Mode.LIST_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
        }

        if (tl.isEmpty() || tl.endsWith(","))
            return new JsonState(JsonState.Mode.LIST_ELEMENT, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
        if (tl.matches(".*\"[^\"]*$"))
            return new JsonState(JsonState.Mode.LIST_ELEMENT, trimmed.lastIndexOf('"'), remainingKeys, usedKeys, Set.of(), null, info);
        if (tl.matches(".*\"[^\"]+\"\\s*$"))
            return new JsonState(JsonState.Mode.LIST_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);

        Matcher mu = Pattern.compile("(?:^|[,\\[])\\s*([a-z0-9_.-]*:?[a-z0-9_./-]*)$").matcher(tl);
        if (mu.find() && !mu.group(1).isEmpty())
            return new JsonState(JsonState.Mode.LIST_ELEMENT, trimmed.length() - mu.group(1).length(), remainingKeys, usedKeys, Set.of(), null, info);
        if (tl.matches(".*[a-z0-9_./:+-]\\s+"))
            return new JsonState(JsonState.Mode.LIST_COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);

        return new JsonState(JsonState.Mode.LIST_ELEMENT, trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
    }

    public CompletableFuture<Suggestions> suggestIdentifiers(
        @NonNull Iterable<Identifier> resources, @NonNull SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        boolean hasColon = remaining.indexOf(':') > -1;
        for (Identifier id : resources) {
            if (remaining.isEmpty()
                || (hasColon ? matchesSubStr(remaining, id.toString())
                : matchesSubStr(remaining, id.getNamespace()) || matchesSubStr(remaining, id.getPath())))
                builder.suggest(id.toString());
        }
        return builder.buildFuture();
    }

    @Deprecated
    public CompletableFuture<Suggestions> suggestIdentifiers(
        Iterable<Identifier> resources, SuggestionsBuilder builder,
        @SuppressWarnings("unused") String ignoredPrefix
    ) {
        return suggestIdentifiers(resources, builder);
    }

    private boolean matchesSubStr(String input, @NonNull String substring) {
        int i = 0;
        while (!substring.startsWith(input, i)) {
            int next = indexOfSplitter(substring, i);
            if (next < 0) return false;
            i = next + 1;
        }
        return true;
    }

    public CompletableFuture<Suggestions> jsonSuggestions(
        CommandContext<CommandSourceStack> context, @NonNull SuggestionsBuilder builder
    ) {
        String input = builder.getRemaining();
        JsonState state = analyzeJson(input, context);
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + state.suggestionStart());

        return switch (state.mode()) {
            case OPEN_BRACE, LIST_OBJECT_OPEN, OBJECT_VALUE -> SharedSuggestionProvider.suggest(List.of("{"), offset);

            case KEY -> {
                List<String> keys = state.availableKeys().stream().map(k -> "\"" + k + "\"").toList();
                List<String> all = new ArrayList<>(keys);
                if (!state.usedKeys().isEmpty()) all.add("}");
                yield SharedSuggestionProvider.suggest(all, offset);
            }

            case COLON -> SharedSuggestionProvider.suggest(List.of(":"), offset);

            case BOOL_VALUE -> SharedSuggestionProvider.suggest(List.of("true", "false"), offset);

            case INT_VALUE,
                 FLOAT_VALUE -> SharedSuggestionProvider.suggest(
                state.currentField() != null ? state.currentField().resolveExamples(context) : List.of(), offset);

            case STRING_VALUE -> SharedSuggestionProvider.suggest(
                quotedStrings(state.currentField() != null ? state.currentField().resolveExamples(context) : List.of()), offset);

            case IDENTIFIER_VALUE -> {
                FieldInfo info = state.currentField();
                if (info == null) yield builder.buildFuture();
                yield suggestIdentifiers(
                    info.identifierSupplier() != null ? info.identifierSupplier().apply(context) : List.of(), offset);
            }

            case LIST_VALUE,
                 LIST_OBJECT -> SharedSuggestionProvider.suggest(List.of("["), offset);

            case LIST_ELEMENT -> {
                FieldInfo info = state.currentField();
                if (info == null || info.elementType() == null) yield builder.buildFuture();
                yield suggestForMode(info.elementType(), context, offset);
            }

            case LIST_COMMA_OR_CLOSE,
                 ARRAY_COMMA_OR_CLOSE -> SharedSuggestionProvider.suggest(List.of(",", "]"), offset);

            case LIST_OBJECT_KEY -> {
                FieldInfo info = state.currentField();
                if (info == null || info.entryFields() == null) yield builder.buildFuture();
                List<String> keys = info.entryFields().keySet().stream()
                    .filter(k -> !state.usedEntryKeys().contains(k))
                    .map(k -> "\"" + k + "\"").toList();
                List<String> all = new ArrayList<>(keys);
                if (!state.usedEntryKeys().isEmpty()) all.add("}");
                yield SharedSuggestionProvider.suggest(all, offset);
            }

            case LIST_OBJECT_VALUE -> {
                FieldInfo info = state.currentField();
                if (info == null || info.entryFields() == null || state.currentEntryKey() == null)
                    yield builder.buildFuture();
                FieldInfo valueInfo = info.entryFields().get(state.currentEntryKey());
                if (valueInfo == null) yield builder.buildFuture();
                yield suggestForMode(valueInfo, context, offset);
            }

            case LIST_OBJECT_COMMA_OR_CLOSE -> {
                FieldInfo info = state.currentField();
                if (info == null || info.entryFields() == null) yield builder.buildFuture();
                List<String> next = new ArrayList<>();
                if (!state.usedEntryKeys().containsAll(info.entryFields().keySet())) next.add(",");
                next.add("}");
                yield SharedSuggestionProvider.suggest(next, offset);
            }

            case COMMA_OR_CLOSE -> SharedSuggestionProvider.suggest(
                state.availableKeys().isEmpty() ? List.of("}") : List.of(",", "}"), offset);

            case CLOSE_BRACE -> SharedSuggestionProvider.suggest(List.of("}"), offset);
            case NONE -> builder.buildFuture();
        };
    }

    public Map<String, FieldInfo> jsonFields() {
        return Map.of();
    }

    public Map<String, FieldInfo> jsonFields(CommandContext<CommandSourceStack> context) {
        return jsonFields();
    }

    @Contract("_, _ -> new")
    @NonNull
    private JsonState analyzeJson(String input, CommandContext<CommandSourceStack> context) {
        Map<String, FieldInfo> fields = jsonFields(context);

        Set<String> usedKeys = new HashSet<>();
        Matcher um = P_USED_KEY.matcher(input);
        while (um.find()) usedKeys.add(um.group(1));
        List<String> remainingKeys = fields.keySet().stream().filter(k -> !usedKeys.contains(k)).toList();

        String trimmed = input.stripTrailing();

        if (trimmed.isEmpty()) return bare(JsonState.Mode.OPEN_BRACE, 0, remainingKeys, usedKeys);
        if (trimmed.equals("{")) return bare(JsonState.Mode.KEY, trimmed.length(), remainingKeys, usedKeys);
        if (isFullyClosed(trimmed)) return bare(JsonState.Mode.NONE, trimmed.length(), remainingKeys, usedKeys);
        if (depthAt(trimmed) == 1 && trimmed.endsWith(","))
            return bare(JsonState.Mode.KEY, trimmed.length(), remainingKeys, usedKeys);
        if (trimmed.matches(".*:\\s*(true|false|[0-9]*\\.?[0-9]+)\\s*$") && !isInsideNestedValue(trimmed))
            return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);
        if (trimmed.matches(".*:\\s*\"[^\"]+\"\\s*$") && !isInsideNestedValue(trimmed))
            return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);

        Matcher iol = P_TOP_OBJ_LIST.matcher(trimmed);
        if (iol.find()) {
            FieldInfo info = fields.get(iol.group(1));
            if (info != null && info.valueMode() == JsonState.Mode.LIST_OBJECT)
                return analyzeObjectListArray(trimmed, iol.group(2), info, remainingKeys, usedKeys);
        }

        Matcher isl = P_TOP_LIST.matcher(trimmed);
        if (isl.find()) {
            FieldInfo info = fields.get(isl.group(1));
            if (info != null && info.valueMode() == JsonState.Mode.LIST_VALUE)
                return analyzeSimpleListArray(trimmed, isl.group(2), info, remainingKeys, usedKeys);
        }

        if (P_CLOSED_LIST.matcher(trimmed).find())
            return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);

        Matcher ino = P_TOP_NESTED.matcher(trimmed);
        if (ino.find()) {
            FieldInfo info = fields.get(ino.group(1));
            if (info != null && info.valueMode() == JsonState.Mode.OBJECT_VALUE)
                return analyzeNestedObject(trimmed, ino.group(2), info, remainingKeys, usedKeys);
        }

        Matcher ac = P_AFTER_COLON.matcher(trimmed);
        if (ac.find()) {
            FieldInfo info = fields.get(ac.group(1));
            if (info != null)
                return new JsonState(info.valueMode(), trimmed.length(), remainingKeys, usedKeys, Set.of(), null, info);
        }

        Matcher mb = P_MID_BOOL.matcher(trimmed);
        if (mb.find()) {
            FieldInfo info = fields.get(mb.group(1));
            if (info != null && info.valueMode() == JsonState.Mode.BOOL_VALUE)
                return new JsonState(JsonState.Mode.BOOL_VALUE, valueTokenStart(trimmed), remainingKeys, usedKeys, Set.of(), null, info);
        }
        Matcher mn = P_MID_NUM.matcher(trimmed);
        if (mn.find()) {
            FieldInfo info = fields.get(mn.group(1));
            if (info != null && (info.valueMode() == JsonState.Mode.FLOAT_VALUE || info.valueMode() == JsonState.Mode.INT_VALUE))
                return new JsonState(info.valueMode(), trimmed.length() - mn.group(2).length(), remainingKeys, usedKeys, Set.of(), null, info);
        }
        Matcher mq = P_MID_QUOTED.matcher(trimmed);
        if (mq.find()) {
            FieldInfo info = fields.get(mq.group(1));
            if (info != null)
                return new JsonState(info.valueMode(), trimmed.lastIndexOf('"'), remainingKeys, usedKeys, Set.of(), null, info);
        }

        if (P_COMPLETED_QUOTED_VAL.matcher(trimmed).find())
            return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);

        Matcher ci = P_COMPLETED_ID.matcher(trimmed);
        if (ci.find()) {
            FieldInfo info = fields.get(ci.group(1));
            if (info != null && info.valueMode() == JsonState.Mode.IDENTIFIER_VALUE)
                return bare(JsonState.Mode.COMMA_OR_CLOSE, trimmed.length(), remainingKeys, usedKeys);
        }
        Matcher mi = P_MID_ID.matcher(trimmed);
        if (mi.find() && !mi.group(2).isEmpty()) {
            FieldInfo info = fields.get(mi.group(1));
            if (info != null && info.valueMode() == JsonState.Mode.IDENTIFIER_VALUE)
                return new JsonState(JsonState.Mode.IDENTIFIER_VALUE, trimmed.length() - mi.group(2).length(), remainingKeys, usedKeys, Set.of(), null, info);
        }

        if (P_LAST_KEY_TYPED.matcher(trimmed).matches() && lastKeyHasNoColon(trimmed))
            return bare(JsonState.Mode.COLON, trimmed.length(), remainingKeys, usedKeys);

        Matcher mk = P_MID_KEY.matcher(trimmed);
        if (mk.find())
            return bare(JsonState.Mode.KEY, trimmed.lastIndexOf('"'), remainingKeys, usedKeys);
        if (P_AFTER_SEP.matcher(trimmed).find())
            return bare(JsonState.Mode.KEY, trimmed.length(), remainingKeys, usedKeys);

        return bare(JsonState.Mode.NONE, input.length(), remainingKeys, usedKeys);
    }

    public record JsonState(
        Mode mode, int suggestionStart,
        List<String> availableKeys, Set<String> usedKeys,
        Set<String> usedEntryKeys, @Nullable String currentEntryKey,
        @Nullable FieldInfo currentField
    ) {
        public enum Mode {
            OPEN_BRACE, KEY, COLON,
            BOOL_VALUE, INT_VALUE, FLOAT_VALUE, STRING_VALUE, IDENTIFIER_VALUE,
            LIST_VALUE, LIST_ELEMENT, LIST_COMMA_OR_CLOSE,
            LIST_OBJECT, LIST_OBJECT_OPEN, LIST_OBJECT_KEY, LIST_OBJECT_VALUE, LIST_OBJECT_COMMA_OR_CLOSE,
            ARRAY_COMMA_OR_CLOSE,
            OBJECT_VALUE,
            COMMA_OR_CLOSE, CLOSE_BRACE, NONE
        }
    }

    public record FieldInfo(
        JsonState.Mode valueMode, List<String> examples,
        @Nullable FieldInfo elementType,
        @Nullable Function<CommandContext<CommandSourceStack>, Iterable<Identifier>> identifierSupplier,
        @Nullable Function<CommandContext<CommandSourceStack>, List<String>> dynamicExamples,
        @Nullable Map<String, FieldInfo> entryFields
    ) {
        @Contract(" -> new")
        public static @NonNull FieldInfo bool() {
            return new FieldInfo(JsonState.Mode.BOOL_VALUE, List.of(), null, null, null, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo floatField(String... examples) {
            return new FieldInfo(JsonState.Mode.FLOAT_VALUE, List.of(examples), null, null, null, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo intField(String... examples) {
            return new FieldInfo(JsonState.Mode.INT_VALUE, List.of(examples), null, null, null, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo stringField(String... examples) {
            return new FieldInfo(JsonState.Mode.STRING_VALUE, List.of(examples), null, null, null, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo dynamicStringField(
            Function<CommandContext<CommandSourceStack>, List<String>> examples
        ) {
            return new FieldInfo(JsonState.Mode.STRING_VALUE, List.of(), null, null, examples, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo identifierField(
            Function<CommandContext<CommandSourceStack>, Iterable<Identifier>> supplier
        ) {
            return new FieldInfo(JsonState.Mode.IDENTIFIER_VALUE, List.of(), null, supplier, null, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo listField(@NonNull FieldInfo elementType) {
            return new FieldInfo(JsonState.Mode.LIST_VALUE, List.of(), elementType, null, null, null);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo objectListField(@NonNull Map<String, FieldInfo> entryFields) {
            return new FieldInfo(JsonState.Mode.LIST_OBJECT, List.of(), null, null, null, entryFields);
        }

        @Contract("_ -> new")
        public static @NonNull FieldInfo objectField(@NonNull Map<String, FieldInfo> nestedFields) {
            return new FieldInfo(JsonState.Mode.OBJECT_VALUE, List.of(), null, null, null, nestedFields);
        }

        public List<String> resolveExamples(CommandContext<CommandSourceStack> context) {
            return dynamicExamples != null ? dynamicExamples.apply(context) : examples;
        }
    }
}
