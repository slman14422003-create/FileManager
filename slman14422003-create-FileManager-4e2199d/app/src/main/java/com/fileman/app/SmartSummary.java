package com.fileman.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-device extractive summary: picks the sentences that carry the most frequent terms of the text. */
final class SmartSummary {
    private SmartSummary() {
    }

    static final class Result {
        final List<String> sentences = new ArrayList<>();
        final List<String> keywords = new ArrayList<>();
        int words;
    }

    private static final Pattern SPLIT = Pattern.compile("(?<=[.!?\\u061F\\u2026])\\s+|\\n+");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    static Result of(String text, int maxSentences) {
        Result res = new Result();
        if (text == null) return res;
        String clean = text.replace('\r', '\n').replaceAll("[ \\t]+", " ").trim();
        if (clean.isEmpty()) return res;

        List<String> sents = new ArrayList<>();
        for (String s : SPLIT.split(clean)) {
            String t = s.trim();
            if (t.length() >= 25 && t.length() <= 420) sents.add(t);
            if (sents.size() >= 600) break;
        }
        Matcher all = WORD.matcher(clean);
        while (all.find()) res.words++;
        if (sents.isEmpty()) return res;

        Map<String, Integer> tf = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        List<List<String>> toks = new ArrayList<>();
        for (String s : sents) {
            List<String> ts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
            while (m.find()) {
                String w = m.group();
                if (w.length() < 3) continue;
                ts.add(w);
                tf.merge(w, 1, Integer::sum);
                if (seen.add(w)) df.merge(w, 1, Integer::sum);
            }
            toks.add(ts);
        }
        final int limit = Math.max(2, (int) (sents.size() * 0.6));
        Map<String, Double> weight = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            Integer d = df.get(e.getKey());
            if (d != null && d > limit && sents.size() > 4) continue;
            weight.put(e.getKey(), (double) e.getValue());
        }

        double[] score = new double[sents.size()];
        for (int i = 0; i < sents.size(); i++) {
            double sum = 0;
            for (String w : toks.get(i)) {
                Double v = weight.get(w);
                if (v != null) sum += v;
            }
            double len = Math.max(4, toks.get(i).size());
            score[i] = sum / Math.pow(len, 0.6) * (i < 3 ? 1.15 : 1.0);
        }
        int n = Math.min(maxSentences, sents.size());
        boolean[] pick = new boolean[sents.size()];
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int i = 0; i < score.length; i++) {
                if (!pick[i] && (best < 0 || score[i] > score[best])) best = i;
            }
            if (best < 0) break;
            pick[best] = true;
        }
        for (int i = 0; i < pick.length; i++) if (pick[i]) res.sentences.add(sents.get(i));

        List<Map.Entry<String, Double>> ranked = new ArrayList<>(weight.entrySet());
        java.util.Collections.sort(ranked, (x, y) -> Double.compare(y.getValue(), x.getValue()));
        for (int i = 0; i < ranked.size() && res.keywords.size() < 6; i++) {
            if (ranked.get(i).getKey().length() >= 4) res.keywords.add(ranked.get(i).getKey());
        }
        return res;
    }
}
