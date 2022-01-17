/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.reflect.hosted;

import static com.oracle.svm.reflect.target.MethodMetadataDecoderImpl.NULL_OBJECT;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.graalvm.compiler.core.common.util.TypeConversion;
import org.graalvm.compiler.core.common.util.UnsafeArrayTypeWriter;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.serviceprovider.JavaVersionUtil;
import org.graalvm.util.GuardedAnnotationAccess;

import com.oracle.svm.core.code.CodeInfoEncoder;
import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.meta.SubstrateObjectConstant;
import com.oracle.svm.core.reflect.Target_jdk_internal_reflect_ConstantPool;
import com.oracle.svm.core.util.ByteArrayReader;
import com.oracle.svm.hosted.ProgressReporter;
import com.oracle.svm.hosted.image.NativeImageCodeCache.MethodMetadataEncoder;
import com.oracle.svm.hosted.image.NativeImageCodeCache.MethodMetadataEncoderFactory;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.reflect.hosted.ReflectionMetadata.ConstructorMetadata;
import com.oracle.svm.reflect.hosted.ReflectionMetadata.FieldMetadata;
import com.oracle.svm.reflect.hosted.ReflectionMetadata.MethodMetadata;
import com.oracle.svm.reflect.target.MethodMetadataDecoderImpl;
import com.oracle.svm.reflect.target.MethodMetadataDecoderImpl.ReflectParameterDescriptor;
import com.oracle.svm.reflect.target.Target_sun_reflect_annotation_AnnotationParser;
import com.oracle.svm.util.ReflectionUtil;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MetaAccessProvider;
import sun.invoke.util.Wrapper;
import sun.reflect.annotation.AnnotationType;
import sun.reflect.annotation.TypeAnnotation;
import sun.reflect.annotation.TypeAnnotationParser;

/**
 * The method metadata encoding puts data in the image for three distinct types of methods.
 * <ol>
 * <li>Methods that are queried for reflection, but never accessed: in that case, the encoding
 * includes everything required to recreate an {@link Executable} object at runtime.</li>
 * <li>Methods that hide a method registered for reflection, but are not registered themselves: only
 * basic method information is stored for those methods (declaring class, name and parameter types).
 * They are used to ensure that the hidden superclass method is not incorrectly returned by a
 * reflection query on the subclass where it is hidden.</li>
 * <li>Methods that are included in the image: all reachable methods have their basic information
 * included to enable introspecting the produced executable.</li>
 * </ol>
 *
 * Emitting the metadata happens in two phases. In the first phase, the string and class encoders
 * are filled with the necessary values (in the {@code add*MethodMetadata} functions). In a second
 * phase, the values are encoded as byte arrays and stored in {@link DynamicHub} arrays (see
 * {@link #encodeAllAndInstall()}).
 */
public class MethodMetadataEncoderImpl implements MethodMetadataEncoder {

    static class Factory implements MethodMetadataEncoderFactory {
        @Override
        public MethodMetadataEncoder create(CodeInfoEncoder.Encoders encoders) {
            return new MethodMetadataEncoderImpl(encoders);
        }
    }

    private final CodeInfoEncoder.Encoders encoders;
    private final TreeSet<HostedType> sortedTypes;
    private Map<HostedType, Set<FieldMetadata>> fieldData;
    private Map<HostedType, Set<MethodMetadata>> methodData;
    private Map<HostedType, Set<ConstructorMetadata>> constructorData;
// private Map<HostedType, Set<RecordComponentMetadata>> recordComponentData;

    public MethodMetadataEncoderImpl(CodeInfoEncoder.Encoders encoders) {
        this.encoders = encoders;
        this.sortedTypes = new TreeSet<>(Comparator.comparingLong(t -> t.getHub().getTypeID()));
        this.fieldData = new HashMap<>();
        this.methodData = new HashMap<>();
        this.constructorData = new HashMap<>();
    }

    private static final Method isFieldTrustedFinal = ReflectionUtil.lookupMethod(true, Field.class, "isTrustedFinal");
    private static final Method parseAllTypeAnnotations = ReflectionUtil.lookupMethod(TypeAnnotationParser.class, "parseAllTypeAnnotations", AnnotatedElement.class);
    private static final Method hasRealParameterData = ReflectionUtil.lookupMethod(Executable.class, "hasRealParameterData");

