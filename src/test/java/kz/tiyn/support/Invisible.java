package kz.tiyn.support;

public final class Invisible {

    public static final String NUL = of(0x0000);
    public static final String BEL = of(0x0007);
    public static final String ESC = of(0x001B);
    public static final String DEL = of(0x007F);
    public static final String NEL = of(0x0085);
    public static final String NBSP = of(0x00A0);
    public static final String IDEOGRAPHIC_SPACE = of(0x3000);
    public static final String EM_SPACE = of(0x2003);
    public static final String LINE_SEPARATOR = of(0x2028);
    public static final String ZWSP = of(0x200B);
    public static final String ZWNJ = of(0x200C);
    public static final String ZWJ = of(0x200D);
    public static final String WORD_JOINER = of(0x2060);
    public static final String BYTE_ORDER_MARK = of(0xFEFF);
    public static final String LRM = of(0x200E);
    public static final String RLM = of(0x200F);
    public static final String ALM = of(0x061C);
    public static final String LRE = of(0x202A);
    public static final String RLE = of(0x202B);
    public static final String PDF = of(0x202C);
    public static final String LRO = of(0x202D);
    public static final String RLO = of(0x202E);
    public static final String LRI = of(0x2066);
    public static final String PDI = of(0x2069);
    public static final String LONE_SURROGATE = of(0xD800);
    public static final String EMOJI = of(0x1F600);

    private Invisible() {}

    private static String of(int codePoint) {
        return new String(Character.toChars(codePoint));
    }
}
