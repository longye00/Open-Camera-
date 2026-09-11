/* SPDX-License-Identifier: GPL-3.0-or-later */
package net.sourceforge.opencamera;
import java.util.*;

public final class CoreTests {
    private static int passed;
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++;
    }
    private static void rejects(String input) {
        boolean rejected = false;
        try { PhotoNameQueueCore.parse(input); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "reject " + input);
    }
    private static void rejectsOperation(Runnable operation, String label) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        check(rejected, label);
    }
    private static void near(float actual, float expected, String label) {
        check(Math.abs(actual - expected) < 0.05f, label + ": " + actual);
    }
    public static void main(String[] args) {
        List<String> names = PhotoNameQueueCore.parse("\ufeff材料A_CK_R1.jpg\r\n\r\n材料A_盐处理_R1.JPEG\n样本003\r");
        check(names.size() == 3, "CRLF, LF, CR and blank lines");
        check(names.get(0).equals("材料A_CK_R1"), "Chinese names and jpg extension");
        check(names.get(1).equals("材料A_盐处理_R1"), "case insensitive jpeg extension");
        check(PhotoNameQueueCore.parse(" leading and trailing spaces ").get(0).equals("leading and trailing spaces"), "outer whitespace");
        check(PhotoNameQueueCore.parse("a.b.c").get(0).equals("a.b.c"), "periods inside name");
        for (String value : new String[]{"", "  \n\r\n", ".", "..", ".hidden", "a.", "CON", "nul.jpg", "COM1.txt",
                "x/y", "x\\y", "x:y", "x*y", "x?y", "x\"y", "x<y", "x>y", "x|y", "a\tb", "x\u0001y",
                "sample\u202eexe", "a\nA.jpg", "é\ne\u0301"}) rejects(value);
        StringBuilder tooLong = new StringBuilder();
        for (int i=0; i<61; i++) tooLong.append('中');
        rejects(tooLong.toString());
        StringBuilder tooMany = new StringBuilder();
        for (int i=0; i<5001; i++) tooMany.append("sample_").append(i).append('\n');
        rejects(tooMany.toString());
        PhotoNameQueueCore.State s = PhotoNameQueueCore.State.empty().withNames(names);
        check(s.ready() && s.selected == 0 && !s.autoAdvance, "manual selection is the default");
        check(s.filename().equals("材料A_CK_R1_001.jpg"), "first numbered filename");
        for (int i = 1; i <= 3; i++) {
            s = s.begin();
            check(s.pending && s.selected == 0 && !s.ready(), "pending locks selection " + i);
            s = s.saved();
            check(s.selected == 0 && s.countFor(names.get(0)) == i, "multiple photos stay on same identifier " + i);
        }
        check(s.filename().equals("材料A_CK_R1_004.jpg"), "suffix increases on fourth photo");
        s = s.select(2);
        check(s.filename().equals("样本003_001.jpg"), "can choose any identifier directly");
        s = s.begin().saved();
        check(s.selected == 2 && s.ready(), "last identifier is not exhausted after one photo");
        s = s.select(0);
        check(s.countFor(names.get(0)) == 3 && s.nextNumber() == 4, "returning to identifier preserves count and suffix");
        s = new PhotoNameQueueCore.State(s.names, s.selected, s.enabled, s.autoAdvance, s.lastNumbers, s.savedCounts, s.pendingNumber);
        check(s.selected == 0 && s.nextNumber() == 4, "restart preserves current selection and number");
        s = s.begin(11);
        check(s.filename().endsWith("_011.jpg"), "existing names can be skipped without overwrite");
        final PhotoNameQueueCore.State locked = s;
        rejectsOperation(() -> locked.begin(), "no overlapping save");
        rejectsOperation(() -> locked.select(1), "cannot change identifier while saving");
        rejectsOperation(() -> locked.withNames(Arrays.asList("other")), "cannot replace list while saving");
        rejectsOperation(() -> locked.enable(false), "cannot bypass pending by pausing");
        rejectsOperation(() -> locked.setAutoAdvance(true), "cannot change switching mode while saving");
        s = new PhotoNameQueueCore.State(s.names, s.selected, s.enabled, s.autoAdvance, s.lastNumbers, s.savedCounts, s.pendingNumber);
        check(s.pending && s.filename().endsWith("_011.jpg"), "pending reservation survives restart");
        s = s.resolve(false);
        check(s.selected == 0 && s.countFor(names.get(0)) == 3 && s.nextNumber() == 12,
                "failed save keeps identifier/count and never reuses uncertain suffix");
        s = s.begin().resolve(true);
        check(s.selected == 0 && s.countFor(names.get(0)) == 4, "manual confirmation counts once without advancing");
        final PhotoNameQueueCore.State committed = s;
        rejectsOperation(() -> committed.saved(), "cannot commit twice");
        s = s.enable(false);
        check(!s.ready() && s.selected == 0, "pause preserves selection");
        s = s.enable(true).setAutoAdvance(true).begin().saved();
        check(s.selected == 1, "auto-advance only when explicitly enabled");
        s = s.begin().saved();
        check(s.selected == 2, "auto advance reaches next item");
        s = s.begin().saved();
        check(s.selected == 2 && s.ready(), "auto advance does not fall back to timestamps at end");
        s = s.setAutoAdvance(false).select(0);
        int oldCount = s.countFor(names.get(0)), oldNumber = s.nextNumber();
        s = s.withNames(Arrays.asList(names.get(2), names.get(0), "新编号"));
        check(s.selected == 1 && s.currentName().equals(names.get(0)), "reordered import preserves current identifier");
        check(s.countFor(names.get(0)) == oldCount && s.nextNumber() == oldNumber, "reimport preserves counters");
        s = s.withNames(Arrays.asList("临时编号"));
        s = s.withNames(names);
        check(s.countFor(names.get(0)) == oldCount && s.nextNumber() == oldNumber, "removed then reimported identifiers preserve history");
        s = s.withNames(Arrays.asList(names.get(0).toLowerCase(Locale.ROOT)));
        check(s.nextNumber() == oldNumber, "case-only rename does not reset suffixes");
        check(PhotoNameQueueCore.numberedFilename("001", 1).equals("001_001.jpg"), "numeric identifier preserved");
        check(PhotoNameQueueCore.numberedFilename("编号", 1000).equals("编号_1000.jpg"), "suffix beyond 999");
        check(PhotoNameQueueCore.serialFromFilename("样本", "样本_014.jpg") == 14, "parse saved photo suffix");
        check(PhotoNameQueueCore.serialFromFilename("A", "a_100.JPG") == 100, "case-insensitive collision recognition");
        check(PhotoNameQueueCore.serialFromFilename("A", "A_001_001.jpg") == 0, "do not mix another identifier's photos");
        check(PhotoNameQueueCore.serialFromFilename("A_001", "A_001_001.jpg") == 1, "identifier may itself end in digits");
        check(PhotoNameQueueCore.serialFromFilename("A", "A.jpg") == 0, "old bare-name JPG is untouched");
        check(PhotoNameQueueCore.serialFromFilename("A", "B_003.jpg") == 0, "unrelated files ignored");
        check(PhotoNameQueueCore.serialFromFilename("A", "A_0x1.jpg") == 0, "invalid suffix ignored");
        check(PhotoNameQueueCore.serialFromFilename("A", "A_.jpg") == 0, "empty suffix ignored");
        final PhotoNameQueueCore.State bounded = s;
        rejectsOperation(() -> bounded.select(-1), "negative selection rejected");
        rejectsOperation(() -> bounded.select(bounded.names.size()), "out of range selection rejected");
        rejectsOperation(() -> bounded.begin(0), "zero serial rejected");
        rejectsOperation(() -> bounded.begin(PhotoNameQueueCore.MAX_SERIAL + 1), "oversized serial rejected");
        Map<String,Integer> last = new HashMap<>(); last.put("A", PhotoNameQueueCore.MAX_SERIAL);
        PhotoNameQueueCore.State full = new PhotoNameQueueCore.State(Arrays.asList("A"),0,true,false,last,null,0);
        check(!full.ready(), "serial bound stops safely");
        final PhotoNameQueueCore.State fullFinal = full;
        rejectsOperation(() -> fullFinal.nextNumber(), "no overflow filename");
        PhotoNameQueueCore.State longRun = PhotoNameQueueCore.State.empty().withNames(Arrays.asList("重复拍摄"));
        for (int i=1;i<=1000;i++) {
            longRun = longRun.begin().saved();
            check(longRun.selected == 0 && longRun.countFor("重复拍摄") == i && longRun.nextNumber() == i + 1,
                    "1000-shot manual sequence " + i);
        }

        LevelGuideCore core=new LevelGuideCore();
        LevelGuideCore.Reading r=core.update(0,9.81f,0,0,100);
        check(r.valid && !r.flat && !r.aligned,"upright waits before yellow");
        r=core.update(0,9.81f,0,0,451);
        check(r.aligned,"settled line turns yellow");
        double a=Math.toRadians(1.3);
        r=core.update((float)(9.81*Math.sin(a)),(float)(9.81*Math.cos(a)),0,0,500);
        check(r.aligned,"alignment hysteresis at 1.3 degrees");
        a=Math.toRadians(2.0);
        r=core.update((float)(9.81*Math.sin(a)),(float)(9.81*Math.cos(a)),0,0,550);
        check(!r.aligned,"unlock beyond 1.6 degrees");
        core.reset();
        r=core.update(0,0,9.81f,0,100);
        check(r.flat && !r.aligned,"flat surface uses cross");
        r=core.update(0,0,9.81f,0,460);
        check(r.aligned,"flat cross aligns");
        near(r.tiltX,0,"flat x"); near(r.tiltY,0,"flat y");
        core.reset();
        r=core.update(1,0,9.76f,0,100);
        check(r.flat && r.error>5 && !r.aligned,"tilted top-down cross separated");
        near(r.tiltY,0,"tilt on one axis");
        float[][] vectors={{0,9.81f,0},{9.81f,0,0},{0,-9.81f,0},{-9.81f,0,0}};
        for(int i=0;i<4;i++) {
            core.reset();
            r=core.update(vectors[i][0],vectors[i][1],vectors[i][2],90*i,100);
            near(r.roll,0,"rotation "+(90*i));
            r=core.update(vectors[i][0],vectors[i][1],vectors[i][2],90*i,500);
            check(r.aligned,"settled rotation "+(90*i));
        }
        core.reset();
        r=core.update(0,0,-9.81f,270,100);
        check(r.flat && r.valid,"upward-facing flat camera also supported");
        check(!core.update(0,0,0,0,600).valid,"no sensor reading is not falsely level");
        check(!core.update(0,0,30,0,650).valid,"strong movement is not falsely level");
        check(!core.update(Float.NaN,0,0,0,700).valid,"NaN is not level");
        check(!core.update(Float.POSITIVE_INFINITY,0,0,0,710).valid,"infinity is not level");
        System.out.println("PASS: "+passed+" queue and level assertions");
    }
}
