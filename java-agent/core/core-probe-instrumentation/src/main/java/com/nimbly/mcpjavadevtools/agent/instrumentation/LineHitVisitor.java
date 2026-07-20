package com.nimbly.mcpjavadevtools.agent.instrumentation;

import com.nimbly.mcpjavadevtools.agent.runtime.ProbeRuntime;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.field.FieldList;
import net.bytebuddy.description.method.MethodList;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

public final class LineHitVisitor extends AsmVisitorWrapper.AbstractBase {
  private static final String PROBE_RUNTIME_INTERNAL_NAME =
      "com/nimbly/mcpjavadevtools/agent/runtime/ProbeRuntime";

  private final String dottedClassName;

  public LineHitVisitor(String dottedClassName) {
    this.dottedClassName = dottedClassName;
  }

  @Override
  public int mergeWriter(int flags) {
    return flags | ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES;
  }

  @Override
  public int mergeReader(int flags) {
    return flags | ClassReader.EXPAND_FRAMES;
  }

  @Override
  public ClassVisitor wrap(
      TypeDescription instrumentedType,
      ClassVisitor classVisitor,
      Implementation.Context implementationContext,
      TypePool typePool,
      FieldList<FieldDescription.InDefinedShape> fields,
      MethodList<?> methods,
      int writerFlags,
      int readerFlags
  ) {
    return new ClassVisitor(Opcodes.ASM9, classVisitor) {
      private boolean isConditionalJumpOpcode(int opcode) {
        return opcode == Opcodes.IFEQ
            || opcode == Opcodes.IFNE
            || opcode == Opcodes.IFLT
            || opcode == Opcodes.IFGE
            || opcode == Opcodes.IFGT
            || opcode == Opcodes.IFLE
            || opcode == Opcodes.IF_ICMPEQ
            || opcode == Opcodes.IF_ICMPNE
            || opcode == Opcodes.IF_ICMPLT
            || opcode == Opcodes.IF_ICMPGE
            || opcode == Opcodes.IF_ICMPGT
            || opcode == Opcodes.IF_ICMPLE
            || opcode == Opcodes.IF_ACMPEQ
            || opcode == Opcodes.IF_ACMPNE
            || opcode == Opcodes.IFNULL
            || opcode == Opcodes.IFNONNULL;
      }

      private boolean isUnaryConditionalJump(int opcode) {
        return opcode == Opcodes.IFEQ
            || opcode == Opcodes.IFNE
            || opcode == Opcodes.IFLT
            || opcode == Opcodes.IFGE
            || opcode == Opcodes.IFGT
            || opcode == Opcodes.IFLE
            || opcode == Opcodes.IFNULL
            || opcode == Opcodes.IFNONNULL;
      }

      private void popConditionalOperands(MethodVisitor mv, int opcode) {
        if (isUnaryConditionalJump(opcode)) {
          mv.visitInsn(Opcodes.POP);
          return;
        }
        mv.visitInsn(Opcodes.POP2);
      }

      @Override
      public MethodVisitor visitMethod(
          int access,
          String name,
          String descriptor,
          String signature,
          String[] exceptions
      ) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        if (mv == null) return null;
        if ((access & Opcodes.ACC_ABSTRACT) != 0 || (access & Opcodes.ACC_NATIVE) != 0) return mv;
        final String probeMethodName = resolveProbeMethodName(name);
        if (probeMethodName == null || probeMethodName.isBlank()) return mv;

        return new MethodVisitor(Opcodes.ASM9, mv) {
          private int currentLine = -1;

          @Override
          public void visitLineNumber(int line, Label start) {
            super.visitLineNumber(line, start);
            if (line <= 0) return;
            currentLine = line;
            ProbeRuntime.registerResolvableLine(dottedClassName, probeMethodName, line);
            super.visitLdcInsn(dottedClassName);
            super.visitLdcInsn(probeMethodName);
            super.visitLdcInsn(line);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                PROBE_RUNTIME_INTERNAL_NAME,
                "hitLineByClassMethod",
                "(Ljava/lang/String;Ljava/lang/String;I)V",
                false
            );
          }

          @Override
          public void visitJumpInsn(int opcode, Label label) {
            if (!isConditionalJumpOpcode(opcode) || currentLine <= 0) {
              super.visitJumpInsn(opcode, label);
              return;
            }

            ProbeRuntime.registerActuatableLine(dottedClassName, probeMethodName, currentLine);

            super.visitLdcInsn(dottedClassName);
            super.visitLdcInsn(probeMethodName);
            super.visitLdcInsn(currentLine);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                PROBE_RUNTIME_INTERNAL_NAME,
                "branchDecisionByClassMethodLine",
                "(Ljava/lang/String;Ljava/lang/String;I)I",
                false
            );

            Label naturalPath = new Label();
            Label forceTaken = new Label();
            Label done = new Label();

            // branchDecision == -1 => run original bytecode condition.
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ICONST_M1);
            super.visitJumpInsn(Opcodes.IF_ICMPEQ, naturalPath);

            // branchDecision == 1 => force jump/taken.
            super.visitInsn(Opcodes.DUP);
            super.visitInsn(Opcodes.ICONST_1);
            super.visitJumpInsn(Opcodes.IF_ICMPEQ, forceTaken);

            // Otherwise branchDecision == 0 => force fallthrough/not-taken.
            super.visitInsn(Opcodes.POP); // drop branchDecision
            popConditionalOperands(this, opcode); // drop original condition operands
            super.visitJumpInsn(Opcodes.GOTO, done);

            super.visitLabel(forceTaken);
            super.visitInsn(Opcodes.POP); // drop branchDecision
            popConditionalOperands(this, opcode); // drop original condition operands
            super.visitJumpInsn(Opcodes.GOTO, label);

            super.visitLabel(naturalPath);
            super.visitInsn(Opcodes.POP); // drop branchDecision
            super.visitJumpInsn(opcode, label); // evaluate original condition

            super.visitLabel(done);
          }
        };
      }
    };
  }

  static String resolveProbeMethodName(String methodName) {
    if (methodName == null || methodName.isBlank()) return methodName;
    if (!methodName.startsWith("lambda$")) return methodName;
    int start = "lambda$".length();
    int end = methodName.lastIndexOf('$');
    if (end <= start) return methodName;
    String ownerMethod = methodName.substring(start, end).trim();
    return ownerMethod.isEmpty() ? methodName : ownerMethod;
  }
}

