package dev.ody.pikmincontrol;

public final class SeedPreviewStateTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static final String OK="v1\t10000\t123\t157.0\tready\t1\t12\t255\t40\t12";
    public static void main(String[] args){
        SeedPreviewState state=new SeedPreviewState("on\n",OK,"123","123",11000);
        check(state.desired&&state.fresh&&state.compatible&&state.applied);
        check(!new SeedPreviewState("on",OK,"123","124",11000).applied);
        check(!new SeedPreviewState("on",OK,"123","123",16000).fresh);
        check(!new SeedPreviewState("on",OK,"123","123",9999).fresh);
        check(!new SeedPreviewState("on",OK.replace("157.0","156.0"),"123","123",11000).compatible);
        check(!new SeedPreviewState("on",OK.replace("ready","incompatible"),"123","123",11000).compatible);
        check(new SeedPreviewState("on",OK.replace("ready","waiting-cards"),"123","123",11000).applied);
        check(!new SeedPreviewState("on",OK+"\textra","123","123",11000).fresh);
        check(!new SeedPreviewState("on",OK,"__PCC_PID_NOT_RUNNING__","__PCC_PID_NOT_RUNNING__",11000).fresh);
        check(!new SeedPreviewState("on",OK.replace("\t12\t255", "\tbad\t255"),"123","123",11000).fresh);
        check(new SeedPreviewState("off",OK,"123","123",11000).message().contains("正在恢復"));
        check(new SeedPreviewState("on","","123","123",11000).message().contains("等待"));
        check(new SeedPreviewState("off","","123","123",11000).message().contains("等待"));
        check(!new SeedPreviewState("on",OK.replace("\t255\t","\t256\t"),"123","123",11000).fresh);
        check(!new SeedPreviewState("on",OK.replace("\t12\t255","\t129\t255"),"123","123",11000).fresh);
        check(!new SeedPreviewState("on",OK.replace("\t40\t","\t4294967296\t"),"123","123",11000).fresh);
    }
}
