package xyz.melodysky.filter;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

public class ClassMethodFilter {

    private final ClassMethodList blackList;
    private final ClassMethodList whiteList;

    public ClassMethodFilter(ClassMethodList blackList, ClassMethodList whiteList) {
        this.blackList = blackList;
        this.whiteList = whiteList;
    }

    public static ClassMethodFilter allowAll() {
        return new ClassMethodFilter(ClassMethodList.parse(null), ClassMethodList.parse(null));
    }

    public boolean shouldProcess(ClassNode classNode) {
        if (hasInList(blackList, classNode.name)) {
            return false;
        }
        if (whiteList != null && !hasInList(whiteList, classNode.name)) {
            return false;
        }
        return true;
    }

    public boolean shouldProcess(ClassNode classNode, MethodNode methodNode) {
        String methodName = classNode.name + '#' + methodNode.name + '!' + methodNode.desc;
        if (hasInList(blackList, methodName)) {
            return false;
        }
        if (whiteList != null && !hasInList(whiteList, methodName)) {
            return false;
        }
        return true;
    }

    private boolean hasInList(ClassMethodList list, String name) {
        return list != null && list.contains(name);
    }
}
