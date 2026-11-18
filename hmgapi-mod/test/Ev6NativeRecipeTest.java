package com.google.android.projection.protocol;

import hmgapi.engine.MemRecipe;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/**
 * Released-engine coexistence checks for the Canadian EV6 260206 recipes.
 * Synthetic memory tests recipe composition, not the manufacturer fingerprints.
 * An optional private stock library additionally checks the shipped fingerprints
 * and executable-segment bounds. This never writes firmware or executes ARM code.
 */
public final class Ev6NativeRecipeTest {
    private static final long BASE = 0x10000000L, CODE = BASE + 0x300120L;
    private static final String STOCK_MD5 = "48ee793af8b86052503b8c952b034731";
    private static int checks;

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final class Memory implements MemRecipe.Memory, NativeCheck.Memory {
        final byte[] library;
        byte[] code;
        int writes, maps;

        Memory(byte[] stock) { library = stock.clone(); }

        public byte[] read(long offset, int length) {
            if (offset < 0 || length < 0 || offset + length > library.length)
                throw new AssertionError("read outside the library");
            return Arrays.copyOfRange(library, (int) offset, (int) offset + length);
        }

        public byte[] peek(long address, int length) {
            if (code != null && address >= CODE && address + length <= CODE + code.length)
                return Arrays.copyOfRange(code, (int) (address - CODE), (int) (address - CODE) + length);
            return read(address - BASE, length);
        }

        public boolean poke(long address, byte[] bytes) {
            writes++;
            if (address == CODE) code = bytes.clone();
            else System.arraycopy(bytes, 0, library, (int) (address - BASE), bytes.length);
            return true;
        }

        public long mapCode(long hint, int length) {
            maps++;
            // Exercise relocation away from the preferred address, preserving
            // the segment's page offset as MemRecipe.placeSegment requires.
            return CODE - 0x120L;
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
    }

    private static String nativeRecipe(String text, Memory memory) throws Exception {
        return NativeCheck.find(Collections.singletonList(NativeCheck.parse(text)), memory, new StringBuilder());
    }

    private static void apply(MemRecipe recipe, Memory memory, long textEnd, String md5) {
        MemRecipe.Result result = recipe.apply(memory, BASE, textEnd, md5);
        check(result.status == MemRecipe.Result.APPLIED, result.message);
    }

    private static void geometry(MemRecipe ev, MemRecipe tbt) {
        check("ev-sensor-48ee793a".equals(ev.id), "the Canadian EV6 EV recipe");
        check("aa-tbt-48ee793a".equals(tbt.id), "the official EV6 TBT recipe");
        check(ev.file.equals(tbt.file), "both recipes target the same library");
        check(ev.verifies.size() == 4 && ev.patchAt.size() == 3, "four EV guards and three patches");
        check(ev.segment == null && ev.branches.isEmpty(), "EV adds no executable segment");
        check(tbt.verifies.size() == 3 && tbt.branches.size() == 1, "official TBT guard geometry");
        check(tbt.segPreferred == 0x1c4120L && tbt.rel32.size() == 5, "TBT segment and five import slots");
        for (long[] a : ev.regions()) for (long[] b : tbt.regions())
            check(a[1] <= b[0] || b[1] <= a[0], "EV/TBT verify regions never overlap");
        check(ev.patchAt.get(2)[0] == 0xbb638L &&
                Arrays.equals(ev.patchBytes.get(2), MemRecipe.branchW(0xbb638L, 0xb1972L)),
                "EV reportDiagnosticsData branches to this build's queueOutgoing");
    }

    /** Change only the guard fingerprints for public, random synthetic memory. */
    private static String synthetic(String text, byte[] stock) {
        MemRecipe recipe = MemRecipe.parse(text);
        byte[] patched = stock.clone();
        for (int i = 0; i < recipe.patchAt.size(); i++)
            System.arraycopy(recipe.patchBytes.get(i), 0, patched,
                    (int) recipe.patchAt.get(i)[0], recipe.patchBytes.get(i).length);
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String[] words = line.trim().split("\\s+");
            if ("verify".equals(words[0])) {
                int offset = Integer.decode(words[1]), size = Integer.decode(words[2]);
                out.append("verify ").append(words[1]).append(' ').append(size).append(' ')
                        .append(MemRecipe.md5(Arrays.copyOfRange(stock, offset, offset + size))).append(' ')
                        .append("-".equals(words[4]) ? "-"
                                : MemRecipe.md5(Arrays.copyOfRange(patched, offset, offset + size)));
            } else out.append(line);
            out.append('\n');
        }
        return out.toString();
    }