    @Override
    public void addReflectionFieldMetadata(MetaAccessProvider metaAccess, HostedField hostedField, Field reflectField) {
        HostedType declaringType = hostedField.getDeclaringClass();
        String name = hostedField.getName();
        HostedType type = hostedField.getType();
        /* Reflect method because substitution of Object.hashCode() is private */
        int modifiers = reflectField.getModifiers();
        boolean trustedFinal = false;
        if (JavaVersionUtil.JAVA_SPEC >= 17) {
            try {
                trustedFinal = (boolean) isFieldTrustedFinal.invoke(reflectField);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw GraalError.shouldNotReachHere();
            }
        }
        String signature = getSignature(reflectField);
        Annotation[] annotations = GuardedAnnotationAccess.getDeclaredAnnotations(hostedField);
        TypeAnnotation[] typeAnnotations;
        try {
            typeAnnotations = (TypeAnnotation[]) parseAllTypeAnnotations.invoke(null, reflectField);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw GraalError.shouldNotReachHere();
        }

        /* Fill encoders with the necessary values. */
        encoders.sourceMethodNames.addObject(name);
        encoders.sourceClasses.addObject(type.getJavaClass());
        encoders.sourceMethodNames.addObject(signature);
        /* Register string and class values in annotations */
        registerAnnotationValues(annotations);

        sortedTypes.add(declaringType);
        fieldData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(new FieldMetadata(declaringType, name, type, modifiers, trustedFinal, signature,
                        annotations, typeAnnotations));
    }

    @Override
    public void addReflectionExecutableMetadata(MetaAccessProvider metaAccess, HostedMethod hostedMethod, Executable reflectMethod, Object accessor) {
        boolean isConstructor = hostedMethod.isConstructor();
        HostedType declaringType = hostedMethod.getDeclaringClass();
        String name = isConstructor ? null : hostedMethod.getName();
        HostedType[] parameterTypes = getParameterTypes(hostedMethod);
        /* Reflect method because substitution of Object.hashCode() is private */
        int modifiers = reflectMethod.getModifiers();
        HostedType returnType = (HostedType) hostedMethod.getSignature().getReturnType(null);
        HostedType[] exceptionTypes = getExceptionTypes(metaAccess, reflectMethod);
        String signature = getSignature(reflectMethod);
        Annotation[] annotations = GuardedAnnotationAccess.getDeclaredAnnotations(hostedMethod);
        Annotation[][] parameterAnnotations = reflectMethod.getParameterAnnotations();
        TypeAnnotation[] typeAnnotations;
        boolean reflectParameterDataPresent;
        try {
            typeAnnotations = (TypeAnnotation[]) parseAllTypeAnnotations.invoke(null, reflectMethod);
            reflectParameterDataPresent = (boolean) hasRealParameterData.invoke(reflectMethod);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw GraalError.shouldNotReachHere();
        }
        ReflectParameterDescriptor[] reflectParameterDescriptors = reflectParameterDataPresent ? getReflectParameters(reflectMethod) : new ReflectParameterDescriptor[0];

        /* Fill encoders with the necessary values. */
        if (!isConstructor) {
            encoders.sourceMethodNames.addObject(name);
        }
        for (HostedType parameterType : parameterTypes) {
            encoders.sourceClasses.addObject(parameterType.getJavaClass());
        }
        if (!isConstructor) {
            encoders.sourceClasses.addObject(returnType.getJavaClass());
        }
        for (HostedType exceptionType : exceptionTypes) {
            encoders.sourceClasses.addObject(exceptionType.getJavaClass());
        }
        encoders.sourceMethodNames.addObject(signature);
        /* Register string and class values in annotations */
        registerAnnotationValues(annotations);
        for (Annotation[] parameterAnnotation : parameterAnnotations) {
            registerAnnotationValues(parameterAnnotation);
        }
        for (TypeAnnotation typeAnnotation : typeAnnotations) {
            // Checkstyle: allow direct annotation access
            registerAnnotationValues(typeAnnotation.getAnnotation());
            // Checkstyle: disallow direct annotation access
        }
        for (ReflectParameterDescriptor parameter : reflectParameterDescriptors) {
            encoders.sourceMethodNames.addObject(parameter.getName());
        }
        JavaConstant accessorConstant = null;
        if (accessor != null) {
            accessorConstant = SubstrateObjectConstant.forObject(accessor);
            encoders.objectConstants.addObject(accessorConstant);
        }

        sortedTypes.add(declaringType);
        if (isConstructor) {
            constructorData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(new ConstructorMetadata(declaringType, parameterTypes, modifiers, exceptionTypes, signature, annotations,
                            parameterAnnotations, typeAnnotations, reflectParameterDataPresent, reflectParameterDescriptors, accessorConstant));
        } else {
            methodData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(new MethodMetadata(declaringType, name, parameterTypes, modifiers, returnType, exceptionTypes, signature,
                            annotations, parameterAnnotations, typeAnnotations, reflectParameterDataPresent, reflectParameterDescriptors, accessorConstant));
        }
    }

