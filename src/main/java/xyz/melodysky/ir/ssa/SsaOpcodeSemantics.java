package xyz.melodysky.ir.ssa;

import org.objectweb.asm.Opcodes;
import xyz.melodysky.ir.model.IrOpcode;
import xyz.melodysky.ir.model.IrType;

/** JVM opcode classifications and their direct SSA IR mappings. */
final class SsaOpcodeSemantics implements Opcodes {
    private SsaOpcodeSemantics() {
    }

    static boolean isLoad(int opcode) {
        return opcode == ILOAD || opcode == LLOAD || opcode == FLOAD || opcode == DLOAD || opcode == ALOAD;
    }

    static boolean isStore(int opcode) {
        return opcode == ISTORE || opcode == LSTORE || opcode == FSTORE || opcode == DSTORE || opcode == ASTORE;
    }

    static boolean isBinary(int opcode) {
        return opcode == IADD || opcode == ISUB || opcode == IMUL || opcode == IDIV || opcode == IREM
                || opcode == LADD || opcode == LSUB || opcode == LMUL || opcode == LDIV || opcode == LREM
                || opcode == FADD || opcode == FSUB || opcode == FMUL || opcode == FDIV || opcode == FREM
                || opcode == DADD || opcode == DSUB || opcode == DMUL || opcode == DDIV || opcode == DREM
                || opcode == IAND || opcode == IOR || opcode == IXOR
                || opcode == LAND || opcode == LOR || opcode == LXOR;
    }

    static boolean isIntegerDivisionOrRemainder(int opcode) {
        return opcode == IDIV || opcode == IREM || opcode == LDIV || opcode == LREM;
    }

    static boolean isNegation(int opcode) {
        return opcode == INEG || opcode == LNEG || opcode == FNEG || opcode == DNEG;
    }

    static boolean isShift(int opcode) {
        return opcode == ISHL || opcode == ISHR || opcode == IUSHR
                || opcode == LSHL || opcode == LSHR || opcode == LUSHR;
    }

    static boolean isConversion(int opcode) {
        return opcode == I2L || opcode == I2F || opcode == I2D || opcode == I2B || opcode == I2C || opcode == I2S
                || opcode == L2I || opcode == L2F || opcode == L2D
                || opcode == F2I || opcode == F2L || opcode == F2D
                || opcode == D2I || opcode == D2L || opcode == D2F;
    }

    static boolean isValueComparison(int opcode) {
        return opcode == LCMP || opcode == FCMPL || opcode == FCMPG || opcode == DCMPL || opcode == DCMPG;
    }

    static boolean isStackManipulation(int opcode) {
        return opcode == POP || opcode == POP2 || opcode == DUP || opcode == DUP_X1 || opcode == DUP_X2
                || opcode == DUP2 || opcode == DUP2_X1 || opcode == DUP2_X2 || opcode == SWAP;
    }

    static boolean isArrayLoad(int opcode) {
        return opcode == IALOAD || opcode == LALOAD || opcode == FALOAD || opcode == DALOAD || opcode == AALOAD
                || opcode == BALOAD || opcode == CALOAD || opcode == SALOAD;
    }

    static boolean isArrayStore(int opcode) {
        return opcode == IASTORE || opcode == LASTORE || opcode == FASTORE || opcode == DASTORE || opcode == AASTORE
                || opcode == BASTORE || opcode == CASTORE || opcode == SASTORE;
    }

    static boolean isValueReturn(int opcode) {
        return opcode == IRETURN || opcode == LRETURN || opcode == FRETURN || opcode == DRETURN || opcode == ARETURN;
    }

    static boolean isConditionalBranch(int opcode) {
        return isIntZeroBranch(opcode) || isIntCompareBranch(opcode) || isReferenceBranch(opcode);
    }

    static boolean isIntZeroBranch(int opcode) {
        return opcode == IFEQ || opcode == IFNE || opcode == IFLT || opcode == IFLE || opcode == IFGT || opcode == IFGE;
    }

    static boolean isIntCompareBranch(int opcode) {
        return opcode == IF_ICMPEQ
                || opcode == IF_ICMPNE
                || opcode == IF_ICMPLT
                || opcode == IF_ICMPLE
                || opcode == IF_ICMPGT
                || opcode == IF_ICMPGE;
    }

    static boolean isReferenceBranch(int opcode) {
        return opcode == IF_ACMPEQ || opcode == IF_ACMPNE || isNullBranch(opcode);
    }

