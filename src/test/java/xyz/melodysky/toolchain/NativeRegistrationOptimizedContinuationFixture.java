package xyz.melodysky.toolchain;

/** Emits offset-varied but structurally fixed volatile continuation evidence. */
final class NativeRegistrationOptimizedContinuationFixture {
    void appendRoute(
            StringBuilder assembly,
            TargetTriple target,
            NativeRegistrationPostCallRecipe recipe) {
        if (target.archClassifier().equals("x64")) {
            appendX64Route(assembly, recipe);
        } else {
            appendArm64Route(assembly, recipe);
        }
    }

    void appendChunk(
            StringBuilder assembly,
            TargetTriple target,
            NativeRegistrationChunkPostCallVariant variant) {
        if (target.archClassifier().equals("x64")) {
            appendX64Chunk(assembly, variant);
        } else {
            appendArm64Chunk(assembly, variant);
        }
    }

    private void appendX64Route(
            StringBuilder assembly,
            NativeRegistrationPostCallRecipe recipe) {
        switch (recipe) {
            case XOR_JINT -> assembly.append(
                    "\tmovl\t%eax, -4(%rsp)\n\tmovq\t%r10, -16(%rsp)\n"
                            + "\tmovq\t-16(%rsp), %r10\n\tmovl\t-4(%rsp), %eax\n");
            case ADD_JLONG -> assembly.append(
                    "\tmovslq\t%eax, %rax\n\tmovq\t%rax, -8(%rsp)\n"
                            + "\tmovq\t%r10, -16(%rsp)\n\tmovq\t-8(%rsp), %rax\n"
                            + "\tmovq\t-16(%rsp), %r10\n\tshrq\t$7, %r10\n"
                            + "\tmovq\t%r10, -16(%rsp)\n\tmovq\t-16(%rsp), %r10\n");
            case MIRROR_JINT -> assembly.append(
                    "\tmovl\t%eax, -4(%rsp)\n\tmovq\t%r10, -16(%rsp)\n"
                            + "\tmovq\t%r11, -24(%rsp)\n\tmovq\t-16(%rsp), %r10\n"
                            + "\tmovq\t-24(%rsp), %r11\n\tmovq\t%r10, -16(%rsp)\n"
                            + "\tmovq\t%r11, -24(%rsp)\n\tmovq\t-16(%rsp), %r10\n"
                            + "\tmovl\t-4(%rsp), %eax\n");
        }
    }

    private void appendArm64Route(
            StringBuilder assembly,
            NativeRegistrationPostCallRecipe recipe) {
        switch (recipe) {
            case XOR_JINT -> assembly.append(
                    "\tstr\tw0, [sp, #4]\n\tstr\tx10, [sp, #16]\n"
                            + "\tldr\tx10, [sp, #16]\n\tldr\tw0, [sp, #4]\n");
            case ADD_JLONG -> assembly.append(
                    "\tsxtw\tx0, w0\n\tstr\tx0, [sp, #8]\n"
                            + "\tstr\tx10, [sp, #16]\n\tldr\tx0, [sp, #8]\n"
                            + "\tldr\tx10, [sp, #16]\n\tlsr\tx10, x10, #7\n"
                            + "\tstr\tx10, [sp, #16]\n\tldr\tx10, [sp, #16]\n");
            case MIRROR_JINT -> assembly.append(
                    "\tstr\tw0, [sp, #4]\n\tstr\tx10, [sp, #16]\n"
                            + "\tstr\tx11, [sp, #24]\n\tldr\tx10, [sp, #16]\n"
                            + "\tldr\tx11, [sp, #24]\n\tstr\tx10, [sp, #16]\n"
                            + "\tstr\tx11, [sp, #24]\n\tldr\tx10, [sp, #16]\n"
                            + "\tldr\tw0, [sp, #4]\n");
        }
    }