    private void registerAnnotationValues(Annotation... annotations) {
        for (Annotation annotation : annotations) {
            encoders.sourceClasses.addObject(annotation.annotationType());
            registerAnnotationValue(annotation.annotationType(), annotation);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerAnnotationValue(Class<?> type, Object value) {
        if (type.isAnnotation()) {
            Annotation annotation = (Annotation) value;
            AnnotationType annotationType = AnnotationType.getInstance((Class<? extends Annotation>) type);
            encoders.sourceClasses.addObject(type);
            for (Map.Entry<String, Class<?>> entry : annotationType.memberTypes().entrySet()) {
                String valueName = entry.getKey();
                Class<?> valueType = entry.getValue();
                encoders.sourceMethodNames.addObject(valueName);
                Method getAnnotationValue = annotationType.members().get(valueName);
                getAnnotationValue.setAccessible(true);
                Object annotationValue;
                try {
                    annotationValue = getAnnotationValue.invoke(annotation);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw GraalError.shouldNotReachHere();
                }
                registerAnnotationValue(valueType, annotationValue);
            }
        } else if (type.isArray()) {
            Class<?> componentType = type.getComponentType();
            if (!componentType.isPrimitive()) {
                for (Object val : (Object[]) value) {
                    registerAnnotationValue(componentType, val);
                }
            }
        } else if (type == Class.class) {
            encoders.sourceClasses.addObject((Class<?>) value);
        } else if (type == String.class) {
            encoders.sourceMethodNames.addObject((String) value);
        } else if (type.isEnum()) {
            encoders.sourceClasses.addObject(type);
            encoders.sourceMethodNames.addObject(((Enum<?>) value).name());
        }
    }

    @Override
    public void addHidingMethodMetadata(HostedType declaringType, String name, HostedType[] parameterTypes) {
        /* Fill encoders with the necessary values. */
        encoders.sourceMethodNames.addObject(name);
        for (HostedType parameterType : parameterTypes) {
            encoders.sourceClasses.addObject(parameterType.getJavaClass());
        }

        sortedTypes.add(declaringType);
        methodData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(new MethodMetadata(declaringType, name, parameterTypes));
    }

    @Override
    public void addReachableMethodMetadata(HostedMethod method) {
        boolean isConstructor = method.isConstructor();
        HostedType declaringType = method.getDeclaringClass();
        String name = isConstructor ? null : method.getName();
        HostedType[] parameterTypes = getParameterTypes(method);

        /* Fill encoders with the necessary values. */
        if (!isConstructor) {
            encoders.sourceMethodNames.addObject(method.getName());
        }
        for (HostedType parameterType : parameterTypes) {
            encoders.sourceClasses.addObject(parameterType.getJavaClass());
        }

        sortedTypes.add(declaringType);
        if (isConstructor) {
            constructorData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(new ConstructorMetadata(declaringType, parameterTypes));
        } else {
            methodData.computeIfAbsent(declaringType, t -> new HashSet<>()).add(new MethodMetadata(declaringType, name, parameterTypes));
        }
    }

    private static HostedType[] getParameterTypes(HostedMethod method) {
        HostedType[] parameterTypes = new HostedType[method.getSignature().getParameterCount(false)];
        for (int i = 0; i < parameterTypes.length; ++i) {
            parameterTypes[i] = (HostedType) method.getSignature().getParameterType(i, null);
        }
        return parameterTypes;
    }

    private static HostedType[] getExceptionTypes(MetaAccessProvider metaAccess, Executable reflectMethod) {
        Class<?>[] exceptionClasses = reflectMethod.getExceptionTypes();
        HostedType[] exceptionTypes = new HostedType[exceptionClasses.length];
        for (int i = 0; i < exceptionClasses.length; ++i) {
            exceptionTypes[i] = (HostedType) metaAccess.lookupJavaType(exceptionClasses[i]);
        }
        return exceptionTypes;
    }

    private static ReflectParameterDescriptor[] getReflectParameters(Executable reflectMethod) {
        Parameter[] reflectParameters = reflectMethod.getParameters();
        ReflectParameterDescriptor[] reflectParameterDescriptors = new ReflectParameterDescriptor[reflectParameters.length];
        for (int i = 0; i < reflectParameters.length; ++i) {
            reflectParameterDescriptors[i] = new ReflectParameterDescriptor(reflectParameters[i].getName(), reflectParameters[i].getModifiers());
        }
        return reflectParameterDescriptors;
    }

    /**
     * See {@link MethodMetadataDecoderImpl} for the encoding format description.
     */
    @Override
    public void encodeAllAndInstall() {
        int metadataByteLength = 0;
        for (HostedType declaringType : sortedTypes) {
            metadataByteLength += encodeAndInstall(declaringType, fieldData, this::encodeField, DynamicHub::setFieldsEncoding);
            metadataByteLength += encodeAndInstall(declaringType, methodData, this::encodeMethod, DynamicHub::setMethodsEncoding);
            metadataByteLength += encodeAndInstall(declaringType, constructorData, this::encodeConstructor, DynamicHub::setConstructorsEncoding);
        }
        ProgressReporter.singleton().setMetadataByteLength(metadataByteLength);
    }

    @SuppressWarnings("unchecked")
    private <T> int encodeAndInstall(HostedType declaringType, Map<HostedType, Set<T>> data, BiConsumer<UnsafeArrayTypeWriter, T> encodeCallback, BiConsumer<DynamicHub, byte[]> saveCallback) {
        UnsafeArrayTypeWriter encodingBuffer = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        Set<T> elements = data.getOrDefault(declaringType, Collections.emptySet());
        encodeArray(encodingBuffer, elements.toArray(), element -> encodeCallback.accept(encodingBuffer, (T) element));
        int encodingSize = TypeConversion.asS4(encodingBuffer.getBytesWritten());
        byte[] dataEncoding = new byte[encodingSize];
        saveCallback.accept(declaringType.getHub(), encodingBuffer.toArray(dataEncoding));
        return encodingSize;
    }

    private void encodeField(UnsafeArrayTypeWriter buf, FieldMetadata field) {
        buf.putUV(field.modifiers);
        encodeName(buf, field.name);
        encodeType(buf, field.type);
        buf.putU1(field.trustedFinal ? 1 : 0);
        encodeName(buf, field.signature);
        encodeByteArray(buf, encodeAnnotations(field.annotations));
        encodeByteArray(buf, encodeTypeAnnotations(field.typeAnnotations));
    }

    private void encodeMethod(UnsafeArrayTypeWriter buf, MethodMetadata method) {
        buf.putUV(method.modifiers | ((method.complete ? 1 : 0) << 31));
        encodeName(buf, method.name);
        encodeArray(buf, method.parameterTypes, parameterType -> encodeType(buf, parameterType));
        if (method.complete) {
            encodeType(buf, method.returnType);
            encodeArray(buf, method.exceptionTypes, exceptionType -> encodeType(buf, exceptionType));
            encodeName(buf, method.signature);
            encodeByteArray(buf, encodeAnnotations(method.annotations));
            encodeByteArray(buf, encodeParameterAnnotations(method.parameterAnnotations));
            encodeByteArray(buf, encodeTypeAnnotations(method.typeAnnotations));
            buf.putU1(method.hasRealParameterData ? 1 : 0);
            if (method.hasRealParameterData) {
                encodeArray(buf, method.reflectParameters, reflectParameter -> {
                    encodeName(buf, reflectParameter.getName());
                    buf.putS4(reflectParameter.getModifiers());
                });
            }
            encodeObject(buf, method.accessor);
        }
    }

    private void encodeConstructor(UnsafeArrayTypeWriter buf, ConstructorMetadata constructor) {
        buf.putUV(constructor.modifiers);
        encodeArray(buf, constructor.parameterTypes, parameterType -> encodeType(buf, parameterType));
        if (constructor.complete) {
            encodeArray(buf, constructor.exceptionTypes, exceptionType -> encodeType(buf, exceptionType));
            encodeName(buf, constructor.signature);
            encodeByteArray(buf, encodeAnnotations(constructor.annotations));
            encodeByteArray(buf, encodeParameterAnnotations(constructor.parameterAnnotations));
            encodeByteArray(buf, encodeTypeAnnotations(constructor.typeAnnotations));
            buf.putU1(constructor.hasRealParameterData ? 1 : 0);
            if (constructor.hasRealParameterData) {
                encodeArray(buf, constructor.reflectParameters, reflectParameter -> {
                    encodeName(buf, reflectParameter.getName());
                    buf.putS4(reflectParameter.getModifiers());
                });
            }
            encodeObject(buf, constructor.accessor);
        }
    }

    private void encodeType(UnsafeArrayTypeWriter buf, HostedType type) {
        buf.putSV(encoders.sourceClasses.getIndex(type.getJavaClass()));
    }

    private void encodeName(UnsafeArrayTypeWriter buf, String name) {
        buf.putSV(encoders.sourceMethodNames.getIndex(name));
    }

    private void encodeObject(UnsafeArrayTypeWriter buf, JavaConstant object) {
        if (object == null) {
            buf.putSV(NULL_OBJECT);
        } else {
            buf.putSV(encoders.objectConstants.getIndex(object));
        }
    }

    private static <T> void encodeArray(UnsafeArrayTypeWriter buf, T[] array, Consumer<T> elementEncoder) {
        buf.putUV(array.length);
        for (T elem : array) {
            elementEncoder.accept(elem);
        }
    }

    private static void encodeByteArray(UnsafeArrayTypeWriter buf, byte[] array) {
        buf.putUV(array.length);
        for (byte b : array) {
            buf.putS1(b);
        }
    }

    private static final Method getFieldSignature = ReflectionUtil.lookupMethod(Field.class, "getGenericSignature");
    private static final Method getMethodSignature = ReflectionUtil.lookupMethod(Method.class, "getGenericSignature");
    private static final Method getConstructorSignature = ReflectionUtil.lookupMethod(Constructor.class, "getSignature");

    private static String getSignature(Field field) {
        try {
            return (String) getFieldSignature.invoke(field);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw GraalError.shouldNotReachHere();
        }
    }

    private static String getSignature(Executable method) {
        try {
            return (String) (method instanceof Method ? getMethodSignature.invoke(method) : getConstructorSignature.invoke(method));
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw GraalError.shouldNotReachHere();
        }
    }

    /**
     * The following methods encode annotations attached to a method or parameter in a format based
     * on the one used internally by the JDK ({@link sun.reflect.annotation.AnnotationParser}). The
     * format we use differs from that one on a few points, based on the fact that the JDK encoding
     * is based on constant pool indices, which are not available in that form at runtime.
     *
     * Class and String values are represented by their index in the source metadata encoders
     * instead of their constant pool indices. Additionally, Class objects are encoded directly
     * instead of through their type signature. Primitive values are written directly into the
     * encoding. This means that our encoding can be of a different length from the JDK one.
     *
     * We use a modified version of the ConstantPool and AnnotationParser classes to decode the
     * data, since those are not used in their original functions at runtime. (see
     * {@link Target_jdk_internal_reflect_ConstantPool} and
     * {@link Target_sun_reflect_annotation_AnnotationParser})
     */
    public byte[] encodeAnnotations(Annotation[] annotations) {
        UnsafeArrayTypeWriter buf = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        buf.putU2(annotations.length);
        for (Annotation annotation : annotations) {
            encodeAnnotation(buf, annotation);
        }
        return buf.toArray();
    }

    private byte[] encodeParameterAnnotations(Annotation[][] annotations) {
        UnsafeArrayTypeWriter buf = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        buf.putU1(annotations.length);
        for (Annotation[] parameterAnnotations : annotations) {
            buf.putU2(parameterAnnotations.length);
            for (Annotation parameterAnnotation : parameterAnnotations) {
                encodeAnnotation(buf, parameterAnnotation);
            }
        }
        return buf.toArray();
    }

    private void encodeAnnotation(UnsafeArrayTypeWriter buf, Annotation annotation) {
        buf.putS4(encoders.sourceClasses.getIndex(annotation.annotationType()));
        AnnotationType type = AnnotationType.getInstance(annotation.annotationType());
        buf.putU2(type.members().size());
        for (Map.Entry<String, Method> entry : type.members().entrySet()) {
            String memberName = entry.getKey();
            Method valueAccessor = entry.getValue();
            buf.putS4(encoders.sourceMethodNames.getIndex(memberName));
            try {
                encodeValue(buf, valueAccessor.invoke(annotation), type.memberTypes().get(memberName));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw GraalError.shouldNotReachHere();
            }
        }
    }

    private void encodeValue(UnsafeArrayTypeWriter buf, Object value, Class<?> type) {
        buf.putU1(tag(type));
        if (type.isAnnotation()) {
            encodeAnnotation(buf, (Annotation) value);
        } else if (type.isEnum()) {
            buf.putS4(encoders.sourceClasses.getIndex(type));
            buf.putS4(encoders.sourceMethodNames.getIndex(((Enum<?>) value).name()));
        } else if (type.isArray()) {
            encodeArray(buf, value, type.getComponentType());
        } else if (type == Class.class) {
            buf.putS4(encoders.sourceClasses.getIndex((Class<?>) value));
        } else if (type == String.class) {
            buf.putS4(encoders.sourceMethodNames.getIndex((String) value));
        } else if (type.isPrimitive() || Wrapper.isWrapperType(type)) {
            Wrapper wrapper = type.isPrimitive() ? Wrapper.forPrimitiveType(type) : Wrapper.forWrapperType(type);
            switch (wrapper) {
                case BOOLEAN:
                    buf.putU1((boolean) value ? 1 : 0);
                    break;
                case BYTE:
                    buf.putS1((byte) value);
                    break;
                case SHORT:
                    buf.putS2((short) value);
                    break;
                case CHAR:
                    buf.putU2((char) value);
                    break;
                case INT:
                    buf.putS4((int) value);
                    break;
                case LONG:
                    buf.putS8((long) value);
                    break;
                case FLOAT:
                    buf.putS4(Float.floatToRawIntBits((float) value));
                    break;
                case DOUBLE:
                    buf.putS8(Double.doubleToRawLongBits((double) value));
                    break;
                default:
                    throw GraalError.shouldNotReachHere();
            }
        } else {
            throw GraalError.shouldNotReachHere();
        }
    }

    private void encodeArray(UnsafeArrayTypeWriter buf, Object value, Class<?> componentType) {
        if (!componentType.isPrimitive()) {
            Object[] array = (Object[]) value;
            buf.putU2(array.length);
            for (Object val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == boolean.class) {
            boolean[] array = (boolean[]) value;
            buf.putU2(array.length);
            for (boolean val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == byte.class) {
            byte[] array = (byte[]) value;
            buf.putU2(array.length);
            for (byte val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == short.class) {
            short[] array = (short[]) value;
            buf.putU2(array.length);
            for (short val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == char.class) {
            char[] array = (char[]) value;
            buf.putU2(array.length);
            for (char val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == int.class) {
            int[] array = (int[]) value;
            buf.putU2(array.length);
            for (int val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == long.class) {
            long[] array = (long[]) value;
            buf.putU2(array.length);
            for (long val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == float.class) {
            float[] array = (float[]) value;
            buf.putU2(array.length);
            for (float val : array) {
                encodeValue(buf, val, componentType);
            }
        } else if (componentType == double.class) {
            double[] array = (double[]) value;
            buf.putU2(array.length);
            for (double val : array) {
                encodeValue(buf, val, componentType);
            }
        }
    }

    private static byte tag(Class<?> type) {
        if (type.isAnnotation()) {
            return '@';
        } else if (type.isEnum()) {
            return 'e';
        } else if (type.isArray()) {
            return '[';
        } else if (type == Class.class) {
            return 'c';
        } else if (type == String.class) {
            return 's';
        } else if (type.isPrimitive()) {
            return (byte) Wrapper.forPrimitiveType(type).basicTypeChar();
        } else if (Wrapper.isWrapperType(type)) {
            return (byte) Wrapper.forWrapperType(type).basicTypeChar();
        } else {
            throw GraalError.shouldNotReachHere();
        }
    }

    private byte[] encodeTypeAnnotations(TypeAnnotation[] annotations) {
        UnsafeArrayTypeWriter buf = UnsafeArrayTypeWriter.create(ByteArrayReader.supportsUnalignedMemoryAccess());
        buf.putU2(annotations.length);
        for (TypeAnnotation typeAnnotation : annotations) {
            encodeTypeAnnotation(buf, typeAnnotation);
        }
        return buf.toArray();
    }

    private void encodeTypeAnnotation(UnsafeArrayTypeWriter buf, TypeAnnotation typeAnnotation) {
        encodeTargetInfo(buf, typeAnnotation.getTargetInfo());
        encodeLocationInfo(buf, typeAnnotation.getLocationInfo());
        // Checkstyle: allow direct annotation access
        encodeAnnotation(buf, typeAnnotation.getAnnotation());
        // Checkstyle: disallow direct annotation access
    }

    private static final byte CLASS_TYPE_PARAMETER = 0x00;
    private static final byte METHOD_TYPE_PARAMETER = 0x01;
    private static final byte CLASS_EXTENDS = 0x10;
    private static final byte CLASS_TYPE_PARAMETER_BOUND = 0x11;
    private static final byte METHOD_TYPE_PARAMETER_BOUND = 0x12;
    private static final byte FIELD = 0x13;
    private static final byte METHOD_RETURN = 0x14;
    private static final byte METHOD_RECEIVER = 0x15;
    private static final byte METHOD_FORMAL_PARAMETER = 0x16;
    private static final byte THROWS = 0x17;

    private static void encodeTargetInfo(UnsafeArrayTypeWriter buf, TypeAnnotation.TypeAnnotationTargetInfo targetInfo) {
        switch (targetInfo.getTarget()) {
            case CLASS_TYPE_PARAMETER:
                buf.putU1(CLASS_TYPE_PARAMETER);
                buf.putU1(targetInfo.getCount());
                break;
            case METHOD_TYPE_PARAMETER:
                buf.putU1(METHOD_TYPE_PARAMETER);
                buf.putU1(targetInfo.getCount());
                break;
            case CLASS_EXTENDS:
                buf.putU1(CLASS_EXTENDS);
                buf.putS2(-1);
                break;
            case CLASS_IMPLEMENTS:
                buf.putU1(CLASS_EXTENDS);
                buf.putS2(targetInfo.getCount());
                break;
            case CLASS_TYPE_PARAMETER_BOUND:
                buf.putU1(CLASS_TYPE_PARAMETER_BOUND);
                buf.putU1(targetInfo.getCount());
                buf.putU1(targetInfo.getSecondaryIndex());
                break;
            case METHOD_TYPE_PARAMETER_BOUND:
                buf.putU1(METHOD_TYPE_PARAMETER_BOUND);
                buf.putU1(targetInfo.getCount());
                buf.putU1(targetInfo.getSecondaryIndex());
                break;
            case FIELD:
                buf.putU1(FIELD);
                break;
            case METHOD_RETURN:
                buf.putU1(METHOD_RETURN);
                break;
            case METHOD_RECEIVER:
                buf.putU1(METHOD_RECEIVER);
                break;
            case METHOD_FORMAL_PARAMETER:
                buf.putU1(METHOD_FORMAL_PARAMETER);
                buf.putU1(targetInfo.getCount());
                break;
            case THROWS:
                buf.putU1(THROWS);
                buf.putU2(targetInfo.getCount());
                break;
        }
    }

    private static final Field locationInfoDepth = ReflectionUtil.lookupField(TypeAnnotation.LocationInfo.class, "depth");
    private static final Field locationInfoLocations = ReflectionUtil.lookupField(TypeAnnotation.LocationInfo.class, "locations");

    private static void encodeLocationInfo(UnsafeArrayTypeWriter buf, TypeAnnotation.LocationInfo locationInfo) {
        try {
            int depth = (int) locationInfoDepth.get(locationInfo);
            buf.putU1(depth);
            TypeAnnotation.LocationInfo.Location[] locations;
            locations = (TypeAnnotation.LocationInfo.Location[]) locationInfoLocations.get(locationInfo);
            for (TypeAnnotation.LocationInfo.Location location : locations) {
                buf.putS1(location.tag);
                buf.putU1(location.index);
            }
        } catch (IllegalAccessException e) {
            throw GraalError.shouldNotReachHere();
        }
    }
}
