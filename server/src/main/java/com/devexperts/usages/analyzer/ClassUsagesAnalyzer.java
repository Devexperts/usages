/**
 * Copyright (C) 2017 Devexperts LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 */
package com.devexperts.usages.analyzer;

import com.devexperts.usages.analyzer.internal.MemberInternal;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.io.File;

// TODO line numbers for all usages
class ClassUsagesAnalyzer extends ClassVisitor {
    private static final String INIT_METHOD = "<init>";
    private static final String DEFAULT_ANNOTATION_PROPERTY = "value";

    private final Cache cache;
    private final Usages usages;
    private final String className;
    private Config config;

    private int lineNumber = -1;

    private static String toClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    public ClassUsagesAnalyzer(Usages usages, String className, Config config) {
        super(Opcodes.ASM5);
        this.config = config;
        this.cache = usages.getCache();
        this.usages = usages;
        this.className = className;
    }

    private void markMemberUse(String className, String name, MemberInternal usedFrom, UseKind useKind) {
        if (className == null) {
            return;
        }
        if (!config.excludesClassName(className)) {
            usages.getUsagesForClass(className)
                .addMemberUsage(name, usedFrom, new Usage(useKind, this.className, lineNumber));
        }
    }

    private void makeTypeUse(String className, MemberInternal usedFrom, UseKind useKind) {
        if (!config.excludesClassName(className)) {
            usages.getUsagesForClass(className).addTypeUsage(usedFrom, new Usage(useKind, this.className, lineNumber));
        }
    }

    private void markTypeUse(Type type, MemberInternal usedFrom, UseKind useKind) {
        if (type.getSort() == Type.METHOD) {
            markTypeUse(type.getReturnType(), usedFrom, UseKind.RETURN);
            for (Type arg : type.getArgumentTypes()) {
                markTypeUse(arg, usedFrom, UseKind.ARGUMENT);
            }
            return;
        }
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        if (type.getSort() == Type.OBJECT) {
            makeTypeUse(type.getClassName(), usedFrom, useKind);
        }
    }

    private void markHandleUse(Handle handle, MemberInternal usedFrom, UseKind useKind) {
        markTypeUse(Type.getType(handle.getDesc()), usedFrom, useKind);
        String className = Type.getType(handle.getOwner()).getClassName();
        switch (handle.getTag()) {
        case Opcodes.H_GETFIELD:
        case Opcodes.H_GETSTATIC:
        case Opcodes.H_PUTFIELD:
        case Opcodes.H_PUTSTATIC:
            markMemberUse(className, handle.getName(), usedFrom, useKind);
            break;
        case Opcodes.H_INVOKEVIRTUAL:
        case Opcodes.H_INVOKESTATIC:
        case Opcodes.H_INVOKESPECIAL:
        case Opcodes.H_NEWINVOKESPECIAL:
        case Opcodes.H_INVOKEINTERFACE:
            markMemberUse(className, MemberInternal.methodMemberName(handle.getName(), Type.getType(handle.getDesc())),
                usedFrom, useKind);
        }
    }

    private void markConstant(Object cst, MemberInternal usedFrom, UseKind useKind) {
        if (cst instanceof Type) {
            markTypeUse((Type) cst, usedFrom, useKind);
        } else if (cst instanceof Handle) {
            markHandleUse((Handle) cst, usedFrom, useKind);
        }
    }

    private void markSignatureUse(String signature, MemberInternal usedFrom) {
        if (signature != null) {
            new SignatureReader(signature).accept(new SignatureAnalyzer(usedFrom));
        }
    }

