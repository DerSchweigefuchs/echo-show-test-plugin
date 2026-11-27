package echoshow.kiosktest;

import java.nio.charset.StandardCharsets;

/**
 * Self-contained ISO/IEC 18004 QR Code encoder (Byte mode, ECC Level M, Versions 1–9).
 * Pure Java, Android API 24 compatible, zero external dependencies.
 */
final class QrCode {
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    // Versions 1..9 at ECC Level M: {totalCodewords, ecPerBlock, b1Count, b1Data, b2Count, b2Data}
    private static final int[][] V_M = {
        null,
        {26,  10, 1, 16, 0, 0},   // V1:  21x21, max 14 bytes
        {44,  16, 1, 28, 0, 0},   // V2:  25x25, max 26 bytes
        {70,  26, 1, 44, 0, 0},   // V3:  29x29, max 42 bytes
        {100, 18, 2, 32, 0, 0},   // V4:  33x33, max 62 bytes
        {134, 24, 2, 43, 0, 0},   // V5:  37x37, max 84 bytes
        {172, 16, 4, 27, 0, 0},   // V6:  41x41, max 106 bytes
        {196, 18, 4, 31, 0, 0},   // V7:  45x45, max 122 bytes
        {242, 22, 2, 38, 2, 39},  // V8:  49x49, max 152 bytes
        {292, 22, 3, 36, 2, 37},  // V9:  53x53, max 180 bytes
    };

