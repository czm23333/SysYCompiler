import org.llvm4j.llvm4j.Module;

public class LLVMPassManager {
    private final Module module;

    public LLVMPassManager(Module module) {
        this.module = module;
    }

    public void run() {
        boolean flag = true;
        while (flag) {
            flag = new LLVMCPPass(module).run();
            flag |= new LLVMUVPass(module).run();
            flag |= new LLVMDCEPass(module).run();
        }
    }
}