    static boolean isNullBranch(int opcode) {
        return opcode == IFNULL || opcode == IFNONNULL;
    }

    static IrType binaryResultType(int opcode) {
        return switch (opcode) {
            case LADD, LSUB, LMUL, LDIV, LREM, LAND, LOR, LXOR, LSHL, LSHR, LUSHR -> IrType.I64;
            case FADD, FSUB, FMUL, FDIV, FREM -> IrType.F32;
            case DADD, DSUB, DMUL, DDIV, DREM -> IrType.F64;
            default -> IrType.I32;
        };
    }

    static IrType conversionResultType(int opcode) {
        return switch (opcode) {
            case I2L, F2L, D2L -> IrType.I64;
            case I2F, L2F, D2F -> IrType.F32;
            case I2D, L2D, F2D -> IrType.F64;
            case I2B, I2C, I2S, L2I, F2I, D2I -> IrType.I32;
            default -> throw new IllegalArgumentException("unsupported conversion opcode " + opcode);
        };
    }

    static IrOpcode irOpcode(int opcode) {
        return switch (opcode) {
            case IADD -> IrOpcode.ADD_I32;
            case ISUB -> IrOpcode.SUB_I32;
            case IMUL -> IrOpcode.MUL_I32;
            case IDIV -> IrOpcode.DIV_I32;
            case IREM -> IrOpcode.REM_I32;
            case INEG -> IrOpcode.NEG_I32;
            case ISHL -> IrOpcode.SHL_I32;
            case ISHR -> IrOpcode.SHR_I32;
            case IUSHR -> IrOpcode.USHR_I32;
            case IAND -> IrOpcode.AND_I32;
            case IOR -> IrOpcode.OR_I32;
            case IXOR -> IrOpcode.XOR_I32;
            case LADD -> IrOpcode.ADD_I64;
            case LSUB -> IrOpcode.SUB_I64;
            case LMUL -> IrOpcode.MUL_I64;
            case LDIV -> IrOpcode.DIV_I64;
            case LREM -> IrOpcode.REM_I64;
            case LNEG -> IrOpcode.NEG_I64;
            case LSHL -> IrOpcode.SHL_I64;
            case LSHR -> IrOpcode.SHR_I64;
            case LUSHR -> IrOpcode.USHR_I64;
            case LAND -> IrOpcode.AND_I64;
            case LOR -> IrOpcode.OR_I64;
            case LXOR -> IrOpcode.XOR_I64;
            case FADD -> IrOpcode.ADD_F32;
            case FSUB -> IrOpcode.SUB_F32;
            case FMUL -> IrOpcode.MUL_F32;
            case FDIV -> IrOpcode.DIV_F32;
            case FREM -> IrOpcode.REM_F32;
            case FNEG -> IrOpcode.NEG_F32;
            case DADD -> IrOpcode.ADD_F64;
            case DSUB -> IrOpcode.SUB_F64;
            case DMUL -> IrOpcode.MUL_F64;
            case DDIV -> IrOpcode.DIV_F64;
            case DREM -> IrOpcode.REM_F64;
            case DNEG -> IrOpcode.NEG_F64;
            case LCMP -> IrOpcode.LCMP;
            case FCMPL -> IrOpcode.FCMPL;
            case FCMPG -> IrOpcode.FCMPG;
            case DCMPL -> IrOpcode.DCMPL;
            case DCMPG -> IrOpcode.DCMPG;
            case I2L -> IrOpcode.I2L;
            case I2F -> IrOpcode.I2F;
            case I2D -> IrOpcode.I2D;
            case I2B -> IrOpcode.I2B;
            case I2C -> IrOpcode.I2C;
            case I2S -> IrOpcode.I2S;
            case L2I -> IrOpcode.L2I;
            case L2F -> IrOpcode.L2F;
            case L2D -> IrOpcode.L2D;
            case F2I -> IrOpcode.F2I;
            case F2L -> IrOpcode.F2L;
            case F2D -> IrOpcode.F2D;
            case D2I -> IrOpcode.D2I;
            case D2L -> IrOpcode.D2L;
            case D2F -> IrOpcode.D2F;
            default -> throw new IllegalArgumentException("unsupported binary opcode " + opcode);
        };
    }