    private void appendX64Chunk(
            StringBuilder assembly,
            NativeRegistrationChunkPostCallVariant variant) {
        switch (variant) {
            case JINT_U16_FOLD -> assembly.append(
                    "\tmovw\t%ax, -8(%rsp)\n\tmovzwl\t-8(%rsp), %r10d\n");
            case JLONG_U32_FOLD -> assembly.append(
                    "\tmovq\t%rax, -16(%rsp)\n\tmovq\t-16(%rsp), %r10\n");
            case JINT_DUAL_WORD -> assembly.append(
                    "\tmovq\t%rax, -8(%rsp)\n\tmovq\t%rbx, -16(%rsp)\n"
                            + "\tmovq\t-8(%rsp), %rax\n\tmovq\t-16(%rsp), %rbx\n");
            case JLONG_ORBIT -> assembly.append(
                    "\tmovq\t%rax, -8(%rsp)\n\tmovq\t-8(%rsp), %r10\n\trolq\t$9, %rax\n");
            case JINT_MIXED_WIDTH -> assembly.append(
                    "\tmovb\t%al, -1(%rsp)\n\tmovq\t%rax, -16(%rsp)\n"
                            + "\tmovzbl\t-1(%rsp), %r10d\n");
            case JLONG_MIXED_WIDTH -> assembly.append(
                    "\tmovw\t%ax, -2(%rsp)\n\tmovq\t%rax, -16(%rsp)\n"
                            + "\tmovzwl\t-2(%rsp), %r10d\n");
            case JINT_SIGNED_BRAID -> assembly.append(
                    "\tmovq\t%rax, -8(%rsp)\n\tmovq\t%rbx, -16(%rsp)\n"
                            + "\tmovq\t%rcx, -24(%rsp)\n\tmovq\t-8(%rsp), %rax\n"
                            + "\tmovq\t-16(%rsp), %rbx\n");
            case JLONG_SPLIT_WORD -> assembly.append(
                    "\tmovq\t%rax, -8(%rsp)\n\tmovl\t%eax, -12(%rsp)\n"
                            + "\tmovl\t%ebx, -16(%rsp)\n\tmovq\t-8(%rsp), %rax\n"
                            + "\tmovl\t-12(%rsp), %eax\n\tmovl\t-16(%rsp), %ebx\n");
        }
    }

    private void appendArm64Chunk(
            StringBuilder assembly,
            NativeRegistrationChunkPostCallVariant variant) {
        switch (variant) {
            case JINT_U16_FOLD -> assembly.append(
                    "\tstrh\tw0, [sp, #8]\n\tldrh\tw10, [sp, #8]\n");
            case JLONG_U32_FOLD -> assembly.append(
                    "\tstr\tx0, [sp, #16]\n\tldr\tx10, [sp, #16]\n");
            case JINT_DUAL_WORD -> assembly.append(
                    "\tstr\tx0, [sp, #8]\n\tstr\tx1, [sp, #16]\n"
                            + "\tldr\tx0, [sp, #8]\n\tldr\tx1, [sp, #16]\n");
            case JLONG_ORBIT -> assembly.append(
                    "\tstr\tx0, [sp, #8]\n\tldr\tx10, [sp, #8]\n\tror\tx0, x0, #9\n");
            case JINT_MIXED_WIDTH -> assembly.append(
                    "\tstrb\tw0, [sp, #7]\n\tstr\tx0, [sp, #16]\n"
                            + "\tldrb\tw10, [sp, #7]\n");
            case JLONG_MIXED_WIDTH -> assembly.append(
                    "\tstrh\tw0, [sp, #6]\n\tstr\tx0, [sp, #16]\n"
                            + "\tldrh\tw10, [sp, #6]\n");
            case JINT_SIGNED_BRAID -> assembly.append(
                    "\tstr\tx0, [sp, #8]\n\tstr\tx1, [sp, #16]\n"
                            + "\tstr\tx2, [sp, #24]\n\tldr\tx0, [sp, #8]\n"
                            + "\tldr\tx1, [sp, #16]\n");
            case JLONG_SPLIT_WORD -> assembly.append(
                    "\tstr\tx0, [sp, #8]\n\tstr\tw0, [sp, #16]\n"
                            + "\tstr\tw1, [sp, #20]\n\tldr\tx0, [sp, #8]\n"
                            + "\tldr\tw0, [sp, #16]\n\tldr\tw1, [sp, #20]\n");
        }
    }
}
