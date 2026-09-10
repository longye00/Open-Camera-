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
        PhotoNameQueueCore.State s = new PhotoNameQueueCore.State(names, 0, true, false);
        check(s.ready(), "ready");
        check(s.filename().equals("材料A_CK_R1.jpg"), "first filename");
        s = s.begin();
        check(s.pending && s.next == 0 && !s.ready(), "begin does not consume name");
        boolean repeatBlocked=false;
        try {s.begin();} catch(IllegalStateException expected) {repeatBlocked=true;}
        check(repeatBlocked, "overlapping transactions blocked");
        PhotoNameQueueCore.State restored = new PhotoNameQueueCore.State(s.names, s.next, s.enabled, s.pending);
        check(restored.pending && !restored.ready(), "restart preserves uncertain save");
        s=restored.resolve(false);
        check(s.next==0 && s.ready(), "failure retry retains name");
        s=s.begin().saved();
        check(s.next==1 && !s.pending, "advance only on confirmed save");
        s=s.enable(false);
        check(!s.ready() && s.next==1, "pause preserves progress");
        s=s.enable(true).begin().resolve(true);
        check(s.next==2 && s.ready(), "manual saved confirmation advances once");
        s=s.begin().saved();
        check(!s.ready() && s.next==3, "stop at end, no timestamp fallback");
        s=s.seek(1);
        check(s.next==1 && s.ready(), "explicit manual seek");
        boolean duplicateCommitBlocked=false;
        try{s.saved();}catch(IllegalStateException expected){duplicateCommitBlocked=true;}
        check(duplicateCommitBlocked,"cannot commit twice");

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
