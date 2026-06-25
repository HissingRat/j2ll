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
        if (matchesBlackListClass(classNode.name)) {
            return false;
        }
        if (whiteList != null && !matchesWhiteListClass(classNode.name)) {
            return false;
        }
        return true;
    }

    public boolean shouldProcess(ClassNode classNode, MethodNode methodNode) {
        if (matchesBlackListMethod(classNode.name, methodNode.name, methodNode.desc)) {
            return false;
        }
        if (whiteList != null && !matchesWhiteListMethod(classNode.name, methodNode.name, methodNode.desc)) {
            return false;
        }
        return true;
    }

    public boolean matchesBlackListClass(String className) {
        return hasInList(blackList, className);
    }

    public boolean matchesWhiteListClass(String className) {
        return hasInList(whiteList, className);
    }

    public boolean matchesBlackListMethod(String className, String methodName, String descriptor) {
        return hasInList(blackList, methodKey(className, methodName, descriptor));
    }

    public boolean matchesWhiteListMethod(String className, String methodName, String descriptor) {
        return hasInList(whiteList, methodKey(className, methodName, descriptor));
    }

    private String methodKey(String className, String methodName, String descriptor) {
        return className + '#' + methodName + '!' + descriptor;
    }

    private boolean hasInList(ClassMethodList list, String name) {
        return list != null && list.contains(name);
    }
}