    static IrOpcode compareOpcode(int opcode) {
        return switch (opcode) {
            case IFEQ, IF_ICMPEQ -> IrOpcode.CMP_EQ_I32;
            case IFNE, IF_ICMPNE -> IrOpcode.CMP_NE_I32;
            case IFLT, IF_ICMPLT -> IrOpcode.CMP_LT_I32;
            case IFLE, IF_ICMPLE -> IrOpcode.CMP_LE_I32;
            case IFGT, IF_ICMPGT -> IrOpcode.CMP_GT_I32;
            case IFGE, IF_ICMPGE -> IrOpcode.CMP_GE_I32;
            case IF_ACMPEQ, IFNULL -> IrOpcode.CMP_EQ_REF;
            case IF_ACMPNE, IFNONNULL -> IrOpcode.CMP_NE_REF;
            default -> throw new IllegalArgumentException("unsupported branch opcode " + opcode);
        };
    }

    static IrOpcode callOpcode(int opcode) {
        return switch (opcode) {
            case INVOKESTATIC -> IrOpcode.CALL_STATIC;
            case INVOKESPECIAL -> IrOpcode.CALL_SPECIAL;
            case INVOKEVIRTUAL -> IrOpcode.CALL_VIRTUAL;
            case INVOKEINTERFACE -> IrOpcode.CALL_INTERFACE;
            default -> throw new IllegalArgumentException("unsupported call opcode " + opcode);
        };
    }

    static IrOpcode callOpcodeForHandleTag(int tag) {
        return switch (tag) {
            case H_INVOKESTATIC -> IrOpcode.CALL_STATIC;
            case H_INVOKEVIRTUAL -> IrOpcode.CALL_VIRTUAL;
            case H_INVOKEINTERFACE -> IrOpcode.CALL_INTERFACE;
            case H_INVOKESPECIAL, H_NEWINVOKESPECIAL -> IrOpcode.CALL_SPECIAL;
            default -> IrOpcode.CALL_DYNAMIC;
        };
    }

    static IrOpcode arrayLoadOpcode(int opcode) {
        return switch (opcode) {
            case LALOAD -> IrOpcode.ARRAY_LOAD_I64;
            case FALOAD -> IrOpcode.ARRAY_LOAD_F32;
            case DALOAD -> IrOpcode.ARRAY_LOAD_F64;
            case AALOAD -> IrOpcode.ARRAY_LOAD_REF;
            case IALOAD, BALOAD, CALOAD, SALOAD -> IrOpcode.ARRAY_LOAD_I32;
            default -> throw new IllegalArgumentException("not an array load opcode " + opcode);
        };
    }

    static IrOpcode arrayStoreOpcode(int opcode) {
        return switch (opcode) {
            case LASTORE -> IrOpcode.ARRAY_STORE_I64;
            case FASTORE -> IrOpcode.ARRAY_STORE_F32;
            case DASTORE -> IrOpcode.ARRAY_STORE_F64;
            case AASTORE -> IrOpcode.ARRAY_STORE_REF;
            case IASTORE, BASTORE, CASTORE, SASTORE -> IrOpcode.ARRAY_STORE_I32;
            default -> throw new IllegalArgumentException("not an array store opcode " + opcode);
        };
    }

    static IrType arrayLoadType(int opcode) {
        return switch (opcode) {
            case LALOAD -> IrType.I64;
            case FALOAD -> IrType.F32;
            case DALOAD -> IrType.F64;
            case AALOAD -> IrType.REFERENCE;
            case IALOAD, BALOAD, CALOAD, SALOAD -> IrType.I32;
            default -> throw new IllegalArgumentException("not an array load opcode " + opcode);
        };
    }

    static String arrayElementKind(int opcode) {
        return switch (opcode) {
            case IALOAD, IASTORE -> "int";
            case LALOAD, LASTORE -> "long";
            case FALOAD, FASTORE -> "float";
            case DALOAD, DASTORE -> "double";
            case AALOAD, AASTORE -> "reference";
            case BALOAD, BASTORE -> "byteOrBoolean";
            case CALOAD, CASTORE -> "char";
            case SALOAD, SASTORE -> "short";
            default -> throw new IllegalArgumentException("not an array opcode " + opcode);
        };
    }

    static String primitiveArrayType(int operand) {
        return switch (operand) {
            case T_BOOLEAN -> "boolean";
            case T_CHAR -> "char";
            case T_FLOAT -> "float";
            case T_DOUBLE -> "double";
            case T_BYTE -> "byte";
            case T_SHORT -> "short";
            case T_INT -> "int";
            case T_LONG -> "long";
            default -> throw new IllegalArgumentException("not a primitive array type " + operand);
        };
    }
}
