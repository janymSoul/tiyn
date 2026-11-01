package kz.tiyn.media;

final class ExifOrientation {

    private static final int TAG_ORIENTATION = 0x0112;

    private ExifOrientation() {}

    static int read(byte[] jpeg) {
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 1;
            }
            int length = u16(jpeg, i + 2, true);
            if (marker == 0xE1 && length >= 16 && startsWithExif(jpeg, i + 4)) {
                return orientationIn(jpeg, i + 10, i + 2 + length);
            }
            i += 2 + length;
        }
        return 1;
    }

    private static int orientationIn(byte[] b, int tiff, int end) {
        if (tiff + 8 > end || end > b.length) {
            return 1;
        }
        boolean big = b[tiff] == 'M';
        int ifd = tiff + u32(b, tiff + 4, big);
        if (ifd < tiff || ifd + 2 > end) {
            return 1;
        }
        int entries = u16(b, ifd, big);
        for (int n = 0; n < entries; n++) {
            int entry = ifd + 2 + n * 12;
            if (entry + 12 > end) {
                return 1;
            }
            if (u16(b, entry, big) == TAG_ORIENTATION) {
                return u16(b, entry + 8, big);
            }
        }
        return 1;
    }

    private static boolean startsWithExif(byte[] b, int at) {
        return b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f' && b[at + 4] == 0;
    }

    private static int u16(byte[] b, int at, boolean big) {
        int hi = b[at] & 0xFF;
        int lo = b[at + 1] & 0xFF;
        return big ? (hi << 8) | lo : (lo << 8) | hi;
    }

    private static int u32(byte[] b, int at, boolean big) {
        int a = u16(b, at, big);
        int c = u16(b, at + 2, big);
        return big ? (a << 16) | c : (c << 16) | a;
    }
}