    private static void exercise(byte[] stock, long textEnd, String evText, String tbtText, String mode) throws Exception {
        MemRecipe ev = MemRecipe.parse(evText), tbt = MemRecipe.parse(tbtText);
        String md5 = MemRecipe.md5(stock);
        Memory evFirst = new Memory(stock), tbtFirst = new Memory(stock);
        check(nativeRecipe(evText, evFirst) == null, mode + ": stock cannot advertise sensor 23");
        apply(ev, evFirst, textEnd, md5);
        check(ev.id.equals(nativeRecipe(evText, evFirst)), mode + ": EV read-back succeeds");
        apply(tbt, evFirst, textEnd, md5);
        apply(tbt, tbtFirst, textEnd, md5);
        check(nativeRecipe(evText, tbtFirst) == null, mode + ": TBT alone is not EV-ready");
        apply(ev, tbtFirst, textEnd, md5);
        check(Arrays.equals(evFirst.library, tbtFirst.library), mode + ": library independent of mod order");
        check(Arrays.equals(evFirst.code, tbtFirst.code), mode + ": relocated TBT code independent of mod order");
        check(ev.id.equals(nativeRecipe(evText, evFirst)) && ev.id.equals(nativeRecipe(evText, tbtFirst)),
                mode + ": EV read-back survives TBT in either order");

        // Test every guard in both recipes. A mismatch must not allocate code,
        // write a single byte, or expose the EV raw-message channel.
        for (MemRecipe recipe : new MemRecipe[] { ev, tbt }) {
            for (MemRecipe.Verify guard : recipe.verifies) {
                Memory corrupt = new Memory(stock);
                corrupt.library[(int) guard.off] ^= 1;
                byte[] before = corrupt.library.clone();
                check(recipe.apply(corrupt, BASE, textEnd, null).status == MemRecipe.Result.MISMATCH,
                        mode + ": changed " + recipe.id + " guard rejects");
                check(corrupt.writes == 0 && corrupt.maps == 0 && Arrays.equals(before, corrupt.library),
                        mode + ": guard rejection makes no changes");
                check(nativeRecipe(evText, corrupt) == null, mode + ": guard rejection leaves EV sending off");
            }
        }
        // Read-back must reject every possible missing EV patch, even after
        // the companion's TBT recipe has succeeded.
        for (int i = 0; i < ev.patchAt.size(); i++) {
            Memory partial = new Memory(evFirst.library);
            int offset = (int) ev.patchAt.get(i)[0], length = ev.patchBytes.get(i).length;
            System.arraycopy(stock, offset, partial.library, offset, length);
            check(nativeRecipe(evText, partial) == null, mode + ": missing EV patch fails read-back");
        }
    }

    /** Use the private ELF's executable load bounds, not Ioniq 6 bounds. */
    private static long textEnd(byte[] elf) {
        check(elf.length >= 52 && elf[0] == 0x7f && elf[1] == 'E' && elf[2] == 'L' && elf[3] == 'F'
                && elf[4] == 1 && elf[5] == 1, "stock is little-endian ELF32");
        ByteBuffer header = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN);
        check((header.getShort(18) & 0xffff) == 40, "stock ELF is ARM");
        long phoff = header.getInt(28) & 0xffffffffL;
        int stride = header.getShort(42) & 0xffff, count = header.getShort(44) & 0xffff;
        check(stride >= 32 && count > 0 && phoff + (long) stride * count <= elf.length,
                "stock ELF program headers fit the file");
        long end = 0;
        for (int i = 0; i < count; i++) {
            int p = (int) (phoff + (long) i * stride);
            if (header.getInt(p) != 1 || (header.getInt(p + 24) & 1) == 0) continue;
            long offset = header.getInt(p + 4) & 0xffffffffL;
            long address = header.getInt(p + 8) & 0xffffffffL;
            long size = header.getInt(p + 16) & 0xffffffffL;
            check(address == offset && offset + size <= elf.length,
                    "executable load segment matches recipe file offsets");
            end = Math.max(end, offset + size);
        }
        check(end > 0, "stock ELF has executable code");
        return end;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("EV recipe, official EV6 TBT recipe, optional private stock library");
        String evText = read(args[0]), tbtText = read(args[1]);
        geometry(MemRecipe.parse(evText), MemRecipe.parse(tbtText));
        byte[] fixture = new byte[0x1c1000];
        new Random(260206L).nextBytes(fixture);
        exercise(fixture, fixture.length, synthetic(evText, fixture), synthetic(tbtText, fixture), "synthetic");
        System.out.println("EV6 synthetic recipe coexistence/read-back: " + checks + " checks passed");
        if (args.length == 3) {
            int before = checks;
            byte[] stock = Files.readAllBytes(Paths.get(args[2]));
            check(STOCK_MD5.equals(MemRecipe.md5(stock)), "exact EV6 stock library required");
            exercise(stock, textEnd(stock), evText, tbtText, "private stock");
            check(Arrays.equals(stock, Files.readAllBytes(Paths.get(args[2]))), "private stock file remains unchanged");
            System.out.println("EV6 exact-stock recipe coexistence/read-back: " + (checks - before) + " checks passed");
        } else System.out.println("EV6 exact-stock checks skipped (set EV6_STOCK_LIBRARY; no firmware is committed)");
    }
}