    private static final int[][] ALIGN = {
        null,
        {},
        {6, 18},
        {6, 22},
        {6, 26},
        {6, 30},
        {6, 34},
        {6, 22, 38},
        {6, 24, 42},
        {6, 26, 46},
    };

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            EXP[i + 255] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
    }

    private QrCode() {}

    /**
     * Encodes {@code text} as UTF-8 bytes at ECC Level M.
     * Returns {@code boolean[size][size]} where {@code [row][col] == true} is a dark module.
     */
    static boolean[][] encode(String text) {
        byte[] payload = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        int ver = -1;
        for (int v = 1; v < V_M.length; v++) {
            int dataCap = V_M[v][2] * V_M[v][3] + V_M[v][4] * V_M[v][5];
            if (payload.length <= dataCap - 2) {
                ver = v;
                break;
            }
        }
        if (ver < 0) throw new IllegalArgumentException("QR payload too long: " + payload.length);

        int[] spec = V_M[ver];
        int totalCw = spec[0], ecPer = spec[1], b1 = spec[2], d1 = spec[3], b2 = spec[4], d2 = spec[5];
        int dataCap = b1 * d1 + b2 * d2;

        // 1. Build data bit stream: mode=0100 (4 bits), count (8 bits), payload, terminator, padding
        int[] dataCw = new int[dataCap];
        int bitPos = 0;
        bitPos = putBits(dataCw, bitPos, 0x4, 4);
        bitPos = putBits(dataCw, bitPos, payload.length, 8);
        for (byte b : payload) bitPos = putBits(dataCw, bitPos, b & 0xFF, 8);
        int term = Math.min(4, dataCap * 8 - bitPos);
        bitPos = putBits(dataCw, bitPos, 0, term);
        if ((bitPos & 7) != 0) bitPos = (bitPos + 7) & ~7;
        int cwIdx = bitPos / 8;
        boolean alt = true;
        while (cwIdx < dataCap) {
            dataCw[cwIdx++] = alt ? 0xEC : 0x11;
            alt = !alt;
        }

        // 2. Split into blocks and compute Reed-Solomon ECC
        int blocks = b1 + b2;
        int[][] dataBlocks = new int[blocks][];
        int[][] eccBlocks = new int[blocks][];
        int[] gen = rsGenerator(ecPer);
        int pos = 0;
        for (int i = 0; i < blocks; i++) {
            int len = i < b1 ? d1 : d2;
            int[] blk = new int[len];
            System.arraycopy(dataCw, pos, blk, 0, len);
            pos += len;
            dataBlocks[i] = blk;
            eccBlocks[i] = rsRemainder(blk, gen, ecPer);
        }

        // 3. Interleave data and ECC codewords
        byte[] allBytes = new byte[totalCw];
        int out = 0;
        int maxD = Math.max(d1, d2);
        for (int c = 0; c < maxD; c++) {
            for (int i = 0; i < blocks; i++) {
                if (c < dataBlocks[i].length) allBytes[out++] = (byte) dataBlocks[i][c];
            }
        }
        for (int c = 0; c < ecPer; c++) {
            for (int i = 0; i < blocks; i++) {
                allBytes[out++] = (byte) eccBlocks[i][c];
            }
        }

        // 4. Build function pattern matrix & place data bits
        int n = 17 + 4 * ver;
        boolean[][] isFunc = new boolean[n][n];
        boolean[][] base = new boolean[n][n];
        placeFunctionPatterns(ver, n, base, isFunc);
        placeCodewords(n, allBytes, base, isFunc);

        // 5. Evaluate all 8 masks and pick lowest ISO 18004 penalty
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        boolean[][] bestGrid = null;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] cand = new boolean[n][n];
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    cand[r][c] = isFunc[r][c] ? base[r][c] : (base[r][c] ^ maskBit(mask, r, c));
                }
            }
            placeFormatInfo(n, mask, cand);
            if (ver >= 7) placeVersionInfo(n, ver, cand);
            int pen = penalty(n, cand);
            if (pen < bestPenalty) {
                bestPenalty = pen;
                bestMask = mask;
                bestGrid = cand;
            }
        }
        return bestGrid;
    }

    private static int putBits(int[] buf, int bitPos, int val, int count) {
        for (int i = count - 1; i >= 0; i--) {
            int idx = bitPos >>> 3;
            int shift = 7 - (bitPos & 7);
            if (((val >>> i) & 1) != 0) buf[idx] |= (1 << shift);
            bitPos++;
        }
        return bitPos;
    }

    private static int gfMul(int a, int b) {
        if (a == 0 || b == 0) return 0;
        return EXP[(LOG[a] + LOG[b]) % 255];
    }

    private static int[] rsGenerator(int deg) {
        int[] g = new int[deg + 1];
        g[0] = 1;
        for (int i = 0; i < deg; i++) {
            int root = EXP[i];
            for (int j = i + 1; j >= 1; j--) {
                g[j] = g[j] ^ gfMul(g[j - 1], root);
            }
        }
        return g;
    }

    private static int[] rsRemainder(int[] data, int[] gen, int ecLen) {
        int[] rem = new int[ecLen];
        for (int d : data) {
            int factor = d ^ rem[0];
            System.arraycopy(rem, 1, rem, 0, ecLen - 1);
            rem[ecLen - 1] = 0;
            for (int i = 0; i < ecLen; i++) {
                rem[i] ^= gfMul(gen[i + 1], factor);
            }
        }
        return rem;
    }

    private static void placeFunctionPatterns(int ver, int n, boolean[][] m, boolean[][] f) {
        // Three 7x7 finder patterns + 1-module separators
        finder(0, 0, n, m, f);
        finder(0, n - 7, n, m, f);
        finder(n - 7, 0, n, m, f);

        // Alignment patterns
        int[] pos = ALIGN[ver];
        for (int r : pos) {
            for (int c : pos) {
                if ((r < 9 && c < 9) || (r < 9 && c > n - 10) || (r > n - 10 && c < 9)) continue;
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        int d = Math.max(Math.abs(dr), Math.abs(dc));
                        m[r + dr][c + dc] = d != 1;
                        f[r + dr][c + dc] = true;
                    }
                }
            }
        }

        // Timing patterns
        for (int i = 8; i < n - 8; i++) {
            boolean dark = (i & 1) == 0;
            m[6][i] = dark; f[6][i] = true;
            m[i][6] = dark; f[i][6] = true;
        }

        // Dark module
        m[n - 8][8] = true;
        f[n - 8][8] = true;

        // Reserve format info strips
        for (int i = 0; i < 9; i++) {
            if (i != 6) {
                f[8][i] = true;
                f[i][8] = true;
            }
        }
        for (int i = 0; i < 8; i++) {
            f[8][n - 1 - i] = true;
            if (i < 7) f[n - 1 - i][8] = true;
        }

        // Reserve version info strips (V7+)
        if (ver >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    f[i][n - 11 + j] = true;
                    f[n - 11 + j][i] = true;
                }
            }
        }
    }

    private static void finder(int r0, int c0, int n, boolean[][] m, boolean[][] f) {
        for (int dr = -1; dr <= 7; dr++) {
            for (int dc = -1; dc <= 7; dc++) {
                int r = r0 + dr, c = c0 + dc;
                if (r < 0 || r >= n || c < 0 || c >= n) continue;
                int dist = Math.max(Math.abs(dr - 3), Math.abs(dc - 3));
                m[r][c] = dist != 2 && dist != 4;
                f[r][c] = true;
            }
        }
    }

    private static void placeCodewords(int n, byte[] cw, boolean[][] m, boolean[][] f) {
        int bitIdx = 0;
        int totalBits = cw.length * 8;
        boolean up = true;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int step = 0; step < n; step++) {
                int r = up ? (n - 1 - step) : step;
                for (int dx = 0; dx < 2; dx++) {
                    int c = right - dx;
                    if (!f[r][c]) {
                        boolean dark = false;
                        if (bitIdx < totalBits) {
                            dark = ((cw[bitIdx >>> 3] >>> (7 - (bitIdx & 7))) & 1) != 0;
                            bitIdx++;
                        }
                        m[r][c] = dark;
                    }
                }
            }
            up = !up;
        }
    }

    private static boolean maskBit(int mask, int r, int c) {
        switch (mask) {
            case 0: return ((r + c) & 1) == 0;
            case 1: return (r & 1) == 0;
            case 2: return c % 3 == 0;
            case 3: return (r + c) % 3 == 0;
            case 4: return (((r / 2) + (c / 3)) & 1) == 0;
            case 5: return ((r * c) % 2 + (r * c) % 3) == 0;
            case 6: return (((r * c) % 2 + (r * c) % 3) & 1) == 0;
            case 7: return (((r + c) % 2 + (r * c) % 3) & 1) == 0;
            default: return false;
        }
    }

    private static void placeFormatInfo(int n, int mask, boolean[][] m) {
        int data = mask; // ECC Level M = 0b00, so (0 << 3) | mask == mask
        int rem = data << 10;
        for (int i = 4; i >= 0; i--) {
            if (((rem >>> (i + 10)) & 1) != 0) rem ^= (0x537 << i);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;

        // Top-left horizontal + vertical
        for (int i = 0; i <= 5; i++) m[8][i] = bit(bits, 14 - i);
        m[8][7] = bit(bits, 8);
        m[8][8] = bit(bits, 7);
        m[7][8] = bit(bits, 6);
        for (int i = 9; i <= 14; i++) m[14 - i][8] = bit(bits, 14 - i);

        // Bottom-left vertical + top-right horizontal
        for (int i = 0; i < 7; i++) m[n - 1 - i][8] = bit(bits, 14 - i);
        for (int i = 7; i < 15; i++) m[8][n - 15 + i] = bit(bits, 14 - i);
    }

    private static void placeVersionInfo(int n, int ver, boolean[][] m) {
        int rem = ver << 12;
        for (int i = 5; i >= 0; i--) {
            if (((rem >>> (i + 12)) & 1) != 0) rem ^= (0x1F25 << i);
        }
        int bits = (ver << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean dark = ((bits >>> i) & 1) != 0;
            int a = n - 11 + (i % 3);
            int b = i / 3;
            m[b][a] = dark;
            m[a][b] = dark;
        }
    }

    private static boolean bit(int val, int pos) {
        return ((val >>> pos) & 1) != 0;
    }

    private static int penalty(int n, boolean[][] m) {
        int pen = 0;
        // N1: 5+ consecutive same-color modules in row/col
        for (int r = 0; r < n; r++) {
            int runR = 0, runC = 0;
            boolean prevR = false, prevC = false;
            for (int c = 0; c < n; c++) {
                boolean vr = m[r][c];
                if (c == 0 || vr != prevR) { runR = 1; prevR = vr; }
                else { runR++; if (runR == 5) pen += 3; else if (runR > 5) pen += 1; }
                boolean vc = m[c][r];
                if (c == 0 || vc != prevC) { runC = 1; prevC = vc; }
                else { runC++; if (runC == 5) pen += 3; else if (runC > 5) pen += 1; }
            }
        }
        // N2: 2x2 blocks of same color
        for (int r = 0; r < n - 1; r++) {
            for (int c = 0; c < n - 1; c++) {
                boolean v = m[r][c];
                if (m[r][c + 1] == v && m[r + 1][c] == v && m[r + 1][c + 1] == v) pen += 3;
            }
        }
        // N3: finder-like 1:1:3:1:1 patterns
        for (int r = 0; r < n; r++) {
            for (int c = 0; c <= n - 11; c++) {
                if (isFinderPattern(m, r, c, true)) pen += 40;
                if (isFinderPattern(m, c, r, false)) pen += 40;
            }
        }
        // N4: dark module balance around 50%
        int dark = 0;
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) if (m[r][c]) dark++;
        int pct = (dark * 100) / (n * n);
        int k = Math.abs(pct - 50) / 5;
        pen += k * 10;
        return pen;
    }

    private static boolean isFinderPattern(boolean[][] m, int r, int c, boolean horiz) {
        // 10111010000 or 00001011101
        boolean a = true;
        for (int i = 0; i < 11; i++) {
            boolean v = horiz ? m[r][c + i] : m[r + i][c];
            boolean p1 = (i == 0 || i == 2 || i == 3 || i == 4 || i == 6);
            if (v != p1) { a = false; break; }
        }
        if (a) return true;
        for (int i = 0; i < 11; i++) {
            boolean v = horiz ? m[r][c + i] : m[r + i][c];
            boolean p2 = (i == 4 || i == 6 || i == 7 || i == 8 || i == 10);
            if (v != p2) return false;
        }
        return true;
    }
}