    @Override
    public void visitSource(String source, String debug) {
        usages.putFileOfClass(className,
            className.substring(0, className.lastIndexOf('.')).replace(".", File.separator) + File.separator + source);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        MemberInternal usedFrom = cache.resolveMember(className, MemberInternal.CLASS_MEMBER_NAME);
        makeTypeUse(toClassName(superName), usedFrom, UseKind.EXTEND);
        if (interfaces != null) {
            for (String intf : interfaces) {
                makeTypeUse(toClassName(intf), usedFrom, UseKind.IMPLEMENT);
            }
        }
        markSignatureUse(signature, usedFrom);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        MemberInternal usedFrom = cache.resolveMember(className, name);
        markTypeUse(Type.getType(desc), usedFrom, UseKind.FIELD);
        markSignatureUse(signature, usedFrom);
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
            && ((access & Opcodes.ACC_STATIC) == 0))
        {
            usages.getUsagesForClass(className).addInheritableMember(name);
        }
        return null;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        String methodMemberName = MemberInternal.methodMemberName(name, Type.getType(desc));
        MemberInternal usedFrom = cache.resolveMember(className, methodMemberName);
        markTypeUse(Type.getType(desc), usedFrom, UseKind.UNKNOWN); // will be replaced by RETURN/ARGUMENT
        markSignatureUse(signature, usedFrom);
        if (exceptions != null) {
            for (String ex : exceptions) {
                makeTypeUse(toClassName(ex), usedFrom, UseKind.THROW);
            }
        }
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
            && ((access & Opcodes.ACC_STATIC) == 0)
            && !name.equals(INIT_METHOD))
        {
            usages.getUsagesForClass(className).addInheritableMember(methodMemberName);
        }
        return new MethodAnalyzer(usedFrom);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        Type annotationType = Type.getType(desc);
        MemberInternal usedFrom = cache.resolveMember(className, MemberInternal.CLASS_MEMBER_NAME);
        markTypeUse(annotationType, usedFrom, UseKind.ANNOTATION);
        return new AnnotationAnalyzer(annotationType.getClassName(), usedFrom);
    }

    private class MethodAnalyzer extends MethodVisitor {
        private MemberInternal method;

        private MethodAnalyzer(MemberInternal method) {
            super(Opcodes.ASM5);
            this.method = method;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            lineNumber = line;
        }

        @Override
        public void visitEnd() {
            lineNumber = -1;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            UseKind useKind;
            switch (opcode) {
            case Opcodes.NEW:
                useKind = UseKind.NEW;
                break;
            case Opcodes.ANEWARRAY:
                useKind = UseKind.ANEWARRAY;
                break;
            case Opcodes.CHECKCAST:
                useKind = UseKind.CHECKCAST;
                break;
            case Opcodes.INSTANCEOF:
                useKind = UseKind.INSTANCEOF;
                break;
            default:
                useKind = UseKind.UNKNOWN;
            }
            markTypeUse(Type.getObjectType(type), method, useKind);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            UseKind useKind;
            switch (opcode) {
            case Opcodes.GETFIELD:
                useKind = UseKind.GETFIELD;
                break;
            case Opcodes.PUTFIELD:
                useKind = UseKind.PUTFIELD;
                break;
            case Opcodes.GETSTATIC:
                useKind = UseKind.GETSTATIC;
                break;
            case Opcodes.PUTSTATIC:
                useKind = UseKind.PUTSTATIC;
                break;
            default:
                useKind = UseKind.UNKNOWN;
            }
            markMemberUse(toClassName(owner), name, method, useKind);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            UseKind useKind;
            switch (opcode) {
            case Opcodes.INVOKEVIRTUAL:
                useKind = UseKind.INVOKEVIRTUAL;
                break;
            case Opcodes.INVOKESPECIAL:
                useKind = UseKind.INVOKESPECIAL;
                break;
            case Opcodes.INVOKESTATIC:
                useKind = UseKind.INVOKESTATIC;
                break;
            case Opcodes.INVOKEINTERFACE:
                useKind = UseKind.INVOKEINTERFACE;
                break;
            default:
                useKind = UseKind.UNKNOWN;
            }
            if (!owner.startsWith("[")) {
                markMemberUse(toClassName(owner), MemberInternal.methodMemberName(name, Type.getType(desc)), method, useKind);
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
            markHandleUse(bsm, method, UseKind.INVOKEDYNAMIC);
            for (Object arg : bsmArgs) {
                markConstant(arg, method, UseKind.INVOKEDYNAMIC);
            }
        }

        @Override
        public void visitLdcInsn(Object cst) {
            markConstant(cst, method, UseKind.CONSTANT);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                makeTypeUse(toClassName(type), method, UseKind.CATCH);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotationDefault() {
            return new AnnotationAnalyzer(className, method);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return new AnnotationAnalyzer(Type.getType(desc).getClassName(), method);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
            return new AnnotationAnalyzer(Type.getType(desc).getClassName(), method);
        }
    }

    private class SignatureAnalyzer extends SignatureVisitor {
        private final MemberInternal usedFrom;

        private SignatureAnalyzer(MemberInternal usedFrom) {
            super(Opcodes.ASM5);
            this.usedFrom = usedFrom;
        }

        @Override
        public void visitClassType(String name) {
            makeTypeUse(toClassName(name), usedFrom, UseKind.SIGNATURE);
        }
    }

    private class AnnotationAnalyzer extends AnnotationVisitor {
        private final String annotationClassName;
        private final MemberInternal usedFrom;

        public AnnotationAnalyzer(String annotationClassName, MemberInternal usedFrom) {
            super(Opcodes.ASM5);
            this.annotationClassName = annotationClassName;
            this.usedFrom = usedFrom;
        }

        @Override
        public void visit(String name, Object value) {
            markMemberUse(annotationClassName, annotationMemberName(name), usedFrom, UseKind.ANNOTATION);
            markConstant(value, usedFrom, UseKind.ANNOTATION);
        }

        @Override
        public void visitEnum(String name, String desc, String value) {
            markMemberUse(annotationClassName, annotationMemberName(name), usedFrom, UseKind.ANNOTATION);
            markMemberUse(Type.getType(desc).getClassName(), value, usedFrom, UseKind.ANNOTATION);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String desc) {
            markMemberUse(annotationClassName, annotationMemberName(name), usedFrom, UseKind.ANNOTATION);
            markTypeUse(Type.getType(desc), usedFrom, UseKind.ANNOTATION);
            return this;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            markMemberUse(annotationClassName, annotationMemberName(name), usedFrom, UseKind.ANNOTATION);
            return this;
        }

        private String annotationMemberName(String name) {
            return (name == null ? DEFAULT_ANNOTATION_PROPERTY : name) + "()";
        }
    }
}