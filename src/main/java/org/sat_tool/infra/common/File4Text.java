package org.sat_tool.infra.common;

import lombok.*;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)   // 체이닝 세터 사용 가능: new File4Text().setPath(...).setDirectory(...)
public class File4Text {

    private String directory = "";
    private String path = "";

    private String format = "";
    private String contents = "";

    private List<String> lines = new ArrayList<>();

    private String[] values;
    private int linenums;
    private int[] widths;

    public String getFileName() { return new File(path).getName(); }

    // 필요 시 빌더로 path/directory만 간편 생성
    @Builder
    public static File4Text of(String directory, String path) {
        File4Text f = new File4Text();
        f.directory = directory == null ? "" : directory;
        f.path = path == null ? "" : path;
        return f;
    }

    /* --- 파일 입출력 --- */
    public void read() throws IOException {
        Path p = Paths.get(path);
        if (Files.notExists(p)) {
            log.debug("read: file not found -> {}", path);
            return;
        }
        try (Stream<String> s = Files.lines(p, StandardCharsets.UTF_8)) {
            lines = s.map(String::trim)
                    .filter(Predicate.not(String::isEmpty))
                    .collect(Collectors.toList());
        }
    }

    public void readAndStore() throws IOException { readAndParse(); store(); }
    public void readAndParse() throws IOException { read(); parse(); }

    /** 하위 클래스 override 지점 */
    public void save() throws IOException { /* no-op */ }
    protected void parse() { /* no-op */ }
    protected void store() throws IOException { /* no-op */ }

    protected void saveContents() throws IOException {
        if (isBlank(contents) || isBlank(path)) return;

        Path out = resolveOutputPath();
        Path parent = out.getParent();
        if (parent != null && Files.notExists(parent)) Files.createDirectories(parent);

        Files.writeString(out, contents, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        log.debug("saveContents: wrote {} bytes -> {}", contents.length(), out);
    }

    protected void saveLines() throws IOException {
        if (lines == null || lines.isEmpty()) return;
        contents = String.join(System.lineSeparator(), lines).trim();
        saveContents();
    }

    private Path resolveOutputPath() {
        File info = new File(path);
        String dir = isBlank(this.directory) ? Optional.ofNullable(info.getParent()).orElse("") : this.directory;
        return dir.isEmpty() ? Paths.get(info.getName()) : Paths.get(dir, info.getName());
    }

    /* --- 문자열/파싱 유틸 --- */
    public enum StringSplitOptions { None, RemoveEmptyEntries }

    protected String[] splitWithDelimiter(String original, char[] delimiters,
                                          int limit, StringSplitOptions opt) {
        if (original == null) return new String[0];

        String charClass = new String(delimiters).chars()   // IntStream
                .mapToObj(ch -> escapeIfNeeded((char) ch))
                .collect(Collectors.joining("", "[",
                        opt == StringSplitOptions.RemoveEmptyEntries ? "]+" : "]"));

        String[] arr = (limit > 0) ? original.split(charClass, limit)
                : original.split(charClass);

        return Arrays.stream(arr)
                .map(String::trim)
                .filter(s -> opt != StringSplitOptions.RemoveEmptyEntries || !s.isEmpty())
                .toArray(String[]::new);
    }

    protected String[] splitWithDelimiter(String original, char[] delimiters, StringSplitOptions opt) {
        return splitWithDelimiter(original, delimiters, 0, opt);
    }
    protected String[] splitWithDelimiter(String original, char delimiter, StringSplitOptions opt) {
        return splitWithDelimiter(original, new char[]{delimiter}, 0, opt);
    }
    protected String[] splitWithDelimiter(String original, char delimiter, int count, StringSplitOptions opt) {
        return splitWithDelimiter(original, new char[]{delimiter}, count, opt);
    }

    protected String[] splitDefault(String original) {
        return splitWithDelimiter(original, ' ', StringSplitOptions.RemoveEmptyEntries);
    }
    protected String[] splitDefault(String original, int count) {
        return splitWithDelimiter(original, ' ', count, StringSplitOptions.RemoveEmptyEntries);
    }

    protected String[] splitWithFieldWidth(String original, boolean spaceBetweenItems, int[] widths) {
        String[] results = new String[widths.length];
        int current = 0;
        for (int i = 0; i < widths.length; i++) {
            if (current < original.length()) {
                int len = Math.min(original.length() - current, widths[i]);
                results[i] = original.substring(current, current + len).trim();
                current += widths[i] + (spaceBetweenItems ? 1 : 0);
            } else results[i] = "";
        }
        return results;
    }

    protected String trimDotsAndCRLF(String v) {
        return v == null ? null : v.replaceAll("^[\\r\\n]+|\\.|[\\r\\n]+$", "");
    }
    protected String removeWhiteSpace(String v) {
        return v == null ? null : v.replaceAll("\\s+", "");
    }

    protected String rightZeroPad(double target, int length) {
        String s = String.valueOf(target);
        if (s.length() >= length) return s;
        char[] zeros = new char[length - s.length()];
        Arrays.fill(zeros, '0');
        return s + new String(zeros);
    }

    protected String leftSpacePad(String msg, int pad) {
        return String.format("%" + (msg.length() + Math.max(pad, 0)) + "s", msg);
    }

    /** fname_XX.ext 패턴의 다음 2자리 시퀀스 */
    protected String sequence(String fname, String dir, String ext) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return "00";

        int max = Arrays.stream(files)
                .map(File::getName)
                .filter(n -> n.contains(fname))
                .filter(n -> extEquals(n, ext))
                .filter(n -> n.length() == (fname.length() + 7)) // fname_XX.ext
                .mapToInt(n -> {
                    String[] div = n.split("_");
                    String tail = div[div.length - 1]; // "XX.ext"
                    return Integer.parseInt(tail.substring(0, 2));
                })
                .max().orElse(0);

        int next = (max + 1) % 100;
        if (next == 0) next = 1;
        return String.format("%02d", next);
    }

    protected int getFieldLength(int length, String header) {
        return Math.max(length, header == null ? 0 : header.length());
    }

    /* --- helpers --- */
    private String escapeIfNeeded(char c) {
        return "\\.^$|?*+()[]{}-".indexOf(c) >= 0 ? "\\" + c : String.valueOf(c);
    }
    private boolean extEquals(String filename, String ext) {
        int dot = filename.lastIndexOf('.');
        String e = (dot >= 0) ? filename.substring(dot + 1) : "";
        return e.equalsIgnoreCase(ext);
    }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}