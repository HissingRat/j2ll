package xyz.melodysky.frontend.bytecode;

import org.objectweb.asm.Type;
import xyz.melodysky.ir.model.IrType;

import java.util.List;

record LambdaBootstrapMetadata(String bootstrapMethodName,
                               int flags,
                               List<IrType> markerInterfaces,
                               List<Type> bridgeMethodTypes) {
}
