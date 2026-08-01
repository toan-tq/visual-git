package com.visualgit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SyntaxHighlighter {

    // ── Language Detection ───────────────────────────────────────────────

    public enum Language {
        C_CPP, JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT, GO, RUST, BASH, MARKDOWN, PLAIN
    }

    public static Language detectLanguage(String fileName) {
        if (fileName == null) return Language.PLAIN;
        String name = fileName.toLowerCase();
        if (name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".cpp") || name.endsWith(".cc")
                || name.endsWith(".cxx") || name.endsWith(".hpp") || name.endsWith(".hxx")
                || name.endsWith(".m") || name.endsWith(".mm")) return Language.C_CPP;
        if (name.endsWith(".java")) return Language.JAVA;
        if (name.endsWith(".py") || name.endsWith(".pyw") || name.endsWith(".pyi")) return Language.PYTHON;
        if (name.endsWith(".js") || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs")) return Language.JAVASCRIPT;
        if (name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".mts") || name.endsWith(".cts")) return Language.TYPESCRIPT;
        if (name.endsWith(".go")) return Language.GO;
        if (name.endsWith(".rs")) return Language.RUST;
        if (name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh")
                || name.endsWith(".ksh") || name.endsWith(".fish")) return Language.BASH;
        if (name.endsWith(".md") || name.endsWith(".markdown")) return Language.MARKDOWN;
        // Common config/data files that look like code
        if (name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".html")
                || name.endsWith(".css") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".toml") || name.endsWith(".ini") || name.endsWith(".cfg")
                || name.endsWith(".conf") || name.endsWith(".properties")) return Language.PLAIN;
        return Language.PLAIN;
    }

    public static boolean usesHashComments(Language lang) {
        return lang == Language.PYTHON || lang == Language.BASH;
    }

    public static boolean usesCStyleComments(Language lang) {
        return lang == Language.C_CPP || lang == Language.JAVA || lang == Language.JAVASCRIPT
                || lang == Language.TYPESCRIPT || lang == Language.GO || lang == Language.RUST;
    }

    public static boolean hasBlockComments(Language lang) {
        return usesCStyleComments(lang);
    }

    public static boolean hasPythonTripleQuotes(Language lang) {
        return lang == Language.PYTHON;
    }

    // ── Legacy keyword/type sets (used by FileCompare via 5-param highlightLine) ─

    public static final Set<String> KEYWORDS = Set.of(
        "auto", "break", "case", "catch", "class", "const", "continue",
        "default", "delete", "do", "else", "enum", "explicit", "extern",
        "false", "for", "friend", "goto", "if", "inline", "namespace",
        "new", "nullptr", "operator", "private", "protected", "public",
        "return", "sizeof", "static", "struct", "switch", "template",
        "this", "throw", "true", "try", "typedef", "typename", "union",
        "using", "virtual", "volatile", "while",
        // Java extras
        "abstract", "assert", "boolean", "byte", "extends", "final",
        "finally", "implements", "import", "instanceof", "interface",
        "native", "package", "strictfp", "super", "synchronized",
        "throws", "transient"
    );

    public static final Set<String> TYPES = Set.of(
        "void", "int", "char", "short", "long", "float", "double",
        "unsigned", "signed", "bool", "string", "vector", "map", "set",
        "list", "array", "size_t", "uint8_t", "int32_t", "int64_t",
        // Java types
        "String", "Integer", "Long", "Double", "Float", "Boolean",
        "Object", "List", "Map", "Set", "ArrayList", "HashMap"
    );

    // ── Per-language keyword sets ────────────────────────────────────────

    private static final Set<String> C_CPP_KW = Set.of(
        "auto", "break", "case", "catch", "class", "const", "constexpr", "continue",
        "default", "delete", "do", "else", "enum", "explicit", "extern",
        "false", "for", "friend", "goto", "if", "inline", "namespace",
        "new", "noexcept", "nullptr", "operator", "override", "private", "protected", "public",
        "return", "sizeof", "static", "static_cast", "dynamic_cast", "reinterpret_cast", "const_cast",
        "struct", "switch", "template", "this", "throw", "true", "try",
        "typedef", "typeid", "typename", "union", "using", "virtual", "volatile", "while", "final"
    );

    private static final Set<String> JAVA_KW = Set.of(
        "abstract", "assert", "break", "case", "catch", "class", "const", "continue",
        "default", "do", "else", "enum", "extends", "false", "final", "finally",
        "for", "goto", "if", "implements", "import", "instanceof", "interface",
        "native", "new", "null", "package", "private", "protected", "public",
        "return", "static", "strictfp", "super", "switch", "synchronized",
        "this", "throw", "throws", "transient", "true", "try", "volatile", "while",
        "yield", "record", "sealed", "permits", "var"
    );

    private static final Set<String> PYTHON_KW = Set.of(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield", "match", "case"
    );

    private static final Set<String> JAVASCRIPT_KW = Set.of(
        "async", "await", "break", "case", "catch", "class", "const", "continue",
        "debugger", "default", "delete", "do", "else", "export", "extends",
        "false", "finally", "for", "function", "if", "import", "in",
        "instanceof", "let", "new", "null", "of", "return", "static",
        "switch", "this", "throw", "true", "try", "typeof", "undefined",
        "var", "void", "while", "with", "yield"
    );

    private static final Set<String> TYPESCRIPT_KW;
    static {
        Set<String> ts = new HashSet<>(JAVASCRIPT_KW);
        ts.addAll(Set.of("abstract", "as", "declare", "enum", "implements", "interface",
            "keyof", "namespace", "never", "readonly", "type", "unknown", "any",
            "infer", "is", "module", "require", "asserts", "override"));
        TYPESCRIPT_KW = Set.copyOf(ts);
    }

    private static final Set<String> GO_KW = Set.of(
        "break", "case", "chan", "const", "continue", "default", "defer",
        "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
        "interface", "map", "package", "range", "return", "select", "struct",
        "switch", "type", "var"
    );

    private static final Set<String> RUST_KW = Set.of(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn",
        "else", "enum", "extern", "false", "fn", "for", "if", "impl", "in",
        "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
        "self", "Self", "static", "struct", "super", "trait", "true", "type",
        "unsafe", "use", "where", "while", "macro_rules"
    );

    private static final Set<String> BASH_KW = Set.of(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "select",
        "while", "until", "do", "done", "in", "function", "time",
        "break", "continue", "return", "exit", "export", "readonly",
        "declare", "local", "typeset", "unset", "shift", "trap",
        "eval", "exec", "source"
    );

    // ── Per-language type sets ───────────────────────────────────────────

    private static final Set<String> C_CPP_TYPES = Set.of(
        "void", "int", "char", "short", "long", "float", "double",
        "unsigned", "signed", "bool", "string", "wstring",
        "vector", "map", "set", "list", "array", "deque", "stack", "queue",
        "pair", "tuple", "size_t", "ptrdiff_t",
        "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "int8_t", "int16_t", "int32_t", "int64_t", "nullptr_t", "FILE"
    );

    private static final Set<String> JAVA_TYPES = Set.of(
        "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short",
        "Object", "Class", "List", "Map", "Set", "ArrayList", "HashMap", "HashSet",
        "LinkedList", "TreeMap", "Optional", "Stream", "Collection", "Iterable",
        "Iterator", "Comparable", "Runnable", "Thread", "Exception", "Error"
    );

    private static final Set<String> PYTHON_TYPES = Set.of(
        "int", "float", "str", "bool", "list", "dict", "tuple", "set",
        "bytes", "bytearray", "complex", "frozenset", "range", "type", "object",
        "super", "print", "len", "isinstance", "issubclass", "property",
        "staticmethod", "classmethod", "enumerate", "zip", "map", "filter",
        "sorted", "reversed", "any", "all", "open", "input",
        "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
        "AttributeError", "RuntimeError", "StopIteration", "NotImplementedError"
    );

    private static final Set<String> JAVASCRIPT_TYPES = Set.of(
        "Array", "Boolean", "Date", "Error", "Function", "JSON", "Map", "Math",
        "Number", "Object", "Promise", "Proxy", "RegExp", "Set", "String",
        "Symbol", "WeakMap", "WeakSet", "BigInt", "Infinity", "NaN",
        "console", "window", "document", "globalThis"
    );

    private static final Set<String> TYPESCRIPT_TYPES = JAVASCRIPT_TYPES;

    private static final Set<String> GO_TYPES = Set.of(
        "bool", "byte", "complex64", "complex128", "error",
        "float32", "float64", "int", "int8", "int16", "int32", "int64",
        "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64", "uintptr",
        "nil", "true", "false", "iota", "any",
        "append", "cap", "close", "copy", "delete", "imag",
        "len", "make", "new", "panic", "print", "println", "real", "recover"
    );

    private static final Set<String> RUST_TYPES = Set.of(
        "i8", "i16", "i32", "i64", "i128", "isize",
        "u8", "u16", "u32", "u64", "u128", "usize",
        "f32", "f64", "bool", "char", "str",
        "String", "Vec", "Box", "Option", "Result", "Some", "None", "Ok", "Err",
        "HashMap", "HashSet", "Rc", "Arc", "Cell", "RefCell", "Mutex", "Pin", "Future"
    );

    private static final Set<String> BASH_TYPES = Set.of(
        "echo", "printf", "read", "test", "cd", "pwd", "let",
        "true", "false", "alias", "type", "command", "builtin",
        "getopts", "wait", "kill", "bg", "fg", "jobs", "umask"
    );

    // ── Language → keyword/type lookup ───────────────────────────────────

    private static final Map<Language, Set<String>> LANG_KEYWORDS = Map.of(
        Language.C_CPP, C_CPP_KW,
        Language.JAVA, JAVA_KW,
        Language.PYTHON, PYTHON_KW,
        Language.JAVASCRIPT, JAVASCRIPT_KW,
        Language.TYPESCRIPT, TYPESCRIPT_KW,
        Language.GO, GO_KW,
        Language.RUST, RUST_KW,
        Language.BASH, BASH_KW
    );

    private static final Map<Language, Set<String>> LANG_TYPES = Map.of(
        Language.C_CPP, C_CPP_TYPES,
        Language.JAVA, JAVA_TYPES,
        Language.PYTHON, PYTHON_TYPES,
        Language.JAVASCRIPT, JAVASCRIPT_TYPES,
        Language.TYPESCRIPT, TYPESCRIPT_TYPES,
        Language.GO, GO_TYPES,
        Language.RUST, RUST_TYPES,
        Language.BASH, BASH_TYPES
    );

    // ── Markdown Highlighting ────────────────────────────────────────────

    /**
     * Highlight markdown syntax in a single line.
     *
     * @param line         the full line text
     * @param offset       character offset of this line within the document
     * @param inCodeBlock  true if this line is inside a fenced code block
     * @param styles       list to append StyleRange objects to
     */
    public static void highlightMarkdownLine(String line, int offset, boolean inCodeBlock, List<StyleRange> styles) {
        String trimmed = line.stripLeading();

        // Fenced code block marker (``` with optional language tag)
        if (trimmed.startsWith("```")) {
            StyleRange sr = new StyleRange();
            sr.start = offset;
            sr.length = line.length();
            sr.foreground = AppTheme.mdCodeColor;
            sr.fontStyle = SWT.BOLD;
            styles.add(sr);
            return;
        }

        // Inside fenced code block — style entire line as code
        if (inCodeBlock) {
            StyleRange sr = new StyleRange();
            sr.start = offset;
            sr.length = line.length();
            sr.foreground = AppTheme.mdCodeColor;
            styles.add(sr);
            return;
        }

        // Headers: # to ######
        if (trimmed.length() > 0 && trimmed.charAt(0) == '#') {
            int level = 0;
            while (level < trimmed.length() && level < 6 && trimmed.charAt(level) == '#') level++;
            if (level < trimmed.length() && trimmed.charAt(level) == ' ') {
                StyleRange sr = new StyleRange();
                sr.start = offset;
                sr.length = line.length();
                sr.foreground = AppTheme.mdHeadingColor;
                sr.fontStyle = SWT.BOLD;
                styles.add(sr);
                return;
            }
        }

        // Blockquote: > text
        if (trimmed.startsWith("> ") || trimmed.equals(">")) {
            StyleRange sr = new StyleRange();
            sr.start = offset;
            sr.length = line.length();
            sr.foreground = AppTheme.mdQuoteColor;
            sr.fontStyle = SWT.ITALIC;
            styles.add(sr);
            return;
        }

        // Horizontal rule: ---, ***, ___ (3 or more of same char, optionally spaced)
        if (trimmed.length() >= 3) {
            String noSpaces = trimmed.replace(" ", "");
            if (noSpaces.length() >= 3 && (noSpaces.chars().allMatch(ch -> ch == '-')
                    || noSpaces.chars().allMatch(ch -> ch == '*')
                    || noSpaces.chars().allMatch(ch -> ch == '_'))) {
                StyleRange sr = new StyleRange();
                sr.start = offset;
                sr.length = line.length();
                sr.foreground = AppTheme.mdQuoteColor;
                styles.add(sr);
                return;
            }
        }

        // List markers: -, *, +, or 1. (with leading whitespace allowed)
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            if ((first == '-' || first == '*' || first == '+') && trimmed.charAt(1) == ' ') {
                int markerStart = line.indexOf(first);
                StyleRange sr = new StyleRange();
                sr.start = offset + markerStart;
                sr.length = 1;
                sr.foreground = AppTheme.mdHeadingColor;
                sr.fontStyle = SWT.BOLD;
                styles.add(sr);
                highlightMarkdownInline(line, offset, markerStart + 2, styles);
                return;
            }
            // Ordered list: digits followed by . and space
            if (Character.isDigit(first)) {
                int dotIdx = trimmed.indexOf('.');
                if (dotIdx > 0 && dotIdx < trimmed.length() - 1 && trimmed.charAt(dotIdx + 1) == ' ') {
                    boolean allDigits = true;
                    for (int d = 0; d < dotIdx; d++) {
                        if (!Character.isDigit(trimmed.charAt(d))) { allDigits = false; break; }
                    }
                    if (allDigits) {
                        int markerStart = line.length() - trimmed.length();
                        StyleRange sr = new StyleRange();
                        sr.start = offset + markerStart;
                        sr.length = dotIdx + 1;
                        sr.foreground = AppTheme.mdHeadingColor;
                        sr.fontStyle = SWT.BOLD;
                        styles.add(sr);
                        highlightMarkdownInline(line, offset, markerStart + dotIdx + 2, styles);
                        return;
                    }
                }
            }
        }

        // Regular line — just scan for inline elements
        highlightMarkdownInline(line, offset, 0, styles);
    }

    /** Scan a line for inline markdown elements: `code`, **bold**, *italic*, [links](url), ~~strikethrough~~. */
    private static void highlightMarkdownInline(String line, int offset, int from, List<StyleRange> styles) {
        int len = line.length();
        for (int i = from; i < len; ) {
            char c = line.charAt(i);

            // Inline code: `...`
            if (c == '`') {
                int end = line.indexOf('`', i + 1);
                if (end > i) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + i;
                    sr.length = end - i + 1;
                    sr.foreground = AppTheme.mdCodeColor;
                    styles.add(sr);
                    i = end + 1;
                    continue;
                }
            }

            // Strikethrough: ~~text~~
            if (c == '~' && i + 1 < len && line.charAt(i + 1) == '~') {
                int end = line.indexOf("~~", i + 2);
                if (end > i + 1) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + i;
                    sr.length = end - i + 2;
                    sr.foreground = AppTheme.mdBoldColor;
                    sr.strikeout = true;
                    styles.add(sr);
                    i = end + 2;
                    continue;
                }
            }

            // Bold: **text** or __text__
            if ((c == '*' || c == '_') && i + 1 < len && line.charAt(i + 1) == c) {
                String marker = "" + c + c;
                int end = line.indexOf(marker, i + 2);
                if (end > i + 1) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + i;
                    sr.length = end - i + 2;
                    sr.foreground = AppTheme.mdBoldColor;
                    sr.fontStyle = SWT.BOLD;
                    styles.add(sr);
                    i = end + 2;
                    continue;
                }
            }

            // Italic: *text* or _text_ (single, not preceded by another of same)
            if ((c == '*' || c == '_') && i + 1 < len && line.charAt(i + 1) != c && line.charAt(i + 1) != ' ') {
                int end = i + 1;
                while (end < len && line.charAt(end) != c) end++;
                if (end < len && end > i + 1) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + i;
                    sr.length = end - i + 1;
                    sr.foreground = AppTheme.mdBoldColor;
                    sr.fontStyle = SWT.ITALIC;
                    styles.add(sr);
                    i = end + 1;
                    continue;
                }
            }

            // Image/Link: ![alt](url) or [text](url)
            if (c == '[' || (c == '!' && i + 1 < len && line.charAt(i + 1) == '[')) {
                int bracketStart = (c == '!') ? i + 1 : i;
                int bracketEnd = line.indexOf(']', bracketStart + 1);
                if (bracketEnd > bracketStart && bracketEnd + 1 < len && line.charAt(bracketEnd + 1) == '(') {
                    int parenEnd = line.indexOf(')', bracketEnd + 2);
                    if (parenEnd > bracketEnd + 1) {
                        // Style the text part [text]
                        StyleRange textSr = new StyleRange();
                        textSr.start = offset + i;
                        textSr.length = bracketEnd - i + 1;
                        textSr.foreground = AppTheme.mdLinkColor;
                        styles.add(textSr);
                        // Style the url part (url) with underline
                        StyleRange urlSr = new StyleRange();
                        urlSr.start = offset + bracketEnd + 1;
                        urlSr.length = parenEnd - bracketEnd;
                        urlSr.foreground = AppTheme.mdLinkColor;
                        urlSr.underline = true;
                        styles.add(urlSr);
                        i = parenEnd + 1;
                        continue;
                    }
                }
            }

            i++;
        }
    }

    // ── Code Highlighting (legacy 5-param — used by FileCompare) ─────────

    /**
     * Highlight code syntax using the merged C++/Java keyword set.
     * Used by FileCompare which doesn't track file language.
     */
    public static void highlightLine(String line, int offset, int from, int to, List<StyleRange> styles) {
        for (int i = from; i < to; ) {
            char c = line.charAt(i);

            // Block comment start /*...*/
            if (c == '/' && i + 1 < to && line.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                while (i + 1 < to && !(line.charAt(i) == '*' && line.charAt(i + 1) == '/')) i++;
                if (i + 1 < to) i += 2; else i = to;
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.commentColor;
                sr.fontStyle = SWT.ITALIC;
                styles.add(sr);
                continue;
            }

            // Line comment //
            if (c == '/' && i + 1 < to && line.charAt(i + 1) == '/') {
                StyleRange sr = new StyleRange();
                sr.start = offset + i;
                sr.length = to - i;
                sr.foreground = AppTheme.commentColor;
                sr.fontStyle = SWT.ITALIC;
                styles.add(sr);
                return;
            }

            // String or char literal
            if (c == '"' || c == '\'') {
                int start = i;
                char quote = c;
                i++;
                while (i < to && line.charAt(i) != quote) {
                    if (line.charAt(i) == '\\' && i + 1 < to) i++;
                    i++;
                }
                if (i < to) i++;
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.stringColor;
                styles.add(sr);
                continue;
            }

            // Number literal
            if (Character.isDigit(c) || (c == '.' && i + 1 < to && Character.isDigit(line.charAt(i + 1)))) {
                int start = i;
                while (i < to && (Character.isDigit(line.charAt(i))
                        || line.charAt(i) == '.' || line.charAt(i) == 'f'
                        || line.charAt(i) == 'x' || line.charAt(i) == 'X'
                        || (line.charAt(i) >= 'a' && line.charAt(i) <= 'f')
                        || (line.charAt(i) >= 'A' && line.charAt(i) <= 'F')
                        || line.charAt(i) == 'L' || line.charAt(i) == 'l'
                        || line.charAt(i) == '_')) {
                    i++;
                }
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.numberColor;
                styles.add(sr);
                continue;
            }

            // Identifier / keyword / type
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < to && Character.isJavaIdentifierPart(line.charAt(i))) i++;
                String word = line.substring(start, i);
                if (KEYWORDS.contains(word)) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + start;
                    sr.length = i - start;
                    sr.foreground = AppTheme.kwColor;
                    sr.fontStyle = SWT.BOLD;
                    styles.add(sr);
                } else if (TYPES.contains(word)) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + start;
                    sr.length = i - start;
                    sr.foreground = AppTheme.typeColor;
                    styles.add(sr);
                }
                continue;
            }

            i++;
        }
    }

    // ── Code Highlighting (language-aware) ──────────

    /**
     * Highlight code syntax with language-specific keywords, types, and comment styles.
     */
    public static void highlightLine(String line, int offset, int from, int to, Language lang, List<StyleRange> styles) {
        if (lang == Language.PLAIN || lang == Language.MARKDOWN) return;

        Set<String> kw = LANG_KEYWORDS.getOrDefault(lang, Set.of());
        Set<String> tp = LANG_TYPES.getOrDefault(lang, Set.of());
        boolean hashComment = usesHashComments(lang);
        boolean cComment = usesCStyleComments(lang);

        for (int i = from; i < to; ) {
            char c = line.charAt(i);

            // ── Hash comment: # to end of line (Python, Bash) ────────
            if (hashComment && c == '#') {
                StyleRange sr = new StyleRange();
                sr.start = offset + i;
                sr.length = to - i;
                sr.foreground = AppTheme.commentColor;
                sr.fontStyle = SWT.ITALIC;
                styles.add(sr);
                return;
            }

            // ── C-style block comment: /* ... */ ─────────────────────
            if (cComment && c == '/' && i + 1 < to && line.charAt(i + 1) == '*') {
                int start = i;
                i += 2;
                while (i + 1 < to && !(line.charAt(i) == '*' && line.charAt(i + 1) == '/')) i++;
                if (i + 1 < to) i += 2; else i = to;
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.commentColor;
                sr.fontStyle = SWT.ITALIC;
                styles.add(sr);
                continue;
            }

            // ── C-style line comment: // to end ──────────────────────
            if (cComment && c == '/' && i + 1 < to && line.charAt(i + 1) == '/') {
                StyleRange sr = new StyleRange();
                sr.start = offset + i;
                sr.length = to - i;
                sr.foreground = AppTheme.commentColor;
                sr.fontStyle = SWT.ITALIC;
                styles.add(sr);
                return;
            }

            // ── Python triple-quoted string: """ or ''' ──────────────
            if (lang == Language.PYTHON && (c == '"' || c == '\'')
                    && i + 2 < to && line.charAt(i + 1) == c && line.charAt(i + 2) == c) {
                int start = i;
                String tq = "" + c + c + c;
                int end = line.indexOf(tq, i + 3);
                if (end >= 0 && end + 3 <= to) {
                    // Triple-quoted string opens and closes on same line
                    i = end + 3;
                } else {
                    // Multi-line string starts here — style to end of line
                    i = to;
                }
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.stringColor;
                styles.add(sr);
                continue;
            }

            // ── Go raw string literal: `...` ─────────────────────────
            if (lang == Language.GO && c == '`') {
                int start = i;
                i++;
                while (i < to && line.charAt(i) != '`') i++;
                if (i < to) i++;
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.stringColor;
                styles.add(sr);
                continue;
            }

            // ── Bash variable: $VAR, ${VAR}, $1, $?, etc. ───────────
            if (lang == Language.BASH && c == '$' && i + 1 < to) {
                int start = i;
                i++;
                if (i < to && line.charAt(i) == '{') {
                    int end = line.indexOf('}', i + 1);
                    if (end >= 0 && end < to) {
                        i = end + 1;
                    } else {
                        i = to;
                    }
                } else if (i < to && line.charAt(i) == '(') {
                    // $(command) — just style the $( marker
                    i += 1;
                } else if (i < to && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) {
                    while (i < to && (Character.isLetterOrDigit(line.charAt(i)) || line.charAt(i) == '_')) i++;
                } else if (i < to) {
                    // Special variables: $?, $!, $@, $#, $0-$9
                    i++;
                }
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.preprocColor;
                styles.add(sr);
                continue;
            }

            // ── Python decorator: @name ──────────────────────────────
            if (lang == Language.PYTHON && c == '@') {
                boolean atStart = true;
                for (int j = from; j < i; j++) {
                    if (!Character.isWhitespace(line.charAt(j))) { atStart = false; break; }
                }
                if (atStart) {
                    int start = i;
                    i++;
                    while (i < to && (Character.isJavaIdentifierPart(line.charAt(i)) || line.charAt(i) == '.')) i++;
                    StyleRange sr = new StyleRange();
                    sr.start = offset + start;
                    sr.length = i - start;
                    sr.foreground = AppTheme.preprocColor;
                    styles.add(sr);
                    continue;
                }
            }

            // ── String or char literal ───────────────────────────────
            if (c == '"' || c == '\'') {
                int start = i;
                char quote = c;
                i++;
                while (i < to && line.charAt(i) != quote) {
                    // Bash single-quoted strings have no escape sequences
                    if (line.charAt(i) == '\\' && !(lang == Language.BASH && quote == '\'') && i + 1 < to) i++;
                    i++;
                }
                if (i < to) i++;
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.stringColor;
                styles.add(sr);
                continue;
            }

            // ── Number literal ───────────────────────────────────────
            if (Character.isDigit(c) || (c == '.' && i + 1 < to && Character.isDigit(line.charAt(i + 1)))) {
                int start = i;
                while (i < to && (Character.isDigit(line.charAt(i))
                        || line.charAt(i) == '.' || line.charAt(i) == 'f'
                        || line.charAt(i) == 'x' || line.charAt(i) == 'X'
                        || (line.charAt(i) >= 'a' && line.charAt(i) <= 'f')
                        || (line.charAt(i) >= 'A' && line.charAt(i) <= 'F')
                        || line.charAt(i) == 'L' || line.charAt(i) == 'l'
                        || line.charAt(i) == '_')) {
                    i++;
                }
                StyleRange sr = new StyleRange();
                sr.start = offset + start;
                sr.length = i - start;
                sr.foreground = AppTheme.numberColor;
                styles.add(sr);
                continue;
            }

            // ── Identifier / keyword / type ──────────────────────────
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < to && Character.isJavaIdentifierPart(line.charAt(i))) i++;
                String word = line.substring(start, i);
                if (kw.contains(word)) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + start;
                    sr.length = i - start;
                    sr.foreground = AppTheme.kwColor;
                    sr.fontStyle = SWT.BOLD;
                    styles.add(sr);
                } else if (tp.contains(word)) {
                    StyleRange sr = new StyleRange();
                    sr.start = offset + start;
                    sr.length = i - start;
                    sr.foreground = AppTheme.typeColor;
                    styles.add(sr);
                }
                continue;
            }

            i++;
        }
    }
}
