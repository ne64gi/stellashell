package net.fuyumori.stellashell.core.layout;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Page membership only. The host still owns one catalog and one allocation per widget. */
public final class WidgetPages {
    public static final int LIMIT = 20;
    private int count = 1, current;
    private final Map<Integer, Integer> pages = new LinkedHashMap<>();

    public WidgetPages(String saved, Collection<Integer> ids) {
        if (saved != null) try {
            String[] parts = saved.split("\\|", -1);
            if (parts.length != 4 || !"v1".equals(parts[0])) throw new IllegalArgumentException();
            int n = Integer.parseInt(parts[1]), selected = Integer.parseInt(parts[2]);
            if (n < 1 || n > LIMIT || selected < 0 || selected >= n) throw new IllegalArgumentException();
            Map<Integer, Integer> decoded = new LinkedHashMap<>();
            if (!parts[3].isEmpty()) for (String row : parts[3].split(";", -1)) {
                String[] fields = row.split(",", -1);
                if (fields.length != 2) throw new IllegalArgumentException();
                int id = Integer.parseInt(fields[0]), page = Integer.parseInt(fields[1]);
                if (page < 0 || page >= n || decoded.put(id, page) != null) throw new IllegalArgumentException();
            }
            count = n; current = selected; pages.putAll(decoded);
        } catch (IllegalArgumentException ignored) { /* Legacy/corrupt state starts on page one. */ }
        reconcile(ids, 0);
    }

    public int count() { return count; }
    public int current() { return current; }
    public int page(int id) { return pages.getOrDefault(id, 0); }
    public void reconcile(Collection<Integer> ids, int newPage) {
        pages.keySet().retainAll(ids);
        for (int id : ids) pages.putIfAbsent(id, Math.max(0, Math.min(count - 1, newPage)));
    }
    public boolean select(int page) {
        if (page < 0 || page >= count || page == current) return false;
        current = page; return true;
    }
    public boolean add() {
        if (count == LIMIT) return false;
        current = count++; return true;
    }
    public boolean move(int id, int page) {
        if (!pages.containsKey(id) || page < 0 || page >= count || page(id) == page) return false;
        pages.put(id, page); return true;
    }
    public boolean empty() { return !pages.containsValue(current); }
    public boolean removeEmpty() {
        if (count == 1 || !empty()) return false;
        final int removed = current;
        pages.replaceAll((id, page) -> page > removed ? page - 1 : page);
        count--; current = Math.min(current, count - 1); return true;
    }
    public String encode() {
        StringBuilder out = new StringBuilder("v1|").append(count).append('|').append(current).append('|');
        for (Map.Entry<Integer, Integer> row : pages.entrySet()) {
            if (out.charAt(out.length() - 1) != '|') out.append(';');
            out.append(row.getKey()).append(',').append(row.getValue());
        }
        return out.toString();
    }
}
