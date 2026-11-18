package com.google.android.projection.protocol;

import hmgapi.engine.MemRecipe;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/**
 * The released HMG-API engine applies EV and the proven beta101 TBT recipe in
 * both orders. CI uses synthetic library bytes and recalculated guard hashes;
 * a separately supplied private stock library also tests the shipped hashes.
 * No library file is written, and this does not execute ARM code or prove an
 * on-car EV session. NativeCheck is the mod's actual read-back implementation.
 */
public final class CeevNativeRecipeTest {
    private static final long BASE = 0x10000000L, TEXT_END = 0x1a60d4L;
    private static final long CODE = BASE + 0x300120L;
    private static final String STOCK_MD5 = "dbfe7bd2e36152a50f97fed612cf6326";
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
            // The real TBT recipe keeps this page offset at a relocated mapping.
            return CODE - 0x120L;
        }
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), "UTF-8");
    }

    private static String nativeRecipe(String evText, Memory memory) throws Exception {
        return NativeCheck.find(Collections.singletonList(NativeCheck.parse(evText)), memory, new StringBuilder());
    }

    private static void apply(MemRecipe recipe, Memory memory, String md5) {
        MemRecipe.Result result = recipe.apply(memory, BASE, TEXT_END, md5);
        check(result.status == MemRecipe.Result.APPLIED, result.message);
    }

    private static void geometry(MemRecipe ev, MemRecipe tbt) {
        check("ev-sensor-dbfe7bd2".equals(ev.id), "the Canadian Ioniq 6 EV recipe");
        check("aa-tbt-dbfe7bd2".equals(tbt.id), "the working Canadian Ioniq 6 TBT recipe");
        check(ev.file.equals(tbt.file), "both recipes use the same library");
        check(ev.verifies.size() == 4 && ev.patchAt.size() == 3, "four EV guards and three patches");
        check(ev.segment == null && ev.branches.isEmpty(), "EV adds no executable segment");
        check(tbt.verifies.size() == 8 && tbt.branches.size() == 1, "beta101 TBT guard geometry");
        check(tbt.segPreferred == 0x1c4120L, "TBT segment page offset");
        for (long[] a : ev.regions()) for (long[] b : tbt.regions())
            check(a[1] <= b[0] || b[1] <= a[0], "EV/TBT verify regions never overlap");
        check(Arrays.equals(ev.patchBytes.get(2), MemRecipe.branchW(0xb18acL, 0xaf50aL)),
                "EV reportDiagnosticsData branches to this build's queueOutgoing");
    }

    /** Recalculate only fingerprints for the public synthetic memory fixture. */
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

    private static void exercise(byte[] stock, String evText, String tbtText, String mode) throws Exception {
        MemRecipe ev = MemRecipe.parse(evText), tbt = MemRecipe.parse(tbtText);
        String md5 = MemRecipe.md5(stock);
        Memory evFirst = new Memory(stock), tbtFirst = new Memory(stock);
        check(nativeRecipe(evText, evFirst) == null, mode + ": stock must not advertise sensor 23");
        apply(ev, evFirst, md5);
        check(ev.id.equals(nativeRecipe(evText, evFirst)), mode + ": EV read-back succeeds");
        apply(tbt, evFirst, md5);
        apply(tbt, tbtFirst, md5);
        check(nativeRecipe(evText, tbtFirst) == null, mode + ": TBT alone is not EV-ready");
        apply(ev, tbtFirst, md5);
        check(Arrays.equals(evFirst.library, tbtFirst.library), mode + ": library independent of mod order");
        check(Arrays.equals(evFirst.code, tbtFirst.code), mode + ": TBT code independent of mod order");
        check(ev.id.equals(nativeRecipe(evText, evFirst)) && ev.id.equals(nativeRecipe(evText, tbtFirst)),
                mode + ": EV read-back survives TBT in either order");

        // The engine owns these verify regions. Every wrong fingerprint must
        // reject before any writes or anonymous executable mappings occur.
        for (MemRecipe.Verify guard : ev.verifies) {
            Memory corrupt = new Memory(stock);
            corrupt.library[(int) guard.off] ^= 1;
            byte[] before = corrupt.library.clone();
            check(ev.apply(corrupt, BASE, TEXT_END, null).status == MemRecipe.Result.MISMATCH,
                    mode + ": changed EV guard rejects");
            check(corrupt.writes == 0 && corrupt.maps == 0 && Arrays.equals(before, corrupt.library),
                    mode + ": rejection makes no changes");
        }
        // A partial native apply must never allow the sender to use the
        // diagnostics method as a raw-message channel.
        for (int i = 0; i < ev.patchAt.size(); i++) {
            Memory partial = new Memory(evFirst.library);
            int offset = (int) ev.patchAt.get(i)[0], length = ev.patchBytes.get(i).length;
            System.arraycopy(stock, offset, partial.library, offset, length);
            check(nativeRecipe(evText, partial) == null, mode + ": missing EV patch fails read-back");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("EV recipe, beta101 TBT recipe, optional private stock library");
        String evText = read(args[0]), tbtText = read(args[1]);
        geometry(MemRecipe.parse(evText), MemRecipe.parse(tbtText));
        byte[] fixture = new byte[0x1c1000];
        new Random(260206L).nextBytes(fixture);
        exercise(fixture, synthetic(evText, fixture), synthetic(tbtText, fixture), "synthetic");
        System.out.println("CEEV synthetic recipe coexistence/read-back: " + checks + " checks passed");
        if (args.length == 3) {
            int before = checks;
            byte[] stock = Files.readAllBytes(Paths.get(args[2]));
            check(STOCK_MD5.equals(MemRecipe.md5(stock)), "exact CEEV stock library required");
            exercise(stock, evText, tbtText, "private stock");
            check(Arrays.equals(stock, Files.readAllBytes(Paths.get(args[2]))), "private stock file remains unchanged");
            System.out.println("CEEV exact-stock recipe coexistence/read-back: " + (checks - before) + " checks passed");
        } else System.out.println("CEEV exact-stock checks skipped (set CEEV_STOCK_LIBRARY; no firmware is committed)");
    }
}
