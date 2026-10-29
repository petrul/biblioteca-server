package ro.editii.scriptorium.fragment;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Public fragment grammar: dots navigate elements, colons introduce UTF-16 offsets. */
public record FragmentSelector(Point start, Point end) {
    public record Point(List<Integer> steps, Integer offset) {
        public Point { steps = List.copyOf(steps); }
        public String nodePath() { return steps.stream().map(String::valueOf).collect(Collectors.joining(".")); }
        @Override public String toString() { return nodePath() + (offset == null ? "" : ":" + offset); }
    }

    public static FragmentSelector parse(String raw) {
        if (raw == null || raw.length() > 1000) throw new IllegalArgumentException("Invalid fragment selector");
        String value = raw.startsWith("_") ? raw.substring(1) : raw;
        String[] range = value.split("\\.\\.", -1);
        if (range.length > 2) throw new IllegalArgumentException("A fragment has at most two endpoints");
        Point start = point(range[0]);
        Point end = range.length == 2 ? point(range[1]) : null;
        if (end == null && start.offset() != null)
            throw new IllegalArgumentException("A text offset needs a range endpoint");
        return new FragmentSelector(start, end);
    }

    private static Point point(String value) {
        if (!value.matches("[0-9]+(?:\\.[0-9]+)*(?::[0-9]+)?"))
            throw new IllegalArgumentException("Invalid fragment endpoint");
        String[] parts = value.split(":", -1);
        List<Integer> steps;
        try {
            steps = Arrays.stream(parts[0].split("\\.")).map(Integer::parseInt).toList();
            if (steps.size() > 64 || steps.stream().anyMatch(n -> n < 1))
                throw new IllegalArgumentException("Element positions must be positive; maximum depth is 64");
            return new Point(steps, parts.length == 2 ? Integer.valueOf(parts[1]) : null);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Fragment number is too large"); }
    }

    public static FragmentSelector fromLegacy(String start, String end) {
        DotPath a = DotPath.parse(start), b = DotPath.parse(end);
        return new FragmentSelector(new Point(a.navigation(), a.textOffset()), new Point(b.navigation(), b.textOffset()));
    }

    @Override public String toString() { return "_" + start + (end == null ? "" : ".." + end); }
}
